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


def _comparaisons(etat, fiche) -> List[ft.Control]:
    """Ce qui est encodé face à ce que le dimensionnement exige.

    Les minima ne sont pas toujours calculés (sur un grand réseau ils se
    demandent) ni toujours définis — un ajutage minimal n'a pas de sens si
    l'ouvrage se vidange déjà par infiltration seule. On ne montre que ce qui
    existe.
    """
    bassin = fiche.ouvrage.etude.bassin
    res = fiche.resultat
    etiquettes: List[ft.Control] = []
    for encode, minimal, unite, decimales, icone, nom, accord in (
        (bassin.debit_ajutage_ls, res.debit_ajutage_min_ls, "l/s", 2,
         ft.Icons.TUNE, "ajutage", "encodé"),
        (bassin.surface_dispersion_m2, res.surface_infiltration_min_m2, "m²", 0,
         ft.Icons.GRASS, "infiltration", "encodée"),
    ):
        if minimal is None or minimal <= 0:
            # Un minimum nul ne veut pas dire « zéro requis » mais « l'autre
            # organe vidange déjà à lui seul » : l'afficher se lirait à
            # l'envers. Le détail est dit dans l'onglet Dimensionnement.
            continue
        suffisant = encode + 1e-9 >= minimal
        couleur = theme.VERT if suffisant else theme.ROUGE
        fond = theme.VERT_CLAIR if suffisant else theme.ROUGE_CLAIR
        etiquettes.append(theme.etiquette(
            theme.fr(f"{nom} {encode:.{decimales}f} {unite} {accord} / "
                     f"{minimal:.{decimales}f} {unite} requis"),
            couleur, fond, icone))
    return etiquettes


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
            theme.fr(f"{fiche.volume_encode_m3:.1f} m³ encodés / "
                     f"{fiche.volume_minimal_m3:.1f} m³ requis"),
            couleur, fond, ft.Icons.WATER))
        # Le volume n'est pas la seule grandeur à porter cette dualité : le
        # débit d'ajutage et la surface infiltrante l'ont aussi, et c'est ce
        # bandeau qui l'exprime le plus clairement.
        etiquettes.extend(_comparaisons(etat, fiche))

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
                # Le texte et les étiquettes tiennent chacun leur ligne : un
                # contrôle `expand` dans un `Row` qui se replie n'a pas de
                # largeur définie, et Flutter le rendait en un grand aplat gris.
                ft.Text(_sous_titre(etat), size=11.5, color=theme.GRIS, no_wrap=False),
                ft.Row(etiquettes, spacing=8, run_spacing=8, wrap=True)
                if etiquettes else ft.Container(height=0),
            ],
            spacing=6,
        ),
        padding=ft.padding.symmetric(10, 14),
        border_radius=theme.RAYON,
        bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
        border=ft.border.all(1, ft.Colors.OUTLINE_VARIANT),
    )
