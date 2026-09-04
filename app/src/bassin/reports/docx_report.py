"""Rapport Word (.docx) du dimensionnement."""

from __future__ import annotations

from typing import List, Sequence

from ..core import hydro, rainfall
from ..core.model import LIBELLES_SCENARIOS
from . import charts
from .docx_writer import Cellule, DocxBuilder
from .dossier import Dossier, ORDRE_SCENARIOS, synthese_scenarios

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
        "Dimensionnement d'un bassin d'orage",
        "Méthode rationnelle - pluies statistiques du GTI (Région wallonne)",
    )
    doc.tableau(
        [
            ["Projet", p.nom_projet or "-", "Commune", f"{p.commune_nom} ({p.commune_ins})"],
            ["Localisation", p.localisation or "-", "Période de retour", f"{p.periode_retour} ans"],
            ["Auteur", p.auteur or "-", "Source des pluies", dossier.libelle_source],
            ["Date", dossier.date, "Scénario retenu", LIBELLES_SCENARIOS[dossier.scenario_principal]],
        ],
        largeurs=[3.2, 5.0, 3.4, 4.4],
        entete=False,
        taille=18,
    )

    doc.encadre(
        f"Volume de temporisation à mettre en oeuvre : {res.volume_m3:.1f} m³   |   "
        f"Durée de pluie critique : {res.duree_critique_hm}   |   "
        f"Vidange après la pluie : {res.temps_vidange_hm}",
        fond=BLEU if res.conforme else ROUGE,
    )

    # 1. Donnees d'entree
    doc.titre1("1. Données d'entrée")
    doc.titre2("1.1 Surfaces incidentes")
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

    doc.titre2("1.2 Sol, exutoire et contraintes")
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
        doc.titre2("1.3 Bassin d'orage amont")
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

    # 2. Pluie de projet
    doc.titre1("2. Pluie de projet")
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
        f"(intensité {res.intensite_mmh:.1f} mm/h, soit {res.intensite_ls_ha:.0f} l/s/ha).",
        puce=True,
    )
    doc.paragraphe(
        f"Débit ruisselle de pointe : {res.debit_entrant_ls:.1f} l/s pour une surface active de "
        f"{p.aire_ponderee_m2:.0f} m².",
        puce=True,
    )

    # 3. Scenarios
    doc.titre1("3. Comparaison des scénarios")
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
    doc.tableau(lignes, largeurs=[4.6, 1.7, 1.9, 1.5, 1.7, 1.6, 1.9, 1.6], taille=14)

    doc.image(charts.rendre_png(dossier.graphique_dimensionnement(), 900, 420), largeur_cm=16.0,
              legende="Volume à maîtriser en fonction de la durée de pluie - "
                      + charts.legende_texte(dossier.graphique_dimensionnement()))

    if res.alertes or res.messages:
        doc.titre2("3.1 Observations")
        for a in res.alertes:
            doc.paragraphe(a, puce=True, couleur="B45309")
        for m in res.messages:
            doc.paragraphe(m, puce=True, couleur="475569")

    # 4. Ouvrage et simulation
    if dossier.simulation:
        sim = dossier.simulation
        b = p.bassin
        doc.saut_de_page()
        doc.titre1("4. Vérification de l'ouvrage")
        doc.tableau(
            [
                ["Caractéristique", "Valeur", "Unité"],
                ["Volume total du bassin", f"{b.volume_total_m3:.1f}", "m³"],
                ["Volume sous l'axe de l'ajutage", f"{b.volume_sous_ajutage_m3:.1f}", "m³"],
                ["Volume tampon au-dessus de l'ajutage", f"{b.volume_tampon_m3:.1f}", "m³"],
                ["Surface de dispersion (fond du bassin)", f"{b.surface_dispersion_m2:.1f}", "m²"],
                ["Débit d'infiltration", f"{sim.q_infiltration_ls:.3f}", "l/s"],
                ["Débit d'ajutage", f"{sim.q_ajutage_ls:.3f}", "l/s"],
            ],
            largeurs=[9.0, 4.0, 3.0],
        )
        amont = p.amont
        if amont.actif:
            doc.titre2("4.1 Apport du bassin d'orage amont")
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
        doc.titre2("4.2 Événement critique simule" if p.amont.actif else "4.1 Événement critique simule")
        doc.tableau(
            [
                ["Grandeur", "Valeur"],
                ["Durée de pluie", f"{sim.duree_pluie_min:.0f} min"],
                ["Hauteur de pluie", f"{sim.hauteur_pluie_mm:.1f} mm"],
                ["Volume ruisselé", f"{sim.volume_ruissele_m3:.1f} m³"],
                ["Volume stocké maximum", f"{sim.volume_max_m3:.1f} m³"],
                ["Taux de remplissage", f"{sim.taux_remplissage * 100:.0f} %"],
                ["Volume déborde", f"{sim.volume_debordement_m3:.2f} m³"],
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
        doc.titre1("5. Pluies absorbées sans débordement (table QDF)")
        doc.paragraphe(
            "Volume requis [m³] par pluie ; fond vert : absorbe par l'ouvrage, orange : limite "
            "(plus de 95 % de la capacité), rouge : débordement."
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
        doc.titre1("6. Dimensionnement de l'ajutage")
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

    # 7. Conclusion
    doc.titre1("7. Conclusion")
    doc.paragraphe(
        f"Pour la commune de {p.commune_nom}, une période de retour de {p.periode_retour} ans et une surface "
        f"active de {p.aire_ponderee_m2:.0f} m², le scénario « {LIBELLES_SCENARIOS[dossier.scenario_principal]} » "
        f"conduit à un volume de temporisation de {res.volume_m3:.1f} m³, vidange en {res.temps_vidange_hm}."
    )
    if res.surface_infiltration_min_m2 is not None:
        doc.paragraphe(
            f"Surface d'infiltration minimale pour respecter le temps de vidange de "
            f"{p.temps_vidange_max_h:.0f} h : {res.surface_infiltration_min_m2:.1f} m².", puce=True)
    if res.debit_ajutage_min_ls is not None:
        doc.paragraphe(
            f"Débit d'ajutage minimal pour respecter le temps de vidange de "
            f"{p.temps_vidange_max_h:.0f} h : {res.debit_ajutage_min_ls:.3f} l/s.", puce=True)
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
