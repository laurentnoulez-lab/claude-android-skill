/*
 * materials.js — Bibliothèque de matériaux et diamètres commerciaux.
 *
 * POINT CRITIQUE MÉTIER : le calcul hydraulique utilise le diamètre
 * INTÉRIEUR RÉEL (Di), pas le diamètre extérieur (De) ni le DN nominal.
 * Pour les tubes plastiques (PVC-U, PEHD), l'épaisseur de paroi réduit
 * fortement le Di : un PEHD De110 SDR11 n'a que 90 mm de passage.
 *
 * Tables De / épaisseur / Di :
 *  - PVC-U pression selon EN 1452 : PN10 = SDR21 (S10), PN16 = SDR13,6 (S6,3).
 *  - PEHD PE100 selon EN 12201 : SDR17 (PN10), SDR11 (PN16). Di = De − 2e.
 *  - Fonte ductile (EN 545, classes usuelles C40/C25, revêtement ciment) :
 *    Di ≈ DN (le DN de la fonte désigne par convention le diamètre intérieur).
 *
 * Rugosités k par défaut : valeurs PRUDENTES « en service » (conduite âgée,
 * dépôts, joints), ordres de grandeur de la littérature (Lencastre, Idel'cik,
 * guides distributeurs). Ce sont des HYPOTHÈSES à justifier dans la note de
 * calcul, pas des valeurs normatives ; elles sont modifiables par l'utilisateur.
 */
(function () {
  const MATERIAUX = {
    'pvc-pn10': {
      nom: 'PVC-U PN10 (EN 1452, SDR21)',
      famille: 'PVC-U',
      kDefaut: 0.05, // mm — plastique « en service » (neuf : ~0,01 mm)
      // [De (mm), épaisseur e (mm)] → Di = De − 2e
      tubes: [
        [63, 3.0], [75, 3.6], [90, 4.3], [110, 5.3], [125, 6.0],
        [140, 6.7], [160, 7.7], [200, 9.6], [225, 10.8], [250, 11.9], [315, 15.0],
      ],
    },
    'pvc-pn16': {
      nom: 'PVC-U PN16 (EN 1452, SDR13,6)',
      famille: 'PVC-U',
      kDefaut: 0.05,
      tubes: [
        [63, 4.7], [75, 5.6], [90, 6.7], [110, 8.2], [125, 9.3],
        [140, 10.4], [160, 11.9], [200, 14.9], [225, 16.7], [250, 18.6], [315, 23.4],
      ],
    },
    'pehd-sdr17': {
      nom: 'PEHD PE100 SDR17 (PN10)',
      famille: 'PEHD',
      kDefaut: 0.05,
      tubes: [
        [63, 3.8], [75, 4.5], [90, 5.4], [110, 6.6], [125, 7.4], [140, 8.3],
        [160, 9.5], [180, 10.7], [200, 11.9], [225, 13.4], [250, 14.8], [315, 18.7],
      ],
    },
    'pehd-sdr11': {
      nom: 'PEHD PE100 SDR11 (PN16)',
      famille: 'PEHD',
      kDefaut: 0.05,
      tubes: [
        [63, 5.8], [75, 6.8], [90, 8.2], [110, 10.0], [125, 11.4], [140, 12.7],
        [160, 14.6], [180, 16.4], [200, 18.2], [225, 20.5], [250, 22.7], [315, 28.6],
      ],
    },
    'fonte': {
      nom: 'Fonte ductile revêtue ciment (EN 545)',
      famille: 'Fonte',
      kDefaut: 0.1, // mm — fonte revêtue ciment en service (neuve : ~0,03 mm)
      // Pour la fonte, le DN correspond au diamètre intérieur (Di ≈ DN).
      dns: [60, 80, 100, 125, 150, 200, 250, 300],
    },
  };

  /**
   * Liste des diamètres candidats d'un matériau, triés par Di croissant.
   * @returns [{designation, de, di}] — di en mm (diamètre intérieur réel)
   */
  function diametres(materiauId) {
    const m = MATERIAUX[materiauId];
    if (!m) throw new Error(`Matériau inconnu : ${materiauId}`);
    if (m.dns) {
      return m.dns.map((dn) => ({ designation: `DN ${dn}`, de: dn, di: dn }));
    }
    return m.tubes.map(([de, e]) => ({
      designation: `De ${de}`,
      de,
      di: Math.round((de - 2 * e) * 10) / 10,
    }));
  }

  /** Rugosité par défaut du matériau (mm), éventuellement surchargée par l'utilisateur. */
  function rugosite(materiauId, surcharges) {
    if (surcharges && surcharges[materiauId] !== undefined && surcharges[materiauId] !== null) {
      return surcharges[materiauId];
    }
    return MATERIAUX[materiauId].kDefaut;
  }

  const M = { MATERIAUX, diametres, rugosite };
  if (typeof module !== 'undefined' && module.exports) module.exports = M;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.materials = M;
})();
