/*
 * rapport.js — Contenu structuré de la note de calcul, partagé entre
 * l'impression PDF (HTML + print.css) et l'export DOCX.
 */
(function () {
  const st = () => RD.stateModule.state;
  const fmt = (x, d) => RD.units.fmt(x, d);

  /** Sections textuelles fixes de la note (hypothèses, méthode, références). */
  function sectionsTexte(projet) {
    const h = projet.hypotheses;
    return {
      conventions: [
        'Toutes les pressions sont des pressions RELATIVES (manométriques), exprimées en bar.',
        'Débits en m³/h et l/s (1 l/s = 3,6 m³/h) ; longueurs en m ; diamètres INTÉRIEURS réels en mm ; vitesses en m/s ; pertes de charge en mCE et bar (1 bar = 10,197 mCE) ; cotes altimétriques en m.',
        'Le schéma du réseau est schématique et non à l’échelle : les longueurs de tronçons sont des données saisies.',
      ],
      methode: [
        'Pertes de charge linéaires par Darcy-Weisbach : ΔH = λ·(L/D)·v²/(2g).',
        'Coefficient de frottement λ par la formule explicite de Swamee-Jain (approximation de Colebrook-White, précision ≈ ±1 % sur le domaine usuel) : λ = 0,25 / [log10(k/(3,7·D) + 5,74/Re^0,9)]².',
        `Viscosité cinématique retenue : ν = ${h.nu.toExponential(2)} m²/s.`,
        h.pertesSingulieresPct
          ? `Pertes singulières : majoration globale de ${h.pertesSingulieresPct} % des pertes linéaires (+ ΣK par tronçon le cas échéant).`
          : 'Pertes singulières : ΣK par tronçon le cas échéant (pas de majoration globale).',
        'Réseau ramifié : calcul direct de l’aval vers l’amont. Réseau maillé : équilibrage itératif de Hardy Cross (λ recalculé à chaque itération ; critère de convergence et nombre d’itérations rapportés).',
        'Les pressions résiduelles intègrent la dénivelée (cotes altimétriques des nœuds).',
        projet.alimentation.mode === 'essai'
          ? 'Alimentation : courbe caractéristique issue d’un essai débit-pression, extrapolée par P(Q) = P0 − (P0 − P1)·(Q/Q1)^1,852 (exposant de Hazen-Williams — règle de l’art d’avant-projet).'
          : 'Alimentation : pression supposée constante au piquage (hypothèse forte, à valider par le distributeur).',
      ],
      casDeCharge: [
        `Cas 1 — Pointe de consommation : Σ(consommations journalières) / ${h.dureeDistribution} h × coefficient de pointe ${h.coeffPointe}.`,
        `Cas 2 — Incendie + consommation moyenne : débit incendie de ${h.debitIncendie} m³/h par hydrant (défaut 60 m³/h = 1 000 l/min, CM 14/10/1975), appliqué à l’hydrant le plus défavorable${h.deuxHydrants ? ' (option 2 hydrants simultanés active)' : ''}, consommations réparties sur 24 h.`,
        'Débit dimensionnant par tronçon = max des deux cas (enveloppe des scénarios incendie).',
      ],
      references: [
        'Circulaire ministérielle du 14/10/1975 relative aux ressources en eau pour l’extinction des incendies (Ø intérieur ≥ 100 mm pour les conduites portant un hydrant ; interdistance ≤ 100 m en zone industrielle/commerciale ; 60 m³/h ≈ 1 000 l/min).',
        'AR du 06/05/1971 et règlement-type (art. 36) : consultation préalable du service incendie / de la zone de secours.',
        'Swamee P.K. & Jain A.K. (1976), Explicit equations for pipe-flow problems, J. Hydr. Div. ASCE.',
        'Hardy Cross (1936), Analysis of flow in networks of conduits or conductors.',
      ],
      reserves: [
        RD.checks.MENTION_AVANT_PROJET,
        'Valeurs de pression minimale exigée et de coefficient de pointe À CONFIRMER PAR ÉCRIT par le distributeur (et la zone de secours pour le volet incendie).',
        'Les rugosités retenues sont des hypothèses prudentes « en service » (ordres de grandeur de la littérature), à justifier — pas des valeurs normatives.',
        'Hors du champ de la présente note : coup de bélier, temps de séjour / qualité d’eau, réseaux maillés complexes (à confier à EPANET ou au modèle du distributeur).',
      ],
    };
  }

  /** Lignes du tableau tronçons pour le rapport. */
  function lignesTroncons(projet, resultats, diametres) {
    const inc = resultats.casIncendie;
    return projet.troncons.map((t) => {
      const d = diametres.get(t.id);
      const rp = resultats.casPointe.troncons.get(t.id);
      const ri = inc ? inc.resultat.troncons.get(t.id) : null;
      const dm = resultats.dimensionnant.get(t.id);
      const k = RD.materials.rugosite(t.materiau, projet.hypotheses.rugosites);
      return {
        id: t.id, nom: t.nom || t.id, longueur: t.longueur,
        materiau: RD.materials.MATERIAUX[t.materiau].nom, k,
        diametre: `${d.designation} (Di ${d.di} mm)${d.force ? ' — forcé' : ''}`,
        di: d.di, sommeK: t.sommeK || 0,
        qDim: dm.Q, origine: dm.origine,
        qPointe: Math.abs(rp.Q), vPointe: rp.v, dHPointe: Math.abs(rp.dH),
        qInc: ri ? Math.abs(ri.Q) : null, vInc: ri ? ri.v : null,
        dHInc: ri ? Math.abs(ri.dH) : null,
        lambda: ri ? ri.lambda : rp.lambda,
        re: ri ? ri.Re : rp.Re,
      };
    });
  }

  /** Lignes du tableau nœuds pour le rapport. */
  function lignesNoeuds(projet, resultats) {
    const inc = resultats.casIncendie;
    return projet.noeuds.map((n) => {
      const np = resultats.casPointe.noeuds.get(n.id);
      const ni = inc ? inc.resultat.noeuds.get(n.id) : null;
      return {
        id: n.id, nom: n.nom || n.id, cote: n.cote || 0,
        conso: n.consommation || 0, hydrant: !!n.hydrant,
        alim: projet.alimentation.noeudId === n.id,
        demandePointe: np.demande, pPointe: np.p,
        demandeInc: ni ? ni.demande : null, pInc: ni ? ni.p : null,
      };
    });
  }

  /** Le schéma SVG rendu en PNG (dataURL) pour insertion dans les rapports. */
  async function schemaEnPNG() {
    const svg = document.getElementById('svg-schema');
    if (!svg) return null;
    const clone = svg.cloneNode(true);
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    clone.setAttribute('width', '1400');
    clone.setAttribute('height', '860');
    // Styles inline (le PNG ne voit pas la feuille de style)
    const style = document.createElementNS('http://www.w3.org/2000/svg', 'style');
    style.textContent = `
      .svg-troncon{stroke:#607d8b;stroke-width:3.5;fill:none}
      .svg-troncon.ok{stroke:#1e8e3e}.svg-troncon.warn{stroke:#e8710a}.svg-troncon.nok{stroke:#c5221f}
      .svg-noeud .forme{fill:#fff;stroke:#37474f;stroke-width:2}
      .svg-noeud.alimentation .forme{fill:#0b5394;stroke:#0b5394}
      .svg-noeud.hydrant .forme{stroke:#c5221f}
      .svg-noeud.p-ok .forme{fill:#d7f0dd}.svg-noeud.p-nok .forme{fill:#f8d1d0}
      .svg-etiquette{font:11px sans-serif;fill:#37474f}
      .svg-etiquette.resultat{fill:#0b5394;font-weight:600}
      .svg-marqueur-h{font:bold 10px sans-serif;fill:#c5221f}
      .svg-fleche{fill:#0b5394}`;
    clone.insertBefore(style, clone.firstChild);
    const xml = new XMLSerializer().serializeToString(clone);
    const url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(xml);
    return new Promise((resoudre) => {
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement('canvas');
        canvas.width = 1400; canvas.height = 860;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0);
        resoudre(canvas.toDataURL('image/png'));
      };
      img.onerror = () => resoudre(null);
      img.src = url;
    });
  }

  /* ── Rapport HTML (impression / PDF) ────────────────────────────────── */
  async function genererHTML() {
    const p = st().projet;
    const r = st().resultats;
    const diam = st().diametres;
    const controles = st().controles || [];
    const s = sectionsTexte(p);
    const m = p.meta;
    const pire = r ? RD.checks.noeudLePlusDefavorable(p, r) : null;
    const png = await schemaEnPNG();
    const ul = (items) => `<ul>${items.map((i) => `<li>${i}</li>`).join('')}</ul>`;

    let html = `
    <div class="page-garde">
      <h1>Note de calcul — Dimensionnement hydraulique<br>d'un réseau de distribution d'eau potable</h1>
      <p><em>Avant-projet</em></p>
      <table>
        <tr><th>Projet</th><td>${m.nom || '—'}</td></tr>
        <tr><th>Maître d'ouvrage</th><td>${m.maitreOuvrage || '—'}</td></tr>
        <tr><th>Distributeur</th><td>${m.distributeur || '—'}</td></tr>
        <tr><th>Bureau d'études</th><td>${m.bureau || '—'}</td></tr>
        <tr><th>Auteur de projet</th><td>${m.auteur || '—'}</td></tr>
        <tr><th>Indice / date</th><td>${m.indice || '—'} — ${m.date || '—'}</td></tr>
        ${r ? `<tr><th>Calcul du</th><td>${new Date(r.horodatage).toLocaleString('fr-BE')}</td></tr>` : ''}
      </table>
      <div class="encadre">${RD.checks.MENTION_AVANT_PROJET}</div>
      ${m.description ? `<p>${m.description}</p>` : ''}
    </div>

    <h2>1. Hypothèses et conventions</h2>
    <h3>1.1 Conventions</h3>${ul(s.conventions)}
    <h3>1.2 Hypothèses de calcul</h3>
    <table>
      <tr><th>Hypothèse</th><th>Valeur</th><th>Source / justification</th></tr>
      <tr><td>Coefficient de pointe Cp</td><td class="num">${p.hypotheses.coeffPointe}</td><td>Hypothèse d'avant-projet — à confirmer par le distributeur</td></tr>
      <tr><td>Durée de distribution</td><td class="num">${p.hypotheses.dureeDistribution} h/j</td><td>Profil d'activité des abonnés</td></tr>
      <tr><td>Débit incendie par hydrant</td><td class="num">${p.hypotheses.debitIncendie} m³/h</td><td>CM 14/10/1975 (1 000 l/min) — à confirmer par la zone de secours</td></tr>
      <tr><td>Hydrants simultanés</td><td class="num">${p.hypotheses.deuxHydrants ? 2 : 1}</td><td>${p.hypotheses.deuxHydrants ? 'Exigence renforcée' : 'Cas de base'}</td></tr>
      <tr><td>Pression minimale exigée</td><td class="num">${p.hypotheses.pressionMinimale ?? 'NON RENSEIGNÉE'} ${p.hypotheses.pressionMinimale != null ? 'bar' : ''}</td><td>À confirmer PAR ÉCRIT — distributeur / zone de secours</td></tr>
      <tr><td>Viscosité cinématique ν</td><td class="num">${p.hypotheses.nu.toExponential(2)} m²/s</td><td>Eau à ~10 °C</td></tr>
      <tr><td>Pertes singulières globales</td><td class="num">${p.hypotheses.pertesSingulieresPct || 0} %</td><td>des pertes linéaires (+ ΣK par tronçon)</td></tr>
      ${Object.entries(RD.materials.MATERIAUX).map(([id, mm]) =>
        `<tr><td>Rugosité k — ${mm.nom}</td><td class="num">${RD.materials.rugosite(id, p.hypotheses.rugosites)} mm</td>
         <td>Valeur prudente « en service » (littérature) — hypothèse à justifier</td></tr>`).join('')}
    </table>
    <h3>1.3 Cas de charge</h3>${ul(s.casDeCharge)}
    <h3>1.4 Alimentation</h3>
    <p>${p.alimentation.mode === 'essai'
      ? `Essai débit-pression au piquage : P0 = ${p.alimentation.p0} bar (statique) ; Q1 = ${p.alimentation.q1} m³/h → P1 = ${p.alimentation.p1} bar. Extrapolation P(Q) = P0 − (P0 − P1)·(Q/Q1)<sup>1,852</sup> (règle de l'art).`
      : `Pression fixée au piquage : ${p.alimentation.p0} bar (hypothèse forte à valider).`}</p>

    <h2>2. Méthode de calcul</h2>${ul(s.methode)}

    <h2>3. Références</h2>${ul(s.references)}

    <h2>4. Schéma du réseau</h2>
    ${png ? `<img class="schema-image" src="${png}" alt="Schéma du réseau">` : '<p>(schéma non disponible)</p>'}
    <p><em>Schéma non à l'échelle — longueurs saisies.</em></p>

    <h2>5. Données d'entrée</h2>
    <h3>5.1 Nœuds</h3>
    <table><tr><th>Nœud</th><th>Cote (m)</th><th>Conso (m³/j)</th><th>Hydrant</th><th>Rôle</th></tr>
    ${p.noeuds.map((n) => `<tr><td>${n.nom || n.id}</td><td class="num">${fmt(n.cote, 1)}</td>
      <td class="num">${n.consommation ? fmt(n.consommation, 1) : '—'}</td>
      <td>${n.hydrant ? 'oui' : '—'}</td>
      <td>${p.alimentation.noeudId === n.id ? 'alimentation (piquage)' : (n.type || '')}</td></tr>`).join('')}
    </table>
    <h3>5.2 Tronçons</h3>
    <table><tr><th>Tronçon</th><th>Liaison</th><th>L (m)</th><th>Matériau</th><th>k (mm)</th><th>ΣK</th></tr>
    ${p.troncons.map((t) => {
      const nn = (id) => { const n = p.noeuds.find((x) => x.id === id); return n ? (n.nom || n.id) : id; };
      return `<tr><td>${t.nom || t.id}</td><td>${nn(t.de)} → ${nn(t.vers)}</td>
      <td class="num">${t.longueur}</td><td>${RD.materials.MATERIAUX[t.materiau].nom}</td>
      <td class="num">${RD.materials.rugosite(t.materiau, p.hypotheses.rugosites)}</td>
      <td class="num">${t.sommeK || 0}</td></tr>`;
    }).join('')}
    </table>`;

    if (r && diam) {
      const lt = lignesTroncons(p, r, diam);
      const ln = lignesNoeuds(p, r);
      const inc = r.casIncendie;
      html += `
      <h2>6. Résultats</h2>
      <h3>6.1 Débits des cas de charge</h3>
      <p>Cas 1 (pointe) : ${fmt(r.casPointe.qTotal, 2)} m³/h = ${fmt(r.casPointe.qTotal / 3.6, 2)} l/s.<br>
      ${inc ? `Cas 2 (incendie + conso moyenne, scénario critique — hydrant(s) ${inc.combo.join(' + ')}) :
        ${fmt(inc.resultat.qTotal, 2)} m³/h = ${fmt(inc.resultat.qTotal / 3.6, 2)} l/s.` : 'Pas d’hydrant : cas incendie sans objet.'}
      ${inc && inc.resultat.equilibrage ? `<br>Équilibrage Hardy Cross : ${inc.resultat.equilibrage.iterations} itérations,
        ${inc.resultat.equilibrage.converge ? 'convergé' : 'NON CONVERGÉ'}
        (correction max ${inc.resultat.equilibrage.maxCorrection.toExponential(2)} m³/h).` : ''}</p>
      <h3>6.2 Résultats par tronçon</h3>
      <table><tr><th>Tronçon</th><th>L (m)</th><th>Diamètre retenu</th><th>Q dim. (m³/h)</th><th>Cas dim.</th>
        <th>v pointe (m/s)</th><th>v inc. (m/s)</th><th>ΔH pointe (mCE)</th><th>ΔH inc. (mCE)</th><th>λ</th></tr>
      ${lt.map((l) => `<tr><td>${l.nom}</td><td class="num">${l.longueur}</td><td>${l.diametre}</td>
        <td class="num">${fmt(l.qDim, 2)}</td><td>${l.origine}</td>
        <td class="num">${fmt(l.vPointe, 2)}</td><td class="num">${l.vInc !== null ? fmt(l.vInc, 2) : '—'}</td>
        <td class="num">${fmt(l.dHPointe, 3)}</td><td class="num">${l.dHInc !== null ? fmt(l.dHInc, 3) : '—'}</td>
        <td class="num">${fmt(l.lambda, 4)}</td></tr>`).join('')}
      </table>
      <h3>6.3 Résultats par nœud (pressions relatives, bar)</h3>
      <table><tr><th>Nœud</th><th>Cote (m)</th><th>P pointe (bar)</th><th>P incendie (bar)</th></tr>
      ${ln.map((l) => `<tr><td>${l.nom}${l.alim ? ' (alimentation)' : ''}${l.hydrant ? ' (hydrant)' : ''}</td>
        <td class="num">${fmt(l.cote, 1)}</td><td class="num">${fmt(l.pPointe, 2)}</td>
        <td class="num">${l.pInc !== null ? fmt(l.pInc, 2) : '—'}</td></tr>`).join('')}
      </table>
      ${pire ? `<p><strong>Nœud le plus défavorable :</strong> ${pire.id} — ${fmt(pire.p, 2)} bar (cas ${pire.cas})${
        p.hypotheses.pressionMinimale != null ? ` pour une exigence de ${p.hypotheses.pressionMinimale} bar` : ''}.</p>` : ''}

      <h2>7. Contrôles et avertissements</h2>
      ${controles.length ? `<table><tr><th>Niveau</th><th>Contrôle</th></tr>
        ${controles.map((c) => `<tr><td class="${c.niveau === 'nok' ? 'nok' : c.niveau}">${
          c.niveau.toUpperCase()}${c.bloquant ? ' (BLOQUANT)' : ''}</td><td>${c.message}</td></tr>`).join('')}
      </table>` : '<p>Aucune observation.</p>'}`;
    } else {
      html += '<h2>6. Résultats</h2><p><em>Aucun calcul effectué : lancer le calcul avant l’export.</em></p>';
    }

    html += `<h2>${r ? 8 : 7}. Réserves</h2>${ul(s.reserves)}`;
    return html;
  }

  async function imprimerPDF() {
    const zone = document.getElementById('rapport-impression');
    zone.innerHTML = await genererHTML();
    window.print();
  }

  const RP = { sectionsTexte, lignesTroncons, lignesNoeuds, schemaEnPNG, genererHTML, imprimerPDF };
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.rapport = RP;
})();
