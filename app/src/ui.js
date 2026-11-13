/*
 * Interface utilisateur de Gabarit Tranchées 2.0 (navigateur uniquement).
 * Dépend de TIModel (model.js), TICoupe (coupe.js), TIDocx (docx-export.js) et
 * de la variable globale ExcelJS.
 *
 * Organisation : quatre onglets (Impétrants, Paramètres, Tronçons, Synthèse),
 * une barre de totaux permanente, plusieurs projets enregistrés sur l'appareil,
 * annuler / rétablir, alertes de cohérence en direct.
 */
(function () {
  'use strict';
  var M = window.TIModel;
  var INFO = M.appInfo;

  // Stockage propre à la 2.0 : rien n'est partagé avec la version 1, qui peut
  // rester installée à côté. Au premier lancement, le projet de la 1.x présent
  // dans ce navigateur est COPIÉ (jamais déplacé ni modifié).
  var K_INDEX = 'gabarit-tranchees-v2:index';
  var K_CURRENT = 'gabarit-tranchees-v2:current';
  var K_PROJECT = 'gabarit-tranchees-v2:project:';
  var K_UI = 'gabarit-tranchees-v2:ui';
  var K_V1 = 'gabarit-tranchees-impetrants:project';

  var TABS = [
    { id: 'impetrants', label: 'Impétrants', n: '1' },
    { id: 'parametres', label: 'Paramètres', n: '2' },
    { id: 'troncons', label: 'Tronçons', n: '3' },
    { id: 'synthese', label: 'Synthèse', n: '4' }
  ];

  var store = { index: [], currentId: null };
  var project = null;
  var ui = { tab: 'troncons', expandedRowId: null };
  var hist = { stack: [], pos: -1, timer: null };

  // --------------------------------------------------------------------- utils
  function $(sel, root) { return (root || document).querySelector(sel); }
  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      if (k === 'class') e.className = attrs[k];
      else if (k === 'html') e.innerHTML = attrs[k];
      else if (k === 'text') e.textContent = attrs[k];
      else if (k.slice(0, 2) === 'on') e.addEventListener(k.slice(2), attrs[k]);
      else if (attrs[k] != null && attrs[k] !== false) e.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c != null) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }
  function fmt(n, d) { if (n == null || isNaN(n)) return '–'; return Number(n).toLocaleString('fr-FR', { minimumFractionDigits: d == null ? 2 : d, maximumFractionDigits: d == null ? 2 : d }); }
  function fmtPct(n) { if (n == null || isNaN(n)) return '–'; return (n * 100).toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' %'; }
  function num(v) { return M.num(v, 0); }
  function lsGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function lsSet(k, v) { try { localStorage.setItem(k, v); return true; } catch (e) { return false; } }
  function lsDel(k) { try { localStorage.removeItem(k); } catch (e) {} }
  function dateFr(iso) {
    if (!iso) return '';
    var d = new Date(iso); if (isNaN(d)) return '';
    return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }
  function toast(msg) {
    var t = $('#toast'); t.textContent = msg; t.classList.add('show');
    clearTimeout(toast._t); toast._t = setTimeout(function () { t.classList.remove('show'); }, 2600);
  }

  // Champ numérique tolérant à la virgule décimale.
  // <input type="number"> refuse la virgule : le navigateur la supprime
  // silencieusement de la valeur (« 0,48 » devient « 048 », soit 48) sans
  // aucun signal visible. On utilise donc un champ texte avec
  // inputmode="decimal" (pavé numérique sur mobile) et l'analyse maison
  // M.parseDecimal, qui accepte la virgule comme le point.
  function numberInput(attrs, initial, commit) {
    var a = { type: 'text', inputmode: 'decimal', autocomplete: 'off', spellcheck: 'false' };
    Object.keys(attrs || {}).forEach(function (k) { a[k] = attrs[k]; });
    var input = el('input', a);
    input.value = M.formatDecimal(initial);
    // Sélection du contenu à la prise de focus : sans cela, le « 0 » déjà
    // présent se colle à la frappe (« 0 » + « 5 » = 05). La sélection est faite
    // au focus (synchrone, donc jamais en concurrence avec la frappe) et le
    // mouseup qui suit un clic est neutralisé, sinon il la réduirait au curseur.
    var selectOnMouseUp = false;
    input.addEventListener('focus', function () {
      selectOnMouseUp = true;
      try { input.select(); } catch (e) {}
    });
    input.addEventListener('mouseup', function (e) {
      if (selectOnMouseUp) { selectOnMouseUp = false; e.preventDefault(); }
    });
    input.addEventListener('keydown', function () { selectOnMouseUp = false; });
    // Une saisie illisible n'écrit jamais dans le modèle : le champ passe en
    // rouge et la dernière valeur valable est conservée, plutôt que d'enregistrer
    // un 0 silencieux. Un champ vidé, lui, vaut bien zéro.
    var last = M.num(initial, 0);
    input.addEventListener('input', function () {
      var raw = input.value;
      if (raw.trim() === '') { input.classList.remove('bad'); last = 0; commit(0); return; }
      var n = M.parseDecimal(raw);
      if (n == null) { input.classList.add('bad'); return; }
      input.classList.remove('bad');
      last = n; commit(n);
    });
    input.addEventListener('blur', function () {
      selectOnMouseUp = false;
      input.classList.remove('bad');
      input.value = M.formatDecimal(last);
    });
    return input;
  }
  function fld(labelHtml, control, cls) {
    return el('div', { class: 'fld' + (cls ? ' ' + cls : '') }, [el('label', { html: labelHtml }), control]);
  }
  function unit(u) { return ' <small>(' + u + ')</small>'; }
  function selectInput(options, value, on) {
    var s = el('select', { onchange: function (e) { on(e.target.value); } },
      options.map(function (o) { return el('option', { value: o[0], text: o[1] }); }));
    s.value = value;
    return s;
  }

  // ------------------------------------------------------- fenêtres modales
  function openModal(title, bodyNode, opts) {
    opts = opts || {};
    closeModal();
    var box = el('div', { class: 'modal' + (opts.wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title }, [
      el('div', { class: 'modal-head' }, [el('h2', { text: title }),
        el('button', { class: 'ghost-dark icon', 'aria-label': 'Fermer', text: '✕', onclick: closeModal })]),
      el('div', { class: 'modal-body' }, [bodyNode]),
      opts.footer ? el('div', { class: 'modal-foot' }, opts.footer) : null
    ]);
    var ov = el('div', { class: 'overlay', onmousedown: function (e) { if (e.target === ov) closeModal(); } }, [box]);
    $('#modal-root').appendChild(ov);
    var first = box.querySelector('[data-autofocus]') || box.querySelector('button.primary, button.danger-solid');
    if (first) first.focus();
  }
  function closeModal() { $('#modal-root').innerHTML = ''; }
  function askConfirm(title, message, okLabel, danger) {
    return new Promise(function (resolve) {
      var done = function (v) { closeModal(); resolve(v); };
      openModal(title, el('p', { class: 'modal-msg', text: message }), { footer: [
        el('button', { text: 'Annuler', onclick: function () { done(false); } }),
        el('button', { class: danger ? 'danger-solid' : 'primary', text: okLabel || 'Confirmer', 'data-autofocus': '1', onclick: function () { done(true); } })
      ] });
    });
  }

  // --------------------------------------------------- projets (multi-projets)
  function loadStore() {
    var idx = [];
    try { idx = JSON.parse(lsGet(K_INDEX) || '[]'); } catch (e) { idx = []; }
    if (!Array.isArray(idx)) idx = [];
    store.index = idx.filter(function (e) { return e && e.id && lsGet(K_PROJECT + e.id) != null; });
    store.currentId = lsGet(K_CURRENT);
    try { var u = JSON.parse(lsGet(K_UI) || '{}'); if (u && TABS.some(function (t) { return t.id === u.tab; })) ui.tab = u.tab; } catch (e) {}

    if (!store.index.length) {
      // Premier lancement : reprise (par copie) du projet de la version 1 s'il existe ici.
      var p = null, fromV1 = false;
      try { var raw = lsGet(K_V1); if (raw) { p = M.migrateProject(JSON.parse(raw)); fromV1 = !!(p && p.rows && p.rows.length); } } catch (e) { p = null; }
      if (!p) p = M.defaultProject();
      addProject(p);
      if (fromV1) setTimeout(function () { toast('Projet de la version 1 repris par copie — l\'original n\'est pas modifié.'); }, 600);
      return;
    }
    if (!store.index.some(function (e) { return e.id === store.currentId; })) store.currentId = store.index[0].id;
    project = readProject(store.currentId) || M.defaultProject();
  }
  function readProject(id) {
    try { var raw = lsGet(K_PROJECT + id); return raw ? M.migrateProject(JSON.parse(raw)) : null; } catch (e) { return null; }
  }
  function addProject(p) {
    var id = M.uid('p');
    store.index.unshift({ id: id, name: p.name || 'Projet', updated: new Date().toISOString(), rows: p.rows.length });
    store.currentId = id; project = p;
    persist();
    return id;
  }
  function persist() {
    if (!project || !store.currentId) return;
    var ok = lsSet(K_PROJECT + store.currentId, JSON.stringify(project));
    var e = store.index.filter(function (x) { return x.id === store.currentId; })[0];
    if (e) { e.name = project.name || 'Projet'; e.updated = new Date().toISOString(); e.rows = project.rows.length; }
    lsSet(K_INDEX, JSON.stringify(store.index));
    lsSet(K_CURRENT, store.currentId);
    if (!ok && !persist._warned) { persist._warned = true; toast('Enregistrement automatique impossible sur cet appareil — pensez à exporter le projet.'); }
  }
  function switchProject(id) {
    flushHistory();
    var p = readProject(id); if (!p) { toast('Projet illisible.'); return; }
    store.currentId = id; project = p; ui.expandedRowId = null;
    lsSet(K_CURRENT, id);
    resetHistory(); render();
  }
  function uniqueName(base) {
    var names = store.index.map(function (e) { return e.name; });
    if (names.indexOf(base) < 0) return base;
    var i = 2; while (names.indexOf(base + ' (' + i + ')') >= 0) i++;
    return base + ' (' + i + ')';
  }

  // ------------------------------------------------- annuler / rétablir
  // Historique par instantanés du projet. Les frappes successives sont
  // regroupées (délai court) pour qu'un Ctrl+Z annule une saisie entière.
  function resetHistory() { clearTimeout(hist.timer); hist.timer = null; hist.stack = [JSON.stringify(project)]; hist.pos = 0; refreshUndoButtons(); }
  function flushHistory() {
    clearTimeout(hist.timer); hist.timer = null;
    var json = JSON.stringify(project);
    if (json === hist.stack[hist.pos]) return;
    hist.stack = hist.stack.slice(0, hist.pos + 1);
    hist.stack.push(json);
    if (hist.stack.length > 120) hist.stack.shift();
    hist.pos = hist.stack.length - 1;
    refreshUndoButtons();
  }
  function scheduleHistory() { clearTimeout(hist.timer); hist.timer = setTimeout(flushHistory, 450); refreshUndoButtons(true); }
  function restoreHistory(pos, label) {
    hist.pos = pos;
    project = M.migrateProject(JSON.parse(hist.stack[pos]));
    if (ui.expandedRowId && !project.rows.some(function (r) { return r.id === ui.expandedRowId; })) ui.expandedRowId = null;
    persist(); render(); toast(label);
  }
  function undo() { flushHistory(); if (hist.pos > 0) restoreHistory(hist.pos - 1, 'Modification annulée.'); }
  function redo() { flushHistory(); if (hist.pos < hist.stack.length - 1) restoreHistory(hist.pos + 1, 'Modification rétablie.'); }
  function refreshUndoButtons(pending) {
    var u = $('#btn-undo'), r = $('#btn-redo'); if (!u || !r) return;
    u.disabled = !(hist.pos > 0 || pending);
    r.disabled = pending ? true : !(hist.pos < hist.stack.length - 1);
  }

  // Enregistre le projet (automatique) et alimente l'historique.
  function save() { persist(); scheduleHistory(); }
  // Après une action de structure (ajout, suppression, déplacement…) : instantané immédiat.
  function commit() { persist(); flushHistory(); }

  // ---------------------------------------------------------------- rendu global
  function render() {
    var y = window.scrollY;
    renderTabs();
    var root = $('#app-root');
    root.innerHTML = '';
    var view = ui.tab === 'impetrants' ? viewImpetrants() : ui.tab === 'parametres' ? viewParametres()
      : ui.tab === 'synthese' ? viewSynthese() : viewTroncons();
    root.appendChild(view);
    var pn = $('#pname'); if (document.activeElement !== pn) pn.value = project.name || '';
    refreshTotals();
    refreshUndoButtons();
    window.scrollTo(0, y);
  }
  function setTab(id) {
    ui.tab = id; lsSet(K_UI, JSON.stringify({ tab: id }));
    render(); window.scrollTo(0, 0);
  }
  function countAlerts() {
    var L = M.buildLayout(project), err = 0, warn = 0;
    project.rows.forEach(function (r) {
      M.rowWarnings(project, r, L).forEach(function (w) { if (w.level === 'error') err++; else warn++; });
    });
    return { err: err, warn: warn };
  }
  function renderTabs() {
    var nav = $('#tabs'); nav.innerHTML = '';
    var nSr = project.concessionnaires.reduce(function (s, c) { return s + c.sousReseaux.length; }, 0);
    var al = countAlerts();
    var counts = { impetrants: nSr + ' sous-réseaux', parametres: '', troncons: String(project.rows.length), synthese: '' };
    TABS.forEach(function (t) {
      var b = el('button', { type: 'button', role: 'tab', class: 'tab' + (ui.tab === t.id ? ' active' : ''), 'aria-selected': ui.tab === t.id ? 'true' : 'false',
        'data-tab': t.id, onclick: function () { setTab(t.id); } }, [
        el('span', { class: 'tab-n', text: t.n }), el('span', { class: 'tab-l', text: t.label }),
        counts[t.id] ? el('span', { class: 'tab-c', text: counts[t.id] }) : null
      ]);
      if (t.id === 'troncons' && (al.err || al.warn)) {
        b.appendChild(el('span', { class: 'tab-alert ' + (al.err ? 'err' : 'warn'), title: al.err + ' erreur(s), ' + al.warn + ' point(s) à vérifier', text: String(al.err + al.warn) }));
      }
      nav.appendChild(b);
    });
  }
  function card(title, sub, bodyNodes, extra) {
    return el('section', { class: 'card' }, [
      el('div', { class: 'card-head' }, [el('div', {}, [el('h2', { text: title }), sub ? el('p', { class: 'card-sub', text: sub }) : null])].concat(extra || [])),
      el('div', { class: 'card-body' }, bodyNodes)
    ]);
  }

  // ============================================================ 1. IMPÉTRANTS
  function viewImpetrants() {
    return card('Impétrants et sous-réseaux',
      'Chaque impétrant relève des câbles ou des conduites : cela détermine sa place et ses formules dans le gabarit. Un interstice précède chaque sous-réseau.',
      [el('div', { class: 'cols2' }, [catColumn('cable', 'Câbles'), catColumn('conduite', 'Conduites')])]);
  }
  function catColumn(category, title) {
    var list = el('div', { class: 'list' });
    var concs = project.concessionnaires.filter(function (c) { return c.category === category; });
    concs.forEach(function (c) { list.appendChild(concCard(c)); });
    if (!concs.length) list.appendChild(el('div', { class: 'empty small', text: 'Aucun impétrant dans cette catégorie.' }));
    list.appendChild(el('button', { class: 'add', onclick: function () {
      project.concessionnaires.push({ id: M.uid('conc'), name: 'Nouvel impétrant', category: category, sousReseaux: [{ id: M.uid('sr'), label: 'E' }] });
      commit(); render();
    }, text: '+ Ajouter un impétrant' }));
    return el('div', { class: 'cat-col ' + category }, [el('div', { class: 'ch' }, [el('span', { text: title }), el('span', { class: 'ch-n', text: concs.length + ' impétrant(s)' })]), list]);
  }
  function rowsUsing(srIds) {
    return project.rows.filter(function (r) { return srIds.some(function (id) { return num(r.widths[id]) > 0; }); }).length;
  }
  function concCard(c) {
    var srList = el('div', { class: 'sr-list' });
    c.sousReseaux.forEach(function (sr) {
      srList.appendChild(el('span', { class: 'sr-chip' }, [
        el('input', { value: sr.label, title: 'Libellé du sous-réseau', 'aria-label': 'Libellé du sous-réseau', oninput: function (e) { sr.label = e.target.value; save(); }, onchange: renderTabs }),
        el('button', { title: 'Supprimer ce sous-réseau', 'aria-label': 'Supprimer le sous-réseau', text: '×', onclick: function () {
          var n = rowsUsing([sr.id]);
          var go = function () { c.sousReseaux = c.sousReseaux.filter(function (x) { return x !== sr; }); commit(); render(); };
          if (!n) { go(); return; }
          askConfirm('Supprimer le sous-réseau', 'Le sous-réseau « ' + c.name + ' ' + sr.label + ' » porte une largeur dans ' + n + ' tronçon(s). Ces largeurs ne seront plus prises en compte.', 'Supprimer', true)
            .then(function (ok) { if (ok) go(); });
        } })
      ]));
    });
    srList.appendChild(el('button', { class: 'tiny add-sr', text: '+ sous-réseau', onclick: function () {
      c.sousReseaux.push({ id: M.uid('sr'), label: 'E' }); commit(); render();
    } }));
    var idx = project.concessionnaires.indexOf(c);
    var move = function (dir) {
      // déplace parmi les impétrants de la même catégorie
      var same = project.concessionnaires.filter(function (x) { return x.category === c.category; });
      var j = same.indexOf(c) + dir; if (j < 0 || j >= same.length) return;
      var other = project.concessionnaires.indexOf(same[j]);
      project.concessionnaires[idx] = same[j]; project.concessionnaires[other] = c;
      commit(); render();
    };
    return el('div', { class: 'conc-card' }, [
      el('div', { class: 'row1' }, [
        el('input', { value: c.name, 'aria-label': 'Nom de l\'impétrant', oninput: function (e) { c.name = e.target.value; save(); } }),
        selectInput([['cable', 'Câbles'], ['conduite', 'Conduites']], c.category, function (v) { c.category = v; commit(); render(); }),
        el('button', { class: 'tiny', title: 'Monter', 'aria-label': 'Monter', text: '↑', onclick: function () { move(-1); } }),
        el('button', { class: 'tiny', title: 'Descendre', 'aria-label': 'Descendre', text: '↓', onclick: function () { move(1); } }),
        el('button', { class: 'tiny danger', title: 'Supprimer l\'impétrant', 'aria-label': 'Supprimer l\'impétrant', text: '✕', onclick: function () {
          var n = rowsUsing(c.sousReseaux.map(function (s) { return s.id; }));
          askConfirm('Supprimer l\'impétrant', 'Supprimer « ' + c.name + ' » et ses ' + c.sousReseaux.length + ' sous-réseau(x) ?' + (n ? ' Il porte des largeurs dans ' + n + ' tronçon(s).' : ''), 'Supprimer', true)
            .then(function (ok) { if (!ok) return; project.concessionnaires = project.concessionnaires.filter(function (x) { return x !== c; }); commit(); render(); });
        } })
      ]),
      srList
    ]);
  }

  // ============================================================ 2. PARAMÈTRES
  var GEOM_COPY = ['litPoseCable', 'htMoyCable', 'recouvSableMinCable', 'ligneAligne', 'recouvNiveauFiniCable', 'hauteurCoffre', 'surfaceType',
    'litPoseConduite', 'recouvSableMinConduite', 'recouvNiveauFiniConduite',
    'remblaiModeCable', 'remblaiSousFondCable', 'remblaiModeConduite', 'remblaiSousFondConduite',
    'gainesCables', 'diamGaine', 'longueurGaines', 'htConduiteMode', 'htConduiteManuelle'];

  function viewParametres() {
    var d = project.defaults;
    var general = el('div', { class: 'grid-params' }, [
      fld('Type de tranchée par défaut', el('input', { value: d.type || 'A', oninput: function (e) { d.type = e.target.value; save(); } })),
      fld('Largeur max disponible' + unit('m'), numberInput({ class: 'in' }, d.largeurMax || 0, function (v) { d.largeurMax = v; save(); }))
    ]);
    var apply = el('button', { text: 'Appliquer à tous les tronçons existants…', onclick: function () {
      if (!project.rows.length) { toast('Aucun tronçon.'); return; }
      askConfirm('Appliquer les paramètres', 'Écraser la géométrie, la couche de surface et la largeur max des ' + project.rows.length + ' tronçon(s) avec ces valeurs ? (Annulable avec Ctrl+Z.)', 'Appliquer')
        .then(function (ok) {
          if (!ok) return;
          project.rows.forEach(function (r) {
            GEOM_COPY.forEach(function (k) { r.geom[k] = d[k]; });
            r.type = r.type || d.type; r.largeurMax = d.largeurMax;
          });
          commit(); render(); toast('Paramètres appliqués aux tronçons.');
        });
    } });
    return card('Paramètres par défaut', 'Valeurs pré-remplies pour chaque nouveau tronçon. Chaque tronçon reste modifiable individuellement.', [
      block('commun', 'Général', [general]),
      block('surface', 'Couche de surface et alignement', [surfaceControls(d, save)]),
      block('cable', 'Câbles', [cableParams(d, save)]),
      catSeparator(),
      block('conduite', 'Conduites', [conduiteParams(d, save)])
    ], [apply]);
  }
  function block(kind, title, nodes, note) {
    return el('section', { class: 'blk blk-' + kind }, [
      el('div', { class: 'blk-head' }, [el('span', { class: 'blk-dot' }), el('h3', { text: title }), note ? el('span', { class: 'blk-note', text: note }) : null]),
      el('div', { class: 'blk-body' }, nodes)
    ]);
  }
  // Barre de séparation entre la saisie des câbles et celle des conduites.
  function catSeparator() {
    return el('div', { class: 'cat-sep', role: 'separator', 'aria-label': 'Séparation câbles / conduites' }, [
      el('span', { class: 'cs-cable', text: '▲ Câbles' }), el('span', { class: 'cs-line' }), el('span', { class: 'cs-conduite', text: 'Conduites ▼' })
    ]);
  }

  // Couche de surface : coffre OU terre végétale (cases à cocher exclusives),
  // épaisseur toujours libre. Posée au-dessus du remblai (terres ou empierrement).
  function surfaceControls(geom, on) {
    var cbCoffre = el('input', { type: 'checkbox', id: M.uid('cb') });
    var cbTV = el('input', { type: 'checkbox', id: M.uid('cb') });
    var thickLabel = el('label');
    var sync = function () {
      var tv = M.surfaceTypeOf(geom) === 'terreVegetale';
      cbCoffre.checked = !tv; cbTV.checked = tv;
      optCoffre.classList.toggle('on', !tv); optTV.classList.toggle('on', tv);
      thickLabel.innerHTML = (tv ? 'Épaisseur de terre végétale' : 'Épaisseur du coffre') + unit('m');
    };
    var choose = function (type) { geom.surfaceType = type; sync(); on(); };
    cbCoffre.addEventListener('change', function () { choose('coffre'); });
    cbTV.addEventListener('change', function () { choose('terreVegetale'); });
    var optCoffre = el('label', { class: 'check-opt coffre', for: cbCoffre.id }, [cbCoffre, el('span', { class: 'sw' }), el('span', { text: 'Coffre' })]);
    var optTV = el('label', { class: 'check-opt tv', for: cbTV.id }, [cbTV, el('span', { class: 'sw' }), el('span', { text: 'Terre végétale' })]);
    var thick = el('div', { class: 'fld' }, [thickLabel, numberInput({ class: 'in' }, geom.hauteurCoffre != null ? geom.hauteurCoffre : 0, function (v) { geom.hauteurCoffre = v; on(); })]);
    sync();
    return el('div', { class: 'grid-params' }, [
      el('div', { class: 'fld span2' }, [el('label', { text: 'Nature de la couche au-dessus du remblai' }), el('div', { class: 'check-row' }, [optCoffre, optTV])]),
      thick,
      fld('Ligne de recouvrement sable alignée <small>(câbles / conduites)</small>',
        selectInput([['OUI', 'OUI'], ['NON', 'NON']], geom.ligneAligne || 'OUI', function (v) { geom.ligneAligne = v; on(); }))
    ]);
  }
  function geomNum(geom, key, label, on) {
    return fld(label + unit('m'), numberInput({ class: 'in' }, geom[key] != null ? geom[key] : 0, function (v) { geom[key] = v; on(); }));
  }
  // Remblai entre le haut du sable et le bas de la couche de surface :
  // terres décaissées (réutilisation) / empierrement de sous-fondation / hauteur manuelle.
  function remblaiControl(geom, modeKey, valKey, on) {
    var numInput = numberInput({ class: 'in' }, geom[valKey] != null ? geom[valKey] : 0, function (v) { geom[valKey] = v; on(); });
    var sync = function () { numInput.disabled = (geom[modeKey] || 'terres') !== 'manuel'; };
    var sel = selectInput([['terres', 'Terres décaissées'], ['empierrement', 'Empierrement sous-fond.'], ['manuel', 'Manuel (hauteur)']],
      geom[modeKey] || 'terres', function (v) { geom[modeKey] = v; sync(); on(); });
    sync();
    return [fld('Remblai <small>(sable → couche de surface)</small>', sel), fld('Hauteur empierrement <small>(m, si manuel)</small>', numInput)];
  }
  // Câbles sous gaines (fourreaux) : OUI/NON + Ø ; la longueur de gaines
  // devient automatique ((largeur câbles ÷ Ø) × longueur) quand OUI, et le
  // volume des gaines est déduit du volume de sable.
  function gaineControl(geom, on) {
    var diamInput = numberInput({ class: 'in' }, geom.diamGaine != null ? geom.diamGaine : 0.16, function (v) { geom.diamGaine = v; on(); });
    var lgInput = numberInput({ class: 'in' }, geom.longueurGaines != null ? geom.longueurGaines : 0, function (v) { geom.longueurGaines = v; on(); });
    var sync = function () {
      var oui = (geom.gainesCables || 'NON') === 'OUI';
      diamInput.disabled = !oui; lgInput.disabled = oui;
      lgInput.title = oui ? 'Calculée automatiquement : (largeur câbles ÷ Ø) × longueur' : '';
    };
    var sel = selectInput([['NON', 'NON'], ['OUI', 'OUI']], geom.gainesCables || 'NON', function (v) { geom.gainesCables = v; sync(); on(); });
    sync();
    return [fld('Câbles sous gaines <small>(fourreaux)</small>', sel), fld('Ø gaine' + unit('m'), diamInput),
      fld('Longueur gaines rigides <small>(m, auto si gaines)</small>', lgInput)];
  }
  // Hauteur moyenne conduites (BB) : MAX automatique des diamètres, ou forcée.
  function htConduiteControl(geom, on) {
    var valInput = numberInput({ class: 'in' }, geom.htConduiteManuelle != null ? geom.htConduiteManuelle : 0, function (v) { geom.htConduiteManuelle = v; on(); });
    var sync = function () { valInput.disabled = (geom.htConduiteMode || 'auto') !== 'manuel'; };
    var sel = selectInput([['auto', 'Auto (MAX des Ø)'], ['manuel', 'Manuelle']], geom.htConduiteMode || 'auto', function (v) { geom.htConduiteMode = v; sync(); on(); });
    sync();
    return [fld('Ht moyenne conduites', sel), fld('Ht conduites forcée <small>(m, si manuelle)</small>', valInput)];
  }
  function cableParams(geom, on) {
    var g = el('div', { class: 'grid-params' }, [
      geomNum(geom, 'litPoseCable', 'Lit de pose min.', on),
      geomNum(geom, 'htMoyCable', 'Ht moyenne câbles', on),
      geomNum(geom, 'recouvSableMinCable', 'Recouvrement sable min.', on),
      geomNum(geom, 'recouvNiveauFiniCable', 'Recouvr. min. / niveau fini', on)
    ]);
    remblaiControl(geom, 'remblaiModeCable', 'remblaiSousFondCable', on).forEach(function (n) { g.appendChild(n); });
    gaineControl(geom, on).forEach(function (n) { g.appendChild(n); });
    return g;
  }
  function conduiteParams(geom, on) {
    var g = el('div', { class: 'grid-params' }, [
      geomNum(geom, 'litPoseConduite', 'Lit de pose min.', on),
      geomNum(geom, 'recouvSableMinConduite', 'Recouvrement sable min.', on),
      geomNum(geom, 'recouvNiveauFiniConduite', 'Recouvr. min. / niveau fini', on)
    ]);
    remblaiControl(geom, 'remblaiModeConduite', 'remblaiSousFondConduite', on).forEach(function (n) { g.appendChild(n); });
    htConduiteControl(geom, on).forEach(function (n) { g.appendChild(n); });
    return g;
  }

  // ============================================================== 3. TRONÇONS
  function addRow() {
    var r = M.newRow(project); project.rows.push(r); ui.expandedRowId = r.id; commit(); render();
    var node = document.getElementById('row-' + r.id);
    if (node) { node.scrollIntoView({ behavior: 'smooth', block: 'start' }); var f = $('.editor input'); if (f) f.focus({ preventScroll: true }); }
  }
  function viewTroncons() {
    var add = el('button', { class: 'primary', id: 'btn-add-row', text: '+ Ajouter un tronçon', onclick: addRow });
    var body;
    if (!project.rows.length) {
      body = el('div', { class: 'empty' }, [
        el('p', { class: 'empty-t', text: 'Aucun tronçon pour l\'instant' }),
        el('p', { text: 'Vérifiez les impétrants (onglet 1) et les paramètres par défaut (onglet 2), puis ajoutez votre premier tronçon.' }),
        el('button', { class: 'primary', text: '+ Ajouter un tronçon', onclick: addRow })
      ]);
    } else body = rowsTable();
    return card('Tronçons de tranchée', project.rows.length ? 'Cliquez sur une ligne pour l\'éditer. La coupe et les volumes se mettent à jour à chaque frappe.' : null, [el('div', { id: 'rows-area' }, [body])], [add]);
  }
  function th(t, cls) { return el('th', { class: cls || '', text: t }); }
  function td(t, cls) { return el('td', { class: cls || '', text: t }); }
  function statusCell(r, c, warns) {
    var errs = warns.filter(function (w) { return w.level === 'error'; }).length;
    var wr = warns.length - errs;
    var wrap = el('div', { class: 'status', id: 'status-' + r.id });
    wrap.appendChild(el('span', { class: 'badge ' + (c.AE === 'NOK' ? 'nok' : 'ok'), text: c.AE === 'NOK' ? 'NOK (trop large)' : 'OK' }));
    if (errs) wrap.appendChild(el('span', { class: 'badge err', title: errs + ' erreur(s) de cohérence', text: '⚠ ' + errs }));
    if (wr) wrap.appendChild(el('span', { class: 'badge warn', title: wr + ' point(s) à vérifier', text: '! ' + wr }));
    return wrap;
  }
  function surfChip(c) {
    var tv = c.surfaceType === 'terreVegetale';
    return el('span', { class: 'surf-chip ' + (tv ? 'tv' : 'coffre'), title: 'Couche de surface', text: (tv ? 'Terre végétale' : 'Coffre') + ' ' + fmt(c.AM) + ' m' });
  }
  function rowsTable() {
    var L = M.buildLayout(project);
    var table = el('table', { class: 'rows' });
    table.appendChild(el('thead', {}, [el('tr', {}, [
      th('#'), th('De'), th('à'), th('Type'), th('Longueur (m)', 'num'),
      th('Larg. théo. (m)', 'num'), th('Vol. total (m³)', 'num'), th('Surface'), th('Contrôle'), th('')
    ])]));
    var tb = el('tbody');
    project.rows.forEach(function (r, i) {
      var c = M.computeRowWithLayout(project, r, L);
      var warns = M.rowWarnings(project, r, L);
      var editing = ui.expandedRowId === r.id;
      var toggle = function () { ui.expandedRowId = editing ? null : r.id; render(); if (!editing) { var n = document.getElementById('row-' + r.id); if (n) n.scrollIntoView({ behavior: 'smooth', block: 'start' }); } };
      var act = function (fn) { return function (e) { e.stopPropagation(); fn(); }; };
      var tr = el('tr', { class: 'summary' + (editing ? ' editing' : ''), id: 'row-' + r.id, onclick: toggle }, [
        td(String(i + 1), 'idx'),
        el('td', { id: 'de-' + r.id, text: r.de || '–' }), el('td', { id: 'a-' + r.id, text: r.a || '–' }), el('td', { id: 'type-' + r.id, text: r.type || '–' }),
        el('td', { id: 'lg-' + r.id, class: 'num', text: fmt(r.longueur) }),
        el('td', { id: 'theo-' + r.id, class: 'num', text: fmt(c.AC) }),
        el('td', { id: 'vol-' + r.id, class: 'num strong', text: fmt(c.BT) }),
        el('td', { id: 'surf-' + r.id }, [surfChip(c)]),
        el('td', {}, [statusCell(r, c, warns)]),
        el('td', {}, [el('div', { class: 'rowact' }, [
          el('button', { class: 'tiny', title: 'Monter', 'aria-label': 'Monter le tronçon', text: '↑', disabled: i === 0, onclick: act(function () { moveRow(i, -1); }) }),
          el('button', { class: 'tiny', title: 'Descendre', 'aria-label': 'Descendre le tronçon', text: '↓', disabled: i === project.rows.length - 1, onclick: act(function () { moveRow(i, 1); }) }),
          el('button', { class: 'tiny', title: 'Dupliquer', 'aria-label': 'Dupliquer le tronçon', text: '⧉', onclick: act(function () {
            var copy = JSON.parse(JSON.stringify(r)); copy.id = M.uid('row');
            project.rows.splice(i + 1, 0, copy); ui.expandedRowId = copy.id; commit(); render(); toast('Tronçon dupliqué.');
          }) }),
          el('button', { class: 'tiny danger', title: 'Supprimer', 'aria-label': 'Supprimer le tronçon', text: '✕', onclick: act(function () {
            askConfirm('Supprimer le tronçon', 'Supprimer le tronçon n° ' + (i + 1) + (r.de || r.a ? ' (' + (r.de || '?') + ' → ' + (r.a || '?') + ')' : '') + ' ? (Annulable avec Ctrl+Z.)', 'Supprimer', true)
              .then(function (ok) { if (!ok) return; project.rows.splice(i, 1); if (ui.expandedRowId === r.id) ui.expandedRowId = null; commit(); render(); toast('Tronçon supprimé — Ctrl+Z pour annuler.'); });
          }) }),
          el('button', { class: 'tiny edit', text: editing ? 'Fermer' : 'Éditer', onclick: act(toggle) })
        ])])
      ]);
      tb.appendChild(tr);
      if (editing) tb.appendChild(el('tr', { class: 'ed-row' }, [el('td', { colspan: 10 }, [rowEditor(r, L)])]));
    });
    table.appendChild(tb);
    return el('div', { class: 'table-wrap' }, [table]);
  }
  function moveRow(i, dir) {
    var j = i + dir; if (j < 0 || j >= project.rows.length) return;
    var r = project.rows[i]; project.rows[i] = project.rows[j]; project.rows[j] = r;
    commit(); render();
  }

  function rowEditor(r, L) {
    var upd = function () { updateRowLive(r); };
    var form = el('div', { class: 'ed-form' });

    // Identité
    form.appendChild(block('commun', 'Identité du tronçon', [el('div', { class: 'grid-params' }, [
      fld('Point « De »', el('input', { value: r.de || '', oninput: function (e) { r.de = e.target.value; upd(); } })),
      fld('Point « à »', el('input', { value: r.a || '', oninput: function (e) { r.a = e.target.value; upd(); } })),
      fld('Type de tranchée', el('input', { value: r.type || '', oninput: function (e) { r.type = e.target.value; upd(); } })),
      fld('Longueur' + unit('m'), numberInput({ class: 'in' }, r.longueur != null ? r.longueur : 0, function (v) { r.longueur = v; upd(); })),
      fld('Largeur max disponible' + unit('m'), numberInput({ class: 'in' }, r.largeurMax != null ? r.largeurMax : 0, function (v) { r.largeurMax = v; upd(); })),
      fld('Commentaires', el('input', { value: r.commentaire || '', oninput: function (e) { r.commentaire = e.target.value; upd(); } }), 'span2')
    ])]));

    // Couche de surface (commune aux câbles et aux conduites)
    form.appendChild(block('surface', 'Couche de surface', [surfaceControls(r.geom, upd)]));

    // Câbles puis conduites, dans deux blocs nettement séparés
    var widthsOf = function (cat) {
      var cols = L.cols.filter(function (col) { return (col.isChannel || col.isInterstice) && col.category === cat; });
      if (!cols.length) return null;
      var g = el('div', { class: 'width-grid' });
      cols.forEach(function (col) {
        if (col.isInterstice) {
          g.appendChild(el('div', { class: 'wfld interstice' }, [el('label', { text: 'Interstice ' + col.h3 + ' · ' + (col.intBefore || ''), title: 'Interstice avant ' + (col.intBefore || '') }),
            numberInput({ class: 'in' }, num(r.interstices[col.intKey]), function (v) { r.interstices[col.intKey] = v; upd(); })]));
        } else {
          g.appendChild(el('div', { class: 'wfld' }, [el('label', { text: col.h3.replace('\n', ' ') }),
            numberInput({ class: 'in' }, num(r.widths[col.srId]), function (v) { r.widths[col.srId] = v; upd(); })]));
        }
      });
      return g;
    };
    var wCable = widthsOf('cable'), wCond = widthsOf('conduite');
    form.appendChild(block('cable', 'Câbles', [
      el('h4', { text: cat4('Largeurs par sous-réseau et interstices', 'm') }),
      wCable || el('p', { class: 'hint', text: 'Aucun sous-réseau « câbles » défini (onglet Impétrants).' }),
      wCable ? el('p', { class: 'hint', text: 'Sous gaines, chaque largeur est arrondie au multiple de Ø supérieur (nombre entier de fourreaux).' }) : null,
      el('h4', { text: 'Paramètres de pose' }), cableParams(r.geom, upd)
    ]));
    form.appendChild(catSeparator());
    form.appendChild(block('conduite', 'Conduites', [
      el('h4', { text: cat4('Diamètres par sous-réseau et interstices', 'm') }),
      wCond || el('p', { class: 'hint', text: 'Aucun sous-réseau « conduites » défini (onglet Impétrants).' }),
      el('h4', { text: 'Paramètres de pose' }), conduiteParams(r.geom, upd)
    ]));

    // Colonne de droite : alertes, résultats et coupe en direct
    var side = el('aside', { class: 'ed-side' }, [
      el('div', { id: 'warn-' + r.id }),
      el('div', { class: 'preview', id: 'preview-' + r.id }),
      el('div', { class: 'coupe-head' }, [el('h4', { text: 'Coupe à l\'échelle' }),
        el('button', { class: 'tiny', text: 'Image PNG', title: 'Enregistrer la coupe en image', onclick: function () { exportCoupePng(r); } })]),
      el('div', { class: 'coupe-box', id: 'coupe-' + r.id })
    ]);

    var editor = el('div', { class: 'editor' }, [
      el('div', { class: 'ed-grid' }, [form, side]),
      el('div', { class: 'ed-full' }, [
        el('h4', { text: 'Parts de volume par sous-réseau' }),
        el('div', { id: 'rep-' + r.id }),
        el('p', { class: 'hint', text: NOTE_INTERSTICE })
      ])
    ]);
    // remplissage différé : les conteneurs doivent être dans le document
    setTimeout(function () { fillLive(r, L); }, 0);
    return editor;
  }
  function cat4(t, u) { return t + ' (' + u + ')'; }

  var NOTE_INTERSTICE = 'Les interstices ne sont attribués à aucun sous-réseau : la part d\'un sous-réseau est calculée sur la largeur occupée, puis appliquée au volume total de la partie (interstices compris). Le volume des interstices est donc réparti au prorata des largeurs occupées.';

  function fillWarnings(r, warns) {
    var box = document.getElementById('warn-' + r.id); if (!box) return;
    box.innerHTML = '';
    if (!warns.length) { box.appendChild(el('div', { class: 'alert ok', text: '✓ Aucune anomalie détectée sur ce tronçon.' })); return; }
    warns.forEach(function (w) { box.appendChild(el('div', { class: 'alert ' + (w.level === 'error' ? 'err' : 'warn') }, [el('b', { text: w.level === 'error' ? 'Erreur — ' : 'À vérifier — ' }), w.msg])); });
  }
  function fillPreview(r, c) {
    var pv = document.getElementById('preview-' + r.id); if (!pv) return;
    pv.innerHTML = '';
    var tv = c.surfaceType === 'terreVegetale';
    var items = [['Largeur théorique', c.AC, 'm', 'key'], ['Volume total', c.BT, 'm³', 'key'],
      ['Largeur câbles', c.AT, 'm'], ['Largeur conduites', c.BN, 'm'],
      ['Volume tranchée câbles', c.AZ, 'm³'], ['Volume tranchée conduites', c.BS, 'm³'],
      ['Volume sable', c.AW + c.BP, 'm³'], ['Volume déblais excéd.', c.AY + c.BR, 'm³'],
      ['Volume remblai s-fond.', c.AX + c.BQ, 'm³'], [tv ? 'Volume terre végétale' : 'Volume coffre', c.SV, 'm³']];
    if (c.AU > 0) items.push(['Longueur gaines', c.AU, 'm']);
    items.forEach(function (x) {
      pv.appendChild(el('div', { class: 'pv' + (x[3] ? ' ' + x[3] : '') }, [el('b', { text: fmt(x[1]) }), el('span', { text: x[0] + ' (' + x[2] + ')' })]));
    });
  }
  function fillCoupe(r) {
    var box = document.getElementById('coupe-' + r.id); if (!box) return;
    box.innerHTML = '';
    try { box.appendChild(window.TICoupe.render(project, r, M)); }
    catch (e) { box.textContent = 'Coupe indisponible : ' + e.message; }
  }
  function repTable(r, L) {
    var rep = M.computeRepartition(project, r, L);
    if (!rep.channels.length) return el('div', { class: 'hint', text: 'Aucun sous-réseau.' });
    var t = el('table', { class: 'rows rep' });
    t.appendChild(el('thead', {}, [el('tr', {}, [
      th('Sous-réseau'), th('Catégorie'), th('Largeur (m)', 'num'), th('Part catégorie', 'num'),
      th('Part totale', 'num'), th('Vol. câbles (m³)', 'num'),
      th('Vol. conduites (m³)', 'num'), th('Vol. total attribué (m³)', 'num')
    ])]));
    var tb = el('tbody'), prevCat = null;
    rep.channels.forEach(function (ch) {
      var tr = el('tr', { class: (prevCat && prevCat !== ch.category ? 'cat-break ' : '') + (ch.width > 0 ? '' : 'muted') }, [
        td(ch.label), el('td', {}, [el('span', { class: 'cat-tag ' + ch.category, text: ch.category === 'cable' ? 'Câbles' : 'Conduites' })]),
        td(fmt(ch.width), 'num'), td(fmtPct(ch.partCat), 'num'), barCell(ch.partTot, ch.category),
        td(fmt(ch.volCable), 'num'), td(fmt(ch.volConduite), 'num'), td(fmt(ch.volTranchee), 'num strong')
      ]);
      prevCat = ch.category;
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    return el('div', { class: 'rep-wrap' }, [t]);
  }
  function barCell(part, category) {
    return el('td', { class: 'num' }, [el('div', { class: 'minibar' + (category === 'conduite' ? ' conduite' : '') }, [
      el('span', { text: fmtPct(part) }),
      el('div', { class: 'track' }, [el('div', { class: 'fill', style: 'width:' + Math.min(100, Math.max(0, part * 100)).toFixed(1) + '%' })])
    ])]);
  }
  function fillLive(r, L) {
    var c = M.computeRowWithLayout(project, r, L);
    var warns = M.rowWarnings(project, r, L);
    fillWarnings(r, warns); fillPreview(r, c); fillCoupe(r);
    var box = document.getElementById('rep-' + r.id); if (box) { box.innerHTML = ''; box.appendChild(repTable(r, L)); }
    return { c: c, warns: warns };
  }
  // Mise à jour en direct pendant la frappe : aucun champ n'est reconstruit
  // (le curseur reste en place), seuls les résultats sont rafraîchis.
  function updateRowLive(r) {
    var L = M.buildLayout(project);
    var res = fillLive(r, L), c = res.c;
    var set = function (id, text) { var n = document.getElementById(id + '-' + r.id); if (n) n.textContent = text; };
    set('de', r.de || '–'); set('a', r.a || '–'); set('type', r.type || '–');
    set('lg', fmt(r.longueur)); set('theo', fmt(c.AC)); set('vol', fmt(c.BT));
    var sf = document.getElementById('surf-' + r.id); if (sf) { sf.innerHTML = ''; sf.appendChild(surfChip(c)); }
    var st = document.getElementById('status-' + r.id); if (st) st.parentNode.replaceChild(statusCell(r, c, res.warns), st);
    refreshTotals(); renderTabs();
    save();
  }

  // ---------------------------------------------------------------------- totaux
  function refreshTotals() {
    var area = $('#totals-area'); if (!area) return;
    var t = M.projectTotals(project);
    var box = el('div', { class: 'totals' });
    var items = [['Tronçons', String(project.rows.length), ''], ['Longueur totale', fmt(t.longueur, 0), 'm'],
      ['Volume total tranchée', fmt(t.volTotal), 'm³', 'key'],
      ['Sable', fmt(t.volSableCable + t.volSableConduite), 'm³'],
      ['Remblai sous-fond.', fmt(t.volRemblai), 'm³'],
      ['Déblais excéd.', fmt(t.volDeblais), 'm³']];
    if (t.volTerreVegetale > 0) items.push(['Terre végétale', fmt(t.volTerreVegetale), 'm³']);
    if (t.volCoffre > 0) items.push(['Coffre', fmt(t.volCoffre), 'm³']);
    items.push(['Contrôles NOK', String(t.nok), '', t.nok ? 'bad' : '']);
    items.forEach(function (x) { box.appendChild(el('div', { class: 't' + (x[3] ? ' ' + x[3] : '') }, [el('b', { text: x[1] + (x[2] ? ' ' + x[2] : '') }), el('span', { text: x[0] })])); });
    area.innerHTML = ''; area.appendChild(box);
  }

  // ============================================================== 4. SYNTHÈSE
  function syntheseContent(forPrint) {
    var L = M.buildLayout(project);
    var t = M.projectTotals(project);
    var out = [];
    var tiles = el('div', { class: 'tiles' });
    [['Tronçons', String(project.rows.length), ''], ['Longueur totale de tranchées', fmt(t.longueur), 'm'],
     ['Volume total de tranchée', fmt(t.volTotal), 'm³', 'key'], ['Sable d\'enrobage', fmt(t.volSableCable + t.volSableConduite), 'm³'],
     ['Remblai matériaux de sous-fondation', fmt(t.volRemblai), 'm³'], ['Déblais excédentaires (merlon)', fmt(t.volDeblais), 'm³'],
     ['Terre végétale (couche de surface)', fmt(t.volTerreVegetale), 'm³'], ['Coffre (couche de surface)', fmt(t.volCoffre), 'm³'],
     ['Longueur gaines rigides', fmt(t.longueurGaines), 'm'], ['Tronçons trop larges (NOK)', String(t.nok), '', t.nok ? 'bad' : '']]
      .forEach(function (x) { tiles.appendChild(el('div', { class: 'tile' + (x[3] ? ' ' + x[3] : '') }, [el('b', { text: x[1] + (x[2] ? ' ' + x[2] : '') }), el('span', { text: x[0] })])); });
    out.push(el('h3', { class: 'sec', text: 'Quantités globales (métré)' }), tiles);

    // Par type de tranchée
    var types = M.projectByType(project);
    out.push(el('h3', { class: 'sec', text: 'Longueurs et volumes par type de tranchée' }));
    if (types.length) {
      var tt = el('table', { class: 'rows compact' }, [el('thead', {}, [el('tr', {}, [th('Type'), th('Nb tronçons', 'num'), th('Longueur (m)', 'num'), th('Volume tranchée (m³)', 'num')])])]);
      var tb = el('tbody');
      types.forEach(function (x) { tb.appendChild(el('tr', {}, [td(x.type, 'strong'), td(String(x.nb), 'num'), td(fmt(x.longueur), 'num'), td(fmt(x.volume), 'num')])); });
      tt.appendChild(tb); out.push(el('div', { class: 'table-wrap' }, [tt]));
    } else out.push(el('p', { class: 'hint', text: 'Aucun tronçon saisi.' }));

    // Clé de répartition
    var rep = M.projectRepartition(project);
    out.push(el('h3', { class: 'sec', text: 'Clé de répartition par sous-réseau' }),
      el('p', { class: 'hint', text: 'Clé = Σ volume de tranchée attribué ÷ Σ volume total. Le volume des interstices est réparti au prorata de la largeur occupée par chaque sous-réseau.' }));
    if (rep.channels.length) {
      var rt = el('table', { class: 'rows compact' }, [el('thead', {}, [el('tr', {}, [th('Impétrant'), th('Sous-réseau'), th('Catégorie'), th('Clé de répartition', 'num'),
        th('Longueur attribuée (m)', 'num'), th('Vol. tranchée (m³)', 'num'), th('Vol. sable (m³)', 'num'), th('Vol. remblai s-fond. (m³)', 'num'), th('Vol. déblais excéd. (m³)', 'num')])])]);
      var rb = el('tbody'), sums = { cle: 0, longueur: 0, vol: 0, sable: 0, remblai: 0, deblais: 0 }, prevCat = null;
      rep.channels.forEach(function (a) {
        rb.appendChild(el('tr', { class: (prevCat && prevCat !== a.category ? 'cat-break ' : '') + (a.volTranchee > 0 ? '' : 'muted') }, [td(a.concName, 'strong'), td(a.srLabel),
          el('td', {}, [el('span', { class: 'cat-tag ' + a.category, text: a.category === 'cable' ? 'Câbles' : 'Conduites' })]),
          barCell(a.cle, a.category), td(fmt(a.longueur), 'num'), td(fmt(a.volTranchee), 'num'), td(fmt(a.volSable), 'num'), td(fmt(a.volRemblai), 'num'), td(fmt(a.volDeblais), 'num')]));
        prevCat = a.category;
        sums.cle += a.cle; sums.longueur += a.longueur; sums.vol += a.volTranchee; sums.sable += a.volSable; sums.remblai += a.volRemblai; sums.deblais += a.volDeblais;
      });
      rb.appendChild(el('tr', { class: 'total' }, [td('TOTAL'), td(''), td(''), td(fmtPct(sums.cle), 'num'), td(fmt(sums.longueur), 'num'), td(fmt(sums.vol), 'num'), td(fmt(sums.sable), 'num'), td(fmt(sums.remblai), 'num'), td(fmt(sums.deblais), 'num')]));
      rt.appendChild(rb); out.push(el('div', { class: 'table-wrap' }, [rt]));
      var okSum = rep.totalVol > 0 ? Math.abs(sums.cle - 1) < 1e-4 : true;
      out.push(el('p', { class: 'ctrl ' + (okSum ? 'ok' : 'bad'), text: rep.totalVol > 0 ? (okSum ? '✓ Contrôle : la somme des clés vaut bien 100 %.' : '⚠ Contrôle : la somme des clés ne vaut pas 100 %.') : 'Aucun volume à répartir.' }));
    } else out.push(el('p', { class: 'hint', text: 'Aucun sous-réseau défini.' }));

    // Alertes de cohérence
    out.push(el('h3', { class: 'sec', text: 'Alertes de cohérence' }));
    var any = false, list = el('div', { class: 'alert-list' });
    project.rows.forEach(function (r, i) {
      var warns = M.rowWarnings(project, r, L); if (!warns.length) return;
      any = true;
      var head = el('div', { class: 'al-head' }, [el('b', { text: 'Tronçon n° ' + (i + 1) + (r.de || r.a ? ' — ' + (r.de || '?') + ' → ' + (r.a || '?') : '') })]);
      if (!forPrint) head.appendChild(el('button', { class: 'tiny', text: 'Ouvrir', onclick: function () { ui.expandedRowId = r.id; setTab('troncons'); var n = document.getElementById('row-' + r.id); if (n) n.scrollIntoView({ block: 'start' }); } }));
      var grp = el('div', { class: 'al-grp' }, [head]);
      warns.forEach(function (w) { grp.appendChild(el('div', { class: 'alert ' + (w.level === 'error' ? 'err' : 'warn') }, [el('b', { text: w.level === 'error' ? 'Erreur — ' : 'À vérifier — ' }), w.msg])); });
      list.appendChild(grp);
    });
    out.push(any ? list : el('div', { class: 'alert ok', text: project.rows.length ? '✓ Aucune anomalie détectée sur les ' + project.rows.length + ' tronçon(s).' : 'Aucun tronçon saisi.' }));
    return out;
  }
  function viewSynthese() {
    return card('Synthèse du projet', 'Mêmes quantités que l\'onglet « Synthèse » du classeur Excel, recalculées en direct.', syntheseContent(false));
  }

  // ------------------------------------------------------------ gestion projets
  function openProjects() {
    flushHistory(); persist();
    var list = el('div', { class: 'proj-list' });
    store.index.forEach(function (e) {
      var cur = e.id === store.currentId;
      list.appendChild(el('div', { class: 'proj' + (cur ? ' current' : '') }, [
        el('div', { class: 'proj-main' }, [
          el('b', { text: e.name || 'Projet' }), cur ? el('span', { class: 'badge ok', text: 'ouvert' }) : null,
          el('div', { class: 'proj-meta', text: (e.rows || 0) + ' tronçon(s) · modifié le ' + dateFr(e.updated) })
        ]),
        el('div', { class: 'proj-act' }, [
          cur ? null : el('button', { class: 'primary', text: 'Ouvrir', onclick: function () { closeModal(); switchProject(e.id); toast('Projet « ' + e.name + ' » ouvert.'); } }),
          el('button', { text: 'Dupliquer', onclick: function () {
            var p = readProject(e.id); if (!p) return;
            p.name = uniqueName((p.name || 'Projet') + ' — copie');
            flushHistory(); addProject(p); ui.expandedRowId = null; resetHistory(); render(); openProjects(); toast('Projet dupliqué et ouvert.');
          } }),
          el('button', { class: 'danger', text: 'Supprimer', onclick: function () {
            askConfirm('Supprimer le projet', 'Supprimer définitivement le projet « ' + e.name + ' » de cet appareil ? Cette action ne peut pas être annulée. Pensez à l\'exporter avant si nécessaire.', 'Supprimer', true)
              .then(function (ok) {
                if (ok) {
                  lsDel(K_PROJECT + e.id);
                  store.index = store.index.filter(function (x) { return x.id !== e.id; });
                  if (!store.index.length) { addProject(M.defaultProject()); ui.expandedRowId = null; resetHistory(); render(); }
                  else if (cur) switchProject(store.index[0].id);
                  else lsSet(K_INDEX, JSON.stringify(store.index));
                  toast('Projet supprimé.');
                }
                openProjects();
              });
          } })
        ])
      ]));
    });
    openModal('Projets enregistrés sur cet appareil', el('div', {}, [
      el('p', { class: 'hint', text: 'Chaque projet est enregistré automatiquement. Utilisez « Exporter » pour le sauvegarder en fichier .json ou le transmettre.' }), list
    ]), { wide: true, footer: [
      el('button', { text: 'Fermer', onclick: closeModal }),
      el('button', { class: 'primary', text: '+ Nouveau projet', onclick: function () {
        var p = M.defaultProject(); p.name = uniqueName('Nouveau projet');
        flushHistory(); addProject(p); ui.expandedRowId = null; resetHistory(); closeModal(); setTab('impetrants'); toast('Nouveau projet créé.');
      } })
    ] });
  }

  function openAbout() {
    var logo = $('header.app .logo').innerHTML;
    openModal('À propos', el('div', { class: 'about' }, [
      el('div', { class: 'about-top' }, [el('span', { class: 'about-logo', html: logo }),
        el('div', {}, [el('h3', { text: INFO.name }), el('p', { class: 'about-v', text: 'Version ' + INFO.version + ' du ' + dateOnly(INFO.date) })])]),
      el('p', { text: 'Calcul des largeurs, des volumes et de la répartition par impétrant des tranchées communes, avec génération du classeur Excel (formules vivantes) et de la notice Word.' }),
      el('h4', { text: 'Nouveautés de la version 2.0' }),
      el('ul', {}, ['Couche de surface au choix : coffre ou terre végétale, avec son volume au métré',
        'Navigation par onglets, coupe en direct à côté de la saisie, séparation nette câbles / conduites',
        'Plusieurs projets enregistrés, annuler / rétablir (Ctrl+Z / Ctrl+Y), réordonnancement des tronçons',
        'Onglet Synthèse (clé de répartition), alertes de cohérence',
        'Coupes dans la notice Word, export de la coupe en image, impression / PDF'].map(function (t) { return el('li', { text: t }); })),
      el('p', { class: 'hint', text: 'Cette version s\'installe à côté de la version 1 sans la remplacer. Les projets .json de la version 1 s\'importent tels quels.' })
    ]), { footer: [el('button', { class: 'primary', text: 'Fermer', onclick: closeModal })] });
  }
  function dateOnly(iso) { var p = String(iso || '').split('-'); return p.length === 3 ? p[2] + '/' + p[1] + '/' + p[0] : iso; }

  // ----------------------------------------------------------- import / export
  function exportJSON() {
    var blob = new Blob([JSON.stringify(project, null, 2)], { type: 'application/json' });
    downloadBlob(blob, sanitize(project.name) + '.tranchees.json');
    toast('Projet exporté (.json)');
  }
  function importJSON(file) {
    var reader = new FileReader();
    reader.onload = function () {
      try {
        var data = JSON.parse(reader.result);
        if (!data || !Array.isArray(data.concessionnaires)) throw new Error('ce fichier n\'est pas un projet de tranchées');
        var p = M.migrateProject(data);
        p.name = uniqueName(p.name || 'Projet importé');
        flushHistory(); addProject(p); ui.expandedRowId = null; resetHistory(); setTab('troncons');
        toast('Projet « ' + p.name + ' » importé (' + p.rows.length + ' tronçon(s)).');
      } catch (e) { openModal('Import impossible', el('p', { class: 'modal-msg', text: 'Fichier invalide : ' + e.message }), { footer: [el('button', { class: 'primary', text: 'Fermer', onclick: closeModal })] }); }
    };
    reader.readAsText(file);
  }
  function generateExcel() {
    try {
      var wb = M.buildWorkbook(project, window.ExcelJS);
      wb.xlsx.writeBuffer().then(function (buf) {
        downloadBlob(new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), sanitize(project.name) + '.xlsx');
        toast('Classeur Excel généré.');
      });
    } catch (e) { alertError('Erreur lors de la génération Excel', e); }
  }
  function alertError(title, e) {
    console.error(e);
    openModal(title, el('p', { class: 'modal-msg', text: (e && e.message) || String(e) }), { footer: [el('button', { class: 'primary', text: 'Fermer', onclick: closeModal })] });
  }
  // Coupe d'un tronçon en PNG (octets), hors écran.
  function coupePng(r, scale) {
    var holder = window.TICoupe.render(project, r, M);
    var svg = holder.querySelector('svg');
    if (!svg) return Promise.resolve(null);
    return window.TICoupe.toPng(svg, scale || 2);
  }
  function rowTitle(r, i) { return 'Tronçon n° ' + (i + 1) + (r.de || r.a ? ' — ' + (r.de || '?') + ' → ' + (r.a || '?') : '') + (r.type ? ' (type ' + r.type + ')' : ''); }
  function exportCoupePng(r) {
    var i = project.rows.indexOf(r);
    coupePng(r, 3).then(function (res) {
      if (!res) { toast('Coupe vide : renseignez des largeurs et des hauteurs.'); return; }
      downloadBlob(res.blob, sanitize(project.name) + '_coupe_troncon_' + (i + 1) + '.png');
      toast('Coupe enregistrée en image.');
    }).catch(function (e) { alertError('Export de la coupe impossible', e); });
  }
  function generateWord() {
    toast('Génération du document Word…');
    // coupes en image, l'une après l'autre (une image manquante n'empêche pas le document)
    var coupes = [];
    var chain = Promise.resolve();
    project.rows.forEach(function (r, i) {
      chain = chain.then(function () {
        return coupePng(r, 2).then(function (res) {
          if (!res) return null;
          return res.blob.arrayBuffer().then(function (buf) { coupes.push({ index: i, title: rowTitle(r, i), data: new Uint8Array(buf), width: res.width, height: res.height }); });
        }).catch(function () { return null; });
      });
    });
    chain.then(function () { return window.TIDocx.generate(project, M, { coupes: coupes }); })
      .then(function (blob) { downloadBlob(blob, sanitize(project.name) + '.docx'); toast('Document Word généré (' + coupes.length + ' coupe(s)).'); })
      .catch(function (e) { alertError('Erreur lors de la génération Word', e); });
  }

  // ------------------------------------------------------------------ impression
  function printProject() {
    if (window.TIAndroid) { toast('Impression indisponible sur Android : utilisez « Word » ou « Excel ».'); return; }
    var L = M.buildLayout(project);
    var root = $('#print-root'); root.innerHTML = '';
    root.appendChild(el('div', { class: 'pr-head' }, [
      el('span', { class: 'pr-logo', html: $('header.app .logo').innerHTML }),
      el('div', {}, [el('h1', { text: project.name || 'Projet' }),
        el('p', { text: 'Tranchées communes impétrants — imprimé le ' + new Date().toLocaleDateString('fr-FR') + ' — ' + INFO.name + ' v' + INFO.version })])
    ]));
    var syn = el('div', { class: 'pr-section' }, [el('h2', { text: 'Synthèse du projet' })]);
    syntheseContent(true).forEach(function (n) { syn.appendChild(n); });
    root.appendChild(syn);
    project.rows.forEach(function (r, i) {
      var c = M.computeRowWithLayout(project, r, L);
      var warns = M.rowWarnings(project, r, L);
      var g = r.geom;
      var sec = el('div', { class: 'pr-section pr-row' }, [el('h2', { text: rowTitle(r, i) })]);
      var kv = function (pairs) { var d = el('div', { class: 'pr-kv' }); pairs.forEach(function (p) { d.appendChild(el('div', {}, [el('span', { text: p[0] }), el('b', { text: p[1] })])); }); return d; };
      sec.appendChild(kv([['Longueur', fmt(r.longueur) + ' m'], ['Largeur théorique', fmt(c.AC) + ' m'], ['Largeur max disponible', num(r.largeurMax) > 0 ? fmt(r.largeurMax) + ' m' : '—'],
        ['Contrôle', c.AE === 'NOK' ? 'NOK (trop large)' : 'OK'], ['Couche de surface', M.surfaceLabel(g) + ' ' + fmt(c.AM) + ' m'], ['Volume couche de surface', fmt(c.SV) + ' m³'],
        ['Volume tranchée câbles', fmt(c.AZ) + ' m³'], ['Volume tranchée conduites', fmt(c.BS) + ' m³'], ['Volume total', fmt(c.BT) + ' m³'],
        ['Volume sable', fmt(c.AW + c.BP) + ' m³'], ['Volume remblai s-fond.', fmt(c.AX + c.BQ) + ' m³'], ['Volume déblais excéd.', fmt(c.AY + c.BR) + ' m³']]));
      if (r.commentaire) sec.appendChild(el('p', { class: 'pr-com', text: 'Commentaires : ' + r.commentaire }));
      warns.forEach(function (w) { sec.appendChild(el('div', { class: 'alert ' + (w.level === 'error' ? 'err' : 'warn') }, [el('b', { text: w.level === 'error' ? 'Erreur — ' : 'À vérifier — ' }), w.msg])); });
      try { var cp = window.TICoupe.render(project, r, M); cp.className = 'pr-coupe'; sec.appendChild(cp); } catch (e) {}
      sec.appendChild(repTable(r, L));
      root.appendChild(sec);
    });
    setTimeout(function () { window.print(); }, 60);
  }

  function downloadBlob(blob, name) {
    // Dans l'app Android (WebView), les téléchargements blob: ne fonctionnent pas :
    // on transmet le fichier au pont natif qui l'enregistre dans « Téléchargements ».
    if (window.TIAndroid && typeof window.TIAndroid.saveBase64 === 'function') {
      var reader = new FileReader();
      reader.onload = function () {
        var b64 = String(reader.result).split(',')[1] || '';
        try { window.TIAndroid.saveBase64(name, blob.type || 'application/octet-stream', b64); }
        catch (e) { alertError('Enregistrement impossible', e); }
      };
      reader.onerror = function () { toast('Lecture du fichier impossible.'); };
      reader.readAsDataURL(blob);
      return;
    }
    var a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 100);
  }
  // Nom de fichier sûr, sans perdre les accents : \w est ASCII seul, il
  // supprimait « É », « ê », « î », « Ü »… (« Rue de l'Église » devenait
  // « Rue_de_lglise »). On ne retire ici que les caractères réellement
  // interdits par Windows / Android, en normalisant la ponctuation typographique.
  function sanitize(s) {
    var out = String(s == null ? '' : s);
    if (out.normalize) out = out.normalize('NFC');
    out = out
      .replace(/[‘’ʼ]/g, "'")      // apostrophes typographiques
      .replace(/[“”]/g, '')             // guillemets courbes
      .replace(/[‐-―−]/g, '-')     // tirets typographiques
      .replace(/[\\/:*?"<>|]/g, '')               // interdits Windows
      .replace(/[\u0000-\u001f\u007f]/g, '')      // caractères de contrôle
      .replace(/\s+/g, '_')
      .replace(/_{2,}/g, '_')
      .replace(/^[._\-]+|[._\-]+$/g, '')
      .slice(0, 80);
    return out || 'projet';
  }

  // -------------------------------------------------------------------- montage
  function mount() {
    loadStore();
    resetHistory();
    var pn = document.getElementById('pname');
    pn.addEventListener('input', function (e) { project.name = e.target.value; save(); });
    pn.addEventListener('blur', function () { if (!String(project.name || '').trim()) { project.name = 'Projet'; pn.value = project.name; save(); } });
    document.getElementById('btn-projects').addEventListener('click', openProjects);
    document.getElementById('btn-about').addEventListener('click', openAbout);
    document.getElementById('btn-undo').addEventListener('click', undo);
    document.getElementById('btn-redo').addEventListener('click', redo);
    document.getElementById('btn-export').addEventListener('click', exportJSON);
    document.getElementById('btn-excel').addEventListener('click', generateExcel);
    document.getElementById('btn-word').addEventListener('click', generateWord);
    document.getElementById('btn-print').addEventListener('click', printProject);
    var fi = document.getElementById('file-import');
    document.getElementById('btn-import').addEventListener('click', function () { fi.click(); });
    fi.addEventListener('change', function (e) { if (e.target.files[0]) importJSON(e.target.files[0]); e.target.value = ''; });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && $('#modal-root').firstChild) { closeModal(); return; }
      if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
      var k = e.key.toLowerCase();
      if (k === 'z' && !e.shiftKey) { e.preventDefault(); if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); undo(); }
      else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); redo(); }
    });
    window.addEventListener('beforeunload', function () { flushHistory(); persist(); });
    if (window.TIAndroid) document.documentElement.classList.add('android');
    render();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
})();
