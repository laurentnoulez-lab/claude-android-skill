/*
 * sizing.js — Dimensionnement automatique des tronçons.
 *
 * Logique métier :
 *  1. Pour chaque tronçon en mode « auto », on part du plus petit diamètre
 *     commercial admissible du matériau choisi (Di ≥ 100 mm si le tronçon
 *     porte un hydrant — CM 14/10/1975).
 *  2. On calcule le réseau, puis on augmente les diamètres tant que :
 *       a) la vitesse au débit dimensionnant dépasse 2 m/s (butée haute), puis
 *       b) la pression résiduelle au nœud le plus défavorable reste sous la
 *          pression minimale exigée : on augmente alors en priorité le
 *          tronçon du chemin critique présentant le plus fort gradient de
 *          perte de charge (ΔH/L) — c'est là qu'un diamètre supérieur
 *          rapporte le plus.
 *  3. Comme les débits dépendent des diamètres dans un réseau maillé, la
 *     procédure est itérative (recalcul complet à chaque ajustement).
 *
 *  Le résultat reste une PROPOSITION d'avant-projet : l'ingénieur peut
 *  forcer chaque diamètre, et le tableau des candidats montre pour chaque
 *  tronçon l'effet de chaque diamètre commercial (v, ΔH, P résiduelle, statut).
 */
(function () {
  const isNode = typeof module !== 'undefined' && module.exports;
  const net = isNode ? require('./network.js') : globalThis.RD.network;
  const mat = isNode ? require('./materials.js') : globalThis.RD.materials;
  const chk = isNode ? require('./checks.js') : globalThis.RD.checks;

  const V_MAX_DIM = 2.0; // m/s — butée haute au débit dimensionnant

  /** Le tronçon porte-t-il un hydrant (à l'une de ses extrémités) ? */
  function porteHydrant(t, projet) {
    const n = (id) => projet.noeuds.find((x) => x.id === id);
    return !!(n(t.de)?.hydrant || n(t.vers)?.hydrant);
  }

  /** Diamètres candidats d'un tronçon (filtrés Di ≥ 100 mm si hydrant). */
  function candidats(t, projet) {
    const liste = mat.diametres(t.materiau);
    return porteHydrant(t, projet) ? liste.filter((d) => d.di >= 100) : liste;
  }

  /** Map(tronconId → Di) à partir des choix courants (forcé ou auto). */
  function mapDiametres(projet, autos) {
    const m = new Map();
    for (const t of projet.troncons) {
      if (t.diametreForce) {
        const d = mat.diametres(t.materiau).find((x) => x.de === t.diametreForce);
        m.set(t.id, d ? d.di : t.diametreForce);
      } else {
        m.set(t.id, autos.get(t.id).di);
      }
    }
    return m;
  }

  /**
   * Dimensionnement automatique.
   * @returns { diametres: Map(id → {designation, de, di, force:bool}),
   *            resultats, iterationsDimensionnement }
   */
  function dimensionner(projet) {
    // Indices courants dans la liste des candidats, pour les tronçons auto
    const listes = new Map();
    const index = new Map();
    for (const t of projet.troncons) {
      const liste = candidats(t, projet);
      if (!liste.length) throw new Error(`Aucun diamètre admissible pour le tronçon ${t.nom || t.id} (hydrant → Di ≥ 100 mm requis).`);
      listes.set(t.id, liste);
      index.set(t.id, 0); // plus petit admissible
    }
    const autoIds = projet.troncons.filter((t) => !t.diametreForce).map((t) => t.id);
    const choixAuto = () => new Map(projet.troncons.map((t) => [t.id, listes.get(t.id)[index.get(t.id)]]));

    let resultats = null;
    let diam = null;
    let iterations = 0;
    const MAX_PASSES = 60;

    for (iterations = 1; iterations <= MAX_PASSES; iterations++) {
      diam = mapDiametres(projet, choixAuto());
      resultats = net.calculComplet(projet, diam);
      let modifie = false;

      // a) Butée de vitesse au débit dimensionnant
      for (const id of autoIds) {
        const t = projet.troncons.find((x) => x.id === id);
        const liste = listes.get(id);
        let i = index.get(id);
        const qDim = resultats.dimensionnant.get(id).Q / 3600; // m³/s
        while (i < liste.length - 1) {
          const di = liste[i].di / 1000;
          const v = qDim / ((Math.PI / 4) * di * di);
          if (v <= V_MAX_DIM) break;
          i++;
        }
        if (i !== index.get(id)) { index.set(id, i); modifie = true; }
      }
      if (modifie) continue;

      // b) Contrainte de pression au nœud le plus défavorable
      const pmin = projet.hypotheses.pressionMinimale;
      if (pmin === null || pmin === undefined || pmin === '') break;
      const pire = chk.noeudLePlusDefavorable(projet, resultats);
      if (!pire || pire.p >= pmin) break;

      // Chemin critique : tronçons entre l'alimentation et le nœud critique
      // dans le cas correspondant ; on prend celui au plus fort ΔH/L encore
      // augmentable.
      const casR = pire.cas === 'pointe' || !resultats.casIncendie
        ? resultats.casPointe
        : resultats.casIncendie.resultat;
      const chemin = cheminCritique(projet, casR, pire.id);
      let meilleur = null;
      for (const id of chemin) {
        if (!autoIds.includes(id)) continue;
        if (index.get(id) >= listes.get(id).length - 1) continue;
        const t = projet.troncons.find((x) => x.id === id);
        const grad = Math.abs(casR.troncons.get(id).dH) / t.longueur;
        if (!meilleur || grad > meilleur.grad) meilleur = { id, grad };
      }
      if (!meilleur) {
        // Aucun tronçon du chemin critique augmentable : tenter n'importe
        // quel tronçon auto encore augmentable, sinon abandonner.
        const candidat = autoIds.find((id) => index.get(id) < listes.get(id).length - 1);
        if (!candidat) break;
        meilleur = { id: candidat };
      }
      index.set(meilleur.id, index.get(meilleur.id) + 1);
    }

    const choix = new Map();
    const autos = choixAuto();
    for (const t of projet.troncons) {
      if (t.diametreForce) {
        const d = mat.diametres(t.materiau).find((x) => x.de === t.diametreForce);
        choix.set(t.id, { ...(d || { designation: `Ø ${t.diametreForce}`, de: t.diametreForce, di: t.diametreForce }), force: true });
      } else {
        choix.set(t.id, { ...autos.get(t.id), force: false });
      }
    }
    return { diametres: choix, resultats, iterationsDimensionnement: iterations };
  }

  /** Suivre le gradient hydraulique : tronçons du chemin alimentation → nœud. */
  function cheminCritique(projet, casR, idNoeud) {
    // Remonter depuis le nœud critique en suivant les débits entrants
    const chemin = [];
    let courant = idNoeud;
    const vus = new Set([courant]);
    const idAlim = projet.alimentation.noeudId;
    while (courant !== idAlim) {
      let entrant = null;
      for (const t of projet.troncons) {
        const r = casR.troncons.get(t.id);
        if (!r) continue;
        // tronçon dont l'eau ARRIVE en `courant`
        if (t.vers === courant && r.Q > 1e-9 && !vus.has(t.de)) entrant = { t, amont: t.de };
        if (t.de === courant && r.Q < -1e-9 && !vus.has(t.vers)) entrant = { t, amont: t.vers };
        if (entrant) break;
      }
      if (!entrant) break;
      chemin.push(entrant.t.id);
      courant = entrant.amont;
      vus.add(courant);
    }
    return chemin;
  }

  /**
   * Tableau des diamètres candidats pour un tronçon : pour chaque diamètre
   * commercial du matériau, recalcul du réseau avec ce seul diamètre
   * substitué → vitesse, ΔH, pression au nœud le plus défavorable, statut.
   */
  function tableauCandidats(projet, diametresActuels, tronconId) {
    const t = projet.troncons.find((x) => x.id === tronconId);
    const doitPorterHydrant = porteHydrant(t, projet);
    const pmin = projet.hypotheses.pressionMinimale;
    const lignes = [];
    for (const d of mat.diametres(t.materiau)) {
      const diam = new Map(diametresActuels);
      diam.set(tronconId, d.di);
      const r = net.calculComplet(projet, diam);
      const qDim = r.dimensionnant.get(tronconId).Q;
      const rPointe = r.casPointe.troncons.get(tronconId);
      const rInc = r.casIncendie ? r.casIncendie.resultat.troncons.get(tronconId) : null;
      const vDim = Math.max(rPointe.v, rInc ? rInc.v : 0);
      const dHDim = Math.max(Math.abs(rPointe.dH), rInc ? Math.abs(rInc.dH) : 0);
      const pire = chk.noeudLePlusDefavorable(projet, r);
      const statuts = [];
      if (doitPorterHydrant && d.di < 100) statuts.push('Ø < 100 mm (hydrant) — NOK bloquant');
      if (vDim > 2.0) statuts.push('v > 2 m/s');
      if (pmin !== null && pmin !== undefined && pmin !== '' && pire && pire.p < pmin) {
        statuts.push(`P ${pire.p.toFixed(2)} bar < ${pmin} bar`);
      }
      if (rPointe.v < 0.3) statuts.push('v pointe < 0,3 m/s (stagnation)');
      lignes.push({
        designation: d.designation, de: d.de, di: d.di,
        qDim, vDim, dH: dHDim,
        pResiduelle: pire ? pire.p : NaN,
        ok: statuts.length === 0 || (statuts.length === 1 && statuts[0].includes('stagnation')),
        statuts,
      });
    }
    return lignes;
  }

  const S = { dimensionner, tableauCandidats, candidats, porteHydrant, mapDiametres };
  if (typeof module !== 'undefined' && module.exports) module.exports = S;
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.sizing = S;
})();
