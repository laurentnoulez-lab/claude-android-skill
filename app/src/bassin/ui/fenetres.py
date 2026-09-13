"""Ouverture d'une seconde fenêtre sur le même projet (Windows et bureau).

Comme la commande « Nouvelle fenêtre » d'un tableur : deux fenêtres sur le même
document, pour changer une valeur d'un côté et en voir l'effet de l'autre. Les
deux fenêtres partagent le même :class:`~bassin.ui.state.EtatApplication`, si
bien qu'une saisie faite dans l'une se répercute dans l'autre.

Le principe est celui de Flet lui-même : l'application de bureau est un petit
serveur local auquel se connecte une fenêtre native. En lançant une **seconde
fenêtre native sur la même adresse**, Flet ouvre une session de plus dans le
même processus Python. Fermer cette fenêtre ferme sa session et rien d'autre :
l'application ne s'arrête qu'avec la fenêtre principale, la seule que Flet
attend.

Sur Android et dans la version web, rien de tout cela n'a de sens : la
fonctionnalité s'y déclare simplement indisponible.
"""

from __future__ import annotations

import threading
from typing import List, Optional, Tuple

#: Fenêtres supplémentaires ouvertes, pour pouvoir les refermer proprement.
_OUVERTES: List[Tuple[object, object]] = []
_VERROU = threading.Lock()


def disponible(page) -> bool:
    """La seconde fenêtre est-elle possible ici ?

    Il faut une application de bureau (ni web, ni mobile), le paquet
    ``flet_desktop`` qui sait lancer une fenêtre native, et l'adresse du serveur
    local de la session en cours.
    """
    if bool(getattr(page, "web", False)):
        return False
    if not _adresse(page):
        return False
    try:
        import flet_desktop  # noqa: F401
    except Exception:
        return False
    try:
        import flet as ft

        plateforme = getattr(page, "platform", None)
        if plateforme in (ft.PagePlatform.ANDROID, ft.PagePlatform.IOS):
            return False
    except Exception:
        pass
    return True


def _adresse(page) -> str:
    """Adresse du serveur local auquel la fenêtre actuelle est connectée."""
    connexion = getattr(page, "connection", None)
    return str(getattr(connexion, "page_url", "") or "")


def nombre_ouvertes() -> int:
    with _VERROU:
        return len(_OUVERTES)


def ouvrir(page) -> str:
    """Ouvre une fenêtre de plus sur le même projet.

    Renvoie une chaîne vide en cas de succès, sinon la raison de l'échec — que
    l'appelant affiche telle quelle à l'utilisateur.
    """
    adresse = _adresse(page)
    if not adresse:
        return ("Cette version ne sait pas ouvrir de seconde fenêtre : l'adresse de "
                "la session n'est pas accessible.")
    try:
        from flet_desktop import open_flet_view
    except Exception as exc:
        return (f"Le composant de fenêtrage de Flet n'est pas disponible ici "
                f"({type(exc).__name__}).")
    try:
        # Le processus rendu n'est pas attendu : c'est ce qui permet de refermer
        # la fenêtre supplémentaire sans arrêter l'application.
        fenetre = open_flet_view(adresse, None, False)
    except Exception as exc:  # pragma: no cover - dépend de l'installation
        return f"Ouverture impossible : {type(exc).__name__} — {exc}"
    with _VERROU:
        _OUVERTES.append(fenetre if isinstance(fenetre, tuple) else (fenetre, None))
    return ""


def fermer_toutes() -> int:
    """Referme les fenêtres supplémentaires encore ouvertes."""
    with _VERROU:
        fenetres = list(_OUVERTES)
        _OUVERTES.clear()
    fermees = 0
    for processus, marqueur in fenetres:
        try:
            from flet_desktop import close_flet_view

            close_flet_view(marqueur)
            fermees += 1
            continue
        except Exception:
            pass
        try:
            processus.terminate()
            fermees += 1
        except Exception:
            pass
    return fermees
