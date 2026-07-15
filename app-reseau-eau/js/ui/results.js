/*
 * results.js — Lancement du calcul et rendu des résultats :
 * synthèse, tableaux par tronçon et par nœud (pointe + incendie),
 * tableaux des diamètres candidats, liste des contrôles.
 */
(function () {
  const st = () => RD.stateModule.state;
  const SM = () => RD.stateModule;
  const fmt = (x, d) => RD.units.fmt(x, d);

  /* ── Lancement du calcul ────────────────────────────────────────────── */
  function calculer() {
    const p = st().projet;
    const zoneErreurs = document.getElementById('erreurs-validation');
    const statut = document.getElementById('statut-calcul');
    zoneErreurs.innerHTML = '';
    statut.textContent = '';

    const erreurs = RD.checks.validerProjet(p);
    if (erreurs.length) {
      zoneErreurs.innerHTML = `<div class="controle nok"><strong>Saisies à corriger avant calcul :</strong><ul>${
        erreurs.map((e) => `<li>${e}</li>`).join('')}</ul></div>`;
      return;
    }
    let dim;
    try {
      dim = RD.sizing.dimensionner(p);
    } catch (e) {
      zoneErreurs.innerHTML = `<div class="controle nok">${e.message}</div>`;
      return;
    }
    const diamMap = new Map([...dim.diametres].map(([id, d]) => [id, d.di]));
    const controles = RD.checks.controler(p, dim.resultats, diamMap);
    SM().definirResultats(dim.resultats, dim.diametres, controles);
    statut.textContent = `Calcul effectué le ${new Date().toLocaleString('fr-BE')} — ${dim.iterationsDimensionnement} passe(s) de dimensionnement.`;
    rendreResultats();
    rendreControles();
  }

  /* ── Rendu des résultats ────────────────────────────────────────────── */
  function classeStatut(id, type) {
    let niveau = '';
    for (const c of st().controles || []) {
      if (!(c.cibles || []).includes(id)) continue;
      if (c.niveau === 'nok') return 'nok';
      if (c.niveau === 'avertissement') niveau = 'warn';
    }
    return niveau || 'ok';
  }

  function rendreResultats() {
    const conteneur = document.getElementById('resultats-conteneur');
    const r = st().resultats;
    const p = st().projet;
    if (!r) { conteneur.innerHTML = ''; return; }
    const diam = st().diametres;

    // Synthèse
    const qP = r.casPointe.qTotal;
    const inc = r.casIncendie;
    const pire = RD.checks.noeudLePlusDefavorable(p, r);
    const nomN = (id) => { const n = p.noeuds.find((x) => x.id === id); return n ? (n.nom || n.id) : id; };
    let html = `<div class="carte"><h2>Synthèse</h2>
      <div class="table-defilante"><table class="donnees">
      <tr><th>Grandeur</th><th class="num">Valeur</th><th>Commentaire</th></tr>
      <tr><td>Débit de pointe (cas 1)</td><td class="num">${fmt(qP, 2)} m³/h = ${fmt(qP / 3.6, 2)} l/s</td>
        <td>Σ conso journalières / ${p.hypotheses.dureeDistribution} h × Cp ${p.hypotheses.coeffPointe}</td></tr>
      ${inc ? `<tr><td>Débit incendie + conso moyenne (cas 2, critique)</td>
        <td class="num">${fmt(inc.resultat.qTotal, 2)} m³/h = ${fmt(inc.resultat.qTotal / 3.6, 2)} l/s</td>
        <td>Hydrant(s) sollicité(s) : ${inc.combo.map(nomN).join(' + ')} (scénario le plus défavorable parmi ${r.scenariosIncendie.length})</td></tr>` : ''}
      <tr><td>Pression disponible au piquage</td>
        <td class="num">${fmt((inc ? inc.resultat : r.casPointe).pAlim, 2)} bar</td>
        <td>${p.alimentation.mode === 'essai' ? 'courbe d’essai P(Q) = P0 − (P0−P1)·(Q/Q1)^1,852' : 'pression fixée'} — cas dimensionnant</td></tr>
      ${pire ? `<tr><td>Nœud le plus défavorable</td>
        <td class="num">${fmt(pire.p, 2)} bar</td>
        <td>« ${nomN(pire.id)} », cas ${pire.cas}${p.hypotheses.pressionMinimale != null ? ` — exigence ${p.hypotheses.pressionMinimale} bar` : ' — exigence à renseigner'}</td></tr>` : ''}
      ${inc && inc.resultat.equilibrage ? `<tr><td>Équilibrage Hardy Cross (cas incendie)</td>
        <td class="num">${inc.resultat.equilibrage.iterations} itérations</td>
        <td>${inc.resultat.equilibrage.converge ? 'convergé' : '<strong>NON CONVERGÉ</strong>'} — correction max ${inc.resultat.equilibrage.maxCorrection.toExponential(2)} m³/h, déséquilibre max ${inc.resultat.equilibrage.maxDesequilibre.toExponential(2)} mCE</td></tr>` : ''}
      </table></div></div>`;

    // Tableau par tronçon
    const lignesT = p.troncons.map((t) => {
      const d = diam.get(t.id);
      const rp = r.casPointe.troncons.get(t.id);
      const ri = inc ? inc.resultat.troncons.get(t.id) : null;
      const dm = r.dimensionnant.get(t.id);
      const cls = classeStatut(t.id, 'troncon');
      return `<tr class="${cls}">
        <td>${t.nom || t.id}</td>
        <td class="num">${t.longueur} m</td>
        <td>${RD.materials.MATERIAUX[t.materiau].nom.split(' (')[0]}</td>
        <td>${d.designation} — Di ${d.di} mm${d.force ? ' <em>(forcé)</em>' : ''}</td>
        <td class="num">${fmt(dm.Q, 2)}<br><span class="aide">${dm.origine}</span></td>
        <td class="num">${fmt(Math.abs(rp.Q), 2)} / ${fmt(rp.v, 2)}</td>
        <td class="num">${ri ? `${fmt(Math.abs(ri.Q), 2)} / ${fmt(ri.v, 2)}` : '—'}</td>
        <td class="num">${fmt(Math.abs(rp.dH), 3)} / ${ri ? fmt(Math.abs(ri.dH), 3) : '—'}</td>
        <td class="num">${ri ? fmt(ri.lambda, 4) : fmt(rp.lambda, 4)}</td>
        <td><button class="btn secondaire btn-candidats" data-troncon="${t.id}" style="padding:2px 8px;font-size:11px">candidats…</button></td>
      </tr>`;
    }).join('');
    html += `<div class="carte"><h2>Résultats par tronçon</h2>
      <p class="aide">Débits en m³/h (1 l/s = 3,6 m³/h), vitesses en m/s, pertes de charge en mCE (1 bar = 10,197 mCE).
      Le débit dimensionnant est le max (pointe ; enveloppe des scénarios incendie).</p>
      <div class="table-defilante"><table class="donnees">
      <tr><th>Tronçon</th><th class="num">L (m)</th><th>Matériau</th><th>Diamètre retenu</th>
      <th class="num">Q dim. (m³/h)</th><th class="num">Pointe : Q / v</th><th class="num">Incendie : Q / v</th>
      <th class="num">ΔH pointe / inc. (mCE)</th><th class="num">λ</th><th></th></tr>
      ${lignesT}</table></div>
      <div id="zone-candidats"></div></div>`;

    // Tableau par nœud
    const lignesN = p.noeuds.map((n) => {
      const np = r.casPointe.noeuds.get(n.id);
      const ni = inc ? inc.resultat.noeuds.get(n.id) : null;
      const cls = classeStatut(n.id, 'noeud');
      return `<tr class="${cls}">
        <td>${n.nom || n.id}${p.alimentation.noeudId === n.id ? ' 🔷' : ''}${n.hydrant ? ' 🧯' : ''}</td>
        <td class="num">${fmt(n.cote, 1)}</td>
        <td class="num">${n.consommation ? fmt(n.consommation, 1) : '—'}</td>
        <td class="num">${fmt(np.demande, 2)}</td>
        <td class="num">${fmt(np.p, 2)}</td>
        <td class="num">${ni ? fmt(ni.demande, 2) : '—'}</td>
        <td class="num">${ni ? fmt(ni.p, 2) : '—'}</td>
      </tr>`;
    }).join('');
    html += `<div class="carte"><h2>Résultats par nœud</h2>
      <p class="aide">Pressions RELATIVES (manométriques) en bar, intégrant la dénivelée (cotes altimétriques).</p>
      <div class="table-defilante"><table class="donnees">
      <tr><th>Nœud</th><th class="num">Cote (m)</th><th class="num">Conso (m³/j)</th>
      <th class="num">Pointe : demande (m³/h)</th><th class="num">Pointe : P (bar)</th>
      <th class="num">Incendie : demande (m³/h)</th><th class="num">Incendie : P (bar)</th></tr>
      ${lignesN}</table></div></div>`;

    conteneur.innerHTML = html;
    conteneur.querySelectorAll('.btn-candidats').forEach((b) => {
      b.addEventListener('click', () => rendreCandidats(b.dataset.troncon));
    });
  }

  /** Tableau complet des diamètres candidats d'un tronçon. */
  function rendreCandidats(tronconId) {
    const p = st().projet;
    const zone = document.getElementById('zone-candidats');
    const t = p.troncons.find((x) => x.id === tronconId);
    const diamActuels = new Map([...st().diametres].map(([id, d]) => [id, d.di]));
    const lignes = RD.sizing.tableauCandidats(p, diamActuels, tronconId);
    const retenu = st().diametres.get(tronconId);
    zone.innerHTML = `<h3 style="font-size:13px;color:var(--bleu)">Diamètres candidats — tronçon ${t.nom || t.id}
        (${RD.materials.MATERIAUX[t.materiau].nom})</h3>
      <p class="aide">Chaque ligne : réseau recalculé avec ce diamètre sur ce tronçon, les autres inchangés.
      P résiduelle = pression au nœud le plus défavorable du réseau.</p>
      <div class="table-defilante"><table class="donnees">
      <tr><th>Diamètre</th><th class="num">Di (mm)</th><th class="num">Q dim. (m³/h)</th>
      <th class="num">v max (m/s)</th><th class="num">ΔH (mCE)</th><th class="num">P résiduelle (bar)</th><th>Statut</th></tr>
      ${lignes.map((l) => `<tr class="${l.ok ? 'ok' : 'nok'}${l.di === retenu.di ? '" style="font-weight:700' : ''}">
        <td>${l.designation}${l.di === retenu.di ? ' ← retenu' : ''}</td>
        <td class="num">${l.di}</td><td class="num">${fmt(l.qDim, 2)}</td>
        <td class="num">${fmt(l.vDim, 2)}</td><td class="num">${fmt(l.dH, 3)}</td>
        <td class="num">${fmt(l.pResiduelle, 2)}</td>
        <td>${l.statuts.length ? `<span class="statut-${l.ok ? 'warn' : 'nok'}">${l.statuts.join(' ; ')}</span>` : '<span class="statut-ok">OK</span>'}</td>
      </tr>`).join('')}</table></div>`;
    zone.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  /* ── Contrôles ──────────────────────────────────────────────────────── */
  function rendreControles() {
    const zone = document.getElementById('liste-controles');
    const controles = st().controles;
    if (!controles) {
      zone.innerHTML = '<p class="aide">Lancez d’abord le calcul (étape ④).</p>';
      return;
    }
    const ordre = { nok: 0, avertissement: 1, info: 2 };
    const tries = [...controles].sort((a, b) => ordre[a.niveau] - ordre[b.niveau]);
    const icone = { nok: '⛔', avertissement: '⚠️', info: 'ℹ️' };
    zone.innerHTML =
      `<div class="controle info">${RD.checks.MENTION_AVANT_PROJET}</div>` +
      (tries.length
        ? tries.map((c) => `<div class="controle ${c.niveau}">
            ${icone[c.niveau]} ${c.message}
            <div class="code">${c.code}${c.bloquant ? ' — BLOQUANT' : ''}</div>
          </div>`).join('')
        : '<div class="controle info">Aucune observation.</div>');
  }

  const R = { calculer, rendreResultats, rendreControles };
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.results = R;
})();
