"""Vue « Table QDF » : pluies absorbées par l'ouvrage sans débordement."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import rainfall
from .. import theme
from .base import Vue


class VueTableQDF(Vue):
    titre = "Table QDF"
    icone = ft.Icons.TABLE_CHART
    sous_titre = "Pluies absorbées sans débordement"

    def construire(self) -> List[ft.Control]:
        if not self.etat.bassin_valide:
            return [theme.message(
                "Encodez d'abord un bassin (onglet « Bassin ») pour construire la table QDF.", "info")]

        table = self.etat.table_acceptation
        assert table is not None
        p = self.etat.projet
        mode = getattr(self, "_mode", "volume")

        def changer_mode(e: ft.ControlEvent) -> None:
            self._mode = e.control.value
            self.rafraichir()

        selecteur = ft.SegmentedButton(
            selected={mode},
            allow_multiple_selection=False,
            on_change=changer_mode,
            segments=[
                ft.Segment("volume", label=ft.Text("Volume requis [m³]"), icon=ft.Icon(ft.Icons.WATER)),
                ft.Segment("taux", label=ft.Text("Remplissage [%]"), icon=ft.Icon(ft.Icons.PERCENT)),
                ft.Segment("pluie", label=ft.Text("Pluie [mm]"), icon=ft.Icon(ft.Icons.WATER_DROP)),
            ],
        )

        colonnes = [ft.DataColumn(ft.Text("Durée", size=12, weight=ft.FontWeight.W_700))]
        colonnes += [
            ft.DataColumn(
                ft.Text(f"{rp} ans", size=12,
                        weight=ft.FontWeight.W_700 if rp == p.periode_retour else ft.FontWeight.W_500,
                        color=theme.BLEU if rp == p.periode_retour else None),
                numeric=True,
            )
            for rp in table.periodes_retour
        ]

        lignes: List[ft.DataRow] = []
        for i, _ in enumerate(table.durees_min):
            cellules = [ft.DataCell(ft.Text(rainfall.QDF_DURATION_LABELS[i], size=12,
                                            weight=ft.FontWeight.W_600))]
            for j in range(len(table.periodes_retour)):
                c = table.cellules[i][j]
                couleur, fond = theme.COULEURS_STATUT[c.statut]
                if mode == "volume":
                    texte = f"{c.volume_requis_m3:.1f}"
                elif mode == "taux":
                    texte = "∞" if c.capacite_m3 <= 0 else f"{min(c.taux, 9.99) * 100:.0f}"
                else:
                    texte = f"{c.hauteur_mm:.1f}"
                cellules.append(
                    ft.DataCell(
                        ft.Container(
                            ft.Text(texte, size=12, color=couleur, weight=ft.FontWeight.W_600,
                                    text_align=ft.TextAlign.CENTER),
                            bgcolor=fond,
                            padding=ft.padding.symmetric(4, 8),
                            border_radius=6,
                            alignment=ft.alignment.center,
                            tooltip=(f"{rainfall.QDF_DURATION_LABELS[i]} · T = {c.periode_retour} ans\n"
                                     f"Pluie : {c.hauteur_mm:.1f} mm\n"
                                     f"Volume requis : {c.volume_requis_m3:.1f} m³ / "
                                     f"{c.capacite_m3:.1f} m³\n"
                                     f"Vidange : {c.temps_vidange_h:.1f} h"),
                        )
                    )
                )
            lignes.append(ft.DataRow(cells=cellules))

        tableau = ft.Row(
            [ft.Column([ft.DataTable(columns=colonnes, rows=lignes, column_spacing=14,
                                     heading_row_height=38, data_row_max_height=42,
                                     divider_thickness=0.4)],
                       scroll=ft.ScrollMode.AUTO)],
            scroll=ft.ScrollMode.AUTO,
        )

        rp_max = table.periode_retour_max_acceptee()
        if rp_max:
            bandeau = theme.message(
                f"L'ouvrage absorbe sans débordement toutes les pluies jusqu'à la récurrence "
                f"{rp_max} ans (toutes durées confondues).",
                "succes" if rp_max >= p.periode_retour else "alerte",
            )
        else:
            bandeau = theme.message(
                "L'ouvrage déborde déjà pour la pluie de récurrence 2 ans.", "erreur")

        critiques = table.durees_critiques(p.periode_retour)
        if critiques:
            libelles = ", ".join(
                rainfall.QDF_DURATION_LABELS[list(table.durees_min).index(d)] for d in critiques)
            bandeau_2 = theme.message(
                f"Débordement pour la récurrence de projet ({p.periode_retour} ans) "
                f"aux durées suivantes : {libelles}.", "erreur")
        else:
            bandeau_2 = theme.message(
                f"Aucun débordement pour la récurrence de projet ({p.periode_retour} ans), "
                "quelle que soit la durée de pluie.", "succes")

        legende = ft.Row(
            [
                theme.etiquette("Absorbé", theme.VERT, theme.VERT_CLAIR, ft.Icons.CHECK_CIRCLE),
                theme.etiquette("Limite (> 95 % de la capacité)", theme.ORANGE, theme.ORANGE_CLAIR,
                                ft.Icons.WARNING_AMBER),
                theme.etiquette("Débordement", theme.ROUGE, theme.ROUGE_CLAIR, ft.Icons.ERROR),
            ],
            wrap=True,
            spacing=8,
            run_spacing=8,
        )

        return [
            theme.section(
                "Capacité d'absorption du bassin",
                ft.Column([bandeau, bandeau_2, legende], spacing=12),
                ft.Icons.VERIFIED,
                f"Bassin de {self.etat.bassin.volume_total_m3:.1f} m³ · "
                f"{self.etat.projet.commune_nom}",
            ),
            theme.section(
                "Table QDF de l'ouvrage",
                ft.Column([selecteur, ft.Container(tableau, padding=ft.padding.only(top=8))], spacing=10),
                ft.Icons.GRID_ON,
                "Lignes : durée de pluie · Colonnes : période de retour",
            ),
        ]
