"""Vue « Ajutage » : dimensionnement de l'orifice calibré (Torricelli)."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import orifice
from ...reports import charts
from .. import graphiques, theme
from ..composants import barre_ouvrage
from .base import Vue


class VueAjutage(Vue):
    titre = "Ajutage"
    icone = ft.Icons.ADJUST
    sous_titre = "Orifice calibré · Torricelli"

    def _formulaire(self) -> ft.Control:
        p = self.etat.projet
        debit = p.bassin.debit_ajutage_ls or p.debit_ajutage_ls

        def maj_debit(v: float) -> None:
            """L/s encodés : le débit est figé en valeur absolue."""
            p.fixer_ajutage_absolu(v)
            self.etat.invalider()

        def maj_debit_specifique(v: float) -> None:
            """L/(s·ha) encodés : le débit absolu suit la surface raccordée."""
            p.fixer_ajutage_specifique(v)
            self.etat.invalider()

        def maj_charge(v: float) -> None:
            p.hauteur_charge_m = v
            self.etat.invalider()

        def maj_cd(e: ft.ControlEvent) -> None:
            p.coef_debit_orifice = float(e.control.value)
            self.etat.invalider()
            self.maj_resultats()

        def basculer_commercial(e: ft.ControlEvent) -> None:
            """Retenir un diamètre de l'abaque, ou s'en tenir au théorique."""
            p.ajutage_diametre_commercial = bool(e.control.value)
            if not p.ajutage_diametre_commercial:
                p.diametre_ajutage_mm = None
            self.etat.invalider()
            self.rafraichir()

        return ft.ResponsiveRow(
            [
                *theme.champs_convertis(
                    "Débit d'ajutage visé", "l/s", debit,
                    "soit", "l/s/ha",
                    (10000.0 / p.aire_raccordee_m2) if p.aire_raccordee_m2 > 0 else None,
                    maj_debit, on_valide=self.maj_resultats,
                    appliquer_b=maj_debit_specifique,
                    aide_a=("calculé sur la surface raccordée" if p.ajutage_suit_la_surface
                            else "débit de fuite autorisé · valeur imposée"),
                    aide_b=f"rapporté aux {p.aire_raccordee_m2:.0f} m² raccordés"
                           " · maximum GTI : 5 l/s/ha",
                    indisponible_b="encodez d'abord les surfaces incidentes",
                    decimales_a=3, decimales_b=2,
                    col_a={"xs": 12, "sm": 6, "md": 3}, col_b={"xs": 12, "sm": 6, "md": 3}),
                theme.champ_nombre("Charge h", p.hauteur_charge_m, maj_charge, "m",
                                   "axe de l'orifice → trop-plein", on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 3},
                                   domaine="hauteur_charge_m"),
                theme.selecteur("Coefficient de débit Cd", str(p.coef_debit_orifice),
                                [(str(v), theme.fr(f"{v:.2f} — {lib}")) for lib, v in orifice.COEFFICIENTS_DEBIT],
                                maj_cd, col={"xs": 12, "md": 6}),
                ft.Container(
                    ft.Column(
                        [
                            ft.Checkbox(value=p.ajutage_diametre_commercial,
                                        label="Retenir un diamètre commercial",
                                        on_change=basculer_commercial),
                            ft.Text("décoché : l'ouvrage garde le diamètre théorique · "
                                    "coché : cliquez une ligne de l'abaque pour en choisir un autre",
                                    size=11, color=theme.GRIS),
                        ],
                        spacing=2,
                    ),
                    col={"xs": 12, "md": 6}),
            ],
            spacing=12,
            run_spacing=12,
        )

    def resultats(self) -> List[ft.Control]:
        p = self.etat.projet
        res = self.etat.orifice
        if res is None:
            return [theme.message("Encodez un débit d'ajutage et une charge non nuls "
                                  "pour dimensionner l'orifice.", "info")]

        tuiles = ft.ResponsiveRow(
            [
                ft.Container(theme.tuile(f"{res.diametre_mm:.1f}", "Diamètre théorique", "mm",
                                         theme.BLEU, ft.Icons.RADIO_BUTTON_UNCHECKED),
                             col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(f"{res.section_cm2:.2f}", "Section requise", "cm²",
                                         theme.ARDOISE, ft.Icons.CROP_SQUARE),
                             col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(
                    "—" if res.diametre_commercial_mm is None else f"{res.diametre_commercial_mm:.0f}",
                    "Diamètre commercial retenu", "mm", theme.VERT, ft.Icons.BUILD,
                    ("aucun · le diamètre théorique fait foi"
                     if not p.ajutage_diametre_commercial else
                     "choisi dans l'abaque" if p.diametre_ajutage_mm else
                     "valeur inférieure la plus proche")), col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(
                    "—" if res.debit_commercial_ls is None else f"{res.debit_commercial_ls:.3f}",
                    "Débit réel obtenu", "l/s", theme.VERT, ft.Icons.WATER,
                    f"visé : {res.debit_ls:.3f} l/s"), col={"xs": 12, "sm": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

        calcul = ft.Column(
            [
                ft.Text(theme.fr(f"A = Q / (Cd × √(2 g h)) = {res.debit_ls / 1000:.6f} / "
                                 f"({res.coef_debit:.2f} × √(2 × 9,81 × {res.charge_m:.2f})) = "
                                 f"{res.section_m2:.6f} m²"),
                        size=12, color=theme.GRIS, selectable=True),
                ft.Text(theme.fr(f"d = √(4 A / π) = {res.diametre_mm:.1f} mm  ·  "
                                 f"vitesse dans l'orifice v = {res.vitesse_ms:.2f} m/s"),
                        size=12, color=theme.GRIS, selectable=True),
            ],
            spacing=4,
        )

        def choisir(diametre: int):
            def _f(e: ft.ControlEvent) -> None:
                # Recliquer le diamètre retenu rend la main à la proposition de
                # l'application : le choix se défait comme il se fait.
                p.diametre_ajutage_mm = (None if p.diametre_ajutage_mm == diametre
                                         else float(diametre))
                p.ajutage_diametre_commercial = True
                self.etat.invalider()
                self.rafraichir()
            return _f

        abaque = orifice.abaque_diametres(p.hauteur_charge_m, p.coef_debit_orifice)
        tableau = theme.tableau_defilant(
            ft.DataTable(
                columns=theme.entete_tableau(["Diamètre [mm]", "Section [cm²]", "Débit [l/s]"]),
                rows=[
                    ft.DataRow(
                        cells=[
                            ft.DataCell(ft.Text(f"{d}", size=12,
                                                weight=ft.FontWeight.W_700
                                                if d == res.diametre_commercial_mm else None)),
                            ft.DataCell(ft.Text(theme.fr(f"{s:.2f}"), size=12)),
                            ft.DataCell(ft.Text(theme.fr(f"{q:.3f}"), size=12,
                                                color=theme.VERT if q <= res.debit_ls else theme.ROUGE)),
                        ],
                        selected=d == res.diametre_commercial_mm,
                        on_select_changed=choisir(d),
                    )
                    for d, s, q in abaque
                ],
                column_spacing=22,
                heading_row_height=38,
                data_row_max_height=36,
            )
        )

        g = charts.Graphique(
            titre="Loi de débit de l'orifice",
            axe_x="Charge [m]",
            axe_y="Débit [l/s]",
            series=[charts.Serie(
                f"DN {res.diametre_commercial_mm or res.diametre_mm:.0f} mm",
                orifice.courbe_hauteur_debit(res.diametre_commercial_mm or res.diametre_mm,
                                             max(res.charge_m, 0.1), res.coef_debit),
                charts.VERT)],
            reperes=[charts.Repere(res.debit_ls, theme.nombre(res.debit_ls, 2, "l/s de projet"), charts.ROUGE)],
        )

        corps = [tuiles, calcul]
        if res.depasse_le_debit_vise:
            # Le diamètre proposé reste sous le débit de fuite ; celui que l'on
            # retient à la main peut le dépasser. Le dire ici, avant le dossier.
            corps.append(theme.message(
                theme.fr(f"Le diamètre retenu laisse passer {res.debit_commercial_ls:.3f} l/s, "
                         f"soit plus que le débit de fuite visé de {res.debit_ls:.3f} l/s. "
                         "Le dossier le signale."), "alerte"))

        return [
            theme.section("Résultat", ft.Column(corps, spacing=14),
                          ft.Icons.CHECK_CIRCLE_OUTLINE),
            theme.section("Courbe de débit", graphiques.construire(g, 240), ft.Icons.SHOW_CHART,
                          "Le débit réel varie avec la charge ; le dimensionnement retient la charge "
                          "maximale."),
            theme.section("Abaque des diamètres commerciaux", tableau, ft.Icons.LIST_ALT,
                          theme.fr(f"Charge h = {p.hauteur_charge_m:.2f} m · "
                                   f"Cd = {p.coef_debit_orifice:.2f}")),
        ]

    def construire(self) -> List[ft.Control]:
        self.zone.controls = self.resultats()
        return [
            self.bloc_derive(lambda: barre_ouvrage(self)),
            theme.section(
                "Données de l'orifice",
                ft.Column(
                    [
                        self._formulaire(),
                        ft.Row([theme.bouton_secondaire("Recalculer", ft.Icons.REFRESH,
                                                        lambda _: self.maj_resultats())]),
                    ],
                    spacing=14,
                ),
                ft.Icons.SETTINGS,
                "Q = Cd × A × √(2 g h) — orifice en paroi mince, débit supposé constant pendant "
                "tout le remplissage au-dessus de l'orifice",
            ),
            self.zone,
        ]
