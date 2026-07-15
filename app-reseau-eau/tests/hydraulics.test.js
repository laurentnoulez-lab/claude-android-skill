/*
 * Tests du module hydraulique : Swamee-Jain vs Colebrook-White (référence
 * itérative), régime laminaire, pertes de charge Darcy-Weisbach.
 */
const test = require('node:test');
const assert = require('node:assert');
const hyd = require('../js/core/hydraulics.js');

/** Référence : résolution itérative de Colebrook-White (point fixe). */
function colebrook(k, D, Re) {
  let x = 0.02; // λ initial
  for (let i = 0; i < 100; i++) {
    const rhs = -2 * Math.log10(k / (3.7 * D) + 2.51 / (Re * Math.sqrt(x)));
    const nx = 1 / (rhs * rhs);
    if (Math.abs(nx - x) < 1e-12) return nx;
    x = nx;
  }
  return x;
}

test('Swamee-Jain ≈ Colebrook-White sur une grille Re × k/D', () => {
  const D = 0.1; // m
  for (const Re of [5e3, 1e4, 5e4, 1e5, 5e5, 1e6, 1e7]) {
    for (const kSurD of [1e-6, 1e-5, 1e-4, 1e-3, 5e-3, 2e-2]) {
      const k = kSurD * D;
      const sj = hyd.swameeJain(k, D, Re);
      const cb = colebrook(k, D, Re);
      const ecart = Math.abs(sj - cb) / cb;
      // Précision documentée de Swamee-Jain : ~±1 % sur l'essentiel du
      // domaine ; elle se dégrade à ~3 % dans le coin faible Re × forte
      // rugosité (Re ≤ 10⁴ et k/D ≥ 5×10⁻³), hors conditions usuelles de
      // distribution d'eau. Tolérances en conséquence.
      const tol = Re <= 1e4 && kSurD >= 5e-3 ? 0.032 : 0.02;
      assert.ok(
        ecart < tol,
        `Re=${Re}, k/D=${kSurD} : Swamee-Jain ${sj.toFixed(5)} vs Colebrook ${cb.toFixed(5)} (écart ${(ecart * 100).toFixed(2)} %)`
      );
    }
  }
});

test('Valeur de référence : Re=1e5, k/D=1e-4 → λ ≈ 0,0186', () => {
  const l = hyd.lambda(0.1 * 1e-4, 0.1, 1e5);
  assert.ok(Math.abs(l - 0.0186) < 0.0005, `λ = ${l}`);
});

test('Régime laminaire : λ = 64/Re', () => {
  assert.strictEqual(hyd.lambda(0.0001, 0.1, 1000), 64 / 1000);
});

test('Darcy-Weisbach : conduite DN100 fonte, 8,5 l/s sur 250 m → v≈1,08 m/s, ΔH≈3,4 mCE', () => {
  // Cas de base de l'exemple métier (une branche de la boucle)
  const r = hyd.perteDeCharge({ Q: 0.0085, L: 250, Di: 100, k: 0.1, nu: 1.31e-6 });
  assert.ok(Math.abs(r.v - 1.082) < 0.01, `v = ${r.v}`);
  assert.ok(Math.abs(r.dH - 3.38) < 0.15, `ΔH = ${r.dH}`);
});

test('Pertes singulières : ΣK et pourcentage s’ajoutent aux pertes linéaires', () => {
  const base = hyd.perteDeCharge({ Q: 0.01, L: 100, Di: 100, k: 0.1, nu: 1.31e-6 });
  const avecK = hyd.perteDeCharge({ Q: 0.01, L: 100, Di: 100, k: 0.1, nu: 1.31e-6, sommeK: 5 });
  const hv = (avecK.v * avecK.v) / (2 * 9.81);
  assert.ok(Math.abs(avecK.dH - (base.dH + 5 * hv)) < 1e-9);
  const avecPct = hyd.perteDeCharge({ Q: 0.01, L: 100, Di: 100, k: 0.1, nu: 1.31e-6, pctSingulieres: 10 });
  assert.ok(Math.abs(avecPct.dH - base.dH * 1.1) < 1e-9);
});

test('Débit nul → ΔH nul, débit négatif → ΔH négatif (signes cohérents)', () => {
  assert.strictEqual(hyd.perteDeCharge({ Q: 0, L: 100, Di: 100, k: 0.1 }).dH, 0);
  const rNeg = hyd.perteDeCharge({ Q: -0.01, L: 100, Di: 100, k: 0.1 });
  const rPos = hyd.perteDeCharge({ Q: 0.01, L: 100, Di: 100, k: 0.1 });
  assert.ok(Math.abs(rNeg.dH + rPos.dH) < 1e-12);
});
