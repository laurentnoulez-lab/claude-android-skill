"""Classe de base des vues."""

from __future__ import annotations

from typing import List

import flet as ft

from ..state import EtatApplication


class Vue:
    """Une page de l'application, reconstruite a chaque affichage."""

    titre = ""
    icone = ft.Icons.CIRCLE
    sous_titre = ""

    def __init__(self, page: ft.Page, etat: EtatApplication):
        self.page = page
        self.etat = etat
        self.corps = ft.Column(spacing=16, tight=True)
        #: Zone recalculée seule, sans reconstruire les champs de saisie.
        self.zone = ft.Column(spacing=16)

    def construire(self) -> List[ft.Control]:
        raise NotImplementedError

    def afficher(self) -> ft.Control:
        self.corps.controls = self.construire()
        return self.corps

    def resultats(self) -> List[ft.Control]:
        """Contenu dépendant des données saisies (rafraîchi seul)."""
        return []

    def maj_resultats(self) -> None:
        """Recalcule la zone de résultats sans toucher aux champs de saisie."""
        self.zone.controls = self.resultats()
        try:
            self.zone.update()
        except Exception:
            pass

    def rafraichir(self) -> None:
        self.corps.controls = self.construire()
        try:
            self.corps.update()
        except Exception:
            pass

    def notifier(self, texte: str, type_: str = "info") -> None:
        couleurs = {"info": None, "succes": "#059669", "erreur": "#DC2626", "alerte": "#D97706"}
        self.page.open(
            ft.SnackBar(
                content=ft.Text(texte, color=ft.Colors.WHITE if couleurs.get(type_) else None),
                bgcolor=couleurs.get(type_),
                behavior=ft.SnackBarBehavior.FLOATING,
                duration=4000,
            )
        )
