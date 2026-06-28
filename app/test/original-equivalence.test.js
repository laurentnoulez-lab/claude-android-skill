// Vérifie que l'application reproduit EXACTEMENT les résultats du classeur
// d'origine sur ses 43 tronçons réels, lorsqu'on place les interstices comme
// dans l'Excel (somme par catégorie). Prouve l'équivalence du moteur de calcul.
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
  // Interstices : on dépose la somme de chaque catégorie sur l'interstice de fin
  // (le calcul ne dépend que de la somme des interstices par catégorie).
  const lastCab = L.cableIntCols[L.cableIntCols.length - 1];
  const lastCon = L.conduiteIntCols[L.conduiteIntCols.length - 1];
  if (lastCab) row.interstices[lastCab.intKey] = ref.cableIntSum;
  if (lastCon) row.interstices[lastCon.intKey] = ref.condIntSum;
  const g = row.geom, G = ref.geom;
  g.litPoseCable = G.AG; g.htMoyCable = G.AH; g.recouvSableMinCable = G.AI; g.ligneAligne = G.AJ;
  g.recouvNiveauFiniCable = G.AL; g.hauteurCoffre = G.AM; g.remblaiSousFondCable = G.AP; g.longueurGaines = G.AU;
  g.litPoseConduite = G.BA; g.recouvSableMinConduite = G.BC; g.recouvNiveauFiniConduite = G.BF; g.remblaiSousFondConduite = G.BJ;
  g.remblaiModeCable = 'manuel'; g.remblaiModeConduite = 'manuel';
  return row;
}

// Clés « largeurs + volume principal » : doivent correspondre sur TOUS les
// tronçons (preuve directe que la gestion des interstices est équivalente).
const WIDTH_KEYS = ['AC', 'AR', 'AS', 'AT', 'BL', 'BM', 'BN', 'AZ'];
// Clés « volumes sable / conduites » : ne valent que pour les lignes au gabarit
// standard. 12 lignes de l'original (traversées de chaussée) emploient des
// formules saisies à la main (déduction des fourreaux dans AW ; hauteur de
// conduite BB forcée), hors logique du gabarit — donc volontairement exclues.
const VOL_KEYS = ['AW', 'BS', 'BT', 'BP'];

let wf = 0, wc = 0, vf = 0, vc = 0;
rows.forEach(ref => {
  const c = M.computeRowWithLayout(project, makeRow(ref), L);
  WIDTH_KEYS.forEach(k => { wc++; if (Math.abs(c[k] - ref.exp[k]) > 1e-6) { wf++; console.log(`  [largeur] ligne ${ref.r} ${k}: got ${c[k]} want ${ref.exp[k]} ✗`); } });
  if (ref.standard) {
    VOL_KEYS.forEach(k => { vc++; if (Math.abs(c[k] - ref.exp[k]) > 1e-6) { vf++; console.log(`  [volume] ligne ${ref.r} ${k}: got ${c[k]} want ${ref.exp[k]} ✗`); } });
  }
});
const nStd = rows.filter(r => r.standard).length;
console.log(`Largeurs + volume principal (interstices) : ${wc - wf}/${wc} identiques sur les ${rows.length} tronçons`);
console.log(`Volumes sable/conduites : ${vc - vf}/${vc} identiques sur les ${nStd} tronçons au gabarit standard`);
console.log(`(${rows.length - nStd} tronçons de l'original utilisent des formules manuelles hors gabarit, exclus.)`);
assert.strictEqual(wf, 0, 'Écart sur les largeurs / volume principal — la gestion des interstices diffère');
assert.strictEqual(vf, 0, 'Écart de volume sur une ligne au gabarit standard');
console.log('ÉQUIVALENCE CONFIRMÉE (gabarit standard + interstices) ✓');
