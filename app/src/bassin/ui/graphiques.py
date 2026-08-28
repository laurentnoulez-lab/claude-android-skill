"""Conversion des graphiques métier en LineChart Flet (rendu interactif)."""

from __future__ import annotations

import math
from typing import List, Optional, Tuple

import flet as ft

from ..reports import charts
from . import theme


def _couleur(c: charts.Couleur) -> str:
    return "#%02X%02X%02X" % c


def _etiquettes_x(g: charts.Graphique, xmin: float, xmax: float, log: bool) -> List[ft.ChartAxisLabel]:
    duree = g.axe_x.lower().startswith(("durée", "duree", "temps"))
    valeurs: List[float] = []
    if log:
        d = 10 ** math.floor(math.log10(max(10 ** xmin, 1e-9)))
        while d <= 10 ** xmax * 10:
            for m in (1, 3):
                v = d * m
                if 10 ** xmin <= v <= 10 ** xmax:
                    valeurs.append(v)
            d *= 10
    else:
        valeurs = charts.graduations(xmin, xmax, 5)
    etiquettes = []
    for v in valeurs:
        texte = charts.format_duree_courte(v) if duree else charts.format_nombre(v)
        etiquettes.append(
            ft.ChartAxisLabel(
                value=math.log10(v) if log else v,
                label=ft.Container(ft.Text(texte, size=10, color=theme.GRIS), padding=ft.padding.only(top=6)),
            )
        )
    return etiquettes


def construire(g: charts.Graphique, hauteur: int = 300) -> ft.Control:
    """Construit un graphique Flet a partir de la description commune."""
    if not any(s.points for s in g.series):
        return ft.Container(
            content=ft.Text("Données insuffisantes pour tracer le graphique.", color=theme.GRIS, size=12),
            padding=20,
        )
    log = g.x_log
    xmin, xmax, ymin, ymax = charts.bornes(g)
    if log:
        xmin, xmax = math.log10(max(xmin, 1e-9)), math.log10(max(xmax, 1e-9))

    def tx(x: float) -> float:
        return math.log10(max(x, 1e-9)) if log else x

    series: List[ft.LineChartData] = []
    for s in g.series:
        if not s.points:
            continue
        series.append(
            ft.LineChartData(
                data_points=[ft.LineChartDataPoint(tx(x), round(y, 3)) for x, y in s.points],
                color=_couleur(s.couleur),
                stroke_width=2.2,
                stroke_cap_round=True,
                curved=False,
                dash_pattern=[6, 4] if s.pointilles else None,
                below_line_bgcolor=ft.Colors.with_opacity(0.18, _couleur(s.couleur)) if s.aire else None,
            )
        )
    for r in g.reperes:
        if r.vertical:
            points = [ft.LineChartDataPoint(tx(r.valeur), ymin), ft.LineChartDataPoint(tx(r.valeur), ymax)]
        else:
            points = [ft.LineChartDataPoint(xmin, r.valeur), ft.LineChartDataPoint(xmax, r.valeur)]
        series.append(
            ft.LineChartData(data_points=points, color=_couleur(r.couleur), stroke_width=1.6,
                             dash_pattern=[5, 5], curved=False)
        )

    intervalle_y = None
    graduations_y = charts.graduations(ymin, ymax, 5)
    if len(graduations_y) > 1:
        intervalle_y = graduations_y[1] - graduations_y[0]

    graphique = ft.LineChart(
        data_series=series,
        min_x=xmin,
        max_x=xmax,
        min_y=ymin,
        max_y=ymax,
        expand=True,
        animate=300,
        border=ft.border.only(
            left=ft.BorderSide(1, ft.Colors.OUTLINE_VARIANT),
            bottom=ft.BorderSide(1, ft.Colors.OUTLINE_VARIANT),
        ),
        horizontal_grid_lines=ft.ChartGridLines(
            interval=intervalle_y, color=ft.Colors.OUTLINE_VARIANT, width=1),
        left_axis=ft.ChartAxis(
            labels=[
                ft.ChartAxisLabel(value=v, label=ft.Text(charts.format_nombre(v), size=10, color=theme.GRIS))
                for v in graduations_y
            ],
            labels_size=44,
        ),
        bottom_axis=ft.ChartAxis(labels=_etiquettes_x(g, xmin, xmax, log), labels_size=34),
        tooltip_bgcolor=ft.Colors.with_opacity(0.9, ft.Colors.INVERSE_SURFACE),
    )

    legende = ft.Row(
        [
            ft.Row(
                [
                    ft.Container(width=14, height=4, bgcolor=_couleur(e.couleur), border_radius=2),
                    ft.Text(e.nom, size=11, color=theme.GRIS),
                ],
                spacing=6,
                tight=True,
            )
            for e in list(g.series) + list(g.reperes)
        ],
        wrap=True,
        spacing=16,
        run_spacing=4,
    )
    entete: List[ft.Control] = []
    if g.titre:
        entete.append(ft.Text(g.titre, size=14, weight=ft.FontWeight.W_700))
    entete.append(
        ft.Row(
            [ft.Text(g.axe_y, size=11, color=theme.GRIS),
             ft.Text(g.axe_x, size=11, color=theme.GRIS)],
            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
        )
    )
    return ft.Column(
        entete + [ft.Container(graphique, height=hauteur, padding=ft.padding.only(top=6, right=8)), legende],
        spacing=6,
    )


def image_png(g: charts.Graphique, largeur: int = 900, hauteur: int = 420) -> ft.Control:
    """Rendu PNG (identique aux rapports) - utilisé en repli."""
    import base64

    png = charts.rendre_png(g, largeur, hauteur)
    return ft.Image(src_base64=base64.b64encode(png).decode(), fit=ft.ImageFit.CONTAIN)
