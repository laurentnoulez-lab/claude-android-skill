"""Composants d'interface partagés par plusieurs onglets."""

from __future__ import annotations

import flet as ft

from ...core import hydro
from .. import theme


def panneau_amont(vue) -> ft.Control:
    """Bassin d'orage situé en amont, qui se déverse dans l'ouvrage étudié.

    Le même panneau sert au Dimensionnement et à la simulation : c'est le même
    ``projet.amont`` des deux côtés, si bien qu'encoder l'amont d'un onglet le
    montre aussitôt dans l'autre. Un utilisateur qui dimensionne n'a plus à
    deviner que cet ouvrage se déclare ailleurs.
    """
    p = vue.etat.projet
    amont = p.amont
    details = ft.Column(spacing=12, visible=amont.actif)

    def maj(champ: str):
        def _f(v: float) -> None:
            setattr(amont, champ, v)
            # La surface du bassin versant amont entre dans la surface
            # raccordée dès que la case est cochée : l'ajutage suit.
            if champ == "surface_bv_m2":
                p.recalculer_ajutage()
            vue.etat.invalider()
        return _f

    def basculer_amont(e: ft.ControlEvent) -> None:
        amont.actif = bool(e.control.value)
        vue.etat.invalider()
        vue.rafraichir()

    def basculer_surface(e: ft.ControlEvent) -> None:
        # Le débit spécifique encodé s'applique à la surface raccordée : en
        # y ajoutant le bassin versant amont, l'ajutage augmente d'autant.
        p.compter_surface_amont(bool(e.control.value))
        vue.etat.invalider()
        vue.rafraichir()

    def proposer_volume(_=None) -> None:
        amont.volume_temporisation_m3 = hydro.volume_amont_minimal_m3(p)
        vue.etat.invalider()
        vue.rafraichir()

    # Le libellé d'un ft.Switch ne se replie pas : sur téléphone il était coupé.
    interrupteur = ft.Row(
        [
            ft.Switch(value=amont.actif, on_change=basculer_amont),
            ft.Text("Un bassin d'orage se déverse dans cet ouvrage", size=13,
                    expand=True, no_wrap=False),
        ],
        spacing=8,
        vertical_alignment=ft.CrossAxisAlignment.CENTER,
    )
    if not amont.actif:
        return ft.Column([interrupteur], spacing=12)

    minimal = hydro.volume_amont_minimal_m3(p)
    res_amont = hydro.dimensionner_amont(p)
    champs = ft.ResponsiveRow(
        [
            theme.champ_nombre("Surface du bassin versant amont", amont.surface_bv_m2,
                               maj("surface_bv_m2"), "m²", "surface qui ruisselle vers l'amont",
                               on_valide=vue.maj_resultats,
                               col={"xs": 12, "sm": 6, "md": 4}),
            theme.champ_nombre("Coefficient de ruissellement moyen", amont.coef_ruissellement,
                               maj("coef_ruissellement"), "—", "pondéré sur ce bassin versant",
                               on_valide=vue.maj_resultats,
                               col={"xs": 12, "sm": 6, "md": 4}),
            theme.champ_nombre("Débit d'ajutage amont", amont.debit_ajutage_ls,
                               maj("debit_ajutage_ls"), "l/s", "restitué vers cet ouvrage",
                               on_valide=vue.maj_resultats,
                               col={"xs": 12, "sm": 6, "md": 4}),
            theme.champ_nombre("Surface de dispersion amont", amont.surface_dispersion_m2,
                               maj("surface_dispersion_m2"), "m²", "fond infiltrant du BO amont",
                               on_valide=vue.maj_resultats,
                               col={"xs": 12, "sm": 6, "md": 4}),
            *theme.champs_convertis(
                "Vitesse d'infiltration amont", "m/s", amont.k_infiltration_ms,
                "soit", "mm/h", 3.6e6, maj("k_infiltration_ms"),
                on_valide=vue.maj_resultats,
                aide_a="essai in situ · 1e-5 ou 0,00001",
                aide_b="équivalent, modifiable aussi",
                col_a={"xs": 12, "sm": 6, "md": 4}, col_b={"xs": 12, "sm": 6, "md": 4}),
            theme.champ_nombre("Volume de temporisation amont", amont.volume_temporisation_m3,
                               maj("volume_temporisation_m3"), "m³",
                               f"minimum sans débordement : {theme.nombre(minimal, 1)} m³",
                               on_valide=vue.maj_resultats,
                               col={"xs": 12, "sm": 6, "md": 4}),
        ],
        spacing=12,
        run_spacing=12,
    )

    manque = amont.volume_temporisation_m3 + 1e-6 < minimal
    avis = theme.message(
        f"Le bassin amont déborderait : {theme.nombre(amont.volume_temporisation_m3, 1)} m³ "
        f"encodés pour {theme.nombre(minimal, 1)} m³ nécessaires. Son trop-plein arriverait "
        "d'un coup dans l'ouvrage aval.", "alerte") if manque else theme.message(
        f"Bassin amont suffisant : il restitue {theme.nombre(res_amont.debit_sortant_ls, 3)} l/s "
        f"(ajutage {theme.nombre(amont.debit_ajutage_ls, 3)} l/s + infiltration "
        f"{theme.nombre(res_amont.debit_infiltration_ls, 3)} l/s), vidange en "
        f"{res_amont.temps_vidange_hm}.", "info")

    return ft.Column(
        [
            interrupteur,
            champs,
            ft.Row(
                [
                    theme.bouton_secondaire(
                        f"Proposer le volume minimal ({theme.nombre(minimal, 1)} m³)",
                        ft.Icons.AUTO_FIX_HIGH, proposer_volume),
                ],
                wrap=True, spacing=10,
            ),
            ft.Row(
                [
                    ft.Checkbox(value=amont.inclure_bv_dans_ajutage,
                                on_change=basculer_surface),
                    ft.Text("Compter la surface du bassin versant amont dans le débit "
                            "d'ajutage de cet ouvrage", size=13, expand=True, no_wrap=False),
                ],
                spacing=8,
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
            ),
            ft.Text(
                f"Surface raccordée : {theme.nombre(p.aire_raccordee_m2, 0)} m² · "
                f"ajutage {theme.nombre(p.debit_ajutage_ls, 3)} l/s "
                f"({theme.nombre(p.debit_specifique_ajutage_ls_ha, 3)} l/s/ha) · "
                f"maximum admissible {theme.nombre(p.debit_fuite_admissible_ls, 3)} l/s",
                size=11.5, color=theme.GRIS),
            ft.Text(
                "En cochant, le débit spécifique encodé s'applique aussi au bassin versant "
                f"amont : l'ajutage de cet ouvrage passerait à "
                f"{theme.nombre(p.debit_specifique_ajutage_ls_ha * (p.aire_totale_m2 + amont.surface_bv_m2) / 10000.0, 3)} l/s."
                if not amont.inclure_bv_dans_ajutage else
                "Le débit spécifique encodé s'applique au bassin versant amont comme aux "
                "surfaces propres ; décocher ramènerait l'ajutage à "
                f"{theme.nombre(p.debit_specifique_ajutage_ls_ha * p.aire_totale_m2 / 10000.0, 3)} l/s.",
                size=11.5, color=theme.GRIS, italic=True),
            avis,
        ],
        spacing=12,
    )
