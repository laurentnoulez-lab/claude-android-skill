/*
 * Version 2.0 — non-régression par rapport à la version 1 et nouveautés.
 *
 * test/fixtures/model-v1.js est le moteur de la version 1 tel que publié
 * (commit d252ef7). Sur des projets tirés au hasard, la 2.0 doit donner
 * EXACTEMENT les mêmes valeurs, les mêmes répartitions et les mêmes cellules
 * Excel que la 1.x pour tout ce qui existait déjà.
 */
const assert = require('assert');
const ExcelJS = require('exceljs');
const V1 = require('./fixtures/model-v1.js');
const V2 = require('../src/model.js');

let checks = 0;
const ok = (cond, msg) => { checks++; assert.ok(cond, msg); };
const same = (a, b, msg) => { checks++; assert.ok(Object.is(a, b) || (typeof a === 'number' && typeof b === 'number' && Math.abs(a - b) <= 1e-12 * Math.max(1, Math.abs(a), Math.abs(b))), `${msg} : v1=${a} v2=${b}`); };

// Générateur déterministe
let seed = 20261113;
const rnd = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
const pick = (a) => a[Math.floor(rnd() * a.length)];
const r2 = (max) => Math.round(rnd() * max * 100) / 100;

function randomProject() {
  const p = V1.defaultProject();
  if (rnd() < 0.3) p.concessionnaires.pop();
  if (rnd() < 0.3) p.concessionnaires.push({ id: V1.uid('conc'), name: 'TELENET', category: pick(['cable', 'conduite']), sousReseaux: [{ id: V1.uid('sr'), label: 'E' }, { id: V1.uid('sr'), label: 'DD' }] });
  const L = V1.buildLayout(p);
  const n = 1 + Math.floor(rnd() * 6);
  for (let i = 0; i < n; i++) {
    const row = V1.newRow(p);
    row.de = 'P' + i; row.a = 'P' + (i + 1); row.type = pick(['A', 'B', 'C', '']); row.commentaire = pick(['', 'Traversée']);
    row.longueur = r2(300); row.largeurMax = pick([0, 0, 1.2, 2]);
    L.cols.forEach((col) => {
      if (col.isChannel) row.widths[col.srId] = rnd() < 0.5 ? 0 : r2(col.category === 'cable' ? 0.4 : 0.5);
      if (col.isInterstice) row.interstices[col.intKey] = rnd() < 0.5 ? 0 : r2(0.3);
    });
    const g = row.geom;
    g.litPoseCable = r2(0.2); g.htMoyCable = r2(0.2); g.recouvSableMinCable = r2(0.3); g.ligneAligne = pick(['OUI', 'NON']);
    g.recouvNiveauFiniCable = 0.5 + r2(1); g.hauteurCoffre = r2(0.7);
    g.remblaiModeCable = pick(['terres', 'empierrement', 'manuel']); g.remblaiSousFondCable = r2(0.4);
    g.gainesCables = pick(['NON', 'NON', 'OUI']); g.diamGaine = pick([0.11, 0.16, 0.2]); g.longueurGaines = r2(100);
    g.litPoseConduite = r2(0.2); g.recouvSableMinConduite = r2(0.3); g.recouvNiveauFiniConduite = 0.6 + r2(1);
    g.remblaiModeConduite = pick(['terres', 'empierrement', 'manuel']); g.remblaiSousFondConduite = r2(0.4);
    g.htConduiteMode = pick(['auto', 'auto', 'manuel']); g.htConduiteManuelle = r2(0.4);
    p.rows.push(row);
  }
  return p;
}

// ---- 1. Moteur : valeurs, répartition, totaux identiques à la version 1
let nbRows = 0;
const projects = [];
for (let k = 0; k < 60; k++) {
  const p1 = randomProject();
  const p2 = V2.migrateProject(JSON.parse(JSON.stringify(p1)));     // tel qu'importé dans la 2.0
  projects.push([p1, p2]);
  const L1 = V1.buildLayout(p1), L2 = V2.buildLayout(p2);
  p1.rows.forEach((row1, i) => {
    nbRows++;
    const c1 = V1.computeRowWithLayout(p1, row1, L1), c2 = V2.computeRowWithLayout(p2, p2.rows[i], L2);
    Object.keys(c1).forEach((key) => same(c1[key], c2[key], `projet ${k} tronçon ${i} ${key}`));
    const rep1 = V1.computeRepartition(p1, row1, L1).channels, rep2 = V2.computeRepartition(p2, p2.rows[i], L2).channels;
    same(rep1.length, rep2.length, 'nombre de canaux');
    rep1.forEach((ch, j) => Object.keys(ch).forEach((key) => same(ch[key], rep2[j][key], `répartition ${k}/${i}/${j} ${key}`)));
    // la nature de la couche de surface ne change aucune valeur héritée de la v1
    const tv = JSON.parse(JSON.stringify(p2.rows[i])); tv.geom.surfaceType = 'terreVegetale';
    const c3 = V2.computeRowWithLayout(p2, tv, L2);
    Object.keys(c1).forEach((key) => same(c1[key], c3[key], `terre végétale ${k}/${i} ${key}`));
    same(c3.SV, c2.SV, 'volume de surface indépendant de la nature');
    same(c2.SV, c2.AF * V2.num(row1.geom.hauteurCoffre) * c2.AC, 'SV = AF × AM × AC');
  });
  const t1 = V1.projectTotals(p1), t2 = V2.projectTotals(p2);
  Object.keys(t1).forEach((key) => same(t1[key], t2[key], `totaux ${k} ${key}`));
}
console.log(`Moteur : ${nbRows} tronçons aléatoires sur 60 projets — valeurs, répartitions et totaux identiques à la v1 ✓`);

// ---- 2. Excel : toutes les cellules de données héritées sont identiques
function cellSig(c) { const v = c.value; return v && typeof v === 'object' && v.formula !== undefined ? '=' + v.formula : JSON.stringify(v); }
let nbCells = 0;
projects.slice(0, 25).forEach(([p1, p2], k) => {
  const w1 = V1.buildWorkbook(p1, ExcelJS).getWorksheet('Gabarits tranchées communes');
  const w2 = V2.buildWorkbook(p2, ExcelJS).getWorksheet('Gabarits tranchées communes');
  const L1 = V1.buildLayout(p1), L2 = V2.buildLayout(p2);
  const bt = L1.roles.BT.index;
  same(L2.roles.BT.index, bt, 'position de la colonne BT');
  // les deux colonnes de la 2.0 (nature + volume de surface) suivent BT : tout ce qui est à droite glisse de 2
  const shift = (f) => f.replace(/\$?([A-Z]{1,3})(\$?\d+)/g, (m, col, row) => {
    let n = 0; for (const ch of col) n = n * 26 + ch.charCodeAt(0) - 64;
    return n > bt ? m.replace(col, V2.colLetter(n + 2)) : m;
  });
  for (let R = V1.DATA_START; R <= V1.DATA_START + p1.rows.length; R++) {      // + ligne TOTAL
    L1.cols.forEach((col) => {
      const a = cellSig(w1.getCell(col.letter + R));
      const target = col.index > bt ? V2.colLetter(col.index + 2) : col.letter;
      const b = cellSig(w2.getCell(target + R));
      nbCells++; checks++;
      assert.strictEqual(b, col.index > bt ? shift(a) : shift(a), `Excel projet ${k} cellule ${col.letter}${R}`);
    });
  }
});
console.log(`Excel : ${nbCells} cellules (saisies, formules, répartition, totaux) identiques à la v1 ✓`);

// ---- 3. Nouvelles colonnes Excel et synthèse
{
  const p = V2.defaultProject();
  const a = V2.newRow(p), b = V2.newRow(p);
  const L = V2.buildLayout(p);
  [a, b].forEach((r) => { r.longueur = 100; r.widths[L.cableChannelCols[0].srId] = 0.5; r.widths[L.conduiteChannelCols[0].srId] = 0.5; });
  a.geom.hauteurCoffre = 0.3; b.geom.hauteurCoffre = 0.2; b.geom.surfaceType = 'terreVegetale';
  p.rows.push(a, b);
  const wb = V2.buildWorkbook(p, ExcelJS), ws = wb.getWorksheet('Gabarits tranchées communes');
  const sn = L.roles.SN.letter, sv = L.roles.SV.letter;
  ok(ws.getCell(sn + '5').value === 'Coffre' && ws.getCell(sn + '6').value === 'Terre végétale', 'nature de la couche de surface dans le classeur');
  ok(ws.getCell(sv + '5').value.formula === `${L.roles.AF.letter}5*${L.roles.AM.letter}5*${L.roles.AC.letter}5`, 'formule du volume de surface');
  ok(!/SPI/.test(JSON.stringify(L.cols.map((c) => c.h3))), 'plus aucune mention « SPI » dans les en-têtes');
  const syn = wb.getWorksheet('Synthèse'); let txt = '';
  syn.eachRow((row) => row.eachCell((c) => { txt += (c.value && c.value.formula ? c.value.formula : c.value) + '\n'; }));
  ok(/terre végétale/i.test(txt) && txt.includes('"Terre végétale"') && txt.includes('"Coffre"'), 'volumes de surface dans la synthèse');
  const t = V2.projectTotals(p);
  same(t.volCoffre, 100 * 0.3 * 1.0, 'volume de coffre'); same(t.volTerreVegetale, 100 * 0.2 * 1.0, 'volume de terre végétale');
  const rep = V2.projectRepartition(p);
  same(rep.channels.reduce((s, c) => s + c.cle, 0), 1, 'somme des clés de répartition');
  same(rep.channels.reduce((s, c) => s + c.volTranchee, 0), t.volTotal, 'somme des volumes attribués');
  same(V2.projectByType(p)[0].longueur, 200, 'longueur par type');
  console.log('Couche de surface : colonnes Excel, synthèse, totaux et clé de répartition ✓');
}

// ---- 4. Migration des projets de la version 1
{
  const v1p = V1.defaultProject(); v1p.rows.push(V1.newRow(v1p)); v1p.rows[0].geom.hauteurCoffre = 0.45;
  const before = JSON.stringify(v1p);
  const m = V2.migrateProject(JSON.parse(before));
  ok(m.version === 2 && m.rows[0].geom.surfaceType === 'coffre' && m.defaults.surfaceType === 'coffre', 'un projet v1 devient un projet 2.0 avec coffre');
  same(m.rows[0].geom.hauteurCoffre, 0.45, 'épaisseur conservée');
  const stripped = JSON.parse(JSON.stringify(m)); stripped.version = 1;
  [stripped.defaults, stripped.rows[0].geom].forEach((g) => delete g.surfaceType);
  ok(JSON.stringify(stripped) === before, 'aucune autre donnée modifiée par la migration');
  ok(V2.migrateProject(null).rows.length === 0 && V2.migrateProject({ concessionnaires: [] }).defaults.hauteurCoffre === 0.3, 'fichiers vides ou partiels acceptés');
  console.log('Migration v1 → 2.0 : sans perte ✓');
}

// ---- 5. Alertes de cohérence
{
  const p = V2.defaultProject(); const L = V2.buildLayout(p);
  const mk = () => { const r = V2.newRow(p); r.longueur = 50; r.widths[L.cableChannelCols[0].srId] = 0.2; r.widths[L.conduiteChannelCols[0].srId] = 0.2; return r; };
  const levels = (r) => V2.rowWarnings(p, r, L).map((w) => w.level);
  const has = (r, re) => V2.rowWarnings(p, r, L).some((w) => re.test(w.msg));
  ok(levels(mk()).length === 0, 'tronçon sain : aucune alerte');
  let r = mk(); r.longueur = 0; ok(has(r, /Longueur nulle/), 'longueur nulle');
  r = mk(); r.widths = {}; ok(has(r, /Aucune largeur/), 'aucune largeur');
  r = mk(); r.largeurMax = 0.3; ok(levels(r).includes('error') && has(r, /largeur max/), 'dépassement de largeur');
  r = mk(); r.geom.recouvNiveauFiniCable = 0.2; ok(has(r, /Câbles : remblai négatif/), 'remblai négatif câbles');
  r = mk(); r.geom.hauteurCoffre = 3; ok(has(r, /terrassement nulle ou négative/), 'couche de surface trop épaisse');
  r = mk(); r.widths[L.conduiteChannelCols[0].srId] = 20; ok(has(r, /vérifier l'unité/), 'valeur aberrante (virgule oubliée)');
  r = mk(); r.geom.gainesCables = 'OUI'; r.geom.diamGaine = 0; ok(has(r, /Ø de gaine est nul/), 'gaine sans diamètre');
  console.log('Alertes de cohérence ✓');
}

console.log(`\nTOUS LES TESTS 2.0 PASSENT ✓ (${checks} vérifications)`);
