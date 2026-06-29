/*
 * Génération du document Word (.docx) : notice méthodologique + données du projet
 * courant + explication de la répartition des interstices.
 * window.TIDocx.generate(project, M) -> Promise<Blob>.
 * Utilise la variable globale `docx` (build IIFE de la bibliothèque docx).
 */
(function () {
  'use strict';

  function generate(project, M) {
    var D = window.docx;
    var Document = D.Document, Packer = D.Packer, Paragraph = D.Paragraph, TextRun = D.TextRun,
        HeadingLevel = D.HeadingLevel, Table = D.Table, TableRow = D.TableRow, TableCell = D.TableCell,
        WidthType = D.WidthType, AlignmentType = D.AlignmentType;
    var num = M.num;
    var f2 = function (n) { return (n == null || isNaN(n)) ? '–' : Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); };
    var pct = function (n) { return (n == null || isNaN(n)) ? '–' : (n * 100).toLocaleString('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' %'; };

    var C = []; // children du document

    function h1(t) { C.push(new Paragraph({ text: t, heading: HeadingLevel.HEADING_1, spacing: { before: 240, after: 120 } })); }
    function h2(t) { C.push(new Paragraph({ text: t, heading: HeadingLevel.HEADING_2, spacing: { before: 180, after: 80 } })); }
    function p(runs) { C.push(new Paragraph({ children: Array.isArray(runs) ? runs : [new TextRun(runs)], spacing: { after: 80 } })); }
    function bullet(t) { C.push(new Paragraph({ text: t, bullet: { level: 0 }, spacing: { after: 40 } })); }
    function formula(label, expr) {
      C.push(new Paragraph({ spacing: { after: 60 }, children: [
        label ? new TextRun({ text: label + ' : ', bold: true }) : new TextRun(''),
        new TextRun({ text: expr, font: 'Consolas' })
      ] }));
    }
    function cell(text, opts) {
      opts = opts || {};
      return new TableCell({
        width: opts.width ? { size: opts.width, type: WidthType.PERCENTAGE } : undefined,
        shading: opts.header ? { fill: 'E6ECF5' } : undefined,
        children: [new Paragraph({ alignment: opts.right ? AlignmentType.RIGHT : AlignmentType.LEFT,
          children: [new TextRun({ text: String(text == null ? '' : text), bold: !!opts.header, size: 18 })] })]
      });
    }
    function table(headers, rows, widths) {
      var trs = [new TableRow({ tableHeader: true, children: headers.map(function (hd, i) { return cell(hd, { header: true, width: widths && widths[i], right: i > 0 }); }) })];
      rows.forEach(function (r) { trs.push(new TableRow({ children: r.map(function (v, i) { return cell(v, { width: widths && widths[i], right: i > 0 }); }) })); });
      C.push(new Table({ width: { size: 100, type: WidthType.PERCENTAGE }, rows: trs }));
      C.push(new Paragraph({ text: '', spacing: { after: 80 } }));
    }

    // ----------------------------------------------------------------- En-tête
    C.push(new Paragraph({ text: 'Gabarit tranchées impétrants', heading: HeadingLevel.TITLE }));
    p([new TextRun({ text: 'Notice méthodologique et récapitulatif du projet', italics: true })]);
    p([new TextRun({ text: 'Projet : ', bold: true }), new TextRun(project.name || '(sans nom)'),
       new TextRun({ text: '    —    Généré le ' + new Date().toLocaleDateString('fr-FR'), color: '888888' })]);

    // -------------------------------------------------------------- 1. Objet
    h1('1. Objet et fonctionnement');
    p('Cette application calcule, pour chaque tronçon de tranchée commune, les largeurs et les volumes de terrassement, puis répartit ces volumes entre les impétrants (concessionnaires) au prorata de leur emprise. Elle reproduit la logique de l\'onglet « Gabarits tranchées communes » du classeur de référence et génère un classeur Excel contenant les mêmes formules.');
    p('Le travail s\'organise en trois temps :');
    bullet('Définir les impétrants et leurs sous-réseaux (catégorie Câbles ou Conduites).');
    bullet('Saisir les tronçons : longueur, largeurs par sous-réseau, interstices et paramètres géométriques (lit de pose, recouvrements, hauteur de coffre…).');
    bullet('Visualiser les résultats (largeurs, volumes, coupe à l\'échelle, parts par sous-réseau) puis exporter en Excel et/ou Word.');

    // ----------------------------------------------------- 2. Structure données
    h1('2. Structure des données');
    p([new TextRun({ text: 'Impétrant : ', bold: true }), new TextRun('un concessionnaire (PROXIMUS, ELEC BT, EAU…), rattaché soit aux câbles soit aux conduites.')]);
    p([new TextRun({ text: 'Sous-réseau : ', bold: true }), new TextRun('une subdivision d\'un impétrant (DP, DD, E, Éclairage…). Chaque sous-réseau correspond à une colonne de largeur dans le gabarit.')]);
    p([new TextRun({ text: 'Interstice : ', bold: true }), new TextRun('un espace (sable) sans réseau. Un interstice précède chaque sous-réseau, et un interstice de fin clôt chaque catégorie. Une catégorie sans aucun sous-réseau ne comporte aucun interstice.')]);

    var concRows = project.concessionnaires.map(function (c) {
      return [c.name, c.category === 'cable' ? 'Câbles' : 'Conduites', c.sousReseaux.map(function (s) { return s.label; }).join(', ')];
    });
    if (concRows.length) table(['Impétrant', 'Catégorie', 'Sous-réseaux'], concRows, [30, 20, 50]);

    // ------------------------------------------------------ 3. Largeurs
    h1('3. Largeur de tranchée');
    p('Pour chaque tronçon, les largeurs saisies (sous-réseaux et interstices) déterminent les largeurs occupées et totales :');
    formula('Largeur occupée câbles (AR)', 'somme des largeurs des sous-réseaux « câbles »');
    formula('Largeur interstices câbles (AS)', 'somme des interstices « câbles »');
    formula('Largeur totale câbles (AT)', 'AR + AS');
    formula('Largeur occupée / interstices / totale conduites', 'BL, BM, BN = BL + BM (même logique)');
    formula('Largeur théorique (AC)', 'AT + BN');
    p([new TextRun({ text: 'Contrôle : ', bold: true }), new TextRun('si une largeur maximale disponible est renseignée et que AC la dépasse, le tronçon est signalé « NOK ».')]);

    // ------------------------------------------------------ 4. Volumes câbles
    h1('4. Géométrie et volumes — partie câbles');
    formula('Recouvrement sable effectif (AK)', 'SI(ligne alignée="OUI" ET conduites présentes ; MAX(AI ; AL−(BF−BC)) ; AI)');
    formula('Hauteur terrassement (AN)', 'AG + AH + AL − AM');
    formula('Lit + câbles + recouvrement (AO)', 'AG + AH + AK');
    formula('Remblai terres décaissées (AQ)', 'AN − AO − AP');
    formula('Volume occupé câbles+sable (AV) / Volume sable (AW)', 'AF × AO × AT ; AW = AV');
    formula('Volume remblai sous-fondation (AX)', 'AF × AP × AT');
    formula('Volume déblais excédentaires (AY)', 'AV + AX');
    formula('Volume de tranchée câbles (AZ)', 'AF × AN × AT');
    p([new TextRun({ text: 'AF', font: 'Consolas' }), new TextRun(' est la longueur du tronçon. Tous les volumes « câbles » utilisent la largeur totale AT (interstices compris).')]);
    p([new TextRun({ text: 'Remblai entre le sable et le fond de coffre : ', bold: true }),
       new TextRun('la zone de hauteur AN−AO se répartit entre l\'empierrement de sous-fondation (AP) et les terres décaissées réutilisées (AQ = AN−AO−AP). Trois choix par tronçon : « Terres décaissées » (AP = 0), « Empierrement » (AP = AN−AO, soit AQ = 0) ou « Manuel » (AP saisi). La même logique s\'applique aux conduites avec BJ et BK = BH−BI−BJ.')]);

    // -------------------------------------------------- 5. Volumes conduites
    h1('5. Géométrie et volumes — partie conduites');
    formula('Ht moyenne conduites (BB)', 'MAX(diamètres des conduites)');
    formula('Recouvrement sable effectif (BE)', 'SI(ligne alignée="OUI" ET câbles présents ; MAX(BC ; BF−(AL−AI)) ; BC)');
    formula('Hauteur terrassement (BH)', 'BA + BB + BF − BG   (BG = AM)');
    formula('Lit + conduites + recouvrement (BI)', 'BA + BB + BE');
    formula('Volume occupé (BO)', 'AF × BI × BN');
    formula('Volume sable (BP)', 'BO − π × Σ(Øᵢ/2)² × AF   (on retire le volume des tuyaux)');
    formula('Volume remblai sous-fondation (BQ)', 'AF × BJ × BN');
    formula('Volume déblais excédentaires (BR)', 'BO + BQ');
    formula('Volume de tranchée conduites (BS)', 'AF × BH × BN');
    formula('Volume total de tranchée (BT)', 'AZ + BS');

    // ---------------------------------------- 6. Répartition + interstices (clé)
    h1('6. Répartition par sous-réseau et traitement des interstices');
    p('Les volumes sont répartis entre sous-réseaux d\'une même catégorie au prorata de leur largeur occupée :');
    formula('Part dans la tranchée (câbles)', 'part = largeur du sous-réseau ÷ AR   (conduites : ÷ BL)');
    formula('Part dans la tranchée totale', 'part_totale = part × AZ ÷ BT   (conduites : × BS ÷ BT)');
    formula('Volumes attribués', 'volume_sable = part × AW ; remblai = part × AX ; déblais = part × AY (câbles) ; idem avec BP/BQ/BR pour les conduites');
    h2('Comment les interstices sont répartis');
    p([new TextRun({ text: 'Les interstices ne sont attribués à aucun sous-réseau en propre. ', bold: true }),
       new TextRun('La part d\'un sous-réseau est calculée sur la largeur OCCUPÉE (AR ou BL), qui exclut les interstices. Mais cette part est ensuite appliquée au volume TOTAL de la partie (AZ pour les câbles, BS pour les conduites), volume qui — lui — est calculé sur la largeur TOTALE (AT = AR + AS, donc interstices compris).')]);
    p('Conséquence : le volume des interstices est réparti implicitement entre les sous-réseaux, proportionnellement à leur largeur occupée. Un sous-réseau plus large « absorbe » donc une plus grande part du volume des interstices voisins.');
    p([new TextRun({ text: 'Démonstration : ', italics: true }),
       new TextRun({ text: 'volume_attribué = part × AZ = (largeur ÷ AR) × (AF × AN × AT) = (largeur ÷ AR) × (AF × AN × (AR + AS)). Le facteur (AR + AS) fait apparaître la quote-part d\'interstices AS, distribuée selon largeur ÷ AR.', font: 'Consolas' })]);
    p('La somme des parts d\'une catégorie vaut 1 (si la largeur occupée est non nulle) ; la somme des parts totales (câbles + conduites) vaut également 1, de sorte que la somme des volumes attribués égale le volume total BT.');

    // ------------------------------------------ 7. Données du projet courant
    h1('7. Données et résultats du projet');
    if (!project.rows.length) {
      p('(Aucun tronçon saisi.)');
    } else {
      var L = M.buildLayout(project);
      var rows = project.rows.map(function (r, i) {
        var c = M.computeRowWithLayout(project, r, L);
        return [String(i + 1), (r.de || '') + '→' + (r.a || ''), r.type || '', f2(r.longueur), f2(c.AC), f2(c.AT), f2(c.BN), f2(c.BT), c.AE === 'NOK' ? 'NOK' : 'OK'];
      });
      table(['#', 'De→à', 'Type', 'Long. (m)', 'Larg. théo.', 'Larg. câbles', 'Larg. cond.', 'Vol. tot. (m³)', 'Ctrl'], rows,
        [6, 16, 8, 11, 12, 12, 12, 14, 9]);

      var t = M.projectTotals(project);
      p([new TextRun({ text: 'Totaux : ', bold: true }),
         new TextRun(project.rows.length + ' tronçon(s) · longueur ' + f2(t.longueur) + ' m · volume sable ' + f2(t.volSableCable + t.volSableConduite) + ' m³ · remblai sous-fond. ' + f2(t.volRemblai) + ' m³ · déblais excéd. ' + f2(t.volDeblais) + ' m³ · ')]);
      p([new TextRun({ text: 'Volume total de tranchée : ' + f2(t.volTotal) + ' m³', bold: true })]);

      // Répartition agrégée par sous-réseau
      h2('Répartition des volumes par sous-réseau (cumul du projet)');
      var agg = {};
      project.rows.forEach(function (r) {
        var rep = M.computeRepartition(project, r, L);
        rep.channels.forEach(function (ch) {
          var k = ch.srId;
          if (!agg[k]) agg[k] = { label: ch.label, category: ch.category, volCable: 0, volConduite: 0, vol: 0, sable: 0, remblai: 0, deblais: 0 };
          agg[k].volCable += ch.volCable; agg[k].volConduite += ch.volConduite;
          agg[k].vol += ch.volTranchee; agg[k].sable += ch.volSable; agg[k].remblai += ch.volRemblai; agg[k].deblais += ch.volDeblais;
        });
      });
      var totVol = Object.keys(agg).reduce(function (s, k) { return s + agg[k].vol; }, 0);
      var aggRows = Object.keys(agg).map(function (k) {
        var a = agg[k];
        return [a.label, a.category === 'cable' ? 'Câbles' : 'Conduites', f2(a.volCable), f2(a.volConduite), f2(a.vol), pct(totVol > 0 ? a.vol / totVol : 0)];
      });
      if (aggRows.length) table(['Sous-réseau', 'Catégorie', 'Vol. attribué câbles (m³)', 'Vol. attribué conduites (m³)', 'Vol. de tranchée totale attribué (m³)', 'Part'], aggRows,
        [22, 13, 17, 17, 19, 12]);
    }

    // ----------------------------------------------------- 8. Excel
    h1('8. Génération du classeur Excel');
    p('Le bouton « Générer Excel » produit un fichier .xlsx reproduisant l\'onglet du gabarit : en-têtes, cellules de saisie (en jaune) et toutes les formules ci-dessus (largeurs, volumes et répartition par sous-réseau). Les colonnes s\'adaptent automatiquement aux impétrants et sous-réseaux définis.');
    p('Les formules de répartition sont encadrées par SI.ERREUR(… ; 0) : si un volume est nul, le résultat affiché est 0 au lieu d\'une erreur #DIV/0!.');

    var doc = new Document({
      creator: 'Gabarit tranchées impétrants',
      title: 'Notice — ' + (project.name || 'projet'),
      sections: [{ properties: {}, children: C }]
    });
    return Packer.toBlob(doc);
  }

  window.TIDocx = { generate: generate };
})();
