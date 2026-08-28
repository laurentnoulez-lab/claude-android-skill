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
from ..state import diagnostic_stockage, repertoire_documents
from .base import Vue

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

    # ------------------------------------------------------------ génération
    def _generer(self, formats: List[str]) -> None:
        etat = self.etat
        self.erreurs = []
        if etat.projet.aire_ponderee_m2 <= 0:
            self.erreurs.append("Aucune surface incidente encodée : encodez au moins une surface "
                                "dans l'onglet « Projet » avant de générer un rapport.")
            self.maj_resultats()
            return

        try:
            dossier = etat.dossier()
        except Exception as exc:
            self.erreurs.append(f"Erreur de calcul : {type(exc).__name__} — {exc}")
            self.maj_resultats()
            return

        destination = repertoire_documents()
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

    def _enregistrer_sous(self, chemin: str) -> None:
        """Propose de copier un rapport ailleurs (sélecteur du système)."""
        if self.selecteur_fichier is None:
            def _resultat(e: ft.FilePickerResultEvent) -> None:
                cible = getattr(e, "path", None)
                source = getattr(self.selecteur_fichier, "data", None)
                if not cible or not source:
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
                    ("Projet", p.nom_projet or "—"),
                    ("Commune", f"{p.commune_nom} (INS {p.commune_ins})"),
                    ("Période de retour", f"{p.periode_retour} ans"),
                    ("Surface active pondérée", f"{p.aire_ponderee_m2:.1f} m²"),
                    ("Scénario retenu", LIBELLES_SCENARIOS[etat.scenario_principal]),
                    ("Volume de temporisation",
                     f"{res.volume_m3:.1f} m³" if res.dimensionnable else "— (aucun débit de sortie)"),
                    ("Durée critique", res.duree_critique_hm if res.dimensionnable else "—"),
                    ("Temps de vidange", res.temps_vidange_hm if res.dimensionnable else "—"),
                    ("Ouvrage encodé",
                     f"{etat.bassin.volume_total_m3:.1f} m³" if etat.bassin_valide else "non encodé"),
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
            theme.section("Récapitulatif du dossier", recap, ft.Icons.FACT_CHECK),
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
                "Le rapport reprend les données d'entrée, les quatre scénarios, la simulation, "
                "la table QDF et l'ajutage.",
            ),
        ]
