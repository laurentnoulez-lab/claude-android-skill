"""Rendu du schéma de réseau en contrôles Flet.

Le placement vient de :mod:`bassin.reports.schema`, partagé avec le rapport PDF :
l'écran et le document montrent donc rigoureusement le même schéma. Les boîtes
sont posées à leurs coordonnées exactes dans un ``Stack``, ce qui garantit qu'
aucune étiquette n'en chevauche une autre — la géométrie est calculée sans
recouvrement possible, et un test le vérifie.
"""

from __future__ import annotations

from typing import List

import flet as ft

from ..reports import schema as schema_module
from . import theme

#: Épaisseur des traits de raccordement, en pixels.
TRAIT = 1.6

COULEURS = {
    schema_module.OUVRAGE: (theme.BLEU, theme.BLEU_CLAIR),
    schema_module.VERSANT: (theme.VERT, theme.VERT_CLAIR),
    schema_module.EXUTOIRE: (theme.GRIS, theme.GRIS_CLAIR),
}

ICONES = {
    schema_module.OUVRAGE: ft.Icons.WATER_DAMAGE,
    schema_module.VERSANT: ft.Icons.LANDSCAPE,
    schema_module.EXUTOIRE: ft.Icons.FOREST,
}


def _boite(boite: schema_module.Boite, echelle: float) -> ft.Control:
    couleur, fond = COULEURS.get(boite.genre, (theme.GRIS, theme.GRIS_CLAIR))
    if boite.genre == schema_module.OUVRAGE:
        couleur, fond = theme.COULEURS_STATUT.get(boite.statut, (couleur, fond))
    titre = ft.Row(
        [
            ft.Icon(ICONES.get(boite.genre, ft.Icons.CIRCLE), color=couleur,
                    size=max(10.0, 11 * echelle)),
            ft.Text(boite.titre, size=max(9.0, 10.5 * echelle), weight=ft.FontWeight.W_700,
                    color=couleur, expand=True, max_lines=1,
                    overflow=ft.TextOverflow.ELLIPSIS),
        ],
        spacing=4, vertical_alignment=ft.CrossAxisAlignment.CENTER,
    )
    lignes = [
        ft.Text(ligne, size=max(8.0, 9 * echelle), color=theme.ARDOISE, max_lines=1,
                overflow=ft.TextOverflow.ELLIPSIS)
        for ligne in boite.lignes
    ]
    return ft.Container(
        content=ft.Column([titre] + lignes, spacing=1, tight=True),
        left=boite.x * echelle,
        top=boite.y * echelle,
        width=boite.largeur * echelle,
        height=boite.hauteur * echelle,
        padding=ft.padding.symmetric(4 * echelle, 7 * echelle),
        border_radius=8,
        bgcolor=fond,
        border=ft.border.all(1.4, couleur),
        tooltip=f"{boite.titre}\n" + "\n".join(boite.lignes),
    )


def _segments(fleche: schema_module.Fleche, echelle: float) -> List[ft.Control]:
    """Une flèche orthogonale, tracée en segments : pas de diagonale à gérer."""
    couleur = theme.GRIS if not fleche.pointille else theme.ORANGE
    controles: List[ft.Control] = []
    for (x0, y0), (x1, y1) in zip(fleche.points, fleche.points[1:]):
        if abs(y1 - y0) < 1e-9 and abs(x1 - x0) < 1e-9:
            continue
        gauche, droite = sorted((x0, x1))
        haut, bas = sorted((y0, y1))
        controles.append(ft.Container(
            left=gauche * echelle,
            top=haut * echelle - TRAIT / 2,
            width=max((droite - gauche) * echelle, TRAIT),
            height=max((bas - haut) * echelle, TRAIT),
            bgcolor=couleur,
            opacity=0.55 if fleche.pointille else 0.85,
            border_radius=1,
        ))
    # Pointe de flèche à l'arrivée, et libellé posé sur le coude vertical.
    if fleche.points:
        x, y = fleche.points[-1]
        controles.append(ft.Container(
            content=ft.Icon(ft.Icons.PLAY_ARROW, size=max(9.0, 11 * echelle), color=couleur),
            left=x * echelle - 6 * echelle,
            top=y * echelle - 6 * echelle,
        ))
    if fleche.libelle and len(fleche.points) >= 3:
        x = fleche.points[1][0]
        y = (fleche.points[1][1] + fleche.points[2][1]) / 2.0
        controles.append(ft.Container(
            content=ft.Text(fleche.libelle, size=max(7.0, 8 * echelle), color=theme.GRIS,
                            max_lines=1, overflow=ft.TextOverflow.ELLIPSIS),
            left=x * echelle - 28 * echelle,
            top=y * echelle - 16 * echelle,
            width=60 * echelle,
        ))
    return controles


def construire(schema: schema_module.Schema, echelle: float = 1.0) -> ft.Control:
    """Schéma complet, défilable horizontalement sur écran étroit."""
    if not schema.boites:
        return theme.message("Aucun ouvrage à représenter.", "info")
    controles: List[ft.Control] = []
    for fleche in schema.fleches:
        controles.extend(_segments(fleche, echelle))
    for boite in schema.boites:
        controles.append(_boite(boite, echelle))
    # Marge à droite et en bas : la pointe de flèche et les ombres débordent un peu.
    plan = ft.Stack(controles,
                    width=schema.largeur * echelle + 16,
                    height=schema.hauteur * echelle + 16)
    return ft.Row([ft.Container(plan, padding=ft.padding.only(4, 4, 12, 12))],
                  scroll=ft.ScrollMode.AUTO,
                  vertical_alignment=ft.CrossAxisAlignment.START)


def legende() -> ft.Control:
    return ft.Row(
        [
            theme.etiquette("Bassin versant", theme.VERT, theme.VERT_CLAIR, ft.Icons.LANDSCAPE),
            theme.etiquette("Bassin d'orage suffisant", theme.VERT, theme.VERT_CLAIR,
                            ft.Icons.WATER_DAMAGE),
            theme.etiquette("Bassin d'orage qui surverse", theme.ROUGE, theme.ROUGE_CLAIR,
                            ft.Icons.WATER_DAMAGE),
            theme.etiquette("Surverse vers le milieu naturel", theme.ORANGE, theme.ORANGE_CLAIR,
                            ft.Icons.CALL_SPLIT),
            theme.etiquette("Exutoire", theme.GRIS, theme.GRIS_CLAIR, ft.Icons.FOREST),
        ],
        wrap=True, spacing=8, run_spacing=8,
    )
