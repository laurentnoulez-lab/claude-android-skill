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
      // Remblai entre le haut du sable d'enrobage et le fond de coffre :
      //  'terres'       -> terres décaissées réutilisées (AP = 0)
      //  'empierrement' -> matériaux de sous-fondation sur toute la zone (AP = AN-AO)
      //  'manuel'       -> hauteur AP saisie librement
      remblaiModeCable: 'terres',   // AP (mode)
      remblaiSousFondCable: 0,      // AP (valeur si mode manuel)
      // Câbles posés sous gaines (fourreaux) côte à côte : le volume des gaines
      // (cylindres Ø × longueur) est déduit du volume de sable, comme pour les
      // conduites. Nombre de gaines = largeur occupée câbles ÷ Ø.
      gainesCables: 'NON',          // OUI/NON
      diamGaine: 0.16,              // Ø gaine (m)
      longueurGaines: 0,            // AU (auto = (AR/Ø)×AF si gaines OUI)
      // partie conduites
      litPoseConduite: 0.1,         // BA
      recouvSableMinConduite: 0.2,  // BC
      recouvNiveauFiniConduite: 1,  // BF
      remblaiModeConduite: 'terres',// BJ (mode)
      remblaiSousFondConduite: 0,   // BJ (valeur si mode manuel)
      htConduiteMode: 'auto',       // BB : 'auto' (MAX des Ø) ou 'manuel'
      htConduiteManuelle: 0         // BB (valeur si mode manuel)
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
        remblaiModeCable: d.remblaiModeCable || 'terres',
        remblaiSousFondCable: num(d.remblaiSousFondCable, 0),
        gainesCables: d.gainesCables || 'NON',
        diamGaine: num(d.diamGaine, 0.16),
        longueurGaines: num(d.longueurGaines, 0),
        litPoseConduite: num(d.litPoseConduite, 0.1),
        recouvSableMinConduite: num(d.recouvSableMinConduite, 0.2),
        recouvNiveauFiniConduite: num(d.recouvNiveauFiniConduite, 1),
        remblaiModeConduite: d.remblaiModeConduite || 'terres',
        remblaiSousFondConduite: num(d.remblaiSousFondConduite, 0),
        htConduiteMode: d.htConduiteMode || 'auto',
        htConduiteManuelle: num(d.htConduiteManuelle, 0)
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
    var AL = num(g.recouvNiveauFiniCable, 0), AM = num(g.hauteurCoffre, 0);
    var AR = sum(wc), AS = sum(ic), AT = AR + AS;
    var BA = num(g.litPoseConduite, 0), BC = num(g.recouvSableMinConduite, 0), BF = num(g.recouvNiveauFiniConduite, 0);
    // BB : hauteur moyenne conduites — MAX des diamètres, ou valeur forcée
    var BB = (g.htConduiteMode === 'manuel') ? num(g.htConduiteManuelle, 0)
                                             : (wp.length ? Math.max.apply(null, wp) : 0);
    var BL = sum(wp), BM = sum(ip), BN = BL + BM;
    var BD = AJ, BG = AM;
    var AK = (AJ === 'OUI' && BN > 0) ? Math.max(AI, AL - (BF - BC)) : AI;
    var AN = AG + AH + AL - AM;
    var AO = AG + AH + AK;
    // AP (remblai sous-fondation/empierrement câbles) selon le mode choisi
    var AP = remblaiEff(g.remblaiModeCable, AN - AO, num(g.remblaiSousFondCable, 0));
    var AQ = AN - AO - AP;
    var AV = AF * AO * AT, AX = AF * AP * AT, AY = AV + AX, AZ = AF * AN * AT;
    // AW (volume sable câbles) : si les câbles sont posés sous gaines côte à
    // côte, on déduit le volume des gaines (nb = AR/Ø ; cylindres π(Ø/2)²×AF),
    // comme le fait BP pour les conduites. Sans gaines, le volume des câbles
    // n'est pas déduit (AW = AV).
    var gaines = (g.gainesCables === 'OUI') && num(g.diamGaine, 0) > 0;
    var nbGaines = gaines ? AR / num(g.diamGaine, 0.16) : 0;
    var AW = gaines ? AV - nbGaines * Math.PI * Math.pow(num(g.diamGaine, 0.16) / 2, 2) * AF : AV;
    // AU (longueur de gaines rigides) : auto si gaines, sinon saisie manuelle
    var AU = gaines ? nbGaines * AF : num(g.longueurGaines, 0);
    var BE = (BD === 'OUI' && AT > 0) ? Math.max(BC, BF - (AL - AI)) : BC;
    var BH = BA + BB + BF - BG;
    var BI = BA + BB + BE;
    var BJ = remblaiEff(g.remblaiModeConduite, BH - BI, num(g.remblaiSousFondConduite, 0));
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
      AC: AC, AE: AE, AF: AF, AR: AR, AS: AS, AT: AT, AK: AK, AN: AN, AO: AO, AP: AP, AQ: AQ,
      AU: AU, AV: AV, AW: AW, AX: AX, AY: AY, AZ: AZ, BB: BB, BE: BE, BH: BH, BI: BI, BJ: BJ, BK: BK,
      BL: BL, BM: BM, BN: BN, BO: BO, BP: BP, BQ: BQ, BR: BR, BS: BS, BT: BT
    };
  }

  // Hauteur effective de remblai sous-fondation selon le mode.
  function remblaiEff(mode, zoneHeight, manualValue) {
    if (mode === 'empierrement') return zoneHeight;     // toute la zone en empierrement
    if (mode === 'manuel') return manualValue;
    return 0;                                            // 'terres' : aucune sous-fondation
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
        // volume attribué dans la tranchée câbles / conduites (0 pour l'autre catégorie)
        volCable: cat === 'cable' ? partCat * c.AZ : 0,
        volConduite: cat === 'conduite' ? partCat * c.BS : 0,
        // volume de tranchée totale attribué (= volCable + volConduite)
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

    // Teintes d'en-tête par section : câbles en bleu, conduites en vert
    var cableHdrFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFDDEBF7' } };
    var conduiteHdrFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFE2EFDA' } };
    var bandFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF1F3864' } };   // bandeau ligne 1
    function fillForCategory(cat) {
      if (cat === 'cable') return cableHdrFill;
      if (cat === 'conduite') return conduiteHdrFill;
      return hdrFill;
    }
    function styleHeaderCell(cell, text, fill) {
      cell.value = text;
      cell.font = hdrFont;
      cell.alignment = { horizontal: 'center', vertical: 'middle', wrapText: true };
      cell.fill = fill || hdrFill;
      cell.border = border;
    }
    // Bandeau de section (ligne 1) : fond bleu nuit, texte blanc
    function styleBandCell(cell, text) {
      cell.value = text;
      cell.font = { bold: true, color: { argb: 'FFFFFFFF' }, size: 10 };
      cell.alignment = { horizontal: 'center', vertical: 'middle' };
      cell.fill = bandFill;
      cell.border = border;
    }
    function mergeSafe(a, b) { try { ws.mergeCells(a + ':' + b); } catch (e) {} }

    // --- Lignes d'en-tête 1 à 4 ---
    styleHeaderCell(ws.getCell('A1'), 'Cellules à remplir');
    ws.getCell('A1').fill = inputFill;
    mergeSafe('A1', 'D1');

    // Bandeau ligne 1 (bleu nuit, texte blanc)
    if (L.widthEnd >= L.widthStart) {
      styleBandCell(ws.getCell(L.widthFirstLetter + '1'), 'Largeur tranchée');
      mergeSafe(L.widthFirstLetter + '1', L.widthLastLetter + '1');
    }
    styleBandCell(ws.getCell(lt('AF') + '1'), 'Longueurs et volumes');
    mergeSafe(lt('AF') + '1', lt('BT') + '1');
    if (L.repartGroups.length) {
      styleBandCell(ws.getCell(colLetter(L.repStart) + '1'), 'Répartition des volumes par sous-réseau');
      mergeSafe(colLetter(L.repStart) + '1', colLetter(L.repEnd) + '1');
    }

    // Bandeau ligne 2 : Câbles / Conduites (largeurs), théorique, max, parties, répartition
    var cableW = cols.filter(function (c) { return (c.isChannel || c.isInterstice) && c.category === 'cable'; });
    var conduiteW = cols.filter(function (c) { return (c.isChannel || c.isInterstice) && c.category === 'conduite'; });
    if (cableW.length) { styleHeaderCell(ws.getCell(cableW[0].letter + '2'), 'Câbles', cableHdrFill); mergeSafe(cableW[0].letter + '2', cableW[cableW.length - 1].letter + '2'); }
    if (conduiteW.length) { styleHeaderCell(ws.getCell(conduiteW[0].letter + '2'), 'Conduites', conduiteHdrFill); mergeSafe(conduiteW[0].letter + '2', conduiteW[conduiteW.length - 1].letter + '2'); }
    styleHeaderCell(ws.getCell(lt('AC') + '2'), 'Largeur tranchée\nthéorique'); mergeSafe(lt('AC') + '2', lt('AC') + '4');
    styleHeaderCell(ws.getCell(lt('AD') + '2'), 'Largeur max disponible\n(limite domaine public)'); mergeSafe(lt('AD') + '2', lt('AD') + '4');
    styleHeaderCell(ws.getCell(lt('AG') + '2'), 'Partie câbles', cableHdrFill); mergeSafe(lt('AG') + '2', lt('AZ') + '2');
    styleHeaderCell(ws.getCell(lt('BA') + '2'), 'Partie Conduites', conduiteHdrFill); mergeSafe(lt('BA') + '2', lt('BS') + '2');
    // bandeau répartition ligne 2 : Câbles / Conduites
    var repCable = L.repartGroups.filter(function (g) { return g.channel.category === 'cable'; });
    var repConduite = L.repartGroups.filter(function (g) { return g.channel.category === 'conduite'; });
    if (repCable.length) { styleHeaderCell(ws.getCell(repCable[0].cols[0].letter + '2'), 'Câbles', cableHdrFill); mergeSafe(repCable[0].cols[0].letter + '2', repCable[repCable.length - 1].cols[5].letter + '2'); }
    if (repConduite.length) { styleHeaderCell(ws.getCell(repConduite[0].cols[0].letter + '2'), 'Conduites', conduiteHdrFill); mergeSafe(repConduite[0].cols[0].letter + '2', repConduite[repConduite.length - 1].cols[5].letter + '2'); }

    // Lignes 3 et 4 : titres de colonnes + unités (teintés selon la catégorie)
    cols.forEach(function (col) {
      if (col.field === 'spacer') return;
      var f3 = fillForCategory(col.category);
      if (col.h3 !== undefined && col.h3 !== '') styleHeaderCell(ws.getCell(col.letter + '3'), col.h3, f3);
      else { ws.getCell(col.letter + '3').border = border; ws.getCell(col.letter + '3').fill = f3; }
      if (col.h4 !== undefined && col.h4 !== '') styleHeaderCell(ws.getCell(col.letter + '4'), col.h4, f3);
      else { ws.getCell(col.letter + '4').border = border; ws.getCell(col.letter + '4').fill = f3; }
    });
    // Titres "De"/"à" fusion verticale + groupe répartition (nom du canal sur les 6 colonnes en ligne 3)
    styleHeaderCell(ws.getCell('A3'), 'Tranchée impétrant\n(voir points sur le plan)'); mergeSafe('A3', 'B3');
    L.repartGroups.forEach(function (gr) {
      styleHeaderCell(ws.getCell(gr.cols[0].letter + '3'), gr.channel.h3, fillForCategory(gr.channel.category));
      mergeSafe(gr.cols[0].letter + '3', gr.cols[5].letter + '3');
    });

    // --- Lignes de données ---
    var fmt = '0.00';
    var pctFmt = '0.0%';
    var isPctCol = function (col) { return col.field === 'rep' && col.repWhich <= 1; };
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
          if (f.numfmt !== false) cell.numFmt = isPctCol(col) ? pctFmt : fmt;
        } else {
          cell.value = f.value;
          if (col.kind === 'input' || f.input) cell.fill = inputFill;
          if (col.dtype === 'number' || f.input) cell.numFmt = fmt;
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

    // --- Ligne TOTAL (sommes des longueurs et volumes) ---
    if (project.rows.length > 0) {
      var TR = DATA_START + project.rows.length;
      var totalFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFD6E4F0' } };
      var totalFont = { bold: true, size: 9 };
      var thick = { style: 'double', color: { argb: 'FF1F3864' } };
      var totalRoles = ['AF', 'AU', 'AV', 'AW', 'AX', 'AY', 'AZ', 'BO', 'BP', 'BQ', 'BR', 'BS', 'BT'];
      var tcell = ws.getCell('A' + TR);
      tcell.value = 'TOTAL'; tcell.font = totalFont; tcell.fill = totalFill;
      tcell.alignment = { horizontal: 'center' };
      mergeSafe('A' + TR, 'D' + TR);
      var sumTo = function (letter) {
        var cell = ws.getCell(letter + TR);
        cell.value = { formula: 'SUM(' + letter + DATA_START + ':' + letter + lastRow + ')' };
        cell.numFmt = fmt; cell.font = totalFont; cell.fill = totalFill;
        cell.border = { top: thick, left: thin, bottom: thin, right: thin };
      };
      sumTo('E');
      totalRoles.forEach(function (role) { sumTo(lt(role)); });
      // totaux répartition : longueur / sable / remblai / déblais par canal
      L.repartGroups.forEach(function (gr) {
        [2, 3, 4, 5].forEach(function (wi) { sumTo(gr.cols[wi].letter); });
      });
      ws.getRow(TR).height = 16;
    }

    ws.getRow(3).height = 60;
    ws.getRow(1).height = 18; ws.getRow(2).height = 18;
    ws.properties.tabColor = { argb: 'FF2563EB' };

    // --- Onglet Synthèse (métré + clé de répartition) ---
    buildSyntheseSheet(wb, project, L, lt, lastRow);

    return wb;
  }

  // Onglet « Synthèse » : quantités globales pour le métré, longueurs par type
  // de tranchée, et clé de répartition par sous-réseau — le tout en formules
  // vivantes référençant l'onglet gabarit (recalcul automatique dans Excel).
  function buildSyntheseSheet(wb, project, L, lt, lastRow) {
    var SRC = "'Gabarits tranchées communes'!";
    var ref = function (letter) { return SRC + letter + '$' + DATA_START + ':' + letter + '$' + lastRow; };
    var ws = wb.addWorksheet('Synthèse', { views: [{ showGridLines: false }] });
    ws.properties.tabColor = { argb: 'FF0E9F6E' };

    var thin = { style: 'thin', color: { argb: 'FFBFBFBF' } };
    var border = { top: thin, left: thin, bottom: thin, right: thin };
    var fmt = '#,##0.00';
    var titleFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF1F3864' } };
    var sectionFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFD6E4F0' } };
    var thFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFEDF2F9' } };
    var cableFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFDDEBF7' } };
    var conduiteFill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFE2EFDA' } };

    var widths = [26, 14, 12, 14, 14, 16, 16, 16, 14, 16, 16];
    widths.forEach(function (w, i) { ws.getColumn(i + 1).width = w; });

    var r = 1;
    function mergeSafe(a, b) { try { ws.mergeCells(a + ':' + b); } catch (e) {} }
    function section(title) {
      var c = ws.getCell('A' + r);
      c.value = title; c.font = { bold: true, size: 11, color: { argb: 'FF1F3864' } };
      c.fill = sectionFill; c.alignment = { vertical: 'middle' };
      mergeSafe('A' + r, 'K' + r);
      ws.getRow(r).height = 18;
      r += 1;
    }
    function kv(label, formula, unit, numFmtOverride) {
      ws.getCell('A' + r).value = label;
      ws.getCell('A' + r).border = border;
      var v = ws.getCell('B' + r);
      v.value = { formula: formula };
      v.numFmt = numFmtOverride || fmt; v.font = { bold: true }; v.border = border;
      v.alignment = { horizontal: 'right' };
      ws.getCell('C' + r).value = unit || '';
      ws.getCell('C' + r).font = { color: { argb: 'FF66727F' } };
      ws.getCell('C' + r).border = border;
      r += 1;
    }
    function headerRow(labels) {
      labels.forEach(function (t, i) {
        var c = ws.getCell(colLetter(i + 1) + r);
        c.value = t; c.font = { bold: true, size: 9 }; c.fill = thFill; c.border = border;
        c.alignment = { horizontal: i === 0 ? 'left' : 'center', wrapText: true, vertical: 'middle' };
      });
      ws.getRow(r).height = 28;
      r += 1;
    }

    // --- Titre ---
    var t = ws.getCell('A1');
    t.value = 'Synthèse — Métré et clé de répartition';
    t.font = { bold: true, size: 14, color: { argb: 'FFFFFFFF' } };
    t.fill = titleFill; t.alignment = { vertical: 'middle', indent: 1 };
    mergeSafe('A1', 'K1'); ws.getRow(1).height = 26;
    r = 2;
    ws.getCell('A2').value = 'Projet : ' + (project.name || '(sans nom)') + '    —    généré le ' + new Date().toLocaleDateString('fr-FR');
    ws.getCell('A2').font = { italic: true, color: { argb: 'FF66727F' } };
    r = 4;

    // --- 1. Quantités globales ---
    section('1. Quantités globales (métré)');
    kv('Nombre de tronçons', 'COUNT(' + ref('E') + ')', '', '0');
    kv('Longueur totale de tranchées', 'SUM(' + ref(lt('AF')) + ')', 'm');
    kv('Volume total de tranchée', 'SUM(' + ref(lt('BT')) + ')', 'm³');
    kv("Volume de sable d'enrobage", 'SUM(' + ref(lt('AW')) + ')+SUM(' + ref(lt('BP')) + ')', 'm³');
    kv('Volume remblai en matériaux de sous-fondation', 'SUM(' + ref(lt('AX')) + ')+SUM(' + ref(lt('BQ')) + ')', 'm³');
    kv('Volume déblais excédentaires (mise en merlon)', 'SUM(' + ref(lt('AY')) + ')+SUM(' + ref(lt('BR')) + ')', 'm³');
    kv('Longueur gaines rigides', 'SUM(' + ref(lt('AU')) + ')', 'm');
    kv('Tronçons en dépassement de largeur (NOK)', 'COUNTIF(' + ref(lt('AE')) + ',"NOK")', '', '0');
    r += 1;

    // --- 2. Longueurs et volumes par type de tranchée ---
    section('2. Longueurs et volumes par type de tranchée');
    var types = [];
    project.rows.forEach(function (row) {
      var ty = (row.type || '').trim();
      if (ty && types.indexOf(ty) < 0) types.push(ty);
    });
    types.sort();
    if (types.length) {
      headerRow(['Type', 'Nb tronçons', '', 'Longueur (m)', 'Volume tranchée (m³)']);
      types.forEach(function (ty) {
        var q = '"' + ty.replace(/"/g, '""') + '"';
        ws.getCell('A' + r).value = ty; ws.getCell('A' + r).border = border; ws.getCell('A' + r).font = { bold: true };
        var c1 = ws.getCell('B' + r); c1.value = { formula: 'COUNTIF(' + ref('C') + ',' + q + ')' }; c1.numFmt = '0'; c1.border = border;
        ws.getCell('C' + r).border = border;
        var c2 = ws.getCell('D' + r); c2.value = { formula: 'SUMIFS(' + ref(lt('AF')) + ',' + ref('C') + ',' + q + ')' }; c2.numFmt = fmt; c2.border = border;
        var c3 = ws.getCell('E' + r); c3.value = { formula: 'SUMIFS(' + ref(lt('BT')) + ',' + ref('C') + ',' + q + ')' }; c3.numFmt = fmt; c3.border = border;
        r += 1;
      });
    } else {
      ws.getCell('A' + r).value = '(aucun tronçon saisi)'; ws.getCell('A' + r).font = { italic: true, color: { argb: 'FF66727F' } };
      r += 1;
    }
    r += 1;

    // --- 3. Clé de répartition par sous-réseau ---
    section('3. Clé de répartition par sous-réseau');
    ws.getCell('A' + r).value = 'Clé = Σ(part volume tranchée totale × volume total du tronçon) ÷ Σ(volumes totaux). '
      + 'Le volume des interstices est réparti au prorata de la largeur occupée par chaque sous-réseau.';
    ws.getCell('A' + r).font = { italic: true, size: 8, color: { argb: 'FF66727F' } };
    ws.getCell('A' + r).alignment = { wrapText: true, vertical: 'top' };
    mergeSafe('A' + r, 'K' + r); ws.getRow(r).height = 24;
    r += 1;

    headerRow(['Impétrant', 'Sous-réseau', 'Catégorie', 'Clé de répartition',
      'Longueur attribuée (m)', 'Vol. tranchée câbles (m³)', 'Vol. tranchée conduites (m³)',
      'Vol. tranchée totale (m³)', 'Vol. sable (m³)', 'Vol. remblai s-fond. (m³)', 'Vol. déblais excéd. (m³)']);
    var firstKey = r;
    var btRange = ref(lt('BT'));
    L.repartGroups.forEach(function (gr) {
      var parts = gr.channel.h3.split('\n');
      var isCable = gr.channel.category === 'cable';
      var rowFill = isCable ? cableFill : conduiteFill;
      var partTot = ref(gr.cols[1].letter);
      var cells = [
        { v: parts[0] || '' }, { v: parts[1] || '' }, { v: isCable ? 'Câbles' : 'Conduites' },
        { f: 'IFERROR(SUMPRODUCT(' + partTot + ',' + btRange + ')/SUM(' + btRange + '),0)', nf: '0.00%' },
        { f: 'SUM(' + ref(gr.cols[2].letter) + ')' },
        { f: isCable ? 'SUMPRODUCT(' + partTot + ',' + btRange + ')' : '0' },
        { f: !isCable ? 'SUMPRODUCT(' + partTot + ',' + btRange + ')' : '0' },
        { f: 'F' + r + '+G' + r },
        { f: 'SUM(' + ref(gr.cols[3].letter) + ')' },
        { f: 'SUM(' + ref(gr.cols[4].letter) + ')' },
        { f: 'SUM(' + ref(gr.cols[5].letter) + ')' }
      ];
      cells.forEach(function (spec, i) {
        var c = ws.getCell(colLetter(i + 1) + r);
        if (spec.f !== undefined) { c.value = { formula: spec.f }; c.numFmt = spec.nf || fmt; }
        else c.value = spec.v;
        c.border = border;
        if (i < 3) c.fill = rowFill;
      });
      r += 1;
    });
    // Ligne TOTAL + contrôle
    if (L.repartGroups.length) {
      var lastKey = r - 1;
      ws.getCell('A' + r).value = 'TOTAL'; ws.getCell('A' + r).font = { bold: true };
      ws.getCell('A' + r).fill = sectionFill; ws.getCell('A' + r).border = border;
      mergeSafe('A' + r, 'C' + r);
      ['D', 'E', 'F', 'G', 'H', 'I', 'J', 'K'].forEach(function (colL) {
        var c = ws.getCell(colL + r);
        c.value = { formula: 'SUM(' + colL + firstKey + ':' + colL + lastKey + ')' };
        c.numFmt = colL === 'D' ? '0.00%' : fmt;
        c.font = { bold: true }; c.fill = sectionFill; c.border = border;
      });
      r += 1;
      var ctrl = ws.getCell('A' + r);
      ctrl.value = { formula: 'IF(SUM(D' + firstKey + ':D' + lastKey + ')=0,"(aucun volume)",IF(ABS(SUM(D' + firstKey + ':D' + lastKey + ')-1)<0.0001,"Contrôle : la somme des clés vaut bien 100 %","Contrôle : ATTENTION, la somme des clés ne vaut pas 100 %"))' };
      ctrl.font = { italic: true, size: 9, color: { argb: 'FF0E9F6E' } };
      mergeSafe('A' + r, 'K' + r);
    }
  }

  // Renvoie {value} pour une saisie, {formula} pour une cellule calculée.
  function formulaFor(col, R, L, lt, row) {
    var g = (row && row.geom) || {};
    var gaines = g.gainesCables === 'OUI' && num(g.diamGaine, 0) > 0;
    var D = String(num(g.diamGaine, 0.16));
    // AP / BJ : terres (0), empierrement (=AN-AO / =BH-BI) ou valeur manuelle
    if (col.role === 'AP' || col.role === 'BJ') return remblaiCell(col.role, R, lt, row);
    // AW : câbles sous gaines -> déduction du volume des gaines du sable
    if (col.role === 'AW' && gaines) {
      return { formula: lt('AV') + R + '-(' + lt('AR') + R + '/' + D + ')*PI()*(' + D + '/2)^2*' + lt('AF') + R };
    }
    // BB : hauteur moyenne conduites forcée manuellement
    if (col.role === 'BB' && g.htConduiteMode === 'manuel') {
      return { value: num(g.htConduiteManuelle, 0), input: true };
    }
    // AU : longueur de gaines auto = (largeur câbles / Ø) × longueur
    if (col.role === 'AU' && gaines) {
      return { formula: '(' + lt('AR') + R + '/' + D + ')*' + lt('AF') + R };
    }
    if (col.kind === 'input') {
      return { value: inputValue(col, row) };
    }
    if (col.field === 'calc') return { formula: calcFormula(col.role, R, L, lt) };
    // SI.ERREUR(...;0) : protège la répartition contre les #DIV/0! (volume nul)
    if (col.field === 'rep') return { formula: 'IFERROR(' + repartFormula(col, R, L, lt) + ',0)' };
    return { value: null };
  }

  function remblaiCell(role, R, lt, row) {
    var g = row.geom || {};
    if (role === 'AP') {
      var mc = g.remblaiModeCable || 'terres';
      if (mc === 'empierrement') return { formula: lt('AN') + R + '-' + lt('AO') + R };
      if (mc === 'manuel') return { value: num(g.remblaiSousFondCable, 0) };
      return { value: 0 };
    }
    var mp = g.remblaiModeConduite || 'terres';
    if (mp === 'empierrement') return { formula: lt('BH') + R + '-' + lt('BI') + R };
    if (mp === 'manuel') return { value: num(g.remblaiSousFondConduite, 0) };
    return { value: 0 };
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
