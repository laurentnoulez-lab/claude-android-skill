"""Vue « Rapport » : génération des livrables Excel, Word et PDF."""

from __future__ import annotations

import os
import shutil
import traceback
from typing import Dict, List, Optional, Tuple

import flet as ft

from ...core.model import LIBELLES_SCENARIOS
from ...reports import docx_report, pdf_report, xlsx_report
from .. import theme
from ..composants import barre_ouvrage
from ..state import destination_utilisable, diagnostic_stockage, repertoire_documents
from .base import Vue
from .plan_rapport import EditeurPlan

FORMATS = (
    ("xlsx", "Classeur Excel", "Feuilles de calcul vivantes : les formules de la méthode "
                               "rationnelle sont écrites dans le classeur et se recalculent.",
     ft.Icons.TABLE_VIEW),
    ("docx", "Document Word", "Rapport rédigé, tableaux et graphiques, prêt à être complété "
                              "et signé.", ft.Icons.DESCRIPTION),
    ("pdf", "Rapport PDF", "Document final paginé, graphiques vectoriels, prêt à être transmis.",
     ft.Icons.PICTURE_AS_PDF),
)

ECRIVAINS = {"xlsx": xlsx_report.ecrire, "docx": docx_report.ecrire, "pdf": pdf_report.ecrire}


class VueRapport(Vue):
    titre = "Rapport"
    icone = ft.Icons.SUMMARIZE
    sous_titre = "Excel, Word et PDF"

    def __init__(self, page, etat):
        super().__init__(page, etat)
        self.produits: List[str] = []
        self.erreurs: List[str] = []
        self.selecteur_fichier: Optional[ft.FilePicker] = None
        self.selecteur_dossier: Optional[ft.FilePicker] = None
        self.editeur = EditeurPlan(self)

    # ------------------------------------------------------------ génération
    def _generer(self, formats: List[str]) -> None:
        etat = self.etat
        self.erreurs = []
        if etat.systeme.aire_ponderee_m2 <= 0:
            self.erreurs.append("Aucune surface incidente encodée : encodez au moins une surface "
                                "dans l'onglet « Bassins versants » avant de générer un "
                                "rapport.")
            self.maj_resultats()
            return

        try:
            dossier = etat.dossier()
        except Exception as exc:
            self.erreurs.append(f"Erreur de calcul : {type(exc).__name__} — {exc}")
            self.maj_resultats()
            return

        destination = self.destination()
        produits: List[str] = []
        for fmt in formats:
            chemin = os.path.join(destination, os.path.basename(etat.nom_fichier(fmt)))
            try:
                ECRIVAINS[fmt](dossier, chemin)
                if not os.path.exists(chemin) or os.path.getsize(chemin) == 0:
                    raise OSError("fichier vide après écriture")
                produits.append(chemin)
            except Exception as exc:
                self.erreurs.append(
                    f"{fmt.upper()} : {type(exc).__name__} — {exc}\n"
                    f"Destination tentée : {chemin}"
                )
                traceback.print_exc()

        self.produits = produits + [c for c in self.produits if c not in produits]
        self.maj_resultats()
        if produits:
            self.notifier(f"{len(produits)} fichier(s) écrit(s) dans {destination}", "succes")
        elif self.erreurs:
            self.notifier("La génération a échoué — voir le détail dans la page.", "erreur")

    # ------------------------------------------------------------ destination
    def destination(self) -> str:
        """Dossier où sont écrits les livrables.

        Trois fichiers sont produits d'un coup : une boîte « Enregistrer sous »
        par fichier serait pénible. Le dossier se choisit donc une fois, et il
        s'affiche — l'utilisateur ne découvrait jusqu'ici sa destination que
        dans le message de succès.
        """
        choisi = getattr(self.etat, "dossier_livrables", "")
        if choisi and os.path.isdir(choisi):
            return choisi
        return repertoire_documents()

    def _choisir_destination(self, _=None) -> None:
        if self.selecteur_dossier is None:
            def _resultat(e: ft.FilePickerResultEvent) -> None:
                chemin = getattr(e, "path", None)
                if not chemin:
                    return                      # annulation : rien ne change
                if not destination_utilisable(os.path.join(chemin, "test")):
                    self.notifier("Ce dossier n'est pas accessible en écriture directe.", "alerte")
                    return
                self.etat.dossier_livrables = chemin
                self.notifier(f"Les livrables seront écrits dans {chemin}", "succes")
                self.rafraichir()

            self.selecteur_dossier = ft.FilePicker(on_result=_resultat)
            self.page.overlay.append(self.selecteur_dossier)
            self.page.update()
        try:
            self.selecteur_dossier.get_directory_path(dialog_title="Dossier des livrables")
        except Exception:
            self.notifier("Le sélecteur de dossiers n'est pas disponible ici ; "
                          f"les livrables restent écrits dans {self.destination()}.", "alerte")

    def _enregistrer_sous(self, chemin: str) -> None:
        """Propose de copier un rapport ailleurs (sélecteur du système)."""
        if self.selecteur_fichier is None:
            def _resultat(e: ft.FilePickerResultEvent) -> None:
                cible = getattr(e, "path", None)
                source = getattr(self.selecteur_fichier, "data", None)
                if not cible or not source:
                    return
                if not destination_utilisable(cible):
                    # Android ne rend qu'un URI de document : le rapport est déjà
                    # écrit, autant dire où plutôt que d'afficher un échec.
                    self.notifier(f"Cet emplacement n'est pas accessible en écriture directe. "
                                  f"Le rapport reste disponible dans {os.path.dirname(source)}.",
                                  "alerte")
                    return
                try:
                    shutil.copyfile(source, cible)
                    self.notifier(f"Copié vers {cible}", "succes")
                except Exception as exc:
                    self.notifier(f"Copie impossible : {exc}", "erreur")

            self.selecteur_fichier = ft.FilePicker(on_result=_resultat)
            self.page.overlay.append(self.selecteur_fichier)
            self.page.update()
        self.selecteur_fichier.data = chemin
        try:
            self.selecteur_fichier.save_file(
                dialog_title="Enregistrer le rapport",
                file_name=os.path.basename(chemin),
                allowed_extensions=[os.path.splitext(chemin)[1].lstrip(".")],
            )
        except Exception as exc:
            self.notifier(f"Le sélecteur de fichiers n'est pas disponible ici ({exc}). "
                          f"Le rapport reste disponible dans {os.path.dirname(chemin)}.", "alerte")

    # ------------------------------------------------------------- résultats
    def resultats(self) -> List[ft.Control]:
        blocs: List[ft.Control] = []
        for message in self.erreurs:
            blocs.append(theme.message(message, "erreur"))
        if self.erreurs:
            lignes = [f"{'accessible' if ok else 'inaccessible'} — {chemin}"
                      for chemin, ok in diagnostic_stockage()]
            blocs.append(theme.message("Répertoires testés :\n" + "\n".join(lignes), "info"))

        blocs.append(ft.Row(
            [
                ft.Icon(ft.Icons.FOLDER_OUTLINED, size=18, color=theme.GRIS),
                ft.Column(
                    [
                        ft.Text("Dossier des livrables", size=11, color=theme.GRIS),
                        ft.Text(self.destination(), size=12, selectable=True, no_wrap=False),
                    ],
                    spacing=0, expand=True,
                ),
                theme.bouton_secondaire("Changer…", ft.Icons.DRIVE_FOLDER_UPLOAD,
                                        self._choisir_destination),
            ],
            spacing=10, vertical_alignment=ft.CrossAxisAlignment.CENTER,
        ))

        if self.produits:
            blocs.append(ft.Text("Fichiers générés", size=13, weight=ft.FontWeight.W_700))
            for chemin in self.produits[:9]:
                taille = os.path.getsize(chemin) // 1024 if os.path.exists(chemin) else 0
                blocs.append(
                    ft.Container(
                        content=ft.Row(
                            [
                                ft.Icon(ft.Icons.INSERT_DRIVE_FILE, size=18, color=theme.VERT),
                                ft.Column(
                                    [
                                        ft.Text(os.path.basename(chemin), size=12.5,
                                                weight=ft.FontWeight.W_600),
                                        ft.Text(f"{chemin} · {taille} Ko", size=11,
                                                color=theme.GRIS, selectable=True),
                                    ],
                                    spacing=0,
                                    expand=True,
                                ),
                                ft.IconButton(ft.Icons.SAVE_ALT, tooltip="Enregistrer sous…",
                                              on_click=lambda _, c=chemin: self._enregistrer_sous(c)),
                            ],
                            spacing=10,
                        ),
                        padding=ft.padding.symmetric(6, 10),
                        border_radius=10,
                        bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
                    )
                )
        return blocs

    def construire(self) -> List[ft.Control]:
        etat = self.etat
        p = etat.projet
        res = etat.resultat
        self.zone.controls = self.resultats()

        recap = ft.Column(
            [
                ft.ResponsiveRow(
                    [
                        ft.Container(ft.Text(k, size=12.5, color=theme.GRIS),
                                     col={"xs": 6, "md": 4}),
                        ft.Container(ft.Text(v, size=12.5, weight=ft.FontWeight.W_600),
                                     col={"xs": 6, "md": 8}),
                    ]
                )
                for k, v in [
                    ("Projet", etat.systeme.nom_projet or "—"),
                    ("Commune", f"{etat.systeme.commune_nom} (INS {etat.systeme.commune_ins})"),
                    ("Période de retour", f"{etat.systeme.periode_retour} ans"),
                    ("Bassins versants", str(len(etat.systeme.bassins_versants))),
                    ("Bassins d'orage", str(len(etat.systeme.ouvrages))),
                    ("Surface active du système",
                     theme.nombre(etat.systeme.aire_ponderee_m2, 1, "m²")),
                    # Le dossier porte sur l'étude entière : annoncer ici un
                    # « ouvrage détaillé » laissait croire qu'il s'arrête à lui.
                    ("Portée du dossier",
                     f"les {len(etat.systeme.ouvrages)} bassins d'orage, un chapitre chacun"
                     if len(etat.systeme.ouvrages) > 1 else "le bassin d'orage du projet"),
                    ("Ouvrage affiché à l'écran", etat.ouvrage.nom),
                    ("Surface active raccordée", theme.nombre(p.aire_ponderee_m2, 1, "m²")),
                    ("Scénario de cet ouvrage", LIBELLES_SCENARIOS[etat.scenario_principal]),
                    ("Volume de temporisation",
                     theme.nombre(res.volume_m3, 1, "m³") if res.dimensionnable
                     else "— (aucun débit de sortie)"),
                    ("Durée critique", res.duree_critique_hm if res.dimensionnable else "—"),
                    ("Vidange après la pluie", res.temps_vidange_hm if res.dimensionnable else "—"),
                    ("Ouvrage encodé",
                     theme.nombre(etat.bassin.volume_total_m3, 1, "m³") if etat.bassin_valide
                     else "non encodé"),
                ]
            ],
            spacing=2,
        )

        cartes = ft.ResponsiveRow(
            [
                ft.Container(
                    ft.Column(
                        [
                            ft.Row([ft.Icon(icone, color=theme.BLEU, size=22),
                                    ft.Text(titre, size=14, weight=ft.FontWeight.W_700, expand=True)],
                                   spacing=8),
                            ft.Text(description, size=12, color=theme.GRIS),
                            ft.Container(height=4),
                            theme.bouton_principal("Générer", ft.Icons.DOWNLOAD,
                                                   lambda _, f=cle: self._generer([f])),
                        ],
                        spacing=8,
                    ),
                    padding=16,
                    border_radius=theme.RAYON,
                    bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
                    border=ft.border.all(1, ft.Colors.OUTLINE_VARIANT),
                    col={"xs": 12, "md": 4},
                )
                for cle, titre, description, icone in FORMATS
            ],
            spacing=12,
            run_spacing=12,
        )

        return [
            self.bloc_derive(lambda: barre_ouvrage(self)),
            theme.section("Récapitulatif du dossier", recap, ft.Icons.FACT_CHECK),
            theme.section(
                "Composition du dossier",
                self.editeur.carte(),
                ft.Icons.LIST_ALT,
                "Décochez ce qui ne sert pas à cette étude, ajoutez vos propres rubriques — "
                "texte mis en forme, intertitres, images — et ordonnez l'ensemble. Le plan "
                "s'enregistre avec le projet.",
            ),
            theme.section(
                "Générer les livrables",
                ft.Column(
                    [
                        cartes,
                        ft.Row(
                            [theme.bouton_principal("Générer les trois formats", ft.Icons.LIBRARY_ADD,
                                                    lambda _: self._generer(["xlsx", "docx", "pdf"]))],
                        ),
                        theme.message(f"Dossier de destination : {repertoire_documents()}", "info"),
                        self.zone,
                    ],
                    spacing=14,
                ),
                ft.Icons.SHARE,
                "Le PDF et le Word suivent la composition ci-dessus ; le classeur Excel "
                "reprend l'étude entière, rubriques décochées comprises.",
            ),
        ]
