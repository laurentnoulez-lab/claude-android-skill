"""Vue « Ajutage » : dimensionnement de l'orifice calibré (Torricelli)."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import orifice
from ...reports import charts
from .. import graphiques, theme
from .base import Vue


class VueAjutage(Vue):
    titre = "Ajutage"
    icone = ft.Icons.ADJUST
    sous_titre = "Orifice calibré · Torricelli"

    def _formulaire(self) -> ft.Control:
        p = self.etat.projet
        debit = p.bassin.debit_ajutage_ls or p.debit_ajutage_ls

        def maj_debit(v: float) -> None:
            p.bassin.debit_ajutage_ls = v
            p.debit_ajutage_ls = v
            self.etat.invalider()

        def maj_charge(v: float) -> None:
            p.hauteur_charge_m = v
            self.etat.invalider()

        def maj_cd(e: ft.ControlEvent) -> None:
            p.coef_debit_orifice = float(e.control.value)
            self.etat.invalider()
            self.maj_resultats()

        return ft.ResponsiveRow(
            [
                theme.champ_nombre("Débit d'ajutage visé", debit, maj_debit, "l/s",
                                   "débit de fuite autorisé", on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 3}),
                theme.champ_nombre("Charge h", p.hauteur_charge_m, maj_charge, "m",
                                   "axe de l'orifice → trop-plein", on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 3}),
                theme.selecteur("Coefficient de débit Cd", str(p.coef_debit_orifice),
                                [(str(v), f"{v:.2f} — {lib}") for lib, v in orifice.COEFFICIENTS_DEBIT],
                                maj_cd, col={"xs": 12, "md": 6}),
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
                    "valeur inférieure la plus proche"), col={"xs": 12, "sm": 6, "md": 3}),
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
                ft.Text(f"A = Q / (Cd × √(2 g h)) = {res.debit_ls / 1000:.6f} / "
                        f"({res.coef_debit:.2f} × √(2 × 9,81 × {res.charge_m:.2f})) = "
                        f"{res.section_m2:.6f} m²", size=12, color=theme.GRIS, selectable=True),
                ft.Text(f"d = √(4 A / π) = {res.diametre_mm:.1f} mm  ·  "
                        f"vitesse dans l'orifice v = {res.vitesse_ms:.2f} m/s",
                        size=12, color=theme.GRIS, selectable=True),
            ],
            spacing=4,
        )

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
                            ft.DataCell(ft.Text(f"{s:.2f}", size=12)),
                            ft.DataCell(ft.Text(f"{q:.3f}", size=12,
                                                color=theme.VERT if q <= res.debit_ls else theme.ROUGE)),
                        ],
                        selected=d == res.diametre_commercial_mm,
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
            reperes=[charts.Repere(res.debit_ls, f"Débit de projet {res.debit_ls:.2f} l/s", charts.ROUGE)],
        )

        return [
            theme.section("Résultat", ft.Column([tuiles, calcul], spacing=14),
                          ft.Icons.CHECK_CIRCLE_OUTLINE),
            theme.section("Courbe de débit", graphiques.construire(g, 240), ft.Icons.SHOW_CHART,
                          "Le débit réel varie avec la charge ; le dimensionnement retient la charge "
                          "maximale."),
            theme.section("Abaque des diamètres commerciaux", tableau, ft.Icons.LIST_ALT,
                          f"Charge h = {p.hauteur_charge_m:.2f} m · Cd = {p.coef_debit_orifice:.2f}"),
        ]

    def construire(self) -> List[ft.Control]:
        self.zone.controls = self.resultats()
        return [
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
