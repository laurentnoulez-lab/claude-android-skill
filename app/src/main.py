"""HydroBassin — dimensionnement de bassins d'orage (méthode rationnelle, pluies GTI).

Point d'entrée de l'application Flet (Windows, Android, web).
"""

from __future__ import annotations

import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import flet as ft  # noqa: E402

from bassin import __app_name__, __version__  # noqa: E402
from bassin.core import rainfall  # noqa: E402
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

#: En dessous de cette largeur, la navigation passe dans un tiroir latéral.
LARGEUR_COMPACTE = 840


def trace(etape: str) -> None:
    """Jalons de démarrage, visibles dans la console (journal des captures)."""
    print(f"[HydroBassin] {etape}", flush=True)


def main(page: ft.Page) -> None:
    trace("démarrage")
    page.title = f"{__app_name__} — dimensionnement de bassins d'orage"
    try:  # sans effet (voire indisponible) sur mobile
        page.window.width = 1280
        page.window.height = 860
        page.window.min_width = 360
        page.window.min_height = 560
    except Exception:
        pass
    try:
        sombre = bool(page.client_storage.get("hydrobassin.sombre"))
    except Exception:
        sombre = False
    theme.appliquer_theme(page, sombre)

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
    page_prete = {"oui": False}

    zone = ft.Column(spacing=0, scroll=ft.ScrollMode.AUTO, expand=True)
    marge = {"valeur": ft.padding.symmetric(18, 22)}

    # ------------------------------------------------------------------ entête
    resume = ft.Text("", size=11.5, color=theme.GRIS, max_lines=1,
                     overflow=ft.TextOverflow.ELLIPSIS)
    titre_page = ft.Text("", size=15, weight=ft.FontWeight.W_700, max_lines=1,
                         overflow=ft.TextOverflow.ELLIPSIS)

    def maj_entete(calculer: bool = True) -> None:
        """Résumé permanent ; `calculer` reste faux pendant la frappe (coût du calcul)."""
        titre_page.value = vues[index["courant"]].titre
        volume = "…"
        if calculer or etat.resultats_disponibles:
            volume = f"{etat.resultat.volume_affiche} m³"
        resume.value = (
            f"{etat.projet.commune_nom} · T = {etat.projet.periode_retour} ans · "
            f"{etat.projet.aire_ponderee_m2:.0f} m² actifs · "
            f"{LIBELLES_SCENARIOS[etat.scenario_principal]} : {volume}"
        )

    def entete_apres_saisie() -> None:
        maj_entete(calculer=False)
        for controle in (titre_page, resume):
            try:
                controle.update()
            except Exception:
                pass

    def sauvegarder() -> None:
        try:
            page.client_storage.set(CLE_STOCKAGE, etat.to_json())
        except Exception:
            pass

    def afficher(i: int) -> None:
        index["courant"] = i
        try:
            contenu = vues[i].afficher()
        except Exception:
            # Une page blanche ne dit rien à l'utilisateur : on montre l'erreur.
            contenu = ft.Column(
                [
                    theme.message(
                        f"Impossible d'afficher la section « {vues[i].titre} ». "
                        "Merci de transmettre le détail ci-dessous.", "erreur"),
                    ft.Container(
                        ft.Text(traceback.format_exc(), size=11, selectable=True,
                                font_family="monospace"),
                        padding=12,
                        border_radius=10,
                        bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
                    ),
                ],
                spacing=12,
            )
        zone.controls = [ft.Container(contenu, padding=marge["valeur"])]
        rail.selected_index = i
        tiroir.selected_index = i
        # Rien ici ne doit empêcher l'affichage : une erreur de calcul dans le
        # résumé laissait auparavant l'écran entièrement vide, sans message.
        try:
            maj_entete()
        except Exception:
            titre_page.value = vues[i].titre
            resume.value = "résumé indisponible — voir Diagnostic"
            trace(f"résumé en erreur : {traceback.format_exc(limit=1).strip()}")
        if page_prete["oui"]:
            page.update()
        # L'enregistrement vient après l'affichage : un stockage lent ou
        # indisponible ne doit jamais retarder le rendu.
        try:
            sauvegarder()
        except Exception:
            trace("enregistrement du projet impossible")

    def ouvrir_menu(_=None) -> None:
        page.open(tiroir)

    def choisir_dans_le_tiroir(e: ft.ControlEvent) -> None:
        page.close(tiroir)
        afficher(e.control.selected_index)

    def basculer_theme(_=None) -> None:
        clair = page.theme_mode == ft.ThemeMode.LIGHT
        page.theme_mode = ft.ThemeMode.DARK if clair else ft.ThemeMode.LIGHT
        try:
            page.client_storage.set("hydrobassin.sombre", clair)
        except Exception:
            pass
        page.update()

    def reinitialiser(_=None) -> None:
        def confirmer(_=None) -> None:
            neuf = EtatApplication()
            etat.projet = neuf.projet
            etat.scenario_principal = neuf.scenario_principal
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

    def diagnostic(_=None) -> None:
        """Informations utiles pour signaler un problème."""
        from bassin.ui.state import diagnostic_stockage, repertoire_documents

        try:
            nb_communes = len(rainfall.communes())
            origine = rainfall.SOURCE_DONNEES["origine"]
        except Exception as exc:
            nb_communes, origine = 0, f"ÉCHEC : {exc}"
        lignes = [
            f"{__app_name__} version {__version__}",
            f"Plateforme : {getattr(page, 'platform', '?')} · largeur {page.width} × hauteur {page.height}",
            f"Python {sys.version.split()[0]} · Flet {getattr(ft, '__version__', '?')}",
            f"Pluies GTI : {nb_communes} communes ({origine})",
            f"Dossier des rapports : {repertoire_documents()}",
        ]
        lignes += [f"  {'écriture possible' if ok else 'inaccessible'} — {c}"
                   for c, ok in diagnostic_stockage()]
        fenetre = ft.AlertDialog(
            title=ft.Text("Diagnostic"),
            content=ft.Column([ft.Text("\n".join(lignes), size=12, selectable=True)],
                              tight=True, scroll=ft.ScrollMode.AUTO, width=460),
            actions=[ft.TextButton("Fermer", on_click=lambda _: page.close(fenetre))],
        )
        page.open(fenetre)

    bouton_menu = ft.IconButton(ft.Icons.MENU, tooltip="Sections", on_click=ouvrir_menu, visible=False)
    barre = ft.Container(
        content=ft.Row(
            [
                bouton_menu,
                ft.Container(
                    ft.Icon(ft.Icons.WATER_DROP, color=ft.Colors.WHITE, size=20),
                    bgcolor=theme.BLEU,
                    padding=8,
                    border_radius=10,
                ),
                ft.Column([titre_page, resume], spacing=0, expand=True, tight=True),
                ft.IconButton(ft.Icons.INFO_OUTLINE, tooltip="Diagnostic", on_click=diagnostic),
                ft.IconButton(ft.Icons.RESTART_ALT, tooltip="Nouveau projet", on_click=reinitialiser),
                ft.IconButton(ft.Icons.DARK_MODE, tooltip="Thème clair / sombre",
                              on_click=basculer_theme),
            ],
            spacing=8,
            vertical_alignment=ft.CrossAxisAlignment.CENTER,
        ),
        padding=ft.padding.symmetric(8, 12),
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
    separateur = ft.VerticalDivider(width=1)

    # Sept sections : un tiroir est bien plus lisible qu'une barre basse sur téléphone.
    tiroir = ft.NavigationDrawer(
        selected_index=0,
        on_change=choisir_dans_le_tiroir,
        controls=[
            ft.Container(
                ft.Column(
                    [
                        ft.Text(__app_name__, size=18, weight=ft.FontWeight.W_800),
                        ft.Text(f"version {__version__}", size=11, color=theme.GRIS),
                    ],
                    spacing=0,
                ),
                padding=ft.padding.only(20, 22, 20, 8),
            ),
            ft.Divider(height=8),
        ] + [
            ft.NavigationDrawerDestination(icon=v.icone, label=v.titre) for v in vues
        ],
    )

    corps = ft.Row([rail, separateur, zone], expand=True, spacing=0)

    def sur_mobile() -> bool:
        """Un téléphone garde la navigation par tiroir quelle que soit la largeur."""
        try:
            return page.platform in (ft.PagePlatform.ANDROID, ft.PagePlatform.IOS)
        except Exception:
            return False

    def adapter(_=None) -> None:
        largeur = page.width or 1200
        compact = sur_mobile() or largeur < LARGEUR_COMPACTE
        rail.visible = not compact
        separateur.visible = not compact
        bouton_menu.visible = compact
        rail.extended = (not compact) and largeur > 1180
        marge["valeur"] = ft.padding.symmetric(10, 10) if compact else ft.padding.symmetric(18, 22)
        if zone.controls:
            zone.controls[0].padding = marge["valeur"]
        page.update()

    etat.abonner(entete_apres_saisie)
    page.on_resized = adapter
    # Sur téléphone, le contenu passait sous la barre d'état et sous la barre de
    # navigation du système : SafeArea réserve ces zones.
    trace("contrôles construits")
    try:
        afficher(0)          # le contenu est prêt avant le premier rendu
        trace("première vue prête")
    except Exception:
        trace(f"première vue en erreur : {traceback.format_exc(limit=2).strip()}")
    page.add(ft.SafeArea(ft.Column([barre, corps], expand=True, spacing=0), expand=True))
    page_prete["oui"] = True
    trace("page affichée")
    try:
        adapter()
        trace("mise en page adaptée")
    except Exception:
        # Filet de sécurité : une panne au démarrage doit rester lisible à l'écran.
        zone.controls = [
            ft.Container(
                ft.Column(
                    [
                        theme.message("HydroBassin n'a pas pu démarrer normalement. "
                                      "Merci de transmettre le détail ci-dessous.", "erreur"),
                        ft.Text(traceback.format_exc(), size=11, selectable=True,
                                font_family="monospace"),
                    ],
                    spacing=12,
                ),
                padding=16,
            )
        ]
        page.update()


if __name__ == "__main__":
    ft.app(target=main)
