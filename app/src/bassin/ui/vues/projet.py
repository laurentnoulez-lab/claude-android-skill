"""Vue « Projet » : commune, récurrence, surfaces incidentes."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import rainfall
from ...core.model import Projet, SurfaceIncidente, TYPES_SURFACES
from .. import theme
from .base import Vue


class VueProjet(Vue):
    titre = "Projet"
    icone = ft.Icons.FOLDER_OPEN
    sous_titre = "Commune, récurrence et surfaces"

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
        def maj_aire(v: float) -> None:
            surface.aire_m2 = max(v, 0.0)
            self.etat.invalider()
            self._maj_totaux()

        def maj_coef(v: float) -> None:
            surface.coefficient = max(min(v, 1.5), 0.0)
            self.etat.invalider()
            self._maj_totaux()

        def supprimer(_=None) -> None:
            self.etat.projet.surfaces.pop(index)
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
            libelle = ft.Text(surface.libelle, size=13, weight=ft.FontWeight.W_500)

        return ft.Container(
            content=ft.ResponsiveRow(
                [
                    ft.Container(libelle, col={"xs": 12, "md": 5},
                                 alignment=ft.alignment.center_left,
                                 padding=ft.padding.only(bottom=2)),
                    ft.Container(
                        theme.champ_nombre("Coefficient", surface.coefficient, maj_coef, "—",
                                           on_valide=self._maj_totaux, compact=True),
                        col={"xs": 5, "md": 2},
                    ),
                    ft.Container(
                        theme.champ_nombre("Surface", surface.aire_m2, maj_aire, "m²",
                                           on_valide=self._maj_totaux, compact=True),
                        col={"xs": 7, "md": 3},
                    ),
                    ft.Container(
                        ft.Row(
                            [
                                ft.Text(f"{surface.aire_ponderee_m2:.1f} m² actifs", size=12,
                                        color=theme.BLEU, weight=ft.FontWeight.W_600,
                                        no_wrap=True),
                                ft.IconButton(ft.Icons.DELETE_OUTLINE, icon_size=18, tooltip="Supprimer",
                                              on_click=supprimer) if personnalisee else ft.Container(),
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
            f"Surface totale : {p.aire_totale_m2:.0f} m²    ·    "
            f"Surface active pondérée : {p.aire_ponderee_m2:.1f} m²    ·    "
            f"Coefficient moyen : {p.coefficient_moyen:.3f}"
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

        sources = [ft.dropdown.Option(rainfall.SOURCE_MONTANA, "Montana (formule continue)")]
        if commune and commune.a_qdf:
            sources.append(ft.dropdown.Option(rainfall.SOURCE_QDF, "QDF (valeurs tabulées)"))
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
            f"Surface totale : {p.aire_totale_m2:.0f} m²    ·    "
            f"Surface active pondérée : {p.aire_ponderee_m2:.1f} m²    ·    "
            f"Coefficient moyen : {p.coefficient_moyen:.3f}",
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
