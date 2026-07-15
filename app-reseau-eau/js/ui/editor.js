/*
 * editor.js — Éditeur graphique SVG du réseau.
 *
 * - Placement de nœuds (alimentation, consommation, hydrant, jonction),
 *   liaison par tronçons, glisser-déposer, grille magnétique, zoom/pan.
 * - Le dessin est SCHÉMATIQUE : les longueurs sont saisies, pas mesurées.
 * - Après calcul : affichage sur le schéma du diamètre retenu, du débit,
 *   du sens d'écoulement (flèche), de la vitesse et des pressions aux
 *   nœuds, avec code couleur vert / orange / rouge.
 */
(function () {
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const PAS_GRILLE = 20;

  const st = () => RD.stateModule.state;
  const SM = () => RD.stateModule;

  let svg, vue = { x: 0, y: 0, l: 900, h: 560 };
  let glisser = null; // {type:'noeud'|'vue', ...}
  let premierNoeud = null; // pour le mode tronçon

  function initialiser() {
    svg = document.getElementById('svg-schema');
    majViewBox();

    svg.addEventListener('pointerdown', surPointerDown);
    svg.addEventListener('pointermove', surPointerMove);
    svg.addEventListener('pointerup', surPointerUp);
    svg.addEventListener('wheel', surMolette, { passive: false });

    // Barre d'outils
    document.querySelectorAll('#barre-outils [data-mode]').forEach((b) => {
      b.addEventListener('click', () => {
        document.querySelectorAll('#barre-outils [data-mode]').forEach((x) => x.classList.remove('actif'));
        b.classList.add('actif');
        premierNoeud = null;
        SM().definirMode(b.dataset.mode);
      });
    });
    document.getElementById('btn-zoom-plus').addEventListener('click', () => zoomer(0.8));
    document.getElementById('btn-zoom-moins').addEventListener('click', () => zoomer(1.25));
    document.getElementById('btn-zoom-ajuster').addEventListener('click', ajusterVue);
    document.getElementById('sel-cas-affiche').addEventListener('change', (e) => {
      st().casAffiche = e.target.value;
      rendre();
    });

    RD.stateModule.abonner((ev) => {
      if (ev === 'projet' || ev === 'resultats' || ev === 'selection') rendre();
      if (ev === 'selection') rendrePanneauProprietes();
      if (ev === 'projet') rendrePanneauProprietes();
    });
    rendre();
  }

  /* ── Coordonnées et vue ─────────────────────────────────────────────── */
  function pointSVG(ev) {
    const r = svg.getBoundingClientRect();
    return {
      x: vue.x + ((ev.clientX - r.left) / r.width) * vue.l,
      y: vue.y + ((ev.clientY - r.top) / r.height) * vue.h,
    };
  }
  function aimant(v) {
    return document.getElementById('chk-grille').checked ? Math.round(v / PAS_GRILLE) * PAS_GRILLE : v;
  }
  function majViewBox() { svg.setAttribute('viewBox', `${vue.x} ${vue.y} ${vue.l} ${vue.h}`); }
  function zoomer(facteur, centre) {
    const c = centre || { x: vue.x + vue.l / 2, y: vue.y + vue.h / 2 };
    vue.l *= facteur; vue.h *= facteur;
    vue.x = c.x - (c.x - vue.x) * facteur;
    vue.y = c.y - (c.y - vue.y) * facteur;
    majViewBox();
  }
  function ajusterVue() {
    const ns = st().projet.noeuds;
    if (!ns.length) { vue = { x: 0, y: 0, l: 900, h: 560 }; majViewBox(); return; }
    const xs = ns.map((n) => n.x), ys = ns.map((n) => n.y);
    const marge = 90;
    const x0 = Math.min(...xs) - marge, y0 = Math.min(...ys) - marge;
    const l = Math.max(...xs) - x0 + marge, h = Math.max(...ys) - y0 + marge;
    const ratio = 900 / 560;
    vue = l / h > ratio ? { x: x0, y: y0, l, h: l / ratio } : { x: x0, y: y0, l: h * ratio, h };
    majViewBox();
  }

  /* ── Interactions ───────────────────────────────────────────────────── */
  function surPointerDown(ev) {
    svg.setPointerCapture(ev.pointerId);
    const p = pointSVG(ev);
    const cible = ev.target.closest('[data-noeud], [data-troncon]');
    const mode = st().mode;

    if (mode.startsWith('noeud-') && !cible) {
      const type = mode.replace('noeud-', '');
      SM().ajouterNoeud({
        x: aimant(p.x), y: aimant(p.y),
        type: type === 'hydrant' ? 'consommation' : type,
        hydrant: type === 'hydrant',
      });
      return;
    }
    if (mode === 'troncon' && cible && cible.dataset.noeud) {
      if (!premierNoeud) {
        premierNoeud = cible.dataset.noeud;
        SM().selectionner({ type: 'noeud', id: premierNoeud });
      } else {
        const t = SM().ajouterTroncon(premierNoeud, cible.dataset.noeud);
        premierNoeud = null;
        if (t) SM().selectionner({ type: 'troncon', id: t.id });
      }
      return;
    }
    if (mode === 'supprimer' && cible) {
      SM().supprimerElement(cible.dataset.noeud
        ? { type: 'noeud', id: cible.dataset.noeud }
        : { type: 'troncon', id: cible.dataset.troncon });
      return;
    }
    if (mode === 'selection') {
      if (cible && cible.dataset.noeud) {
        SM().selectionner({ type: 'noeud', id: cible.dataset.noeud });
        glisser = { type: 'noeud', id: cible.dataset.noeud };
        return;
      }
      if (cible && cible.dataset.troncon) {
        SM().selectionner({ type: 'troncon', id: cible.dataset.troncon });
        return;
      }
      SM().selectionner(null);
      glisser = { type: 'vue', depart: p, vueDepart: { ...vue } };
    }
  }

  function surPointerMove(ev) {
    if (!glisser) return;
    const p = pointSVG(ev);
    if (glisser.type === 'noeud') {
      const n = st().projet.noeuds.find((x) => x.id === glisser.id);
      if (n) { n.x = aimant(p.x); n.y = aimant(p.y); rendre(); glisser.bouge = true; }
    } else if (glisser.type === 'vue') {
      vue.x = glisser.vueDepart.x - (p.x - glisser.depart.x);
      vue.y = glisser.vueDepart.y - (p.y - glisser.depart.y);
      majViewBox();
      // recalcul du point de départ dans le nouveau repère inutile :
      // pointSVG dépend de vue, on garde le delta par rapport à vueDepart
      const r = svg.getBoundingClientRect();
      glisser.depart = {
        x: glisser.vueDepart.x + ((ev.clientX - r.left) / r.width) * vue.l,
        y: glisser.vueDepart.y + ((ev.clientY - r.top) / r.height) * vue.h,
      };
    }
  }

  function surPointerUp() {
    if (glisser && glisser.type === 'noeud' && glisser.bouge) {
      SM().sauvegarderSession(); // positions modifiées (pas de recalcul nécessaire)
    }
    glisser = null;
  }

  function surMolette(ev) {
    ev.preventDefault();
    zoomer(ev.deltaY > 0 ? 1.15 : 0.87, pointSVG(ev));
  }

  /* ── Rendu ──────────────────────────────────────────────────────────── */
  function el(nom, attrs, texte) {
    const e = document.createElementNS(SVG_NS, nom);
    for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
    if (texte !== undefined) e.textContent = texte;
    return e;
  }

  function casResultats() {
    const r = st().resultats;
    if (!r) return null;
    if (st().casAffiche === 'incendie' && r.casIncendie) return r.casIncendie.resultat;
    return r.casPointe;
  }

  function couleursControles() {
    const map = { troncons: new Map(), noeuds: new Map() };
    for (const c of st().controles || []) {
      const niveau = c.niveau === 'nok' ? 'nok' : c.niveau === 'avertissement' ? 'warn' : null;
      if (!niveau) continue;
      for (const cible of c.cibles || []) {
        const dest = cible.startsWith('T') ? map.troncons : map.noeuds;
        if (dest.get(cible) !== 'nok') dest.set(cible, niveau);
      }
    }
    return map;
  }

  function rendre() {
    if (!svg) return;
    const p = st().projet;
    const sel = st().selection;
    const res = casResultats();
    const coul = couleursControles();
    svg.innerHTML = '';

    // Grille de fond
    const defs = el('defs');
    const motif = el('pattern', { id: 'grille', width: PAS_GRILLE, height: PAS_GRILLE, patternUnits: 'userSpaceOnUse' });
    motif.appendChild(el('circle', { cx: 1, cy: 1, r: 0.8, fill: '#d0d7de' }));
    defs.appendChild(motif);
    svg.appendChild(defs);
    svg.appendChild(el('rect', { x: vue.x - 2000, y: vue.y - 2000, width: 6000, height: 6000, fill: 'url(#grille)' }));

    const parId = new Map(p.noeuds.map((n) => [n.id, n]));

    // Tronçons
    for (const t of p.troncons) {
      const a = parId.get(t.de), b = parId.get(t.vers);
      if (!a || !b) continue;
      const g = el('g', { 'data-troncon': t.id });
      const classes = ['svg-troncon'];
      if (res) classes.push(coul.troncons.get(t.id) || 'ok');
      if (sel && sel.type === 'troncon' && sel.id === t.id) classes.push('selection');
      const ligne = el('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: classes.join(' ') });
      g.appendChild(el('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, stroke: 'transparent', 'stroke-width': 14 })); // zone cliquable
      g.appendChild(ligne);

      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      // Étiquette : nom + longueur (+ résultats après calcul)
      const lignes = [`${t.nom || t.id} — ${t.longueur ? t.longueur + ' m' : 'L à saisir !'}`];
      if (res && st().diametres) {
        const rt = res.troncons.get(t.id);
        const d = st().diametres.get(t.id);
        if (rt && d) {
          lignes.push(`${d.designation} (Di ${d.di})${d.force ? ' [forcé]' : ''}`);
          lignes.push(`${RD.units.fmt(Math.abs(rt.Q), 1)} m³/h — ${RD.units.fmt(rt.v, 2)} m/s`);
        }
        // Flèche de sens d'écoulement
        if (rt && Math.abs(rt.Q) > 1e-6) {
          const sens = rt.Q >= 0 ? 1 : -1;
          const dx = (b.x - a.x), dy = (b.y - a.y);
          const L = Math.hypot(dx, dy) || 1;
          const ux = (dx / L) * sens, uy = (dy / L) * sens;
          const fx = mx + ux * 14, fy = my + uy * 14;
          const p1 = `${fx},${fy}`;
          const p2 = `${fx - ux * 11 - uy * 5},${fy - uy * 11 + ux * 5}`;
          const p3 = `${fx - ux * 11 + uy * 5},${fy - uy * 11 - ux * 5}`;
          g.appendChild(el('polygon', { points: `${p1} ${p2} ${p3}`, class: 'svg-fleche' }));
        }
      }
      lignes.forEach((txt, i) => {
        g.appendChild(el('text', {
          x: mx + 8, y: my - 10 + i * 13,
          class: 'svg-etiquette' + (i > 0 ? ' resultat' : ''),
        }, txt));
      });
      svg.appendChild(g);
    }

    // Nœuds
    const pmin = p.hypotheses.pressionMinimale;
    for (const n of p.noeuds) {
      const estAlim = p.alimentation.noeudId === n.id;
      const g = el('g', { 'data-noeud': n.id });
      const classes = ['svg-noeud'];
      if (estAlim) classes.push('alimentation');
      if (n.hydrant) classes.push('hydrant');
      if (sel && sel.type === 'noeud' && sel.id === n.id) classes.push('selection');
      let pRes = null;
      if (res) {
        pRes = res.noeuds.get(n.id) ? res.noeuds.get(n.id).p : null;
        const seuil = pmin !== null && pmin !== undefined && pmin !== '' ? pmin : 0;
        if (pRes !== null && Number.isFinite(pRes)) classes.push(pRes >= seuil ? 'p-ok' : 'p-nok');
      }
      g.setAttribute('class', classes.join(' '));

      if (estAlim) {
        g.appendChild(el('rect', { x: n.x - 9, y: n.y - 9, width: 18, height: 18, rx: 3, class: 'forme', transform: `rotate(45 ${n.x} ${n.y})` }));
      } else if (n.type === 'jonction') {
        g.appendChild(el('rect', { x: n.x - 5, y: n.y - 5, width: 10, height: 10, class: 'forme' }));
      } else {
        g.appendChild(el('circle', { cx: n.x, cy: n.y, r: 8, class: 'forme' }));
      }
      if (n.hydrant) g.appendChild(el('text', { x: n.x + 9, y: n.y - 9, class: 'svg-marqueur-h' }, 'H'));

      const etiquettes = [n.nom || n.id];
      if (n.consommation) etiquettes.push(`${n.consommation} m³/j`);
      if (res && pRes !== null && Number.isFinite(pRes)) etiquettes.push(`${RD.units.fmt(pRes, 2)} bar`);
      etiquettes.forEach((txt, i) => {
        g.appendChild(el('text', { x: n.x + 12, y: n.y + 4 + i * 13, class: 'svg-etiquette' + (i === etiquettes.length - 1 && res ? ' resultat' : '') }, txt));
      });
      svg.appendChild(g);
    }

    // Info convergence Hardy Cross sur le schéma
    if (res && res.equilibrage) {
      const e = res.equilibrage;
      svg.appendChild(el('text', { x: vue.x + 10, y: vue.y + 20, class: 'svg-etiquette' },
        `Maillé — Hardy Cross : ${e.iterations} itération(s), ${e.converge ? 'convergé' : 'NON CONVERGÉ'} (correction max ${e.maxCorrection.toExponential(1)} m³/h)`));
    } else if (res) {
      svg.appendChild(el('text', { x: vue.x + 10, y: vue.y + 20, class: 'svg-etiquette' }, 'Réseau ramifié — calcul direct aval → amont'));
    }
  }

  /* ── Panneau de propriétés ──────────────────────────────────────────── */
  function rendrePanneauProprietes() {
    const panneau = document.getElementById('panneau-proprietes');
    const sel = st().selection;
    const p = st().projet;
    if (!sel) { panneau.innerHTML = ''; return; }
    const F = RD.forms;

    if (sel.type === 'noeud') {
      const n = p.noeuds.find((x) => x.id === sel.id);
      if (!n) { panneau.innerHTML = ''; return; }
      const estAlim = p.alimentation.noeudId === n.id;
      panneau.innerHTML = `<h3>Nœud ${n.id}</h3>
        <div class="champ"><label>Nom</label><input type="text" data-p="nom" value="${F.echap(n.nom || '')}"></div>
        <div class="champ"><label>Cote (m)</label><input type="number" step="0.1" data-p="cote" value="${n.cote ?? ''}"></div>
        <div class="champ"><label>Conso (m³/j)</label><input type="number" step="0.1" min="0" data-p="consommation" value="${n.consommation ?? ''}"></div>
        <div class="champ"><label>Hydrant</label><input type="checkbox" data-p="hydrant" ${n.hydrant ? 'checked' : ''}></div>
        <div class="champ"><label>Alimentation</label><input type="checkbox" data-p="alim" ${estAlim ? 'checked' : ''} title="Fait de ce nœud le point de piquage."></div>
        <button class="btn secondaire" data-p="supprimer">Supprimer le nœud</button>`;
      panneau.querySelectorAll('[data-p]').forEach((inp) => {
        if (inp.dataset.p === 'supprimer') {
          inp.addEventListener('click', () => SM().supprimerElement(sel));
          return;
        }
        inp.addEventListener('change', () => {
          if (inp.dataset.p === 'hydrant') n.hydrant = inp.checked;
          else if (inp.dataset.p === 'alim') p.alimentation.noeudId = inp.checked ? n.id : null;
          else if (inp.type === 'number') n[inp.dataset.p] = F.versNombre(inp.value);
          else n[inp.dataset.p] = inp.value;
          SM().marquerModifie();
        });
      });
    } else {
      const t = p.troncons.find((x) => x.id === sel.id);
      if (!t) { panneau.innerHTML = ''; return; }
      const optionsMat = Object.entries(RD.materials.MATERIAUX)
        .map(([id, m]) => `<option value="${id}" ${t.materiau === id ? 'selected' : ''}>${m.nom}</option>`).join('');
      const optionsDiam = ['<option value="">Auto (proposé)</option>']
        .concat(RD.materials.diametres(t.materiau).map((d) =>
          `<option value="${d.de}" ${t.diametreForce === d.de ? 'selected' : ''}>${d.designation} (Di ${d.di} mm)</option>`)).join('');
      panneau.innerHTML = `<h3>Tronçon ${t.id}</h3>
        <p class="aide">Longueur saisie manuellement — le schéma n'est pas à l'échelle.</p>
        <div class="champ"><label>Nom</label><input type="text" data-p="nom" value="${F.echap(t.nom || '')}"></div>
        <div class="champ"><label>Longueur (m)</label><input type="number" step="1" min="0" data-p="longueur" value="${t.longueur ?? ''}" placeholder="obligatoire"></div>
        <div class="champ"><label>Matériau</label><select data-p="materiau">${optionsMat}</select></div>
        <div class="champ"><label>Diamètre</label><select data-p="diametreForce">${optionsDiam}</select></div>
        <div class="champ"><label>ΣK singularités</label><input type="number" step="0.1" min="0" data-p="sommeK" value="${t.sommeK ?? ''}"></div>
        <button class="btn secondaire" data-p="supprimer">Supprimer le tronçon</button>`;
      panneau.querySelectorAll('[data-p]').forEach((inp) => {
        if (inp.dataset.p === 'supprimer') {
          inp.addEventListener('click', () => SM().supprimerElement(sel));
          return;
        }
        inp.addEventListener('change', () => {
          const cle = inp.dataset.p;
          if (cle === 'diametreForce') t.diametreForce = inp.value ? Number(inp.value) : null;
          else if (cle === 'materiau') { t.materiau = inp.value; t.diametreForce = null; }
          else if (inp.type === 'number') t[cle] = F.versNombre(inp.value);
          else t[cle] = inp.value;
          SM().marquerModifie();
          rendrePanneauProprietes();
        });
      });
    }
  }

  const E = { initialiser, ajusterVue, rendre };
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.editor = E;
})();
