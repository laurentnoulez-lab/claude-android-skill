/*
 * example.js — Projet d'exemple pré-chargé.
 *
 * Cas type : desserte d'une rue d'un parc d'activités économiques.
 *  - Piquage sur une conduite DN150 fonte existante.
 *    Essai débit-pression : P0 = 4,5 bar (statique) ; Q1 = 40 m³/h → P1 = 3,8 bar.
 *  - Rue de 10 entreprises (5 de chaque côté), 3 m³/j chacune,
 *    distribution sur 10 h, coefficient de pointe Cp = 2,5.
 *  - Boucle de 2 × 250 m refermée en bout de rue (maillage).
 *  - Hydrants tous les 100 m ; débit incendie 60 m³/h (CM 14/10/1975).
 *  - Pression minimale exigée : 1,5 bar (exemple — À CONFIRMER par le
 *    distributeur dans un projet réel).
 *
 * Résultats attendus (vérifiés par les tests unitaires) :
 *  - Débit dimensionnant ≈ 17,0 l/s (incendie 60 m³/h + conso moyenne 1,25 m³/h).
 *  - En DN100 fonte, boucle fermée : v ≈ 1,08 m/s par branche,
 *    ΔH ≈ 3,4 mCE par branche de 250 m, P résiduelle ≈ 2,6 bar en bout de rue.
 */
(function () {
  function projetExemple() {
    return {
      version: 1,
      meta: {
        nom: 'Exemple — Desserte parc d’activités (rue en boucle)',
        auteur: '',
        bureau: '',
        maitreOuvrage: '',
        distributeur: 'SWDE (exemple)',
        indice: 'A',
        date: new Date().toISOString().slice(0, 10),
        description:
          'Projet d’exemple : piquage sur DN150 fonte existant, rue de 10 entreprises, boucle 2 × 250 m, hydrants tous les 100 m.',
      },
      hypotheses: {
        nu: 1.31e-6,              // m²/s — eau à 10 °C
        coeffPointe: 2.5,          // coefficient de pointe (à confirmer / distributeur)
        dureeDistribution: 10,     // h/j
        debitIncendie: 60,         // m³/h par hydrant (CM 14/10/1975 : 1 000 l/min)
        deuxHydrants: false,       // option « 2 hydrants simultanés »
        pressionMinimale: 1.5,     // bar — EXEMPLE, à confirmer par écrit (distributeur / zone de secours)
        pertesSingulieresPct: 0,   // % des pertes linéaires (optionnel)
        rugosites: {},             // surcharges éventuelles de k par matériau (mm)
      },
      alimentation: {
        noeudId: 'N0',
        mode: 'essai',             // courbe issue d'un essai débit-pression
        p0: 4.5,                   // bar (pression statique au piquage)
        q1: 40,                    // m³/h (débit d'essai)
        p1: 3.8,                   // bar (pression résiduelle sous Q1)
      },
      // Schéma : le dessin est SCHÉMATIQUE (longueurs saisies, pas mesurées).
      noeuds: [
        { id: 'N0', nom: 'Piquage DN150', x: 80,  y: 220, cote: 0, consommation: 0,  hydrant: false, type: 'alimentation' },
        // Côté nord de la rue (branche 1)
        { id: 'N1', nom: 'H1 (nord 100 m)', x: 240, y: 120, cote: 0, consommation: 6, hydrant: true,  type: 'consommation' },
        { id: 'N2', nom: 'H2 (nord 200 m)', x: 400, y: 120, cote: 0, consommation: 6, hydrant: true,  type: 'consommation' },
        // Bout de rue (fermeture de la boucle)
        { id: 'N3', nom: 'H3 (bout de rue)', x: 560, y: 220, cote: 0, consommation: 6, hydrant: true, type: 'consommation' },
        // Côté sud de la rue (branche 2)
        { id: 'N4', nom: 'H4 (sud 100 m)',  x: 240, y: 320, cote: 0, consommation: 6, hydrant: true,  type: 'consommation' },
        { id: 'N5', nom: 'H5 (sud 200 m)',  x: 400, y: 320, cote: 0, consommation: 6, hydrant: true,  type: 'consommation' },
      ],
      troncons: [
        { id: 'T1', nom: 'Nord 1', de: 'N0', vers: 'N1', longueur: 100, materiau: 'fonte', diametreForce: 100, sommeK: 0 },
        { id: 'T2', nom: 'Nord 2', de: 'N1', vers: 'N2', longueur: 100, materiau: 'fonte', diametreForce: 100, sommeK: 0 },
        { id: 'T3', nom: 'Nord 3', de: 'N2', vers: 'N3', longueur: 50,  materiau: 'fonte', diametreForce: 100, sommeK: 0 },
        { id: 'T4', nom: 'Sud 1',  de: 'N0', vers: 'N4', longueur: 100, materiau: 'fonte', diametreForce: 100, sommeK: 0 },
        { id: 'T5', nom: 'Sud 2',  de: 'N4', vers: 'N5', longueur: 100, materiau: 'fonte', diametreForce: 100, sommeK: 0 },
        { id: 'T6', nom: 'Sud 3',  de: 'N5', vers: 'N3', longueur: 50,  materiau: 'fonte', diametreForce: 100, sommeK: 0 },
      ],
      resultats: null,
    };
  }

  const E = { projetExemple };
  if (typeof module !== 'undefined' && module.exports) module.exports = E;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.example = E;
})();
