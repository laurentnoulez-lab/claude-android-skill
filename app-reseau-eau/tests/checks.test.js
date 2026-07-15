/*
 * Tests des contrôles réglementaires et de la validation des saisies.
 */
const test = require('node:test');
const assert = require('node:assert');
const net = require('../js/core/network.js');
const chk = require('../js/core/checks.js');
const mat = require('../js/core/materials.js');
const ex = require('../js/core/example.js');

test('Contrôle BLOQUANT : Ø < 100 mm sur tronçon portant un hydrant', () => {
  const p = ex.projetExemple();
  const diam = new Map(p.troncons.map((t) => [t.id, 100]));
  diam.set('T3', 80); // DN80 sur un tronçon menant à l'hydrant N3
  const r = net.calculComplet(p, diam);
  const c = chk.controler(p, r, diam);
  const bloquants = c.filter((x) => x.bloquant);
  assert.ok(bloquants.length >= 1);
  assert.ok(bloquants.some((x) => x.code === 'HYDRANT_DIAMETRE' && x.cibles.includes('T3')));
});

test('Avertissement : interdistance hydrants > 100 m', () => {
  const p = ex.projetExemple();
  p.troncons.find((t) => t.id === 'T2').longueur = 150; // N1–N2 passe à 150 m
  const diam = new Map(p.troncons.map((t) => [t.id, 100]));
  const r = net.calculComplet(p, diam);
  const c = chk.controler(p, r, diam);
  assert.ok(c.some((x) => x.code === 'HYDRANT_DISTANCE'));
});

test('Avertissement : pression minimale non renseignée (pas de défaut silencieux)', () => {
  const p = ex.projetExemple();
  p.hypotheses.pressionMinimale = null;
  const diam = new Map(p.troncons.map((t) => [t.id, 100]));
  const r = net.calculComplet(p, diam);
  const c = chk.controler(p, r, diam);
  assert.ok(c.some((x) => x.code === 'PMIN_MANQUANTE'));
});

test('NOK : pression résiduelle insuffisante', () => {
  const p = ex.projetExemple();
  p.hypotheses.pressionMinimale = 3.5; // exigence irréaliste pour ce réseau
  const diam = new Map(p.troncons.map((t) => [t.id, 100]));
  const r = net.calculComplet(p, diam);
  const c = chk.controler(p, r, diam);
  assert.ok(c.some((x) => x.code === 'PRESSION_INSUFFISANTE' && x.niveau === 'nok'));
});

test('Avertissement : vitesse en pointe < 0,3 m/s (stagnation)', () => {
  const p = ex.projetExemple();
  const diam = new Map(p.troncons.map((t) => [t.id, 100]));
  const r = net.calculComplet(p, diam);
  const c = chk.controler(p, r, diam);
  // En pointe (7,5 m³/h répartis sur la boucle en DN100), v < 0,3 m/s partout
  assert.ok(c.some((x) => x.code === 'VITESSE_FAIBLE'));
});

test('Validation des saisies : erreurs en français, cas négatifs et manquants', () => {
  const p = ex.projetExemple();
  p.noeuds[1].consommation = -5;
  p.troncons[0].longueur = 0;
  p.hypotheses.coeffPointe = 0;
  const erreurs = chk.validerProjet(p);
  assert.ok(erreurs.some((e) => e.includes('négative')));
  assert.ok(erreurs.some((e) => e.includes('longueur')));
  assert.ok(erreurs.some((e) => e.includes('coefficient de pointe')));
});

test('Validation : nœud non raccordé détecté', () => {
  const p = ex.projetExemple();
  p.noeuds.push({ id: 'N9', nom: 'Orphelin', x: 0, y: 0, cote: 0, consommation: 1, hydrant: false });
  const erreurs = chk.validerProjet(p);
  assert.ok(erreurs.some((e) => e.includes('non raccordé')));
});

test('Validation : essai débit-pression incohérent (P1 > P0)', () => {
  const p = ex.projetExemple();
  p.alimentation.p1 = 5.0;
  const erreurs = chk.validerProjet(p);
  assert.ok(erreurs.some((e) => e.includes('P1')));
});

test('Matériaux : diamètres intérieurs réels (l’épaisseur compte)', () => {
  const pehd11 = mat.diametres('pehd-sdr11');
  const de110 = pehd11.find((d) => d.de === 110);
  assert.strictEqual(de110.di, 90); // De110 SDR11 → Di 90 mm (épaisseur 10 mm)
  const pvc10 = mat.diametres('pvc-pn10');
  assert.strictEqual(pvc10.find((d) => d.de === 110).di, 99.4);
  const fonte = mat.diametres('fonte');
  assert.strictEqual(fonte.find((d) => d.de === 100).di, 100); // fonte : Di ≈ DN
});

test('Rugosités : défaut par matériau, surcharge utilisateur possible', () => {
  assert.strictEqual(mat.rugosite('fonte', {}), 0.1);
  assert.strictEqual(mat.rugosite('pvc-pn10', {}), 0.05);
  assert.strictEqual(mat.rugosite('fonte', { fonte: 0.5 }), 0.5);
});
