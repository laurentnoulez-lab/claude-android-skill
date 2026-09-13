"""Vue « Projet » : identification, commune, récurrence, contraintes, sauvegarde.

Les surfaces ont quitté cet onglet pour celui des **bassins versants** : un
projet peut en compter plusieurs, chacun avec son propre tableau de surfaces et
son propre raccordement. Ce qui reste ici est ce qui vaut pour tout le système.
"""

from __future__ import annotations

import os
import shutil
from typing import List, Optional

import flet as ft

from ...core import rainfall
from .. import theme
from ..state import (EXTENSION_PROJET, destination_utilisable, repertoire_documents,
                     source_utilisable)
from .base import Vue


class VueProjet(Vue):
    titre = "Projet"
    icone = ft.Icons.FOLDER_OPEN
    sous_titre = "Identification, pluie de projet et contraintes"

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
        systeme = self.etat.systeme
        self.notifier(
            f"Projet « {systeme.nom_projet or os.path.basename(chemin)} » chargé : "
            f"{len(systeme.bassins_versants)} bassin(s) versant(s), "
            f"{len(systeme.ouvrages)} bassin(s) d'orage.", "succes")
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
        systeme = self.etat.systeme
        commune = rainfall.commune_par_ins(systeme.commune_ins)

        def maj_texte(champ: str):
            def _f(e: ft.ControlEvent) -> None:
                # L'identification appartient au système : l'écrire sur l'étude
                # d'un ouvrage serait effacé à la synchronisation suivante.
                setattr(systeme, champ, e.control.value)
            return _f

        identification = ft.ResponsiveRow(
            [
                ft.Container(ft.TextField(label="Nom du projet", value=systeme.nom_projet,
                                          dense=True, border_radius=10,
                                          on_change=maj_texte("nom_projet")),
                             col={"xs": 12, "md": 6}),
                ft.Container(ft.TextField(label="Localisation", value=systeme.localisation,
                                          dense=True, border_radius=10,
                                          on_change=maj_texte("localisation")),
                             col={"xs": 12, "md": 6}),
                ft.Container(ft.TextField(label="Auteur du calcul", value=systeme.auteur,
                                          dense=True, border_radius=10,
                                          on_change=maj_texte("auteur")),
                             col={"xs": 12, "md": 6}),
                ft.Container(ft.TextField(label="Remarques", value=systeme.remarques, dense=True,
                                          multiline=True, min_lines=1, max_lines=3,
                                          border_radius=10, on_change=maj_texte("remarques")),
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
        source_effective = rainfall.SourcePluie(
            systeme.commune_ins, systeme.periode_retour, systeme.source_pluie).source

        pluie = ft.ResponsiveRow(
            [
                ft.Container(
                    ft.Column(
                        [
                            ft.Text("Commune", size=12, color=theme.GRIS,
                                    weight=ft.FontWeight.W_600),
                            ft.Container(
                                content=ft.Row(
                                    [
                                        ft.Icon(ft.Icons.LOCATION_CITY, color=theme.BLEU),
                                        ft.Column(
                                            [
                                                ft.Text(systeme.commune_nom, size=16,
                                                        weight=ft.FontWeight.W_700),
                                                ft.Text(f"INS {systeme.commune_ins}", size=11,
                                                        color=theme.GRIS),
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
                        value=str(systeme.periode_retour),
                        options=[ft.dropdown.Option(str(rp), f"{rp} ans")
                                 for rp in rainfall.RETURN_PERIODS],
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
        if systeme.periode_retour < 25:
            avertissements.append(theme.message(
                "Le GTI recommande une période de retour d'au moins 25 ans pour le "
                "dimensionnement.", "alerte"))
        if commune and not commune.a_montana:
            avertissements.append(theme.message(
                f"{commune.nom} ne dispose pas des coefficients de Montana dans le GTI : "
                "les tables QDF sont utilisées (interpolation logarithmique).", "info"))

        def maj_vidange(v: float) -> None:
            self.etat.definir("temps_vidange_max_h", max(v, 0.0))

        def maj_securite(v: float) -> None:
            self.etat.definir("coef_securite_infiltration", max(v, 1e-9))

        contraintes = ft.ResponsiveRow(
            [
                theme.champ_nombre("Temps de vidange maximum", systeme.temps_vidange_max_h,
                                   maj_vidange, "h", "après la pluie · GTI : 48 h",
                                   on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 4}),
                theme.champ_nombre("Coefficient de sécurité sur K",
                                   systeme.coef_securite_infiltration, maj_securite, "—",
                                   "GTI : 2", on_valide=self.maj_resultats,
                                   col={"xs": 12, "sm": 6, "md": 4}),
            ],
            spacing=12,
            run_spacing=12,
        )

        self.zone.controls = self.resultats()
        return [
            theme.section("Identification du projet", identification, ft.Icons.EDIT_DOCUMENT),
            theme.section("Sauvegarde du projet", self._bloc_sauvegarde(), ft.Icons.SAVE_ALT,
                          "Exporter pour reprendre plus tard · importer un projet existant"),
            theme.section("Pluie de projet", ft.Column([pluie] + avertissements, spacing=12),
                          ft.Icons.WATER_DROP_OUTLINED,
                          "Pluies statistiques du GTI (Région wallonne) · commune à tout le réseau"),
            theme.section("Contraintes du GTI", contraintes, ft.Icons.RULE,
                          "Elles s'appliquent à tous les bassins d'orage du projet"),
            theme.section("Composition du projet", self.zone, ft.Icons.ACCOUNT_TREE,
                          "Les surfaces s'encodent dans l'onglet « Bassins versants »"),
        ]

    def resultats(self) -> List[ft.Control]:
        systeme = self.etat.systeme
        return [
            ft.Row(
                [
                    theme.etiquette(f"{len(systeme.bassins_versants)} bassin(s) versant(s)",
                                    theme.VERT, theme.VERT_CLAIR, ft.Icons.LANDSCAPE),
                    theme.etiquette(f"{len(systeme.ouvrages)} bassin(s) d'orage", theme.BLEU,
                                    theme.BLEU_CLAIR, ft.Icons.WATER_DAMAGE),
                    theme.etiquette(
                        f"Surface totale {theme.nombre(systeme.aire_totale_m2, 0)} m²",
                        theme.ARDOISE, theme.GRIS_CLAIR, ft.Icons.CROP_LANDSCAPE),
                    theme.etiquette(
                        f"Surface active {theme.nombre(systeme.aire_ponderee_m2, 1)} m²",
                        theme.GRIS, theme.GRIS_CLAIR, ft.Icons.GRASS),
                    theme.etiquette(
                        f"C moyen {theme.nombre(systeme.coefficient_moyen, 3)}",
                        theme.GRIS, theme.GRIS_CLAIR, ft.Icons.FUNCTIONS),
                ],
                wrap=True, spacing=8, run_spacing=8,
            ),
        ] + [theme.message(a, "alerte") for a in systeme.anomalies()]
