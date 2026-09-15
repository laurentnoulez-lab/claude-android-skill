"""Mise en forme francophone partagée par l'interface et les rapports."""

from __future__ import annotations

import re

_DECIMALE = re.compile(r"(?<=\d)\.(?=\d)")


def fr(texte: str) -> str:
    """Virgule décimale francophone dans un texte déjà formaté.

    Seul un point encadré par deux chiffres est converti : les notations
    scientifiques (1e-5), les puces et la ponctuation restent intactes.
    """
    return _DECIMALE.sub(",", texte)


def nombre(valeur: float, decimales: int = 1, unite: str = "") -> str:
    """Nombre affiché à la française, avec unité facultative.

    Une grandeur infinie — un bassin sans débit de sortie ne se vidange jamais —
    s'écrivait « inf », qui n'est ni un nombre lisible ni du français.
    """
    valeur = float(valeur)
    if valeur != valeur:
        return "—"
    if valeur in (float("inf"), float("-inf")):
        return "∞" if valeur > 0 else "-∞"
    texte = fr(f"{valeur:.{decimales}f}")
    return f"{texte} {unite}".strip() if unite else texte


def duree_h(heures: float, decimales: int = 1) -> str:
    """Durée en heures, y compris quand il n'y en a pas.

    Un ouvrage sans débit de sortie ne se vidange jamais : le temps de vidange
    vaut alors l'infini. Formaté par ``f"{x:.1f} h"``, il s'affichait « inf h »,
    qui n'est ni du français ni une information.
    """
    valeur = float(heures)
    if valeur == float("inf"):
        return "jamais (aucun débit de sortie)"
    if valeur != valeur:                       # NaN
        return "indéterminé"
    return fr(f"{valeur:.{decimales}f} h")
