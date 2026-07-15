/*
 * network.js — Solveur hydraulique du réseau.
 *
 * Conventions :
 *  - Un tronçon relie le nœud `de` au nœud `vers`. Le débit Q d'un tronçon
 *    est SIGNÉ : positif s'il s'écoule de `de` vers `vers`, négatif sinon.
 *  - Débits internes en m³/h (affichage), convertis en m³/s pour l'hydraulique.
 *  - Charges piézométriques H en m (cote + pression en mCE) ; pressions
 *    relatives aux nœuds en bar : P = (H − cote) / 10,197.
 *
 * Méthode :
 *  - Réseau RAMIFIÉ (arbre) : calcul direct de l'aval vers l'amont
 *    (accumulation des demandes des sous-arbres), puis propagation des
 *    charges de l'amont vers l'aval.
 *  - Réseau MAILLÉ : détection des boucles indépendantes (nombre de mailles
 *    = T − N + 1 pour un réseau connexe), répartition initiale des débits
 *    sur un arbre couvrant (cordes à débit nul), puis équilibrage itératif
 *    de HARDY CROSS : pour chaque maille,
 *        ΔQ = − Σ(h signé) / Σ|dh/dQ|      avec h par Darcy-Weisbach
 *    (dh/dQ = 2·|h|/|Q| puisque h ∝ Q²), λ recalculé à chaque itération
 *    par Swamee-Jain. Critère de convergence et nombre d'itérations
 *    affichés dans l'interface et le rapport.
 */
(function () {
  const isNode = typeof module !== 'undefined' && module.exports;
  const hyd = isNode ? require('./hydraulics.js') : globalThis.RD.hydraulics;
  const sup = isNode ? require('./supply.js') : globalThis.RD.supply;
  const mat = isNode ? require('./materials.js') : globalThis.RD.materials;
  const U = isNode ? require('./units.js') : globalThis.RD.units;

  /* ------------------------------------------------------------------ */
  /* Analyse du graphe                                                    */
  /* ------------------------------------------------------------------ */

  /**
   * Analyse topologique : connexité depuis l'alimentation, arbre couvrant
   * (BFS), cordes et mailles indépendantes.
   */
  function analyserGraphe(noeuds, troncons, idAlimentation) {
    const adj = new Map(); // idNoeud → [{troncon, autre}]
    noeuds.forEach((n) => adj.set(n.id, []));
    troncons.forEach((t) => {
      if (!adj.has(t.de) || !adj.has(t.vers)) return;
      adj.get(t.de).push({ t, autre: t.vers });
      adj.get(t.vers).push({ t, autre: t.de });
    });

    // BFS depuis l'alimentation → arbre couvrant
    const parent = new Map();      // idNoeud → {noeud parent, troncon}
    const ordre = [];              // nœuds dans l'ordre BFS (amont → aval)
    const dansArbre = new Set();   // ids de tronçons de l'arbre
    const visites = new Set([idAlimentation]);
    const file = [idAlimentation];
    while (file.length) {
      const n = file.shift();
      ordre.push(n);
      for (const { t, autre } of adj.get(n) || []) {
        if (!visites.has(autre)) {
          visites.add(autre);
          parent.set(autre, { noeud: n, troncon: t });
          dansArbre.add(t.id);
          file.push(autre);
        }
      }
    }

    const nonConnectes = noeuds.filter((n) => !visites.has(n.id)).map((n) => n.id);
    const cordes = troncons.filter(
      (t) => !dansArbre.has(t.id) && visites.has(t.de) && visites.has(t.vers)
    );

    // Chaque corde définit une maille : corde + chemin dans l'arbre.
    const mailles = cordes.map((corde) => {
      const cheminVers = cheminArbre(parent, corde.vers, idAlimentation);
      const cheminDe = cheminArbre(parent, corde.de, idAlimentation);
      // Retirer la partie commune (ancêtre commun)
      const setDe = new Map(cheminDe.map((e, i) => [e.noeud, i]));
      let iV = cheminVers.length - 1;
      // maille : corde (de→vers), puis remontée de `vers` vers l'ancêtre
      // commun, puis descente vers `de`.
      let ancetre = null;
      for (const e of cheminVers) {
        if (setDe.has(e.noeud)) { ancetre = e.noeud; break; }
      }
      const membres = [{ tronconId: corde.id, sens: +1 }]; // parcours de→vers
      for (const e of cheminVers) {
        if (e.noeud === ancetre) break;
        // Remontée de `vers` vers l'ancêtre commun : on traverse le tronçon
        // parent dans le sens enfant→parent ; sens = +1 si le tronçon est
        // orienté enfant→parent (t.de === enfant), −1 sinon.
        membres.push({ tronconId: e.troncon.id, sens: e.troncon.vers === e.noeud ? -1 : +1 });
      }
      const descente = [];
      for (const e of cheminDe) {
        if (e.noeud === ancetre) break;
        // Descente de l'ancêtre vers `de` : traversée parent→enfant ;
        // sens = +1 si le tronçon est orienté parent→enfant (t.vers === enfant).
        descente.push({ tronconId: e.troncon.id, sens: e.troncon.vers === e.noeud ? +1 : -1 });
      }
      descente.reverse();
      return membres.concat(descente);
    });

    return { adj, parent, ordre, dansArbre, cordes, mailles, nonConnectes, nbMailles: mailles.length };
  }

  /** Chemin d'un nœud vers la racine dans l'arbre couvrant : [{noeud, troncon}]. */
  function cheminArbre(parent, depuis, racine) {
    const chemin = [];
    let n = depuis;
    while (n !== racine && parent.has(n)) {
      const p = parent.get(n);
      chemin.push({ noeud: n, troncon: p.troncon });
      n = p.noeud;
    }
    chemin.push({ noeud: racine, troncon: null });
    return chemin;
  }

  /* ------------------------------------------------------------------ */
  /* Demandes nodales par cas de charge                                   */
  /* ------------------------------------------------------------------ */

  /**
   * Demande (m³/h) à chaque nœud pour un cas de charge.
   * @param cas {type:'pointe'} ou {type:'incendie', hydrants:[idNoeud,...]}
   */
  function demandesNodales(projet, cas) {
    const h = projet.hypotheses;
    const d = new Map();
    for (const n of projet.noeuds) {
      let q = 0;
      const conso = n.consommation || 0; // m³/j
      if (cas.type === 'pointe') {
        // Pointe : consommation journalière / durée de distribution × Cp
        q = (conso / h.dureeDistribution) * h.coeffPointe;
      } else {
        // Incendie : consommation MOYENNE (répartie sur 24 h) + débit incendie
        q = conso / 24;
        if (cas.hydrants && cas.hydrants.includes(n.id)) q += h.debitIncendie;
      }
      d.set(n.id, q);
    }
    return d;
  }

  /* ------------------------------------------------------------------ */
  /* Résolution des débits                                                */
  /* ------------------------------------------------------------------ */

  /**
   * Répartition initiale : débits d'arbre par accumulation aval → amont
   * (les cordes restent à débit nul). Satisfait exactement la continuité.
   */
  function debitsInitiaux(graphe, troncons, demandes) {
    const Q = new Map(troncons.map((t) => [t.id, 0]));
    // Accumulation des demandes des feuilles vers la racine (ordre BFS inversé)
    const cumul = new Map();
    for (let i = graphe.ordre.length - 1; i >= 0; i--) {
      const n = graphe.ordre[i];
      let total = (demandes.get(n) || 0) + (cumul.get(n) || 0);
      const p = graphe.parent.get(n);
      if (p) {
        // Débit du tronçon parent : positif si orienté parent→enfant (de→vers)
        Q.set(p.troncon.id, p.troncon.de === p.noeud ? total : -total);
        cumul.set(p.noeud, (cumul.get(p.noeud) || 0) + total);
      }
    }
    return Q;
  }

  /** Paramètres hydrauliques d'un tronçon pour un débit Q (m³/h). */
  function optionsTroncon(t, Q, projet, diametres) {
    const h = projet.hypotheses;
    const k = mat.rugosite(t.materiau, h.rugosites);
    return {
      Q: Q / 3600, // m³/h → m³/s
      L: t.longueur,
      Di: diametres.get(t.id),
      k,
      nu: h.nu,
      sommeK: t.sommeK || 0,
      pctSingulieres: h.pertesSingulieresPct || 0,
    };
  }

  /**
   * Équilibrage Hardy Cross. Modifie Q en place.
   * @returns {converge, iterations, maxCorrection (m³/h), maxDesequilibre (mCE)}
   */
  function hardyCross(graphe, troncons, Q, projet, diametres, opts) {
    const tolerance = (opts && opts.tolerance) || 1e-3; // m³/h
    const maxIter = (opts && opts.maxIter) || 200;
    const parId = new Map(troncons.map((t) => [t.id, t]));
    let iter = 0;
    let maxCorr = 0;
    for (iter = 1; iter <= maxIter; iter++) {
      maxCorr = 0;
      for (const maille of graphe.mailles) {
        let sommeH = 0;
        let sommeDh = 0;
        for (const m of maille) {
          const t = parId.get(m.tronconId);
          const o = optionsTroncon(t, Q.get(t.id), projet, diametres);
          const { h, dhdq } = hyd.hardyCrossTerme(o);
          sommeH += m.sens * h;
          // dhdq est en mCE/(m³/s) ; les débits de maille sont en m³/h,
          // donc dh/dQ[m³/h] = dh/dQ[m³/s] / 3600.
          sommeDh += dhdq / 3600;
        }
        if (sommeDh < 1e-12) {
          // Maille à débits nuls : amorcer avec une petite correction si
          // le déséquilibre existe (sinon rien à faire).
          if (Math.abs(sommeH) < 1e-9) continue;
          sommeDh = 1e-6;
        }
        const dQ = -sommeH / sommeDh; // m³/h
        for (const m of maille) {
          Q.set(m.tronconId, Q.get(m.tronconId) + m.sens * dQ);
        }
        maxCorr = Math.max(maxCorr, Math.abs(dQ));
      }
      if (maxCorr < tolerance) break;
    }
    // Déséquilibre résiduel de charge par maille (contrôle qualité)
    let maxDeseq = 0;
    for (const maille of graphe.mailles) {
      let sommeH = 0;
      for (const m of maille) {
        const t = parId.get(m.tronconId);
        const o = optionsTroncon(t, Q.get(t.id), projet, diametres);
        sommeH += m.sens * hyd.perteDeCharge(o).dH;
      }
      maxDeseq = Math.max(maxDeseq, Math.abs(sommeH));
    }
    return {
      converge: maxCorr < tolerance,
      iterations: Math.min(iter, maxIter),
      maxCorrection: maxCorr,
      maxDesequilibre: maxDeseq,
    };
  }

  /* ------------------------------------------------------------------ */
  /* Calcul d'un cas de charge complet                                    */
  /* ------------------------------------------------------------------ */

  /**
   * Calcule un cas de charge : débits, vitesses, pertes de charge,
   * charges et pressions nodales.
   * @param diametres Map(tronconId → Di en mm)
   */
  function calculerCas(projet, diametres, cas) {
    const idAlim = projet.alimentation.noeudId;
    const graphe = analyserGraphe(projet.noeuds, projet.troncons, idAlim);
    const demandes = demandesNodales(projet, cas);
    const Q = debitsInitiaux(graphe, projet.troncons, demandes);

    let equilibrage = null;
    if (graphe.mailles.length > 0) {
      equilibrage = hardyCross(graphe, projet.troncons, Q, projet, diametres);
    }

    // Résultats par tronçon
    const troncons = new Map();
    let qTotal = 0;
    demandes.forEach((v) => { qTotal += v; });
    for (const t of projet.troncons) {
      const o = optionsTroncon(t, Q.get(t.id), projet, diametres);
      const r = hyd.perteDeCharge(o);
      troncons.set(t.id, {
        Q: Q.get(t.id),          // m³/h signé (de → vers)
        v: r.v,                   // m/s
        Re: r.Re,
        lambda: r.lambda,
        dH: r.dH,                 // mCE signé
        dHLin: r.dHLin,
        dHSing: r.dHSing,
        Di: diametres.get(t.id),
      });
    }

    // Charges piézométriques : propagation depuis l'alimentation.
    const noeudAlim = projet.noeuds.find((n) => n.id === idAlim);
    const pAlim = sup.pressionDisponible(projet.alimentation, qTotal); // bar
    const H = new Map();
    H.set(idAlim, (noeudAlim.cote || 0) + pAlim * U.MCE_PAR_BAR);
    // BFS sur TOUS les tronçons (pas seulement l'arbre) pour couvrir les mailles
    const aFaire = [idAlim];
    const vus = new Set([idAlim]);
    while (aFaire.length) {
      const n = aFaire.shift();
      for (const { t, autre } of graphe.adj.get(n) || []) {
        if (vus.has(autre)) continue;
        const r = troncons.get(t.id);
        // dH est signé dans le sens de→vers : H(vers) = H(de) − dH
        const hAutre = t.de === n ? H.get(n) - r.dH : H.get(n) + r.dH;
        H.set(autre, hAutre);
        vus.add(autre);
        aFaire.push(autre);
      }
    }

    const noeuds = new Map();
    for (const n of projet.noeuds) {
      const h = H.get(n.id);
      noeuds.set(n.id, {
        H: h,                                             // charge (m)
        p: h === undefined ? NaN : (h - (n.cote || 0)) / U.MCE_PAR_BAR, // bar
        demande: demandes.get(n.id) || 0,                 // m³/h
      });
    }

    return { cas, qTotal, pAlim, troncons, noeuds, equilibrage, graphe };
  }

  /**
   * Calcul complet : cas de pointe + enveloppe incendie.
   *
   * Pratique métier : le débit incendie est appliqué au nœud le plus
   * défavorable. Comme celui-ci n'est pas connu a priori, on calcule un
   * scénario PAR HYDRANT (ou par paire d'hydrants si l'option « 2 hydrants
   * simultanés » est active) ; le scénario critique est celui qui donne la
   * pression résiduelle la plus basse à l'hydrant sollicité. Le débit
   * dimensionnant de chaque tronçon est le MAX (pointe, enveloppe incendie).
   */
  function calculComplet(projet, diametres) {
    const horodatage = new Date().toISOString();
    const casPointe = calculerCas(projet, diametres, { type: 'pointe' });

    const hydrants = projet.noeuds.filter((n) => n.hydrant).map((n) => n.id);
    const scenarios = [];
    if (hydrants.length) {
      const combinaisons = [];
      if (projet.hypotheses.deuxHydrants && hydrants.length >= 2) {
        for (let i = 0; i < hydrants.length; i++) {
          for (let j = i + 1; j < hydrants.length; j++) {
            combinaisons.push([hydrants[i], hydrants[j]]);
          }
        }
      } else {
        hydrants.forEach((h) => combinaisons.push([h]));
      }
      for (const combo of combinaisons) {
        const r = calculerCas(projet, diametres, { type: 'incendie', hydrants: combo });
        // Pression au(x) hydrant(s) sollicité(s) : critère de criticité
        const pMin = Math.min(...combo.map((id) => r.noeuds.get(id).p));
        scenarios.push({ combo, resultat: r, pHydrant: pMin });
      }
    }
    const casIncendie = scenarios.length
      ? scenarios.reduce((a, b) => (b.pHydrant < a.pHydrant ? b : a))
      : null;

    // Débit dimensionnant par tronçon = max(|pointe|, enveloppe incendie)
    const dimensionnant = new Map();
    for (const t of projet.troncons) {
      let qMax = Math.abs(casPointe.troncons.get(t.id).Q);
      let origine = 'pointe';
      for (const s of scenarios) {
        const q = Math.abs(s.resultat.troncons.get(t.id).Q);
        if (q > qMax) { qMax = q; origine = `incendie (${s.combo.join(' + ')})`; }
      }
      dimensionnant.set(t.id, { Q: qMax, origine });
    }

    return { horodatage, casPointe, casIncendie, scenariosIncendie: scenarios, dimensionnant };
  }

  /* ------------------------------------------------------------------ */
  /* Distances entre hydrants (le long des conduites)                     */
  /* ------------------------------------------------------------------ */

  /** Distance minimale le long du réseau de chaque hydrant à son plus proche voisin hydrant. */
  function distancesHydrants(projet) {
    const hydrants = projet.noeuds.filter((n) => n.hydrant).map((n) => n.id);
    if (hydrants.length < 2) return [];
    const adj = new Map();
    projet.noeuds.forEach((n) => adj.set(n.id, []));
    projet.troncons.forEach((t) => {
      adj.get(t.de).push({ vers: t.vers, l: t.longueur });
      adj.get(t.vers).push({ vers: t.de, l: t.longueur });
    });
    // Dijkstra simple depuis chaque hydrant (réseaux de petite taille)
    const resultat = [];
    for (const h of hydrants) {
      const dist = new Map([[h, 0]]);
      const file = [[0, h]];
      while (file.length) {
        file.sort((a, b) => a[0] - b[0]);
        const [d, n] = file.shift();
        if (d > (dist.get(n) ?? Infinity)) continue;
        for (const { vers, l } of adj.get(n) || []) {
          const nd = d + l;
          if (nd < (dist.get(vers) ?? Infinity)) {
            dist.set(vers, nd);
            file.push([nd, vers]);
          }
        }
      }
      let plusProche = null;
      for (const autre of hydrants) {
        if (autre === h) continue;
        const d = dist.get(autre);
        if (d !== undefined && (plusProche === null || d < plusProche.distance)) {
          plusProche = { voisin: autre, distance: d };
        }
      }
      if (plusProche) resultat.push({ hydrant: h, voisin: plusProche.voisin, distance: plusProche.distance });
    }
    return resultat;
  }

  const N = {
    analyserGraphe, demandesNodales, debitsInitiaux, hardyCross,
    calculerCas, calculComplet, distancesHydrants,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = N;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.network = N;
})();
