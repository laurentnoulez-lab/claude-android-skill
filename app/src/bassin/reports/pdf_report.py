"""Rapport PDF du dimensionnement (graphiques vectoriels)."""

from __future__ import annotations

import math
from typing import Dict, List, Optional, Sequence, Tuple

from ..core import hydro, rainfall
from ..core.model import LIBELLES_SCENARIOS
from . import charts, schema as mod_schema
from .dossier import (Dossier, ORDRE_SCENARIOS, synthese_reseau, synthese_scenarios,
                      synthese_simulation_systeme, synthese_versants)
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
    pdf.besoin(hauteur + 64)
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
    libelles = (charts.etiquettes_de_temps(ticks) if duree_en_x
                else [charts.format_nombre(v) for v in ticks])
    for v, lib in zip(ticks, libelles):
        x = cadre.px(v)
        pdf.ligne(x, y0, x, y1, GRIS_CLAIR, 0.4)
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
    largeur_axe = largeur_texte(graphique.axe_x, 6.5)
    pdf._texte_brut(x1 - largeur_axe, y1 + 20, graphique.axe_x, 6.5, GRIS)

    # La légende se replie dès qu'elle atteint le libellé de l'axe : sur la
    # première ligne elle doit lui laisser la place, sur les suivantes elle
    # dispose de toute la largeur.
    x = x0
    y = y1 + 20
    for entree in list(graphique.series) + list(graphique.reperes):
        largeur_entree = 16 + largeur_texte(entree.nom, 6.5)
        limite = (x1 - largeur_axe - 12) if abs(y - (y1 + 20)) < 1e-9 else x1
        if x > x0 and x + largeur_entree > limite:
            x = x0
            y += 9
        coul = _couleur(entree.couleur)
        pdf.rectangle(x, y - 5, 10, 5, coul)
        pdf._texte_brut(x + 13, y, entree.nom, 6.5, GRIS)
        x += largeur_entree
    pdf.y = y + 10


_COULEURS_BOITE = {
    mod_schema.VERSANT: (VERT_PALE, rgb(5, 150, 105)),
    mod_schema.OUVRAGE: (BLEU_PALE, BLEU),
    mod_schema.EXUTOIRE: (GRIS_CLAIR, GRIS),
}

#: En dessous, les étiquettes du schéma deviennent illisibles : on lui préfère
#: alors l'arbre indenté, qui reste exact quelle que soit la taille du réseau.
TAILLE_MINIMALE_SCHEMA = 4.2


def dessiner_schema(pdf: Pdf, schema: mod_schema.Schema) -> bool:
    """Trace le schéma du réseau au vecteur. Faux s'il ne tient pas lisiblement.

    La géométrie vient de :mod:`bassin.reports.schema`, la même qu'à l'écran :
    le rapport et l'application montrent le même schéma, aux mêmes places.
    """
    if not schema.boites or schema.largeur <= 0:
        return False
    place = pdf.hauteur - pdf.marge - 40 - pdf.y
    if place < 120:
        pdf.nouvelle_page()
        place = pdf.hauteur - 2 * pdf.marge - 40
    echelle = min(pdf.largeur_utile / schema.largeur, place / max(schema.hauteur, 1.0), 1.0)
    if mod_schema.HAUTEUR_LIGNE * echelle * 0.75 < TAILLE_MINIMALE_SCHEMA:
        return False

    x0 = pdf.marge
    y0 = pdf.y + 4

    def px(x: float) -> float:
        return x0 + x * echelle

    def py(y: float) -> float:
        return y0 + y * echelle

    for fleche in schema.fleches:
        couleur = rgb(217, 119, 6) if fleche.pointille else GRIS
        for (xa, ya), (xb, yb) in zip(fleche.points, fleche.points[1:]):
            pdf.ligne(px(xa), py(ya), px(xb), py(yb), couleur, 0.7, fleche.pointille)
        if fleche.points:
            # Pointe de flèche : deux petits traits à l'arrivée.
            xf, yf = fleche.points[-1]
            pdf.ligne(px(xf), py(yf), px(xf) - 4, py(yf) - 2.4, couleur, 0.7)
            pdf.ligne(px(xf), py(yf), px(xf) - 4, py(yf) + 2.4, couleur, 0.7)

    for boite in schema.boites:
        fond, bord = _COULEURS_BOITE.get(boite.genre, (GRIS_CLAIR, GRIS))
        if boite.genre == mod_schema.OUVRAGE and boite.statut == "DEBORDEMENT":
            fond, bord = ROUGE_PALE, ROUGE
        pdf.rectangle(px(boite.x), py(boite.y), boite.largeur * echelle,
                      boite.hauteur * echelle, fond, bord, 0.5)
        taille_titre = max(mod_schema.HAUTEUR_TITRE * echelle * 0.58, 3.6)
        taille_ligne = max(mod_schema.HAUTEUR_LIGNE * echelle * 0.72, 3.4)
        marge = mod_schema.MARGE_BOITE * echelle
        y = py(boite.y) + marge + taille_titre
        pdf._texte_brut(px(boite.x) + marge, y, boite.titre, taille_titre, bord, True)
        for ligne in boite.lignes:
            y += mod_schema.HAUTEUR_LIGNE * echelle
            pdf._texte_brut(px(boite.x) + marge, y, ligne, taille_ligne, NOIR)

    pdf.y = py(schema.hauteur) + 12
    # Légende : les flèches ne portent pas d'étiquette (elles se gêneraient dès
    # que deux ouvrages se déversent au même endroit), c'est elle qui explique.
    x = pdf.marge
    y = pdf.y
    for libelle, fond, bord, pointille in (
            ("bassin versant", VERT_PALE, rgb(5, 150, 105), False),
            ("bassin d'orage", BLEU_PALE, BLEU, False),
            ("bassin qui surverse", ROUGE_PALE, ROUGE, False),
            ("exutoire", GRIS_CLAIR, GRIS, False),
            ("surverse vers le milieu naturel", None, rgb(217, 119, 6), True)):
        if fond is None:
            pdf.ligne(x, y - 2, x + 12, y - 2, bord, 0.8, True)
        else:
            pdf.rectangle(x, y - 5.5, 12, 6, fond, bord, 0.4)
        pdf._texte_brut(x + 16, y, libelle, 6.5, GRIS)
        x += 22 + largeur_texte(libelle, 6.5)
    pdf.y = y + 10
    return True


def section_reseau(pdf: Pdf, dossier: Dossier, numero: int) -> None:
    """Synthèse du réseau : schéma, ouvrages, bassins versants, simulation."""
    systeme = dossier.systeme
    L = pdf.largeur_utile
    pdf.nouvelle_page()
    pdf.titre1(f"{numero}. Synthèse du réseau")
    schema = mod_schema.construire(systeme, dossier.fiches, dossier.simulation_systeme)
    pdf.texte(schema.sous_titre, 9.0, gras=True, couleur=BLEU)
    if not dessiner_schema(pdf, schema):
        pdf.texte("Le réseau est trop étendu pour être représenté lisiblement sur une page : "
                  "il est décrit ci-dessous sous forme d'arbre.", 8.5, italique=True,
                  couleur=GRIS)
        for ligne in mod_schema.arbre_texte(systeme, dossier.fiches):
            pdf.texte(ligne, 8.0, couleur=NOIR, interligne=1.25, apres=0)
        pdf.espace(8)
    for note in schema.notes:
        pdf.puce(note, 8.5, couleur=GRIS)

    pdf.titre2(f"{numero}.1 Bassins versants")
    lignes = synthese_versants(dossier)
    colonnes = len(lignes[0]) - 1
    pdf.tableau(lignes, [0.26 * L] + [(0.74 / colonnes) * L] * colonnes, taille=7.5,
                fonds={(len(lignes) - 1, j): BLEU_PALE for j in range(len(lignes[0]))},
                alignements=["left", "left"] + ["right"] * (colonnes - 1))

    pdf.titre2(f"{numero}.2 Dimensionnement de chaque bassin d'orage")
    pdf.texte("Chaque ouvrage est dimensionné sur ses propres bassins versants et sur ce que "
              "lui restituent les ouvrages amont, tels qu'ils sont encodés. Un ouvrage amont "
              "sous-dimensionné surverse : son trop-plein arrive sans laminage et gonfle le "
              "volume à prévoir en aval.", 8.5, couleur=GRIS)
    lignes = synthese_reseau(dossier)
    fonds: Dict[Tuple[int, int], object] = {}
    for i, fiche in enumerate(dossier.fiches, start=1):
        if not fiche.suffisant and fiche.volume_encode_m3 > 0:
            fonds.update({(i, j): ROUGE_PALE for j in range(len(lignes[0]))})
    colonnes = len(lignes[0]) - 1
    pdf.tableau(lignes, [0.16 * L] + [(0.84 / colonnes) * L] * colonnes, taille=6.5,
                fonds=fonds, alignements=["left"] + ["center"] * colonnes)

    sim = dossier.simulation_systeme
    if sim is not None:
        pdf.titre2(f"{numero}.3 Simulation du système complet")
        pdf.texte(
            f"Averse la plus défavorable pour l'ensemble du réseau : "
            f"{sim.hauteur_mm:.1f} mm en {sim.duree_min:.0f} min, T = {sim.periode_retour} ans. "
            f"Chaque ouvrage a sa propre durée critique ; celle retenue ici est celle qui met "
            f"le plus de volume en jeu.", 8.5, couleur=GRIS)
        lignes = synthese_simulation_systeme(dossier)
        colonnes = len(lignes[0]) - 1
        fonds = {}
        for i, (_ouvrage, res) in enumerate(sim.resultats, start=1):
            fonds.update({(i, j): _COULEURS_STATUT[res.statut] for j in range(len(lignes[0]))})
        pdf.tableau(lignes, [0.24 * L] + [(0.76 / colonnes) * L] * colonnes, taille=7.5,
                    fonds=fonds, alignements=["left"] + ["center"] * colonnes)
        pdf.encadre(
            f"Volume stocké : {sim.volume_stocke_m3:.1f} m³    |    "
            f"Débordement total : {sim.volume_debordement_m3:.2f} m³    |    "
            f"Vidange la plus longue : {sim.temps_vidange_max_h:.1f} h",
            fond=ROUGE_PALE if sim.ouvrages_en_debordement else VERT_PALE)
    for anomalie in systeme.anomalies():
        pdf.puce(anomalie, 8.5, couleur=rgb(180, 83, 9))


def ecrire(dossier: Dossier, chemin: str) -> str:
    """Génère le rapport PDF et renvoie le chemin du fichier."""
    p = dossier.projet
    res = dossier.resultat_principal
    pdf = Pdf()
    pdf.pied = (("Réseau de bassins d'orage" if dossier.reseau_multiple else "Bassin d'orage")
                + f" - {p.commune_nom} - T = {p.periode_retour} ans - "
                f"{p.nom_projet or 'projet sans nom'} - {dossier.date}")
    L = pdf.largeur_utile

    pdf.titre("Dimensionnement d'un bassin d'orage" if not dossier.reseau_multiple
              else "Dimensionnement d'un réseau de bassins d'orage", 18)
    pdf.texte("Méthode rationnelle - pluies statistiques du GTI (Région wallonne)", 9.5,
              italique=True, couleur=GRIS, apres=10)
    pdf.tableau(
        [
            ["Projet", p.nom_projet or "-", "Commune", f"{p.commune_nom} ({p.commune_ins})"],
            ["Localisation", p.localisation or "-", "Période de retour", f"{p.periode_retour} ans"],
            ["Auteur", p.auteur or "-", "Source des pluies", dossier.libelle_source],
            ["Date", dossier.date, "Scénario retenu", LIBELLES_SCENARIOS[dossier.scenario_principal]],
        ] + ([["Ouvrage détaillé", dossier.ouvrage_courant.nom, "Composition du projet",
               f"{len(dossier.systeme.bassins_versants)} bassins versants, "
               f"{len(dossier.systeme.ouvrages)} bassins d'orage"]]
             if dossier.reseau_multiple else []),
        [0.16 * L, 0.31 * L, 0.20 * L, 0.33 * L],
        entete=False,
        taille=8.5,
        fonds={(i, 0): BLEU_PALE for i in range(5)} | {(i, 2): BLEU_PALE for i in range(5)},
    )
    pdf.encadre(
        f"Volume de temporisation : {res.volume_m3:.1f} m³    |    "
        f"Durée de pluie critique : {res.duree_critique_hm}    |    "
        f"Vidange après la pluie : {res.temps_vidange_hm}",
        fond=BLEU_PALE if res.conforme else ROUGE_PALE,
    )

    # Les sections se numérotent au fil de l'écriture : la synthèse du réseau
    # n'apparaît que pour un projet à plusieurs ouvrages, et tout ce qui suit
    # se décale d'autant.
    rang = {"n": 1}

    def suivant() -> int:
        rang["n"] += 1
        return rang["n"]

    def numero() -> int:
        return rang["n"]

    pdf.titre1("1. Données d'entrée")
    versants = dossier.systeme.versants_de(dossier.ouvrage_courant.id) if dossier.systeme else []
    titre_surfaces = ("1.1 Surfaces raccordées à « " + dossier.ouvrage_courant.nom + " »"
                      if dossier.reseau_multiple else "1.1 Surfaces incidentes")
    pdf.titre2(titre_surfaces)
    if len(versants) > 1:
        pdf.texte("Ces surfaces se répartissent entre "
                  + ", ".join(f"« {bv.nom} »" for bv in versants) + ".", 8.5, couleur=GRIS)
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

    # Le bassin amont est une donnée d'entrée : il doit figurer au dossier même
    # si l'ouvrage aval n'est pas encore encodé.
    if p.amont.actif:
        amont = p.amont
        pdf.titre2("1.3 Bassin d'orage amont")
        pdf.texte("Un bassin d'orage situé en amont se déverse dans l'ouvrage étudié. Il "
                  "reçoit la meme pluie de projet sur son propre bassin versant, la tamponne, "
                  "puis la restitue a son débit de fuite - y compris longtemps après l'averse.")
        res_amont = hydro.dimensionner_amont(p)
        lignes_amont = [
            ["Caractéristique", "Valeur", "Unité"],
            ["Surface du bassin versant amont", f"{amont.surface_bv_m2:.0f}", "m²"],
            ["Coefficient de ruissellement moyen", f"{amont.coef_ruissellement:.2f}", "-"],
            ["Surface active amont", f"{amont.aire_ponderee_m2:.0f}", "m²"],
            ["Volume de temporisation amont", f"{amont.volume_temporisation_m3:.1f}", "m³"],
            ["Volume minimal pour éviter son débordement", f"{res_amont.volume_m3:.1f}", "m³"],
            ["Surface de dispersion amont", f"{amont.surface_dispersion_m2:.1f}", "m²"],
            ["Vitesse d'infiltration amont", f"{amont.k_infiltration_ms:.2e}", "m/s"],
            ["Débit d'ajutage amont", f"{amont.debit_ajutage_ls:.3f}", "l/s"],
            ["Débit restitué vers l'ouvrage aval", f"{res_amont.debit_sortant_ls:.3f}", "l/s"],
        ]
        pdf.tableau(lignes_amont, [0.60 * L, 0.22 * L, 0.18 * L], taille=8.5,
                    alignements=["left", "right", "center"])
        if amont.volume_temporisation_m3 + 1e-6 < res_amont.volume_m3:
            pdf.puce("Le bassin amont est sous-dimensionné : son trop-plein arrive sans "
                     "laminage dans l'ouvrage aval.")
        if amont.inclure_bv_dans_ajutage:
            pdf.puce(f"Surface du bassin versant amont comptée dans la surface raccordée : "
                     f"{p.aire_raccordee_m2:.0f} m², débit de fuite admissible "
                     f"{p.debit_fuite_admissible_ls:.3f} l/s.")

    if dossier.reseau_multiple:
        section_reseau(pdf, dossier, suivant())

    pdf.titre1(f"{suivant()}. Pluie de projet")
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

    pdf.titre1(f"{suivant()}. Comparaison des scénarios")
    pdf.texte("V(t) = h(t) x S_pondérée / 1000 - Q_sortie x t x 60 / 1000 ; le volume retenu est le "
              "maximum sur l'ensemble des durées de pluie.", 8.5, couleur=GRIS)
    synth = synthese_scenarios(dossier)
    fonds: Dict[Tuple[int, int], object] = {}
    for i, s in enumerate(ORDRE_SCENARIOS, start=1):
        if s == dossier.scenario_principal:
            fonds.update({(i, j): BLEU_PALE for j in range(len(synth[0]))})
        elif not dossier.resultats[s].conforme:
            fonds.update({(i, j): ROUGE_PALE for j in range(len(synth[0]))})
    colonnes = len(synth[0]) - 1
    pdf.tableau(synth, [0.26 * L] + [(0.74 / colonnes) * L] * colonnes, taille=7.0, fonds=fonds,
                alignements=["left"] + ["center"] * colonnes)
    dessiner_graphique(pdf, dossier.graphique_dimensionnement(), 170)

    if res.alertes or res.messages:
        pdf.titre2(f"{numero()}.1 Observations")
        for a in res.alertes:
            pdf.puce(a, couleur=rgb(180, 83, 9))
        for m in res.messages:
            pdf.puce(m, couleur=GRIS)

    if dossier.simulation:
        sim = dossier.simulation
        b = p.bassin
        pdf.nouvelle_page()
        # Le compteur avance d'abord : dans une expression conditionnelle, la
        # branche non retenue n'est pas évaluée, et la section suivante héritait
        # du numéro de la précédente sur un projet à bassin unique.
        rang_ouvrage = suivant()
        pdf.titre1(f"{rang_ouvrage}. Vérification de l'ouvrage encodé"
                   + (f" — {dossier.ouvrage_courant.nom}" if dossier.reseau_multiple else ""))
        pdf.tableau(
            [["Caractéristique", "Valeur", "Unité"],
             ["Volume tampon total (sous l'ajutage + au-dessus)",
              f"{b.volume_total_m3:.1f}", "m³"],
             ["    dont sous l'axe de l'ajutage", f"{b.volume_sous_ajutage_m3:.1f}", "m³"],
             ["    dont au-dessus de l'axe de l'ajutage", f"{b.volume_tampon_m3:.1f}", "m³"],
             ["Surface de dispersion (fond du bassin)", f"{b.surface_dispersion_m2:.1f}", "m²"],
             ["Débit d'infiltration", f"{sim.q_infiltration_ls:.3f}", "l/s"],
             ["Débit d'ajutage", f"{sim.q_ajutage_ls:.3f}", "l/s"]],
            [0.60 * L, 0.22 * L, 0.18 * L], taille=8.5, alignements=["left", "right", "center"],
        )
        amont = p.amont
        if amont.actif:
            pdf.titre2(f"{numero()}.1 Apport du bassin d'orage amont")
            pdf.tableau(
                [["Grandeur", "Valeur", "Unité"],
                 ["Volume restitué a l'ouvrage aval", f"{sim.volume_amont_m3:.1f}", "m³"],
                 ["Débit de pointe restitué", f"{sim.q_amont_max_ls:.3f}", "l/s"],
                 ["Fin du déversement amont", f"{sim.t_fin_apport_amont_min:.0f}", "min"],
                 ["Débit restitué après la fin de l'averse",
                  f"{sim.q_amont_apres_pluie_ls:.3f}", "l/s"]],
                [0.60 * L, 0.22 * L, 0.18 * L], taille=8.5,
                alignements=["left", "right", "center"],
            )
            pdf.puce(sim.commentaire_amont)
        pdf.titre2(f"{numero()}." + ("2 " if amont.actif else "1 ") + "Événement critique simulé")
        pdf.tableau(
            [["Grandeur", "Valeur", "Grandeur", "Valeur"],
             ["Durée de pluie", f"{sim.duree_pluie_min:.0f} min", "Volume stocké maximum", f"{sim.volume_max_m3:.1f} m³"],
             ["Hauteur de pluie", f"{sim.hauteur_pluie_mm:.1f} mm", "Taux de remplissage", f"{sim.taux_remplissage * 100:.0f} %"],
             ["Volume ruisselé", f"{sim.volume_ruissele_m3:.1f} m³", "Volume débordé", f"{sim.volume_debordement_m3:.2f} m³"],
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
        pdf.titre1(f"{suivant()}. Pluies absorbées sans débordement (table QDF)")
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
        pdf.titre1(f"{suivant()}. Dimensionnement de l'ajutage")
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

    pdf.titre1(f"{suivant()}. Conclusion")
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
