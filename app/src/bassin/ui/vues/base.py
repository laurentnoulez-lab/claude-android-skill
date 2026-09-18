"""Classe de base des vues."""

from __future__ import annotations

from typing import Callable, List, Tuple

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
        #: Ce que décrivaient les champs de saisie lors du dernier tracé.
        self._signature = None
        #: Textes dérivés posés à côté des champs : ils se recalculent avec les
        #: résultats, sans reconstruire les champs.
        self._derives: List[Tuple[ft.Text, Callable[[], str]]] = []
        #: Blocs entiers recalculés de même (pastilles, barres de sélection).
        self._blocs: List[Tuple[ft.Container, Callable[[], ft.Control]]] = []
        self.rafraichisseur = Rafraichisseur(self.maj_resultats)
        # Toute modification du projet doit finir par se voir, y compris quand
        # la sortie de champ n'a pas eu lieu.
        etat.abonner(self._sur_modification)

    def signature(self) -> tuple:
        """Ce qui, en changeant, rend les champs de saisie eux-mêmes caducs.

        Une valeur retapée ne touche qu'aux résultats : les champs affichent
        déjà ce qu'il faut, et les redessiner ferait sauter le curseur. Mais
        changer d'ouvrage étudié, ou ajouter un bassin versant, change ce que
        les champs *décrivent* : ils ne valent plus rien.
        """
        etat = self.etat
        return (etat.systeme.ouvrage_courant, len(etat.ouvrages), len(etat.versants))

    def _sur_modification(self) -> None:
        if not self.affichee:
            return
        if self._signature is not None and self._signature != self.signature():
            # Les champs décrivent autre chose qu'avant : les recalculer ne
            # suffit pas, il faut les reconstruire. Sans quoi l'onglet gardait
            # les valeurs de l'ouvrage précédent jusqu'à ce qu'on en sorte et
            # qu'on y revienne.
            self.rafraichir()
            return
        self.rafraichisseur.demander()

    def texte_derive(self, calcul: Callable[[], str], **kwargs) -> ft.Text:
        """Texte calculé à partir des données, posé près des champs de saisie.

        Le sous-titre d'une carte ou une pastille de contrôle décrit l'état
        courant, mais vit dans la zone des champs, qu'on ne reconstruit pas à
        chaque frappe — sans quoi le curseur sauterait. Ces textes restaient
        donc figés sur la valeur d'avant la saisie. Enregistrés ici, ils se
        recalculent avec les résultats.
        """
        controle = ft.Text(calcul(), **kwargs)
        self._derives.append((controle, calcul))
        return controle

    def bloc_derive(self, constructeur: Callable[[], ft.Control]) -> ft.Control:
        """Bloc entier recalculé avec les résultats, sans toucher aux champs.

        Pour ce qui ne se réduit pas à un texte : une pastille colorée, une
        barre de sélection qui affiche « encodé / requis ». Le conteneur reste
        le même objet — c'est son contenu qu'on remplace — pour que l'arbre de
        contrôles ne bouge pas sous Flet.
        """
        boite = ft.Container(constructeur())
        self._blocs.append((boite, constructeur))
        return boite

    def _rafraichir_les_derives(self) -> None:
        for controle, calcul in self._derives:
            try:
                controle.value = calcul()
            except Exception:
                pass
        for boite, constructeur in self._blocs:
            try:
                boite.content = constructeur()
            except Exception:
                pass

    def construire(self) -> List[ft.Control]:
        raise NotImplementedError

    def afficher(self) -> ft.Control:
        self.affichee = True
        self._signature = self.signature()
        self._derives = []
        self._blocs = []
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
        self._rafraichir_les_derives()
        try:
            self.zone.controls = self.resultats()
        except Exception:
            # Un écran figé ne dit rien ; mieux vaut afficher la panne.
            self.zone.controls = [
                theme.message("Les résultats n'ont pas pu être recalculés. "
                              "Vérifiez les valeurs saisies ci-dessus.", "erreur")]
        try:
            self.corps.update()
        except Exception:
            pass
        try:
            self.zone.update()
        except Exception:
            pass

    def rafraichir(self) -> None:
        self._signature = self.signature()
        self._derives = []
        self._blocs = []
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
