/*
 * xlsx.js — Export du classeur Excel à FORMULES VIVANTES.
 *
 * Principe : les colonnes v, Re, λ, ΔH de l'onglet « Tronçons » sont des
 * FORMULES Excel (Darcy-Weisbach + Swamee-Jain) qui référencent les cellules
 * de l'onglet « Hypothèses » (ν, pertes singulières, rugosités) et les
 * cellules d'entrée de la ligne (L, Di, ΣK, Q). Modifier une hypothèse dans
 * Excel recalcule immédiatement le classeur.
 *
 * Limite documentée (en tête d'onglet) : les DÉBITS de tronçon proviennent
 * de l'équilibrage Hardy Cross réalisé par l'application — Excel ne refait
 * pas l'itération d'équilibrage. Ils sont saisis comme valeurs d'entrée
 * (modifiables pour des études de sensibilité).
 *
 * Convention de style : cellules d'ENTRÉE en fond jaune / police bleue ;
 * formules en police noire. (Bibliothèque : xlsx-js-style.)
 */
(function () {
  const st = () => RD.stateModule.state;

  // Styles
  const S_TITRE = { font: { bold: true, sz: 13, color: { rgb: '0B5394' } } };
  const S_ENTETE = { font: { bold: true, color: { rgb: 'FFFFFF' } }, fill: { patternType: 'solid', fgColor: { rgb: '0B5394' } }, border: bordures() };
  const S_ENTREE = { fill: { patternType: 'solid', fgColor: { rgb: 'FFF2CC' } }, font: { color: { rgb: '0000CC' } }, border: bordures() };
  const S_FORMULE = { font: { color: { rgb: '000000' } }, border: bordures() };
  const S_TEXTE = { border: bordures() };
  const S_NOTE = { font: { italic: true, sz: 9, color: { rgb: '666666' } } };

  function bordures() {
    const b = { style: 'thin', color: { rgb: 'AAAAAA' } };
    return { top: b, bottom: b, left: b, right: b };
  }

  function cellule(v, style, opts) {
    const c = { v, s: style, ...(opts || {}) };
    if (typeof v === 'number') c.t = 'n';
    else if (typeof v === 'boolean') c.t = 'b';
    else c.t = 's';
    return c;
  }
  function formule(f, style, numFmt) {
    return { t: 'n', f, s: { ...style, ...(numFmt ? { numFmt } : {}) } };
  }

  function feuilleDepuisLignes(lignes, largeurs) {
    const ws = {};
    let maxC = 0;
    lignes.forEach((ligne, r) => {
      ligne.forEach((cell, c) => {
        if (cell === null || cell === undefined) return;
        ws[XLSX.utils.encode_cell({ r, c })] = cell;
        maxC = Math.max(maxC, c);
      });
    });
    ws['!ref'] = XLSX.utils.encode_range({ s: { r: 0, c: 0 }, e: { r: lignes.length - 1, c: maxC } });
    if (largeurs) ws['!cols'] = largeurs.map((w) => ({ wch: w }));
    return ws;
  }

  function exporter() {
    const p = st().projet;
    const r = st().resultats;
    const diam = st().diametres;
    if (!r || !diam) { alert('Lancez d’abord le calcul (étape ④).'); return; }

    const wb = XLSX.utils.book_new();

    /* ── Onglet Hypothèses ────────────────────────────────────────────── */
    const matIds = Object.keys(RD.materials.MATERIAUX);
    const lignesH = [
      [cellule('HYPOTHÈSES DE CALCUL — cellules jaunes/bleues = entrées modifiables', S_TITRE)],
      [cellule('Pressions relatives (manométriques) en bar — 1 bar = 10,197 mCE — 1 l/s = 3,6 m³/h', S_NOTE)],
      [],
      [cellule('Hypothèse', S_ENTETE), cellule('Valeur', S_ENTETE), cellule('Unité', S_ENTETE), cellule('Source / justification', S_ENTETE)],
      [cellule('Viscosité cinématique ν', S_TEXTE), cellule(p.hypotheses.nu, S_ENTREE, { z: '0.00E+00' }), cellule('m²/s', S_TEXTE), cellule('Eau à ~10 °C (défaut 1,31E-06)', S_TEXTE)],
      [cellule('Coefficient de pointe Cp', S_TEXTE), cellule(p.hypotheses.coeffPointe, S_ENTREE), cellule('—', S_TEXTE), cellule('À confirmer par le distributeur', S_TEXTE)],
      [cellule('Durée de distribution', S_TEXTE), cellule(p.hypotheses.dureeDistribution, S_ENTREE), cellule('h/j', S_TEXTE), cellule('Profil d’activité', S_TEXTE)],
      [cellule('Débit incendie par hydrant', S_TEXTE), cellule(p.hypotheses.debitIncendie, S_ENTREE), cellule('m³/h', S_TEXTE), cellule('CM 14/10/1975 : 60 m³/h = 1 000 l/min — à confirmer zone de secours', S_TEXTE)],
      [cellule('Pression minimale exigée', S_TEXTE),
        p.hypotheses.pressionMinimale != null ? cellule(p.hypotheses.pressionMinimale, S_ENTREE) : cellule('À COMPLÉTER', S_ENTREE),
        cellule('bar', S_TEXTE), cellule('À confirmer PAR ÉCRIT — distributeur / zone de secours (pas de défaut)', S_TEXTE)],
      [cellule('Pertes singulières globales', S_TEXTE), cellule(p.hypotheses.pertesSingulieresPct || 0, S_ENTREE), cellule('% des pertes linéaires', S_TEXTE), cellule('Forfait avant-projet (0 % si ΣK détaillé)', S_TEXTE)],
      [cellule('Pesanteur g', S_TEXTE), cellule(9.81, S_ENTREE), cellule('m/s²', S_TEXTE), cellule('', S_TEXTE)],
      [],
      [cellule('Rugosités k par matériau (mm)', S_ENTETE), cellule('k (mm)', S_ENTETE), cellule('', S_ENTETE), cellule('Hypothèses prudentes « en service » — à justifier', S_ENTETE)],
    ];
    const ligneRugosite = {}; // materiauId → numéro de ligne Excel (1-based)
    matIds.forEach((id, i) => {
      ligneRugosite[id] = lignesH.length + 1;
      lignesH.push([
        cellule(RD.materials.MATERIAUX[id].nom, S_TEXTE),
        cellule(RD.materials.rugosite(id, p.hypotheses.rugosites), S_ENTREE),
        cellule('mm', S_TEXTE),
        cellule(`Défaut : ${RD.materials.MATERIAUX[id].kDefaut} mm`, S_TEXTE),
      ]);
    });
    // Adresses réutilisées par les autres onglets
    const H = {
      nu: 'Hypothèses!$B$5', cp: 'Hypothèses!$B$6', duree: 'Hypothèses!$B$7',
      qInc: 'Hypothèses!$B$8', pmin: 'Hypothèses!$B$9', pctSing: 'Hypothèses!$B$10', g: 'Hypothèses!$B$11',
      k: (mid) => `Hypothèses!$B$${ligneRugosite[mid]}`,
    };
    XLSX.utils.book_append_sheet(wb, feuilleDepuisLignes(lignesH, [34, 14, 20, 60]), 'Hypothèses');

    /* ── Onglet Nœuds ─────────────────────────────────────────────────── */
    const comboCritique = r.casIncendie ? r.casIncendie.combo : [];
    const lignesN = [
      [cellule('NŒUDS — demandes recalculées par formules ; pressions issues du calcul de réseau de l’application', S_TITRE)],
      [cellule('Les pressions résultent de la propagation des pertes de charge sur le réseau (Hardy Cross en maillé) : elles ne sont pas recalculées par Excel.', S_NOTE)],
      [],
      [ 'ID', 'Nom', 'Cote (m)', 'Conso (m³/j)', 'Hydrant', 'Hydrant sollicité (cas incendie critique : 1/0)',
        'Demande pointe (m³/h)', 'Demande incendie (m³/h)', 'P pointe (bar)', 'P incendie (bar)' ].map((t) => cellule(t, S_ENTETE)),
    ];
    p.noeuds.forEach((n, i) => {
      const lig = lignesN.length + 1; // ligne Excel 1-based de cette ligne de données
      const np = r.casPointe.noeuds.get(n.id);
      const ni = r.casIncendie ? r.casIncendie.resultat.noeuds.get(n.id) : null;
      lignesN.push([
        cellule(n.id, S_TEXTE), cellule(n.nom || n.id, S_TEXTE),
        cellule(n.cote || 0, S_ENTREE), cellule(n.consommation || 0, S_ENTREE),
        cellule(n.hydrant ? 'oui' : '—', S_TEXTE),
        cellule(comboCritique.includes(n.id) ? 1 : 0, S_ENTREE),
        formule(`D${lig}/${H.duree}*${H.cp}`, S_FORMULE, '0.000'),
        formule(`D${lig}/24+F${lig}*${H.qInc}`, S_FORMULE, '0.000'),
        cellule(Number(np.p.toFixed(3)), S_FORMULE),
        ni ? cellule(Number(ni.p.toFixed(3)), S_FORMULE) : cellule('—', S_TEXTE),
      ]);
    });
    XLSX.utils.book_append_sheet(wb, feuilleDepuisLignes(lignesN, [8, 22, 9, 11, 8, 14, 14, 14, 12, 12]), 'Nœuds');

    /* ── Onglet Tronçons (formules Darcy-Weisbach / Swamee-Jain) ─────── */
    const lignesT = [
      [cellule('TRONÇONS — v, Re, λ (Swamee-Jain), ΔH (Darcy-Weisbach) : FORMULES VIVANTES référencant l’onglet Hypothèses', S_TITRE)],
      [cellule('Q = débit dimensionnant issu de l’équilibrage du réseau par l’application (Hardy Cross en maillé) : valeur d’entrée modifiable pour étude de sensibilité, mais non ré-équilibrée par Excel.', S_NOTE)],
      [],
      [ 'ID', 'Nom', 'De', 'Vers', 'L (m)', 'Matériau', 'Di (mm)', 'k (mm)', 'ΣK',
        'Q (m³/h)', 'Cas dimensionnant', 'Q (l/s)', 'v (m/s)', 'Re', 'λ', 'ΔH lin (mCE)', 'ΔH sing (mCE)', 'ΔH tot (mCE)', 'ΔH (bar)' ].map((t) => cellule(t, S_ENTETE)),
    ];
    p.troncons.forEach((t) => {
      const lig = lignesT.length + 1;
      const d = diam.get(t.id);
      const dm = r.dimensionnant.get(t.id);
      // v = Q[m³/s] / (π/4·D²) ; Re = v·D/ν ; λ = 0,25/log10(k/3,7D + 5,74/Re^0,9)² ;
      // ΔH = λ·L/D·v²/2g (+ singulières : % des linéaires + ΣK·v²/2g)
      const D = `(G${lig}/1000)`;
      const V = `M${lig}`;
      lignesT.push([
        cellule(t.id, S_TEXTE), cellule(t.nom || t.id, S_TEXTE),
        cellule(t.de, S_TEXTE), cellule(t.vers, S_TEXTE),
        cellule(t.longueur, S_ENTREE),
        cellule(RD.materials.MATERIAUX[t.materiau].nom, S_TEXTE),
        cellule(d.di, S_ENTREE),
        formule(H.k(t.materiau), S_FORMULE, '0.000'),
        cellule(t.sommeK || 0, S_ENTREE),
        cellule(Number(dm.Q.toFixed(4)), S_ENTREE),
        cellule(dm.origine, S_TEXTE),
        formule(`J${lig}/3.6`, S_FORMULE, '0.00'),
        formule(`(J${lig}/3600)/(PI()/4*${D}^2)`, S_FORMULE, '0.000'),
        formule(`${V}*${D}/${H.nu}`, S_FORMULE, '0'),
        formule(`0.25/(LOG10((H${lig}/1000)/(3.7*${D})+5.74/N${lig}^0.9))^2`, S_FORMULE, '0.0000'),
        formule(`O${lig}*(E${lig}/${D})*${V}^2/(2*${H.g})`, S_FORMULE, '0.000'),
        formule(`P${lig}*${H.pctSing}/100+I${lig}*${V}^2/(2*${H.g})`, S_FORMULE, '0.000'),
        formule(`P${lig}+Q${lig}`, S_FORMULE, '0.000'),
        formule(`R${lig}/10.197`, S_FORMULE, '0.000'),
      ]);
    });
    XLSX.utils.book_append_sheet(wb, feuilleDepuisLignes(lignesT,
      [7, 14, 7, 7, 8, 30, 9, 8, 6, 10, 22, 9, 8, 11, 9, 12, 12, 12, 9]), 'Tronçons');

    /* ── Onglet Contrôles ─────────────────────────────────────────────── */
    const lignesC = [
      [cellule('CONTRÔLES — formules vivantes sur les tronçons ; pression au nœud critique reprise du calcul réseau', S_TITRE)],
      [],
      [ 'Contrôle', 'Tronçon', 'Référence', 'Résultat' ].map((t) => cellule(t, S_ENTETE)),
    ];
    p.troncons.forEach((t, i) => {
      const ligT = 5 + i; // ligne du tronçon dans l'onglet Tronçons
      const porteH = RD.sizing.porteHydrant(t, p);
      lignesC.push([
        cellule('Ø intérieur ≥ 100 mm si hydrant (CM 14/10/1975 — BLOQUANT)', S_TEXTE),
        cellule(t.nom || t.id, S_TEXTE),
        cellule(porteH ? 'porte un hydrant' : 'sans hydrant', S_TEXTE),
        porteH
          ? formule(`IF(Tronçons!G${ligT}>=100,"OK","NOK — Ø < 100 mm")`, S_FORMULE)
          : cellule('sans objet', S_TEXTE),
      ]);
    });
    p.troncons.forEach((t, i) => {
      const ligT = 5 + i;
      lignesC.push([
        cellule('Vitesse (plage indicative 0,5–1,5 m/s ; > 2 m/s : coup de bélier)', S_TEXTE),
        cellule(t.nom || t.id, S_TEXTE),
        cellule('au débit dimensionnant', S_TEXTE),
        formule(`IF(Tronçons!M${ligT}>2,"AVERTISSEMENT — v > 2 m/s",IF(Tronçons!M${ligT}<0.3,"AVERTISSEMENT — v < 0,3 m/s (stagnation en pointe à vérifier)","OK"))`, S_FORMULE),
      ]);
    });
    const pire = RD.checks.noeudLePlusDefavorable(p, r);
    if (pire) {
      lignesC.push([
        cellule('Pression résiduelle au nœud le plus défavorable ≥ pression minimale', S_TEXTE),
        cellule(`${pire.id} (cas ${pire.cas})`, S_TEXTE),
        cellule('valeur issue du calcul réseau de l’application', S_TEXTE),
        formule(`IF(ISNUMBER(${H.pmin}),IF(${Number(pire.p.toFixed(3))}>=${H.pmin},"OK — ${pire.p.toFixed(2)} bar","NOK — ${pire.p.toFixed(2)} bar < exigence"),"Pression minimale à compléter (onglet Hypothèses)")`, S_FORMULE),
      ]);
    }
    lignesC.push([]);
    lignesC.push([cellule(RD.checks.MENTION_AVANT_PROJET, S_NOTE)]);
    XLSX.utils.book_append_sheet(wb, feuilleDepuisLignes(lignesC, [52, 16, 30, 40]), 'Contrôles');

    const nom = (p.meta.nom || 'projet').replace(/[^\wàâäéèêëïîôöùûüç -]/gi, '_');
    XLSX.writeFile(wb, `${nom} — note de calcul.xlsx`);
  }

  const X = { exporter };
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.exportXlsx = X;
})();
