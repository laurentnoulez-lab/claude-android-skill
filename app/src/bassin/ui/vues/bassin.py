"""Vue « Bassin » : encodage de l'ouvrage et simulation complète."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import hydro, rainfall, simulation
from ...reports import charts
from .. import graphiques, theme
from .base import Vue


class VueBassin(Vue):
    titre = "Bassin"
    icone = ft.Icons.WATER_DAMAGE
    sous_titre = "Ouvrage encodé et simulation"

    # ------------------------------------------------------------ formulaire
    def _formulaire(self) -> ft.Control:
        p = self.etat.projet
        b = self.etat.bassin

        def maj(champ: str):
            def _f(v: float) -> None:
                setattr(b, champ, v)
                self.etat.invalider()
            return _f

        def maj_charge(v: float) -> None:
            p.hauteur_charge_m = v
            self.etat.invalider()

        return ft.ResponsiveRow(
            [
                theme.champ_nombre("Volume tampon total", b.volume_total_m3, maj("volume_total_m3"),
                                   "m³", "jusqu'au trop-plein", on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 3}),
                theme.champ_nombre("Volume sous l'ajutage", b.volume_sous_ajutage_m3,
                                   maj("volume_sous_ajutage_m3"), "m³", "volume mort",
                                   on_valide=self.maj_resultats, col={"xs": 12, "sm": 6, "md": 3}),
                theme.champ_nombre("Surface de dispersion", b.surface_dispersion_m2,
                                   maj("surface_dispersion_m2"), "m²", "fond infiltrant",
                                   on_valide=self.maj_resultats, col={"xs": 12, "sm": 6, "md": 3}),
                *theme.champs_convertis(
                    "Débit d'ajutage", "l/s", b.debit_ajutage_ls,
                    "soit", "l/s/ha",
                    (10000.0 / p.aire_raccordee_m2) if p.aire_raccordee_m2 > 0 else None,
                    maj("debit_ajutage_ls"), on_valide=self.maj_resultats,
                    aide_a="orifice calibré",
                    aide_b=f"rapporté aux {p.aire_raccordee_m2:.0f} m² raccordés",
                    indisponible_b="encodez d'abord les surfaces incidentes",
                    decimales_a=3, decimales_b=2,
                    col_a={"xs": 12, "sm": 6, "md": 3}, col_b={"xs": 12, "sm": 6, "md": 3}),
                theme.champ_nombre("Charge sur l'ajutage", p.hauteur_charge_m, maj_charge, "m",
                                   "axe de l'orifice → trop-plein", on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

    def _reprendre(self, _=None) -> None:
        self.etat.reprendre_dimensionnement()
        self.rafraichir()
        self.notifier("Ouvrage pré-rempli à partir du scénario retenu.", "succes")

    # ------------------------------------------------------------- résultats
    def resultats(self) -> List[ft.Control]:
        p = self.etat.projet
        b = self.etat.bassin
        q_inf = simulation.debit_infiltration_ls(b.surface_dispersion_m2, p.k_infiltration_ms,
                                                 p.coef_securite_infiltration)
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

        if not self.etat.bassin_valide:
            return [
                entete,
                theme.message("Encodez un volume tampon et au moins une surface incidente "
                              "pour lancer la simulation.", "info"),
            ]

        sim = self.etat.simulation
        couleur = theme.ROUGE if sim.debordement else (theme.ORANGE if sim.statut == "LIMITE" else theme.VERT)
        tuiles = ft.ResponsiveRow(
            [
                ft.Container(theme.tuile(f"{sim.volume_max_m3:.1f}", "Volume stocké maximum", "m³",
                                         couleur, ft.Icons.WATER), col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(f"{sim.taux_remplissage * 100:.0f}", "Taux de remplissage", "%",
                                         couleur, ft.Icons.PERCENT), col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(theme.nombre(sim.temps_vidange_h, 1), "Temps de vidange après la pluie", "h",
                                         theme.ARDOISE, ft.Icons.TIMELAPSE,
                                         f"maximum admis : {p.temps_vidange_max_h:.0f} h"),
                             col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(f"{sim.volume_debordement_m3:.2f}", "Volume débordé", "m³",
                                         theme.ROUGE if sim.debordement else theme.GRIS,
                                         ft.Icons.OUTBOX), col={"xs": 12, "sm": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

        details = ft.Row(
            [
                theme.etiquette_statut(sim.statut),
                theme.etiquette(theme.fr(f"Pluie critique : {sim.hauteur_pluie_mm:.1f} mm en "
                                         f"{sim.duree_pluie_min:.0f} min"), theme.BLEU, theme.BLEU_CLAIR,
                                ft.Icons.WATER_DROP_OUTLINED),
                theme.etiquette(theme.fr(f"Volume ruisselé : {sim.volume_ruissele_m3:.1f} m³"), theme.GRIS,
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

        return [
            entete,
            theme.section("Simulation de l'événement critique",
                          ft.Column([details, tuiles] + avis, spacing=14),
                          ft.Icons.SCIENCE,
                          "Pluie de projet à intensité constante, durée la plus défavorable"),
            theme.section("Remplissage et vidange",
                          ft.Column(
                              [
                                  graphiques.construire(self._graphique_niveau(sim), 280),
                                  ft.Divider(height=18),
                                  graphiques.construire(self._graphique_debits(sim), 220),
                              ],
                              spacing=10),
                          ft.Icons.SHOW_CHART),
            theme.section("Bassin d'orage amont", self._panneau_amont(), ft.Icons.MERGE,
                          "Ouvrage situé en amont qui se déverse dans celui-ci"),
            theme.section("Simuler une ou plusieurs pluies", self._simulateur_manuel(), ft.Icons.TUNE,
                          "Cochez les durées à comparer, puis lancez la simulation"),
        ]

    def construire(self) -> List[ft.Control]:
        self.zone.controls = self.resultats()
        return [
            theme.section(
                "Caractéristiques de l'ouvrage",
                ft.Column(
                    [
                        self._formulaire(),
                        ft.Row(
                            [
                                theme.bouton_secondaire("Reprendre le dimensionnement",
                                                        ft.Icons.DOWNLOAD_DONE, self._reprendre),
                                theme.bouton_secondaire("Recalculer", ft.Icons.REFRESH,
                                                        lambda _: self.maj_resultats()),
                            ],
                            wrap=True,
                            spacing=10,
                        ),
                        ft.Text("« Reprendre le dimensionnement » pré-remplit l'ouvrage à partir du "
                                "scénario retenu (+5 % de marge).", size=11.5, color=theme.GRIS),
                    ],
                    spacing=14,
                ),
                ft.Icons.ARCHITECTURE,
                "Bassin, noue, puits perdu, structure alvéolaire…",
            ),
            self.zone,
        ]

    # ------------------------------------------------------------------
    def _graphique_niveau(self, sim) -> charts.Graphique:
        b = self.etat.bassin
        g = charts.Graphique(
            titre="Niveau de remplissage du bassin",
            axe_x="Temps [min]",
            axe_y="Volume stocké [m³]",
            series=[charts.Serie("Volume stocké", [(p.t_min, p.volume_m3) for p in sim.pas],
                                 charts.BLEU, aire=True)],
            reperes=[charts.Repere(b.volume_total_m3, theme.nombre(b.volume_total_m3, 1, "m³ de capacité"), charts.ROUGE)],
        )
        if b.volume_sous_ajutage_m3 > 0:
            g.reperes.append(charts.Repere(b.volume_sous_ajutage_m3,
                                           theme.nombre(b.volume_sous_ajutage_m3, 1, "m³ · axe de l'ajutage"),
                                           charts.VIOLET))
        g.reperes.append(charts.Repere(sim.duree_pluie_min, "Fin de la pluie", charts.GRIS, vertical=True))
        return g

    def _graphique_debits(self, sim) -> charts.Graphique:
        g = charts.Graphique(
            titre="Débits entrant et sortant",
            axe_x="Temps [min]",
            axe_y="Débit [l/s]",
            series=charts.series_debits(sim),
        )
        g.reperes.append(charts.Repere(sim.duree_pluie_min, "Fin de la pluie", charts.GRIS,
                                       vertical=True))
        return g

    # ------------------------------------------------- bassin d'orage amont
    def _panneau_amont(self) -> ft.Control:
        """Bassin d'orage situé en amont, qui se déverse dans l'ouvrage étudié."""
        p = self.etat.projet
        amont = p.amont
        details = ft.Column(spacing=12, visible=amont.actif)

        def maj(champ: str):
            def _f(v: float) -> None:
                setattr(amont, champ, v)
                # La surface du bassin versant amont entre dans la surface
                # raccordée dès que la case est cochée : l'ajutage suit.
                if champ == "surface_bv_m2":
                    p.recalculer_ajutage()
                self.etat.invalider()
            return _f

        def basculer_amont(e: ft.ControlEvent) -> None:
            amont.actif = bool(e.control.value)
            self.etat.invalider()
            self.rafraichir()

        def basculer_surface(e: ft.ControlEvent) -> None:
            # Le débit spécifique encodé s'applique à la surface raccordée : en
            # y ajoutant le bassin versant amont, l'ajutage augmente d'autant.
            p.compter_surface_amont(bool(e.control.value))
            self.etat.invalider()
            self.rafraichir()

        def proposer_volume(_=None) -> None:
            amont.volume_temporisation_m3 = hydro.volume_amont_minimal_m3(p)
            self.etat.invalider()
            self.rafraichir()

        # Le libellé d'un ft.Switch ne se replie pas : sur téléphone il était coupé.
        interrupteur = ft.Row(
            [
                ft.Switch(value=amont.actif, on_change=basculer_amont),
                ft.Text("Un bassin d'orage se déverse dans cet ouvrage", size=13,
                        expand=True, no_wrap=False),
            ],
            spacing=8,
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )
        if not amont.actif:
            return ft.Column([interrupteur], spacing=12)

        minimal = hydro.volume_amont_minimal_m3(p)
        res_amont = hydro.dimensionner_amont(p)
        champs = ft.ResponsiveRow(
            [
                theme.champ_nombre("Surface du bassin versant amont", amont.surface_bv_m2,
                                   maj("surface_bv_m2"), "m²", "surface qui ruisselle vers l'amont",
                                   on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 4}),
                theme.champ_nombre("Coefficient de ruissellement moyen", amont.coef_ruissellement,
                                   maj("coef_ruissellement"), "—", "pondéré sur ce bassin versant",
                                   on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 4}),
                theme.champ_nombre("Débit d'ajutage amont", amont.debit_ajutage_ls,
                                   maj("debit_ajutage_ls"), "l/s", "restitué vers cet ouvrage",
                                   on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 4}),
                theme.champ_nombre("Surface de dispersion amont", amont.surface_dispersion_m2,
                                   maj("surface_dispersion_m2"), "m²", "fond infiltrant du BO amont",
                                   on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 4}),
                *theme.champs_convertis(
                    "Vitesse d'infiltration amont", "m/s", amont.k_infiltration_ms,
                    "soit", "mm/h", 3.6e6, maj("k_infiltration_ms"),
                    on_valide=self.maj_resultats,
                    aide_a="essai in situ · 1e-5 ou 0,00001",
                    aide_b="équivalent, modifiable aussi",
                    col_a={"xs": 12, "sm": 6, "md": 4}, col_b={"xs": 12, "sm": 6, "md": 4}),
                theme.champ_nombre("Volume de temporisation amont", amont.volume_temporisation_m3,
                                   maj("volume_temporisation_m3"), "m³",
                                   f"minimum sans débordement : {theme.nombre(minimal, 1)} m³",
                                   on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 4}),
            ],
            spacing=12,
            run_spacing=12,
        )

        manque = amont.volume_temporisation_m3 + 1e-6 < minimal
        avis = theme.message(
            f"Le bassin amont déborderait : {theme.nombre(amont.volume_temporisation_m3, 1)} m³ "
            f"encodés pour {theme.nombre(minimal, 1)} m³ nécessaires. Son trop-plein arriverait "
            "d'un coup dans l'ouvrage aval.", "alerte") if manque else theme.message(
            f"Bassin amont suffisant : il restitue {theme.nombre(res_amont.debit_sortant_ls, 3)} l/s "
            f"(ajutage {theme.nombre(amont.debit_ajutage_ls, 3)} l/s + infiltration "
            f"{theme.nombre(res_amont.debit_infiltration_ls, 3)} l/s), vidange en "
            f"{res_amont.temps_vidange_hm}.", "info")

        return ft.Column(
            [
                interrupteur,
                champs,
                ft.Row(
                    [
                        theme.bouton_secondaire(
                            f"Proposer le volume minimal ({theme.nombre(minimal, 1)} m³)",
                            ft.Icons.AUTO_FIX_HIGH, proposer_volume),
                    ],
                    wrap=True, spacing=10,
                ),
                ft.Row(
                    [
                        ft.Checkbox(value=amont.inclure_bv_dans_ajutage,
                                    on_change=basculer_surface),
                        ft.Text("Compter la surface du bassin versant amont dans le débit "
                                "d'ajutage de cet ouvrage", size=13, expand=True, no_wrap=False),
                    ],
                    spacing=8,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
                ft.Text(
                    f"Surface raccordée : {theme.nombre(p.aire_raccordee_m2, 0)} m² · "
                    f"ajutage {theme.nombre(p.debit_ajutage_ls, 3)} l/s "
                    f"({theme.nombre(p.debit_specifique_ajutage_ls_ha, 3)} l/s/ha) · "
                    f"maximum admissible {theme.nombre(p.debit_fuite_admissible_ls, 3)} l/s",
                    size=11.5, color=theme.GRIS),
                ft.Text(
                    "En cochant, le débit spécifique encodé s'applique aussi au bassin versant "
                    f"amont : l'ajutage de cet ouvrage passerait à "
                    f"{theme.nombre(p.debit_specifique_ajutage_ls_ha * (p.aire_totale_m2 + amont.surface_bv_m2) / 10000.0, 3)} l/s."
                    if not amont.inclure_bv_dans_ajutage else
                    "Le débit spécifique encodé s'applique au bassin versant amont comme aux "
                    "surfaces propres ; décocher ramènerait l'ajutage à "
                    f"{theme.nombre(p.debit_specifique_ajutage_ls_ha * p.aire_totale_m2 / 10000.0, 3)} l/s.",
                    size=11.5, color=theme.GRIS, italic=True),
                avis,
            ],
            spacing=12,
        )

    # ----------------------------------------------------- simulation manuelle
    def _durees_choisies(self) -> List[float]:
        """Durées cochées, initialisées sur la plus proche de l'événement critique.

        La durée critique issue du balayage n'est pas forcément tabulée : on
        retient la durée normalisée la plus proche, sans quoi aucune case ne
        serait cochée au premier affichage.
        """
        if getattr(self, "_selection_durees", None) is None:
            depart = self.etat.simulation.duree_pluie_min if self.etat.simulation else 60.0
            proche = min(rainfall.QDF_DURATIONS_MIN, key=lambda d: abs(d - depart))
            self._selection_durees = {float(proche)}
        return sorted(self._selection_durees)

    def _simulateur_manuel(self) -> ft.Control:
        etat = self.etat
        zone = ft.Column(spacing=10)
        choisies = set(self._durees_choisies())

        recurrence = ft.Dropdown(
            label="Période de retour",
            value=str(etat.projet.periode_retour),
            options=[ft.dropdown.Option(str(rp), f"{rp} ans") for rp in rainfall.RETURN_PERIODS],
            dense=True,
            border_radius=10,
        )

        def basculer(duree: float):
            def _f(e: ft.ControlEvent) -> None:
                if e.control.selected:
                    self._selection_durees.add(duree)
                else:
                    self._selection_durees.discard(duree)
            return _f

        puces = ft.Row(
            [
                ft.Chip(
                    label=ft.Text(libelle, size=12),
                    selected=float(duree) in choisies,
                    on_select=basculer(float(duree)),
                    show_checkmark=True,
                )
                for duree, libelle in zip(rainfall.QDF_DURATIONS_MIN, rainfall.QDF_DURATION_LABELS)
            ],
            wrap=True, spacing=6, run_spacing=6,
        )

        def tout(valeur: bool):
            def _f(_=None) -> None:
                self._selection_durees = ({float(d) for d in rainfall.QDF_DURATIONS_MIN}
                                          if valeur else set())
                self.rafraichir()
            return _f

        def lancer(_=None) -> None:
            durees = sorted(self._selection_durees)
            if not durees:
                zone.controls = [theme.message("Cochez au moins une durée de pluie.", "info")]
                try:
                    zone.update()
                except Exception:
                    pass
                return
            rp = int(recurrence.value)
            src = rainfall.SourcePluie(etat.projet.commune_ins, rp, etat.projet.source_pluie)
            resultats = []
            for duree in durees:
                hauteur = src.hauteur(duree)
                apport = simulation.hydrogramme_amont(etat.projet, hauteur, duree)
                resultats.append(
                    (duree, hauteur,
                     simulation.simuler(etat.projet, etat.bassin, hauteur, duree, apport=apport)))
            zone.controls = self._resultats_multiples(resultats)
            try:
                zone.update()
            except Exception:
                pass

        return ft.Column(
            [
                ft.Text("Durées de pluie à simuler", size=12, weight=ft.FontWeight.W_600),
                puces,
                ft.Row(
                    [
                        theme.bouton_secondaire("Tout cocher", ft.Icons.DONE_ALL, tout(True)),
                        theme.bouton_secondaire("Tout décocher", ft.Icons.REMOVE_DONE, tout(False)),
                    ],
                    wrap=True, spacing=8,
                ),
                ft.ResponsiveRow(
                    [
                        ft.Container(recurrence, col={"xs": 12, "sm": 6, "md": 4}),
                        ft.Container(theme.bouton_principal("Simuler", ft.Icons.PLAY_ARROW, lancer),
                                     col={"xs": 12, "sm": 6, "md": 4}),
                    ],
                    spacing=12, run_spacing=12,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
                zone,
            ],
            spacing=12,
        )

    def _resultats_multiples(self, resultats) -> List[ft.Control]:
        """Tableau comparatif des durées simulées, puis courbe de la plus défavorable."""
        lignes = []
        for duree, hauteur, res in resultats:
            couleur, fond = theme.COULEURS_STATUT[res.statut]
            lignes.append(
                ft.DataRow(cells=[
                    ft.DataCell(ft.Text(hydro.formater_duree(duree), size=12,
                                        weight=ft.FontWeight.W_600)),
                    ft.DataCell(ft.Text(theme.nombre(hauteur, 1), size=12)),
                    ft.DataCell(ft.Text(theme.nombre(res.volume_max_m3, 1), size=12)),
                    ft.DataCell(ft.Text(theme.nombre(res.taux_remplissage * 100, 0), size=12)),
                    ft.DataCell(ft.Text(theme.nombre(res.volume_debordement_m3, 2), size=12,
                                        color=theme.ROUGE if res.debordement else None)),
                    ft.DataCell(ft.Text(res.temps_vidange_h_texte, size=12)),
                    ft.DataCell(ft.Container(
                        ft.Text(theme.LIBELLES_STATUT.get(res.statut, res.statut), size=11,
                                color=couleur, weight=ft.FontWeight.W_700),
                        bgcolor=fond, padding=ft.padding.symmetric(3, 8), border_radius=8)),
                ])
            )
        tableau = theme.tableau_defilant(
            ft.DataTable(
                columns=theme.entete_tableau(
                    ["Durée", "Pluie [mm]", "Pointe [m³]", "Remplissage [%]",
                     "Débordement [m³]", "Vidange", "Statut"]),
                rows=lignes,
                column_spacing=18,
                heading_row_height=38,
                data_row_max_height=42,
                divider_thickness=0.4,
            )
        )
        pire = max(resultats, key=lambda r: (r[2].volume_debordement_m3, r[2].volume_max_m3))
        controles: List[ft.Control] = [tableau]
        amont = pire[2].volume_amont_m3
        if amont > 0:
            controles.append(theme.etiquette(
                f"Apport du bassin amont : {theme.nombre(amont, 1)} m³ "
                f"(pointe {theme.nombre(pire[2].q_amont_max_ls, 2)} l/s)",
                theme.BLEU, theme.BLEU_CLAIR, ft.Icons.MERGE))
        controles.append(ft.Text(
            f"Courbe de la pluie la plus défavorable : {hydro.formater_duree(pire[0])}",
            size=12, weight=ft.FontWeight.W_600))
        controles.append(graphiques.construire(self._graphique_niveau(pire[2]), 240))
        return controles
