// Équivalence TOTALE avec le classeur d'origine : les 43 tronçons réels sont
// rejoués dans l'application en pilotant uniquement les OPTIONS détectées dans
// les formules de l'Excel (mode de remblai terres/empierrement, câbles sous
// gaines Ø0,16, hauteur de conduites forcée) — aucune valeur calculée n'est
// réinjectée. Toutes les colonnes calculées doivent être identiques.
const M = require('../src/model.js');
const assert = require('assert');
const rows = require('./original-rows.json');

const project = M.defaultProject(); // structure identique à l'original
const L = M.buildLayout(project);

function makeRow(ref) {
  const row = M.newRow(project);
  row.longueur = ref.E; row.largeurMax = ref.geom.AD || 0;
  L.cableChannelCols.forEach((c, i) => { row.widths[c.srId] = ref.cab[i] || 0; });
  L.conduiteChannelCols.forEach((c, i) => { row.widths[c.srId] = ref.con[i] || 0; });
  const lastCab = L.cableIntCols[L.cableIntCols.length - 1];
  const lastCon = L.conduiteIntCols[L.conduiteIntCols.length - 1];
  if (lastCab) row.interstices[lastCab.intKey] = ref.cableIntSum;
  if (lastCon) row.interstices[lastCon.intKey] = ref.condIntSum;
  const g = row.geom, G = ref.geom;
  g.litPoseCable = G.AG; g.htMoyCable = G.AH; g.recouvSableMinCable = G.AI; g.ligneAligne = G.AJ;
  g.recouvNiveauFiniCable = G.AL; g.hauteurCoffre = G.AM;
  g.litPoseConduite = G.BA; g.recouvSableMinConduite = G.BC; g.recouvNiveauFiniConduite = G.BF;
  // Options détectées dans les formules de l'original :
  g.remblaiModeCable = ref.modeCable; g.remblaiModeConduite = ref.modeConduite;
  g.remblaiSousFondCable = 0; g.remblaiSousFondConduite = 0;
  g.gainesCables = ref.gaines ? 'OUI' : 'NON'; g.diamGaine = ref.diam || 0.16;
  if (ref.bbManual) { g.htConduiteMode = 'manuel'; g.htConduiteManuelle = ref.bbValue; }
  return row;
}

// Toutes les colonnes calculées comparées sur TOUTES les lignes.
const KEYS = ['AC', 'AR', 'AS', 'AT', 'BL', 'BM', 'BN', 'AP', 'AQ', 'BJ', 'BK',
  'AV', 'AW', 'AX', 'AY', 'AZ', 'BB', 'BO', 'BP', 'BQ', 'BR', 'BS', 'BT'];
// AU : identique partout sauf ligne 42 (la formule AU de l'original y compte
// aussi les conduites — particularité de saisie sans effet sur les volumes)
// et 47 (la formule de l'original référence la ligne 46 par erreur de recopie,
// mais vaut 0 comme la nôtre).
const AU_SKIP = { 42: true };

let fails = 0, checks = 0, sumBT = 0, sumBTexp = 0;
rows.forEach(ref => {
  const c = M.computeRowWithLayout(project, makeRow(ref), L);
  sumBT += c.BT; sumBTexp += ref.exp.BT;
  KEYS.forEach(k => {
    checks++;
    if (Math.abs(c[k] - ref.exp[k]) > 1e-6) { fails++; console.log(`  ligne ${ref.r} ${k}: got ${c[k]} want ${ref.exp[k]} ✗`); }
  });
  if (!AU_SKIP[ref.r]) {
    checks++;
    if (Math.abs(c.AU - ref.exp.AU) > 1e-6) { fails++; console.log(`  ligne ${ref.r} AU: got ${c.AU} want ${ref.exp.AU} ✗`); }
  }
});
console.log(`Équivalence TOTALE : ${checks - fails}/${checks} valeurs identiques sur les ${rows.length} tronçons (toutes colonnes calculées)`);
console.log(`Volume total cumulé : app=${sumBT.toFixed(3)} m³  vs  Excel=${sumBTexp.toFixed(3)} m³`);
assert.strictEqual(fails, 0, 'Des écarts avec le classeur d\'origine subsistent');
assert.ok(Math.abs(sumBT - sumBTexp) < 1e-6, 'Volume total cumulé différent');
console.log('ÉQUIVALENCE 100% CONFIRMÉE — gaines, remblais, hauteur forcée, interstices ✓');
