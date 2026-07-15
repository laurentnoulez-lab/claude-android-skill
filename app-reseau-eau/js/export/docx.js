/*
 * docx.js — Export de la note de calcul au format Word (bibliothèque docx).
 * Structure : page de garde, hypothèses/conventions, références, données,
 * méthode, résultats par tronçon et par nœud, contrôles, schéma en image,
 * réserves.
 */
(function () {
  const st = () => RD.stateModule.state;
  const fmt = (x, d) => RD.units.fmt(x, d);

  function para(texte, opts) {
    return new docx.Paragraph({
      children: [new docx.TextRun({ text: texte, ...(opts && opts.run ? opts.run : {}) })],
      ...(opts && opts.p ? opts.p : {}),
    });
  }
  function titre(texte, niveau) {
    return new docx.Paragraph({
      text: texte,
      heading: niveau === 1 ? docx.HeadingLevel.HEADING_1 : niveau === 2 ? docx.HeadingLevel.HEADING_2 : docx.HeadingLevel.HEADING_3,
      spacing: { before: 240, after: 120 },
    });
  }
  function puces(items) {
    return items.map((i) => new docx.Paragraph({ text: i, bullet: { level: 0 } }));
  }
  function celluleTexte(texte, entete, largeur) {
    return new docx.TableCell({
      children: [new docx.Paragraph({
        children: [new docx.TextRun({ text: String(texte), bold: !!entete, size: 16 })],
      })],
      shading: entete ? { fill: 'DCE6F1' } : undefined,
      width: largeur ? { size: largeur, type: docx.WidthType.PERCENTAGE } : undefined,
    });
  }
  function tableau(lignes) {
    return new docx.Table({
      width: { size: 100, type: docx.WidthType.PERCENTAGE },
      rows: lignes.map((l, i) => new docx.TableRow({
        children: l.map((c) => celluleTexte(c, i === 0)),
        tableHeader: i === 0,
      })),
    });
  }

  function dataURLVersUint8(dataURL) {
    const base64 = dataURL.split(',')[1];
    const brut = atob(base64);
    const octets = new Uint8Array(brut.length);
    for (let i = 0; i < brut.length; i++) octets[i] = brut.charCodeAt(i);
    return octets;
  }

  async function exporter() {
    const p = st().projet;
    const r = st().resultats;
    const diam = st().diametres;
    const controles = st().controles || [];
    if (!r || !diam) { alert('Lancez d’abord le calcul (étape ④).'); return; }

    const s = RD.rapport.sectionsTexte(p);
    const m = p.meta;
    const pire = RD.checks.noeudLePlusDefavorable(p, r);
    const inc = r.casIncendie;
    const png = await RD.rapport.schemaEnPNG();

    const enfants = [];

    // ── Page de garde ──
    enfants.push(
      new docx.Paragraph({ text: '', spacing: { before: 2400 } }),
      new docx.Paragraph({
        children: [new docx.TextRun({ text: 'NOTE DE CALCUL', bold: true, size: 56 })],
        alignment: docx.AlignmentType.CENTER,
      }),
      new docx.Paragraph({
        children: [new docx.TextRun({ text: 'Dimensionnement hydraulique d’un réseau de distribution d’eau potable — avant-projet', size: 28 })],
        alignment: docx.AlignmentType.CENTER, spacing: { after: 600 },
      }),
      tableau([
        ['Projet', m.nom || '—'],
        ['Maître d’ouvrage', m.maitreOuvrage || '—'],
        ['Distributeur', m.distributeur || '—'],
        ['Bureau d’études', m.bureau || '—'],
        ['Auteur de projet', m.auteur || '—'],
        ['Version / indice', m.indice || '—'],
        ['Date', m.date || '—'],
        ['Calcul du', new Date(r.horodatage).toLocaleString('fr-BE')],
      ]),
      new docx.Paragraph({ text: '', spacing: { before: 400 } }),
      para(RD.checks.MENTION_AVANT_PROJET, { run: { bold: true, color: '990000' } }),
      new docx.Paragraph({ children: [], pageBreakBefore: false }),
      new docx.Paragraph({ text: '', pageBreakBefore: true })
    );

    // ── 1. Hypothèses et conventions ──
    enfants.push(titre('1. Hypothèses et conventions', 1));
    enfants.push(titre('1.1 Conventions', 2), ...puces(s.conventions));
    enfants.push(titre('1.2 Hypothèses de calcul', 2));
    const lignesHyp = [
      ['Hypothèse', 'Valeur', 'Source / justification'],
      ['Coefficient de pointe Cp', String(p.hypotheses.coeffPointe), 'À confirmer par le distributeur'],
      ['Durée de distribution', `${p.hypotheses.dureeDistribution} h/j`, 'Profil d’activité des abonnés'],
      ['Débit incendie par hydrant', `${p.hypotheses.debitIncendie} m³/h`, 'CM 14/10/1975 (1 000 l/min) — à confirmer zone de secours'],
      ['Hydrants simultanés', p.hypotheses.deuxHydrants ? '2' : '1', ''],
      ['Pression minimale exigée', p.hypotheses.pressionMinimale != null ? `${p.hypotheses.pressionMinimale} bar` : 'NON RENSEIGNÉE', 'À confirmer PAR ÉCRIT — distributeur / zone de secours'],
      ['Viscosité cinématique ν', `${p.hypotheses.nu.toExponential(2)} m²/s`, 'Eau à ~10 °C'],
      ['Pertes singulières globales', `${p.hypotheses.pertesSingulieresPct || 0} % des pertes linéaires`, '+ ΣK par tronçon'],
    ];
    for (const [id, mm] of Object.entries(RD.materials.MATERIAUX)) {
      lignesHyp.push([`Rugosité k — ${mm.nom}`, `${RD.materials.rugosite(id, p.hypotheses.rugosites)} mm`, 'Hypothèse prudente « en service » — à justifier']);
    }
    enfants.push(tableau(lignesHyp));
    enfants.push(titre('1.3 Cas de charge', 2), ...puces(s.casDeCharge));
    enfants.push(titre('1.4 Alimentation', 2),
      para(p.alimentation.mode === 'essai'
        ? `Essai débit-pression au piquage : P0 = ${p.alimentation.p0} bar (statique) ; Q1 = ${p.alimentation.q1} m³/h → P1 = ${p.alimentation.p1} bar. Extrapolation P(Q) = P0 − (P0 − P1)·(Q/Q1)^1,852 (règle de l’art, exposant Hazen-Williams).`
        : `Pression fixée au piquage : ${p.alimentation.p0} bar (hypothèse forte à valider par le distributeur).`));

    // ── 2. Méthode ──
    enfants.push(titre('2. Méthode de calcul', 1), ...puces(s.methode));

    // ── 3. Références ──
    enfants.push(titre('3. Références', 1), ...puces(s.references));

    // ── 4. Schéma ──
    enfants.push(titre('4. Schéma du réseau', 1));
    if (png) {
      enfants.push(new docx.Paragraph({
        children: [new docx.ImageRun({ data: dataURLVersUint8(png), transformation: { width: 620, height: 381 } })],
      }));
    }
    enfants.push(para('Schéma non à l’échelle — longueurs saisies manuellement.', { run: { italics: true } }));

    // ── 5. Données d'entrée ──
    enfants.push(titre('5. Données d’entrée', 1));
    enfants.push(titre('5.1 Nœuds', 2), tableau([
      ['Nœud', 'Cote (m)', 'Conso (m³/j)', 'Hydrant', 'Rôle'],
      ...p.noeuds.map((n) => [
        n.nom || n.id, fmt(n.cote, 1), n.consommation ? fmt(n.consommation, 1) : '—',
        n.hydrant ? 'oui' : '—', p.alimentation.noeudId === n.id ? 'alimentation (piquage)' : (n.type || ''),
      ]),
    ]));
    const nn = (id) => { const n = p.noeuds.find((x) => x.id === id); return n ? (n.nom || n.id) : id; };
    enfants.push(titre('5.2 Tronçons', 2), tableau([
      ['Tronçon', 'Liaison', 'L (m)', 'Matériau', 'k (mm)', 'ΣK'],
      ...p.troncons.map((t) => [
        t.nom || t.id, `${nn(t.de)} → ${nn(t.vers)}`, String(t.longueur),
        RD.materials.MATERIAUX[t.materiau].nom,
        String(RD.materials.rugosite(t.materiau, p.hypotheses.rugosites)), String(t.sommeK || 0),
      ]),
    ]));

    // ── 6. Résultats ──
    const lt = RD.rapport.lignesTroncons(p, r, diam);
    const ln = RD.rapport.lignesNoeuds(p, r);
    enfants.push(titre('6. Résultats', 1));
    enfants.push(titre('6.1 Débits des cas de charge', 2),
      para(`Cas 1 (pointe) : ${fmt(r.casPointe.qTotal, 2)} m³/h = ${fmt(r.casPointe.qTotal / 3.6, 2)} l/s.`),
      ...(inc ? [para(`Cas 2 (incendie + consommation moyenne, scénario critique — hydrant(s) ${inc.combo.join(' + ')}) : ${fmt(inc.resultat.qTotal, 2)} m³/h = ${fmt(inc.resultat.qTotal / 3.6, 2)} l/s.`)] : []),
      ...(inc && inc.resultat.equilibrage ? [para(`Équilibrage Hardy Cross : ${inc.resultat.equilibrage.iterations} itérations, ${inc.resultat.equilibrage.converge ? 'convergé' : 'NON CONVERGÉ'} (correction max ${inc.resultat.equilibrage.maxCorrection.toExponential(2)} m³/h).`)] : []));
    enfants.push(titre('6.2 Résultats par tronçon', 2), tableau([
      ['Tronçon', 'L (m)', 'Diamètre retenu', 'Q dim. (m³/h)', 'Cas', 'v pointe (m/s)', 'v inc. (m/s)', 'ΔH pointe (mCE)', 'ΔH inc. (mCE)', 'λ'],
      ...lt.map((l) => [
        l.nom, String(l.longueur), l.diametre, fmt(l.qDim, 2), l.origine,
        fmt(l.vPointe, 2), l.vInc !== null ? fmt(l.vInc, 2) : '—',
        fmt(l.dHPointe, 3), l.dHInc !== null ? fmt(l.dHInc, 3) : '—', fmt(l.lambda, 4),
      ]),
    ]));
    enfants.push(titre('6.3 Résultats par nœud', 2), tableau([
      ['Nœud', 'Cote (m)', 'P pointe (bar)', 'P incendie (bar)'],
      ...ln.map((l) => [
        `${l.nom}${l.alim ? ' (alimentation)' : ''}${l.hydrant ? ' (hydrant)' : ''}`,
        fmt(l.cote, 1), fmt(l.pPointe, 2), l.pInc !== null ? fmt(l.pInc, 2) : '—',
      ]),
    ]));
    if (pire) {
      enfants.push(para(`Nœud le plus défavorable : ${pire.id} — ${fmt(pire.p, 2)} bar (cas ${pire.cas})${p.hypotheses.pressionMinimale != null ? ` pour une exigence de ${p.hypotheses.pressionMinimale} bar` : ''}.`, { run: { bold: true } }));
    }

    // ── 7. Contrôles ──
    enfants.push(titre('7. Contrôles et avertissements', 1));
    enfants.push(controles.length
      ? tableau([
          ['Niveau', 'Contrôle'],
          ...controles.map((c) => [`${c.niveau.toUpperCase()}${c.bloquant ? ' (BLOQUANT)' : ''}`, c.message]),
        ])
      : para('Aucune observation.'));

    // ── 8. Réserves ──
    enfants.push(titre('8. Réserves', 1), ...puces(s.reserves));

    const doc = new docx.Document({
      creator: m.auteur || 'Dimensionnement réseau eau',
      title: `Note de calcul — ${m.nom || ''}`,
      description: 'Note de calcul d’avant-projet générée par l’application de dimensionnement de réseau de distribution d’eau.',
      styles: {
        default: { document: { run: { font: 'Calibri', size: 20 } } },
        paragraphStyles: [
          { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
            run: { size: 30, bold: true, color: '0B5394' } },
          { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
            run: { size: 24, bold: true, color: '0B5394' } },
        ],
      },
      sections: [{ properties: {}, children: enfants }],
    });

    const blob = await docx.Packer.toBlob(doc);
    const nom = (m.nom || 'projet').replace(/[^\wàâäéèêëïîôöùûüç -]/gi, '_');
    RD.stateModule.telechargerBlob(blob, `${nom} — note de calcul.docx`);
  }

  const D = { exporter };
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.exportDocx = D;
})();
