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
    """Nombre affiché à la française, avec unité facultative."""
    texte = fr(f"{valeur:.{decimales}f}")
    return f"{texte} {unite}".strip() if unite else texte
