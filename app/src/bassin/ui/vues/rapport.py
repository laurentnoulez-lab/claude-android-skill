"""Vue « Rapport » : génération des livrables Excel, Word et PDF."""

from __future__ import annotations

import os
import traceback
from typing import List

import flet as ft

from ...core.model import LIBELLES_SCENARIOS
from ...reports import docx_report, pdf_report, xlsx_report
from .. import theme
from ..state import repertoire_documents
from .base import Vue

FORMATS = (
    ("xlsx", "Classeur Excel", "Feuilles de calcul vivantes : les formules de la méthode rationnelle "
                               "sont écrites dans le classeur et se recalculent.", ft.Icons.TABLE_VIEW),
    ("docx", "Document Word", "Rapport rédigé, tableaux et graphiques, prêt à être complété et signé.",
     ft.Icons.DESCRIPTION),
    ("pdf", "Rapport PDF", "Document final paginé, graphiques vectoriels, prêt à être transmis.",
     ft.Icons.PICTURE_AS_PDF),
)


class VueRapport(Vue):
    titre = "Rapport"
    icone = ft.Icons.SUMMARIZE
    sous_titre = "Excel, Word et PDF"

    def __init__(self, page, etat):
        super().__init__(page, etat)
        self._journal: List[str] = []

    def _generer(self, formats: List[str]) -> None:
        etat = self.etat
        if etat.projet.aire_ponderee_m2 <= 0:
            self.notifier("Encodez au moins une surface incidente avant de générer un rapport.", "erreur")
            return
        try:
            dossier = etat.dossier()
        except Exception as exc:  # pragma: no cover - garde-fou interface
            self.notifier(f"Erreur de calcul : {exc}", "erreur")
            return
        produits: List[str] = []
        for fmt in formats:
            chemin = etat.nom_fichier(fmt)
            try:
                if fmt == "xlsx":
                    xlsx_report.ecrire(dossier, chemin)
                elif fmt == "docx":
                    docx_report.ecrire(dossier, chemin)
                else:
                    pdf_report.ecrire(dossier, chemin)
                produits.append(chemin)
            except Exception as exc:  # pragma: no cover - garde-fou interface
                self._journal.append(f"Échec {fmt.upper()} : {exc}")
                traceback.print_exc()
        self._journal = [f"{os.path.basename(c)} — {c}" for c in produits] + self._journal[:6]
        self.rafraichir()
        if produits:
            self.notifier(f"{len(produits)} fichier(s) généré(s) dans {repertoire_documents()}", "succes")

    def construire(self) -> List[ft.Control]:
        etat = self.etat
        p = etat.projet
        res = etat.resultat

        recap = ft.Column(
            [
                ft.Row([ft.Text(k, size=12.5, color=theme.GRIS, width=210),
                        ft.Text(v, size=12.5, weight=ft.FontWeight.W_600, expand=True)])
                for k, v in [
                    ("Projet", p.nom_projet or "—"),
                    ("Commune", f"{p.commune_nom} (INS {p.commune_ins})"),
                    ("Période de retour", f"{p.periode_retour} ans"),
                    ("Surface active pondérée", f"{p.aire_ponderee_m2:.1f} m²"),
                    ("Scénario retenu", LIBELLES_SCENARIOS[etat.scenario_principal]),
                    ("Volume de temporisation", f"{res.volume_m3:.1f} m³"),
                    ("Durée critique", res.duree_critique_hm),
                    ("Temps de vidange", res.temps_vidange_hm),
                    ("Ouvrage encodé",
                     f"{etat.bassin.volume_total_m3:.1f} m³" if etat.bassin_valide else "non encodé"),
                ]
            ],
            spacing=4,
        )

        cartes = ft.ResponsiveRow(
            [
                ft.Container(
                    ft.Column(
                        [
                            ft.Row([ft.Icon(icone, color=theme.BLEU, size=22),
                                    ft.Text(titre, size=14, weight=ft.FontWeight.W_700)], spacing=8),
                            ft.Text(description, size=12, color=theme.GRIS),
                            ft.Container(height=4),
                            theme.bouton_principal("Générer", ft.Icons.DOWNLOAD,
                                                   lambda _, f=cle: self._generer([f])),
                        ],
                        spacing=8,
                    ),
                    padding=18,
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

        journal: List[ft.Control] = []
        if self._journal:
            journal.append(ft.Text("Fichiers générés", size=13, weight=ft.FontWeight.W_700))
            journal += [
                ft.Row([ft.Icon(ft.Icons.INSERT_DRIVE_FILE, size=16, color=theme.VERT),
                        ft.Text(ligne, size=11.5, selectable=True, expand=True)], spacing=8)
                for ligne in self._journal
            ]

        return [
            theme.section("Récapitulatif du dossier", recap, ft.Icons.FACT_CHECK),
            theme.section(
                "Générer les livrables",
                ft.Column(
                    [
                        cartes,
                        ft.Row(
                            [
                                theme.bouton_principal("Générer les trois formats", ft.Icons.LIBRARY_ADD,
                                                       lambda _: self._generer(["xlsx", "docx", "pdf"])),
                            ]
                        ),
                        theme.message(f"Dossier de destination : {repertoire_documents()}", "info"),
                    ]
                    + journal,
                    spacing=14,
                ),
                ft.Icons.SHARE,
                "Le rapport reprend les données d'entrée, les quatre scénarios, la simulation, "
                "la table QDF et l'ajutage.",
            ),
        ]
