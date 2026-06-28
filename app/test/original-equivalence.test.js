// Vérifie que l'application reproduit EXACTEMENT les résultats du classeur
// d'origine sur ses 43 tronçons réels — y compris le calcul automatique des
// hauteurs de remblai (AP, AQ, BJ, BK) selon le mode terres/empierrement
// détecté dans l'Excel. Aucune valeur AP/BJ n'est réinjectée : elles sont
// recalculées par l'application à partir du seul mode.
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
  // Pilotage par le mode uniquement (pas de valeur AP/BJ réinjectée)
  g.remblaiModeCable = ref.modeCable; g.remblaiModeConduite = ref.modeConduite;
  g.remblaiSousFondCable = 0; g.remblaiSousFondConduite = 0;
  return row;
}

// Toujours identiques (largeurs, volume principal, hauteurs de remblai auto)
const ALL_KEYS = ['AC', 'AR', 'AS', 'AT', 'BL', 'BM', 'BN', 'AP', 'AQ', 'BJ', 'BK', 'AZ', 'AX'];
// Volumes dépendant du gabarit standard (exclus pour les 12 lignes manuelles)
const STD_KEYS = ['AW', 'AY', 'BP', 'BQ', 'BR', 'BS', 'BT'];

let af = 0, ac = 0, sf = 0, sc = 0, sumBT = 0, sumBTexp = 0;
rows.forEach(ref => {
  const c = M.computeRowWithLayout(project, makeRow(ref), L);
  ALL_KEYS.forEach(k => { ac++; if (Math.abs(c[k] - ref.exp[k]) > 1e-6) { af++; console.log(`  [tous] ligne ${ref.r} ${k}: got ${c[k]} want ${ref.exp[k]} ✗`); } });
  if (ref.standard) {
    sumBT += c.BT; sumBTexp += ref.exp.BT;
    STD_KEYS.forEach(k => { sc++; if (Math.abs(c[k] - ref.exp[k]) > 1e-6) { sf++; console.log(`  [std] ligne ${ref.r} ${k}: got ${c[k]} want ${ref.exp[k]} ✗`); } });
  }
});
const nStd = rows.filter(r => r.standard).length;
console.log(`Largeurs + remblai auto (AP/AQ/BJ/BK) + volume principal : ${ac - af}/${ac} identiques sur les ${rows.length} tronçons`);
console.log(`Volumes sable/conduites : ${sc - sf}/${sc} identiques sur les ${nStd} tronçons au gabarit standard`);
console.log(`Volume total cumulé (lignes standard) : app=${sumBT.toFixed(3)} m³  vs  Excel=${sumBTexp.toFixed(3)} m³`);
console.log(`(${rows.length - nStd} tronçons de l'original utilisent des formules manuelles hors gabarit, exclus des volumes.)`);
assert.strictEqual(af, 0, 'Écart sur largeurs / remblai auto / volume principal');
assert.strictEqual(sf, 0, 'Écart de volume sur une ligne au gabarit standard');
console.log('ÉQUIVALENCE 100% CONFIRMÉE (remblai auto terres/empierrement + interstices) ✓');
