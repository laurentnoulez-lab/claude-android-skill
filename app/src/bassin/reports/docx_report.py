"""Rapport Word (.docx) du dimensionnement."""

from __future__ import annotations

from typing import List, Sequence

from ..core import hydro, rainfall
from ..core.model import LIBELLES_SCENARIOS
from . import charts, schema as mod_schema
from .docx_writer import Cellule, DocxBuilder
from .dossier import (Dossier, ORDRE_SCENARIOS, synthese_reseau, synthese_scenarios,
                      synthese_simulation_systeme, synthese_versants)

VERT = "DCFCE7"
ORANGE = "FEF3C7"
ROUGE = "FEE2E2"
BLEU = "DBEAFE"


def ecrire(dossier: Dossier, chemin: str) -> str:
    """Génère le rapport Word et renvoie le chemin du fichier."""
    p = dossier.projet
    res = dossier.resultat_principal
    doc = DocxBuilder(titre=f"Bassin d'orage - {p.commune_nom}", auteur=p.auteur)

    doc.titre_principal(
        "Dimensionnement d'un réseau de bassins d'orage" if dossier.reseau_multiple
        else "Dimensionnement d'un bassin d'orage",
        "Méthode rationnelle - pluies statistiques du GTI (Région wallonne)",
    )
    doc.tableau(
        [
            ["Projet", p.nom_projet or "-", "Commune", f"{p.commune_nom} ({p.commune_ins})"],
            ["Localisation", p.localisation or "-", "Période de retour", f"{p.periode_retour} ans"],
            ["Auteur", p.auteur or "-", "Source des pluies", dossier.source_pluies_datee],
            ["Date", dossier.date, "Composition du projet",
             f"{len(dossier.systeme.bassins_versants)} bassins versants, "
             f"{len(dossier.systeme.ouvrages)} bassins d'orage"]
            if dossier.reseau_multiple else
            ["Date", dossier.date, "Scénario retenu", LIBELLES_SCENARIOS[dossier.scenario_principal]],
        ],
        largeurs=[3.2, 5.0, 3.4, 4.4],
        entete=False,
        taille=18,
    )

    if dossier.reseau_multiple and dossier.fiches:
        cumul = sum(f.volume_minimal_m3 for f in dossier.fiches)
        encode_total = sum(f.volume_encode_m3 for f in dossier.fiches)
        vidange = max((f.resultat.temps_vidange_h for f in dossier.fiches), default=0.0)
        conforme = all(f.resultat.conforme for f in dossier.fiches)
        doc.encadre(
            f"Volume minimal cumulé : {cumul:.1f} m³   |   "
            f"Volume encodé : {encode_total:.1f} m³   |   "
            f"Vidange la plus longue : {vidange:.1f} h",
            fond=BLEU if conforme else ROUGE,
        )
    else:
        doc.encadre(
            f"Volume de temporisation à mettre en oeuvre : {res.volume_m3:.1f} m³   |   "
            f"Durée de pluie critique : {res.duree_critique_hm}   |   "
            f"Vidange après la pluie : {res.temps_vidange_hm}",
            fond=BLEU if res.conforme else ROUGE,
        )

    # Le rapport porte sur l'étude entière : chaque bassin d'orage a son
    # chapitre, écrit par le même code. Les sections communes à tout le réseau
    # - pluie de projet, synthèse - ne s'écrivent qu'une fois.
    chapitres = dossier.par_ouvrage()
    reseau = dossier.reseau_multiple and len(chapitres) > 1
    rang = {"n": 0, "sous": 0}

    def titre(texte: str) -> None:
        rang["n"] += 1
        rang["sous"] = 0
        doc.titre1(f"{rang['n']}. {texte}")

    def sous_titre(texte: str) -> None:
        rang["sous"] += 1
        doc.titre2(f"{rang['n']}.{rang['sous']} {texte}")

    def chapitre(texte: str) -> None:
        """Titre de premier niveau d'un chapitre d'ouvrage."""
        rang["n"] += 1
        rang["sous"] = 0
        doc.titre1(f"{rang['n']}. {texte}")

    def sans_numero(texte: str) -> None:
        """Dans un chapitre d'ouvrage, une sous-section devient un intertitre."""
        doc.titre2(texte)

    if reseau:
        _w_versants(doc, dossier, titre, sous_titre)
        _section_reseau(doc, dossier, rang["n"] + 1)
        rang["n"] += 1
        _w_pluie(doc, dossier, titre, sous_titre)
        for sous in chapitres:
            doc.saut_de_page()
            chapitre(sous.ouvrage_courant.nom)
            _w_donnees(doc, sous, sous_titre, sans_numero)
            _w_ouvrage(doc, sous, sous_titre, sans_numero)
    else:
        _w_donnees(doc, dossier, titre, sous_titre)
        _w_pluie(doc, dossier, titre, sous_titre)
        _w_ouvrage(doc, dossier, titre, sous_titre)

    titre("Conclusion")
    if reseau:
        # La conclusion d'un rapport de réseau porte sur le réseau : résumer le
        # seul ouvrage courant laisserait croire que l'étude s'arrête à lui.
        systeme = dossier.systeme
        cumul = sum(f.volume_minimal_m3 for f in dossier.fiches)
        encode = sum(f.volume_encode_m3 for f in dossier.fiches)
        doc.paragraphe(
            f"Pour la commune de {p.commune_nom} et une période de retour de "
            f"{p.periode_retour} ans, le réseau compte "
            f"{len(systeme.bassins_versants)} bassins versants totalisant "
            f"{systeme.aire_ponderee_m2:.0f} m² de surface active, et "
            f"{len(systeme.ouvrages)} bassins d'orage."
        )
        doc.paragraphe(f"Volume de temporisation minimal cumulé : {cumul:.1f} m³ "
                       f"(encodé : {encode:.1f} m³).", puce=True)
        for sous in chapitres:
            r = sous.resultat_principal
            doc.paragraphe(
                f"{sous.ouvrage_courant.nom} : {r.volume_m3:.1f} m³, pluie critique "
                f"{r.duree_critique_hm}, vidange {r.temps_vidange_hm} "
                f"({'conforme' if r.conforme else 'NON CONFORME'}).", puce=True)
            if r.surface_infiltration_min_m2 is not None:
                doc.paragraphe("    " + sous.phrase_minimum(
                    r.surface_infiltration_min_m2, 1, "m²",
                    "Surface d'infiltration minimale", "l'ajutage"), couleur="475569")
            if r.debit_ajutage_min_ls is not None:
                doc.paragraphe("    " + sous.phrase_minimum(
                    r.debit_ajutage_min_ls, 3, "l/s",
                    "Débit d'ajutage minimal", "l'infiltration"), couleur="475569")
        sim = dossier.simulation_systeme
        if sim is not None:
            doc.paragraphe(f"Simulation d'ensemble : averse la plus défavorable "
                           f"{sim.hauteur_mm:.1f} mm en {sim.duree_min:.0f} min, "
                           f"débordement total {sim.volume_debordement_m3:.2f} m³, "
                           f"vidange la plus longue {sim.temps_vidange_max_h:.1f} h.",
                           puce=True)
        for anomalie in systeme.anomalies():
            doc.paragraphe(anomalie, puce=True)
    else:
        doc.paragraphe(
            f"Pour la commune de {p.commune_nom}, une période de retour de {p.periode_retour} ans et une surface "
            f"active de {p.aire_ponderee_m2:.0f} m², le scénario « {LIBELLES_SCENARIOS[dossier.scenario_principal]} » "
            f"conduit à un volume de temporisation de {res.volume_m3:.1f} m³, vidange en {res.temps_vidange_hm}."
        )
        if res.surface_infiltration_min_m2 is not None:
            doc.paragraphe(dossier.phrase_minimum(res.surface_infiltration_min_m2, 1, "m²",
                                                  "Surface d'infiltration minimale", "l'ajutage"),
                           puce=True)
        if res.debit_ajutage_min_ls is not None:
            doc.paragraphe(dossier.phrase_minimum(res.debit_ajutage_min_ls, 3, "l/s",
                                                  "Débit d'ajutage minimal", "l'infiltration"),
                           puce=True)
    if p.remarques:
        doc.titre2("Remarques")
        doc.paragraphe(p.remarques)
    doc.paragraphe("")
    doc.paragraphe("Fait à ............................................., le ................................")
    doc.paragraphe("")
    doc.paragraphe("Titre et nom : ............................................................................")
    doc.paragraphe("")
    doc.paragraphe("Signature :")
    return doc.enregistrer(chemin)


def _cellules(lignes, fonds=None):
    """Entête en gras, lignes signalées teintées."""
    fonds = fonds or {}
    sortie = [[Cellule(v, gras=True) for v in lignes[0]]]
    for i, ligne in enumerate(lignes[1:], start=1):
        fond = fonds.get(i)
        sortie.append([Cellule(v, fond=fond) if fond else v for v in ligne])
    return sortie


def _section_reseau(doc: DocxBuilder, dossier: Dossier, numero: int) -> None:
    """Synthèse du réseau : arbre, bassins versants, ouvrages, simulation.

    Le schéma se rend ici en **arbre indenté** plutôt qu'en dessin : la police
    5x7 du rasteur embarqué ne sait pas écrire les noms des ouvrages, et un
    schéma sans étiquette ne dirait rien. Le rapport PDF, lui, le trace au
    vecteur avec la vraie typographie.
    """
    systeme = dossier.systeme
    doc.saut_de_page()
    doc.titre1(f"{numero}. Synthèse du réseau")
    schema = mod_schema.construire(systeme, dossier.fiches, dossier.simulation_systeme)
    doc.paragraphe(schema.sous_titre, gras=True)
    doc.titre2(f"{numero}.1 Raccordements")
    doc.paragraphe("Un bassin versant se raccorde à un seul bassin d'orage ; un bassin d'orage "
                   "se déverse dans un autre bassin ou à l'exutoire. Les collecteurs sont "
                   "supposés véhiculer tout le débit et les temps de parcours sont négligés.")
    for ligne in mod_schema.arbre_texte(systeme, dossier.fiches):
        doc.paragraphe(ligne, taille=17)
    for note in schema.notes:
        doc.paragraphe(note, puce=True)

    doc.titre2(f"{numero}.2 Bassins versants")
    doc.tableau(_cellules(synthese_versants(dossier),
                          {len(systeme.bassins_versants) + 1: BLEU}),
                largeurs=[3.7, 3.7, 2.1, 1.8, 2.4, 2.3])

    doc.titre2(f"{numero}.3 Dimensionnement de chaque bassin d'orage")
    doc.paragraphe("Chaque ouvrage est dimensionné sur ses propres bassins versants et sur ce "
                   "que lui restituent les ouvrages amont, tels qu'ils sont encodés. Un ouvrage "
                   "amont sous-dimensionné surverse : son trop-plein arrive sans laminage et "
                   "gonfle le volume à prévoir en aval.")
    fonds = {i: ROUGE for i, fiche in enumerate(dossier.fiches, start=1)
             if not fiche.suffisant and fiche.volume_encode_m3 > 0}
    doc.tableau(_cellules(synthese_reseau(dossier), fonds),
                largeurs=[2.6, 2.0, 1.8, 1.4, 1.4, 1.3, 1.3, 1.2, 1.2, 1.2, 1.1], taille=13)

    sim = dossier.simulation_systeme
    if sim is not None:
        doc.titre2(f"{numero}.4 Simulation du système complet")
        doc.paragraphe(
            f"Averse la plus défavorable pour l'ensemble du réseau : {sim.hauteur_mm:.1f} mm "
            f"en {sim.duree_min:.0f} min, T = {sim.periode_retour} ans. Chaque ouvrage a sa "
            f"propre durée critique ; celle retenue ici est celle qui met le plus de volume "
            f"en jeu.")
        couleurs = {"OK": VERT, "LIMITE": ORANGE, "DEBORDEMENT": ROUGE}
        fonds = {i: couleurs[res.statut]
                 for i, (_o, res) in enumerate(sim.resultats, start=1)}
        doc.tableau(_cellules(synthese_simulation_systeme(dossier), fonds),
                    largeurs=[4.0, 1.8, 1.8, 1.9, 2.0, 2.0, 1.6, 1.3])
        doc.encadre(
            f"Volume stocké : {sim.volume_stocke_m3:.1f} m³   |   "
            f"Débordement total : {sim.volume_debordement_m3:.2f} m³   |   "
            f"Vidange la plus longue : {sim.temps_vidange_max_h:.1f} h",
            fond=ROUGE if sim.ouvrages_en_debordement else VERT)
    for anomalie in systeme.anomalies():
        doc.paragraphe(anomalie, puce=True)


def _w_versants(doc, dossier, titre, sous_titre):
    """Données générales du projet et tous les bassins versants.

    Sur un réseau, les contraintes (commune, sécurité sur K, vidange maximale)
    valent pour tout le système : les répéter dans chaque chapitre d'ouvrage
    n'apprendrait rien. Les bassins versants, eux, se lisent d'un bloc.
    """
    p = dossier.projet
    systeme = dossier.systeme
    titre("Données d'entrée du projet")

    sous_titre("Contraintes communes à tout le réseau")
    doc.tableau(
        [
            ["Paramètre", "Valeur", "Unité"],
            ["Commune", f"{p.commune_nom} ({p.commune_ins})", "-"],
            ["Période de retour", f"{p.periode_retour}", "ans"],
            ["Source des pluies", dossier.source_pluies_datee, "-"],
            ["Coefficient de sécurité appliqué à K", f"{p.coef_securite_infiltration:.1f}", "-"],
            ["Temps de vidange maximum admis", f"{p.temps_vidange_max_h:.0f}", "h"],
        ],
        largeurs=[9.0, 4.0, 3.0],
    )

    sous_titre("Bassins versants")
    lignes: List[Sequence] = [["Bassin versant", "Raccordé à", "Surface [m²]",
                               "C moyen", "Surface active [m²]"]]
    for bv in systeme.bassins_versants:
        aval = systeme.ouvrage(bv.bassin_id)
        lignes.append([bv.nom, aval.nom if aval is not None else "non raccordé",
                       f"{bv.aire_totale_m2:.0f}", f"{bv.coefficient_moyen:.3f}",
                       f"{bv.aire_ponderee_m2:.1f}"])
    lignes.append([
        Cellule("TOTAL", gras=True, fond=BLEU),
        Cellule("", fond=BLEU),
        Cellule(f"{systeme.aire_totale_m2:.0f}", gras=True, fond=BLEU),
        Cellule(f"{systeme.coefficient_moyen:.3f}", gras=True, fond=BLEU),
        Cellule(f"{systeme.aire_ponderee_m2:.1f}", gras=True, fond=BLEU),
    ])
    doc.tableau(lignes, largeurs=[4.4, 4.4, 2.2, 1.9, 3.1])


def _w_donnees(doc, dossier, titre, sous_titre):
    """Surfaces, sol et exutoire de l'ouvrage décrit."""
    p = dossier.projet
    res = dossier.resultat_principal
    titre("Données d'entrée")
    versants = dossier.systeme.versants_de(dossier.ouvrage_courant.id) if dossier.systeme else []
    sous_titre("Surfaces raccordées à « " + dossier.ouvrage_courant.nom + " »"
               if dossier.reseau_multiple else "Surfaces incidentes")
    if len(versants) > 1:
        doc.paragraphe("Ces surfaces se répartissent entre "
                       + ", ".join(f"« {bv.nom} »" for bv in versants) + ".",
                       couleur="475569")
    lignes: List[Sequence] = [["Type d'occupation du sol", "Coeff. [-]", "Surface [m²]", "Surface pondérée [m²]"]]
    for s in p.surfaces_non_vides():
        lignes.append([s.libelle, f"{s.coefficient:.2f}", f"{s.aire_m2:.0f}", f"{s.aire_ponderee_m2:.1f}"])
    lignes.append([
        Cellule("TOTAL", gras=True, fond=BLEU),
        Cellule(f"C moyen = {p.coefficient_moyen:.3f}", gras=True, fond=BLEU),
        Cellule(f"{p.aire_totale_m2:.0f}", gras=True, fond=BLEU),
        Cellule(f"{p.aire_ponderee_m2:.1f}", gras=True, fond=BLEU),
    ])
    doc.tableau(lignes, largeurs=[8.0, 2.4, 2.6, 3.0])
    doc.paragraphe(f"Surface de référence du projet : {p.surface_reference_m2:.0f} m²", puce=True)

    sous_titre("Sol, exutoire et contraintes")
    doc.tableau(
        [
            ["Paramètre", "Valeur", "Unité"],
            ["Coefficient d'infiltration K", f"{p.k_infiltration_ms:.2e}", "m/s"],
            ["Coefficient de sécurité appliqué à K", f"{p.coef_securite_infiltration:.1f}", "-"],
            ["Surface d'infiltration du dispositif", f"{p.surface_infiltration_m2:.1f}", "m²"],
            ["Débit d'infiltration Q = 1000 x S x K / coef.",
             f"{res.debit_infiltration_ls:.3f}", "l/s"],
            ["Débit d'ajutage (orifice calibré)", f"{p.debit_ajutage_ls:.3f}", "l/s"],
            ["Débit de fuite admissible (5 l/s/ha)", f"{p.debit_fuite_admissible_ls:.3f}", "l/s"],
            ["Temps de vidange maximum admis (après la pluie)", f"{p.temps_vidange_max_h:.0f}", "h"],
        ],
        largeurs=[9.0, 4.0, 3.0],
    )

    # Le bassin amont est une donnée d'entrée : il figure au dossier même sans
    # ouvrage aval encodé.
    if p.amont.actif:
        amont = p.amont
        res_amont = hydro.dimensionner_amont(p)
        sous_titre("Bassin d'orage amont")
        doc.paragraphe(
            "Un bassin d'orage situé en amont se déverse dans l'ouvrage étudié. Il reçoit la "
            "meme pluie de projet sur son propre bassin versant, la tamponne, puis la restitue "
            "a son débit de fuite - y compris longtemps après l'averse.")
        doc.tableau(
            [
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
            ],
            largeurs=[9.0, 4.0, 3.0],
        )
        if amont.volume_temporisation_m3 + 1e-6 < res_amont.volume_m3:
            doc.paragraphe("Le bassin amont est sous-dimensionné : son trop-plein arrive sans "
                           "laminage dans l'ouvrage aval.", puce=True, couleur="B45309")
        if amont.inclure_bv_dans_ajutage:
            doc.paragraphe(
                f"Surface du bassin versant amont comptée dans la surface raccordée : "
                f"{p.aire_raccordee_m2:.0f} m², débit de fuite admissible "
                f"{p.debit_fuite_admissible_ls:.3f} l/s.", puce=True)


def _w_pluie(doc, dossier, titre, sous_titre):
    """Pluie de projet : commune au réseau, écrite une fois."""
    p = dossier.projet
    res = dossier.resultat_principal
    titre("Pluie de projet")
    if rainfall.a_donnees_montana(p.commune_ins) and p.source_pluie == rainfall.SOURCE_MONTANA:
        a1, b1, a2, b2, a3, b3 = rainfall.montana_coeffs(p.commune_ins, p.periode_retour)
        doc.paragraphe("Formule de Montana : i [mm/h] = a x t[min] ^ (-b)")
        doc.tableau(
            [
                ["Plage de durée", "a", "b"],
                ["t < 25 min", f"{a1:.1f}", f"{b1:.4f}"],
                ["25 min <= t <= 6000 min", f"{a2:.1f}", f"{b2:.4f}"],
                ["t > 6000 min", f"{a3:.1f}", f"{b3:.4f}"],
            ],
            largeurs=[8.0, 4.0, 4.0],
        )
    else:
        doc.paragraphe("Hauteurs de pluie issues des tables QDF du GTI (interpolation logarithmique).")
    doc.paragraphe(
        f"Pluie critique retenue : {res.hauteur_pluie_mm:.1f} mm en {res.duree_critique_hm} "
        f"(intensité {res.intensite_mmh:.1f} mm/h, soit {res.intensite_ls_ha:.1f} l/s/ha).",
        puce=True,
    )
    doc.paragraphe(
        f"Débit ruisselé de pointe : {res.debit_entrant_ls:.1f} l/s pour une surface active de "
        f"{p.aire_ponderee_m2:.0f} m².",
        puce=True,
    )


def _w_ouvrage(doc, dossier, titre, sous_titre):
    """Scénarios, vérification, table QDF et ajutage d'un ouvrage."""
    p = dossier.projet
    res = dossier.resultat_principal
    titre("Comparaison des scénarios")
    doc.paragraphe(
        "Méthode rationnelle : V(t) = h(t) x S_pondérée / 1000 - Q_sortie x t x 60 / 1000. "
        "Le volume retenu est le maximum sur l'ensemble des durées de pluie."
    )
    synth = synthese_scenarios(dossier)
    lignes = [synth[0]]
    for s, ligne in zip(ORDRE_SCENARIOS, synth[1:]):
        r = dossier.resultats[s]
        fond = BLEU if s == dossier.scenario_principal else (ROUGE if not r.conforme else None)
        lignes.append([Cellule(str(v), fond=fond, gras=(s == dossier.scenario_principal)) for v in ligne])
    doc.tableau(lignes, largeurs=[4.0, 1.5, 1.9, 1.6, 1.4, 1.5, 1.5, 1.7, 1.5], taille=13)

    doc.image(charts.rendre_png(dossier.graphique_dimensionnement(), 900, 420), largeur_cm=16.0,
              legende="Volume à maîtriser en fonction de la durée de pluie - "
                      + charts.legende_texte(dossier.graphique_dimensionnement()))

    if res.alertes or res.messages:
        sous_titre("Observations")
        for a in res.alertes:
            doc.paragraphe(a, puce=True, couleur="B45309")
        for m in res.messages:
            doc.paragraphe(m, puce=True, couleur="475569")

    # 4. Ouvrage et simulation
    if dossier.simulation:
        sim = dossier.simulation
        b = p.bassin
        doc.saut_de_page()
        titre("Vérification de l'ouvrage")
        doc.tableau(
            [
                ["Caractéristique", "Valeur", "Unité"],
                ["Volume tampon total (sous l'ajutage + au-dessus)",
                 f"{b.volume_total_m3:.1f}", "m³"],
                ["    dont sous l'axe de l'ajutage", f"{b.volume_sous_ajutage_m3:.1f}", "m³"],
                ["    dont au-dessus de l'axe de l'ajutage", f"{b.volume_tampon_m3:.1f}", "m³"],
                ["Surface de dispersion (fond du bassin)", f"{b.surface_dispersion_m2:.1f}", "m²"],
                ["Débit d'infiltration", f"{sim.q_infiltration_ls:.3f}", "l/s"],
                ["Débit d'ajutage", f"{sim.q_ajutage_ls:.3f}", "l/s"],
            ],
            largeurs=[9.0, 4.0, 3.0],
        )
        amont = p.amont
        if amont.actif:
            sous_titre("Apport du bassin d'orage amont")
            doc.tableau(
                [
                    ["Grandeur", "Valeur", "Unité"],
                    ["Volume restitué a l'ouvrage aval", f"{sim.volume_amont_m3:.1f}", "m³"],
                    ["Débit de pointe restitué", f"{sim.q_amont_max_ls:.3f}", "l/s"],
                    ["Fin du déversement amont", f"{sim.t_fin_apport_amont_min:.0f}", "min"],
                    ["Débit restitué après la fin de l'averse",
                     f"{sim.q_amont_apres_pluie_ls:.3f}", "l/s"],
                ],
                largeurs=[9.0, 4.0, 3.0],
            )
            doc.paragraphe(sim.commentaire_amont, puce=True)
        sous_titre("Événement critique simulé")
        doc.tableau(
            [
                ["Grandeur", "Valeur"],
                ["Durée de pluie", f"{sim.duree_pluie_min:.0f} min"],
                ["Hauteur de pluie", f"{sim.hauteur_pluie_mm:.1f} mm"],
                ["Volume ruisselé", f"{sim.volume_ruissele_m3:.1f} m³"],
                ["Volume stocké maximum", f"{sim.volume_max_m3:.1f} m³"],
                ["Taux de remplissage", f"{sim.taux_remplissage * 100:.0f} %"],
                ["Volume débordé", f"{sim.volume_debordement_m3:.2f} m³"],
                ["Temps de vidange", f"{sim.temps_vidange_h:.1f} h"],
                [Cellule("Statut", gras=True),
                 Cellule(sim.statut, gras=True, fond=ROUGE if sim.debordement else VERT)],
            ],
            largeurs=[8.0, 8.0],
        )
        g = dossier.graphique_simulation()
        if g:
            doc.image(charts.rendre_png(g, 900, 420), largeur_cm=16.0,
                      legende="Remplissage et vidange - " + charts.legende_texte(g))
        gd = dossier.graphique_debits()
        if gd:
            doc.image(charts.rendre_png(gd, 900, 360), largeur_cm=16.0,
                      legende="Débits - " + charts.legende_texte(gd))

    # 5. Table QDF
    if dossier.table:
        table = dossier.table
        doc.saut_de_page()
        titre("Pluies absorbées sans débordement")
        doc.paragraphe(
            f"{dossier.titre_table_volumes}. Volume requis [m³] par pluie ; fond vert : absorbé "
            "par l'ouvrage, orange : limite (plus de 95 % de la capacité), rouge : débordement."
        )
        entete = ["Durée"] + [f"{rp} a" for rp in table.periodes_retour]
        lignes = [entete]
        for i, _ in enumerate(table.durees_min):
            ligne = [Cellule(rainfall.QDF_DURATION_LABELS[i], gras=True)]
            for j in range(len(table.periodes_retour)):
                c = table.cellules[i][j]
                fond = {"OK": VERT, "LIMITE": ORANGE, "DEBORDEMENT": ROUGE}[c.statut]
                ligne.append(Cellule(f"{c.volume_requis_m3:.1f}", fond=fond, alignement="center"))
            lignes.append(ligne)
        doc.tableau(lignes, largeurs=[1.9] + [1.17] * len(table.periodes_retour), taille=13)
        rp_max = table.periode_retour_max_acceptee()
        doc.encadre(
            f"Période de retour maximale absorbée sans débordement : {rp_max} ans"
            if rp_max else "Le bassin déborde déjà pour la pluie de récurrence 2 ans.",
            fond=VERT if rp_max else ROUGE,
        )

    # 6. Ajutage
    if dossier.orifice:
        o = dossier.orifice
        titre("Dimensionnement de l'ajutage")
        doc.paragraphe("Orifice en paroi mince - formule de Torricelli : Q = Cd x A x racine(2 g h).")
        doc.tableau(
            [
                ["Grandeur", "Valeur", "Unité"],
                ["Débit d'ajutage visé", f"{o.debit_ls:.3f}", "l/s"],
                ["Charge h (axe de l'orifice -> trop-plein)", f"{o.charge_m:.2f}", "m"],
                ["Coefficient de débit Cd", f"{o.coef_debit:.2f}", "-"],
                ["Section requise", f"{o.section_cm2:.2f}", "cm²"],
                ["Diamètre requis", f"{o.diametre_mm:.1f}", "mm"],
                ["Vitesse dans l'orifice", f"{o.vitesse_ms:.2f}", "m/s"],
                ["Diamètre commercial retenu",
                 "-" if o.diametre_commercial_mm is None else f"{o.diametre_commercial_mm:.0f}", "mm"],
                ["Débit réel du diamètre retenu",
                 "-" if o.debit_commercial_ls is None else f"{o.debit_commercial_ls:.3f}", "l/s"],
            ],
            largeurs=[9.0, 4.0, 3.0],
        )
        go = dossier.graphique_orifice()
        if go:
            doc.image(charts.rendre_png(go, 900, 340), largeur_cm=15.0,
                      legende="Loi de débit de l'orifice - " + charts.legende_texte(go))
