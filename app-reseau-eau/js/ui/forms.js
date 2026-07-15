/*
 * forms.js — Formulaires : métadonnées, alimentation, hypothèses,
 * tables des nœuds et tronçons. Info-bulles pédagogiques sur chaque champ.
 */
(function () {
  const SM = () => RD.stateModule;
  const st = () => RD.stateModule.state;

  function bulle(texte) {
    return `<span class="infobulle" title="${texte.replace(/"/g, '&quot;')}">?</span>`;
  }

  function champ(html, label, aide, importante) {
    return `<div class="champ ${importante ? 'champ-important' : ''}">
      <label>${label}${aide ? bulle(aide) : ''}</label>
      <span>${html}</span>
    </div>`;
  }

  /* ── Métadonnées ────────────────────────────────────────────────────── */
  function rendreMeta() {
    const m = st().projet.meta;
    const el = document.getElementById('form-meta');
    const t = (cle, label, aide) => champ(
      `<input type="text" data-meta="${cle}" value="${echap(m[cle] || '')}">`, label, aide
    );
    el.innerHTML =
      t('nom', 'Nom du projet', 'Intitulé repris en page de garde de la note de calcul.') +
      t('auteur', 'Auteur de projet', 'Ingénieur responsable de la note.') +
      t('bureau', 'Bureau d’études', '') +
      t('maitreOuvrage', 'Maître d’ouvrage', 'Pouvoir public client (commune, intercommunale, IDEA, etc.).') +
      t('distributeur', 'Distributeur', 'SWDE ou intercommunale : c’est lui qui validera le dimensionnement final.') +
      t('indice', 'Version / indice', 'Indice de révision du document (A, B, C…).') +
      champ(`<input type="date" data-meta="date" value="${echap(m.date || '')}">`, 'Date', '') +
      champ(`<textarea data-meta="description" rows="2">${echap(m.description || '')}</textarea>`, 'Description', '');
    el.querySelectorAll('[data-meta]').forEach((inp) => {
      inp.addEventListener('change', () => {
        st().projet.meta[inp.dataset.meta] = inp.value;
        SM().sauvegarderSession();
      });
    });
  }

  /* ── Alimentation ───────────────────────────────────────────────────── */
  function rendreAlimentation() {
    const a = st().projet.alimentation;
    const noeuds = st().projet.noeuds;
    const el = document.getElementById('form-alimentation');
    const options = ['<option value="">— à choisir —</option>']
      .concat(noeuds.map((n) => `<option value="${n.id}" ${a.noeudId === n.id ? 'selected' : ''}>${echap(n.nom || n.id)}</option>`))
      .join('');
    el.innerHTML =
      champ(`<select data-alim="noeudId">${options}</select>`, 'Nœud d’alimentation',
        'Nœud du schéma correspondant au piquage sur le réseau existant.') +
      champ(`<select data-alim="mode">
          <option value="essai" ${a.mode === 'essai' ? 'selected' : ''}>Courbe d’essai débit-pression (recommandé)</option>
          <option value="pression_fixe" ${a.mode === 'pression_fixe' ? 'selected' : ''}>Pression fixée (hypothèse forte)</option>
        </select>`, 'Mode',
        'Pression fixée : suppose le réseau amont infiniment capacitif. Courbe d’essai : reflète la chute de pression réelle du réseau amont.') +
      champ(`<input type="number" step="0.01" min="0" data-alim="p0" value="${val(a.p0)}"> <span class="unite">bar (relatif)</span>`,
        'P0 — pression statique',
        'Pression relative mesurée au piquage à débit nul (manomètre). Où la trouver : essai in situ ou données du distributeur.') +
      (a.mode === 'essai'
        ? champ(`<input type="number" step="0.1" min="0" data-alim="q1" value="${val(a.q1)}"> <span class="unite">m³/h <span data-conv="q1"></span></span>`,
            'Q1 — débit d’essai',
            'Débit soutiré pendant l’essai (généralement à un hydrant voisin).') +
          champ(`<input type="number" step="0.01" min="0" data-alim="p1" value="${val(a.p1)}"> <span class="unite">bar (relatif)</span>`,
            'P1 — pression sous Q1',
            'Pression résiduelle mesurée au piquage pendant le soutirage Q1. Extrapolation : P(Q) = P0 − (P0 − P1)·(Q/Q1)^1,852 (règle de l’art, exposant Hazen-Williams).')
        : '');
    el.querySelectorAll('[data-alim]').forEach((inp) => {
      inp.addEventListener('change', () => {
        const cle = inp.dataset.alim;
        st().projet.alimentation[cle] = inp.type === 'number' ? versNombre(inp.value) : inp.value;
        SM().marquerModifie();
        rendreAlimentation();
      });
    });
    majConversion(el.querySelector('[data-conv="q1"]'), a.q1);
  }

  function majConversion(span, m3h) {
    if (span) span.textContent = m3h ? `(= ${RD.units.fmt(m3h / 3.6, 2)} l/s)` : '(1 l/s = 3,6 m³/h)';
  }

  /* ── Hypothèses (panneau unique) ────────────────────────────────────── */
  function rendreHypotheses() {
    const h = st().projet.hypotheses;
    const el = document.getElementById('form-hypotheses');
    const ligne = (contenu, label, aide, source, importante) =>
      `<div class="champ ${importante ? 'champ-important' : ''}">
         <label>${label}${aide ? bulle(aide) : ''}</label>
         <span>${contenu}<br><span class="hypothese-source">${source}</span></span>
       </div>`;
    el.innerHTML =
      ligne(`<input type="number" step="0.1" min="0.1" data-hyp="coeffPointe" value="${val(h.coeffPointe)}"> <span class="unite">—</span>`,
        'Coefficient de pointe Cp',
        'Rapport entre le débit de pointe et le débit moyen sur la durée de distribution. Q pointe = Σ conso journalière / durée × Cp.',
        'Hypothèse d’avant-projet — à confirmer par le distributeur.', true) +
      ligne(`<input type="number" step="0.5" min="0.5" max="24" data-hyp="dureeDistribution" value="${val(h.dureeDistribution)}"> <span class="unite">h/j</span>`,
        'Durée de distribution',
        'Nombre d’heures sur lesquelles la consommation journalière est distribuée (ex. 10 h pour un parc d’activités).',
        'Hypothèse liée au profil d’activité des abonnés.') +
      ligne(`<input type="number" step="1" min="0" data-hyp="debitIncendie" value="${val(h.debitIncendie)}"> <span class="unite">m³/h par hydrant <span data-conv="qi"></span></span>`,
        'Débit incendie par hydrant',
        'Débit à garantir à l’hydrant le plus défavorisé. 60 m³/h = 1 000 l/min.',
        'Défaut : 60 m³/h — CM belge du 14/10/1975 (ressources en eau pour l’extinction des incendies). À confirmer par la zone de secours selon le risque.') +
      ligne(`<label class="case"><input type="checkbox" data-hyp="deuxHydrants" ${h.deuxHydrants ? 'checked' : ''}> activer</label>`,
        'Deux hydrants simultanés',
        'Le cas incendie sollicite alors les 2 hydrants (paire la plus défavorable) — zones à risque important.',
        'Option — exigence éventuelle de la zone de secours.') +
      ligne(`<input type="number" step="0.1" min="0" data-hyp="pressionMinimale" value="${val(h.pressionMinimale)}" placeholder="à compléter"> <span class="unite">bar (relatif)</span>`,
        'Pression minimale exigée',
        'Pression résiduelle à garantir au nœud le plus défavorable. Champ volontairement SANS valeur par défaut.',
        '⚠️ À confirmer PAR ÉCRIT auprès du distributeur / de la zone de secours — aucune valeur par défaut n’est proposée.', true) +
      ligne(`<input type="number" step="0.01" min="0.1" data-hyp="nuE6" value="${val(h.nu * 1e6)}"> <span class="unite">×10⁻⁶ m²/s</span>`,
        'Viscosité cinématique ν',
        'Défaut : 1,31×10⁻⁶ m²/s (eau à 10 °C). 1,00 à 20 °C.',
        'Propriété physique de l’eau — dépend de la température retenue.') +
      ligne(`<input type="number" step="1" min="0" max="100" data-hyp="pertesSingulieresPct" value="${val(h.pertesSingulieresPct)}"> <span class="unite">% des pertes linéaires</span>`,
        'Pertes singulières globales',
        'Majoration forfaitaire pour coudes, tés, vannes. Alternative : saisir ΣK par tronçon dans la table des tronçons.',
        'Pratique courante : 5 à 15 % en avant-projet, 0 % si ΣK détaillé.') +
      rugositesHTML(h);
    el.querySelectorAll('[data-hyp]').forEach((inp) => {
      inp.addEventListener('change', () => {
        const cle = inp.dataset.hyp;
        const hyp = st().projet.hypotheses;
        if (cle === 'deuxHydrants') hyp.deuxHydrants = inp.checked;
        else if (cle === 'nuE6') hyp.nu = (versNombre(inp.value) || 1.31) * 1e-6;
        else if (cle === 'pressionMinimale') hyp.pressionMinimale = versNombre(inp.value);
        else hyp[cle] = versNombre(inp.value);
        SM().marquerModifie();
        rendreHypotheses();
      });
    });
    el.querySelectorAll('[data-rug]').forEach((inp) => {
      inp.addEventListener('change', () => {
        const mid = inp.dataset.rug;
        const v = versNombre(inp.value);
        const hyp = st().projet.hypotheses;
        hyp.rugosites = hyp.rugosites || {};
        if (v === null || v === RD.materials.MATERIAUX[mid].kDefaut) delete hyp.rugosites[mid];
        else hyp.rugosites[mid] = v;
        SM().marquerModifie();
      });
    });
    majConversion(el.querySelector('[data-conv="qi"]'), h.debitIncendie);
  }

  function rugositesHTML(h) {
    const lignes = Object.entries(RD.materials.MATERIAUX).map(([id, m]) => {
      const k = RD.materials.rugosite(id, h.rugosites);
      return `<tr><td>${m.nom}</td>
        <td class="num"><input type="number" step="0.01" min="0.001" data-rug="${id}" value="${k}"></td>
        <td class="num">${m.kDefaut}</td></tr>`;
    }).join('');
    return `<h3 style="font-size:13px;color:var(--bleu);margin:12px 0 4px">Rugosités k par matériau (mm)
      ${bulle('Rugosité équivalente de Colebrook. Valeurs par défaut PRUDENTES « en service » (ordres de grandeur de la littérature) : hypothèses à justifier dans la note, pas des valeurs normatives.')}</h3>
      <div class="table-defilante"><table class="donnees">
      <tr><th>Matériau</th><th class="num">k retenu (mm)</th><th class="num">Défaut (mm)</th></tr>${lignes}</table></div>`;
  }

  /* ── Table des nœuds ────────────────────────────────────────────────── */
  function rendreTableNoeuds() {
    const p = st().projet;
    const el = document.getElementById('table-noeuds');
    if (!p.noeuds.length) { el.innerHTML = '<p class="aide">Aucun nœud — dessinez le réseau à l’étape ② Schéma.</p>'; return; }
    const lignes = p.noeuds.map((n) => `<tr data-id="${n.id}">
      <td>${n.id}${p.alimentation.noeudId === n.id ? ' 🔷' : ''}</td>
      <td><input type="text" data-cle="nom" value="${echap(n.nom || '')}"></td>
      <td class="num"><input type="number" step="0.1" data-cle="cote" value="${val(n.cote)}" title="Cote altimétrique du terrain/de la conduite (m) — la pression résiduelle intègre la dénivelée."></td>
      <td class="num"><input type="number" step="0.1" min="0" data-cle="consommation" value="${val(n.consommation)}" title="Consommation journalière raccordée à ce nœud (m³/j)."></td>
      <td class="num">${n.consommation ? RD.units.fmt(n.consommation / 24 / 3.6 * 1000, 3) : '—'}</td>
      <td style="text-align:center"><input type="checkbox" data-cle="hydrant" ${n.hydrant ? 'checked' : ''} title="Hydrant (bouche/borne incendie) sur ce nœud — impose Ø ≥ 100 mm aux tronçons qui le portent."></td>
    </tr>`).join('');
    el.innerHTML = `<div class="table-defilante"><table class="donnees">
      <tr><th>ID ${bulle('🔷 = nœud d’alimentation (piquage).')}</th><th>Nom</th>
      <th class="num">Cote (m)</th><th class="num">Conso (m³/j)</th>
      <th class="num">Conso moy. (l/s)</th><th>Hydrant</th></tr>${lignes}</table></div>`;
    el.querySelectorAll('tr[data-id] [data-cle]').forEach((inp) => {
      inp.addEventListener('change', () => {
        const n = p.noeuds.find((x) => x.id === inp.closest('tr').dataset.id);
        if (inp.dataset.cle === 'hydrant') n.hydrant = inp.checked;
        else if (inp.type === 'number') n[inp.dataset.cle] = versNombre(inp.value);
        else n[inp.dataset.cle] = inp.value;
        SM().marquerModifie();
      });
    });
  }

  /* ── Table des tronçons ─────────────────────────────────────────────── */
  function rendreTableTroncons() {
    const p = st().projet;
    const el = document.getElementById('table-troncons');
    if (!p.troncons.length) { el.innerHTML = '<p class="aide">Aucun tronçon — dessinez le réseau à l’étape ② Schéma.</p>'; return; }
    const nomN = (id) => { const n = p.noeuds.find((x) => x.id === id); return n ? (n.nom || n.id) : '?'; };
    const optionsMateriaux = (t) => Object.entries(RD.materials.MATERIAUX)
      .map(([id, m]) => `<option value="${id}" ${t.materiau === id ? 'selected' : ''}>${m.nom}</option>`).join('');
    const optionsDiam = (t) => ['<option value="">Auto (proposé)</option>']
      .concat(RD.materials.diametres(t.materiau).map((d) =>
        `<option value="${d.de}" ${t.diametreForce === d.de ? 'selected' : ''}>${d.designation} (Di ${d.di} mm)</option>`))
      .join('');
    const lignes = p.troncons.map((t) => `<tr data-id="${t.id}">
      <td>${t.id}</td>
      <td><input type="text" data-cle="nom" value="${echap(t.nom || '')}"></td>
      <td>${nomN(t.de)} → ${nomN(t.vers)}</td>
      <td class="num"><input type="number" step="1" min="0" data-cle="longueur" value="${val(t.longueur)}" placeholder="à saisir" title="Longueur réelle du tronçon (m) — saisie manuelle obligatoire : le schéma n’est PAS à l’échelle."></td>
      <td><select data-cle="materiau" title="Matériau : détermine la table des diamètres commerciaux (Di réels) et la rugosité par défaut.">${optionsMateriaux(t)}</select></td>
      <td><select data-cle="diametreForce" title="« Auto » : l’application propose le plus petit diamètre commercial satisfaisant les contrôles. Sinon, diamètre forcé.">${optionsDiam(t)}</select></td>
      <td class="num"><input type="number" step="0.1" min="0" data-cle="sommeK" value="${val(t.sommeK)}" title="Somme des coefficients de pertes singulières K du tronçon (coudes, tés, vannes) — optionnel."></td>
    </tr>`).join('');
    el.innerHTML = `<div class="table-defilante"><table class="donnees">
      <tr><th>ID</th><th>Nom</th><th>Liaison</th><th class="num">Longueur (m)</th>
      <th>Matériau</th><th>Diamètre</th><th class="num">ΣK</th></tr>${lignes}</table></div>`;
    el.querySelectorAll('tr[data-id] [data-cle]').forEach((inp) => {
      inp.addEventListener('change', () => {
        const t = p.troncons.find((x) => x.id === inp.closest('tr').dataset.id);
        const cle = inp.dataset.cle;
        if (cle === 'diametreForce') t.diametreForce = inp.value ? Number(inp.value) : null;
        else if (cle === 'materiau') { t.materiau = inp.value; t.diametreForce = null; }
        else if (inp.type === 'number') t[cle] = versNombre(inp.value);
        else t[cle] = inp.value;
        SM().marquerModifie();
        rendreTableTroncons();
      });
    });
  }

  /* ── Utilitaires ────────────────────────────────────────────────────── */
  function echap(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;'); }
  function val(x) { return x === null || x === undefined ? '' : x; }
  function versNombre(s) {
    if (s === '' || s === null || s === undefined) return null;
    const n = Number(String(s).replace(',', '.'));
    return Number.isNaN(n) ? null : n;
  }

  function rendreTout() {
    rendreMeta(); rendreAlimentation(); rendreHypotheses();
    rendreTableNoeuds(); rendreTableTroncons();
  }

  const F = { rendreTout, rendreMeta, rendreAlimentation, rendreHypotheses, rendreTableNoeuds, rendreTableTroncons, echap, versNombre };
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.forms = F;
})();
