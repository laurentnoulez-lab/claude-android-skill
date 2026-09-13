"""HydroBassin+ — dimensionnement de réseaux de bassins d'orage.

Méthode rationnelle, pluies statistiques du GTI (Région wallonne). Point
d'entrée de l'application Flet (Windows, Android, web).

Sur le bureau, l'application peut ouvrir une **seconde fenêtre** sur le même
projet, comme la commande « Nouvelle fenêtre » d'un tableur. Les deux fenêtres
partagent un unique état applicatif : c'est pourquoi il vit au niveau du module
et non dans ``main``.
"""

from __future__ import annotations

import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import flet as ft  # noqa: E402

from bassin import __app_name__, __version__  # noqa: E402
from bassin.core import exemple, rainfall  # noqa: E402
from bassin.core.model import LIBELLES_SCENARIOS  # noqa: E402
from bassin.ui import fenetres, theme  # noqa: E402
from bassin.ui.state import CLE_STOCKAGE, EtatApplication  # noqa: E402
from bassin.ui.vues.ajutage import VueAjutage  # noqa: E402
from bassin.ui.vues.bassin import VueBassin  # noqa: E402
from bassin.ui.vues.dimensionnement import VueDimensionnement  # noqa: E402
from bassin.ui.vues.pluies import VuePluies  # noqa: E402
from bassin.ui.vues.projet import VueProjet  # noqa: E402
from bassin.ui.vues.qdf import VueTableQDF  # noqa: E402
from bassin.ui.vues.rapport import VueRapport  # noqa: E402
from bassin.ui.vues.reseau import VueReseau  # noqa: E402
from bassin.ui.vues.synthese import VueSynthese  # noqa: E402
from bassin.ui.vues.versants import VueVersants  # noqa: E402

#: En dessous de cette largeur, la navigation passe dans un tiroir latéral.
LARGEUR_COMPACTE = 840

#: Projet partagé par toutes les fenêtres du même processus. Une seconde fenêtre
#: ouvre une session Flet de plus, pas une seconde application : les deux doivent
#: montrer et modifier le même projet.
_PARTAGE = {"etat": None, "fenetres": 0}


def etat_partage(charger=None) -> EtatApplication:
    """État applicatif du processus, créé à la première fenêtre.

    ``charger`` n'est appelé que pour la première fenêtre : les suivantes
    reprennent le projet déjà en mémoire, sans relire le stockage.
    """
    if _PARTAGE["etat"] is None:
        etat = EtatApplication()
        if charger is not None:
            try:
                charger(etat)
            except Exception:
                pass
        _PARTAGE["etat"] = etat
    return _PARTAGE["etat"]


def reinitialiser_partage() -> None:
    """Repart d'un état neuf — utilisé par les tests."""
    _PARTAGE["etat"] = None
    _PARTAGE["fenetres"] = 0


def trace(etape: str) -> None:
    """Jalons de démarrage, visibles dans la console (journal des captures)."""
    print(f"[HydroBassin] {etape}", flush=True)


#: Pourquoi l'adresse du navigateur a pu être lue, ou non (visible au Diagnostic).
ORIGINE_ADRESSE = {"origine": "non tentée"}


def _adresse_navigateur() -> str:
    """Adresse de la page dans la version web ; chaîne vide ailleurs.

    ``js`` n'existe que sous Pyodide (application web). Selon que le code tourne
    dans la page ou dans un « worker », l'objet global s'appelle ``window``,
    ``self`` ou ``js`` lui-même : les trois sont essayés.
    """
    try:
        import js  # type: ignore[import-not-found]
    except Exception as exc:
        ORIGINE_ADRESSE["origine"] = f"module js absent ({type(exc).__name__})"
        return ""
    echecs = []
    for nom in ("window", "self", None):
        try:
            portee = getattr(js, nom) if nom else js
            lieu = portee.location
            adresse = f"{lieu.pathname}{lieu.hash}"
            ORIGINE_ADRESSE["origine"] = f"js.{nom}.location" if nom else "js.location"
            return adresse
        except Exception as exc:
            echecs.append(f"{nom or 'js'}:{type(exc).__name__}")
    ORIGINE_ADRESSE["origine"] = "js présent mais sans location — " + ", ".join(echecs)
    return ""


def main(page: ft.Page) -> None:
    trace("démarrage")
    page.title = f"{__app_name__} — dimensionnement de réseaux de bassins d'orage"
    try:  # sans effet (voire indisponible) sur mobile
        page.window.width = 1280
        page.window.height = 860
        page.window.min_width = 360
        page.window.min_height = 560
    except Exception:
        pass
    # Le stockage client bloque dans la version web (appel synchrone vers le
    # navigateur) : la persistance n'est activée que sur les applications
    # installées (Android, Windows), où elle fonctionne.
    stockage = {"actif": not bool(getattr(page, "web", False))}

    def lire_stockage(cle, defaut=None):
        if not stockage["actif"]:
            return defaut
        try:
            return page.client_storage.get(cle)
        except BaseException:
            stockage["actif"] = False
            return defaut

    def ecrire_stockage(cle, valeur) -> None:
        if not stockage["actif"]:
            return
        try:
            page.client_storage.set(cle, valeur)
        except BaseException:
            stockage["actif"] = False

    sombre = bool(lire_stockage("hydrobassin.sombre") or False)
    theme.appliquer_theme(page, sombre)

    def reprendre(etat: EtatApplication) -> None:
        sauvegarde = lire_stockage(CLE_STOCKAGE)
        if sauvegarde:
            etat.charger_json(sauvegarde)

    etat = etat_partage(reprendre)
    _PARTAGE["fenetres"] += 1
    numero = _PARTAGE["fenetres"]
    secondaire = numero > 1
    if secondaire:
        page.title = f"{page.title} — fenêtre {numero}"

    vues = [
        VueProjet(page, etat),
        VueVersants(page, etat),
        VueReseau(page, etat),
        VueDimensionnement(page, etat),
        VueBassin(page, etat),
        VueTableQDF(page, etat),
        VueAjutage(page, etat),
        VueSynthese(page, etat),
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
        systeme = etat.systeme
        ouvrages = len(systeme.ouvrages)
        detail = (f"{ouvrages} bassins · " if ouvrages > 1 else "")
        # Virgule décimale, ici comme partout ailleurs : le volume s'y affichait
        # avec un point, seul endroit de l'application à le faire.
        resume.value = theme.fr(
            f"{systeme.commune_nom} · T = {systeme.periode_retour} ans · "
            f"{systeme.aire_ponderee_m2:.0f} m² actifs · {detail}"
            f"{etat.ouvrage.nom} : {volume}"
        )

    def entete_apres_saisie() -> None:
        maj_entete(calculer=False)
        for controle in (titre_page, resume):
            try:
                controle.update()
            except Exception:
                pass

    def sauvegarder() -> None:
        ecrire_stockage(CLE_STOCKAGE, etat.to_json())

    def afficher(i: int) -> None:
        index["courant"] = i
        # Une seule vue est à l'écran : les autres ne doivent pas recalculer.
        for j, vue in enumerate(vues):
            if j != i:
                vue.masquer()
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
        # Changer de section doit montrer le haut de la nouvelle : sans cela, le
        # défilement de la section précédente est conservé et la page s'ouvre au
        # milieu, voire sous un grand vide quand la nouvelle est plus courte.
        try:
            zone.scroll_to(offset=0, duration=0)
        except Exception:
            pass
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
        ecrire_stockage("hydrobassin.sombre", clair)
        page.update()

    def charger_exemple(_=None) -> None:
        """Remplit l'application avec un réseau complet, pour la découvrir."""
        etat.systeme = exemple.systeme_demonstration()
        etat.invalider()
        afficher(index["courant"])

    def reinitialiser(_=None) -> None:
        def confirmer(_=None) -> None:
            etat.systeme = EtatApplication().systeme
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
            f"Fenêtres : {_PARTAGE['fenetres']} ouverte(s) · seconde fenêtre "
            + ("disponible" if fenetres.disponible(page) else "indisponible ici"),
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

    def nouvelle_fenetre(_=None) -> None:
        """Seconde fenêtre sur le même projet, comme dans un tableur."""
        raison = fenetres.ouvrir(page)
        if raison:
            page.open(ft.SnackBar(content=ft.Text(raison), behavior=ft.SnackBarBehavior.FLOATING,
                                  duration=5000))
            return
        page.open(ft.SnackBar(
            content=ft.Text("Nouvelle fenêtre ouverte sur le même projet : ce que vous "
                            "modifiez d'un côté se recalcule de l'autre."),
            behavior=ft.SnackBarBehavior.FLOATING, duration=4000))

    def fermer_cette_fenetre(_=None) -> None:
        """Referme une fenêtre supplémentaire sans arrêter l'application."""
        try:
            page.window.destroy()
        except Exception:
            try:
                page.window.close()
            except Exception:
                pass

    bouton_menu = ft.IconButton(ft.Icons.MENU, tooltip="Sections", on_click=ouvrir_menu, visible=False)
    actions_fenetre = []
    if secondaire:
        actions_fenetre.append(ft.IconButton(
            ft.Icons.CLOSE_FULLSCREEN,
            tooltip="Fermer cette fenêtre (l'application reste ouverte)",
            on_click=fermer_cette_fenetre))
    elif fenetres.disponible(page):
        actions_fenetre.append(ft.IconButton(
            ft.Icons.OPEN_IN_NEW,
            tooltip="Nouvelle fenêtre sur le même projet (Ctrl+N)",
            on_click=nouvelle_fenetre))
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
                *actions_fenetre,
                ft.IconButton(ft.Icons.INFO_OUTLINE, tooltip="Diagnostic", on_click=diagnostic),
                ft.IconButton(ft.Icons.LIGHTBULB_OUTLINE, tooltip="Charger un exemple (Ctrl+E)",
                              on_click=charger_exemple),
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

    def sur_fermeture(_=None) -> None:
        """Désabonne cette fenêtre : l'état partagé survit, ses rappels non."""
        etat.desabonner(entete_apres_saisie)
        for vue in vues:
            etat.desabonner(vue._sur_modification)
            vue.masquer()
        _PARTAGE["fenetres"] = max(_PARTAGE["fenetres"] - 1, 0)

    # Selon la plateforme, Flet prévient d'une fermeture par l'un ou l'autre
    # de ces rappels : les deux sont branchés, le désabonnement est idempotent.
    for attribut in ("on_close", "on_disconnect"):
        try:
            setattr(page, attribut, sur_fermeture)
        except Exception:
            pass
    page.on_resized = adapter
    # Sur téléphone, le contenu passait sous la barre d'état et sous la barre de
    # navigation du système : SafeArea réserve ces zones.
    def vue_demandee() -> int:
        """Section demandée par l'adresse (« /vue/3 »), pour les liens directs.

        La version web de Flet ne transmet pas le chemin de l'URL : ``page.route``
        y vaut toujours « / » et ``on_route_change`` ne se déclenche jamais. Comme
        Python s'exécute dans le navigateur (Pyodide), l'adresse est lue
        directement en second recours ; sur Android et Windows, ce module n'existe
        pas et seul ``page.route`` sert.
        """
        for adresse in (getattr(page, "route", "") or "", _adresse_navigateur()):
            morceaux = adresse.strip("/#").split("/")
            if len(morceaux) >= 2 and morceaux[-2] == "vue":
                try:
                    return max(0, min(len(vues) - 1, int(morceaux[-1])))
                except ValueError:
                    continue
        return 0

    def sur_changement_de_route(_=None) -> None:
        """Utile là où Flet transmet bien la route (applications installées)."""
        trace(f"route reçue : {getattr(page, 'route', None)!r}")
        demandee = vue_demandee()
        if demandee != index["courant"]:
            afficher(demandee)

    page.on_route_change = sur_changement_de_route

    def sur_touche(e) -> None:
        """Ctrl+1 à Ctrl+9 et Ctrl+0 ouvrent une section, comme dans un navigateur."""
        if not getattr(e, "ctrl", False):
            return
        touche = str(getattr(e, "key", ""))
        if touche.isdigit():
            # Ctrl+0 ouvre la dixième section, comme la touche 0 d'un navigateur.
            rang = 10 if touche == "0" else int(touche)
            if 1 <= rang <= len(vues):
                afficher(rang - 1)
        elif touche.upper() == "E":
            charger_exemple()
        elif touche.upper() == "N" and not secondaire and fenetres.disponible(page):
            nouvelle_fenetre()

    page.on_keyboard_event = sur_touche

    trace(f"contrôles construits · route {getattr(page, 'route', None)!r} · "
          f"adresse {_adresse_navigateur()!r} ({ORIGINE_ADRESSE['origine']})")
    page.add(ft.SafeArea(ft.Column([barre, corps], expand=True, spacing=0), expand=True))
    page_prete["oui"] = True
    trace("coquille affichée")
    try:
        afficher(vue_demandee())
        adapter()
        trace("première vue affichée")
    except BaseException:
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
