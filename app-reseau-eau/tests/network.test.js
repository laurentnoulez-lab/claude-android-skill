/*
 * Tests du solveur de réseau : détection des mailles, réseau ramifié,
 * Hardy Cross sur une boucle académique.
 */
const test = require('node:test');
const assert = require('node:assert');
const net = require('../js/core/network.js');

function projetBase(noeuds, troncons, alim) {
  return {
    meta: {},
    hypotheses: {
      nu: 1.31e-6, coeffPointe: 1, dureeDistribution: 24,
      debitIncendie: 60, deuxHydrants: false, pressionMinimale: null,
      pertesSingulieresPct: 0, rugosites: {},
    },
    alimentation: alim,
    noeuds, troncons,
  };
}

test('Détection des mailles : arbre → 0 maille, boucle simple → 1 maille', () => {
  const noeuds = [
    { id: 'A', cote: 0 }, { id: 'B', cote: 0 }, { id: 'C', cote: 0 },
  ];
  const arbre = [
    { id: 'T1', de: 'A', vers: 'B', longueur: 100, materiau: 'fonte' },
    { id: 'T2', de: 'B', vers: 'C', longueur: 100, materiau: 'fonte' },
  ];
  const g1 = net.analyserGraphe(noeuds, arbre, 'A');
  assert.strictEqual(g1.nbMailles, 0);

  const boucle = arbre.concat([{ id: 'T3', de: 'A', vers: 'C', longueur: 100, materiau: 'fonte' }]);
  const g2 = net.analyserGraphe(noeuds, boucle, 'A');
  assert.strictEqual(g2.nbMailles, 1);
  // La maille contient bien les 3 tronçons
  assert.strictEqual(g2.mailles[0].length, 3);
});

test('Réseau ramifié : débits par accumulation aval → amont', () => {
  // A(alim) → B → C, avec 10 m³/j en B et 20 m³/j en C, cas pointe Cp=1, 24 h
  // → demandes : B 10/24, C 20/24 m³/h ; T1 porte B+C, T2 porte C.
  const p = projetBase(
    [
      { id: 'A', cote: 0, consommation: 0 },
      { id: 'B', cote: 0, consommation: 10 },
      { id: 'C', cote: 0, consommation: 20 },
    ],
    [
      { id: 'T1', de: 'A', vers: 'B', longueur: 100, materiau: 'fonte' },
      { id: 'T2', de: 'B', vers: 'C', longueur: 100, materiau: 'fonte' },
    ],
    { noeudId: 'A', mode: 'pression_fixe', p0: 4 }
  );
  const diam = new Map([['T1', 100], ['T2', 100]]);
  const r = net.calculerCas(p, diam, { type: 'pointe' });
  assert.ok(Math.abs(r.troncons.get('T1').Q - 30 / 24) < 1e-9);
  assert.ok(Math.abs(r.troncons.get('T2').Q - 20 / 24) < 1e-9);
  assert.strictEqual(r.equilibrage, null); // pas d'équilibrage nécessaire
  // Pressions décroissantes vers l'aval
  assert.ok(r.noeuds.get('A').p > r.noeuds.get('B').p);
  assert.ok(r.noeuds.get('B').p > r.noeuds.get('C').p);
});

test('Hardy Cross académique : 2 conduites parallèles identiques → partage 50/50', () => {
  const p = projetBase(
    [
      { id: 'A', cote: 0, consommation: 0 },
      { id: 'B', cote: 0, consommation: 240 }, // 10 m³/h en pointe Cp=1/24h
    ],
    [
      { id: 'T1', de: 'A', vers: 'B', longueur: 200, materiau: 'fonte' },
      { id: 'T2', de: 'A', vers: 'B', longueur: 200, materiau: 'fonte' },
    ],
    { noeudId: 'A', mode: 'pression_fixe', p0: 4 }
  );
  const diam = new Map([['T1', 100], ['T2', 100]]);
  const r = net.calculerCas(p, diam, { type: 'pointe' });
  assert.ok(r.equilibrage.converge, 'Hardy Cross doit converger');
  const q1 = r.troncons.get('T1').Q;
  const q2 = r.troncons.get('T2').Q;
  assert.ok(Math.abs(q1 - 5) < 0.01, `Q1 = ${q1}`);
  assert.ok(Math.abs(q2 - 5) < 0.01, `Q2 = ${q2}`);
  // Continuité : somme des débits = demande
  assert.ok(Math.abs(q1 + q2 - 10) < 1e-6);
});

test('Hardy Cross académique : parallèles L=100 m et L=300 m — comparaison à une résolution indépendante', () => {
  // Deux conduites parallèles identiques en diamètre : à l'équilibre,
  // h1(Q1) = h2(Qtot − Q1). Référence indépendante : résolution par
  // DICHOTOMIE sur Q1 avec les mêmes lois de perte de charge, à comparer
  // au résultat de Hardy Cross. (À λ constant, Q1/Q2 → √(L2/L1) = √3 ;
  // comme λ dépend de Re, le rapport réel s'en écarte légèrement.)
  const hyd = require('../js/core/hydraulics.js');
  const QTOT = 20; // m³/h
  const p = projetBase(
    [
      { id: 'A', cote: 0, consommation: 0 },
      { id: 'B', cote: 0, consommation: QTOT * 24 },
    ],
    [
      { id: 'T1', de: 'A', vers: 'B', longueur: 100, materiau: 'fonte' },
      { id: 'T2', de: 'A', vers: 'B', longueur: 300, materiau: 'fonte' },
    ],
    { noeudId: 'A', mode: 'pression_fixe', p0: 4 }
  );
  const diam = new Map([['T1', 100], ['T2', 100]]);
  const r = net.calculerCas(p, diam, { type: 'pointe' });
  assert.ok(r.equilibrage.converge);

  // Référence par dichotomie : h1(Q1) − h2(QTOT − Q1) = 0
  const h = (Q, L) => hyd.perteDeCharge({ Q: Q / 3600, L, Di: 100, k: 0.1, nu: 1.31e-6 }).dH;
  let lo = 1e-6, hi = QTOT - 1e-6;
  for (let i = 0; i < 200; i++) {
    const mid = (lo + hi) / 2;
    if (h(mid, 100) - h(QTOT - mid, 300) > 0) hi = mid; else lo = mid;
  }
  const q1Ref = (lo + hi) / 2;

  const q1 = r.troncons.get('T1').Q;
  assert.ok(Math.abs(q1 - q1Ref) / q1Ref < 0.005, `Q1 Hardy Cross = ${q1}, référence = ${q1Ref}`);
  // Ordre de grandeur du partage : proche de √3 (λ variable → écart admis)
  const ratio = q1 / r.troncons.get('T2').Q;
  assert.ok(Math.abs(ratio - Math.sqrt(3)) / Math.sqrt(3) < 0.10, `Q1/Q2 = ${ratio}`);
  // Équilibre des charges : ΔH identique sur les deux branches
  const dH1 = r.troncons.get('T1').dH;
  const dH2 = r.troncons.get('T2').dH;
  assert.ok(Math.abs(dH1 - dH2) < 0.005, `ΔH1=${dH1}, ΔH2=${dH2}`);
  // Le déséquilibre résiduel de maille est quasi nul
  assert.ok(r.equilibrage.maxDesequilibre < 0.005);
});

test('Cotes altimétriques : la dénivelée s’ajoute à la pression', () => {
  // B est 10 m PLUS BAS que A → pression statique augmentée de ~0,98 bar
  const p = projetBase(
    [
      { id: 'A', cote: 50, consommation: 0 },
      { id: 'B', cote: 40, consommation: 0.001 },
    ],
    [{ id: 'T1', de: 'A', vers: 'B', longueur: 10, materiau: 'fonte' }],
    { noeudId: 'A', mode: 'pression_fixe', p0: 3 }
  );
  const r = net.calculerCas(p, new Map([['T1', 100]]), { type: 'pointe' });
  const pB = r.noeuds.get('B').p;
  assert.ok(Math.abs(pB - (3 + 10 / 10.197)) < 0.001, `pB = ${pB}`);
});

test('Distances entre hydrants le long du réseau', () => {
  const p = projetBase(
    [
      { id: 'A', cote: 0, hydrant: true },
      { id: 'B', cote: 0, hydrant: false },
      { id: 'C', cote: 0, hydrant: true },
    ],
    [
      { id: 'T1', de: 'A', vers: 'B', longueur: 80, materiau: 'fonte' },
      { id: 'T2', de: 'B', vers: 'C', longueur: 70, materiau: 'fonte' },
    ],
    { noeudId: 'A', mode: 'pression_fixe', p0: 4 }
  );
  const d = net.distancesHydrants(p);
  assert.strictEqual(d.length, 2);
  assert.strictEqual(d[0].distance, 150); // A → C par le réseau
});
