/*
 * Coupe verticale à l'échelle d'un tronçon de tranchée (SVG, navigateur).
 * window.TICoupe.render(project, row, M) -> élément <div> contenant le SVG.
 * window.TICoupe.toPng(svg, scale)       -> Promise<{ blob, width, height }>.
 *
 * Modèle géométrique (en mètres), dérivé des mêmes valeurs que l'Excel :
 *  - Bandeau supérieur : couche de surface d'épaisseur AM sur toute la largeur —
 *    coffre (gris) ou terre végétale (brun sombre, pelouse dessinée en surface).
 *  - Zone câbles (largeur AT) : remblai terres (AQ), remblai sous-fond (AP),
 *    recouvrement sable (AK), câbles (rectangles, hauteur AH), lit de pose (AG).
 *  - Zone conduites (largeur BN) : remblai terres (BK), remblai sous-fond (BJ),
 *    recouvrement sable (BE), conduites (cercles Ø = largeur), lit de pose (BA).
 *  - Les interstices sont les espaces de sable entre/aux bords des canaux.
 *
 * Lisibilité : libellés des canaux en cartouches au-dessus de la coupe, espacés
 * pour éviter tout chevauchement et reliés à leur forme par une ligne de rappel ;
 * cotes de chaque couche réparties verticalement (anti-collision) de part et
 * d'autre de la fouille ; légende sous le dessin. Le SVG porte un viewBox : il
 * s'adapte à la largeur disponible sans perdre son échelle interne.
 */
(function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';
  var COL = {
    coffre: '#9aa5b1', terreVeg: '#3a2a20', terreVegGrain: '#5a4334', herbe: '#3f9b3a', herbeClair: '#6cc24a',
    terres: '#a9744f', sousfond: '#d8c19a', sable: '#f3e6a8',
    conduiteFill: '#cdeee2', gaineFill: '#f2ede2', line: '#33414f', dim: '#62707e', leader: '#9aa7b4'
  };
  var PALETTE = ['#2f6fed', '#e0533d', '#7b3fe4', '#0e9f6e', '#d9890b', '#c026d3', '#0891b2', '#65a30d', '#db2777', '#475569'];

  function E(tag, attrs, children) {
    var e = document.createElementNS(NS, tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    (children || []).forEach(function (c) { if (c != null) e.appendChild(c); });
    return e;
  }
  function txt(x, y, s, opts) {
    var a = Object.assign({ x: x, y: y, fill: COL.dim, 'font-size': 10, 'font-family': 'Segoe UI, Roboto, Helvetica, Arial, sans-serif' }, opts || {});
    var t = E('text', a); t.textContent = s; return t;
  }
  function fnum(n) { return (Math.round(n * 1000) / 1000).toLocaleString('fr-FR'); }
  function colorForConc(project, concId) {
    var idx = project.concessionnaires.findIndex(function (c) { return c.id === concId; });
    return PALETTE[(idx < 0 ? 0 : idx) % PALETTE.length];
  }
  // Répartit des positions le long d'un axe sans descendre sous minGap d'écart.
  function spread(positions, minGap) {
    var idx = positions.map(function (p, i) { return i; }).sort(function (a, b) { return positions[a] - positions[b]; });
    var out = positions.slice();
    for (var k = 1; k < idx.length; k++) {
      var prev = out[idx[k - 1]], cur = out[idx[k]];
      if (cur < prev + minGap) out[idx[k]] = prev + minGap;
    }
    return out;
  }
  // Générateur pseudo-aléatoire déterministe : la pelouse et le grain de la
  // terre végétale ne « dansent » pas d'un rafraîchissement à l'autre.
  function rng(seed) {
    var s = seed >>> 0 || 1;
    return function () { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
  }

  function render(project, row, M) {
    var L = M.buildLayout(project);
    var c = M.computeRowWithLayout(project, row, L);
    var g = row.geom || {};
    var n = M.num;

    var AM = n(g.hauteurCoffre, 0), APv = c.AP, BJv = c.BJ; // AP/BJ effectifs (selon mode de remblai)
    var AG = n(g.litPoseCable, 0), AH = n(g.htMoyCable, 0), BA = n(g.litPoseConduite, 0);
    // Câbles sous gaines : le Ø de la gaine prime sur la hauteur des câbles,
    // et les gaines sont dessinées en cercles (comme les conduites).
    var gaines = (g.gainesCables === 'OUI') && n(g.diamGaine, 0) > 0;
    var DG = n(g.diamGaine, 0.16);
    if (gaines) AH = DG;
    var AT = c.AT, BN = c.BN, AC = c.AC;
    var depthCable = c.AN, depthCond = c.BH;
    var totalDepth = AM + Math.max(depthCable, depthCond, 0);
    var surfTV = M.surfaceTypeOf(g) === 'terreVegetale';
    var surfName = surfTV ? 'Terre végétale' : 'Coffre';

    var wrap = document.createElement('div');
    if (!(AC > 0) || !(totalDepth > 0)) {
      wrap.innerHTML = '<p class="hint coupe-empty">Renseignez des largeurs et des hauteurs pour afficher la coupe.</p>';
      return wrap;
    }

    // Canaux et interstices non nuls (pour les cartouches et les cotes)
    var channels = [], interstices = [];
    var cursorC = 0;
    L.cols.filter(function (col) { return col.category === 'cable' && (col.isInterstice || col.isChannel); }).forEach(function (col) {
      if (col.isInterstice) {
        var iw = n(row.interstices[col.intKey], 0);
        if (iw > 1e-9) interstices.push({ x0: cursorC, w: iw, center: cursorC + iw / 2 });
        cursorC += iw; return;
      }
      var w = n(row.widths[col.srId], 0);
      if (gaines) w = M.effGaineWidth(w, DG); // largeur effective = nb gaines × Ø
      if (w > 0) channels.push({ col: col, cat: 'cable', x0: cursorC, w: w, center: cursorC + w / 2 });
      cursorC += w;
    });
    cursorC = AT;
    L.cols.filter(function (col) { return col.category === 'conduite' && (col.isInterstice || col.isChannel); }).forEach(function (col) {
      if (col.isInterstice) {
        var iw2 = n(row.interstices[col.intKey], 0);
        if (iw2 > 1e-9) interstices.push({ x0: cursorC, w: iw2, center: cursorC + iw2 / 2 });
        cursorC += iw2; return;
      }
      var d = n(row.widths[col.srId], 0);
      if (d > 0) channels.push({ col: col, cat: 'conduite', x0: cursorC, w: d, center: cursorC + d / 2 });
      cursorC += d;
    });

    // Géométrie de dessin (échelle uniforme m -> px)
    var trenchW = 470, trenchH = 300;
    var scale = Math.min(trenchW / AC, trenchH / totalDepth);
    var W = AC * scale, H = totalDepth * scale;
    var padTop = 138, padLeft = 152, padRight = 150;

    // Légende : sous la coupe, sur des lignes qui se replient à la largeur du dessin
    var legendMat = [[surfName, surfTV ? COL.terreVeg : COL.coffre], ['Remblai terres', COL.terres],
      ['Remblai sous-fond.', COL.sousfond], ['Sable', COL.sable], ['Conduite', COL.conduiteFill]];
    if (gaines) legendMat.push(['Gaine (fourreau)', COL.gaineFill]);
    var legendConc = project.concessionnaires.filter(function (cc) {
      return channels.some(function (ch) { return ch.col.concId === cc.id; });
    }).map(function (cc) { return [cc.name, colorForConc(project, cc.id)]; });
    var legendX = 24, legendW = padLeft + W + padRight - 2 * legendX;
    function flow(items) {
      var x = 0, line = 0, out = [];
      items.forEach(function (it) {
        var w = 20 + String(it[0]).length * 5.6 + 14;
        if (x > 0 && x + w > legendW) { x = 0; line += 1; }
        out.push({ x: x, line: line, label: it[0], color: it[1] });
        x += w;
      });
      return { items: out, lines: items.length ? line + 1 : 0 };
    }
    var flowMat = flow(legendMat), flowConc = flow(legendConc);
    var legendH = (flowMat.lines + flowConc.lines) * 16 + (flowConc.lines ? 6 : 0);
    var padBottom = 70 + legendH;

    var x0 = padLeft, y0 = padTop;
    var X = function (m) { return x0 + m * scale; };
    var Y = function (m) { return y0 + m * scale; };
    var svgW = Math.round(x0 + W + padRight), svgH = Math.round(y0 + H + padBottom);
    var svg = E('svg', { xmlns: NS, width: svgW, height: svgH, viewBox: '0 0 ' + svgW + ' ' + svgH, class: 'coupe-svg',
      role: 'img', 'aria-label': 'Coupe de tranchée à l\'échelle' });
    svg.appendChild(E('rect', { x: 0, y: 0, width: svgW, height: svgH, fill: '#fbfcfe' }));

    // ---- Couches (rectangles) ----
    function layerRect(xm, wm, dTop, h, color) {
      if (h <= 0) return;
      svg.appendChild(E('rect', { x: X(xm), y: Y(dTop), width: wm * scale, height: h * scale, fill: color, stroke: '#00000016' }));
    }
    // Couche de surface (toute la largeur) : coffre ou terre végétale
    layerRect(0, AC, 0, AM, surfTV ? COL.terreVeg : COL.coffre);
    if (AM > 0) {
      if (surfTV) {
        // grain de la terre : petites mottes plus claires, réparties de façon stable
        var rnd = rng(Math.round(AC * 1000) * 31 + Math.round(AM * 1000));
        var nbGrain = Math.min(160, Math.round((W * AM * scale) / 90));
        for (var gi = 0; gi < nbGrain; gi++) {
          svg.appendChild(E('circle', { cx: X(0) + 2 + rnd() * (W - 4), cy: Y(0) + 2 + rnd() * Math.max(0, AM * scale - 4),
            r: 0.6 + rnd() * 0.9, fill: COL.terreVegGrain }));
        }
      }
      if (AM * scale >= 11) {
        svg.appendChild(txt(X(AC / 2), Y(AM / 2) + 3, surfName, { fill: '#fff', 'text-anchor': 'middle', 'font-size': 9 }));
      }
    }
    // Zone câbles
    if (AT > 0) {
      var d = AM;
      layerRect(0, AT, d, c.AQ, COL.terres); d += Math.max(0, c.AQ);
      layerRect(0, AT, d, APv, COL.sousfond); d += Math.max(0, APv);
      layerRect(0, AT, d, c.AO, COL.sable); // recouvrement + câbles + lit (sable)
    }
    // Zone conduites
    if (BN > 0) {
      var e = AM;
      layerRect(AT, BN, e, c.BK, COL.terres); e += Math.max(0, c.BK);
      layerRect(AT, BN, e, BJv, COL.sousfond); e += Math.max(0, BJv);
      layerRect(AT, BN, e, c.BI, COL.sable);
    }

    // ---- Câbles : rectangles, ou cercles Ø gaine si posés sous fourreaux ----
    var cableTop = AM + c.AQ + APv + c.AK;
    channels.forEach(function (ch) {
      if (ch.cat !== 'cable' || AH <= 0) { if (ch.cat === 'cable') ch.topY = Y(cableTop); return; }
      if (gaines) {
        // une rangée de gaines côte à côte : nb = largeur du canal ÷ Ø
        var nb = Math.max(1, Math.round(ch.w / DG));
        for (var i = 0; i < nb; i++) {
          svg.appendChild(E('circle', {
            cx: X(ch.x0 + DG / 2 + i * DG), cy: Y(cableTop + DG / 2), r: (DG / 2) * scale,
            fill: COL.gaineFill, stroke: colorForConc(project, ch.col.concId), 'stroke-width': 1.4
          }, [E('title', {}, [document.createTextNode(ch.col.h3.replace('\n', ' ') + ' — gaine Ø ' + fnum(DG) + ' m')])]));
        }
      } else {
        svg.appendChild(E('rect', { x: X(ch.x0), y: Y(cableTop), width: ch.w * scale, height: AH * scale,
          fill: colorForConc(project, ch.col.concId), stroke: COL.line, 'stroke-width': 0.6, rx: 1 }));
      }
      ch.topY = Y(cableTop);
    });
    // ---- Conduites (cercles) ----
    var condLitTop = AM + c.BK + BJv + c.BI - BA; // haut du lit de pose conduites
    channels.forEach(function (ch) {
      if (ch.cat !== 'conduite') return;
      var r = ch.w / 2, cy = condLitTop - r;
      svg.appendChild(E('circle', { cx: X(ch.center), cy: Y(cy), r: r * scale,
        fill: COL.conduiteFill, stroke: colorForConc(project, ch.col.concId), 'stroke-width': 1.6 }));
      ch.topY = Y(cy - r);
    });

    // ---- Séparation câbles | conduites ----
    if (AT > 0 && BN > 0) {
      svg.appendChild(E('line', { x1: X(AT), y1: Y(AM), x2: X(AT), y2: Y(totalDepth), stroke: COL.line, 'stroke-dasharray': '5 3', 'stroke-width': 1, opacity: 0.55 }));
    }

    // ---- Lignes surface / bas de la couche de surface ----
    svg.appendChild(E('line', { x1: X(0) - 8, y1: Y(0), x2: X(AC) + 8, y2: Y(0), stroke: surfTV ? '#2c6e2a' : COL.line, 'stroke-width': 1.4 }));
    svg.appendChild(E('line', { x1: X(0), y1: Y(AM), x2: X(AC), y2: Y(AM), stroke: COL.line, 'stroke-dasharray': '4 3', 'stroke-width': 0.8 }));
    // Pelouse : touffes de brins d'herbe le long du niveau fini
    if (surfTV && AM > 0) {
      var rg = rng(Math.round(AC * 977) + 7);
      var gx = X(0) - 7, gEnd = X(AC) + 7, yS = Y(0), dpath = '', dpath2 = '';
      while (gx < gEnd) {
        var h1 = 5 + rg() * 5, lean = (rg() - 0.5) * 5;
        var seg = 'M' + gx.toFixed(1) + ',' + yS + ' q' + (lean / 2).toFixed(1) + ',' + (-h1 / 2).toFixed(1) + ' ' + lean.toFixed(1) + ',' + (-h1).toFixed(1) + ' ';
        if (rg() < 0.5) dpath += seg; else dpath2 += seg;
        gx += 2.2 + rg() * 2.6;
      }
      svg.appendChild(E('path', { d: dpath, stroke: COL.herbe, 'stroke-width': 1.1, fill: 'none', 'stroke-linecap': 'round' }));
      svg.appendChild(E('path', { d: dpath2, stroke: COL.herbeClair, 'stroke-width': 1, fill: 'none', 'stroke-linecap': 'round' }));
    }
    svg.appendChild(txt(X(AC) + 12, Y(0) - 3, 'Niveau fini', { 'font-size': 9, fill: COL.dim }));

    // ---- Cartouches de libellés des canaux (au-dessus, anti-chevauchement) ----
    var labelBaseY = y0 - 16;            // bas des cartouches (au-dessus de la pelouse)
    var minGap = 15;
    var sorted = channels.slice().sort(function (a, b) { return a.center - b.center; });
    var targets = sorted.map(function (ch) { return X(ch.center); });
    var lo = x0 - 24, hi = x0 + W + 24;
    var placed = spread(targets, minGap);
    if (placed.length) { // recentre si débordement
      var shift = 0, last = placed[placed.length - 1];
      if (last > hi) shift = hi - last;
      placed = placed.map(function (p) { return Math.max(lo, p + shift); });
    }
    sorted.forEach(function (ch, i) {
      var lx = placed[i];
      var color = colorForConc(project, ch.col.concId);
      // ligne de rappel : du cartouche vers le sommet de la forme
      svg.appendChild(E('path', { d: 'M' + lx + ',' + (labelBaseY + 2) + ' L' + lx + ',' + (labelBaseY + 8) + ' L' + X(ch.center) + ',' + (ch.topY - 2), stroke: COL.leader, fill: 'none', 'stroke-width': 0.8 }));
      svg.appendChild(E('rect', { x: lx - 3, y: labelBaseY - 1, width: 6, height: 6, fill: color, rx: 1 }));
      // texte vertical (lecture de bas en haut)
      var label = ch.col.h3.replace('\n', ' ') + ' · ' + (ch.cat === 'conduite' ? 'Ø' : '') + fnum(ch.w) + ' m';
      var t = txt(lx, labelBaseY - 6, label, { 'font-size': 8, fill: '#2b3642', 'text-anchor': 'start' });
      t.setAttribute('transform', 'rotate(-90 ' + lx + ' ' + (labelBaseY - 6) + ')');
      svg.appendChild(t);
    });

    // ---- Cotes par couche (gauche = câbles, droite = conduites) ----
    function drawCotes(xTick, dir, startDepth, layers) {
      // layers : [{name, h}] de haut en bas ; dir -1 (gauche) ou +1 (droite)
      var present = layers.filter(function (l) { return l.h > 1e-6; });
      if (!present.length) return;
      var d = startDepth;
      var bounds = [startDepth];
      present.forEach(function (l) { d += l.h; bounds.push(d); });
      svg.appendChild(E('line', { x1: xTick, y1: Y(startDepth), x2: xTick, y2: Y(d), stroke: COL.dim, 'stroke-width': 0.8 }));
      bounds.forEach(function (b) { svg.appendChild(E('line', { x1: xTick - 3, y1: Y(b), x2: xTick + 3, y2: Y(b), stroke: COL.dim, 'stroke-width': 0.8 })); });
      var mids = present.map(function (l, i) { return (Y(bounds[i]) + Y(bounds[i + 1])) / 2; });
      var placedY = spread(mids, 13);
      var xText = xTick + dir * 7;
      present.forEach(function (l, i) {
        var ly = placedY[i];
        svg.appendChild(E('line', { x1: xTick + dir * 3, y1: mids[i], x2: xText, y2: ly, stroke: COL.leader, 'stroke-width': 0.6 }));
        svg.appendChild(txt(xText + dir * 2, ly + 3, l.name + ' ' + fnum(l.h) + ' m', { 'font-size': 9, 'text-anchor': dir < 0 ? 'end' : 'start', fill: '#3a4654' }));
      });
    }
    var cablesLayers = [
      { name: 'Remblai terres', h: c.AQ }, { name: 'Remblai s-fond.', h: APv },
      { name: 'Recouvr. sable', h: c.AK }, { name: gaines ? 'Gaines Ø' : 'Câbles', h: AH }, { name: 'Lit de pose', h: AG }
    ];
    var condLayers = [
      { name: 'Remblai terres', h: c.BK }, { name: 'Remblai s-fond.', h: BJv }, { name: 'Recouvr. sable', h: c.BE },
      { name: 'Conduites', h: c.BB }, { name: 'Lit de pose', h: BA }
    ];
    var surfLayer = { name: surfName, h: AM };
    if (AT > 0) {
      drawCotes(X(0) - 12, -1, 0, [surfLayer].concat(cablesLayers));
      if (BN > 0) drawCotes(X(AC) + 12, 1, AM, condLayers);
    } else {
      // pas de câbles : les cotes des conduites passent à gauche, avec la couche de surface
      drawCotes(X(0) - 12, -1, 0, [surfLayer].concat(condLayers));
    }

    // ---- Cote de profondeur totale (extrême gauche) ----
    dimV(svg, Y(0), Y(totalDepth), 24, fnum(totalDepth) + ' m');

    // ---- Cotes des interstices non nuls (sous la fouille) ----
    var yIB = y0 + H;
    if (interstices.length) {
      var centersI = interstices.map(function (it) { return X(it.center); });
      var placedI = spread(centersI, 26);
      var hiI = x0 + W + 90;
      var lastI = placedI[placedI.length - 1];
      if (lastI > hiI) { var shI = hiI - lastI; placedI = placedI.map(function (p) { return Math.max(x0 - 6, p + shI); }); }
      var yILabel = yIB + 16;
      svg.appendChild(txt(42, yILabel, 'interst. (m)', { 'font-size': 8, 'text-anchor': 'start', fill: '#8a6d1a' }));
      interstices.forEach(function (it, i) {
        var cx = X(it.center), lx = placedI[i];
        svg.appendChild(E('line', { x1: X(it.x0), y1: yIB, x2: X(it.x0), y2: yIB + 4, stroke: COL.dim, 'stroke-width': 0.6 }));
        svg.appendChild(E('line', { x1: X(it.x0 + it.w), y1: yIB, x2: X(it.x0 + it.w), y2: yIB + 4, stroke: COL.dim, 'stroke-width': 0.6 }));
        svg.appendChild(E('line', { x1: X(it.x0), y1: yIB + 4, x2: X(it.x0 + it.w), y2: yIB + 4, stroke: '#c9a24a', 'stroke-width': 1 }));
        svg.appendChild(E('line', { x1: cx, y1: yIB + 4, x2: lx, y2: yILabel - 7, stroke: COL.leader, 'stroke-width': 0.5 }));
        svg.appendChild(txt(lx, yILabel, fnum(it.w), { 'text-anchor': 'middle', 'font-size': 8, fill: '#8a6d1a' }));
      });
    }

    // ---- Cotes de largeur (bas) ----
    var yDim = y0 + H + 34;
    dimH(svg, X(0), X(AC), yDim, fnum(AC) + ' m');
    if (AT > 0 && BN > 0) {
      svg.appendChild(E('line', { x1: X(AT), y1: Y(totalDepth), x2: X(AT), y2: yDim - 12, stroke: COL.leader, 'stroke-dasharray': '2 2', 'stroke-width': 0.8 }));
      svg.appendChild(txt(X(AT / 2), yDim + 15, 'câbles ' + fnum(AT) + ' m', { 'text-anchor': 'middle', 'font-size': 9, fill: '#1d4ed8' }));
      svg.appendChild(txt(X(AT + BN / 2), yDim + 15, 'conduites ' + fnum(BN) + ' m', { 'text-anchor': 'middle', 'font-size': 9, fill: '#047857' }));
    }

    // ---- Légende (matières puis impétrants), sous les cotes ----
    var ly0 = yDim + 40;
    flowMat.items.forEach(function (it) { legendItem(svg, legendX + it.x, ly0 + it.line * 16, it.color, it.label); });
    var ly1 = ly0 + flowMat.lines * 16 + 6;
    flowConc.items.forEach(function (it) { legendItem(svg, legendX + it.x, ly1 + it.line * 16, it.color, it.label); });

    wrap.appendChild(svg);
    return wrap;
  }

  function legendItem(svg, x, y, color, label) {
    svg.appendChild(E('rect', { x: x, y: y - 8, width: 12, height: 10, fill: color, stroke: '#00000033', rx: 2 }));
    svg.appendChild(txt(x + 17, y, label, { 'font-size': 9, fill: '#33414f' }));
  }
  function dimH(svg, x1, x2, y, label) {
    svg.appendChild(E('line', { x1: x1, y1: y, x2: x2, y2: y, stroke: '#62707e' }));
    svg.appendChild(E('line', { x1: x1, y1: y - 4, x2: x1, y2: y + 4, stroke: '#62707e' }));
    svg.appendChild(E('line', { x1: x2, y1: y - 4, x2: x2, y2: y + 4, stroke: '#62707e' }));
    svg.appendChild(txt((x1 + x2) / 2, y - 5, label, { 'text-anchor': 'middle', fill: '#33414f', 'font-weight': 'bold' }));
  }
  function dimV(svg, y1, y2, x, label) {
    svg.appendChild(E('line', { x1: x, y1: y1, x2: x, y2: y2, stroke: '#62707e' }));
    svg.appendChild(E('line', { x1: x - 4, y1: y1, x2: x + 4, y2: y1, stroke: '#62707e' }));
    svg.appendChild(E('line', { x1: x - 4, y1: y2, x2: x + 4, y2: y2, stroke: '#62707e' }));
    var t = txt(x - 6, (y1 + y2) / 2, label, { 'text-anchor': 'middle', fill: '#33414f', 'font-weight': 'bold' });
    t.setAttribute('transform', 'rotate(-90 ' + (x - 6) + ' ' + ((y1 + y2) / 2) + ')');
    svg.appendChild(t);
  }

  // Convertit un SVG de coupe en image PNG (pour l'export image et la notice Word).
  function toPng(svg, scale) {
    scale = scale || 2;
    return new Promise(function (resolve, reject) {
      try {
        var w = parseFloat(svg.getAttribute('width')), h = parseFloat(svg.getAttribute('height'));
        var xml = new XMLSerializer().serializeToString(svg);
        var img = new Image();
        img.onload = function () {
          try {
            var cv = document.createElement('canvas');
            cv.width = Math.round(w * scale); cv.height = Math.round(h * scale);
            var ctx = cv.getContext('2d');
            ctx.fillStyle = '#ffffff'; ctx.fillRect(0, 0, cv.width, cv.height);
            ctx.drawImage(img, 0, 0, cv.width, cv.height);
            cv.toBlob(function (blob) {
              if (!blob) { reject(new Error('conversion PNG impossible')); return; }
              resolve({ blob: blob, width: w, height: h });
            }, 'image/png');
          } catch (e) { reject(e); }
        };
        img.onerror = function () { reject(new Error('rendu de la coupe impossible')); };
        img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(xml);
      } catch (e) { reject(e); }
    });
  }

  window.TICoupe = { render: render, toPng: toPng };
})();
