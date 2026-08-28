"""HydroBassin — dimensionnement de bassins d'orage (méthode rationnelle, pluies GTI).

Point d'entrée de l'application Flet (Windows, Android, web).
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import flet as ft  # noqa: E402

from bassin import __app_name__, __version__  # noqa: E402
from bassin.core.model import LIBELLES_SCENARIOS  # noqa: E402
from bassin.ui import theme  # noqa: E402
from bassin.ui.state import CLE_STOCKAGE, EtatApplication  # noqa: E402
from bassin.ui.vues.ajutage import VueAjutage  # noqa: E402
from bassin.ui.vues.bassin import VueBassin  # noqa: E402
from bassin.ui.vues.dimensionnement import VueDimensionnement  # noqa: E402
from bassin.ui.vues.pluies import VuePluies  # noqa: E402
from bassin.ui.vues.projet import VueProjet  # noqa: E402
from bassin.ui.vues.qdf import VueTableQDF  # noqa: E402
from bassin.ui.vues.rapport import VueRapport  # noqa: E402

LARGEUR_COMPACTE = 840


def main(page: ft.Page) -> None:
    page.title = f"{__app_name__} — dimensionnement de bassins d'orage"
    page.window.width = 1280
    page.window.height = 860
    page.window.min_width = 380
    page.window.min_height = 560
    sombre = False
    try:
        sombre = page.client_storage.get("hydrobassin.sombre") or False
    except Exception:
        sombre = False
    theme.appliquer_theme(page, bool(sombre))

    etat = EtatApplication()
    try:
        sauvegarde = page.client_storage.get(CLE_STOCKAGE)
        if sauvegarde:
            etat.charger_json(sauvegarde)
    except Exception:
        pass

    vues = [
        VueProjet(page, etat),
        VueDimensionnement(page, etat),
        VueBassin(page, etat),
        VueTableQDF(page, etat),
        VueAjutage(page, etat),
        VuePluies(page, etat),
        VueRapport(page, etat),
    ]
    index = {"courant": 0}
    zone = ft.Container(expand=True, padding=ft.padding.symmetric(18, 22))

    # ------------------------------------------------------------------ entête
    resume = ft.Text("", size=12, color=theme.GRIS)
    titre_vue = ft.Text("", size=20, weight=ft.FontWeight.W_800)

    def maj_entete() -> None:
        vue = vues[index["courant"]]
        titre_vue.value = vue.titre
        res = etat.resultat
        resume.value = (
            f"{etat.projet.commune_nom} · T = {etat.projet.periode_retour} ans · "
            f"{etat.projet.aire_ponderee_m2:.0f} m² actifs · "
            f"{LIBELLES_SCENARIOS[etat.scenario_principal]} : {res.volume_m3:.1f} m³"
        )

    def sauvegarder() -> None:
        try:
            page.client_storage.set(CLE_STOCKAGE, etat.to_json())
        except Exception:
            pass

    def afficher(i: int) -> None:
        index["courant"] = i
        vue = vues[i]
        zone.content = vue.afficher()
        maj_entete()
        sauvegarder()
        page.update()

    def basculer_theme(_=None) -> None:
        nouveau = page.theme_mode == ft.ThemeMode.LIGHT
        page.theme_mode = ft.ThemeMode.DARK if nouveau else ft.ThemeMode.LIGHT
        try:
            page.client_storage.set("hydrobassin.sombre", nouveau)
        except Exception:
            pass
        page.update()

    def reinitialiser(_=None) -> None:
        def confirmer(_=None) -> None:
            nouveau = EtatApplication()
            etat.projet = nouveau.projet
            etat.scenario_principal = nouveau.scenario_principal
            etat.invalider()
            page.close(dialogue)
            afficher(0)

        dialogue = ft.AlertDialog(
            modal=True,
            title=ft.Text("Nouveau projet"),
            content=ft.Text("Les données encodées seront effacées. Continuer ?"),
            actions=[
                ft.TextButton("Annuler", on_click=lambda _: page.close(dialogue)),
                ft.FilledButton("Effacer", on_click=confirmer),
            ],
        )
        page.open(dialogue)

    barre = ft.Container(
        content=ft.Row(
            [
                ft.Container(
                    ft.Icon(ft.Icons.WATER_DROP, color=ft.Colors.WHITE, size=22),
                    bgcolor=theme.BLEU,
                    padding=9,
                    border_radius=12,
                ),
                ft.Column(
                    [
                        ft.Row([ft.Text(__app_name__, size=17, weight=ft.FontWeight.W_800),
                                ft.Text(f"v{__version__}", size=10, color=theme.GRIS)],
                               spacing=6, vertical_alignment=ft.CrossAxisAlignment.END),
                        resume,
                    ],
                    spacing=0,
                    expand=True,
                ),
                ft.IconButton(ft.Icons.RESTART_ALT, tooltip="Nouveau projet", on_click=reinitialiser),
                ft.IconButton(ft.Icons.DARK_MODE, tooltip="Thème clair / sombre", on_click=basculer_theme),
            ],
            spacing=12,
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        ),
        padding=ft.padding.symmetric(10, 18),
        bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
        border=ft.border.only(bottom=ft.BorderSide(1, ft.Colors.OUTLINE_VARIANT)),
    )

    # ------------------------------------------------------------- navigation
    rail = ft.NavigationRail(
        selected_index=0,
        label_type=ft.NavigationRailLabelType.ALL,
        min_width=76,
        min_extended_width=220,
        extended=False,
        group_alignment=-0.9,
        destinations=[
            ft.NavigationRailDestination(icon=v.icone, selected_icon=v.icone, label=v.titre)
            for v in vues
        ],
        on_change=lambda e: afficher(e.control.selected_index),
    )
    barre_basse = ft.NavigationBar(
        selected_index=0,
        destinations=[ft.NavigationBarDestination(icon=v.icone, label=v.titre) for v in vues],
        on_change=lambda e: afficher(e.control.selected_index),
        visible=False,
    )

    corps = ft.Row([rail, ft.VerticalDivider(width=1), zone], expand=True, spacing=0)

    def adapter(_=None) -> None:
        compact = (page.width or 1200) < LARGEUR_COMPACTE
        rail.visible = not compact
        corps.controls[1].visible = not compact
        barre_basse.visible = compact
        rail.extended = (page.width or 1200) > 1180
        zone.padding = ft.padding.symmetric(12, 12) if compact else ft.padding.symmetric(18, 22)
        rail.selected_index = index["courant"]
        barre_basse.selected_index = index["courant"]
        page.update()

    page.on_resized = adapter
    page.add(ft.Column([barre, corps, barre_basse], expand=True, spacing=0))
    afficher(0)
    adapter()


if __name__ == "__main__":
    ft.app(target=main)
