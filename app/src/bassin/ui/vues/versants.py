"""Vue « Bassins versants » : plusieurs bassins versants nommés, chacun raccordé
à un bassin d'orage.

Un projet d'infrastructure draine rarement une seule parcelle : une voirie, un
lotissement et un parking peuvent aboutir à des ouvrages différents. Chaque
bassin versant porte donc son propre tableau de surfaces et son coefficient
moyen, et se raccorde à un seul bassin d'orage.
"""

from __future__ import annotations

from typing import List

import flet as ft

from ...core.model import SurfaceIncidente, TYPES_SURFACES
from ...core.reseau import BassinVersant
from .. import theme
from .base import Vue


class VueVersants(Vue):
    titre = "Bassins versants"
    icone = ft.Icons.LANDSCAPE
    sous_titre = "Surfaces et coefficients de ruissellement"

    def __init__(self, page, etat):
        super().__init__(page, etat)
        #: Bassin versant déplié ; les autres restent repliés pour rester lisibles.
        self._ouvert = None
        self._totaux = {}

    # ------------------------------------------------------------- surfaces
    def _ligne_surface(self, versant: BassinVersant, index: int,
                       surface: SurfaceIncidente) -> ft.Control:
        actifs = ft.Text(theme.nombre(surface.aire_ponderee_m2, 1, "m² actifs"), size=12,
                         color=theme.BLEU, weight=ft.FontWeight.W_600, no_wrap=True)

        def rafraichir_ligne() -> None:
            actifs.value = theme.nombre(surface.aire_ponderee_m2, 1, "m² actifs")
            try:
                actifs.update()
            except Exception:
                pass
            self._maj_totaux(versant)

        def maj_aire(v: float) -> None:
            surface.aire_m2 = max(v, 0.0)
            # Un ajutage encodé en l/(s·ha) se recalcule sur la nouvelle surface.
            self.etat.invalider()
            rafraichir_ligne()

        def maj_coef(v: float) -> None:
            surface.coefficient = max(min(v, 1.5), 0.0)
            self.etat.invalider()
            rafraichir_ligne()

        def supprimer(_=None) -> None:
            versant.surfaces.pop(index)
            self.etat.invalider()
            self.rafraichir()

        personnalisee = index >= len(TYPES_SURFACES)
        libelle: ft.Control
        if personnalisee:
            def maj_libelle(e: ft.ControlEvent) -> None:
                surface.libelle = e.control.value

            libelle = ft.TextField(value=surface.libelle, label="Surface personnalisée",
                                   dense=True, border_radius=10, text_size=13,
                                   on_change=maj_libelle)
        else:
            libelle = ft.Text(surface.libelle, size=13, weight=ft.FontWeight.W_500,
                              no_wrap=False)

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

    def _texte_totaux(self, versant: BassinVersant) -> str:
        return (f"Surface totale : {theme.nombre(versant.aire_totale_m2, 0)} m²    ·    "
                f"Surface active pondérée : {theme.nombre(versant.aire_ponderee_m2, 1)} m²"
                f"    ·    Coefficient moyen : {theme.nombre(versant.coefficient_moyen, 3)}")

    def _maj_totaux(self, versant: BassinVersant) -> None:
        controle = self._totaux.get(versant.id)
        if controle is None:
            return
        controle.value = self._texte_totaux(versant)
        try:
            controle.update()
        except Exception:
            pass

    # --------------------------------------------------------- un versant
    def _carte_versant(self, versant: BassinVersant) -> ft.Control:
        etat = self.etat
        systeme = etat.systeme
        ouvert = self._ouvert == versant.id or len(systeme.bassins_versants) == 1

        def maj_nom(e: ft.ControlEvent) -> None:
            versant.nom = e.control.value

        def maj_note(e: ft.ControlEvent) -> None:
            versant.note = e.control.value

        def maj_raccordement(e: ft.ControlEvent) -> None:
            versant.bassin_id = e.control.value
            etat.invalider()
            self.rafraichir()

        def maj_reference(v: float) -> None:
            versant.surface_reference_m2 = max(v, 0.0)
            etat.invalider()

        def basculer(_=None) -> None:
            self._ouvert = None if ouvert else versant.id
            self.rafraichir()

        def ajouter_surface(_=None) -> None:
            versant.surfaces.append(SurfaceIncidente("Autre surface (à justifier)", 0.8, 0.0))
            etat.invalider()
            self.rafraichir()

        def supprimer(_=None) -> None:
            if not etat.supprimer_versant(versant.id):
                self.notifier("Un projet garde au moins un bassin versant.", "alerte")
                return
            self.rafraichir()

        raccorde = systeme.ouvrage(versant.bassin_id)
        entete = ft.ResponsiveRow(
            [
                ft.Container(
                    ft.TextField(label="Nom du bassin versant", value=versant.nom, dense=True,
                                 border_radius=10, on_change=maj_nom),
                    col={"xs": 12, "md": 4},
                ),
                ft.Container(
                    ft.Dropdown(
                        label="Raccordé au bassin d'orage",
                        value=versant.bassin_id if raccorde else None,
                        options=[ft.dropdown.Option(o.id, o.nom) for o in systeme.ouvrages],
                        on_change=maj_raccordement,
                        dense=True,
                        border_radius=10,
                        error_text=None if raccorde else "à raccorder",
                    ),
                    col={"xs": 12, "md": 4},
                ),
                ft.Container(
                    theme.champ_nombre("Surface de référence", versant.surface_reference_m2,
                                       maj_reference, "m²", "parcelle concernée",
                                       on_valide=self.maj_resultats),
                    col={"xs": 12, "md": 4},
                ),
            ],
            spacing=12,
            run_spacing=12,
        )

        total = ft.Text(self._texte_totaux(versant), size=13, weight=ft.FontWeight.W_700,
                        color=theme.BLEU, no_wrap=False)
        self._totaux[versant.id] = total

        contenu: List[ft.Control] = [
            ft.Row(
                [
                    ft.Icon(ft.Icons.LANDSCAPE, color=theme.BLEU, size=20),
                    ft.Column(
                        [
                            ft.Text(versant.nom or "Bassin versant sans nom", size=15,
                                    weight=ft.FontWeight.W_700, no_wrap=False),
                            ft.Text(
                                theme.fr(f"{versant.aire_totale_m2:.0f} m² · "
                                         f"{versant.aire_ponderee_m2:.0f} m² actifs · vers "
                                         + (raccorde.nom if raccorde else "aucun bassin")),
                                size=11.5, color=theme.GRIS, no_wrap=False),
                        ],
                        spacing=0, expand=True, tight=True,
                    ),
                    ft.IconButton(ft.Icons.EXPAND_LESS if ouvert else ft.Icons.EXPAND_MORE,
                                  tooltip="Replier" if ouvert else "Déplier",
                                  on_click=basculer),
                    ft.IconButton(ft.Icons.DELETE_OUTLINE, tooltip="Supprimer ce bassin versant",
                                  on_click=supprimer),
                ],
                spacing=8,
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
            ),
        ]
        if ouvert:
            contenu += [
                ft.Divider(height=14),
                entete,
                ft.TextField(label="Remarque", value=versant.note, dense=True, border_radius=10,
                             on_change=maj_note),
                ft.Divider(height=14),
                ft.Column([self._ligne_surface(versant, i, s)
                           for i, s in enumerate(versant.surfaces)], spacing=4),
                ft.Row([theme.bouton_secondaire("Ajouter une surface", ft.Icons.ADD,
                                                ajouter_surface)]),
                ft.Divider(height=14),
                total,
            ]
        return ft.Container(
            content=ft.Column(contenu, spacing=8),
            padding=16,
            border_radius=theme.RAYON,
            bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
            border=ft.border.all(1, ft.Colors.OUTLINE_VARIANT),
        )

    # ------------------------------------------------------------- rendu
    def _ajouter_versant(self, _=None) -> None:
        versant = self.etat.ajouter_versant()
        self._ouvert = versant.id
        self.rafraichir()
        self.notifier(f"« {versant.nom} » ajouté.", "succes")

    def resultats(self) -> List[ft.Control]:
        systeme = self.etat.systeme
        total = ft.Row(
            [
                theme.etiquette(f"{len(systeme.bassins_versants)} bassin(s) versant(s)",
                                theme.BLEU, theme.BLEU_CLAIR, ft.Icons.LANDSCAPE),
                theme.etiquette(f"Surface totale {theme.nombre(systeme.aire_totale_m2, 0)} m²",
                                theme.ARDOISE, theme.GRIS_CLAIR, ft.Icons.CROP_LANDSCAPE),
                theme.etiquette(
                    f"Surface active {theme.nombre(systeme.aire_ponderee_m2, 1)} m²",
                    theme.VERT, theme.VERT_CLAIR, ft.Icons.GRASS),
                theme.etiquette(f"C moyen {theme.nombre(systeme.coefficient_moyen, 3)}",
                                theme.GRIS, theme.GRIS_CLAIR, ft.Icons.FUNCTIONS),
            ],
            wrap=True, spacing=8, run_spacing=8,
        )
        blocs: List[ft.Control] = [total]
        orphelins = systeme.versants_orphelins()
        if orphelins:
            blocs.append(theme.message(
                "Ces bassins versants ne sont raccordés à aucun bassin d'orage, leur "
                "ruissellement n'est donc compté nulle part : "
                + ", ".join(f"« {bv.nom} »" for bv in orphelins) + ".", "alerte"))
        return blocs

    def construire(self) -> List[ft.Control]:
        self.zone.controls = self.resultats()
        systeme = self.etat.systeme
        cartes = ft.Column([self._carte_versant(bv) for bv in systeme.bassins_versants],
                           spacing=12)
        return [
            theme.section(
                "Bassins versants du projet",
                ft.Column(
                    [
                        ft.Text("Chaque bassin versant porte ses propres surfaces et se "
                                "raccorde à un seul bassin d'orage. Les coefficients de "
                                "ruissellement sont ceux du GTI.", size=12, color=theme.GRIS),
                        self.zone,
                        ft.Row(
                            [
                                theme.bouton_principal("Ajouter un bassin versant", ft.Icons.ADD,
                                                       self._ajouter_versant),
                                theme.bouton_secondaire("Recalculer", ft.Icons.REFRESH,
                                                        lambda _: self.maj_resultats()),
                            ],
                            wrap=True, spacing=10,
                        ),
                    ],
                    spacing=14,
                ),
                ft.Icons.LANDSCAPE,
                "S_pondérée = Σ (coefficient × surface)",
            ),
            cartes,
        ]
