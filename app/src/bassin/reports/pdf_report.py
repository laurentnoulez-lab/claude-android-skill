"""Rapport PDF du dimensionnement (graphiques vectoriels)."""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Sequence, Tuple

from ..core import rainfall
from ..core.model import LIBELLES_SCENARIOS
from . import charts
from .dossier import Dossier, ORDRE_SCENARIOS, synthese_scenarios
from .pdf_writer import (
    BLANC,
    BLEU,
    BLEU_PALE,
    GRIS,
    GRIS_CLAIR,
    NOIR,
    ORANGE_PALE,
    Pdf,
    ROUGE,
    ROUGE_PALE,
    VERT_PALE,
    largeur_texte,
    rgb,
)

_COULEURS_STATUT = {"OK": VERT_PALE, "LIMITE": ORANGE_PALE, "DEBORDEMENT": ROUGE_PALE}


def _couleur(c: charts.Couleur):
    return rgb(*c)


def dessiner_graphique(pdf: Pdf, graphique: charts.Graphique, hauteur: float = 190.0) -> None:
    """Trace un graphique vectoriel dans le flux du document."""
    if not graphique.series or not any(s.points for s in graphique.series):
        return
    pdf.besoin(hauteur + 46)
    if graphique.titre:
        pdf.texte(graphique.titre, 9.5, gras=True, apres=3)
    x0 = pdf.marge + 42
    x1 = pdf.largeur - pdf.marge - 8
    y0 = pdf.y + 6
    y1 = y0 + hauteur
    xmin, xmax, ymin, ymax = charts.bornes(graphique)
    cadre = charts.Cadre(int(x0), int(y0), int(x1), int(y1), xmin, xmax, ymin, ymax, graphique.x_log)

    duree_en_x = graphique.axe_x.lower().startswith(("duree", "durée", "temps"))
    if graphique.x_log:
        ticks: List[float] = []
        d = 10 ** math.floor(math.log10(max(xmin, 1e-9)))
        while d <= xmax * 10:
            for m in (1, 2, 5):
                if xmin <= d * m <= xmax:
                    ticks.append(d * m)
            d *= 10
    else:
        ticks = charts.graduations(xmin, xmax, 6)
    for v in ticks:
        x = cadre.px(v)
        pdf.ligne(x, y0, x, y1, GRIS_CLAIR, 0.4)
        lib = charts.format_duree_courte(v) if duree_en_x else charts.format_nombre(v)
        pdf._texte_brut(x - largeur_texte(lib, 6.5) / 2, y1 + 9, lib, 6.5, GRIS)
    for v in charts.graduations(ymin, ymax, 5):
        y = cadre.py(v)
        pdf.ligne(x0, y, x1, y, GRIS_CLAIR, 0.4)
        lib = charts.format_nombre(v)
        pdf._texte_brut(x0 - 5 - largeur_texte(lib, 6.5), y + 2.2, lib, 6.5, GRIS)

    for s in graphique.series:
        if not s.aire or len(s.points) < 2:
            continue
        base = cadre.py(max(ymin, 0.0))
        pastel = tuple(min(1.0, v + (1.0 - v) * 0.80) for v in _couleur(s.couleur))
        for (xa, ya), (xb, yb) in zip(s.points, s.points[1:]):
            pxa, pxb = cadre.px(xa), cadre.px(xb)
            pya, pyb = cadre.py(ya), cadre.py(yb)
            haut = min(pya, pyb)
            if pxb > pxa:
                pdf.rectangle(pxa, haut, pxb - pxa + 0.4, max(base - haut, 0.1), pastel)
    for r in graphique.reperes:
        coul = _couleur(r.couleur)
        if r.vertical:
            pdf.ligne(cadre.px(r.valeur), y0, cadre.px(r.valeur), y1, coul, 0.7, pointilles=True)
        else:
            pdf.ligne(x0, cadre.py(r.valeur), x1, cadre.py(r.valeur), coul, 0.7, pointilles=True)
    for s in graphique.series:
        coul = _couleur(s.couleur)
        for p0, p1 in zip(s.points, s.points[1:]):
            pdf.ligne(cadre.px(p0[0]), cadre.py(p0[1]), cadre.px(p1[0]), cadre.py(p1[1]), coul,
                      0.9 if s.epaisseur < 3 else 1.4, s.pointilles)
    pdf.ligne(x0, y0, x0, y1, GRIS, 0.6)
    pdf.ligne(x0, y1, x1, y1, GRIS, 0.6)

    pdf._texte_brut(x0 - 38, y0 - 2, graphique.axe_y, 6.5, GRIS)
    pdf._texte_brut(x1 - largeur_texte(graphique.axe_x, 6.5), y1 + 20, graphique.axe_x, 6.5, GRIS)

    x = x0
    y = y1 + 20
    for entree in list(graphique.series) + list(graphique.reperes):
        coul = _couleur(entree.couleur)
        pdf.rectangle(x, y - 5, 10, 5, coul)
        pdf._texte_brut(x + 13, y, entree.nom, 6.5, GRIS)
        x += 16 + largeur_texte(entree.nom, 6.5)
    pdf.y = y1 + 30


def ecrire(dossier: Dossier, chemin: str) -> str:
    """Génère le rapport PDF et renvoie le chemin du fichier."""
    p = dossier.projet
    res = dossier.resultat_principal
    pdf = Pdf()
    pdf.pied = (f"Bassin d'orage - {p.commune_nom} - T = {p.periode_retour} ans - "
                f"{p.nom_projet or 'projet sans nom'} - {dossier.date}")
    L = pdf.largeur_utile

    pdf.titre("Dimensionnement d'un bassin d'orage", 18)
    pdf.texte("Méthode rationnelle - pluies statistiques du GTI (Région wallonne)", 9.5,
              italique=True, couleur=GRIS, apres=10)
    pdf.tableau(
        [
            ["Projet", p.nom_projet or "-", "Commune", f"{p.commune_nom} ({p.commune_ins})"],
            ["Localisation", p.localisation or "-", "Période de retour", f"{p.periode_retour} ans"],
            ["Auteur", p.auteur or "-", "Source des pluies", dossier.libelle_source],
            ["Date", dossier.date, "Scénario retenu", LIBELLES_SCENARIOS[dossier.scenario_principal]],
        ],
        [0.16 * L, 0.31 * L, 0.20 * L, 0.33 * L],
        entete=False,
        taille=8.5,
        fonds={(i, 0): BLEU_PALE for i in range(4)} | {(i, 2): BLEU_PALE for i in range(4)},
    )
    pdf.encadre(
        f"Volume de temporisation : {res.volume_m3:.1f} m³    |    "
        f"Durée de pluie critique : {res.duree_critique_hm}    |    "
        f"Vidange après la pluie : {res.temps_vidange_hm}",
        fond=BLEU_PALE if res.conforme else ROUGE_PALE,
    )

    pdf.titre1("1. Données d'entrée")
    pdf.titre2("1.1 Surfaces incidentes")
    lignes: List[Sequence] = [["Type d'occupation du sol", "Coeff. [-]", "Surface [m²]", "Surface pondérée [m²]"]]
    for s in p.surfaces_non_vides():
        lignes.append([s.libelle, f"{s.coefficient:.2f}", f"{s.aire_m2:.0f}", f"{s.aire_ponderee_m2:.1f}"])
    lignes.append(["TOTAL", f"C moyen = {p.coefficient_moyen:.3f}",
                   f"{p.aire_totale_m2:.0f}", f"{p.aire_ponderee_m2:.1f}"])
    pdf.tableau(lignes, [0.50 * L, 0.16 * L, 0.16 * L, 0.18 * L], taille=8.5,
                fonds={(len(lignes) - 1, j): BLEU_PALE for j in range(4)},
                alignements=["left", "center", "right", "right"])
    pdf.texte(f"Surface de référence du projet : {p.surface_reference_m2:.0f} m²", 8.5, couleur=GRIS)

    pdf.titre2("1.2 Sol, exutoire et contraintes")
    pdf.tableau(
        [
            ["Paramètre", "Valeur", "Unité"],
            ["Coefficient d'infiltration K", f"{p.k_infiltration_ms:.2e}", "m/s"],
            ["Coefficient de sécurité appliqué à K", f"{p.coef_securite_infiltration:.1f}", "-"],
            ["Surface d'infiltration du dispositif", f"{p.surface_infiltration_m2:.1f}", "m²"],
            ["Débit d'infiltration Q = 1000 x S x K / coef.", f"{res.debit_infiltration_ls:.3f}", "l/s"],
            ["Débit d'ajutage (orifice calibré)", f"{p.debit_ajutage_ls:.3f}", "l/s"],
            ["Débit de fuite admissible (5 l/s/ha)", f"{p.debit_fuite_admissible_ls:.3f}", "l/s"],
            ["Temps de vidange maximum admis (après la pluie)", f"{p.temps_vidange_max_h:.0f}", "h"],
        ],
        [0.60 * L, 0.22 * L, 0.18 * L], taille=8.5, alignements=["left", "right", "center"],
    )

    pdf.titre1("2. Pluie de projet")
    if rainfall.a_donnees_montana(p.commune_ins) and p.source_pluie == rainfall.SOURCE_MONTANA:
        a1, b1, a2, b2, a3, b3 = rainfall.montana_coeffs(p.commune_ins, p.periode_retour)
        pdf.texte("Formule de Montana : i [mm/h] = a x t[min] ^ (-b)", 9.0)
        pdf.tableau(
            [["Plage de durée", "a", "b"],
             ["t < 25 min", f"{a1:.1f}", f"{b1:.4f}"],
             ["25 min <= t <= 6000 min", f"{a2:.1f}", f"{b2:.4f}"],
             ["t > 6000 min", f"{a3:.1f}", f"{b3:.4f}"]],
            [0.50 * L, 0.25 * L, 0.25 * L], taille=8.5, alignements=["left", "right", "right"],
        )
    else:
        pdf.texte("Hauteurs de pluie issues des tables QDF du GTI (interpolation logarithmique).", 9.0)
    pdf.puce(f"Pluie critique : {res.hauteur_pluie_mm:.1f} mm en {res.duree_critique_hm} "
             f"(intensité {res.intensite_mmh:.1f} mm/h, soit {res.intensite_ls_ha:.0f} l/s/ha).")
    pdf.puce(f"Débit ruisselle de pointe : {res.debit_entrant_ls:.1f} l/s pour une surface active de "
             f"{p.aire_ponderee_m2:.0f} m².")

    pdf.titre1("3. Comparaison des scénarios")
    pdf.texte("V(t) = h(t) x S_pondérée / 1000 - Q_sortie x t x 60 / 1000 ; le volume retenu est le "
              "maximum sur l'ensemble des durées de pluie.", 8.5, couleur=GRIS)
    synth = synthese_scenarios(dossier)
    fonds: Dict[Tuple[int, int], object] = {}
    for i, s in enumerate(ORDRE_SCENARIOS, start=1):
        if s == dossier.scenario_principal:
            fonds.update({(i, j): BLEU_PALE for j in range(8)})
        elif not dossier.resultats[s].conforme:
            fonds.update({(i, j): ROUGE_PALE for j in range(8)})
    pdf.tableau(synth, [0.28 * L] + [0.103 * L] * 7, taille=7.5, fonds=fonds,
                alignements=["left"] + ["center"] * 7)
    dessiner_graphique(pdf, dossier.graphique_dimensionnement(), 170)

    if res.alertes or res.messages:
        pdf.titre2("3.1 Observations")
        for a in res.alertes:
            pdf.puce(a, couleur=rgb(180, 83, 9))
        for m in res.messages:
            pdf.puce(m, couleur=GRIS)

    if dossier.simulation:
        sim = dossier.simulation
        b = p.bassin
        pdf.nouvelle_page()
        pdf.titre1("4. Vérification de l'ouvrage encodé")
        pdf.tableau(
            [["Caractéristique", "Valeur", "Unité"],
             ["Volume total du bassin", f"{b.volume_total_m3:.1f}", "m³"],
             ["Volume sous l'axe de l'ajutage", f"{b.volume_sous_ajutage_m3:.1f}", "m³"],
             ["Volume tampon au-dessus de l'ajutage", f"{b.volume_tampon_m3:.1f}", "m³"],
             ["Surface de dispersion (fond du bassin)", f"{b.surface_dispersion_m2:.1f}", "m²"],
             ["Débit d'infiltration", f"{sim.q_infiltration_ls:.3f}", "l/s"],
             ["Débit d'ajutage", f"{sim.q_ajutage_ls:.3f}", "l/s"]],
            [0.60 * L, 0.22 * L, 0.18 * L], taille=8.5, alignements=["left", "right", "center"],
        )
        amont = p.amont
        if amont.actif:
            pdf.titre2("4.1 Bassin d'orage amont")
            pdf.texte(
                "Un bassin d'orage situé en amont se déverse dans l'ouvrage étudié. Il reçoit la "
                "meme pluie de projet sur son propre bassin versant, la tamponne, puis la restitue "
                "a son débit de fuite.")
            pdf.tableau(
                [["Caractéristique", "Valeur", "Unité"],
                 ["Surface du bassin versant amont", f"{amont.surface_bv_m2:.0f}", "m²"],
                 ["Coefficient de ruissellement moyen", f"{amont.coef_ruissellement:.2f}", "-"],
                 ["Surface active amont", f"{amont.aire_ponderee_m2:.0f}", "m²"],
                 ["Volume de temporisation amont", f"{amont.volume_temporisation_m3:.1f}", "m³"],
                 ["Surface de dispersion amont", f"{amont.surface_dispersion_m2:.1f}", "m²"],
                 ["Vitesse d'infiltration amont", f"{amont.k_infiltration_ms:.2e}", "m/s"],
                 ["Débit d'ajutage amont", f"{amont.debit_ajutage_ls:.3f}", "l/s"],
                 ["Volume restitué a l'ouvrage aval", f"{sim.volume_amont_m3:.1f}", "m³"],
                 ["Débit de pointe restitué", f"{sim.q_amont_max_ls:.3f}", "l/s"]],
                [0.60 * L, 0.22 * L, 0.18 * L], taille=8.5,
                alignements=["left", "right", "center"],
            )
            if amont.inclure_bv_dans_ajutage:
                pdf.puce(
                    f"Surface du bassin versant amont comptée dans la surface raccordée : "
                    f"{p.aire_raccordee_m2:.0f} m², débit de fuite admissible "
                    f"{p.debit_fuite_admissible_ls:.3f} l/s.")
        pdf.titre2(("4.2 " if amont.actif else "4.1 ") + "Événement critique simule")
        pdf.tableau(
            [["Grandeur", "Valeur", "Grandeur", "Valeur"],
             ["Durée de pluie", f"{sim.duree_pluie_min:.0f} min", "Volume stocké maximum", f"{sim.volume_max_m3:.1f} m³"],
             ["Hauteur de pluie", f"{sim.hauteur_pluie_mm:.1f} mm", "Taux de remplissage", f"{sim.taux_remplissage * 100:.0f} %"],
             ["Volume ruisselé", f"{sim.volume_ruissele_m3:.1f} m³", "Volume déborde", f"{sim.volume_debordement_m3:.2f} m³"],
             ["Temps de vidange", f"{sim.temps_vidange_h:.1f} h", "Statut", sim.statut]],
            [0.27 * L, 0.23 * L, 0.27 * L, 0.23 * L], taille=8.5,
            fonds={(4, 3): ROUGE_PALE if sim.debordement else VERT_PALE},
        )
        g = dossier.graphique_simulation()
        if g:
            dessiner_graphique(pdf, g, 165)
        gd = dossier.graphique_debits()
        if gd:
            dessiner_graphique(pdf, gd, 140)

    if dossier.table:
        table = dossier.table
        pdf.nouvelle_page()
        pdf.titre1("5. Pluies absorbées sans débordement (table QDF)")
        pdf.texte("Volume requis [m³] par pluie. Vert : absorbe par l'ouvrage - orange : limite "
                  "(plus de 95 % de la capacité) - rouge : débordement.", 8.5, couleur=GRIS)
        entete = ["Durée"] + [f"{rp} ans" for rp in table.periodes_retour]
        lignes = [entete]
        fonds = {}
        for i in range(len(table.durees_min)):
            ligne = [rainfall.QDF_DURATION_LABELS[i]]
            for j in range(len(table.periodes_retour)):
                c = table.cellules[i][j]
                ligne.append(f"{c.volume_requis_m3:.1f}")
                fonds[(i + 1, j + 1)] = _COULEURS_STATUT[c.statut]
            lignes.append(ligne)
        largeur_col = (L - 0.10 * L) / len(table.periodes_retour)
        pdf.tableau(lignes, [0.10 * L] + [largeur_col] * len(table.periodes_retour), taille=7.0,
                    fonds=fonds, alignements=["left"] + ["center"] * len(table.periodes_retour))
        rp_max = table.periode_retour_max_acceptee()
        pdf.encadre(
            f"Période de retour maximale absorbée sans débordement : {rp_max} ans" if rp_max
            else "Le bassin déborde déjà pour la pluie de récurrence 2 ans.",
            fond=VERT_PALE if rp_max else ROUGE_PALE,
        )

    if dossier.orifice:
        o = dossier.orifice
        pdf.titre1("6. Dimensionnement de l'ajutage")
        pdf.texte("Orifice en paroi mince - formule de Torricelli : Q = Cd x A x racine(2 g h).", 9.0)
        pdf.tableau(
            [["Grandeur", "Valeur", "Unité"],
             ["Débit d'ajutage visé", f"{o.debit_ls:.3f}", "l/s"],
             ["Charge h (axe de l'orifice -> trop-plein)", f"{o.charge_m:.2f}", "m"],
             ["Coefficient de débit Cd", f"{o.coef_debit:.2f}", "-"],
             ["Section requise", f"{o.section_cm2:.2f}", "cm²"],
             ["Diamètre requis", f"{o.diametre_mm:.1f}", "mm"],
             ["Vitesse dans l'orifice", f"{o.vitesse_ms:.2f}", "m/s"],
             ["Diamètre commercial retenu",
              "-" if o.diametre_commercial_mm is None else f"{o.diametre_commercial_mm:.0f}", "mm"],
             ["Débit réel du diamètre retenu",
              "-" if o.debit_commercial_ls is None else f"{o.debit_commercial_ls:.3f}", "l/s"]],
            [0.60 * L, 0.22 * L, 0.18 * L], taille=8.5, alignements=["left", "right", "center"],
            fonds={(5, 1): BLEU_PALE, (8, 1): VERT_PALE},
        )
        go = dossier.graphique_orifice()
        if go:
            dessiner_graphique(pdf, go, 130)

    pdf.titre1("7. Conclusion")
    pdf.texte(
        f"Pour la commune de {p.commune_nom}, une période de retour de {p.periode_retour} ans et une surface "
        f"active de {p.aire_ponderee_m2:.0f} m², le scénario \"{LIBELLES_SCENARIOS[dossier.scenario_principal]}\" "
        f"conduit à un volume de temporisation de {res.volume_m3:.1f} m³, vidange en {res.temps_vidange_hm}."
    )
    if res.surface_infiltration_min_m2 is not None:
        pdf.puce(f"Surface d'infiltration minimale pour un temps de vidange de "
                 f"{p.temps_vidange_max_h:.0f} h : {res.surface_infiltration_min_m2:.1f} m².")
    if res.debit_ajutage_min_ls is not None:
        pdf.puce(f"Débit d'ajutage minimal pour un temps de vidange de "
                 f"{p.temps_vidange_max_h:.0f} h : {res.debit_ajutage_min_ls:.3f} l/s.")
    if p.remarques:
        pdf.titre2("Remarques")
        pdf.texte(p.remarques)
    pdf.espace(18)
    pdf.texte("Fait à ............................................., le ................................", 9.5)
    pdf.espace(14)
    pdf.texte("Titre et nom : ............................................................................", 9.5)
    pdf.espace(14)
    pdf.texte("Signature :", 9.5)
    return pdf.enregistrer(chemin)
