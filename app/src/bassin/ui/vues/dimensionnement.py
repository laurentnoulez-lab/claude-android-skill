"""Vue « Dimensionnement » : paramètres du sol, exutoire et comparaison des scénarios."""

from __future__ import annotations

from typing import List

import flet as ft

from ...core import hydro
from ...core.model import (
    LIBELLES_SCENARIOS,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_SEUIL,
    SCENARIO_TEMPORISATION,
)
from ...reports.dossier import ORDRE_SCENARIOS
from .. import graphiques, theme
from .base import Vue

DESCRIPTIONS = {
    SCENARIO_TEMPORISATION: "Le bassin est étanche : seul l'orifice calibré évacue l'eau vers l'exutoire.",
    SCENARIO_DISPERSION: "Aucun exutoire : toute l'eau s'infiltre par le fond du bassin.",
    SCENARIO_MIXTE: "Infiltration par le fond + orifice calibré placé au fond de l'ouvrage.",
    SCENARIO_SEUIL: "L'orifice est surélevé : sous le seuil l'eau ne part que par infiltration, "
                    "au-dessus l'ajutage s'y ajoute.",
}
ICONES = {
    SCENARIO_TEMPORISATION: ft.Icons.HOURGLASS_BOTTOM,
    SCENARIO_DISPERSION: ft.Icons.GRASS,
    SCENARIO_MIXTE: ft.Icons.CALL_SPLIT,
    SCENARIO_SEUIL: ft.Icons.STAIRS,
}


class VueDimensionnement(Vue):
    titre = "Dimensionnement"
    icone = ft.Icons.CALCULATE
    sous_titre = "Méthode rationnelle · 4 scénarios"

    def _carte_scenario(self, cle: str) -> ft.Control:
        res = self.etat.resultats[cle]
        actif = cle == self.etat.scenario_principal
        couleur = theme.BLEU if res.conforme else theme.ROUGE

        def choisir(_=None) -> None:
            self.etat.scenario_principal = cle
            self.rafraichir()

        details = [
            ("Durée critique", res.duree_critique_hm),
            ("Pluie", f"{res.hauteur_pluie_mm:.1f} mm"),
            ("Intensité", f"{res.intensite_ls_ha:.0f} l/s/ha"),
            ("Débit entrant", f"{res.debit_entrant_ls:.1f} l/s"),
            ("Débit de sortie", f"{res.debit_sortant_ls:.2f} l/s"),
            ("Temps de vidange", res.temps_vidange_hm if res.temps_vidange_h != float("inf") else "—"),
        ]
        return ft.Container(
            content=ft.Column(
                [
                    ft.Row(
                        [
                            ft.Icon(ICONES[cle], color=couleur, size=20),
                            ft.Text(LIBELLES_SCENARIOS[cle], size=13.5, weight=ft.FontWeight.W_700,
                                    expand=True),
                            ft.Icon(ft.Icons.CHECK_CIRCLE if actif else ft.Icons.RADIO_BUTTON_UNCHECKED,
                                    color=theme.BLEU if actif else theme.GRIS, size=18),
                        ],
                        spacing=8,
                    ),
                    ft.Text(DESCRIPTIONS[cle], size=11.5, color=theme.GRIS),
                    ft.Row(
                        [
                            ft.Text(f"{res.volume_m3:.1f}", size=30, weight=ft.FontWeight.W_800, color=couleur),
                            ft.Text("m³ de temporisation", size=12, color=theme.GRIS),
                        ],
                        spacing=8,
                        vertical_alignment=ft.CrossAxisAlignment.END,
                    ),
                    ft.Column(
                        [
                            ft.Row(
                                [ft.Text(k, size=11.5, color=theme.GRIS, expand=True),
                                 ft.Text(v, size=11.5, weight=ft.FontWeight.W_600)],
                            )
                            for k, v in details
                        ],
                        spacing=2,
                    ),
                    ft.Container(
                        content=theme.etiquette(
                            "Conforme" if res.conforme else "Non conforme",
                            theme.VERT if res.conforme else theme.ROUGE,
                            theme.VERT_CLAIR if res.conforme else theme.ROUGE_CLAIR,
                            ft.Icons.CHECK if res.conforme else ft.Icons.PRIORITY_HIGH,
                        ),
                        margin=ft.margin.only(top=4),
                    ),
                ],
                spacing=8,
            ),
            padding=16,
            border_radius=theme.RAYON,
            bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
            border=ft.border.all(2 if actif else 1,
                                 theme.BLEU if actif else ft.Colors.OUTLINE_VARIANT),
            on_click=choisir,
            ink=True,
            col={"xs": 12, "sm": 6, "xl": 3},
        )

    def construire(self) -> List[ft.Control]:
        p = self.etat.projet
        res = self.etat.resultat

        def maj(champ: str):
            def _f(v: float) -> None:
                self.etat.definir(champ, v)
                self.rafraichir()
            return _f

        parametres = ft.ResponsiveRow(
            [
                theme.champ_nombre("Vitesse d'infiltration K", p.k_infiltration_ms, maj("k_infiltration_ms"),
                                   "m/s", "Essai in situ ; 1e-5 m/s = sol sableux limoneux",
                                   scientifique=True, col={"xs": 12, "md": 4}),
                theme.champ_nombre("Coefficient de sécurité sur K", p.coef_securite_infiltration,
                                   maj("coef_securite_infiltration"), "-", "GTI : 2", decimales=1,
                                   col={"xs": 6, "md": 2}),
                theme.champ_nombre("Surface d'infiltration", p.surface_infiltration_m2,
                                   maj("surface_infiltration_m2"), "m²", "Fond du dispositif",
                                   decimales=1, col={"xs": 6, "md": 3}),
                theme.champ_nombre("Débit d'ajutage", p.debit_ajutage_ls, maj("debit_ajutage_ls"),
                                   "l/s", "Orifice calibré", decimales=3, col={"xs": 6, "md": 3}),
                theme.champ_nombre("Temps de vidange maximum", p.temps_vidange_max_h,
                                   maj("temps_vidange_max_h"), "h", "GTI : 48 h", decimales=1,
                                   col={"xs": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

        q_inf = res.debit_infiltration_ls
        info_debits = ft.Row(
            [
                theme.etiquette(f"Q infiltration = {q_inf:.3f} l/s", theme.VERT, theme.VERT_CLAIR,
                                ft.Icons.WATER_DROP),
                theme.etiquette(f"Q ajutage = {p.debit_ajutage_ls:.3f} l/s", theme.BLEU, theme.BLEU_CLAIR,
                                ft.Icons.CIRCLE_OUTLINED),
                theme.etiquette(f"Débit de fuite admissible = {p.debit_fuite_admissible_ls:.3f} l/s",
                                theme.GRIS, theme.GRIS_CLAIR, ft.Icons.RULE),
            ],
            wrap=True,
            spacing=8,
            run_spacing=8,
        )

        tuiles = ft.ResponsiveRow(
            [
                ft.Container(theme.tuile(f"{res.volume_m3:.1f}", "Volume à mettre en œuvre", "m³",
                                         theme.BLEU, ft.Icons.WATER),
                             col={"xs": 6, "md": 3}),
                ft.Container(theme.tuile(res.duree_critique_hm, "Durée de pluie critique", "",
                                         theme.ARDOISE, ft.Icons.TIMER),
                             col={"xs": 6, "md": 3}),
                ft.Container(theme.tuile(
                    "—" if res.surface_infiltration_min_m2 is None else f"{res.surface_infiltration_min_m2:.1f}",
                    "Surface d'infiltration minimale", "m²", theme.VERT, ft.Icons.GRASS,
                    f"pour vidanger en {p.temps_vidange_max_h:.0f} h"),
                    col={"xs": 6, "md": 3}),
                ft.Container(theme.tuile(
                    "—" if res.debit_ajutage_min_ls is None else f"{res.debit_ajutage_min_ls:.3f}",
                    "Débit d'ajutage minimal", "l/s", theme.ORANGE, ft.Icons.TUNE,
                    f"pour vidanger en {p.temps_vidange_max_h:.0f} h"),
                    col={"xs": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

        alertes = [theme.message(a, "alerte") for a in res.alertes]
        alertes += [theme.message(m, "info") for m in res.messages]

        graphique = graphiques.construire(self._graphique_volume(), hauteur=300)

        return [
            theme.section("Sol, exutoire et contraintes",
                          ft.Column([parametres, info_debits], spacing=14),
                          ft.Icons.TERRAIN,
                          "Q_infiltration = 1000 × S × K / coefficient de sécurité"),
            theme.section(
                "Scénarios étudiés",
                ft.Column(
                    [
                        ft.Text("Cliquez sur un scénario pour le retenir comme scénario de projet.",
                                size=12, color=theme.GRIS),
                        ft.ResponsiveRow([self._carte_scenario(s) for s in ORDRE_SCENARIOS],
                                         spacing=12, run_spacing=12),
                    ],
                    spacing=10,
                ),
                ft.Icons.COMPARE_ARROWS,
            ),
            theme.section(
                f"Résultat retenu — {LIBELLES_SCENARIOS[self.etat.scenario_principal]}",
                ft.Column([tuiles] + alertes + [graphique], spacing=14),
                ft.Icons.INSIGHTS,
                "V(t) = h(t) × S_pondérée / 1000 − Q_sortie × t × 60 / 1000",
            ),
        ]

    def _graphique_volume(self):
        from ...reports import charts

        res = self.etat.resultat
        pts = hydro.courbe_volume(self.etat.projet, self.etat.scenario_principal)
        g = charts.Graphique(
            titre="Volume à maîtriser selon la durée de pluie",
            axe_x="Durée de pluie",
            axe_y="Volume [m³]",
            x_log=True,
            series=[charts.Serie("Volume à maîtriser", pts, charts.BLEU, aire=True)],
            reperes=[charts.Repere(res.volume_m3, f"Volume retenu {res.volume_m3:.1f} m³", charts.ROUGE)],
        )
        if res.duree_critique_min:
            g.reperes.append(charts.Repere(res.duree_critique_min,
                                           f"Durée critique {res.duree_critique_hm}",
                                           charts.ORANGE, vertical=True))
        return g
