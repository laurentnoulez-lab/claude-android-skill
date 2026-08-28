"""Vue « Bassin » : encodage de l'ouvrage et simulation complète."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import simulation
from ...reports import charts
from .. import graphiques, theme
from .base import Vue


class VueBassin(Vue):
    titre = "Bassin"
    icone = ft.Icons.WATER_DAMAGE
    sous_titre = "Ouvrage encodé et simulation"

    def construire(self) -> List[ft.Control]:
        p = self.etat.projet
        b = self.etat.bassin

        def maj(champ: str):
            def _f(v: float) -> None:
                self.etat.definir_bassin(champ, v)
                self.rafraichir()
            return _f

        def maj_charge(v: float) -> None:
            self.etat.definir("hauteur_charge_m", v)
            self.rafraichir()

        q_inf = simulation.debit_infiltration_ls(b.surface_dispersion_m2, p.k_infiltration_ms,
                                                 p.coef_securite_infiltration)
        saisie = ft.ResponsiveRow(
            [
                theme.champ_nombre("Volume tampon total", b.volume_total_m3, maj("volume_total_m3"),
                                   "m³", "Jusqu'au niveau du trop-plein", decimales=1,
                                   col={"xs": 6, "md": 3}),
                theme.champ_nombre("Volume sous l'ajutage", b.volume_sous_ajutage_m3,
                                   maj("volume_sous_ajutage_m3"), "m³",
                                   "Volume mort sous l'axe de l'orifice", decimales=1,
                                   col={"xs": 6, "md": 3}),
                theme.champ_nombre("Surface de dispersion", b.surface_dispersion_m2,
                                   maj("surface_dispersion_m2"), "m²", "Fond infiltrant du bassin",
                                   decimales=1, col={"xs": 6, "md": 3}),
                theme.champ_nombre("Débit d'ajutage", b.debit_ajutage_ls, maj("debit_ajutage_ls"),
                                   "l/s", "Orifice calibré", decimales=3, col={"xs": 6, "md": 3}),
                theme.champ_nombre("Charge sur l'ajutage", p.hauteur_charge_m, maj_charge, "m",
                                   "Axe de l'orifice → trop-plein", decimales=2,
                                   col={"xs": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

        entete = ft.Row(
            [
                theme.etiquette(f"Volume utile au-dessus de l'ajutage : {b.volume_tampon_m3:.1f} m³",
                                theme.BLEU, theme.BLEU_CLAIR, ft.Icons.STACKED_LINE_CHART),
                theme.etiquette(f"Q infiltration = {q_inf:.3f} l/s", theme.VERT, theme.VERT_CLAIR,
                                ft.Icons.WATER_DROP),
                theme.etiquette(f"Q total = {q_inf + b.debit_ajutage_ls:.3f} l/s", theme.GRIS,
                                theme.GRIS_CLAIR, ft.Icons.CALL_MERGE),
            ],
            wrap=True,
            spacing=8,
            run_spacing=8,
        )

        actions = ft.Row(
            [
                theme.bouton_secondaire("Reprendre le dimensionnement", ft.Icons.DOWNLOAD_DONE,
                                        self._reprendre),
                ft.Text("Pré-remplit l'ouvrage à partir du scénario retenu (+5 % de marge).",
                        size=11.5, color=theme.GRIS),
            ],
            wrap=True,
            spacing=12,
        )

        contenu: List[ft.Control] = [
            theme.section("Caractéristiques de l'ouvrage",
                          ft.Column([saisie, entete, actions], spacing=14),
                          ft.Icons.ARCHITECTURE,
                          "Bassin, noue, puits perdu, structure alvéolaire…"),
        ]

        if not self.etat.bassin_valide:
            contenu.append(theme.message(
                "Encodez un volume tampon et au moins une surface incidente pour lancer la simulation.",
                "info"))
            return contenu

        sim = self.etat.simulation
        assert sim is not None
        couleur = theme.ROUGE if sim.debordement else (theme.ORANGE if sim.statut == "LIMITE" else theme.VERT)
        tuiles = ft.ResponsiveRow(
            [
                ft.Container(theme.tuile(f"{sim.volume_max_m3:.1f}", "Volume stocké maximum", "m³",
                                         couleur, ft.Icons.WATER),
                             col={"xs": 6, "md": 3}),
                ft.Container(theme.tuile(f"{sim.taux_remplissage * 100:.0f}", "Taux de remplissage", "%",
                                         couleur, ft.Icons.PERCENT),
                             col={"xs": 6, "md": 3}),
                ft.Container(theme.tuile(f"{sim.temps_vidange_h:.1f}", "Temps de vidange", "h",
                                         theme.ARDOISE, ft.Icons.TIMELAPSE,
                                         f"maximum admis : {p.temps_vidange_max_h:.0f} h"),
                             col={"xs": 6, "md": 3}),
                ft.Container(theme.tuile(f"{sim.volume_debordement_m3:.2f}", "Volume débordé", "m³",
                                         theme.ROUGE if sim.debordement else theme.GRIS,
                                         ft.Icons.OUTBOX),
                             col={"xs": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

        details = ft.Row(
            [
                theme.etiquette_statut(sim.statut),
                theme.etiquette(f"Pluie critique : {sim.hauteur_pluie_mm:.1f} mm en "
                                f"{sim.duree_pluie_min:.0f} min", theme.BLEU, theme.BLEU_CLAIR,
                                ft.Icons.WATER_DROP_OUTLINED),
                theme.etiquette(f"Volume ruisselé : {sim.volume_ruissele_m3:.1f} m³", theme.GRIS,
                                theme.GRIS_CLAIR, ft.Icons.SHOWER),
                theme.etiquette(f"T = {p.periode_retour} ans", theme.ARDOISE, theme.GRIS_CLAIR,
                                ft.Icons.EVENT_REPEAT),
            ],
            wrap=True,
            spacing=8,
            run_spacing=8,
        )

        avis: List[ft.Control] = []
        if sim.debordement:
            avis.append(theme.message(
                f"Le bassin déborde de {sim.volume_debordement_m3:.2f} m³ pour la pluie de projet "
                f"({p.periode_retour} ans). Augmentez le volume, la surface de dispersion "
                "ou le débit d'ajutage.", "erreur"))
        elif sim.taux_remplissage > 0.95:
            avis.append(theme.message(
                "L'ouvrage est rempli à plus de 95 % : il n'y a pratiquement pas de marge.", "alerte"))
        else:
            avis.append(theme.message(
                f"L'ouvrage absorbe la pluie de projet avec {(1 - sim.taux_remplissage) * 100:.0f} % "
                "de réserve.", "succes"))
        if sim.temps_vidange_h > p.temps_vidange_max_h:
            avis.append(theme.message(
                f"Temps de vidange de {sim.temps_vidange_h:.1f} h supérieur au maximum admis "
                f"({p.temps_vidange_max_h:.0f} h).", "alerte"))

        contenu.append(
            theme.section("Simulation de l'événement critique",
                          ft.Column([details, tuiles] + avis, spacing=14),
                          ft.Icons.SCIENCE,
                          "Pluie de projet à intensité constante, recherche de la durée la plus défavorable")
        )
        contenu.append(
            theme.section("Remplissage et vidange",
                          ft.Column(
                              [
                                  graphiques.construire(self._graphique_niveau(sim), 300),
                                  ft.Divider(height=18),
                                  graphiques.construire(self._graphique_debits(sim), 240),
                              ],
                              spacing=10,
                          ),
                          ft.Icons.SHOW_CHART)
        )
        contenu.append(
            theme.section("Simuler une autre pluie",
                          self._simulateur_manuel(),
                          ft.Icons.TUNE,
                          "Choisissez une durée et une récurrence pour visualiser le comportement de l'ouvrage")
        )
        return contenu

    # ------------------------------------------------------------------
    def _reprendre(self, _=None) -> None:
        self.etat.reprendre_dimensionnement()
        self.rafraichir()
        self.notifier("Ouvrage pré-rempli à partir du scénario retenu.", "succes")

    def _graphique_niveau(self, sim) -> charts.Graphique:
        b = self.etat.bassin
        g = charts.Graphique(
            titre="Niveau de remplissage du bassin",
            axe_x="Temps [min]",
            axe_y="Volume stocké [m³]",
            series=[charts.Serie("Volume stocké", [(p.t_min, p.volume_m3) for p in sim.pas],
                                 charts.BLEU, aire=True)],
            reperes=[charts.Repere(b.volume_total_m3, f"Capacité {b.volume_total_m3:.1f} m³", charts.ROUGE)],
        )
        if b.volume_sous_ajutage_m3 > 0:
            g.reperes.append(charts.Repere(b.volume_sous_ajutage_m3,
                                           f"Axe de l'ajutage {b.volume_sous_ajutage_m3:.1f} m³",
                                           charts.VIOLET))
        g.reperes.append(charts.Repere(sim.duree_pluie_min, "Fin de la pluie", charts.GRIS, vertical=True))
        return g

    def _graphique_debits(self, sim) -> charts.Graphique:
        return charts.Graphique(
            titre="Débits entrant et sortant",
            axe_x="Temps [min]",
            axe_y="Débit [l/s]",
            series=[
                charts.Serie("Débit entrant", [(p.t_min, p.q_entrant_ls) for p in sim.pas], charts.BLEU),
                charts.Serie("Débit sortant", [(p.t_min, p.q_sortant_ls) for p in sim.pas], charts.VERT),
                charts.Serie("Débordement", [(p.t_min, p.q_debordement_ls) for p in sim.pas], charts.ROUGE),
            ],
        )

    def _simulateur_manuel(self) -> ft.Control:
        from ...core import rainfall

        etat = self.etat
        zone = ft.Column(spacing=10)
        duree = ft.Dropdown(
            label="Durée de pluie",
            value=str(etat.simulation.duree_pluie_min if etat.simulation else 60),
            options=[ft.dropdown.Option(str(float(d)), lib)
                     for d, lib in zip(rainfall.QDF_DURATIONS_MIN, rainfall.QDF_DURATION_LABELS)],
            dense=True,
            border_radius=10,
        )
        recurrence = ft.Dropdown(
            label="Période de retour",
            value=str(etat.projet.periode_retour),
            options=[ft.dropdown.Option(str(rp), f"{rp} ans") for rp in rainfall.RETURN_PERIODS],
            dense=True,
            border_radius=10,
        )

        def lancer(_=None) -> None:
            d = float(duree.value)
            rp = int(recurrence.value)
            src = rainfall.SourcePluie(etat.projet.commune_ins, rp, etat.projet.source_pluie)
            hauteur = src.hauteur(d)
            res = simulation.simuler(etat.projet, etat.bassin, hauteur, d)
            zone.controls = [
                ft.Row(
                    [
                        theme.etiquette_statut(res.statut),
                        theme.etiquette(f"Pluie {hauteur:.1f} mm", theme.BLEU, theme.BLEU_CLAIR),
                        theme.etiquette(f"Pointe {res.volume_max_m3:.1f} m³", theme.ARDOISE,
                                        theme.GRIS_CLAIR),
                        theme.etiquette(f"Remplissage {res.taux_remplissage * 100:.0f} %", theme.ARDOISE,
                                        theme.GRIS_CLAIR),
                        theme.etiquette(f"Vidange {res.temps_vidange_h:.1f} h", theme.ARDOISE,
                                        theme.GRIS_CLAIR),
                    ],
                    wrap=True,
                    spacing=8,
                    run_spacing=8,
                ),
                graphiques.construire(self._graphique_niveau(res), 260),
            ]
            zone.update()

        return ft.Column(
            [
                ft.ResponsiveRow(
                    [
                        ft.Container(duree, col={"xs": 6, "md": 4}),
                        ft.Container(recurrence, col={"xs": 6, "md": 3}),
                        ft.Container(theme.bouton_principal("Simuler", ft.Icons.PLAY_ARROW, lancer),
                                     col={"xs": 12, "md": 3}),
                    ],
                    spacing=12,
                    run_spacing=12,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
                zone,
            ],
            spacing=12,
        )
