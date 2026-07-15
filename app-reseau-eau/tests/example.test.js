/*
 * Test d'intégration : projet d'exemple du cahier des charges.
 *
 * Valeurs attendues (données du prompt métier) :
 *  - Débit dimensionnant ≈ 17,0 l/s (incendie 60 m³/h au nœud le plus
 *    défavorable + consommation moyenne).
 *  - En DN100 fonte, boucle fermée : v ≈ 1,08 m/s par branche,
 *    ΔH ≈ 3,4 mCE par branche de 250 m, P résiduelle ≈ 2,6 bar.
 *  - Cas de pointe : 30 m³/j / 10 h × 2,5 = 7,5 m³/h = 2,08 l/s.
 */
const test = require('node:test');
const assert = require('node:assert');
const net = require('../js/core/network.js');
const sup = require('../js/core/supply.js');
const siz = require('../js/core/sizing.js');
const chk = require('../js/core/checks.js');
const ex = require('../js/core/example.js');

function diamDN100(projet) {
  return new Map(projet.troncons.map((t) => [t.id, 100]));
}

test('Exemple : validation du projet sans erreur', () => {
  const p = ex.projetExemple();
  assert.deepStrictEqual(chk.validerProjet(p), []);
});

test('Exemple : cas de pointe = 7,5 m³/h (2,08 l/s)', () => {
  const p = ex.projetExemple();
  const r = net.calculerCas(p, diamDN100(p), { type: 'pointe' });
  assert.ok(Math.abs(r.qTotal - 7.5) < 1e-9, `qTotal = ${r.qTotal}`);
});

test('Exemple : courbe d’alimentation P(0)=4,5 bar, P(40)=3,8 bar', () => {
  const p = ex.projetExemple();
  assert.strictEqual(sup.pressionDisponible(p.alimentation, 0), 4.5);
  assert.ok(Math.abs(sup.pressionDisponible(p.alimentation, 40) - 3.8) < 1e-12);
});

test('Exemple : débit dimensionnant ≈ 17,0 l/s (cas incendie critique)', () => {
  const p = ex.projetExemple();
  const r = net.calculComplet(p, diamDN100(p));
  assert.ok(r.casIncendie, 'un scénario incendie critique doit exister');
  const qLs = r.casIncendie.resultat.qTotal / 3.6; // m³/h → l/s
  assert.ok(Math.abs(qLs - 17.0) < 0.15, `Q dimensionnant = ${qLs.toFixed(2)} l/s`);
  // Le nœud critique est bien le bout de rue (N3, le plus éloigné)
  assert.deepStrictEqual(r.casIncendie.combo, ['N3']);
});

test('Exemple DN100 fonte : v ≈ 1,08 m/s par branche de boucle', () => {
  const p = ex.projetExemple();
  const r = net.calculComplet(p, diamDN100(p));
  const rInc = r.casIncendie.resultat;
  assert.ok(rInc.equilibrage.converge, 'Hardy Cross doit converger');
  const vT1 = rInc.troncons.get('T1').v;
  const vT4 = rInc.troncons.get('T4').v;
  assert.ok(Math.abs(vT1 - 1.08) < 0.05, `v(T1) = ${vT1.toFixed(3)} m/s`);
  assert.ok(Math.abs(vT4 - 1.08) < 0.05, `v(T4) = ${vT4.toFixed(3)} m/s`);
});

test('Exemple DN100 fonte : ΔH ≈ 3,4 mCE par branche de 250 m', () => {
  const p = ex.projetExemple();
  const r = net.calculComplet(p, diamDN100(p));
  const rInc = r.casIncendie.resultat;
  const dHNord = ['T1', 'T2', 'T3'].reduce((s, id) => s + Math.abs(rInc.troncons.get(id).dH), 0);
  const dHSud = ['T4', 'T5', 'T6'].reduce((s, id) => s + Math.abs(rInc.troncons.get(id).dH), 0);
  assert.ok(Math.abs(dHNord - 3.4) < 0.25, `ΔH nord = ${dHNord.toFixed(2)} mCE`);
  assert.ok(Math.abs(dHSud - 3.4) < 0.25, `ΔH sud = ${dHSud.toFixed(2)} mCE`);
});

test('Exemple DN100 fonte : P résiduelle ≈ 2,6 bar au bout de rue (incendie)', () => {
  const p = ex.projetExemple();
  const r = net.calculComplet(p, diamDN100(p));
  const pN3 = r.casIncendie.resultat.noeuds.get('N3').p;
  assert.ok(Math.abs(pN3 - 2.6) < 0.15, `P(N3) = ${pN3.toFixed(2)} bar`);
  // Nœud le plus défavorable = N3, en cas incendie, et ≥ 1,5 bar exigé
  const pire = chk.noeudLePlusDefavorable(p, r);
  assert.strictEqual(pire.id, 'N3');
  assert.ok(pire.p >= p.hypotheses.pressionMinimale);
});

test('Exemple : dimensionnement auto → DN100 partout (plus petit Ø admissible avec hydrants)', () => {
  const p = ex.projetExemple();
  p.troncons.forEach((t) => { t.diametreForce = null; });
  const d = siz.dimensionner(p);
  for (const t of p.troncons) {
    assert.strictEqual(d.diametres.get(t.id).di, 100, `tronçon ${t.id}`);
    assert.strictEqual(d.diametres.get(t.id).force, false);
  }
  // Et le résultat satisfait la pression minimale
  const pire = chk.noeudLePlusDefavorable(p, d.resultats);
  assert.ok(pire.p >= 1.5);
});

test('Exemple : contrôles — aucun NOK, interdistances hydrants respectées', () => {
  const p = ex.projetExemple();
  const diam = diamDN100(p);
  const r = net.calculComplet(p, diam);
  const c = chk.controler(p, r, diam);
  assert.strictEqual(c.filter((x) => x.niveau === 'nok').length, 0,
    JSON.stringify(c.filter((x) => x.niveau === 'nok'), null, 2));
  assert.strictEqual(c.filter((x) => x.code === 'HYDRANT_DISTANCE').length, 0);
  // La pression au nœud critique est contrôlée et conforme
  assert.ok(c.some((x) => x.code === 'PRESSION_OK'));
});
