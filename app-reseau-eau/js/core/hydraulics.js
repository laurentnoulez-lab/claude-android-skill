/*
 * hydraulics.js — Pertes de charge linéaires : Darcy-Weisbach + Swamee-Jain.
 *
 * Formule de Darcy-Weisbach :  ΔH = λ · (L/D) · v²/(2g)   [mCE]
 *
 * Coefficient de frottement λ par la formule EXPLICITE de Swamee-Jain (1976) :
 *      λ = 0,25 / [ log10( k/(3,7·D) + 5,74/Re^0,9 ) ]²
 * C'est une approximation de l'équation implicite de Colebrook-White,
 * précise à ±1 % environ pour 5×10³ ≤ Re ≤ 10⁸ et 10⁻⁶ ≤ k/D ≤ 5×10⁻².
 * Elle est retenue ici car explicite (pas d'itération) et largement admise
 * en avant-projet ; la note de calcul la documente comme telle.
 *
 * Régime laminaire (Re < 2300) : λ = 64/Re (Poiseuille).
 * Zone de transition 2300–4000 : interpolation prudente (rare en distribution).
 *
 * Viscosité cinématique par défaut : ν = 1,31×10⁻⁶ m²/s (eau à 10 °C),
 * modifiable dans les hypothèses.
 */
(function () {
  const G = 9.81;
  const NU_DEFAUT = 1.31e-6; // m²/s, eau à 10 °C

  /** Nombre de Reynolds. v en m/s, D en m, nu en m²/s. */
  function reynolds(v, D, nu) {
    return (v * D) / nu;
  }

  /** λ par Swamee-Jain. k et D en m (mêmes unités), Re sans dimension. */
  function swameeJain(k, D, Re) {
    const arg = k / (3.7 * D) + 5.74 / Math.pow(Re, 0.9);
    const l = Math.log10(arg);
    return 0.25 / (l * l);
  }

  /**
   * Coefficient de frottement λ selon le régime.
   * @param k rugosité (m), D diamètre intérieur (m), Re Reynolds
   */
  function lambda(k, D, Re) {
    if (Re <= 0) return 0;
    if (Re < 2300) return 64 / Re; // laminaire (Poiseuille)
    if (Re < 4000) {
      // Zone critique : interpolation linéaire entre laminaire (2300)
      // et turbulent Swamee-Jain (4000) — prudence, cas rare en pratique.
      const lLam = 64 / 2300;
      const lTur = swameeJain(k, D, 4000);
      return lLam + ((Re - 2300) / (4000 - 2300)) * (lTur - lLam);
    }
    return swameeJain(k, D, Re);
  }

  /** Vitesse moyenne (m/s). Q en m³/s, D en m. */
  function vitesse(Q, D) {
    const A = (Math.PI / 4) * D * D;
    return Q / A;
  }

  /**
   * Perte de charge d'un tronçon (mCE), pertes singulières comprises.
   * @param opts {Q (m³/s, signé), L (m), Di (mm), k (mm), nu (m²/s),
   *              sommeK (ΣK, optionnel), pctSingulieres (%, optionnel)}
   * @returns {dH (mCE, même signe que Q), v (m/s, >0), Re, lambda,
   *           dHLin, dHSing}
   */
  function perteDeCharge(opts) {
    const Q = Math.abs(opts.Q);
    const D = opts.Di / 1000; // mm → m
    const k = opts.k / 1000;  // mm → m
    const nu = opts.nu || NU_DEFAUT;
    if (Q === 0 || D <= 0) {
      return { dH: 0, v: 0, Re: 0, lambda: 0, dHLin: 0, dHSing: 0 };
    }
    const v = vitesse(Q, D);
    const Re = reynolds(v, D, nu);
    const lam = lambda(k, D, Re);
    const hv = (v * v) / (2 * G); // hauteur cinétique v²/2g
    const dHLin = lam * (opts.L / D) * hv;
    // Pertes singulières : ΣK explicite et/ou pourcentage des pertes linéaires.
    let dHSing = 0;
    if (opts.sommeK) dHSing += opts.sommeK * hv;
    if (opts.pctSingulieres) dHSing += (opts.pctSingulieres / 100) * dHLin;
    const dH = dHLin + dHSing;
    const signe = opts.Q < 0 ? -1 : 1;
    return { dH: signe * dH, v, Re, lambda: lam, dHLin, dHSing };
  }

  /**
   * Résistance dérivée pour Hardy Cross : pour h = r·Q·|Q|,
   * retourne { h (signé), dhdq = |dh/dQ| = 2·|h|/|Q| }.
   */
  function hardyCrossTerme(opts) {
    const r = perteDeCharge(opts);
    const Qa = Math.abs(opts.Q);
    const dhdq = Qa > 1e-12 ? (2 * Math.abs(r.dH)) / Qa : 0;
    return { h: r.dH, dhdq };
  }

  const H = { G, NU_DEFAUT, reynolds, swameeJain, lambda, vitesse, perteDeCharge, hardyCrossTerme };
  if (typeof module !== 'undefined' && module.exports) module.exports = H;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.hydraulics = H;
})();
