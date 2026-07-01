const M = require('../src/model.js');
const ExcelJS = require('exceljs');
const assert = require('assert');
const path = require('path');

// Valeurs de référence issues du classeur d'origine (lignes 5 et 30), exprimées
// indépendamment de la disposition des colonnes : largeurs par canal dans
// l'ordre du layout (câbles puis conduites) et somme des interstices par
// catégorie (le calcul ne dépend que de la somme des interstices).
const REF = {
  5: {
    E: 165, AD: 1.6,
    cableCh: [0, 0.05, 0.05, 0.1, 0, 0, 0.12, 0, 0, 0.06, 0.05], // PROXIMUS DP/DD/E, SOFICO E, ELEC MT DP/DD/E, ELEC BT DP/DD/E/Écl
    conduiteCh: [0, 0, 0.12, 0.16],                               // EAU DP/DD/E, Gaz E
    cableIntSum: 0.37, conduiteIntSum: 0.5,
    geom: { AG: 0.1, AH: 0.12, AI: 0.1, AJ: 'OUI', AL: 0.8, AM: 0.3, AP: 0, AU: 0, BA: 0.1, BC: 0.2, BF: 1, BJ: 0 },
    exp: { AC: 1.58, AT: 0.8, AR: 0.43, AS: 0.37, BB: 0.16, BL: 0.28, BM: 0.5, BN: 0.78, AK: 0.1, AN: 0.72, AO: 0.32, AQ: 0.4,
           AV: 42.24, AW: 42.24, AX: 0, AY: 42.24, AZ: 95.04, BE: 0.3, BH: 0.96, BI: 0.56, BK: 0.4, BO: 72.072, BP: 66.88837212157685, BQ: 0, BR: 72.072, BS: 123.552, BT: 218.592, AE: '' }
  },
  30: {
    E: 20, AD: 0,
    cableCh: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    conduiteCh: [0, 0.12, 0, 0], // EAU DP=0, DD=0.12, E=0, Gaz=0
    cableIntSum: 0, conduiteIntSum: 0.7,
    geom: { AG: 0.1, AH: 0.05, AI: 0.2, AJ: 'OUI', AL: 1.2, AM: 0.65, AP: 0.35, AU: 0, BA: 0.1, BC: 0.2, BF: 1.2, BJ: 0.35 },
    exp: { AC: 0.82, AT: 0, AR: 0, AS: 0, BB: 0.12, BL: 0.12, BM: 0.7, BN: 0.82, AK: 0.2, AN: 0.7, AO: 0.35, AQ: 0,
           AV: 0, AW: 0, AX: 0, AY: 0, AZ: 0, BE: 0.2, BH: 0.77, BI: 0.42, BK: 0, BO: 6.888, BP: 6.661805328941535, BQ: 5.74, BR: 12.628, BS: 12.628, BT: 12.628, AE: '' }
  }
};

function makeRow(project, ref) {
  const L = M.buildLayout(project);
  const row = M.newRow(project);
  row.longueur = ref.E; row.largeurMax = ref.AD;
  L.cableChannelCols.forEach((c, i) => { row.widths[c.srId] = ref.cableCh[i] || 0; });
  L.conduiteChannelCols.forEach((c, i) => { row.widths[c.srId] = ref.conduiteCh[i] || 0; });
  // Place la somme des interstices sur l'interstice de fin de chaque catégorie.
  const lastCableInt = L.cableIntCols[L.cableIntCols.length - 1];
  const lastCondInt = L.conduiteIntCols[L.conduiteIntCols.length - 1];
  if (lastCableInt) row.interstices[lastCableInt.intKey] = ref.cableIntSum;
  if (lastCondInt) row.interstices[lastCondInt.intKey] = ref.conduiteIntSum;
  const g = row.geom, r = ref.geom;
  g.litPoseCable = r.AG; g.htMoyCable = r.AH; g.recouvSableMinCable = r.AI; g.ligneAligne = r.AJ;
  g.recouvNiveauFiniCable = r.AL; g.hauteurCoffre = r.AM; g.remblaiSousFondCable = r.AP; g.longueurGaines = r.AU;
  g.litPoseConduite = r.BA; g.recouvSableMinConduite = r.BC; g.recouvNiveauFiniConduite = r.BF; g.remblaiSousFondConduite = r.BJ;
  g.remblaiModeCable = 'manuel'; g.remblaiModeConduite = 'manuel';
  return row;
}

let passed = 0, failed = 0;
const project = M.defaultProject();
Object.keys(REF).forEach(k => {
  const ref = REF[k];
  const c = M.computeRow(project, makeRow(project, ref));
  Object.keys(ref.exp).forEach(key => {
    const got = c[key], want = ref.exp[key];
    const ok = (typeof want === 'number') ? Math.abs(got - want) < 1e-6 : got === want;
    if (ok) passed++; else { failed++; console.log(`  ROW ${k} ${key}: got ${got} want ${want}  ✗`); }
  });
});
console.log(`Moteur de calcul : ${passed} OK, ${failed} KO`);
assert.strictEqual(failed, 0, 'Des écarts de calcul détectés');

// Cohérence de la répartition : somme des parts catégorie = 1 (largeur occupée > 0),
// somme des parts totales = AZ/BT + BS/BT = 1, somme des volumes attribués = BT.
(function () {
  const rep = M.computeRepartition(project, makeRow(project, REF[5]));
  const cableParts = rep.channels.filter(c => c.category === 'cable').reduce((s, c) => s + c.partCat, 0);
  const totParts = rep.channels.reduce((s, c) => s + c.partTot, 0);
  const volTot = rep.channels.reduce((s, c) => s + c.volTranchee, 0);
  assert.ok(Math.abs(cableParts - 1) < 1e-9, 'somme parts câbles = 1');
  assert.ok(Math.abs(totParts - 1) < 1e-9, 'somme parts totales = 1');
  assert.ok(Math.abs(volTot - rep.row.BT) < 1e-6, 'somme volumes attribués = BT');
  console.log('Répartition : cohérence parts/volumes OK');
})();

// Génération Excel
project.rows = [makeRow(project, REF[5]), makeRow(project, REF[30])];
const wb = M.buildWorkbook(project, ExcelJS);
const outPath = path.join(__dirname, 'out.xlsx');
wb.xlsx.writeFile(outPath).then(async () => {
  const wb2 = new ExcelJS.Workbook();
  await wb2.xlsx.readFile(outPath);
  const ws = wb2.getWorksheet('Gabarits tranchées communes');
  const L = M.buildLayout(project);
  const lt = (role) => L.roles[role].letter;
  const get = (addr) => { const c = ws.getCell(addr); return c.formula || (c.value && c.value.formula) || c.value; };
  const checks = {};
  checks[lt('AC') + '5'] = lt('AT') + '5+' + lt('BN') + '5';
  checks[lt('AT') + '5'] = lt('AR') + '5+' + lt('AS') + '5';
  checks[lt('BT') + '5'] = lt('AZ') + '5+' + lt('BS') + '5';
  let fp = 0, ff = 0;
  Object.keys(checks).forEach(addr => {
    const f = get(addr);
    if (f === checks[addr]) fp++; else { ff++; console.log(`  FORMULE ${addr}: got '${f}' want '${checks[addr]}' ✗`); }
  });
  assert.strictEqual(ws.getCell('E5').value, 165, 'E5 longueur');
  console.log(`Formules Excel : ${fp} OK, ${ff} KO`);
  assert.strictEqual(ff, 0);

  // Ligne TOTAL du gabarit (2 lignes de données -> TOTAL ligne 7)
  assert.strictEqual(ws.getCell('A7').value, 'TOTAL', 'ligne TOTAL');
  assert.strictEqual(get('E7'), 'SUM(E5:E6)', 'somme des longueurs');

  // Onglet Synthèse : présent, avec clé de répartition en SUMPRODUCT
  const sy = wb2.getWorksheet('Synthèse');
  assert.ok(sy, 'onglet Synthèse');
  let foundKey = false;
  sy.eachRow(row => row.eachCell(cell => {
    const f = cell.formula || (cell.value && cell.value.formula);
    if (f && /^IFERROR\(SUMPRODUCT\('Gabarits tranchées communes'!/.test(f)) foundKey = true;
  }));
  assert.ok(foundKey, 'clé de répartition (SUMPRODUCT) dans la Synthèse');
  console.log('Synthèse : onglet + clé de répartition OK');
  console.log('TOUS LES TESTS PASSENT ✓');
}).catch(e => { console.error(e); process.exit(1); });
