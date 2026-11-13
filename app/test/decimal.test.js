/*
 * Analyse des nombres décimaux saisis à la main (virgule ou point).
 *
 * Régression couverte : les champs <input type="number"> supprimaient
 * silencieusement la virgule, si bien qu'une largeur saisie « 0,48 » était
 * lue « 048 », soit 48 m au lieu de 0,48 m.
 */
const assert = require('assert');
const M = require('../src/model.js');

let ko = 0;
function eq(actual, expected, label) {
  const ok = Object.is(actual, expected) ||
    (typeof actual === 'number' && typeof expected === 'number' && Math.abs(actual - expected) < 1e-12);
  if (!ok) { ko++; console.error(`  ✗ ${label} : attendu ${JSON.stringify(expected)}, obtenu ${JSON.stringify(actual)}`); }
  return ok;
}

console.log('parseDecimal — séparateur décimal');
[
  ['0,48', 0.48], ['0.48', 0.48], [',5', 0.5], ['.5', 0.5],
  ['12,5', 12.5], ['12.5', 12.5], ['1,20', 1.2], ['100', 100],
  ['-0,3', -0.3], ['+1,5', 1.5], ['0', 0],
  ['12,', 12], ['12.', 12],                       // saisie en cours
  ['1 234,5', 1234.5], ['1 234,5', 1234.5],  // espaces de milliers
  ['1.234,5', 1234.5], ['1,234.5', 1234.5],       // les deux séparateurs
  [' 0,15 ', 0.15], [0.48, 0.48],
].forEach(([input, expected]) => eq(M.parseDecimal(input), expected, `parseDecimal(${JSON.stringify(input)})`));

console.log('parseDecimal — saisies non exploitables → null');
['', '   ', 'abc', '-', ',', '.', '1,2,', '0,4a', null, undefined, NaN, Infinity]
  .forEach((input) => eq(M.parseDecimal(input), null, `parseDecimal(${JSON.stringify(input)})`));

console.log('num — repli sur la valeur par défaut');
[['0,16', 0.5, 0.16], ['', 0.5, 0.5], ['abc', 0.12, 0.12], [undefined, 0.1, 0.1], ['2', 0.1, 2]]
  .forEach(([v, d, expected]) => eq(M.num(v, d), expected, `num(${JSON.stringify(v)}, ${d})`));
eq(M.num('bruit'), 0, 'num("bruit") sans défaut → 0');

console.log('formatDecimal — affichage dans les champs');
[[0.48, '0,48'], [1.2, '1,2'], [0, '0'], [12.5, '12,5'], [0.1 + 0.2, '0,3'], [null, ''], [undefined, ''], [1234.5, '1234,5']]
  .forEach(([v, expected]) => eq(M.formatDecimal(v), expected, `formatDecimal(${v})`));

console.log('aller-retour saisie → modèle → affichage');
['0,48', '1,20', '12,5', '0,16'].forEach((s) => {
  eq(M.formatDecimal(M.parseDecimal(s)), s.replace(/0$/, '').replace(/,$/, ''), `aller-retour ${s}`);
});

console.log('impact métier : une largeur saisie « 0,48 » ne doit pas valoir 48');
eq(M.num('0,48'), 0.48, 'largeur 0,48 m');
assert.ok(M.num('0,48') < 1, 'la largeur reste sous le mètre');

if (ko) { console.error(`\n${ko} assertion(s) en échec`); process.exit(1); }
console.log('\nTOUS LES TESTS DÉCIMAUX PASSENT ✓');
