"""Classe de base des vues."""

from __future__ import annotations

from typing import List

import flet as ft

from .. import theme

from ..rafraichissement import Rafraichisseur
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
        #: Vrai quand cette vue est celle affichée : les autres ne recalculent pas.
        self.affichee = False
        self.rafraichisseur = Rafraichisseur(self.maj_resultats)
        # Toute modification du projet doit finir par se voir, y compris quand
        # la sortie de champ n'a pas eu lieu.
        etat.abonner(self._sur_modification)

    def _sur_modification(self) -> None:
        if self.affichee:
            self.rafraichisseur.demander()

    def construire(self) -> List[ft.Control]:
        raise NotImplementedError

    def afficher(self) -> ft.Control:
        self.affichee = True
        self.corps.controls = self.construire()
        return self.corps

    def masquer(self) -> None:
        """La vue n'est plus à l'écran : inutile de la recalculer."""
        self.affichee = False
        self.rafraichisseur.annuler()

    def resultats(self) -> List[ft.Control]:
        """Contenu dépendant des données saisies (rafraîchi seul)."""
        return []

    def maj_resultats(self) -> None:
        """Recalcule la zone de résultats sans toucher aux champs de saisie."""
        # Ce recalcul rend caduc celui qui attendait : sinon la sortie de champ
        # et le minuteur calculeraient deux fois la même chose.
        self.rafraichisseur.annuler()
        try:
            self.zone.controls = self.resultats()
        except Exception:
            # Un écran figé ne dit rien ; mieux vaut afficher la panne.
            self.zone.controls = [
                theme.message("Les résultats n'ont pas pu être recalculés. "
                              "Vérifiez les valeurs saisies ci-dessus.", "erreur")]
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
