/*
 * checks.js — Validation des saisies et contrôles réglementaires.
 *
 * Références métier :
 *  - Circulaire ministérielle belge du 14/10/1975 (ressources en eau pour
 *    l'extinction des incendies) : Ø intérieur ≥ 100 mm pour les conduites
 *    portant un hydrant (contrôle BLOQUANT), interdistance des hydrants
 *    ≤ 100 m en zone industrielle/commerciale, débit de référence
 *    60 m³/h (1 000 l/min) pendant 2 h.
 *  - AR du 06/05/1971 + règlement-type (art. 36) : consultation préalable
 *    du service incendie / de la zone de secours.
 *  - Plage de vitesses de bonne pratique en pointe : 0,5 – 1,5 m/s ;
 *    < 0,3 m/s → risque de stagnation (qualité d'eau) ;
 *    > 2 m/s → pertes de charge élevées et risque de coup de bélier.
 *  - La pression minimale exigée relève du DISTRIBUTEUR et de la zone de
 *    secours : champ à compléter par l'utilisateur, JAMAIS de valeur par
 *    défaut proposée par l'application.
 */
(function () {
  const isNode = typeof module !== 'undefined' && module.exports;
  const net = isNode ? require('./network.js') : globalThis.RD.network;

  const MENTION_AVANT_PROJET =
    'Dimensionnement d’avant-projet — validation par le distributeur et ' +
    'consultation préalable de la zone de secours obligatoires ' +
    '(AR 06/05/1971, règlement-type art. 36 ; CM 14/10/1975 art. 1.5).';

  /* ------------------------------------------------------------------ */
  /* Validation des saisies (erreurs bloquantes AVANT calcul)             */
  /* ------------------------------------------------------------------ */

  function validerProjet(projet) {
    const erreurs = [];
    const ids = new Set();
    if (!projet.noeuds.length) erreurs.push('Le réseau ne contient aucun nœud.');
    for (const n of projet.noeuds) {
      if (ids.has(n.id)) erreurs.push(`Identifiant de nœud dupliqué : ${n.id}.`);
      ids.add(n.id);
      if (n.cote === undefined || n.cote === null || Number.isNaN(n.cote)) {
        erreurs.push(`Nœud « ${n.nom || n.id} » : cote altimétrique manquante.`);
      }
      if ((n.consommation || 0) < 0) {
        erreurs.push(`Nœud « ${n.nom || n.id} » : la consommation ne peut pas être négative.`);
      }
    }
    const idsT = new Set();
    for (const t of projet.troncons) {
      if (idsT.has(t.id)) erreurs.push(`Identifiant de tronçon dupliqué : ${t.id}.`);
      idsT.add(t.id);
      if (!(t.longueur > 0)) {
        erreurs.push(`Tronçon « ${t.nom || t.id} » : longueur manquante ou non positive (saisie manuelle obligatoire, le schéma n'est pas à l'échelle).`);
      }
      if ((t.sommeK || 0) < 0) {
        erreurs.push(`Tronçon « ${t.nom || t.id} » : ΣK ne peut pas être négatif.`);
      }
      if (!ids.has(t.de) || !ids.has(t.vers)) {
        erreurs.push(`Tronçon « ${t.nom || t.id} » : extrémité inconnue.`);
      }
      if (t.de === t.vers) {
        erreurs.push(`Tronçon « ${t.nom || t.id} » : relie un nœud à lui-même.`);
      }
    }
    const alim = projet.alimentation;
    if (!alim || !alim.noeudId || !ids.has(alim.noeudId)) {
      erreurs.push('Aucun nœud d’alimentation défini.');
    }
    if (alim) {
      if (alim.p0 === undefined || alim.p0 === null || Number.isNaN(alim.p0)) {
        erreurs.push('Alimentation : pression P0 manquante.');
      } else if (alim.p0 < 0) {
        erreurs.push('Alimentation : P0 doit être une pression relative positive (bar).');
      }
      if (alim.mode === 'essai') {
        if (!(alim.q1 > 0)) erreurs.push('Alimentation (essai débit-pression) : Q1 doit être > 0.');
        if (alim.p1 === undefined || alim.p1 === null || Number.isNaN(alim.p1)) {
          erreurs.push('Alimentation (essai débit-pression) : P1 manquante.');
        } else if (alim.p1 > alim.p0) {
          erreurs.push('Alimentation : P1 (sous débit) doit être ≤ P0 (statique).');
        }
      }
    }
    const h = projet.hypotheses;
    if (!(h.dureeDistribution > 0 && h.dureeDistribution <= 24)) {
      erreurs.push('Hypothèses : la durée de distribution doit être comprise entre 0 et 24 h.');
    }
    if (!(h.coeffPointe > 0)) erreurs.push('Hypothèses : coefficient de pointe manquant ou non positif.');
    if (!(h.debitIncendie >= 0)) erreurs.push('Hypothèses : débit incendie manquant.');
    if (!(h.nu > 0)) erreurs.push('Hypothèses : viscosité cinématique manquante ou non positive.');

    // Connexité
    if (alim && ids.has(alim.noeudId) && projet.noeuds.length) {
      const g = net.analyserGraphe(projet.noeuds, projet.troncons, alim.noeudId);
      for (const id of g.nonConnectes) {
        const n = projet.noeuds.find((x) => x.id === id);
        erreurs.push(`Nœud « ${n.nom || id} » non raccordé au point d'alimentation.`);
      }
    }
    return erreurs;
  }

  /* ------------------------------------------------------------------ */
  /* Contrôles réglementaires et de bonne pratique (APRÈS calcul)         */
  /* ------------------------------------------------------------------ */

  /**
   * @param projet le projet
   * @param resultats sortie de network.calculComplet
   * @param diametres Map(tronconId → Di mm)
   * @returns liste [{niveau: 'nok'|'avertissement'|'info', bloquant, code, message, cibles}]
   */
  function controler(projet, resultats, diametres) {
    const c = [];
    const nomT = (t) => t.nom || t.id;
    const nomN = (n) => n.nom || n.id;
    const parIdN = new Map(projet.noeuds.map((n) => [n.id, n]));

    // 1. Ø ≥ 100 mm sur les tronçons portant un hydrant (CM 14/10/1975) — BLOQUANT
    for (const t of projet.troncons) {
      const porteHydrant = parIdN.get(t.de)?.hydrant || parIdN.get(t.vers)?.hydrant;
      if (porteHydrant && diametres.get(t.id) < 100) {
        c.push({
          niveau: 'nok', bloquant: true, code: 'HYDRANT_DIAMETRE', cibles: [t.id],
          message: `Tronçon « ${nomT(t)} » : diamètre intérieur ${diametres.get(t.id)} mm < 100 mm alors qu'il alimente un hydrant. La CM du 14/10/1975 impose un Ø intérieur ≥ 100 mm pour les conduites portant un hydrant. Contrôle BLOQUANT : augmentez le diamètre ou déplacez l'hydrant.`,
        });
      }
    }

    // 2. Interdistance des hydrants ≤ 100 m (zones industrielles/commerciales)
    for (const d of net.distancesHydrants(projet)) {
      if (d.distance > 100) {
        const n1 = parIdN.get(d.hydrant);
        const n2 = parIdN.get(d.voisin);
        c.push({
          niveau: 'avertissement', bloquant: false, code: 'HYDRANT_DISTANCE', cibles: [d.hydrant],
          message: `Hydrant « ${nomN(n1)} » : l'hydrant le plus proche (« ${nomN(n2)} ») est à ${Math.round(d.distance)} m le long des conduites, au-delà des 100 m recommandés en zone industrielle/commerciale (CM 14/10/1975). À vérifier avec la zone de secours.`,
        });
      }
    }

    // 3. Vitesses en pointe : plage indicative 0,5–1,5 m/s
    for (const t of projet.troncons) {
      const vPointe = resultats.casPointe.troncons.get(t.id).v;
      const vDim = Math.max(
        vPointe,
        resultats.casIncendie ? resultats.casIncendie.resultat.troncons.get(t.id).v : 0
      );
      if (vPointe < 0.3 && vPointe >= 0) {
        c.push({
          niveau: 'avertissement', bloquant: false, code: 'VITESSE_FAIBLE', cibles: [t.id],
          message: `Tronçon « ${nomT(t)} » : vitesse en pointe ${vPointe.toFixed(2)} m/s < 0,3 m/s → risque de stagnation et de dégradation de la qualité d'eau (temps de séjour). Envisager un diamètre plus faible si les contrôles incendie le permettent, ou prévoir des purges.`,
        });
      }
      if (vDim > 2.0) {
        c.push({
          niveau: 'avertissement', bloquant: false, code: 'VITESSE_ELEVEE', cibles: [t.id],
          message: `Tronçon « ${nomT(t)} » : vitesse maximale ${vDim.toFixed(2)} m/s > 2 m/s → pertes de charge élevées et risque de coup de bélier. Envisager un diamètre supérieur.`,
        });
      } else if (vPointe > 1.5) {
        c.push({
          niveau: 'info', bloquant: false, code: 'VITESSE_HAUTE_PLAGE', cibles: [t.id],
          message: `Tronçon « ${nomT(t)} » : vitesse en pointe ${vPointe.toFixed(2)} m/s au-dessus de la plage indicative 0,5–1,5 m/s.`,
        });
      }
    }

    // 4. Pression résiduelle au nœud le plus défavorable ≥ pression minimale exigée
    const pmin = projet.hypotheses.pressionMinimale;
    const noeudCritique = noeudLePlusDefavorable(projet, resultats);
    if (pmin === null || pmin === undefined || pmin === '') {
      c.push({
        niveau: 'avertissement', bloquant: false, code: 'PMIN_MANQUANTE', cibles: [],
        message: 'Pression minimale exigée non renseignée. Cette valeur relève du distributeur (et de la zone de secours pour le cas incendie) : elle doit être confirmée PAR ÉCRIT et saisie dans les hypothèses — l’application ne propose volontairement aucune valeur par défaut.',
      });
    } else if (noeudCritique) {
      const n = parIdN.get(noeudCritique.id);
      if (noeudCritique.p < pmin) {
        c.push({
          niveau: 'nok', bloquant: false, code: 'PRESSION_INSUFFISANTE', cibles: [noeudCritique.id],
          message: `Pression résiduelle au nœud le plus défavorable « ${nomN(n)} » : ${noeudCritique.p.toFixed(2)} bar < ${pmin} bar exigés (cas ${noeudCritique.cas}). Augmenter les diamètres, revoir le point de piquage ou renégocier l'exigence avec le distributeur.`,
        });
      } else {
        c.push({
          niveau: 'info', bloquant: false, code: 'PRESSION_OK', cibles: [noeudCritique.id],
          message: `Pression résiduelle au nœud le plus défavorable « ${nomN(n)} » : ${noeudCritique.p.toFixed(2)} bar ≥ ${pmin} bar exigés (cas ${noeudCritique.cas}).`,
        });
      }
    }

    // 5. Pressions négatives (physiquement inadmissibles en distribution)
    const casAControler = [
      ['pointe', resultats.casPointe],
      ...(resultats.casIncendie ? [['incendie', resultats.casIncendie.resultat]] : []),
    ];
    for (const [nomCas, r] of casAControler) {
      for (const n of projet.noeuds) {
        const p = r.noeuds.get(n.id).p;
        if (p < 0) {
          c.push({
            niveau: 'nok', bloquant: false, code: 'PRESSION_NEGATIVE', cibles: [n.id],
            message: `Nœud « ${nomN(n)} » : pression calculée ${p.toFixed(2)} bar < 0 en cas ${nomCas} — le réseau ne peut pas fournir ce débit. Redimensionner ou revoir l'alimentation.`,
          });
        }
      }
    }

    // 6. Convergence Hardy Cross
    for (const [nomCas, r] of casAControler) {
      if (r.equilibrage && !r.equilibrage.converge) {
        c.push({
          niveau: 'nok', bloquant: false, code: 'NON_CONVERGENCE', cibles: [],
          message: `Équilibrage Hardy Cross non convergé pour le cas ${nomCas} (correction résiduelle ${r.equilibrage.maxCorrection.toFixed(4)} m³/h après ${r.equilibrage.iterations} itérations). Résultats à ne pas exploiter en l'état.`,
        });
      }
    }

    return c;
  }

  /**
   * Nœud le plus défavorable : pression résiduelle minimale, tous cas
   * confondus (pointe et scénario incendie critique).
   */
  function noeudLePlusDefavorable(projet, resultats) {
    let pire = null;
    const explorer = (nomCas, r) => {
      for (const n of projet.noeuds) {
        const p = r.noeuds.get(n.id).p;
        if (Number.isFinite(p) && (pire === null || p < pire.p)) {
          pire = { id: n.id, p, cas: nomCas };
        }
      }
    };
    explorer('pointe', resultats.casPointe);
    if (resultats.casIncendie) {
      explorer(
        `incendie (hydrant ${resultats.casIncendie.combo.join(' + ')})`,
        resultats.casIncendie.resultat
      );
    }
    return pire;
  }

  const C = { MENTION_AVANT_PROJET, validerProjet, controler, noeudLePlusDefavorable };
  if (typeof module !== 'undefined' && module.exports) module.exports = C;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.checks = C;
})();
