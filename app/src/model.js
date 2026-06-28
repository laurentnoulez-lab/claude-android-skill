/*
 * Gabarit tranchées impétrants — coeur métier (modèle, moteur de calcul, export Excel).
 *
 * Ce module est volontairement sans dépendance au DOM : il fonctionne aussi bien
 * dans le navigateur que sous Node (pour les tests). La fonction buildWorkbook()
 * reçoit la bibliothèque ExcelJS en paramètre afin de rester agnostique de
 * l'environnement.
 *
 * Reproduit l'onglet « Gabarits tranchées communes » du classeur de référence :
 * saisie des tronçons, largeurs par impétrant (câbles / conduites), calcul des
 * volumes et répartition des volumes par impétrant — le tout avec les mêmes
 * formules Excel que l'original.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.TIModel = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var DATA_START = 5; // première ligne de données dans le classeur (lignes 1-4 = en-têtes)

  // ---------------------------------------------------------------------------
  // Utilitaires
  // ---------------------------------------------------------------------------
  var _idc = 0;
  function uid(prefix) {
    _idc += 1;
    return (prefix || 'id') + '-' + Date.now().toString(36) + '-' + _idc.toString(36);
  }

  // Index de colonne 1-based -> lettre Excel (1 -> A, 27 -> AA, ...)
  function colLetter(n) {
    var s = '';
    while (n > 0) {
      var m = (n - 1) % 26;
      s = String.fromCharCode(65 + m) + s;
      n = Math.floor((n - 1) / 26);
    }
    return s;
  }

  function num(v, dflt) {
    var n = typeof v === 'string' ? parseFloat(v.replace(',', '.')) : v;
    return (typeof n === 'number' && isFinite(n)) ? n : (dflt === undefined ? 0 : dflt);
  }

  // ---------------------------------------------------------------------------
  // Projet par défaut
  // ---------------------------------------------------------------------------
  function defaultGeometry() {
    return {
      // partie câbles
      litPoseCable: 0.1,            // AG
      htMoyCable: 0.12,             // AH
      recouvSableMinCable: 0.1,     // AI
      ligneAligne: 'OUI',           // AJ (OUI/NON)
      recouvNiveauFiniCable: 0.8,   // AL
      hauteurCoffre: 0.3,           // AM
      remblaiSousFondCable: 0,      // AP
      longueurGaines: 0,            // AU
      // partie conduites
      litPoseConduite: 0.1,         // BA
      recouvSableMinConduite: 0.2,  // BC
      recouvNiveauFiniConduite: 1,  // BF
      remblaiSousFondConduite: 0    // BJ
    };
  }

  function defaultProject() {
    var mk = function (name, category, labels) {
      return {
        id: uid('conc'),
        name: name,
        category: category, // 'cable' | 'conduite'
        sousReseaux: labels.map(function (l) { return { id: uid('sr'), label: l }; })
      };
    };
    return {
      version: 1,
      app: 'gabarit-tranchees-impetrants',
      name: 'Nouveau projet',
      concessionnaires: [
        mk('PROXIMUS', 'cable', ['DP', 'DD', 'E']),
        mk('SOFICO', 'cable', ['E']),
        mk('ELEC MT', 'cable', ['DP', 'DD', 'E']),
        mk('ELEC BT', 'cable', ['DP', 'DD', 'E', 'Éclairage']),
        mk('EAU', 'conduite', ['DP', 'DD', 'E']),
        mk('Gaz', 'conduite', ['E'])
      ],
      defaults: Object.assign({ type: 'A', largeurMax: 0 }, defaultGeometry()),
      rows: []
    };
  }

  // Crée une nouvelle ligne de tronçon en appliquant les valeurs par défaut.
  function newRow(project) {
    var d = project.defaults || {};
    var row = {
      id: uid('row'),
      de: '', a: '', type: d.type || 'A', commentaire: '',
      longueur: 0,
      largeurMax: num(d.largeurMax, 0),
      widths: {},        // sousReseauId -> largeur
      interstices: {},   // clé -> largeur
      geom: {
        litPoseCable: num(d.litPoseCable, 0.1),
        htMoyCable: num(d.htMoyCable, 0.12),
        recouvSableMinCable: num(d.recouvSableMinCable, 0.1),
        ligneAligne: d.ligneAligne || 'OUI',
        recouvNiveauFiniCable: num(d.recouvNiveauFiniCable, 0.8),
        hauteurCoffre: num(d.hauteurCoffre, 0.3),
        remblaiSousFondCable: num(d.remblaiSousFondCable, 0),
        longueurGaines: num(d.longueurGaines, 0),
        litPoseConduite: num(d.litPoseConduite, 0.1),
        recouvSableMinConduite: num(d.recouvSableMinConduite, 0.2),
        recouvNiveauFiniConduite: num(d.recouvNiveauFiniConduite, 1),
        remblaiSousFondConduite: num(d.remblaiSousFondConduite, 0)
      }
    };
    return row;
  }

  // ---------------------------------------------------------------------------
  // Construction de la structure de colonnes (dynamique selon les impétrants)
  // ---------------------------------------------------------------------------
  // Clés d'interstices : un interstice précède chaque groupe impétrant, plus un
  // interstice de fin par catégorie (câbles, conduites). => N+1 interstices.
  function intersticePreKey(concId) { return 'pre_' + concId; }
  function intersticePostKey(category) { return 'post_' + category; }

  function channelsOf(project, category) {
    var list = [];
    project.concessionnaires.forEach(function (c) {
      if (c.category !== category) return;
      c.sousReseaux.forEach(function (sr) {
        list.push({ conc: c, sr: sr });
      });
    });
    return list;
  }

  // Retourne la description ordonnée des colonnes + tables de correspondance.
  function buildLayout(project) {
    var cols = [];
    var push = function (col) { col.index = cols.length + 1; col.letter = colLetter(col.index); cols.push(col); return col; };

    // Colonnes fixes de gauche
    push({ field: 'de', kind: 'input', dtype: 'text', h3: 'Tranchée impétrant\n(voir points sur le plan)', h4: 'De' });
    push({ field: 'a', kind: 'input', dtype: 'text', h3: '', h4: 'à' });
    push({ field: 'type', kind: 'input', dtype: 'text', h3: 'Tranchée Type:', h4: '' });
    push({ field: 'commentaire', kind: 'input', dtype: 'text', h3: 'Commentaires', h4: '' });
    push({ field: 'longueur', kind: 'input', dtype: 'number', h3: 'Longueur', h4: '(m)' });

    // Bloc « Largeur tranchée » : interstices + canaux, câbles puis conduites
    var widthStart = cols.length + 1;
    var intersticeLetter = 0;
    var cableChannelCols = [], conduiteChannelCols = [];
    var cableIntCols = [], conduiteIntCols = [];

    // Un interstice précède CHAQUE sous-réseau, plus un interstice de fin de
    // catégorie. K sous-réseaux => K+1 interstices. Aucune colonne d'interstice
    // si la catégorie ne contient aucun sous-réseau.
    ['cable', 'conduite'].forEach(function (cat) {
      var chans = channelsOf(project, cat); // [{conc, sr}]
      if (!chans.length) return;
      var intCols = (cat === 'cable' ? cableIntCols : conduiteIntCols);
      var chCols = (cat === 'cable' ? cableChannelCols : conduiteChannelCols);
      chans.forEach(function (d) {
        var ic = push({ field: 'int', intKey: intersticePreKey(d.sr.id), kind: 'input', dtype: 'number',
                        category: cat, h3: String.fromCharCode(97 + (intersticeLetter++)), h4: '(m)', isInterstice: true,
                        intBefore: d.conc.name + ' ' + d.sr.label });
        intCols.push(ic);
        var ch = push({ field: 'ch', srId: d.sr.id, concId: d.conc.id, kind: 'input', dtype: 'number',
                        category: cat, h3: d.conc.name + '\n' + d.sr.label, h4: '(m)', isChannel: true });
        chCols.push(ch);
      });
      // interstice de fin de catégorie
      var post = push({ field: 'int', intKey: intersticePostKey(cat), kind: 'input', dtype: 'number',
                       category: cat, h3: String.fromCharCode(97 + (intersticeLetter++)), h4: '(m)', isInterstice: true,
                       intBefore: '(fin ' + (cat === 'cable' ? 'câbles' : 'conduites') + ')' });
      intCols.push(post);
    });
    var widthEnd = cols.length;
    var widthFirstLetter = colLetter(widthStart);
    var widthLastLetter = colLetter(widthEnd);

    // Colonnes calculées « fixes » (rôles identiques à l'original AC..BT)
    // On mémorise la lettre de chaque rôle pour bâtir les formules.
    var R = {}; // rôle -> colonne
    var addCalc = function (role, opts) {
      var col = push(Object.assign({ field: 'calc', role: role, kind: 'calc' }, opts));
      R[role] = col;
      return col;
    };

    addCalc('AC', { h2: 'Largeur tranchée\nthéorique', h4: '(m)' });
    addCalc('AD', { kind: 'input', dtype: 'number', h2: 'Largeur max disponible\n(limite domaine public)', h4: '(m)', field: 'largeurMax' });
    addCalc('AE', { h4: '' }); // contrôle NOK
    addCalc('AF', { h3: 'Longueur tranchée', h4: '(m)' });
    // Partie câbles
    addCalc('AG', { kind: 'input', dtype: 'number', gkey: 'litPoseCable', h3: 'Lit de pose min.', h4: '(m)' });
    addCalc('AH', { kind: 'input', dtype: 'number', gkey: 'htMoyCable', h3: 'Ht moyenne câbles', h4: '(m)' });
    addCalc('AI', { kind: 'input', dtype: 'number', gkey: 'recouvSableMinCable', h3: 'Recouvrement sable min.', h4: '(m)' });
    addCalc('AJ', { kind: 'input', dtype: 'oui', gkey: 'ligneAligne', h3: 'Ligne recouvrement sable alignée entre câbles et conduites', h4: '(OUI/NON)' });
    addCalc('AK', { h3: 'Recouvrement sable effectif', h4: '(m)' });
    addCalc('AL', { kind: 'input', dtype: 'number', gkey: 'recouvNiveauFiniCable', h3: 'Recouvrement min. par rapport niveau fini', h4: '(m)' });
    addCalc('AM', { kind: 'input', dtype: 'number', gkey: 'hauteurCoffre', h3: 'Hauteur coffre (travaux SPI)', h4: '(m)' });
    addCalc('AN', { h3: 'Hauteur terrassement (après mise à fond de coffre)', h4: '(m)' });
    addCalc('AO', { h3: 'Lit de pose + câbles + recouvrement sable', h4: '(m)' });
    addCalc('AP', { kind: 'input', dtype: 'number', gkey: 'remblaiSousFondCable', h3: 'Hauteur remblai matériaux de sous-fondation entre sable et fond de coffre', h4: '(m)' });
    addCalc('AQ', { h3: 'Hauteur remblai terres décaissées entre sable et fond de coffre', h4: '(m)' });
    addCalc('AR', { h3: 'Largeur occupée par les câbles', h4: '(m)' });
    addCalc('AS', { h3: 'Largeur interstices câbles', h4: '(m)' });
    addCalc('AT', { h3: 'Largeur totale tranchée câbles', h4: '(m)' });
    addCalc('AU', { kind: 'input', dtype: 'number', gkey: 'longueurGaines', h3: 'Longueur gaines rigides', h4: '(m)' });
    addCalc('AV', { h3: 'Volume occupé par câbles sable et câbles', h4: '(m³)' });
    addCalc('AW', { h3: 'Volume sable', h4: '(m³)' });
    addCalc('AX', { h3: 'Volume remblais en matériaux de sous-fondation', h4: '(m³)' });
    addCalc('AY', { h3: 'Volume déblais excédentaires pour mise en merlon', h4: '(m³)' });
    addCalc('AZ', { h3: 'Volume de tranchée (entre fond de coffre et fond de fouille)', h4: '(m³)' });
    // Partie conduites
    addCalc('BA', { kind: 'input', dtype: 'number', gkey: 'litPoseConduite', h3: 'Lit de pose min.', h4: '(m)' });
    addCalc('BB', { h3: 'Ht moyenne conduites', h4: '(m)' });
    addCalc('BC', { kind: 'input', dtype: 'number', gkey: 'recouvSableMinConduite', h3: 'Recouvrement sable min.', h4: '(m)' });
    addCalc('BD', { h3: 'Ligne recouvrement sable alignée entre câbles et conduites', h4: '(OUI/NON)' });
    addCalc('BE', { h3: 'Recouvrement sable effectif', h4: '(m)' });
    addCalc('BF', { kind: 'input', dtype: 'number', gkey: 'recouvNiveauFiniConduite', h3: 'Recouvrement min. par rapport niveau fini', h4: '(m)' });
    addCalc('BG', { h3: 'Hauteur coffre (travaux SPI)', h4: '(m)' });
    addCalc('BH', { h3: 'Hauteur terrassement (après mise à fond de coffre)', h4: '(m)' });
    addCalc('BI', { h3: 'Lit de pose + câbles + recouvrement sable', h4: '(m)' });
    addCalc('BJ', { kind: 'input', dtype: 'number', gkey: 'remblaiSousFondConduite', h3: 'Hauteur remblai matériaux de sous-fondation entre sable et fond de coffre', h4: '(m)' });
    addCalc('BK', { h3: 'Hauteur remblai terres décaissées entre sable et fond de coffre', h4: '(m)' });
    addCalc('BL', { h3: 'Largeur occupée par les conduites', h4: '(m)' });
    addCalc('BM', { h3: 'Largeur interstices conduites', h4: '(m)' });
    addCalc('BN', { h3: 'Largeur totale tranchée conduites', h4: '(m)' });
    addCalc('BO', { h3: 'Volume occupé par câbles sable et conduites', h4: '(m³)' });
    addCalc('BP', { h3: 'Volume sable', h4: '(m³)' });
    addCalc('BQ', { h3: 'Volume remblais en matériaux de sous-fondation', h4: '(m³)' });
    addCalc('BR', { h3: 'Volume déblais excédentaires pour mise en merlon', h4: '(m³)' });
    addCalc('BS', { h3: 'Volume de tranchée (entre fond de coffre et fond de fouille)', h4: '(m³)' });
    addCalc('BT', { h3: 'Volume total de tranchée (entre fond de coffre et fond de fouille)', h4: '(m³)' });

    // Colonne tampon vide (comme l'original : sépare volumes et répartition)
    push({ field: 'spacer', kind: 'spacer', h3: '', h4: '' });

    // Bloc « Répartition » : 6 colonnes par canal (câbles puis conduites)
    var repStart = cols.length + 1;
    var repartGroups = [];
    var allChannels = cableChannelCols.concat(conduiteChannelCols);
    var REP_SUB = function (cat) {
      return [
        cat === 'cable' ? 'Part volume tranchée câbles ' : 'Part volume tranchée conduites',
        'Part volume tranchée totale', 'Longueur tranchée', 'Volume sable',
        'Volume remblai matériaux sous-fondation', 'Volume déblais excédentaires'
      ];
    };
    allChannels.forEach(function (ch) {
      var subs = REP_SUB(ch.category);
      var group = { channel: ch, cols: [] };
      subs.forEach(function (label, i) {
        var rc = push({ field: 'rep', repWhich: i, repChannel: ch, category: ch.category,
                        h3: ch.h3, h4: label });
        group.cols.push(rc);
      });
      repartGroups.push(group);
    });
    var repEnd = cols.length;

    return {
      cols: cols, roles: R,
      widthStart: widthStart, widthEnd: widthEnd,
      widthFirstLetter: widthFirstLetter, widthLastLetter: widthLastLetter,
      cableChannelCols: cableChannelCols, conduiteChannelCols: conduiteChannelCols,
      cableIntCols: cableIntCols, conduiteIntCols: conduiteIntCols,
      allChannels: allChannels, repartGroups: repartGroups,
      repStart: repStart, repEnd: repEnd
    };
  }

  // ---------------------------------------------------------------------------
  // Moteur de calcul (miroir des formules, pour l'aperçu en direct)
  // ---------------------------------------------------------------------------
  function computeRow(project, row) {
    var L = buildLayout(project); // structure (pour connaître canaux par catégorie)
    return computeRowWithLayout(project, row, L);
  }

  function computeRowWithLayout(project, row, L) {
    var g = row.geom || {};
    var widthOf = function (col) {
      if (col.isInterstice) return num(row.interstices[col.intKey], 0);
      return num(row.widths[col.srId], 0);
    };
    var wc = L.cableChannelCols.map(widthOf);
    var wp = L.conduiteChannelCols.map(widthOf);
    var ic = L.cableIntCols.map(widthOf);
    var ip = L.conduiteIntCols.map(widthOf);
    var sum = function (a) { return a.reduce(function (s, v) { return s + v; }, 0); };

    var E = num(row.longueur, 0);
    var AF = E;
    var AG = num(g.litPoseCable, 0), AH = num(g.htMoyCable, 0), AI = num(g.recouvSableMinCable, 0);
    var AJ = (g.ligneAligne || 'OUI');
    var AL = num(g.recouvNiveauFiniCable, 0), AM = num(g.hauteurCoffre, 0), AP = num(g.remblaiSousFondCable, 0);
    var AR = sum(wc), AS = sum(ic), AT = AR + AS;
    var BA = num(g.litPoseConduite, 0), BC = num(g.recouvSableMinConduite, 0), BF = num(g.recouvNiveauFiniConduite, 0);
    var BJ = num(g.remblaiSousFondConduite, 0);
    var BB = wp.length ? Math.max.apply(null, wp) : 0;
    var BL = sum(wp), BM = sum(ip), BN = BL + BM;
    var BD = AJ, BG = AM;
    var AK = (AJ === 'OUI' && BN > 0) ? Math.max(AI, AL - (BF - BC)) : AI;
    var AN = AG + AH + AL - AM;
    var AO = AG + AH + AK;
    var AQ = AN - AO - AP;
    var AV = AF * AO * AT, AW = AV, AX = AF * AP * AT, AY = AV + AX, AZ = AF * AN * AT;
    var BE = (BD === 'OUI' && AT > 0) ? Math.max(BC, BF - (AL - AI)) : BC;
    var BH = BA + BB + BF - BG;
    var BI = BA + BB + BE;
    var BK = BH - BI - BJ;
    var BO = AF * BI * BN;
    var sumSq = wp.reduce(function (s, d) { return s + Math.pow(d / 2, 2); }, 0);
    var BP = BO - Math.PI * sumSq * AF;
    var BQ = AF * BJ * BN;
    var BR = BO + BQ;
    var BS = AF * BH * BN;
    var BT = AZ + BS;
    var AC = AT + BN;
    var AE = (num(row.largeurMax, 0) > 0 && AC > num(row.largeurMax, 0)) ? 'NOK' : '';

    return {
      AC: AC, AE: AE, AF: AF, AR: AR, AS: AS, AT: AT, AK: AK, AN: AN, AO: AO, AQ: AQ,
      AV: AV, AW: AW, AX: AX, AY: AY, AZ: AZ, BB: BB, BE: BE, BH: BH, BI: BI, BK: BK,
      BL: BL, BM: BM, BN: BN, BO: BO, BP: BP, BQ: BQ, BR: BR, BS: BS, BT: BT
    };
  }

  // Répartition par sous-réseau (miroir des colonnes BV..FG de l'Excel).
  // Le volume des interstices est réparti au prorata de la largeur occupée :
  // partCat = largeur du sous-réseau / largeur occupée de la catégorie ; ce ratio
  // s'applique au volume TOTAL de la partie (AZ câbles / BS conduites), lequel
  // inclut déjà les interstices.
  function computeRepartition(project, row, L) {
    L = L || buildLayout(project);
    var c = computeRowWithLayout(project, row, L);
    var widthOf = function (col) { return num(row.widths[col.srId], 0); };
    var res = [];
    var add = function (col, cat) {
      var w = widthOf(col);
      var occ = cat === 'cable' ? c.AR : c.BL;
      var volPart = cat === 'cable' ? c.AZ : c.BS;
      var partCat = occ > 0 ? w / occ : 0;
      var partTot = c.BT > 0 ? partCat * volPart / c.BT : 0;
      res.push({
        srId: col.srId, concId: col.concId, label: col.h3.replace('\n', ' '), category: cat,
        width: w, partCat: partCat, partTot: partTot,
        longueur: partTot * c.AF,
        volTranchee: partCat * volPart,
        volSable: partCat * (cat === 'cable' ? c.AW : c.BP),
        volRemblai: partCat * (cat === 'cable' ? c.AX : c.BQ),
        volDeblais: partCat * (cat === 'cable' ? c.AY : c.BR)
      });
    };
    L.cableChannelCols.forEach(function (col) { add(col, 'cable'); });
    L.conduiteChannelCols.forEach(function (col) { add(col, 'conduite'); });
    return { row: c, channels: res };
  }

  function projectTotals(project) {
    var L = buildLayout(project);
    var t = { longueur: 0, volSableCable: 0, volSableConduite: 0, volRemblai: 0, volDeblais: 0, volTotal: 0, nok: 0 };
    project.rows.forEach(function (row) {
      var c = computeRowWithLayout(project, row, L);
      t.longueur += num(row.longueur, 0);
      t.volSableCable += c.AW; t.volSableConduite += c.BP;
      t.volRemblai += c.AX + c.BQ;
      t.volDeblais += c.AY + c.BR;
      t.volTotal += c.BT;
      if (c.AE === 'NOK') t.nok += 1;
    });
    return t;
  }

  // ---------------------------------------------------------------------------
  // Génération du classeur Excel (avec formules, comme l'original)
  // ---------------------------------------------------------------------------
  function buildWorkbook(project, ExcelJS) {
    var L = buildLayout(project);
    var cols = L.cols;
    var Rr = L.roles; // rôle -> col (avec .letter)
    var lt = function (role) { return Rr[role].letter; };

    var wb = new ExcelJS.Workbook();
    wb.creator = 'Gabarit tranchées impétrants';
    wb.created = new Date();
    var ws = wb.addWorksheet('Gabarits tranchées communes', { views: [{ state: 'frozen', xSplit: 5, ySplit: 4 }] });

    // Largeurs de colonnes
    cols.forEach(function (col) {
      ws.getColumn(col.index).width = (col.field === 'commentaire') ? 26 : (col.field === 'de' || col.field === 'a' || col.field === 'type') ? 7 : 11;
    });

    // Styles réutilisables
    var thin = { style: 'thin', color: { argb: 'FFBFBFBF' } };
    var border = { top: thin, left: thin, bottom: thin, right: thin };
    var inputFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFFFF2CC' } };       // jaune clair
    var calcFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFF2F2F2' } };        // gris très clair
    var hdrFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFD9E1F2' } };         // bleu clair
    var hdrFont = { bold: true, size: 9 };

    function styleHeaderCell(cell, text) {
      cell.value = text;
      cell.font = hdrFont;
      cell.alignment = { horizontal: 'center', vertical: 'middle', wrapText: true };
      cell.fill = hdrFill;
      cell.border = border;
    }
    function mergeSafe(a, b) { try { ws.mergeCells(a + ':' + b); } catch (e) {} }

    // --- Lignes d'en-tête 1 à 4 ---
    styleHeaderCell(ws.getCell('A1'), 'Cellules à remplir');
    ws.getCell('A1').fill = inputFill;
    mergeSafe('A1', 'D1');

    // Bandeau ligne 1
    if (L.widthEnd >= L.widthStart) {
      styleHeaderCell(ws.getCell(L.widthFirstLetter + '1'), 'Largeur tranchée');
      mergeSafe(L.widthFirstLetter + '1', L.widthLastLetter + '1');
    }
    styleHeaderCell(ws.getCell(lt('AF') + '1'), 'Longueurs et volumes');
    mergeSafe(lt('AF') + '1', lt('BT') + '1');
    if (L.repartGroups.length) {
      styleHeaderCell(ws.getCell(colLetter(L.repStart) + '1'), 'Répartition');
      mergeSafe(colLetter(L.repStart) + '1', colLetter(L.repEnd) + '1');
    }

    // Bandeau ligne 2 : Câbles / Conduites (largeurs), théorique, max, parties, répartition
    var cableW = cols.filter(function (c) { return (c.isChannel || c.isInterstice) && c.category === 'cable'; });
    var conduiteW = cols.filter(function (c) { return (c.isChannel || c.isInterstice) && c.category === 'conduite'; });
    if (cableW.length) { styleHeaderCell(ws.getCell(cableW[0].letter + '2'), 'Câbles'); mergeSafe(cableW[0].letter + '2', cableW[cableW.length - 1].letter + '2'); }
    if (conduiteW.length) { styleHeaderCell(ws.getCell(conduiteW[0].letter + '2'), 'Conduites'); mergeSafe(conduiteW[0].letter + '2', conduiteW[conduiteW.length - 1].letter + '2'); }
    styleHeaderCell(ws.getCell(lt('AC') + '2'), 'Largeur tranchée\nthéorique'); mergeSafe(lt('AC') + '2', lt('AC') + '4');
    styleHeaderCell(ws.getCell(lt('AD') + '2'), 'Largeur max disponible\n(limite domaine public)'); mergeSafe(lt('AD') + '2', lt('AD') + '4');
    styleHeaderCell(ws.getCell(lt('AG') + '2'), 'Partie câbles'); mergeSafe(lt('AG') + '2', lt('AZ') + '2');
    styleHeaderCell(ws.getCell(lt('BA') + '2'), 'Partie Conduites'); mergeSafe(lt('BA') + '2', lt('BS') + '2');
    // bandeau répartition ligne 2 : Câbles / Conduites
    var repCable = L.repartGroups.filter(function (g) { return g.channel.category === 'cable'; });
    var repConduite = L.repartGroups.filter(function (g) { return g.channel.category === 'conduite'; });
    if (repCable.length) { styleHeaderCell(ws.getCell(repCable[0].cols[0].letter + '2'), 'Câbles'); mergeSafe(repCable[0].cols[0].letter + '2', repCable[repCable.length - 1].cols[5].letter + '2'); }
    if (repConduite.length) { styleHeaderCell(ws.getCell(repConduite[0].cols[0].letter + '2'), 'Conduites'); mergeSafe(repConduite[0].cols[0].letter + '2', repConduite[repConduite.length - 1].cols[5].letter + '2'); }

    // Lignes 3 et 4 : titres de colonnes + unités
    cols.forEach(function (col) {
      if (col.field === 'spacer') return;
      if (col.h3 !== undefined && col.h3 !== '') styleHeaderCell(ws.getCell(col.letter + '3'), col.h3);
      else { ws.getCell(col.letter + '3').border = border; ws.getCell(col.letter + '3').fill = hdrFill; }
      if (col.h4 !== undefined && col.h4 !== '') styleHeaderCell(ws.getCell(col.letter + '4'), col.h4);
      else { ws.getCell(col.letter + '4').border = border; ws.getCell(col.letter + '4').fill = hdrFill; }
    });
    // Titres "De"/"à" fusion verticale + groupe répartition (nom du canal sur les 6 colonnes en ligne 3)
    styleHeaderCell(ws.getCell('A3'), 'Tranchée impétrant\n(voir points sur le plan)'); mergeSafe('A3', 'B3');
    L.repartGroups.forEach(function (gr) {
      styleHeaderCell(ws.getCell(gr.cols[0].letter + '3'), gr.channel.h3);
      mergeSafe(gr.cols[0].letter + '3', gr.cols[5].letter + '3');
    });

    // --- Lignes de données ---
    var fmt = '0.00';
    project.rows.forEach(function (row, i) {
      var R = DATA_START + i;
      cols.forEach(function (col) {
        if (col.field === 'spacer') return;
        var cell = ws.getCell(col.letter + R);
        cell.border = border;
        var f = formulaFor(col, R, L, lt, row);
        if (f.formula !== undefined) {
          cell.value = { formula: f.formula };
          cell.fill = calcFill;
          if (f.numfmt !== false) cell.numFmt = fmt;
        } else {
          cell.value = f.value;
          if (col.kind === 'input') cell.fill = inputFill;
          if (col.dtype === 'number') cell.numFmt = fmt;
        }
        if (col.field === 'commentaire') cell.alignment = { wrapText: true, vertical: 'top' };
      });
    });

    // Mise en forme conditionnelle : AE = "NOK" en rouge
    var lastRow = DATA_START + Math.max(project.rows.length, 1) - 1;
    ws.addConditionalFormatting({
      ref: lt('AE') + DATA_START + ':' + lt('AE') + lastRow,
      rules: [{
        type: 'containsText', operator: 'containsText', text: 'NOK', priority: 1,
        style: { font: { color: { argb: 'FFFF0000' }, bold: true }, fill: { type: 'pattern', pattern: 'solid', bgColor: { argb: 'FFFFC7CE' } } }
      }]
    });

    ws.getRow(3).height = 60;
    ws.getRow(1).height = 18; ws.getRow(2).height = 18;
    return wb;
  }

  // Renvoie {value} pour une saisie, {formula} pour une cellule calculée.
  function formulaFor(col, R, L, lt, row) {
    if (col.kind === 'input') {
      return { value: inputValue(col, row) };
    }
    if (col.field === 'calc') return { formula: calcFormula(col.role, R, L, lt) };
    if (col.field === 'rep') return { formula: repartFormula(col, R, L, lt) };
    return { value: null };
  }

  function inputValue(col, row) {
    if (!row) return null;
    switch (col.field) {
      case 'de': return row.de || null;
      case 'a': return row.a || null;
      case 'type': return row.type || null;
      case 'commentaire': return row.commentaire || null;
      case 'longueur': return num(row.longueur, 0);
      case 'largeurMax': return num(row.largeurMax, 0);
      case 'int': return num(row.interstices[col.intKey], 0);
      case 'ch': return num(row.widths[col.srId], 0);
      case 'calc': // saisies parmi les colonnes calculées (gkey)
        if (col.role === 'AD') return num(row.largeurMax, 0);
        if (col.role === 'AJ') return (row.geom && row.geom.ligneAligne) || 'OUI';
        if (col.gkey) return num(row.geom[col.gkey], 0);
        return null;
      default: return null;
    }
  }

  function calcFormula(role, R, L, lt) {
    var cc = L.cableChannelCols.map(function (c) { return c.letter + R; });
    var pp = L.conduiteChannelCols.map(function (c) { return c.letter + R; });
    var ci = L.cableIntCols.map(function (c) { return c.letter + R; });
    var pi = L.conduiteIntCols.map(function (c) { return c.letter + R; });
    var sumList = function (a) { return a.length ? 'SUM(' + a.join(',') + ')' : '0'; };
    var f = {
      AC: lt('AT') + R + '+' + lt('BN') + R, // = somme de toutes les largeurs (câbles + conduites)
      AE: 'IF(AND(' + lt('AD') + R + '>0,' + lt('AC') + R + '>' + lt('AD') + R + '),"NOK","")',
      AF: 'E' + R,
      AK: 'IF(AND(' + lt('AJ') + R + '="OUI",' + lt('BN') + R + '>0),MAX(' + lt('AI') + R + ',' + lt('AL') + R + '-(' + lt('BF') + R + '-' + lt('BC') + R + ')),' + lt('AI') + R + ')',
      AN: lt('AG') + R + '+' + lt('AH') + R + '+' + lt('AL') + R + '-' + lt('AM') + R,
      AO: lt('AG') + R + '+' + lt('AH') + R + '+' + lt('AK') + R,
      AQ: lt('AN') + R + '-' + lt('AO') + R + '-' + lt('AP') + R,
      AR: sumList(cc),
      AS: sumList(ci),
      AT: lt('AR') + R + '+' + lt('AS') + R,
      AV: lt('AF') + R + '*' + lt('AO') + R + '*' + lt('AT') + R,
      AW: lt('AV') + R,
      AX: lt('AF') + R + '*' + lt('AP') + R + '*' + lt('AT') + R,
      AY: lt('AV') + R + '+' + lt('AX') + R,
      AZ: lt('AF') + R + '*' + lt('AN') + R + '*' + lt('AT') + R,
      BB: pp.length ? 'MAX(' + pp.join(',') + ')' : '0',
      BD: lt('AJ') + R,
      BE: 'IF(AND(' + lt('BD') + R + '="OUI",' + lt('AT') + R + '>0),MAX(' + lt('BC') + R + ',' + lt('BF') + R + '-(' + lt('AL') + R + '-' + lt('AI') + R + ')),' + lt('BC') + R + ')',
      BG: lt('AM') + R,
      BH: lt('BA') + R + '+' + lt('BB') + R + '+' + lt('BF') + R + '-' + lt('BG') + R,
      BI: lt('BA') + R + '+' + lt('BB') + R + '+' + lt('BE') + R,
      BK: lt('BH') + R + '-' + lt('BI') + R + '-' + lt('BJ') + R,
      BL: sumList(pp),
      BM: sumList(pi),
      BN: lt('BL') + R + '+' + lt('BM') + R,
      BO: lt('AF') + R + '*' + lt('BI') + R + '*' + lt('BN') + R,
      BP: pp.length ? (lt('BO') + R + '-PI()*(' + pp.map(function (c) { return '(' + c + '/2)^2'; }).join('+') + ')*' + lt('AF') + R) : (lt('BO') + R),
      BQ: lt('AF') + R + '*' + lt('BJ') + R + '*' + lt('BN') + R,
      BR: lt('BO') + R + '+' + lt('BQ') + R,
      BS: lt('AF') + R + '*' + lt('BH') + R + '*' + lt('BN') + R,
      BT: lt('AZ') + R + '+' + lt('BS') + R
    };
    return f[role];
  }

  function repartFormula(col, R, L, lt) {
    var ch = col.repChannel;
    var group = null;
    L.repartGroups.forEach(function (g) { if (g.channel === ch) group = g; });
    var P1 = group.cols[0].letter; // part
    var P2 = group.cols[1].letter; // part totale
    var chRef = ch.letter + R;
    if (ch.category === 'cable') {
      switch (col.repWhich) {
        case 0: return 'IF($' + lt('AR') + R + '>0,' + chRef + '/$' + lt('AR') + R + ',0)';
        case 1: return P1 + R + '*$' + lt('AZ') + R + '/$' + lt('BT') + R;
        case 2: return P2 + R + '*$' + lt('AF') + R;
        case 3: return P1 + R + '*$' + lt('AW') + R;
        case 4: return P1 + R + '*$' + lt('AX') + R;
        case 5: return P1 + R + '*$' + lt('AY') + R;
      }
    } else {
      switch (col.repWhich) {
        case 0: return 'IF($' + lt('BL') + R + '>0,' + chRef + '/$' + lt('BL') + R + ',0)';
        case 1: return P1 + R + '*$' + lt('BS') + R + '/$' + lt('BT') + R;
        case 2: return P2 + R + '*$' + lt('AF') + R;
        case 3: return P1 + R + '*$' + lt('BP') + R;
        case 4: return P1 + R + '*$' + lt('BQ') + R;
        case 5: return P1 + R + '*$' + lt('BR') + R;
      }
    }
  }

  return {
    DATA_START: DATA_START,
    uid: uid, colLetter: colLetter, num: num,
    defaultProject: defaultProject, defaultGeometry: defaultGeometry, newRow: newRow,
    buildLayout: buildLayout,
    computeRow: computeRow, computeRowWithLayout: computeRowWithLayout, projectTotals: projectTotals,
    computeRepartition: computeRepartition,
    buildWorkbook: buildWorkbook,
    channelsOf: channelsOf,
    intersticePreKey: intersticePreKey, intersticePostKey: intersticePostKey,
    _calcFormula: calcFormula, _repartFormula: repartFormula
  };
});
