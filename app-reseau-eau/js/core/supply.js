/*
 * supply.js — Point d'alimentation (piquage sur réseau existant).
 *
 * Deux modes :
 *  1. « pression_fixe » : pression relative constante P0 (bar) au nœud
 *     d'alimentation, quel que soit le débit prélevé. Hypothèse forte :
 *     à réserver aux piquages sur des conduites largement dimensionnées.
 *  2. « essai » : courbe caractéristique issue d'un essai débit-pression
 *     réalisé sur le réseau existant (pratique courante : mesure de la
 *     pression statique P0, puis de la pression résiduelle P1 sous un
 *     débit soutiré Q1, généralement à un hydrant).
 *     Extrapolation :  P(Q) = P0 − (P0 − P1) · (Q/Q1)^1,852
 *     L'exposant 1,852 (≈ 1/0,54) est celui de Hazen-Williams : les pertes
 *     de charge du réseau amont croissent comme Q^1,852. C'est une RÈGLE DE
 *     L'ART d'avant-projet (cf. pratique des essais hydrants, AWWA M31),
 *     pas une loi exacte ; elle est documentée comme telle dans le rapport.
 */
(function () {
  /**
   * Pression disponible au point d'alimentation pour un débit Q donné.
   * @param alim {mode, p0 (bar), q1 (m³/h), p1 (bar)}
   * @param Q débit total prélevé (m³/h)
   * @returns pression relative (bar)
   */
  function pressionDisponible(alim, Q) {
    if (!alim || alim.p0 === undefined || alim.p0 === null) return NaN;
    if (alim.mode === 'pression_fixe') return alim.p0;
    // Mode « essai » : P(Q) = P0 − (P0 − P1)·(Q/Q1)^1,852
    if (!alim.q1 || alim.q1 <= 0 || alim.p1 === undefined || alim.p1 === null) return NaN;
    if (Q <= 0) return alim.p0;
    return alim.p0 - (alim.p0 - alim.p1) * Math.pow(Q / alim.q1, 1.852);
  }

  const S = { pressionDisponible };
  if (typeof module !== 'undefined' && module.exports) module.exports = S;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.supply = S;
})();
