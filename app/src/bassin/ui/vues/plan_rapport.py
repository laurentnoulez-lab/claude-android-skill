"""Éditeur du plan du dossier : rubriques retenues, ajoutées, ordonnées.

Le dossier suivait un plan figé. L'utilisateur le compose ici : il décoche ce
qui ne sert pas à son étude, ajoute ses propres rubriques — paragraphes mis en
forme, intertitres, images —, et fait monter ou descendre l'ensemble.

La mise en forme porte sur le **bloc**, pas sur une sélection de caractères :
un traitement de texte dans un canevas Flutter demanderait un éditeur que rien
ici ne fournit. Écrire deux paragraphes pour changer de style est plus long,
mais tout ce qui s'affiche se modifie, et rien ne se cache dans un balisage
qu'il faudrait apprendre.
"""

from __future__ import annotations

import base64
import os
from typing import List, Optional

import flet as ft

from ...core import rapport as mod_rapport
from .. import theme

#: Ce qu'un bloc peut être, et comment le dire à l'utilisateur.
GENRES = (
    (mod_rapport.BLOC_PARAGRAPHE, "Paragraphe", ft.Icons.NOTES),
    (mod_rapport.BLOC_INTERTITRE, "Intertitre", ft.Icons.TITLE),
    (mod_rapport.BLOC_IMAGE, "Image", ft.Icons.IMAGE),
)

#: Formats d'image que les deux écrivains savent reprendre.
EXTENSIONS_IMAGE = ("png", "jpg", "jpeg")

#: Au-delà, le fichier de projet devient lourd à enregistrer et à relire.
TAILLE_IMAGE_MAX = 4 * 1024 * 1024


class EditeurPlan:
    """Construit les contrôles du plan pour la vue qui l'héberge."""

    def __init__(self, vue):
        self.vue = vue
        #: Rubrique libre dépliée, par son rang ; -1 : aucune.
        self.depliee: int = -1
        self.selecteur_image: Optional[ft.FilePicker] = None

    # ------------------------------------------------------------ raccourcis
    @property
    def plan(self) -> mod_rapport.PlanRapport:
        return self.vue.etat.systeme.plan_rapport

    def _applique(self) -> None:
        """Le plan a changé : le projet est à réenregistrer, la vue à refaire."""
        self.vue.etat.invalider()
        self.vue.rafraichir()

    # ---------------------------------------------------------------- carte
    def carte(self) -> ft.Control:
        lignes: List[ft.Control] = []
        for index, rubrique in enumerate(self.plan.rubriques):
            lignes.append(self._ligne(index, rubrique))
            if rubrique.libre and index == self.depliee:
                lignes.append(self._contenu(index, rubrique))
        lignes.append(
            ft.Column(
                [
                    ft.Row([theme.bouton_secondaire("Ajouter une rubrique",
                                                    ft.Icons.PLAYLIST_ADD,
                                                    lambda _e: self._ajouter())]),
                    ft.Text("Les rubriques décochées ne sont écrites ni au PDF ni au Word ; "
                            "le classeur Excel, lui, reste complet.",
                            size=11, color=theme.GRIS),
                ],
                spacing=8,
            )
        )
        return ft.Column(lignes, spacing=4)

    # --------------------------------------------------------------- lignes
    def _ligne(self, index: int, rubrique: mod_rapport.Rubrique) -> ft.Control:
        def basculer(e: ft.ControlEvent) -> None:
            rubrique.active = bool(e.control.value)
            self._applique()

        def renommer(e: ft.ControlEvent) -> None:
            rubrique.titre = e.control.value

        def deplier(_e) -> None:
            self.depliee = -1 if self.depliee == index else index
            self.vue.rafraichir()

        titre: ft.Control
        if rubrique.libre:
            titre = ft.TextField(value=rubrique.titre, dense=True, filled=True,
                                 content_padding=ft.padding.symmetric(6, 10),
                                 text_size=12.5, on_change=renommer,
                                 on_blur=lambda _e: self._applique(), expand=True)
        else:
            titre = ft.Text(rubrique.libelle, size=12.5,
                            weight=ft.FontWeight.W_600 if rubrique.active else None,
                            color=None if rubrique.active else theme.GRIS, expand=True)

        actions = [
            ft.IconButton(ft.Icons.ARROW_UPWARD, tooltip="Monter", icon_size=17,
                          on_click=lambda _e: self._deplacer(index, -1)),
            ft.IconButton(ft.Icons.ARROW_DOWNWARD, tooltip="Descendre", icon_size=17,
                          on_click=lambda _e: self._deplacer(index, 1)),
        ]
        if rubrique.libre:
            actions += [
                ft.IconButton(ft.Icons.EDIT_NOTE, tooltip="Contenu", icon_size=19,
                              selected=index == self.depliee, on_click=deplier),
                ft.IconButton(ft.Icons.DELETE_OUTLINE, tooltip="Supprimer", icon_size=17,
                              icon_color=theme.ROUGE,
                              on_click=lambda _e: self._supprimer(index)),
            ]
        else:
            actions.append(ft.Container(width=40))   # aligne les colonnes
        return ft.Container(
            ft.Row([ft.Checkbox(value=rubrique.active, on_change=basculer), titre]
                   + actions,
                   spacing=2, vertical_alignment=ft.CrossAxisAlignment.CENTER),
            bgcolor=theme.GRIS_CLAIR if rubrique.libre else None,
            border_radius=8,
            padding=ft.padding.symmetric(2, 6),
        )

    # -------------------------------------------------------------- contenu
    def _contenu(self, index: int, rubrique: mod_rapport.Rubrique) -> ft.Control:
        blocs = [self._bloc(rubrique, rang, bloc) for rang, bloc in enumerate(rubrique.blocs)]
        ajouts = ft.Row(
            [theme.bouton_secondaire(libelle, icone,
                                     (lambda g: lambda _e: self._ajouter_bloc(rubrique, g))(genre))
             for genre, libelle, icone in GENRES],
            spacing=8, wrap=True,
        )
        return ft.Container(
            ft.Column(blocs + [ajouts], spacing=8),
            padding=ft.padding.only(left=28, right=8, top=6, bottom=10),
        )

    def _bloc(self, rubrique, rang: int, bloc: mod_rapport.Bloc) -> ft.Control:
        if bloc.est_image:
            corps = self._bloc_image(bloc)
        else:
            corps = self._bloc_texte(bloc)
        entete = ft.Row(
            [
                ft.Icon(dict((g, i) for g, _l, i in GENRES)[bloc.genre], size=16,
                        color=theme.GRIS),
                ft.Text(dict((g, l) for g, l, _i in GENRES)[bloc.genre], size=11,
                        color=theme.GRIS, expand=True),
                ft.IconButton(ft.Icons.ARROW_UPWARD, icon_size=15, tooltip="Monter",
                              on_click=lambda _e: self._deplacer_bloc(rubrique, rang, -1)),
                ft.IconButton(ft.Icons.ARROW_DOWNWARD, icon_size=15, tooltip="Descendre",
                              on_click=lambda _e: self._deplacer_bloc(rubrique, rang, 1)),
                ft.IconButton(ft.Icons.DELETE_OUTLINE, icon_size=15, tooltip="Supprimer",
                              icon_color=theme.ROUGE,
                              on_click=lambda _e: self._supprimer_bloc(rubrique, rang)),
            ],
            spacing=0, vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )
        return ft.Container(
            ft.Column([entete, corps], spacing=6),
            border=ft.border.all(1, theme.GRIS_CLAIR),
            border_radius=8,
            padding=10,
        )

    def _bloc_texte(self, bloc: mod_rapport.Bloc) -> ft.Control:
        def maj_texte(e: ft.ControlEvent) -> None:
            bloc.texte = e.control.value

        def bascule(champ: str):
            def _f(e: ft.ControlEvent) -> None:
                setattr(bloc, champ, bool(e.control.value))
                self._applique()
            return _f

        def maj_couleur(e: ft.ControlEvent) -> None:
            bloc.couleur = e.control.value or ""
            self._applique()

        champ = ft.TextField(value=bloc.texte, multiline=True, min_lines=2, max_lines=8,
                             dense=True, filled=True, text_size=12.5,
                             on_change=maj_texte, on_blur=lambda _e: self._applique(),
                             hint_text="Texte de la rubrique")
        if bloc.genre == mod_rapport.BLOC_INTERTITRE:
            # Un intertitre prend le style des titres du dossier : lui proposer
            # gras, italique ou couleur laisserait croire qu'ils s'appliquent.
            return champ
        mise_en_forme = ft.Row(
            [
                ft.Checkbox(label="Gras", value=bloc.gras, on_change=bascule("gras")),
                ft.Checkbox(label="Italique", value=bloc.italique,
                            on_change=bascule("italique")),
                ft.Checkbox(label="Souligné", value=bloc.souligne,
                            on_change=bascule("souligne")),
                ft.Container(
                    theme.selecteur("Couleur", bloc.couleur,
                                    list(mod_rapport.COULEURS_TEXTE), maj_couleur,
                                    compact=True),
                    width=210),
            ],
            spacing=6, wrap=True, vertical_alignment=ft.CrossAxisAlignment.CENTER,
        )
        return ft.Column([champ, mise_en_forme], spacing=6)

    def _bloc_image(self, bloc: mod_rapport.Bloc) -> ft.Control:
        def maj_largeur(valeur: float) -> None:
            bloc.largeur_cm = max(1.0, min(valeur, 18.0))
            self._applique()

        def maj_legende(e: ft.ControlEvent) -> None:
            bloc.legende = e.control.value

        octets = bloc.image()
        etat = (f"{len(octets) // 1024} ko" if octets else "aucune image choisie")
        apercu: List[ft.Control] = [
            ft.Row(
                [
                    theme.bouton_secondaire("Choisir une image…", ft.Icons.ADD_PHOTO_ALTERNATE,
                                            (lambda b: lambda _e: self._choisir_image(b))(bloc)),
                    ft.Text(etat, size=11, color=theme.GRIS, expand=True),
                ],
                spacing=10, vertical_alignment=ft.CrossAxisAlignment.CENTER,
            )
        ]
        if octets:
            apercu.append(ft.Image(src_base64=bloc.image_base64, height=110,
                                   fit=ft.ImageFit.CONTAIN))
        apercu.append(ft.ResponsiveRow(
            [
                ft.Container(theme.champ_nombre("Largeur à l'impression", bloc.largeur_cm,
                                                maj_largeur, "cm", "18 cm au maximum",
                                                decimales=1),
                             col={"xs": 12, "sm": 5, "md": 4}),
                ft.Container(
                    ft.TextField(value=bloc.legende, dense=True, filled=True, text_size=12.5,
                                 label="Légende", on_change=maj_legende,
                                 on_blur=lambda _e: self._applique()),
                    col={"xs": 12, "sm": 7, "md": 8}),
            ],
            spacing=10, run_spacing=10,
        ))
        return ft.Column(apercu, spacing=8)

    # ------------------------------------------------------------- gestes
    def _deplacer(self, index: int, pas: int) -> None:
        if self.plan.deplacer(index, pas):
            if self.depliee == index:
                self.depliee = index + pas
            elif self.depliee == index + pas:
                self.depliee = index
            self._applique()

    def _supprimer(self, index: int) -> None:
        if self.plan.supprimer(index):
            self.depliee = -1
            self._applique()

    def _ajouter(self) -> None:
        self.plan.ajouter_libre()
        self.depliee = len(self.plan.rubriques) - 1
        self._applique()

    def _ajouter_bloc(self, rubrique, genre: str) -> None:
        rubrique.blocs.append(mod_rapport.Bloc(genre=genre))
        self._applique()

    def _supprimer_bloc(self, rubrique, rang: int) -> None:
        if 0 <= rang < len(rubrique.blocs):
            del rubrique.blocs[rang]
            self._applique()

    def _deplacer_bloc(self, rubrique, rang: int, pas: int) -> None:
        cible = rang + pas
        if 0 <= rang < len(rubrique.blocs) and 0 <= cible < len(rubrique.blocs):
            rubrique.blocs[rang], rubrique.blocs[cible] = (rubrique.blocs[cible],
                                                           rubrique.blocs[rang])
            self._applique()

    # -------------------------------------------------------------- images
    def _choisir_image(self, bloc: mod_rapport.Bloc) -> None:
        """Ouvre le sélecteur du système et range l'image dans le bloc."""
        if self.selecteur_image is None:
            def _resultat(e: ft.FilePickerResultEvent) -> None:
                cible = getattr(self.selecteur_image, "data", None)
                fichiers = getattr(e, "files", None) or []
                if cible is None or not fichiers:
                    return
                chemin = getattr(fichiers[0], "path", None)
                if not chemin or not os.path.exists(chemin):
                    self.vue.notifier(
                        "Ce sélecteur ne donne pas accès au fichier choisi ; copiez l'image "
                        "dans le dossier des livrables et réessayez.", "alerte")
                    return
                self._ranger_image(cible, chemin)

            self.selecteur_image = ft.FilePicker(on_result=_resultat)
            self.vue.page.overlay.append(self.selecteur_image)
            self.vue.page.update()
        self.selecteur_image.data = bloc
        try:
            self.selecteur_image.pick_files(
                dialog_title="Choisir une image", allow_multiple=False,
                allowed_extensions=list(EXTENSIONS_IMAGE))
        except Exception as exc:
            self.vue.notifier(f"Le sélecteur de fichiers n'est pas disponible ici ({exc}).",
                              "alerte")

    def _ranger_image(self, bloc: mod_rapport.Bloc, chemin: str) -> None:
        """Relit le fichier, contrôle qu'il est utilisable, et le range."""
        from ...reports import images as mod_images

        try:
            taille = os.path.getsize(chemin)
            if taille > TAILLE_IMAGE_MAX:
                self.vue.notifier(
                    f"Image de {taille // 1024} ko : au-delà de "
                    f"{TAILLE_IMAGE_MAX // 1024} ko, le fichier de projet devient lourd à "
                    "enregistrer. Réduisez-la avant de l'ajouter.", "alerte")
                return
            with open(chemin, "rb") as fh:
                octets = fh.read()
        except OSError as exc:
            self.vue.notifier(f"Lecture impossible : {exc}", "erreur")
            return
        decrite = mod_images.lire(octets)
        if decrite is None:
            self.vue.notifier("Format non reconnu : le dossier reprend les images PNG et JPEG.",
                              "alerte")
            return
        if decrite.canaux not in (1, 3):
            # Le PDF affiche le JPEG sans le décoder : il ne sait pas rendre la
            # quadrichromie. Le dire ici, c'est l'éviter avant le dossier.
            self.vue.notifier(
                "Image en quadrichromie (CMJN) : réenregistrez-la en RVB ou en niveaux de "
                "gris avant de l'ajouter au dossier.", "alerte")
            return
        bloc.image_base64 = base64.b64encode(octets).decode("ascii")
        if not bloc.legende:
            bloc.legende = os.path.splitext(os.path.basename(chemin))[0]
        self._applique()
