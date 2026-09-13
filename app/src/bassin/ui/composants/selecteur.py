"""Barre de choix de l'ouvrage, partagée par les onglets de détail.

Les onglets Dimensionnement, Bassin, Table QDF, Ajutage et Rapport décrivent
**un** bassin d'orage. Quand le projet en compte plusieurs, il faut pouvoir dire
lequel sans quitter l'onglet — et voir d'un coup d'œil d'où lui vient son eau et
où elle repart.
"""

from __future__ import annotations

from typing import List

import flet as ft

from .. import theme


def _sous_titre(etat) -> str:
    """Ce que reçoit et ce que rejette l'ouvrage courant, en une ligne."""
    systeme = etat.systeme
    ouvrage = etat.ouvrage
    versants = systeme.versants_de(ouvrage.id)
    amonts = systeme.amonts_directs(ouvrage.id)
    morceaux: List[str] = []
    if versants:
        morceaux.append(", ".join(bv.nom for bv in versants))
    if amonts:
        morceaux.append(" + ".join(o.nom for o in amonts))
    entrees = " · ".join(morceaux) if morceaux else "aucun apport raccordé"
    aval = systeme.aval(ouvrage.id)
    sortie = aval.nom if aval is not None else "exutoire (milieu naturel)"
    return theme.fr(f"Reçoit : {entrees}   →   Rejette vers : {sortie}")


def barre_ouvrage(vue) -> ft.Control:
    """Sélecteur du bassin d'orage étudié, masqué quand il n'y en a qu'un."""
    etat = vue.etat
    if len(etat.ouvrages) <= 1:
        return ft.Container(height=0)

    def choisir(e: ft.ControlEvent) -> None:
        etat.choisir_ouvrage(e.control.value)
        vue.rafraichir()

    def precedent(_=None) -> None:
        _decaler(-1)

    def suivant(_=None) -> None:
        _decaler(1)

    def _decaler(pas: int) -> None:
        identifiants = [o.id for o in etat.ouvrages]
        index = (identifiants.index(etat.ouvrage.id) + pas) % len(identifiants)
        etat.choisir_ouvrage(identifiants[index])
        vue.rafraichir()

    fiche = None
    try:
        fiche = etat.fiche(etat.ouvrage.id)
    except Exception:
        fiche = None
    etiquettes: List[ft.Control] = []
    if fiche is not None:
        couleur, fond = theme.COULEURS_STATUT.get(fiche.statut, (theme.GRIS, theme.GRIS_CLAIR))
        etiquettes.append(theme.etiquette(
            f"{fiche.volume_encode_m3:.1f} m³ encodés / {fiche.volume_minimal_m3:.1f} m³ requis",
            couleur, fond, ft.Icons.WATER))

    return ft.Container(
        content=ft.Column(
            [
                ft.Row(
                    [
                        ft.Icon(ft.Icons.WATER_DAMAGE, color=theme.BLEU, size=20),
                        ft.Container(
                            ft.Dropdown(
                                label="Bassin d'orage étudié",
                                value=etat.ouvrage.id,
                                options=[ft.dropdown.Option(o.id, o.nom) for o in etat.ouvrages],
                                on_change=choisir,
                                dense=True,
                                border_radius=10,
                            ),
                            expand=True,
                        ),
                        ft.IconButton(ft.Icons.CHEVRON_LEFT, tooltip="Ouvrage précédent",
                                      on_click=precedent),
                        ft.IconButton(ft.Icons.CHEVRON_RIGHT, tooltip="Ouvrage suivant",
                                      on_click=suivant),
                    ],
                    spacing=8,
                    vertical_alignment=ft.CrossAxisAlignment.CENTER,
                ),
                ft.Row([ft.Text(_sous_titre(etat), size=11.5, color=theme.GRIS, expand=True,
                                no_wrap=False)] + etiquettes,
                       spacing=10, wrap=True),
            ],
            spacing=6,
        ),
        padding=ft.padding.symmetric(10, 14),
        border_radius=theme.RAYON,
        bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
        border=ft.border.all(1, ft.Colors.OUTLINE_VARIANT),
    )
