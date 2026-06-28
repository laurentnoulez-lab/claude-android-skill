/*
 * Coupe verticale à l'échelle d'un tronçon de tranchée (SVG, navigateur).
 * window.TICoupe.render(project, row, M) -> élément SVG.
 *
 * Modèle géométrique (en mètres), dérivé des mêmes valeurs que l'Excel :
 *  - Bandeau supérieur : coffre (travaux SPI), hauteur AM, sur toute la largeur.
 *  - Zone câbles (largeur AT) : remblai terres (AQ), remblai sous-fondation (AP),
 *    puis bloc de sable AO = lit de pose (AG) + câbles (AH) + recouvrement (AK).
 *    Les câbles sont des rectangles (largeur saisie × hauteur AH).
 *  - Zone conduites (largeur BN) : remblai terres (BK), remblai sous-fond (BJ),
 *    puis bloc de sable BI = lit de pose (BA) + conduites (BB) + recouvrement (BE).
 *    Les conduites sont des cercles (Ø = largeur saisie) posés sur le lit de pose.
 *  - Les interstices sont les espaces de sable entre/aux bords des canaux.
 */
(function () {
  'use strict';
  var NS = 'http://www.w3.org/2000/svg';
  var COL = {
    coffre: '#9aa5b1', terres: '#a9744f', sousfond: '#d8c19a', sable: '#f3e6a8',
    cableFill: '#2f6fed', conduiteStroke: '#0e7c5a', conduiteFill: '#bfe9d8',
    line: '#33414f', dim: '#6b7785'
  };
  var PALETTE = ['#2f6fed', '#e0533d', '#7b3fe4', '#0e9f6e', '#d9890b', '#c026d3', '#0891b2', '#65a30d', '#db2777', '#475569'];

  function E(tag, attrs, children) {
    var e = document.createElementNS(NS, tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
    (children || []).forEach(function (c) { if (c != null) e.appendChild(c); });
    return e;
  }
  function txt(x, y, s, opts) {
    var a = Object.assign({ x: x, y: y, fill: COL.dim, 'font-size': 11, 'font-family': 'sans-serif' }, opts || {});
    var t = E('text', a); t.textContent = s; return t;
  }
  function fnum(n) { return (Math.round(n * 1000) / 1000).toLocaleString('fr-FR'); }

  function colorForConc(project, concId) {
    var idx = project.concessionnaires.findIndex(function (c) { return c.id === concId; });
    return PALETTE[(idx < 0 ? 0 : idx) % PALETTE.length];
  }

  function render(project, row, M) {
    var L = M.buildLayout(project);
    var c = M.computeRowWithLayout(project, row, L);
    var g = row.geom || {};
    var n = M.num;

    var AM = n(g.hauteurCoffre, 0);
    var APv = n(g.remblaiSousFondCable, 0);  // AP (saisie)
    var BJv = n(g.remblaiSousFondConduite, 0); // BJ (saisie)
    var AT = c.AT, BN = c.BN, AC = c.AC;
    var depthCable = c.AN, depthCond = c.BH;
    var totalDepth = AM + Math.max(depthCable, depthCond, 0);

    var wrap = document.createElement('div');
    if (AC <= 0 || totalDepth <= 0) {
      wrap.innerHTML = '<p class="hint">Renseignez des largeurs et des hauteurs pour afficher la coupe.</p>';
      return wrap;
    }

    // Échelle uniforme (m -> px)
    var padL = 64, padR = 150, padT = 28, padB = 52;
    var availW = 760 - padL - padR, availH = 440 - padT - padB;
    var scale = Math.min(availW / AC, availH / totalDepth);
    var W = AC * scale, H = totalDepth * scale;
    var svgW = W + padL + padR, svgH = H + padT + padB;
    var X = function (m) { return padL + m * scale; };
    var Y = function (m) { return padT + m * scale; };  // m = profondeur depuis la surface

    var svg = E('svg', { width: svgW, height: svgH, viewBox: '0 0 ' + svgW + ' ' + svgH, class: 'coupe-svg' });

    // Bloc de sable global (fond de la fouille des deux zones) puis couches par-dessus.
    function zoneLayers(x0, w, depth, layers) {
      // layers: [{h, color, label}] de haut (sous le coffre) vers le bas
      var y = AM;
      layers.forEach(function (ly) {
        if (ly.h <= 0) return;
        svg.appendChild(E('rect', { x: X(x0), y: Y(y), width: w * scale, height: ly.h * scale, fill: ly.color, stroke: '#00000018' }));
        y += ly.h;
      });
    }

    // Coffre (toute la largeur)
    svg.appendChild(E('rect', { x: X(0), y: Y(0), width: AC * scale, height: AM * scale, fill: COL.coffre, stroke: '#00000022' }));
    svg.appendChild(txt(X(AC / 2), Y(AM / 2) + 4, 'Coffre (SPI)', { fill: '#fff', 'text-anchor': 'middle', 'font-size': 10 }));

    // Zone câbles
    if (AT > 0) {
      zoneLayers(0, AT, depthCable, [
        { h: c.AQ, color: COL.terres }, { h: APv, color: COL.sousfond }, { h: c.AO, color: COL.sable }
      ]);
    }
    // Zone conduites
    if (BN > 0) {
      zoneLayers(AT, BN, depthCond, [
        { h: c.BK, color: COL.terres }, { h: BJv, color: COL.sousfond }, { h: c.BI, color: COL.sable }
      ]);
    }

    // Câbles : rectangles dans la bande AH (entre recouvrement AK et lit de pose AG)
    var cableTop = AM + c.AQ + APv + c.AK;
    var cableH = n(g.htMoyCable, 0);
    var widthOf = function (col) { return n(row.widths[col.srId], 0); };
    var xCursor = 0;
    // Reparcourt les colonnes de largeur câbles dans l'ordre (interstice, canal, …)
    L.cols.filter(function (col) { return col.category === 'cable' && (col.isInterstice || col.isChannel); }).forEach(function (col) {
      if (col.isInterstice) { xCursor += n(row.interstices[col.intKey], 0); return; }
      var w = widthOf(col);
      if (w > 0 && cableH > 0) {
        svg.appendChild(E('rect', { x: X(xCursor), y: Y(cableTop), width: w * scale, height: cableH * scale,
          fill: colorForConc(project, col.concId), stroke: COL.line, 'stroke-width': 0.5, rx: 1 },
          [E('title', {}, [document.createTextNode(col.h3.replace('\n', ' ') + ' — ' + fnum(w) + ' m')])]));
      }
      xCursor += w;
    });

    // Conduites : cercles posés sur le lit de pose de la zone conduites
    var condBottom = AM + c.BK + BJv + c.BI - n(g.litPoseConduite, 0); // haut du lit de pose
    xCursor = AT;
    L.cols.filter(function (col) { return col.category === 'conduite' && (col.isInterstice || col.isChannel); }).forEach(function (col) {
      if (col.isInterstice) { xCursor += n(row.interstices[col.intKey], 0); return; }
      var d = widthOf(col);
      if (d > 0) {
        var cx = xCursor + d / 2, r = d / 2;
        svg.appendChild(E('circle', { cx: X(cx), cy: Y(condBottom - r), r: r * scale,
          fill: COL.conduiteFill, stroke: colorForConc(project, col.concId), 'stroke-width': 1.5 },
          [E('title', {}, [document.createTextNode(col.h3.replace('\n', ' ') + ' — Ø ' + fnum(d) + ' m')])]));
      }
      xCursor += d;
    });

    // Ligne de surface + fond de coffre
    svg.appendChild(E('line', { x1: X(0) - 6, y1: Y(0), x2: X(AC) + 6, y2: Y(0), stroke: COL.line, 'stroke-width': 1.5 }));
    svg.appendChild(txt(X(0) - 8, Y(0) - 6, 'Niveau fini', { fill: COL.dim, 'font-size': 10 }));
    svg.appendChild(E('line', { x1: X(0), y1: Y(AM), x2: X(AC), y2: Y(AM), stroke: COL.line, 'stroke-dasharray': '4 3', 'stroke-width': 1 }));

    // Cote de largeur (bas)
    var yDim = svgH - padB + 26;
    dimH(svg, X(0), X(AC), yDim, fnum(AC) + ' m', X);
    if (AT > 0 && BN > 0) {
      svg.appendChild(E('line', { x1: X(AT), y1: Y(0), x2: X(AT), y2: yDim - 14, stroke: COL.dim, 'stroke-dasharray': '2 2' }));
      svg.appendChild(txt(X(AT / 2), yDim + 18, 'câbles ' + fnum(AT) + ' m', { 'text-anchor': 'middle', 'font-size': 10 }));
      svg.appendChild(txt(X(AT + BN / 2), yDim + 18, 'conduites ' + fnum(BN) + ' m', { 'text-anchor': 'middle', 'font-size': 10 }));
    }
    // Cote de profondeur (gauche)
    dimV(svg, Y(0), Y(totalDepth), padL - 34, fnum(totalDepth) + ' m');

    // Légende
    var lg = padL + W + 18, ly = padT + 4;
    legendItem(svg, lg, ly, COL.coffre, 'Coffre (SPI)'); ly += 18;
    legendItem(svg, lg, ly, COL.terres, 'Remblai terres'); ly += 18;
    legendItem(svg, lg, ly, COL.sousfond, 'Remblai sous-fond.'); ly += 18;
    legendItem(svg, lg, ly, COL.sable, 'Sable (lit + recouvr.)'); ly += 18;
    legendItem(svg, lg, ly, COL.cableFill, 'Câble (rectangle)'); ly += 18;
    legendItem(svg, lg, ly, COL.conduiteFill, 'Conduite (cercle)', COL.conduiteStroke); ly += 24;
    project.concessionnaires.forEach(function (cc) {
      legendItem(svg, lg, ly, colorForConc(project, cc.id), cc.name); ly += 16;
    });

    wrap.appendChild(svg);
    return wrap;
  }

  function legendItem(svg, x, y, color, label, stroke) {
    svg.appendChild(E('rect', { x: x, y: y - 9, width: 13, height: 11, fill: color, stroke: stroke || '#00000033' }));
    svg.appendChild(txt(x + 19, y, label, { 'font-size': 11, fill: '#33414f' }));
  }
  function dimH(svg, x1, x2, y, label, X) {
    svg.appendChild(E('line', { x1: x1, y1: y, x2: x2, y2: y, stroke: '#6b7785' }));
    svg.appendChild(E('line', { x1: x1, y1: y - 4, x2: x1, y2: y + 4, stroke: '#6b7785' }));
    svg.appendChild(E('line', { x1: x2, y1: y - 4, x2: x2, y2: y + 4, stroke: '#6b7785' }));
    svg.appendChild(txt((x1 + x2) / 2, y - 5, label, { 'text-anchor': 'middle', fill: '#33414f', 'font-weight': 'bold' }));
  }
  function dimV(svg, y1, y2, x, label) {
    svg.appendChild(E('line', { x1: x, y1: y1, x2: x, y2: y2, stroke: '#6b7785' }));
    svg.appendChild(E('line', { x1: x - 4, y1: y1, x2: x + 4, y2: y1, stroke: '#6b7785' }));
    svg.appendChild(E('line', { x1: x - 4, y1: y2, x2: x + 4, y2: y2, stroke: '#6b7785' }));
    var t = txt(x - 6, (y1 + y2) / 2, label, { 'text-anchor': 'middle', fill: '#33414f', 'font-weight': 'bold' });
    t.setAttribute('transform', 'rotate(-90 ' + (x - 6) + ' ' + ((y1 + y2) / 2) + ')');
    svg.appendChild(t);
  }

  window.TICoupe = { render: render };
})();
