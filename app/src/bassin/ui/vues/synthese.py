"""Vue « Synthèse » : schéma du réseau et simulation du système complet.

Un projet à plusieurs bassins ne se lit pas dans un tableau : il faut voir d'un
coup d'œil qui alimente quoi, ce que chaque ouvrage retient, et où part la
surverse. Cette vue réunit le schéma, les grandeurs de chaque ouvrage, la pluie
dimensionnante et le temps de vidange maximal admis, puis la simulation de
l'averse la plus défavorable pour l'ensemble du réseau.
"""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import hydro, rainfall, reseau as coeur
from ...reports import charts, schema as schema_module
from .. import graphiques, schema_flet, theme
from .base import Vue

#: Zooms proposés pour le schéma.
ECHELLES = ((0.8, "Réduit"), (1.0, "Normal"), (1.3, "Agrandi"))

#: Une liste déroulante Flet dont la valeur est la chaîne vide n'affiche rien.
DUREE_AUTOMATIQUE = "__critique__"


class VueSynthese(Vue):
    titre = "Synthèse"
    icone = ft.Icons.SCHEMA
    sous_titre = "Schéma du réseau et simulation d'ensemble"

    def __init__(self, page, etat):
        super().__init__(page, etat)
        self._echelle = 1.0
        self._duree = None
        self._recurrence = None

    # ---------------------------------------------------------------- schéma
    def _schema(self) -> ft.Control:
        systeme = self.etat.systeme
        schema = schema_module.construire(systeme, self.etat.fiches,
                                          self.etat.simulation_systeme)

        def changer_echelle(e: ft.ControlEvent) -> None:
            self._echelle = float(e.control.value)
            self.rafraichir()

        notes = [ft.Text(theme.fr(n), size=12, color=theme.GRIS, no_wrap=False)
                 for n in schema.notes[:2]]
        alertes = [theme.message(a, "alerte") for a in systeme.anomalies()]
        return ft.Column(
            [
                ft.Text(schema.sous_titre, size=12.5, weight=ft.FontWeight.W_600,
                        color=theme.BLEU, no_wrap=False),
                schema_flet.construire(schema, self._echelle),
                schema_flet.legende(),
                ft.ResponsiveRow(
                    [theme.selecteur("Taille du schéma", str(self._echelle),
                                     [(str(v), t) for v, t in ECHELLES], changer_echelle,
                                     col={"xs": 12, "sm": 6, "md": 4}, compact=True)],
                ),
            ] + notes + alertes,
            spacing=12,
        )

    # ---------------------------------------------------------- vue d'ensemble
    def _tuiles(self) -> ft.Control:
        systeme = self.etat.systeme
        fiches = self.etat.fiches
        sim = self.etat.simulation_systeme
        minimal = sum(f.volume_minimal_m3 for f in fiches)
        suffisant = systeme.volume_total_m3 + 1e-6 >= minimal
        # La tuile porte sur l'ouvrage **encodé**, comme le tableau qui la suit et
        # comme l'alerte des 48 h. Elle montrait le maximum des fiches de
        # dimensionnement — 12,5 h — au-dessus d'un tableau qui affichait 16 h 13
        # pour le même réseau : deux « vidanges les plus longues » sur un écran,
        # et rien pour les distinguer. C'est celle de l'ouvrage construit que le
        # maître d'ouvrage doit tenir.
        vidange = sim.temps_vidange_max_h if sim is not None else 0.0
        # La tuile doit porter sur ce qui est réellement routé : un bassin versant
        # laissé sans raccordement ne ruisselle nulle part, et le compter faisait
        # dire à la tuile 45 000 m² au-dessus d'un volume ruisselé qui n'en
        # concernait que 18 000.
        routee = systeme.aire_ponderee_routee_m2
        orpheline = systeme.aire_ponderee_m2 - routee
        return ft.ResponsiveRow(
            [
                ft.Container(theme.tuile(
                    theme.nombre(routee, 0), "Surface active du système", "m²",
                    theme.ARDOISE, ft.Icons.LANDSCAPE,
                    (f"sur {theme.nombre(systeme.aire_routee_m2, 0)} m² incidents · "
                     f"{theme.nombre(orpheline, 0)} m² non raccordés, non comptés"
                     if orpheline > 0 else
                     f"sur {theme.nombre(systeme.aire_routee_m2, 0)} m² incidents")),
                    col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(
                    theme.nombre(minimal, 1), "Volume minimal cumulé", "m³",
                    theme.VERT if suffisant else theme.ROUGE, ft.Icons.WATER,
                    f"encodé : {theme.nombre(systeme.volume_total_m3, 1)} m³"),
                    col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(
                    theme.nombre(sim.hauteur_mm, 1) if sim else "—",
                    "Pluie dimensionnante du système", "mm", theme.BLEU,
                    ft.Icons.WATER_DROP_OUTLINED,
                    (f"{hydro.formater_duree(sim.duree_min)} · T = {sim.periode_retour} ans"
                     if sim else "")),
                    col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(
                    theme.nombre(vidange, 1), "Vidange la plus longue", "h",
                    theme.ROUGE if vidange > systeme.temps_vidange_max_h else theme.ARDOISE,
                    ft.Icons.TIMELAPSE,
                    theme.fr(f"ouvrages encodés · maximum admis : "
                             f"{systeme.temps_vidange_max_h:.0f} h")),
                    col={"xs": 12, "sm": 6, "md": 3}),
            ],
            spacing=12, run_spacing=12,
        )

    # ------------------------------------------------------------ simulation
    def _simulateur(self) -> ft.Control:
        etat = self.etat
        systeme = etat.systeme
        zone = ft.Column(spacing=12)

        recurrence = ft.Dropdown(
            label="Période de retour",
            value=str(self._recurrence or systeme.periode_retour),
            options=[ft.dropdown.Option(str(rp), f"{rp} ans")
                     for rp in rainfall.RETURN_PERIODS],
            dense=True, border_radius=10,
        )
        duree = ft.Dropdown(
            label="Durée de pluie",
            value=str(self._duree) if self._duree else DUREE_AUTOMATIQUE,
            options=[ft.dropdown.Option(DUREE_AUTOMATIQUE,
                                        "La plus défavorable pour le système")]
                    + [ft.dropdown.Option(str(float(d)), libelle)
                       for d, libelle in zip(rainfall.QDF_DURATIONS_MIN,
                                             rainfall.QDF_DURATION_LABELS)],
            dense=True, border_radius=10,
        )

        def lancer(_=None) -> None:
            self._recurrence = int(recurrence.value)
            self._duree = (None if duree.value in (DUREE_AUTOMATIQUE, "", None)
                           else float(duree.value))
            try:
                if self._duree is None:
                    resultat = coeur.simuler_evenement_critique(systeme, self._recurrence)
                else:
                    resultat = coeur.simuler(systeme, self._duree, self._recurrence)
            except Exception as exc:  # pragma: no cover - filet de sécurité
                zone.controls = [theme.message(f"Simulation impossible : {exc}", "erreur")]
            else:
                zone.controls = self._resultats_simulation(resultat)
            try:
                zone.update()
            except Exception:
                pass

        sim = etat.simulation_systeme
        if sim is not None:
            zone.controls = self._resultats_simulation(sim)

        return ft.Column(
            [
                ft.Text("La durée critique n'est pas la même pour tous les ouvrages : un "
                        "petit bassin versant imperméable culmine en quelques minutes, un "
                        "grand ensemble tamponné en plusieurs heures. La durée « la plus "
                        "défavorable pour le système » est celle qui met le plus de volume "
                        "en jeu, débordement d'abord.", size=12, color=theme.GRIS),
                ft.ResponsiveRow(
                    [
                        ft.Container(duree, col={"xs": 12, "sm": 6, "md": 5}),
                        ft.Container(recurrence, col={"xs": 12, "sm": 6, "md": 3}),
                        ft.Container(theme.bouton_principal("Simuler le système",
                                                            ft.Icons.PLAY_ARROW, lancer),
                                     col={"xs": 12, "md": 4}),
                    ],
                    spacing=12, run_spacing=12,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
                zone,
            ],
            spacing=12,
        )

    def _resultats_simulation(self, sim) -> List[ft.Control]:
        systeme = self.etat.systeme
        lignes = []
        for ouvrage, res in sim.resultats:
            couleur, fond = theme.COULEURS_STATUT[res.statut]
            lignes.append(ft.DataRow(cells=[
                ft.DataCell(ft.Text(ouvrage.nom, size=12, weight=ft.FontWeight.W_600)),
                ft.DataCell(ft.Text(theme.nombre(res.volume_max_m3, 1), size=12)),
                ft.DataCell(ft.Text(theme.nombre(res.volume_capacite_m3, 1), size=12)),
                ft.DataCell(ft.Text(res.taux_remplissage_texte, size=12)),
                ft.DataCell(ft.Text(theme.nombre(res.volume_debordement_m3, 2), size=12,
                                    color=theme.ROUGE if res.debordement else None)),
                ft.DataCell(ft.Text(theme.nombre(res.volume_amont_m3, 1), size=12)),
                ft.DataCell(ft.Text(res.temps_vidange_h_texte, size=12,
                                    color=theme.ROUGE
                                    if res.temps_vidange_h > systeme.temps_vidange_max_h
                                    else None)),
                ft.DataCell(ft.Container(
                    ft.Text(theme.LIBELLES_STATUT.get(res.statut, res.statut), size=11,
                            color=couleur, weight=ft.FontWeight.W_700),
                    bgcolor=fond, padding=ft.padding.symmetric(3, 8), border_radius=8)),
            ]))
        tableau = theme.tableau_defilant(
            ft.DataTable(
                columns=theme.entete_tableau(
                    ["Bassin d'orage", "Pointe [m³]", "Capacité [m³]", "Remplissage [%]",
                     "Débordement [m³]", "Apport amont [m³]", "Vidange", "Statut"]),
                rows=lignes, column_spacing=18, heading_row_height=38,
                data_row_max_height=42, divider_thickness=0.4,
            )
        )
        entete = ft.Row(
            [
                theme.etiquette_statut(sim.statut),
                theme.etiquette(
                    theme.fr(f"Averse : {sim.hauteur_mm:.1f} mm en "
                             f"{hydro.formater_duree(sim.duree_min)}"),
                    theme.BLEU, theme.BLEU_CLAIR, ft.Icons.WATER_DROP_OUTLINED),
                theme.etiquette(f"T = {sim.periode_retour} ans", theme.ARDOISE,
                                theme.GRIS_CLAIR, ft.Icons.EVENT_REPEAT),
                theme.etiquette(
                    theme.fr(f"Ruisselé : {sim.volume_ruissele_m3:.1f} m³"), theme.GRIS,
                    theme.GRIS_CLAIR, ft.Icons.SHOWER),
                theme.etiquette(
                    # Ce sont les pointes de chaque ouvrage, qui ne sont pas
                    # simultanées : ce n'est pas un volume stocké à un instant donné.
                    theme.fr(f"Somme des pointes : {sim.volume_stocke_m3:.1f} m³"), theme.VERT,
                    theme.VERT_CLAIR, ft.Icons.WATER),
            ],
            wrap=True, spacing=8, run_spacing=8,
        )
        avis: List[ft.Control] = []
        # Un ouvrage sans volume ne retient rien : annoncer qu'aucun ne déborde
        # reviendrait à valider un réseau dont il manque une pièce.
        non_encodes = sim.ouvrages_non_encodes
        if non_encodes:
            avis.append(theme.message(
                theme.fr(
                    f"{len(non_encodes)} ouvrage(s) sans volume encodé : "
                    + ", ".join(f"« {o.nom} »" for o in non_encodes)
                    + ". La simulation les traite comme des ouvrages de transit — ce "
                      "qu'ils reçoivent repart vers l'aval sans laminage. Cette synthèse "
                      "ne décrit donc pas encore le réseau projeté."), "erreur"))
        debordent = sim.ouvrages_en_debordement
        if debordent:
            avis.append(theme.message(
                theme.fr(
                    f"{len(debordent)} ouvrage(s) débordent pour cette averse : "
                    + ", ".join(f"« {o.nom} »" for o in debordent)
                    + f", pour un total de {sim.volume_debordement_m3:.1f} m³. "
                      "L'onglet Réseau propose un dimensionnement en cascade."), "erreur"))
        elif not non_encodes:
            avis.append(theme.message(
                "Aucun ouvrage ne déborde pour cette averse.", "succes"))
        if sim.temps_vidange_max_h > systeme.temps_vidange_max_h:
            avis.append(theme.message(
                theme.fr(f"La vidange la plus longue atteint "
                         f"{theme.duree_h(sim.temps_vidange_max_h)}, au-delà du maximum admis "
                         f"de {systeme.temps_vidange_max_h:.0f} h."),
                "alerte"))
        return [entete, tableau] + avis + [
            graphiques.construire(self._graphique_niveaux(sim), 280),
            graphiques.construire(self._graphique_exutoire(sim), 220),
        ]

    def _graphique_niveaux(self, sim) -> charts.Graphique:
        """Le remplissage de chaque ouvrage sur le même axe de temps."""
        couleurs = (charts.BLEU, charts.VERT, charts.ORANGE, charts.VIOLET, charts.ROUGE,
                    charts.GRIS)
        series = []
        for i, (ouvrage, res) in enumerate(sim.resultats):
            series.append(charts.Serie(ouvrage.nom, [(p.t_min, p.volume_m3) for p in res.pas],
                                       couleurs[i % len(couleurs)]))
        g = charts.Graphique(
            titre="Remplissage des bassins du réseau",
            axe_x="Temps [min]",
            axe_y="Volume stocké [m³]",
            series=series,
            reperes=[charts.Repere(sim.duree_min, "Fin de la pluie", charts.GRIS,
                                   vertical=True)],
        )
        return g

    def _graphique_exutoire(self, sim) -> charts.Graphique:
        """Ce que le réseau rejette réellement au milieu naturel."""
        apport = coeur.debit_a_l_exutoire_ls(self.etat.systeme, sim.hauteur_mm, sim.duree_min)
        points = [(0.0, apport.debit_ls(0.0))]
        for t0, t1, q in apport.segments:
            points.append((t0, q))
            points.append((t1, q))
        if not apport.segments:
            points.append((sim.duree_min, 0.0))
        return charts.Graphique(
            titre="Débit rejeté à l'exutoire",
            axe_x="Temps [min]",
            axe_y="Débit [l/s]",
            series=[charts.Serie("Rejet au milieu naturel", points, charts.VERT, aire=True)],
            reperes=[charts.Repere(sim.duree_min, "Fin de la pluie", charts.GRIS,
                                   vertical=True)],
        )

    # --------------------------------------------------------------- rendu
    def resultats(self) -> List[ft.Control]:
        if self.etat.systeme.aire_ponderee_m2 <= 0:
            return [theme.message(
                "Encodez au moins une surface dans l'onglet « Bassins versants » pour "
                "construire la synthèse du réseau.", "info")]
        return [self._tuiles()]

    def construire(self) -> List[ft.Control]:
        self.zone.controls = self.resultats()
        blocs: List[ft.Control] = [
            theme.section("Vue d'ensemble du système", self.zone, ft.Icons.INSIGHTS,
                          "Surfaces, volumes, pluie dimensionnante et vidange maximale"),
            theme.section("Schéma du réseau", self._schema(), ft.Icons.SCHEMA,
                          "De l'amont vers l'exutoire · le rapport PDF reprend le même schéma"),
        ]
        if self.etat.systeme.aire_ponderee_m2 > 0:
            blocs.append(theme.section("Simulation du système complet", self._simulateur(),
                                       ft.Icons.SCIENCE,
                                       "Tous les ouvrages pour la même averse"))
        return blocs
