"""Vue « Table QDF » : pluies absorbées par l'ouvrage sans débordement."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import rainfall
from .. import theme
from ..composants import barre_ouvrage
from .base import Vue


class VueTableQDF(Vue):
    titre = "Table QDF"
    icone = ft.Icons.TABLE_CHART
    sous_titre = "Pluies absorbées sans débordement"

    def construire(self) -> List[ft.Control]:
        if not self.etat.bassin_valide:
            return [theme.message(
                "Encodez d'abord un bassin (onglet « Bassin réel ») pour construire la table QDF.",
                "info")]

        table = self.etat.table_acceptation
        assert table is not None
        p = self.etat.projet
        src = rainfall.SourcePluie(p.commune_ins, p.periode_retour, p.source_pluie)
        mode = getattr(self, "_mode", "volume")

        def changer_mode(e: ft.ControlEvent) -> None:
            self._mode = e.control.value
            self.rafraichir()


        selecteur = ft.ResponsiveRow(
            [
                theme.selecteur(
                    "Valeur affichée", mode,
                    [("volume", "Volume requis [m³]"), ("taux", "Remplissage [%]"),
                     ("pluie", "Hauteur de pluie [mm]")],
                    changer_mode, col={"xs": 12, "md": 5},
                )
            ]
        )

        colonnes = [ft.DataColumn(ft.Text("Durée", size=12, weight=ft.FontWeight.W_700))]
        colonnes += [
            ft.DataColumn(
                ft.Text(f"{rp} ans", size=12,
                        weight=ft.FontWeight.W_700 if rp == p.periode_retour else ft.FontWeight.W_500,
                        color=theme.BLEU if rp == p.periode_retour else None),
                numeric=True,
            )
            for rp in table.periodes_retour
        ]

        lignes: List[ft.DataRow] = []
        for i, _ in enumerate(table.durees_min):
            cellules = [ft.DataCell(ft.Text(rainfall.QDF_DURATION_LABELS[i], size=12,
                                            weight=ft.FontWeight.W_600))]
            for j in range(len(table.periodes_retour)):
                c = table.cellules[i][j]
                couleur, fond = theme.COULEURS_STATUT.get(c.statut, (theme.GRIS, theme.GRIS_CLAIR))
                if mode == "volume":
                    texte = f"{c.volume_requis_m3:.1f}"
                elif mode == "taux":
                    texte = "∞" if c.capacite_m3 <= 0 else f"{min(c.taux, 9.99) * 100:.0f}"
                else:
                    texte = f"{c.hauteur_mm:.1f}"
                cellules.append(
                    ft.DataCell(
                        ft.Container(
                            ft.Text(theme.fr(texte), size=12, color=couleur, weight=ft.FontWeight.W_600,
                                    text_align=ft.TextAlign.CENTER),
                            bgcolor=fond,
                            padding=ft.padding.symmetric(4, 8),
                            border_radius=6,
                            alignment=ft.alignment.center,
                            tooltip=theme.fr(
                                f"{rainfall.QDF_DURATION_LABELS[i]} · T = {c.periode_retour} ans\n"
                                f"Pluie : {c.hauteur_mm:.1f} mm\n"
                                f"Volume requis : {c.volume_requis_m3:.1f} m³ / "
                                f"{c.capacite_m3:.1f} m³\n"
                                f"Vidange : {theme.duree_h(c.temps_vidange_h)}"),
                        )
                    )
                )
            lignes.append(ft.DataRow(cells=cellules))

        tableau = theme.tableau_defilant(
            ft.DataTable(columns=colonnes, rows=lignes, column_spacing=10,
                         heading_row_height=36, data_row_max_height=40, divider_thickness=0.4)
        )

        rp_max = table.periode_retour_max_acceptee()
        if rp_max:
            bandeau = theme.message(
                f"L'ouvrage absorbe sans débordement toutes les pluies jusqu'à la récurrence "
                f"{rp_max} ans (toutes durées confondues).",
                "succes" if rp_max >= p.periode_retour else "alerte",
            )
        else:
            bandeau = theme.message(
                "L'ouvrage déborde déjà pour la pluie de récurrence 2 ans.", "erreur")

        critiques = table.durees_critiques(p.periode_retour)
        if critiques:
            libelles = ", ".join(
                rainfall.QDF_DURATION_LABELS[list(table.durees_min).index(d)] for d in critiques)
            bandeau_2 = theme.message(
                f"Débordement pour la récurrence de projet ({p.periode_retour} ans) "
                f"aux durées suivantes : {libelles}.", "erreur")
        else:
            bandeau_2 = theme.message(
                f"Aucun débordement pour la récurrence de projet ({p.periode_retour} ans), "
                "quelle que soit la durée de pluie.", "succes")

        # Le bandeau de sélection annonce le volume du *dimensionnement*, qui
        # raisonne sur un scénario ; cette table décrit l'ouvrage **encodé**,
        # volume mort compris. Les deux peuvent légitimement différer — sur un
        # ajutage surélevé, notamment — et l'onglet doit donc porter son propre
        # chiffre, sans quoi « 0,0 m³ requis » surmonte une table pleine de
        # volumes sans que rien ne l'explique.
        requis = table.volume_requis_max_m3(p.periode_retour)
        exige = theme.etiquette(
            theme.fr(f"Ouvrage encodé : {requis:.1f} m³ requis à {p.periode_retour} ans "
                     f"pour {table.capacite_m3:.1f} m³ encodés"),
            theme.BLEU, theme.BLEU_CLAIR, ft.Icons.STRAIGHTEN)

        legende = ft.Row(
            [
                exige,
                theme.etiquette("Absorbé", theme.VERT, theme.VERT_CLAIR, ft.Icons.CHECK_CIRCLE),
                theme.etiquette("Limite (> 95 % de la capacité)", theme.ORANGE, theme.ORANGE_CLAIR,
                                ft.Icons.WARNING_AMBER),
                theme.etiquette("Débordement", theme.ROUGE, theme.ROUGE_CLAIR, ft.Icons.ERROR),
            ],
            wrap=True,
            spacing=8,
            run_spacing=8,
        )

        return [
            self.bloc_derive(lambda: barre_ouvrage(self)),
            theme.section(
                "Capacité d'absorption du bassin",
                ft.Column([bandeau, bandeau_2, legende], spacing=12),
                ft.Icons.VERIFIED,
                f"Bassin de {theme.nombre(self.etat.bassin.volume_total_m3, 1, 'm³')} · "
                f"{self.etat.projet.commune_nom}",
            ),
            theme.section(
                f"{src.titre_tableau_volumes} — ouvrage encodé",
                ft.Column([selecteur, ft.Container(tableau, padding=ft.padding.only(top=8))], spacing=10),
                ft.Icons.GRID_ON,
                "Lignes : durée de pluie · Colonnes : période de retour",
            ),
        ]
