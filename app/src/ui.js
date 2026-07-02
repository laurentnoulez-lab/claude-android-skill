/*
 * Interface utilisateur (navigateur uniquement).
 * Dépend de TIModel (model.js) et de la variable globale ExcelJS.
 */
(function () {
  'use strict';
  var M = window.TIModel;
  var STORAGE_KEY = 'gabarit-tranchees-impetrants:project';

  var project = loadProject();
  var ui = { expandedRowId: null, collapsed: { conc: false, defaults: true }, coupeVisible: {} };

  // --------------------------------------------------------------------- utils
  function $(sel, root) { return (root || document).querySelector(sel); }
  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      if (k === 'class') e.className = attrs[k];
      else if (k === 'html') e.innerHTML = attrs[k];
      else if (k === 'text') e.textContent = attrs[k];
      else if (k.slice(0, 2) === 'on') e.addEventListener(k.slice(2), attrs[k]);
      else if (attrs[k] != null) e.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c != null) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }
  function fmt(n, d) { if (n == null || isNaN(n)) return '–'; return Number(n).toLocaleString('fr-FR', { minimumFractionDigits: d == null ? 2 : d, maximumFractionDigits: d == null ? 2 : d }); }
  function fmtPct(n) { if (n == null || isNaN(n)) return '–'; return (n * 100).toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' %'; }
  function num(v) { return M.num(v, 0); }

  function loadProject() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      if (raw) return migrate(JSON.parse(raw));
    } catch (e) {}
    return M.defaultProject();
  }
  function migrate(p) {
    if (!p || !p.concessionnaires) return M.defaultProject();
    p.version = p.version || 1;
    p.defaults = p.defaults || Object.assign({ type: 'A', largeurMax: 0 }, M.defaultGeometry());
    p.rows = p.rows || [];
    p.rows.forEach(function (r) { r.widths = r.widths || {}; r.interstices = r.interstices || {}; r.geom = r.geom || M.defaultGeometry(); });
    return p;
  }
  function save() { try { localStorage.setItem(STORAGE_KEY, JSON.stringify(project)); } catch (e) {} }
  function toast(msg) {
    var t = $('#toast'); t.textContent = msg; t.classList.add('show');
    clearTimeout(toast._t); toast._t = setTimeout(function () { t.classList.remove('show'); }, 2200);
  }

  // ---------------------------------------------------------------- rendu global
  function render() {
    var root = $('#app-root');
    root.innerHTML = '';
    root.appendChild(renderConcessionnaires());
    root.appendChild(renderDefaults());
    root.appendChild(renderRows());
    root.appendChild(renderTotals());
    $('#pname').value = project.name || '';
    save();
  }

  function panel(id, title, count, collapsedKey, bodyNode, headExtra) {
    var p = el('section', { class: 'panel' + (ui.collapsed[collapsedKey] ? ' collapsed' : '') });
    var head = el('div', { class: 'head', onclick: function (e) {
      if (e.target.closest('button, input, select')) return;
      ui.collapsed[collapsedKey] = !ui.collapsed[collapsedKey];
      p.classList.toggle('collapsed');
    } }, [
      el('h2', { text: title }),
      count != null ? el('span', { class: 'count', text: count }) : null
    ]);
    if (headExtra) headExtra.forEach(function (n) { head.appendChild(n); });
    head.appendChild(el('span', { class: 'chev', text: '▾' }));
    p.appendChild(head);
    p.appendChild(el('div', { class: 'body' }, [bodyNode]));
    return p;
  }

  // ------------------------------------------------------------- concessionnaires
  function renderConcessionnaires() {
    var body = el('div', {}, [
      el('p', { class: 'hint', text: 'Définissez les impétrants et leurs sous-réseaux. Chaque impétrant relève des câbles ou des conduites — cela détermine sa place et ses formules dans le gabarit.' }),
      el('div', { class: 'cols2' }, [catColumn('cable', 'Câbles'), catColumn('conduite', 'Conduites')])
    ]);
    var n = project.concessionnaires.reduce(function (s, c) { return s + c.sousReseaux.length; }, 0);
    return panel('conc', 'Impétrants (concessionnaires)', project.concessionnaires.length + ' impétrants · ' + n + ' sous-réseaux', 'conc', body);
  }

  function catColumn(category, title) {
    var list = el('div', { class: 'list' });
    project.concessionnaires.filter(function (c) { return c.category === category; }).forEach(function (c) {
      list.appendChild(concCard(c));
    });
    list.appendChild(el('button', { class: 'tiny', onclick: function () {
      project.concessionnaires.push({ id: M.uid('conc'), name: 'Nouvel impétrant', category: category, sousReseaux: [{ id: M.uid('sr'), label: 'E' }] });
      render();
    }, text: '+ Ajouter un impétrant' }));
    return el('div', { class: 'cat-col ' + category }, [el('div', { class: 'ch', text: title }), list]);
  }

  function concCard(c) {
    var srList = el('div', { class: 'sr-list' });
    c.sousReseaux.forEach(function (sr) {
      srList.appendChild(el('span', { class: 'sr-chip' }, [
        el('input', { value: sr.label, title: 'Libellé du sous-réseau', onchange: function (e) { sr.label = e.target.value; save(); refreshRowsArea(); } }),
        el('button', { title: 'Supprimer', text: '×', onclick: function () {
          c.sousReseaux = c.sousReseaux.filter(function (x) { return x !== sr; }); render();
        } })
      ]));
    });
    srList.appendChild(el('button', { class: 'tiny add-sr', text: '+ sous-réseau', onclick: function () {
      c.sousReseaux.push({ id: M.uid('sr'), label: 'E' }); render();
    } }));

    var catSel = el('select', { onchange: function (e) { c.category = e.target.value; render(); } }, [
      el('option', { value: 'cable', text: 'Câbles', selected: c.category === 'cable' ? 'selected' : null }),
      el('option', { value: 'conduite', text: 'Conduites', selected: c.category === 'conduite' ? 'selected' : null })
    ]);
    catSel.value = c.category;
    return el('div', { class: 'conc-card' }, [
      el('div', { class: 'row1' }, [
        el('input', { value: c.name, onchange: function (e) { c.name = e.target.value; save(); refreshRowsArea(); } }),
        catSel,
        el('button', { class: 'tiny danger', title: 'Supprimer l\'impétrant', text: '🗑', onclick: function () {
          if (!confirm('Supprimer l\'impétrant « ' + c.name + ' » ?')) return;
          project.concessionnaires = project.concessionnaires.filter(function (x) { return x !== c; }); render();
        } })
      ]),
      srList
    ]);
  }

  // -------------------------------------------------------------- valeurs défaut
  var GEOM_FIELDS = [
    ['litPoseCable', 'Lit de pose min. câbles', 'm'],
    ['htMoyCable', 'Ht moyenne câbles', 'm'],
    ['recouvSableMinCable', 'Recouvrement sable min. câbles', 'm'],
    ['ligneAligne', 'Ligne recouvrement alignée', 'OUI/NON'],
    ['recouvNiveauFiniCable', 'Recouvr. min. / niveau fini (câbles)', 'm'],
    ['hauteurCoffre', 'Hauteur coffre (travaux SPI)', 'm'],
    ['litPoseConduite', 'Lit de pose min. conduites', 'm'],
    ['recouvSableMinConduite', 'Recouvrement sable min. conduites', 'm'],
    ['recouvNiveauFiniConduite', 'Recouvr. min. / niveau fini (conduites)', 'm']
  ];
  var REMBLAI_KEYS = ['remblaiModeCable', 'remblaiSousFondCable', 'remblaiModeConduite', 'remblaiSousFondConduite',
    'gainesCables', 'diamGaine', 'longueurGaines', 'htConduiteMode', 'htConduiteManuelle'];

  function geomField(obj, key, labelTxt, unit, onUpdate) {
    var input;
    if (key === 'ligneAligne') {
      input = el('select', { onchange: function (e) { obj[key] = e.target.value; onUpdate(); } }, [
        el('option', { value: 'OUI', text: 'OUI' }), el('option', { value: 'NON', text: 'NON' })
      ]);
      input.value = obj[key] || 'OUI';
    } else {
      input = el('input', { class: 'in', type: 'number', step: '0.01', value: obj[key] != null ? obj[key] : 0,
        oninput: function (e) { obj[key] = num(e.target.value); onUpdate(); } });
    }
    return el('div', { class: 'fld' }, [el('label', { html: labelTxt + ' <small>(' + unit + ')</small>' }), input]);
  }

  function renderDefaults() {
    var d = project.defaults;
    var grid = el('div', { class: 'grid-params' });
    grid.appendChild(el('div', { class: 'fld' }, [el('label', { html: 'Type de tranchée par défaut' }),
      el('input', { value: d.type || 'A', onchange: function (e) { d.type = e.target.value; save(); } })]));
    grid.appendChild(el('div', { class: 'fld' }, [el('label', { html: 'Largeur max disponible <small>(m)</small>' }),
      el('input', { class: 'in', type: 'number', step: '0.01', value: d.largeurMax || 0, oninput: function (e) { d.largeurMax = num(e.target.value); save(); } })]));
    GEOM_FIELDS.forEach(function (f) { grid.appendChild(geomField(d, f[0], f[1], f[2], save)); });
    remblaiControl(d, 'remblaiModeCable', 'remblaiSousFondCable', save).forEach(function (fld) { grid.appendChild(fld); });
    remblaiControl(d, 'remblaiModeConduite', 'remblaiSousFondConduite', save).forEach(function (fld) { grid.appendChild(fld); });
    gaineControl(d, save).forEach(function (fld) { grid.appendChild(fld); });
    htConduiteControl(d, save).forEach(function (fld) { grid.appendChild(fld); });

    var apply = el('button', { class: 'tiny', text: 'Appliquer ces paramètres à tous les tronçons existants', onclick: function () {
      if (!project.rows.length) { toast('Aucun tronçon.'); return; }
      if (!confirm('Écraser les paramètres géométriques de tous les tronçons avec ces valeurs ?')) return;
      project.rows.forEach(function (r) {
        GEOM_FIELDS.forEach(function (f) { r.geom[f[0]] = d[f[0]]; });
        REMBLAI_KEYS.forEach(function (k) { r.geom[k] = d[k]; });
        r.type = r.type || d.type; r.largeurMax = d.largeurMax;
      });
      render(); toast('Paramètres appliqués.');
    } });

    var body = el('div', {}, [
      el('p', { class: 'hint', text: 'Valeurs pré-remplies pour chaque nouveau tronçon. Chaque tronçon reste modifiable individuellement.' }),
      grid, el('div', { style: 'margin-top:12px' }, [apply])
    ]);
    return panel('defaults', 'Paramètres par défaut (géométrie)', null, 'defaults', body);
  }

  // -------------------------------------------------------------------- tronçons
  function renderRows() {
    var add = el('button', { class: 'primary', text: '+ Ajouter un tronçon', onclick: function () {
      var r = M.newRow(project); project.rows.push(r); ui.expandedRowId = r.id; render();
      var node = document.getElementById('row-' + r.id); if (node) node.scrollIntoView({ behavior: 'smooth', block: 'center' });
    } });
    var body = el('div', { id: 'rows-area' }, [rowsTable()]);
    return panel('rows', 'Tronçons de tranchée', project.rows.length + ' tronçon(s)', 'rowsOpen', body, [add]);
  }

  function refreshRowsArea() {
    var area = $('#rows-area'); if (!area) return;
    area.innerHTML = ''; area.appendChild(rowsTable());
    var tot = $('#totals-area'); if (tot) { tot.innerHTML = ''; tot.appendChild(totalsInner()); }
  }

  function rowsTable() {
    if (!project.rows.length) {
      return el('div', { class: 'empty', text: 'Aucun tronçon pour l\'instant. Cliquez sur « + Ajouter un tronçon ».' });
    }
    var L = M.buildLayout(project);
    var table = el('table', { class: 'rows' });
    table.appendChild(el('thead', {}, [el('tr', {}, [
      th('#'), th('De'), th('à'), th('Type'), th('Longueur (m)', 'num'),
      th('Larg. théo. (m)', 'num'), th('Vol. total (m³)', 'num'), th('Contrôle'), th('')
    ])]));
    var tb = el('tbody');
    project.rows.forEach(function (r, i) {
      var c = M.computeRowWithLayout(project, r, L);
      var editing = ui.expandedRowId === r.id;
      var tr = el('tr', { class: 'summary' + (editing ? ' editing' : ''), id: 'row-' + r.id }, [
        td(String(i + 1)), td(r.de || '–'), td(r.a || '–'), td(r.type || '–'),
        td(fmt(r.longueur), 'num'),
        tdId('theo-' + r.id, fmt(c.AC), 'num'),
        tdId('vol-' + r.id, fmt(c.BT), 'num'),
        el('td', {}, [badge(r.id, c.AE)]),
        el('td', {}, [el('div', { class: 'rowact' }, [
          el('button', { class: 'tiny', text: editing ? 'Fermer' : 'Éditer', onclick: function () { ui.expandedRowId = editing ? null : r.id; refreshRowsArea(); } }),
          el('button', { class: 'tiny', title: 'Dupliquer', text: '⧉', onclick: function () {
            var copy = JSON.parse(JSON.stringify(r)); copy.id = M.uid('row');
            project.rows.splice(i + 1, 0, copy); refreshRowsArea();
          } }),
          el('button', { class: 'tiny danger', title: 'Supprimer', text: '×', onclick: function () {
            project.rows.splice(i, 1); if (ui.expandedRowId === r.id) ui.expandedRowId = null; refreshRowsArea();
          } })
        ])])
      ]);
      tb.appendChild(tr);
      if (editing) {
        var edtr = el('tr', {}, [el('td', { colspan: 9, style: 'padding:0' }, [rowEditor(r, L)])]);
        tb.appendChild(edtr);
      }
    });
    table.appendChild(tb);
    return table;
  }

  function badge(rowId, ae) {
    return el('span', { id: 'badge-' + rowId, class: 'badge ' + (ae === 'NOK' ? 'nok' : 'ok'), text: ae === 'NOK' ? 'NOK (trop large)' : 'OK' });
  }
  function th(t, cls) { return el('th', { class: cls || '', text: t }); }
  function td(t, cls) { return el('td', { class: cls || '', text: t }); }
  function tdId(id, t, cls) { return el('td', { id: id, class: cls || '', text: t }); }

  function rowEditor(r, L) {
    var inner = el('div', { class: 'ed-inner' });
    var upd = function () { updateRowPreview(r); };

    // Identité
    inner.appendChild(el('h3', { text: 'Identité du tronçon' }));
    var idg = el('div', { class: 'grid-params' });
    idg.appendChild(fieldText('Point « De »', r.de, function (v) { r.de = v; save(); }));
    idg.appendChild(fieldText('Point « à »', r.a, function (v) { r.a = v; save(); }));
    idg.appendChild(fieldText('Type de tranchée', r.type, function (v) { r.type = v; save(); }));
    idg.appendChild(fieldText('Commentaires', r.commentaire, function (v) { r.commentaire = v; save(); }));
    idg.appendChild(fieldNum('Longueur', 'm', r.longueur, function (v) { r.longueur = v; upd(); }));
    idg.appendChild(fieldNum('Largeur max disponible', 'm', r.largeurMax, function (v) { r.largeurMax = v; upd(); }));
    inner.appendChild(idg);

    // Largeurs par canal (câbles puis conduites)
    ['cable', 'conduite'].forEach(function (cat) {
      var widthCols = L.cols.filter(function (col) { return (col.isChannel || col.isInterstice) && col.category === cat; });
      if (!widthCols.length) return;
      inner.appendChild(el('h3', { text: cat === 'cable' ? 'Largeurs — Câbles (m)' : 'Largeurs — Conduites (m)' }));
      var g = el('div', { class: 'width-grid' });
      widthCols.forEach(function (col) {
        if (col.isInterstice) {
          var lbl = 'Interstice ' + (col.h3 ? col.h3 + ' ' : '') + '· ' + (col.intBefore || '');
          g.appendChild(wfield(lbl.trim(), true, num(r.interstices[col.intKey]), function (v) { r.interstices[col.intKey] = v; upd(); }));
        } else {
          g.appendChild(wfield(col.h3.replace('\n', ' '), false, num(r.widths[col.srId]), function (v) { r.widths[col.srId] = v; upd(); }));
        }
      });
      inner.appendChild(g);
    });

    // Paramètres câbles
    inner.appendChild(el('h3', { text: 'Paramètres — Câbles' }));
    var gc = el('div', { class: 'grid-params' });
    [['litPoseCable', 'Lit de pose min.', 'm'], ['htMoyCable', 'Ht moyenne câbles', 'm'], ['recouvSableMinCable', 'Recouvrement sable min.', 'm'],
     ['ligneAligne', 'Ligne recouvrement alignée', 'OUI/NON'], ['recouvNiveauFiniCable', 'Recouvr. min. / niveau fini', 'm'],
     ['hauteurCoffre', 'Hauteur coffre (SPI)', 'm']]
      .forEach(function (f) { gc.appendChild(geomField(r.geom, f[0], f[1], f[2], upd)); });
    remblaiControl(r.geom, 'remblaiModeCable', 'remblaiSousFondCable', upd).forEach(function (fld) { gc.appendChild(fld); });
    gaineControl(r.geom, upd).forEach(function (fld) { gc.appendChild(fld); });
    inner.appendChild(gc);

    // Paramètres conduites
    inner.appendChild(el('h3', { text: 'Paramètres — Conduites' }));
    var gp = el('div', { class: 'grid-params' });
    [['litPoseConduite', 'Lit de pose min.', 'm'], ['recouvSableMinConduite', 'Recouvrement sable min.', 'm'],
     ['recouvNiveauFiniConduite', 'Recouvr. min. / niveau fini', 'm']]
      .forEach(function (f) { gp.appendChild(geomField(r.geom, f[0], f[1], f[2], upd)); });
    remblaiControl(r.geom, 'remblaiModeConduite', 'remblaiSousFondConduite', upd).forEach(function (fld) { gp.appendChild(fld); });
    htConduiteControl(r.geom, upd).forEach(function (fld) { gp.appendChild(fld); });
    inner.appendChild(gp);

    // Aperçu calculé
    var pv = el('div', { class: 'preview', id: 'preview-' + r.id });
    inner.appendChild(pv);
    fillPreview(pv, M.computeRowWithLayout(project, r, L));

    // Coupe de tranchée (à l'échelle)
    inner.appendChild(el('h3', { text: 'Coupe de tranchée (à l\'échelle)' }));
    var coupeBtn = el('button', { class: 'tiny', text: ui.coupeVisible[r.id] ? 'Masquer la coupe' : 'Afficher la coupe', onclick: function () {
      ui.coupeVisible[r.id] = !ui.coupeVisible[r.id];
      coupeBtn.textContent = ui.coupeVisible[r.id] ? 'Masquer la coupe' : 'Afficher la coupe';
      renderCoupeInto(r);
    } });
    inner.appendChild(coupeBtn);
    inner.appendChild(el('div', { class: 'coupe-box', id: 'coupe-' + r.id }));

    // Parts de volume par sous-réseau
    inner.appendChild(el('h3', { text: 'Parts de volume par sous-réseau' }));
    inner.appendChild(el('p', { class: 'hint', text: NOTE_INTERSTICE }));
    inner.appendChild(el('div', { id: 'rep-' + r.id }));

    setTimeout(function () { renderCoupeInto(r); fillRepartition(r, L); }, 0);
    return el('div', { class: 'editor' }, [inner]);
  }

  var NOTE_INTERSTICE = 'Répartition des interstices : les interstices ne sont attribués à aucun sous-réseau. La part d\'un sous-réseau est calculée sur la largeur occupée (part = largeur ÷ largeur occupée AR/BL), puis appliquée au volume TOTAL de la partie (AZ/BS), lequel inclut déjà les interstices (largeur totale AT/BN). Le volume des interstices est donc réparti au prorata de la largeur occupée : un sous-réseau plus large en absorbe une part plus grande.';

  function renderCoupeInto(r) {
    var box = document.getElementById('coupe-' + r.id);
    if (!box) return;
    box.innerHTML = '';
    if (!ui.coupeVisible[r.id]) return;
    try { box.appendChild(window.TICoupe.render(project, r, M)); }
    catch (e) { box.textContent = 'Coupe indisponible : ' + e.message; }
  }

  function fillRepartition(r, L) {
    var box = document.getElementById('rep-' + r.id);
    if (!box) return;
    var rep = M.computeRepartition(project, r, L);
    box.innerHTML = '';
    if (!rep.channels.length) { box.appendChild(el('div', { class: 'hint', text: 'Aucun sous-réseau.' })); return; }
    var t = el('table', { class: 'rows rep' });
    t.appendChild(el('thead', {}, [el('tr', {}, [
      th('Sous-réseau'), th('Catégorie'), th('Largeur (m)', 'num'), th('Part catégorie', 'num'),
      th('Part totale', 'num'), th('Vol. attribué tranchée câbles (m³)', 'num'),
      th('Vol. attribué tranchée conduites (m³)', 'num'), th('Vol. de tranchée totale attribué (m³)', 'num')
    ])]));
    var tb = el('tbody');
    rep.channels.forEach(function (ch) {
      // cellule « part totale » avec mini-barre proportionnelle
      var barCell = el('td', { class: 'num' }, [
        el('div', { class: 'minibar' + (ch.category === 'conduite' ? ' conduite' : '') }, [
          el('span', { text: fmtPct(ch.partTot) }),
          el('div', { class: 'track' }, [
            el('div', { class: 'fill', style: 'width:' + Math.min(100, Math.max(0, ch.partTot * 100)).toFixed(1) + '%' })
          ])
        ])
      ]);
      tb.appendChild(el('tr', {}, [
        td(ch.label), td(ch.category === 'cable' ? 'Câbles' : 'Conduites'),
        td(fmt(ch.width), 'num'), td(fmtPct(ch.partCat), 'num'), barCell,
        td(fmt(ch.volCable), 'num'), td(fmt(ch.volConduite), 'num'), td(fmt(ch.volTranchee), 'num')
      ]));
    });
    t.appendChild(tb);
    box.appendChild(el('div', { class: 'rep-wrap' }, [t]));
  }

  function fillPreview(pv, c) {
    pv.innerHTML = '';
    [['Largeur théorique', c.AC, 'm'], ['Largeur totale câbles', c.AT, 'm'], ['Largeur totale conduites', c.BN, 'm'],
     ['Volume tranchée câbles', c.AZ, 'm³'], ['Volume tranchée conduites', c.BS, 'm³'], ['Volume total', c.BT, 'm³'],
     ['Volume sable', c.AW + c.BP, 'm³'], ['Volume déblais excéd.', c.AY + c.BR, 'm³']]
      .forEach(function (x) {
        pv.appendChild(el('div', { class: 'pv' }, [el('b', { text: fmt(x[1]) }), el('span', { text: x[0] + ' (' + x[2] + ')' })]));
      });
  }

  function updateRowPreview(r) {
    var L = M.buildLayout(project);
    var c = M.computeRowWithLayout(project, r, L);
    var theo = document.getElementById('theo-' + r.id); if (theo) theo.textContent = fmt(c.AC);
    var vol = document.getElementById('vol-' + r.id); if (vol) vol.textContent = fmt(c.BT);
    var bg = document.getElementById('badge-' + r.id);
    if (bg) { bg.className = 'badge ' + (c.AE === 'NOK' ? 'nok' : 'ok'); bg.textContent = c.AE === 'NOK' ? 'NOK (trop large)' : 'OK'; }
    var pv = document.getElementById('preview-' + r.id); if (pv) fillPreview(pv, c);
    renderCoupeInto(r);
    fillRepartition(r, L);
    var tot = $('#totals-area'); if (tot) { tot.innerHTML = ''; tot.appendChild(totalsInner()); }
    save();
  }

  function fieldText(label, val, on) {
    return el('div', { class: 'fld' }, [el('label', { text: label }),
      el('input', { value: val || '', oninput: function (e) { on(e.target.value); } })]);
  }
  function fieldNum(label, unit, val, on) {
    return el('div', { class: 'fld' }, [el('label', { html: label + ' <small>(' + unit + ')</small>' }),
      el('input', { class: 'in', type: 'number', step: '0.01', value: val != null ? val : 0, oninput: function (e) { on(num(e.target.value)); } })]);
  }
  // Choix du remblai entre le haut du sable et le fond de coffre :
  // terres décaissées (réutilisation) / empierrement de sous-fondation / hauteur manuelle.
  function remblaiControl(geom, modeKey, valKey, on) {
    var numInput = el('input', { class: 'in', type: 'number', step: '0.01', value: geom[valKey] != null ? geom[valKey] : 0,
      oninput: function (e) { geom[valKey] = num(e.target.value); on(); } });
    var sync = function () { numInput.disabled = (geom[modeKey] || 'terres') !== 'manuel'; };
    var sel = el('select', { onchange: function (e) { geom[modeKey] = e.target.value; sync(); on(); } }, [
      el('option', { value: 'terres', text: 'Terres décaissées' }),
      el('option', { value: 'empierrement', text: 'Empierrement sous-fond.' }),
      el('option', { value: 'manuel', text: 'Manuel (hauteur)' })
    ]);
    sel.value = geom[modeKey] || 'terres';
    sync();
    return [
      el('div', { class: 'fld' }, [el('label', { html: 'Remblai (sable → fond de coffre)' }), sel]),
      el('div', { class: 'fld' }, [el('label', { html: 'Hauteur empierrement <small>(m, si manuel)</small>' }), numInput])
    ];
  }

  // Câbles sous gaines (fourreaux) : OUI/NON + Ø ; la longueur de gaines
  // devient automatique ((largeur câbles ÷ Ø) × longueur) quand OUI, et le
  // volume des gaines est déduit du volume de sable.
  function gaineControl(geom, on) {
    var diamInput = el('input', { class: 'in', type: 'number', step: '0.01', value: geom.diamGaine != null ? geom.diamGaine : 0.16,
      oninput: function (e) { geom.diamGaine = num(e.target.value); on(); } });
    var lgInput = el('input', { class: 'in', type: 'number', step: '0.1', value: geom.longueurGaines != null ? geom.longueurGaines : 0,
      oninput: function (e) { geom.longueurGaines = num(e.target.value); on(); } });
    var sync = function () {
      var oui = (geom.gainesCables || 'NON') === 'OUI';
      diamInput.disabled = !oui;
      lgInput.disabled = oui;
      lgInput.title = oui ? 'Calculée automatiquement : (largeur câbles ÷ Ø) × longueur' : '';
    };
    var sel = el('select', { onchange: function (e) { geom.gainesCables = e.target.value; sync(); on(); } }, [
      el('option', { value: 'NON', text: 'NON' }), el('option', { value: 'OUI', text: 'OUI' })
    ]);
    sel.value = geom.gainesCables || 'NON';
    sync();
    return [
      el('div', { class: 'fld' }, [el('label', { html: 'Câbles sous gaines (fourreaux)' }), sel]),
      el('div', { class: 'fld' }, [el('label', { html: 'Ø gaine <small>(m)</small>' }), diamInput]),
      el('div', { class: 'fld' }, [el('label', { html: 'Longueur gaines rigides <small>(m, auto si gaines)</small>' }), lgInput])
    ];
  }

  // Hauteur moyenne conduites (BB) : MAX automatique des diamètres, ou forcée.
  function htConduiteControl(geom, on) {
    var valInput = el('input', { class: 'in', type: 'number', step: '0.01', value: geom.htConduiteManuelle != null ? geom.htConduiteManuelle : 0,
      oninput: function (e) { geom.htConduiteManuelle = num(e.target.value); on(); } });
    var sync = function () { valInput.disabled = (geom.htConduiteMode || 'auto') !== 'manuel'; };
    var sel = el('select', { onchange: function (e) { geom.htConduiteMode = e.target.value; sync(); on(); } }, [
      el('option', { value: 'auto', text: 'Auto (MAX des Ø)' }),
      el('option', { value: 'manuel', text: 'Manuelle' })
    ]);
    sel.value = geom.htConduiteMode || 'auto';
    sync();
    return [
      el('div', { class: 'fld' }, [el('label', { html: 'Ht moyenne conduites' }), sel]),
      el('div', { class: 'fld' }, [el('label', { html: 'Ht conduites forcée <small>(m, si manuelle)</small>' }), valInput])
    ];
  }

  function wfield(label, interstice, val, on) {
    return el('div', { class: 'wfld' + (interstice ? ' interstice' : '') }, [el('label', { text: label }),
      el('input', { class: 'in', type: 'number', step: '0.01', value: val != null ? val : 0, oninput: function (e) { on(num(e.target.value)); } })]);
  }

  // ---------------------------------------------------------------------- totaux
  function totalsInner() {
    var t = M.projectTotals(project);
    var box = el('div', { class: 'totals' });
    [['Tronçons', project.rows.length, ''], ['Longueur totale', fmt(t.longueur, 0), 'm'],
     ['Volume sable', fmt(t.volSableCable + t.volSableConduite), 'm³'],
     ['Volume remblai sous-fond.', fmt(t.volRemblai), 'm³'],
     ['Volume déblais excéd.', fmt(t.volDeblais), 'm³'],
     ['Volume total tranchée', fmt(t.volTotal), 'm³'],
     ['Contrôles NOK', t.nok, '']]
      .forEach(function (x) { box.appendChild(el('div', { class: 't' }, [el('b', { text: x[1] + (x[2] ? ' ' + x[2] : '') }), el('span', { text: x[0] })])); });
    return box;
  }
  function renderTotals() { return el('div', { id: 'totals-area' }, [totalsInner()]); }

  // ----------------------------------------------------------- import / export
  function exportJSON() {
    project.name = $('#pname').value || 'projet';
    var blob = new Blob([JSON.stringify(project, null, 2)], { type: 'application/json' });
    downloadBlob(blob, sanitize(project.name) + '.tranchees.json');
    toast('Projet exporté (.json)');
  }
  function importJSON(file) {
    var reader = new FileReader();
    reader.onload = function () {
      try {
        var p = migrate(JSON.parse(reader.result));
        project = p; ui.expandedRowId = null; render(); toast('Projet importé.');
      } catch (e) { alert('Fichier invalide : ' + e.message); }
    };
    reader.readAsText(file);
  }
  function newProject() {
    if (!confirm('Démarrer un nouveau projet ? Les données non exportées seront perdues.')) return;
    project = M.defaultProject(); ui.expandedRowId = null; render(); toast('Nouveau projet.');
  }
  function generateExcel() {
    project.name = $('#pname').value || 'projet';
    try {
      var wb = M.buildWorkbook(project, window.ExcelJS);
      wb.xlsx.writeBuffer().then(function (buf) {
        downloadBlob(new Blob([buf], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), sanitize(project.name) + '.xlsx');
        toast('Classeur Excel généré.');
      });
    } catch (e) { alert('Erreur lors de la génération : ' + e.message); console.error(e); }
  }
  function generateWord() {
    project.name = $('#pname').value || 'projet';
    toast('Génération du document Word…');
    try {
      window.TIDocx.generate(project, M).then(function (blob) {
        downloadBlob(blob, sanitize(project.name) + '.docx');
        toast('Document Word généré.');
      }).catch(function (e) { alert('Erreur Word : ' + e.message); console.error(e); });
    } catch (e) { alert('Erreur Word : ' + e.message); console.error(e); }
  }
  function downloadBlob(blob, name) {
    // Dans l'app Android (WebView), les téléchargements blob: ne fonctionnent pas :
    // on transmet le fichier au pont natif qui l'enregistre dans « Téléchargements ».
    if (window.TIAndroid && typeof window.TIAndroid.saveBase64 === 'function') {
      var reader = new FileReader();
      reader.onload = function () {
        var b64 = String(reader.result).split(',')[1] || '';
        try { window.TIAndroid.saveBase64(name, blob.type || 'application/octet-stream', b64); }
        catch (e) { alert('Enregistrement impossible : ' + e.message); }
      };
      reader.onerror = function () { alert('Lecture du fichier impossible.'); };
      reader.readAsDataURL(blob);
      return;
    }
    var a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name;
    document.body.appendChild(a); a.click(); setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 100);
  }
  function sanitize(s) { return (s || 'projet').replace(/[^\w\-éèàùç ]+/g, '').trim().replace(/\s+/g, '_') || 'projet'; }

  // -------------------------------------------------------------------- montage
  function mount() {
    document.getElementById('pname').addEventListener('input', function (e) { project.name = e.target.value; save(); });
    document.getElementById('btn-new').addEventListener('click', newProject);
    document.getElementById('btn-export').addEventListener('click', exportJSON);
    document.getElementById('btn-excel').addEventListener('click', generateExcel);
    document.getElementById('btn-word').addEventListener('click', generateWord);
    var fi = document.getElementById('file-import');
    document.getElementById('btn-import').addEventListener('click', function () { fi.click(); });
    fi.addEventListener('change', function (e) { if (e.target.files[0]) importJSON(e.target.files[0]); e.target.value = ''; });
    ui.collapsed.rowsOpen = false;
    render();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
})();
