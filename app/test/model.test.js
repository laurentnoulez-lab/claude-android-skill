const M = require('../src/model.js');
const ExcelJS = require('exceljs');
const assert = require('assert');

const REF = {
  5: {
    widths: {F:0.1,G:0,H:0.05,I:0.05,J:0.1,K:0.1,L:0.1,M:0,N:0,O:0.12,P:0.07,Q:0,R:0,S:0.06,T:0.05,U:0,V:0.1,W:0,X:0,Y:0.12,Z:0.2,AA:0.16,AB:0.2},
    geom:{AG:0.1,AH:0.12,AI:0.1,AJ:'OUI',AL:0.8,AM:0.3,AP:0,AU:0,BA:0.1,BC:0.2,BF:1,BJ:0}, AD:1.6, E:165,
    exp:{AC:1.58,AT:0.8,AR:0.43,AS:0.37,BB:0.16,BL:0.28,BM:0.5,BN:0.78,AK:0.1,AN:0.72,AO:0.32,AQ:0.4,
         AV:42.24,AW:42.24,AX:0,AY:42.24,AZ:95.04,BE:0.3,BH:0.96,BI:0.56,BK:0.4,BO:72.072,BP:66.88837212157685,BQ:0,BR:72.072,BS:123.552,BT:218.592,AE:''}
  },
  30: {
    widths:{F:0,G:0,H:0,I:0,J:0,K:0,L:0,M:0,N:0,O:0,P:0,Q:0,R:0,S:0,T:0,U:0,V:0.35,W:0,X:0.12,Y:0,Z:0.35,AA:0,AB:0},
    geom:{AG:0.1,AH:0.05,AI:0.2,AJ:'OUI',AL:1.2,AM:0.65,AP:0.35,AU:0,BA:0.1,BC:0.2,BF:1.2,BJ:0.35}, AD:0, E:20,
    exp:{AC:0.82,AT:0,AR:0,AS:0,BB:0.12,BL:0.12,BM:0.7,BN:0.82,AK:0.2,AN:0.7,AO:0.35,AQ:0,
         AV:0,AW:0,AX:0,AY:0,AZ:0,BE:0.2,BH:0.77,BI:0.42,BK:0,BO:6.888,BP:6.661805328941535,BQ:5.74,BR:12.628,BS:12.628,BT:12.628,AE:''}
  }
};

function makeRow(project, ref) {
  const L = M.buildLayout(project);
  const row = M.newRow(project);
  row.longueur = ref.E;
  row.largeurMax = ref.AD;
  L.cols.forEach(c => {
    if (c.isInterstice) row.interstices[c.intKey] = ref.widths[c.letter];
    if (c.isChannel) row.widths[c.srId] = ref.widths[c.letter];
  });
  const g = row.geom;
  g.litPoseCable=ref.geom.AG; g.htMoyCable=ref.geom.AH; g.recouvSableMinCable=ref.geom.AI;
  g.ligneAligne=ref.geom.AJ; g.recouvNiveauFiniCable=ref.geom.AL; g.hauteurCoffre=ref.geom.AM;
  g.remblaiSousFondCable=ref.geom.AP; g.longueurGaines=ref.geom.AU;
  g.litPoseConduite=ref.geom.BA; g.recouvSableMinConduite=ref.geom.BC;
  g.recouvNiveauFiniConduite=ref.geom.BF; g.remblaiSousFondConduite=ref.geom.BJ;
  return row;
}

let passed = 0, failed = 0;
const project = M.defaultProject();
Object.keys(REF).forEach(k => {
  const ref = REF[k];
  const row = makeRow(project, ref);
  const c = M.computeRow(project, row);
  Object.keys(ref.exp).forEach(key => {
    const got = c[key], want = ref.exp[key];
    let ok;
    if (typeof want === 'number') ok = Math.abs(got - want) < 1e-6;
    else ok = got === want;
    if (ok) passed++; else { failed++; console.log(`  ROW ${k} ${key}: got ${got} want ${want}  ✗`); }
  });
});
console.log(`Moteur de calcul : ${passed} OK, ${failed} KO`);
assert.strictEqual(failed, 0, 'Des écarts de calcul détectés');

// --- Test génération Excel ---
project.rows = [makeRow(project, REF[5]), makeRow(project, REF[30])];
const wb = M.buildWorkbook(project, ExcelJS);
wb.xlsx.writeFile(require('path').join(__dirname,'out.xlsx')).then(async () => {
  const wb2 = new ExcelJS.Workbook();
  await wb2.xlsx.readFile(require('path').join(__dirname,'out.xlsx'));
  const ws = wb2.getWorksheet('Gabarits tranchées communes');
  // Vérifie quelques formules clés sur la ligne 5 (1ère ligne de données)
  const checks = {
    'AC5': 'SUM(F5:AB5)',
    'AT5': 'AR5+AS5',
    'AR5': 'SUM(G5,H5,I5,K5,M5,N5,O5,Q5,R5,S5,T5)',
    'BL5': 'SUM(W5,X5,Y5,AA5)',
    'BP5': 'BO5-PI()*((W5/2)^2+(X5/2)^2+(Y5/2)^2+(AA5/2)^2)*AF5',
    'BT5': 'AZ5+BS5',
    'BV5': 'IF($AR5>0,G5/$AR5,0)',
    'BW5': 'BV5*$AZ5/$BT5'
  };
  let fp=0, ff=0;
  Object.keys(checks).forEach(addr => {
    const cell = ws.getCell(addr);
    const f = cell.formula || (cell.value && cell.value.formula);
    if (f === checks[addr]) fp++; else { ff++; console.log(`  FORMULE ${addr}: got '${f}' want '${checks[addr]}' ✗`); }
  });
  // valeur de saisie
  assert.strictEqual(ws.getCell('E5').value, 165, 'E5 longueur');
  assert.strictEqual(ws.getCell('I5').value, 0.05, 'I5 largeur PROXIMUS E');
  console.log(`Formules Excel : ${fp} OK, ${ff} KO`);
  assert.strictEqual(ff, 0);
  console.log('TOUS LES TESTS PASSENT ✓');
}).catch(e => { console.error(e); process.exit(1); });
