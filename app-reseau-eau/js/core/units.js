/*
 * units.js — Conversions d'unités et constantes physiques.
 *
 * Conventions de l'application (rappelées dans l'UI et le rapport) :
 *  - Toutes les pressions sont des pressions RELATIVES (manométriques), en bar.
 *  - 1 bar = 10,197 mCE (colonne d'eau à ~10 °C, g = 9,81 m/s²).
 *  - 1 l/s = 3,6 m³/h.
 *  - Diamètres INTÉRIEURS réels en mm, longueurs en m, vitesses en m/s.
 */
(function () {
  const U = {
    G: 9.81,                 // accélération de la pesanteur (m/s²)
    MCE_PAR_BAR: 10.197,     // 1 bar = 10,197 mCE

    // Débits
    m3hVersLs: (q) => q / 3.6,
    lsVersM3h: (q) => q * 3.6,
    m3jVersM3h: (q, heures) => q / heures, // consommation journalière étalée sur `heures` h

    // Pressions / charges
    barVersMce: (p) => p * 10.197,
    mceVersBar: (h) => h / 10.197,

    // Divers
    mmVersM: (d) => d / 1000,

    /** Formatage français d'un nombre (virgule décimale). */
    fmt: (x, dec = 2) => {
      if (x === null || x === undefined || Number.isNaN(x)) return '—';
      return Number(x).toLocaleString('fr-BE', {
        minimumFractionDigits: dec,
        maximumFractionDigits: dec,
      });
    },
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = U;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.units = U;
})();
