"""Vue « Projet » : commune, récurrence, surfaces incidentes, sauvegarde."""

from __future__ import annotations

import os
import shutil
from typing import List, Optional

import flet as ft

from ...core import rainfall
from ...core.model import Projet, SurfaceIncidente, TYPES_SURFACES
from .. import theme
from ..state import (EXTENSION_PROJET, destination_utilisable, repertoire_documents,
                     source_utilisable)
from .base import Vue


class VueProjet(Vue):
    titre = "Projet"
    icone = ft.Icons.FOLDER_OPEN
    sous_titre = "Commune, récurrence et surfaces"

    def __init__(self, page, etat):
        super().__init__(page, etat)
        self._selecteur_export: Optional[ft.FilePicker] = None
        self._selecteur_import: Optional[ft.FilePicker] = None
        self._dernier_export: str = ""

    # ---------------------------------------------------------------- commune
    def _ouvrir_selecteur_commune(self, e=None) -> None:
        hauteur_ecran = self.page.height or 800
        largeur_ecran = self.page.width or 1000
        resultats = ft.ListView(spacing=2, height=max(200, min(360, hauteur_ecran - 320)))
        champ = ft.TextField(
            label="Rechercher une commune ou un code INS",
            autofocus=True,
            prefix_icon=ft.Icons.SEARCH,
            border_radius=10,
            dense=True,
        )
        filtre_wallonnes = ft.Checkbox(label="Communes wallonnes uniquement", value=True)

        def remplir(_=None) -> None:
            resultats.controls = []
            for commune in rainfall.rechercher_communes(
                champ.value or "", limite=60, wallonnes_seulement=bool(filtre_wallonnes.value)
            ):
                resultats.controls.append(
                    ft.ListTile(
                        title=ft.Text(commune.nom, size=14, weight=ft.FontWeight.W_600),
                        subtitle=ft.Text(
                            f"INS {commune.ins} · "
                            + ("Montana + QDF" if commune.a_montana and commune.a_qdf
                               else ("QDF seul" if commune.a_qdf else "Montana seul")),
                            size=11,
                            color=theme.GRIS,
                        ),
                        leading=ft.Icon(ft.Icons.LOCATION_CITY,
                                        color=theme.BLEU if commune.wallonne else theme.GRIS),
                        selected=commune.ins == self.etat.projet.commune_ins,
                        on_click=lambda _, c=commune: choisir(c),
                        dense=True,
                    )
                )
            try:
                resultats.update()
            except Exception:
                pass  # le dialogue n'est pas encore attaché à la page

        def choisir(commune: rainfall.Commune) -> None:
            self.etat.definir_commune(commune)
            self.page.close(dialogue)
            self.rafraichir()
            self.notifier(f"Commune : {commune.nom}", "succes")

        champ.on_change = remplir
        filtre_wallonnes.on_change = remplir
        dialogue = ft.AlertDialog(
            modal=True,
            title=ft.Text("Choisir la commune"),
            content=ft.Container(
                ft.Column([champ, filtre_wallonnes, resultats], spacing=10, tight=True),
                width=min(460, largeur_ecran - 60),
            ),
            actions=[ft.TextButton("Fermer", on_click=lambda _: self.page.close(dialogue))],
        )
        self.page.open(dialogue)
        remplir()

    # --------------------------------------------------------------- surfaces
    def _ligne_surface(self, index: int, surface: SurfaceIncidente) -> ft.Control:
        actifs = ft.Text(theme.nombre(surface.aire_ponderee_m2, 1, "m² actifs"), size=12, color=theme.BLEU,
                         weight=ft.FontWeight.W_600, no_wrap=True)

        def rafraichir_ligne() -> None:
            """Met à jour la surface active de la ligne et les totaux, sans tout reconstruire."""
            actifs.value = theme.nombre(surface.aire_ponderee_m2, 1, "m² actifs")
            try:
                actifs.update()
            except Exception:
                pass
            self._maj_totaux()

        def maj_aire(v: float) -> None:
            surface.aire_m2 = max(v, 0.0)
            # Un ajutage encodé en l/(s·ha) se recalcule sur la nouvelle surface.
            self.etat.projet.recalculer_ajutage()
            self.etat.invalider()
            rafraichir_ligne()

        def maj_coef(v: float) -> None:
            surface.coefficient = max(min(v, 1.5), 0.0)
            self.etat.invalider()
            rafraichir_ligne()

        def supprimer(_=None) -> None:
            self.etat.projet.surfaces.pop(index)
            self.etat.projet.recalculer_ajutage()
            self.etat.invalider()
            self.rafraichir()

        personnalisee = index >= len(TYPES_SURFACES)
        libelle: ft.Control
        if personnalisee:
            def maj_libelle(e: ft.ControlEvent) -> None:
                surface.libelle = e.control.value

            libelle = ft.TextField(value=surface.libelle, label="Surface personnalisée", dense=True,
                                   border_radius=10, text_size=13, on_change=maj_libelle)
        else:
            libelle = ft.Text(surface.libelle, size=13, weight=ft.FontWeight.W_500, no_wrap=False)

        return ft.Container(
            content=ft.ResponsiveRow(
                [
                    ft.Container(libelle, col={"xs": 12, "md": 5},
                                 alignment=ft.alignment.center_left,
                                 padding=ft.padding.only(bottom=2)),
                    ft.Container(
                        theme.champ_nombre("Coefficient", surface.coefficient, maj_coef,
                                           on_valide=rafraichir_ligne, compact=True),
                        col={"xs": 5, "md": 2},
                    ),
                    ft.Container(
                        theme.champ_nombre("Surface", surface.aire_m2, maj_aire, "m²",
                                           on_valide=rafraichir_ligne, compact=True),
                        col={"xs": 7, "md": 3},
                    ),
                    ft.Container(
                        ft.Row(
                            [
                                actifs,
                                ft.IconButton(ft.Icons.DELETE_OUTLINE, icon_size=18,
                                              tooltip="Supprimer", on_click=supprimer)
                                if personnalisee else ft.Container(),
                            ],
                            spacing=4,
                            alignment=ft.MainAxisAlignment.END,
                        ),
                        col={"xs": 12, "md": 2},
                        alignment=ft.alignment.center_right,
                    ),
                ],
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
                spacing=8,
                run_spacing=8,
            ),
            padding=ft.padding.symmetric(6, 4),
            border_radius=10,
            bgcolor=ft.Colors.with_opacity(0.55, ft.Colors.SURFACE) if surface.aire_m2 > 0 else None,
        )

    def _maj_totaux(self) -> None:
        p = self.etat.projet
        self._total.value = (
            f"Surface totale : {theme.nombre(p.aire_totale_m2, 0)} m²    ·    "
            f"Surface active pondérée : {theme.nombre(p.aire_ponderee_m2, 1)} m²    ·    "
            f"Coefficient moyen : {theme.nombre(p.coefficient_moyen, 3)}"
        )
        try:
            self._total.update()
        except Exception:
            pass

    def _ajouter_surface(self, _=None) -> None:
        self.etat.projet.surfaces.append(SurfaceIncidente("Autre surface (à justifier)", 0.8, 0.0))
        self.etat.invalider()
        self.rafraichir()

    # ---------------------------------------------------------------- rendu
    # ---------------------------------------------------- import / export
    def _sur_mobile(self) -> bool:
        try:
            return self.page.platform in (ft.PagePlatform.ANDROID, ft.PagePlatform.IOS)
        except Exception:
            return False

    def _exporter(self, _=None) -> None:
        """Écrit le projet dans un fichier, puis propose de le ranger ailleurs."""
        try:
            chemin = self.etat.exporter_vers(
                os.path.join(repertoire_documents(),
                             os.path.basename(self.etat.nom_fichier_projet())))
        except Exception as exc:
            self.notifier(f"Enregistrement impossible : {type(exc).__name__} — {exc}", "erreur")
            return
        self._dernier_export = chemin
        self.rafraichir()
        if self._sur_mobile():
            # Le sélecteur d'Android ne rend qu'un URI de document, inutilisable
            # depuis Python : le fichier est déjà au bon endroit, dans un dossier
            # que n'importe quel gestionnaire de fichiers sait ouvrir.
            self.notifier(f"Projet enregistré : {chemin}", "succes")
            return
        self.notifier(f"Projet enregistré dans {chemin}", "succes")
        self._enregistrer_sous(chemin)

    def _enregistrer_sous(self, chemin: str) -> None:
        """Sélecteur du système, pour choisir soi-même la destination."""
        if self._selecteur_export is None:
            def _resultat(e: ft.FilePickerResultEvent) -> None:
                cible = getattr(e, "path", None)
                source = getattr(self._selecteur_export, "data", None)
                if not cible or not source:
                    return
                if not cible.lower().endswith("." + EXTENSION_PROJET):
                    cible = f"{cible}.{EXTENSION_PROJET}"
                if not destination_utilisable(cible):
                    # Ce n'est pas un chemin de fichier mais un URI du système :
                    # inutile d'alarmer, le projet est déjà enregistré.
                    self.notifier(
                        f"Cet emplacement n'est pas accessible en écriture directe. "
                        f"Le projet reste enregistré dans {source}.", "alerte")
                    return
                try:
                    shutil.copyfile(source, cible)
                    self._dernier_export = cible
                    self.rafraichir()
                    self.notifier(f"Projet copié vers {cible}", "succes")
                except Exception as exc:
                    self.notifier(f"Copie impossible : {exc}", "erreur")

            self._selecteur_export = ft.FilePicker(on_result=_resultat)
            self.page.overlay.append(self._selecteur_export)
            self.page.update()
        self._selecteur_export.data = chemin
        try:
            self._selecteur_export.save_file(
                dialog_title="Enregistrer le projet",
                file_name=os.path.basename(chemin),
                allowed_extensions=[EXTENSION_PROJET],
            )
        except Exception:
            # Sur certaines plateformes le sélecteur n'existe pas : le fichier
            # est déjà écrit, il suffit de dire où.
            self.notifier(f"Le sélecteur de fichiers n'est pas disponible ici. "
                          f"Le projet reste dans {os.path.dirname(chemin)}.", "alerte")

    def _importer(self, _=None) -> None:
        if self._selecteur_import is None:
            def _resultat(e: ft.FilePickerResultEvent) -> None:
                fichiers = getattr(e, "files", None) or []
                chemin = getattr(fichiers[0], "path", None) if fichiers else None
                if not chemin:
                    return
                self.charger_fichier(chemin)

            self._selecteur_import = ft.FilePicker(on_result=_resultat)
            self.page.overlay.append(self._selecteur_import)
            self.page.update()
        try:
            self._selecteur_import.pick_files(
                dialog_title="Ouvrir un projet HydroBassin",
                allow_multiple=False,
                allowed_extensions=[EXTENSION_PROJET],
            )
        except Exception as exc:
            self.notifier(f"Le sélecteur de fichiers n'est pas disponible ici ({exc}).", "erreur")

    def charger_fichier(self, chemin: str) -> bool:
        """Charge un projet et reconstruit la vue. Renvoie False en cas d'échec."""
        if not source_utilisable(chemin):
            self.notifier(
                "Ce fichier n'est pas accessible directement. Copiez-le dans le dossier "
                "Téléchargements ou Documents, puis réessayez.", "erreur")
            return False
        try:
            self.etat.importer_fichier(chemin)
        except Exception as exc:
            self.notifier(str(exc), "erreur")
            return False
        self.rafraichir()
        self.notifier(f"Projet « {self.etat.projet.nom_projet or os.path.basename(chemin)} » "
                      f"chargé.", "succes")
        return True

    def _bloc_sauvegarde(self) -> ft.Control:
        lignes: List[ft.Control] = [
            ft.Text("Un projet exporté se recharge tel quel : surfaces, sol, ouvrage, bassin "
                    "amont et scénario retenu. De quoi reprendre une étude sans tout resaisir, "
                    "ou la transmettre à un collègue.", size=12, color=theme.GRIS),
            ft.Row(
                [
                    theme.bouton_principal("Exporter le projet", ft.Icons.SAVE, self._exporter),
                    theme.bouton_secondaire("Importer un projet", ft.Icons.FOLDER_OPEN,
                                            self._importer),
                ],
                spacing=10,
                wrap=True,
            ),
        ]
        if self._dernier_export:
            lignes.append(ft.Text(f"Dernier enregistrement : {self._dernier_export}",
                                  size=11, color=theme.GRIS, selectable=True))
        return ft.Column(lignes, spacing=12)

    def construire(self) -> List[ft.Control]:
        p = self.etat.projet
        commune = rainfall.commune_par_ins(p.commune_ins)

        def maj_texte(champ: str):
            def _f(e: ft.ControlEvent) -> None:
                setattr(p, champ, e.control.value)
            return _f

        identification = ft.ResponsiveRow(
            [
                ft.Container(ft.TextField(label="Nom du projet", value=p.nom_projet, dense=True,
                                          border_radius=10, on_change=maj_texte("nom_projet")),
                             col={"xs": 12, "md": 6}),
                ft.Container(ft.TextField(label="Localisation", value=p.localisation, dense=True,
                                          border_radius=10, on_change=maj_texte("localisation")),
                             col={"xs": 12, "md": 6}),
                ft.Container(ft.TextField(label="Auteur du calcul", value=p.auteur, dense=True,
                                          border_radius=10, on_change=maj_texte("auteur")),
                             col={"xs": 12, "md": 6}),
                ft.Container(ft.TextField(label="Remarques", value=p.remarques, dense=True, multiline=True,
                                          min_lines=1, max_lines=3, border_radius=10,
                                          on_change=maj_texte("remarques")),
                             col={"xs": 12, "md": 6}),
            ],
            spacing=12,
            run_spacing=12,
        )

        def maj_recurrence(e: ft.ControlEvent) -> None:
            self.etat.definir("periode_retour", int(e.control.value))
            self.rafraichir()

        def maj_source(e: ft.ControlEvent) -> None:
            self.etat.definir("source_pluie", e.control.value)
            self.rafraichir()

        sources = [ft.dropdown.Option(rainfall.SOURCE_MONTANA, "Montana")]
        if commune and commune.a_qdf:
            sources.append(ft.dropdown.Option(rainfall.SOURCE_QDF, "Tables QDF"))
        source_effective = rainfall.SourcePluie(p.commune_ins, p.periode_retour, p.source_pluie).source

        pluie = ft.ResponsiveRow(
            [
                ft.Container(
                    ft.Column(
                        [
                            ft.Text("Commune", size=12, color=theme.GRIS, weight=ft.FontWeight.W_600),
                            ft.Container(
                                content=ft.Row(
                                    [
                                        ft.Icon(ft.Icons.LOCATION_CITY, color=theme.BLEU),
                                        ft.Column(
                                            [
                                                ft.Text(p.commune_nom, size=16, weight=ft.FontWeight.W_700),
                                                ft.Text(f"INS {p.commune_ins}", size=11, color=theme.GRIS),
                                            ],
                                            spacing=0,
                                            expand=True,
                                        ),
                                        ft.Icon(ft.Icons.CHEVRON_RIGHT, color=theme.GRIS),
                                    ],
                                    spacing=12,
                                ),
                                on_click=self._ouvrir_selecteur_commune,
                                padding=ft.padding.symmetric(12, 14),
                                border_radius=10,
                                border=ft.border.all(1, ft.Colors.OUTLINE_VARIANT),
                                ink=True,
                            ),
                        ],
                        spacing=6,
                    ),
                    col={"xs": 12, "md": 5},
                ),
                ft.Container(
                    ft.Dropdown(
                        label="Période de retour",
                        value=str(p.periode_retour),
                        options=[ft.dropdown.Option(str(rp), f"{rp} ans") for rp in rainfall.RETURN_PERIODS],
                        on_change=maj_recurrence,
                        dense=True,
                        border_radius=10,
                    ),
                    col={"xs": 6, "md": 3},
                ),
                ft.Container(
                    ft.Dropdown(
                        label="Source des pluies",
                        value=source_effective,
                        options=sources,
                        on_change=maj_source,
                        dense=True,
                        border_radius=10,
                    ),
                    col={"xs": 6, "md": 4},
                ),
            ],
            spacing=12,
            run_spacing=12,
        )

        avertissements: List[ft.Control] = []
        if p.periode_retour < 25:
            avertissements.append(theme.message(
                "Le GTI recommande une période de retour d'au moins 25 ans pour le dimensionnement.", "alerte"))
        if commune and not commune.a_montana:
            avertissements.append(theme.message(
                f"{commune.nom} ne dispose pas des coefficients de Montana dans le GTI : "
                "les tables QDF sont utilisées (interpolation logarithmique).", "info"))

        self._total = ft.Text(
            f"Surface totale : {theme.nombre(p.aire_totale_m2, 0)} m²    ·    "
            f"Surface active pondérée : {theme.nombre(p.aire_ponderee_m2, 1)} m²    ·    "
            f"Coefficient moyen : {theme.nombre(p.coefficient_moyen, 3)}",
            size=13,
            weight=ft.FontWeight.W_700,
            color=theme.BLEU,
        )

        surfaces = ft.Column(
            [self._ligne_surface(i, s) for i, s in enumerate(p.surfaces)]
            + [
                ft.Row(
                    [
                        theme.bouton_secondaire("Ajouter une surface", ft.Icons.ADD, self._ajouter_surface),
                        ft.Container(expand=True),
                    ]
                ),
                ft.Divider(height=16),
                self._total,
            ],
            spacing=4,
        )

        def maj_sref(v: float) -> None:
            self.etat.definir("surface_reference_m2", v)

        return [
            theme.section("Identification du projet", identification, ft.Icons.EDIT_DOCUMENT),
            theme.section("Sauvegarde du projet", self._bloc_sauvegarde(), ft.Icons.SAVE_ALT,
                          "Exporter pour reprendre plus tard · importer un projet existant"),
            theme.section("Pluie de projet", ft.Column([pluie] + avertissements, spacing=12),
                          ft.Icons.WATER_DROP_OUTLINED,
                          "Pluies statistiques du GTI (Région wallonne)"),
            theme.section(
                "Surfaces incidentes",
                ft.Column(
                    [
                        ft.ResponsiveRow(
                            [
                                ft.Container(
                                    theme.champ_nombre("Surface de référence du projet",
                                                       p.surface_reference_m2, maj_sref, "m²",
                                                       "parcelle concernée par le projet"),
                                    col={"xs": 12, "md": 5},
                                ),
                            ]
                        ),
                        ft.Divider(height=14),
                        surfaces,
                    ],
                    spacing=10,
                ),
                ft.Icons.GRID_ON,
                "Coefficients de ruissellement du GTI par type d'occupation du sol",
            ),
        ]
