/*
 * Coupe verticale à l'échelle d'un tronçon de tranchée (SVG, navigateur).
 * window.TICoupe.render(project, row, M) -> élément SVG.
 *
 * Modèle géométrique (en mètres), dérivé des mêmes valeurs que l'Excel :
 *  - Bandeau supérieur : coffre (travaux SPI), hauteur AM, sur toute la largeur.
 *  - Zone câbles (largeur AT) : remblai terres (AQ), remblai sous-fond (AP),
 *    recouvrement sable (AK), câbles (rectangles, hauteur AH), lit de pose (AG).
 *  - Zone conduites (largeur BN) : remblai terres (BK), remblai sous-fond (BJ),
 *    recouvrement sable (BE), conduites (cercles Ø = largeur), lit de pose (BA).
 *  - Les interstices sont les espaces de sable entre/aux bords des canaux.
 *
 * Lisibilité : libellés des canaux en cartouches au-dessus de la coupe, espacés
 * pour éviter tout chevauchement et reliés à leur forme par une ligne de rappel ;
 * cotes de chaque couche réparties verticalement (anti-collision) de part et
 * d'autre de la fouille.
 */
(function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';
  var COL = {
    coffre: '#9aa5b1', terres: '#a9744f', sousfond: '#d8c19a', sable: '#f3e6a8',
    conduiteFill: '#cdeee2', line: '#33414f', dim: '#62707e', leader: '#9aa7b4'
  };
  var PALETTE = ['#2f6fed', '#e0533d', '#7b3fe4', '#0e9f6e', '#d9890b', '#c026d3', '#0891b2', '#65a30d', '#db2777', '#475569'];

  function E(tag, attrs, children) {
    var e = document.createElementNS(NS, tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    (children || []).forEach(function (c) { if (c != null) e.appendChild(c); });
    return e;
  }
  function txt(x, y, s, opts) {
    var a = Object.assign({ x: x, y: y, fill: COL.dim, 'font-size': 10, 'font-family': 'sans-serif' }, opts || {});
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

  function render(project, row, M) {
    var L = M.buildLayout(project);
    var c = M.computeRowWithLayout(project, row, L);
    var g = row.geom || {};
    var n = M.num;

    var AM = n(g.hauteurCoffre, 0), APv = c.AP, BJv = c.BJ; // AP/BJ effectifs (selon mode de remblai)
    var AG = n(g.litPoseCable, 0), AH = n(g.htMoyCable, 0), BA = n(g.litPoseConduite, 0);
    var AT = c.AT, BN = c.BN, AC = c.AC;
    var depthCable = c.AN, depthCond = c.BH;
    var totalDepth = AM + Math.max(depthCable, depthCond, 0);

    var wrap = document.createElement('div');
    if (AC <= 0 || totalDepth <= 0) {
      wrap.innerHTML = '<p class="hint">Renseignez des largeurs et des hauteurs pour afficher la coupe.</p>';
      return wrap;
    }

    // Canaux non nuls (pour les cartouches au-dessus)
    var channels = [];
    var cursorC = 0;
    L.cols.filter(function (col) { return col.category === 'cable' && (col.isInterstice || col.isChannel); }).forEach(function (col) {
      if (col.isInterstice) { cursorC += n(row.interstices[col.intKey], 0); return; }
      var w = n(row.widths[col.srId], 0);
      if (w > 0) channels.push({ col: col, cat: 'cable', x0: cursorC, w: w, center: cursorC + w / 2 });
      cursorC += w;
    });
    cursorC = AT;
    L.cols.filter(function (col) { return col.category === 'conduite' && (col.isInterstice || col.isChannel); }).forEach(function (col) {
      if (col.isInterstice) { cursorC += n(row.interstices[col.intKey], 0); return; }
      var d = n(row.widths[col.srId], 0);
      if (d > 0) channels.push({ col: col, cat: 'conduite', x0: cursorC, w: d, center: cursorC + d / 2 });
      cursorC += d;
    });

    // Géométrie de dessin (échelle uniforme m -> px)
    var trenchW = 520, trenchH = 300;
    var scale = Math.min(trenchW / AC, trenchH / totalDepth);
    var W = AC * scale, H = totalDepth * scale;
    var padTop = 138, padBottom = 64, padLeft = 152, padRight = 212;
    var x0 = padLeft, y0 = padTop;
    var X = function (m) { return x0 + m * scale; };
    var Y = function (m) { return y0 + m * scale; };
    var svgW = x0 + W + padRight, svgH = y0 + H + padBottom;
    var svg = E('svg', { width: svgW, height: svgH, viewBox: '0 0 ' + svgW + ' ' + svgH, class: 'coupe-svg' });

    // ---- Couches (rectangles) ----
    function layerRect(xm, wm, dTop, h, color) {
      if (h <= 0) return;
      svg.appendChild(E('rect', { x: X(xm), y: Y(dTop), width: wm * scale, height: h * scale, fill: color, stroke: '#00000016' }));
    }
    // Coffre (toute la largeur)
    layerRect(0, AC, 0, AM, COL.coffre);
    svg.appendChild(txt(X(AC / 2), Y(AM / 2) + 3, 'Coffre (SPI)', { fill: '#fff', 'text-anchor': 'middle', 'font-size': 9 }));
    // Zone câbles
    if (AT > 0) {
      var d = AM;
      layerRect(0, AT, d, c.AQ, COL.terres); d += c.AQ;
      layerRect(0, AT, d, APv, COL.sousfond); d += APv;
      layerRect(0, AT, d, c.AO, COL.sable); // recouvrement + câbles + lit (sable)
    }
    // Zone conduites
    if (BN > 0) {
      var e = AM;
      layerRect(AT, BN, e, c.BK, COL.terres); e += c.BK;
      layerRect(AT, BN, e, BJv, COL.sousfond); e += BJv;
      layerRect(AT, BN, e, c.BI, COL.sable);
    }

    // ---- Câbles (rectangles) ----
    var cableTop = AM + c.AQ + APv + c.AK;
    channels.forEach(function (ch) {
      if (ch.cat !== 'cable' || AH <= 0) return;
      svg.appendChild(E('rect', { x: X(ch.x0), y: Y(cableTop), width: ch.w * scale, height: AH * scale,
        fill: colorForConc(project, ch.col.concId), stroke: COL.line, 'stroke-width': 0.6, rx: 1 }));
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

    // ---- Lignes surface / fond de coffre ----
    svg.appendChild(E('line', { x1: X(0) - 8, y1: Y(0), x2: X(AC) + 8, y2: Y(0), stroke: COL.line, 'stroke-width': 1.4 }));
    svg.appendChild(txt(X(AC) + 10, Y(0) + 3, 'Niveau fini', { 'font-size': 9, fill: COL.dim }));
    svg.appendChild(E('line', { x1: X(0), y1: Y(AM), x2: X(AC), y2: Y(AM), stroke: COL.line, 'stroke-dasharray': '4 3', 'stroke-width': 0.8 }));

    // ---- Cartouches de libellés des canaux (au-dessus, anti-chevauchement) ----
    var labelBaseY = y0 - 8;            // bas des cartouches
    var minGap = 15;
    var sorted = channels.slice().sort(function (a, b) { return a.center - b.center; });
    var targets = sorted.map(function (ch) { return X(ch.center); });
    var lo = x0 - 24, hi = x0 + W + 24;
    var placed = spread(targets, minGap);
    // recentre si débordement
    if (placed.length) {
      var shift = 0, last = placed[placed.length - 1];
      if (last > hi) shift = hi - last;
      placed = placed.map(function (p) { return Math.max(lo, p + shift); });
    }
    sorted.forEach(function (ch, i) {
      var lx = placed[i];
      var color = colorForConc(project, ch.col.concId);
      // ligne de rappel : du cartouche vers le sommet de la forme
      svg.appendChild(E('path', { d: 'M' + lx + ',' + (labelBaseY + 2) + ' L' + lx + ',' + (labelBaseY + 8) + ' L' + X(ch.center) + ',' + (ch.topY - 2), stroke: COL.leader, fill: 'none', 'stroke-width': 0.8 }));
      // pastille couleur
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
      // ligne verticale + ticks
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
    var AK = c.AK;
    drawCotes(X(0) - 12, -1, 0, [
      { name: 'Coffre', h: AM }, { name: 'Remblai terres', h: c.AQ }, { name: 'Remblai s-fond.', h: APv },
      { name: 'Recouvr. sable', h: AK }, { name: 'Câbles', h: AH }, { name: 'Lit de pose', h: AG }
    ]);
    if (BN > 0) {
      drawCotes(X(AC) + 12, 1, AM, [
        { name: 'Remblai terres', h: c.BK }, { name: 'Remblai s-fond.', h: BJv }, { name: 'Recouvr. sable', h: c.BE },
        { name: 'Conduites', h: c.BB }, { name: 'Lit de pose', h: BA }
      ]);
    }

    // ---- Cote de profondeur totale (extrême gauche) ----
    dimV(svg, Y(0), Y(totalDepth), 24, fnum(totalDepth) + ' m');

    // ---- Cotes de largeur (bas) ----
    var yDim = y0 + H + 30;
    dimH(svg, X(0), X(AC), yDim, fnum(AC) + ' m');
    if (AT > 0 && BN > 0) {
      svg.appendChild(E('line', { x1: X(AT), y1: Y(0), x2: X(AT), y2: yDim - 12, stroke: COL.leader, 'stroke-dasharray': '2 2', 'stroke-width': 0.8 }));
      svg.appendChild(txt(X(AT / 2), yDim + 16, 'câbles ' + fnum(AT) + ' m', { 'text-anchor': 'middle', 'font-size': 9 }));
      svg.appendChild(txt(X(AT + BN / 2), yDim + 16, 'conduites ' + fnum(BN) + ' m', { 'text-anchor': 'middle', 'font-size': 9 }));
    }

    // ---- Légende (matières + impétrants) ----
    var lg = x0 + W + 116, ly = y0 + 2;
    [['Coffre (SPI)', COL.coffre], ['Remblai terres', COL.terres], ['Remblai sous-fond.', COL.sousfond],
     ['Sable', COL.sable], ['Conduite', COL.conduiteFill]].forEach(function (it) {
      legendItem(svg, lg, ly, it[1], it[0]); ly += 16;
    });
    ly += 6;
    svg.appendChild(txt(lg, ly, 'Impétrants :', { 'font-size': 9, 'font-weight': 'bold', fill: '#3a4654' })); ly += 14;
    project.concessionnaires.forEach(function (cc) { legendItem(svg, lg, ly, colorForConc(project, cc.id), cc.name); ly += 15; });

    wrap.appendChild(svg);
    return wrap;
  }

  function legendItem(svg, x, y, color, label) {
    svg.appendChild(E('rect', { x: x, y: y - 8, width: 12, height: 10, fill: color, stroke: '#00000033' }));
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

  window.TICoupe = { render: render };
})();
