"""Vue « Pluies » : accès aux pluies statistiques du GTI (tables QDF)."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import rainfall
from ...reports import charts
from .. import graphiques, theme
from .base import Vue


class VuePluies(Vue):
    titre = "Pluies GTI"
    icone = ft.Icons.CLOUD
    sous_titre = "Tables QDF en mm et l/s/ha"

    def construire(self) -> List[ft.Control]:
        p = self.etat.projet
        unite = getattr(self, "_unite", "mm")
        commune = rainfall.commune_par_ins(p.commune_ins)
        src = rainfall.SourcePluie(p.commune_ins, p.periode_retour, p.source_pluie)

        def changer_unite(e: ft.ControlEvent) -> None:
            self._unite = e.control.value
            self.rafraichir()

        selecteur = ft.SegmentedButton(
            selected={unite},
            allow_multiple_selection=False,
            on_change=changer_unite,
            segments=[
                ft.Segment("mm", label=ft.Text("Hauteurs [mm]"), icon=ft.Icon(ft.Icons.WATER_DROP)),
                ft.Segment("lsha", label=ft.Text("Intensités [l/s/ha]"), icon=ft.Icon(ft.Icons.SPEED)),
            ],
        )

        table = (rainfall.table_qdf_mm(p.commune_ins, src.source) if unite == "mm"
                 else rainfall.table_qdf_ls_ha(p.commune_ins, src.source))

        colonnes = [ft.DataColumn(ft.Text("Durée", size=12, weight=ft.FontWeight.W_700))]
        colonnes += [
            ft.DataColumn(
                ft.Text(f"{rp} ans", size=12,
                        weight=ft.FontWeight.W_700 if rp == p.periode_retour else ft.FontWeight.W_500,
                        color=theme.BLEU if rp == p.periode_retour else None),
                numeric=True,
            )
            for rp in rainfall.RETURN_PERIODS
        ]
        lignes = []
        for i, ligne in enumerate(table):
            cellules = [ft.DataCell(ft.Text(rainfall.QDF_DURATION_LABELS[i], size=12,
                                            weight=ft.FontWeight.W_600))]
            for j, v in enumerate(ligne):
                selection = rainfall.RETURN_PERIODS[j] == p.periode_retour
                cellules.append(ft.DataCell(ft.Container(
                    ft.Text("—" if v is None else (f"{v:.1f}" if unite == "mm" else f"{v:.0f}"),
                            size=12, weight=ft.FontWeight.W_700 if selection else None,
                            color=theme.BLEU if selection else None),
                    bgcolor=theme.BLEU_CLAIR if selection else None,
                    padding=ft.padding.symmetric(4, 8),
                    border_radius=6,
                )))
            lignes.append(ft.DataRow(cells=cellules))

        tableau = ft.Row(
            [ft.Column([ft.DataTable(columns=colonnes, rows=lignes, column_spacing=16,
                                     heading_row_height=38, data_row_max_height=40,
                                     divider_thickness=0.4)], scroll=ft.ScrollMode.AUTO)],
            scroll=ft.ScrollMode.AUTO,
        )

        info: List[ft.Control] = []
        if commune and commune.a_montana:
            a1, b1, a2, b2, a3, b3 = rainfall.montana_coeffs(p.commune_ins, p.periode_retour)
            info.append(ft.Row(
                [
                    theme.etiquette(f"a₁ = {a1:.1f} · b₁ = {b1:.4f}  (t < 25 min)", theme.BLEU,
                                    theme.BLEU_CLAIR),
                    theme.etiquette(f"a₂ = {a2:.1f} · b₂ = {b2:.4f}  (25 → 6000 min)", theme.BLEU,
                                    theme.BLEU_CLAIR),
                    theme.etiquette(f"a₃ = {a3:.1f} · b₃ = {b3:.4f}  (t > 6000 min)", theme.BLEU,
                                    theme.BLEU_CLAIR),
                ],
                wrap=True, spacing=8, run_spacing=8,
            ))
            info.append(ft.Text("i [mm/h] = a × t[min]^(−b)", size=12.5, color=theme.GRIS))
        else:
            info.append(theme.message(
                "Commune sans coefficients de Montana : les valeurs proviennent des tables QDF.", "info"))

        courbe_mm = charts.Graphique(
            titre=f"Courbes intensité–durée–fréquence — {p.commune_nom}",
            axe_x="Durée de pluie",
            axe_y="Hauteur [mm]",
            x_log=True,
            series=[
                charts.Serie(
                    f"{rp} ans",
                    [(float(d), rainfall.SourcePluie(p.commune_ins, rp, src.source).hauteur(float(d)))
                     for d in rainfall.QDF_DURATIONS_MIN],
                    couleur,
                )
                for rp, couleur in zip((2, 25, 100, 200),
                                       (charts.VERT, charts.BLEU, charts.ORANGE, charts.ROUGE))
            ],
        )

        return [
            theme.section(
                f"Pluies statistiques — {p.commune_nom} (INS {p.commune_ins})",
                ft.Column(info, spacing=10),
                ft.Icons.CLOUD_QUEUE,
                f"Source : {src.libelle_source} · période de retour de projet : {p.periode_retour} ans",
            ),
            theme.section("Tables QDF", ft.Column([selecteur, tableau], spacing=12), ft.Icons.TABLE_ROWS,
                          "Lignes : durée de pluie · Colonnes : période de retour"),
            theme.section("Courbes IDF", graphiques.construire(courbe_mm, 300), ft.Icons.STACKED_LINE_CHART),
        ]
