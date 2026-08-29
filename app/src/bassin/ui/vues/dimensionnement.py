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
    SCENARIO_TEMPORISATION: "Bassin étanche : seul l'orifice calibré évacue l'eau.",
    SCENARIO_DISPERSION: "Aucun exutoire : toute l'eau s'infiltre par le fond.",
    SCENARIO_MIXTE: "Infiltration par le fond + orifice calibré au fond de l'ouvrage.",
    SCENARIO_SEUIL: "Orifice surélevé : sous le seuil, infiltration seule ; au-dessus, "
                    "l'ajutage s'y ajoute.",
}
ICONES = {
    SCENARIO_TEMPORISATION: ft.Icons.HOURGLASS_BOTTOM,
    SCENARIO_DISPERSION: ft.Icons.GRASS,
    SCENARIO_MIXTE: ft.Icons.CALL_SPLIT,
    SCENARIO_SEUIL: ft.Icons.STAIRS,
}

#: Perméabilités indicatives (m/s) par nature de sol.
SOLS = (
    ("1e-3", "Sable grossier / gravier — 1 × 10⁻³ m/s", 1e-3),
    ("3e-4", "Sable moyen — 3 × 10⁻⁴ m/s", 3e-4),
    ("1e-4", "Sable fin — 1 × 10⁻⁴ m/s", 1e-4),
    ("1e-5", "Sable limoneux — 1 × 10⁻⁵ m/s", 1e-5),
    ("1e-6", "Limon — 1 × 10⁻⁶ m/s", 1e-6),
    ("1e-7", "Argile limoneuse — 1 × 10⁻⁷ m/s", 1e-7),
)

MM_H = 3.6e6  # 1 m/s = 3 600 000 mm/h


class VueDimensionnement(Vue):
    titre = "Dimensionnement"
    icone = ft.Icons.CALCULATE
    sous_titre = "Méthode rationnelle · 4 scénarios"

    # ------------------------------------------------------------ formulaire
    def _formulaire(self) -> ft.Control:
        p = self.etat.projet

        def maj(champ: str):
            def _f(v: float) -> None:
                setattr(p, champ, v)
                self.etat.invalider()
            return _f

        def maj_k(v: float) -> None:
            p.k_infiltration_ms = max(v, 0.0)
            self.etat.invalider()

        def maj_ajutage(v: float) -> None:
            p.debit_ajutage_ls = max(v, 0.0)
            p.bassin.debit_ajutage_ls = p.debit_ajutage_ls
            self.etat.invalider()

        def choisir_sol(e: ft.ControlEvent) -> None:
            for cle, _, valeur in SOLS:
                if cle == e.control.value:
                    p.k_infiltration_ms = valeur
                    self.etat.invalider()
                    self.rafraichir()
                    return

        # K est encodé en m/s (grandeur de référence) ; l'équivalent en mm/h se
        # complète tout seul, et inversement.
        champs_k = theme.champs_convertis(
            "Vitesse d'infiltration K", "m/s", p.k_infiltration_ms,
            "soit", "mm/h", MM_H, maj_k, on_valide=self.maj_resultats,
            aide_a="essai in situ · 1e-5, 0,00001 ou 0.00001",
            aide_b="équivalent, modifiable aussi",
            col_a={"xs": 12, "sm": 6, "md": 3}, col_b={"xs": 12, "sm": 6, "md": 3},
        )

        # L'ajutage s'encode en l/s ou en l/s/ha : la case laissée vide se remplit.
        hectares = p.aire_totale_m2 / 10000.0
        champs_ajutage = theme.champs_convertis(
            "Débit d'ajutage", "l/s", p.debit_ajutage_ls,
            "soit", "l/s/ha", (1.0 / hectares) if hectares > 0 else None,
            maj_ajutage, on_valide=self.maj_resultats,
            aide_a="orifice calibré",
            aide_b=f"rapporté aux {p.aire_totale_m2:.0f} m² raccordés"
                   f" · maximum GTI : 5 l/s/ha",
            indisponible_b="encodez d'abord les surfaces incidentes",
            decimales_a=3, decimales_b=2,
            col_a={"xs": 12, "sm": 6, "md": 3}, col_b={"xs": 12, "sm": 6, "md": 3},
        )

        return ft.Column(
            [
                ft.ResponsiveRow(
                    [
                        theme.selecteur(
                            "Nature du sol (valeur indicative)",
                            next((c for c, _, v in SOLS
                                  if abs(v - p.k_infiltration_ms) < v * 0.01), None),
                            [(c, t) for c, t, _ in SOLS], choisir_sol,
                            col={"xs": 12, "md": 6},
                        ),
                        champs_k[0],
                        champs_k[1],
                        theme.champ_nombre("Coefficient de sécurité sur K",
                                           p.coef_securite_infiltration,
                                           maj("coef_securite_infiltration"), "—", "GTI : 2",
                                           on_valide=self.maj_resultats,
                                           col={"xs": 12, "sm": 6, "md": 3}),
                        theme.champ_nombre("Surface d'infiltration", p.surface_infiltration_m2,
                                           maj("surface_infiltration_m2"), "m²",
                                           "fond du dispositif", on_valide=self.maj_resultats,
                                           col={"xs": 12, "sm": 6, "md": 3}),
                        champs_ajutage[0],
                        champs_ajutage[1],
                        theme.champ_nombre("Temps de vidange maximum", p.temps_vidange_max_h,
                                           maj("temps_vidange_max_h"), "h",
                                           "après la pluie · GTI : 48 h",
                                           on_valide=self.maj_resultats,
                                           col={"xs": 12, "sm": 6, "md": 3}),
                    ],
                    spacing=12,
                    run_spacing=12,
                ),
            ],
            spacing=12,
        )

    # ------------------------------------------------------------- résultats
    def _carte_scenario(self, cle: str) -> ft.Control:
        res = self.etat.resultats[cle]
        actif = cle == self.etat.scenario_principal
        couleur = theme.BLEU if res.conforme else theme.ROUGE

        def choisir(_=None) -> None:
            self.etat.scenario_principal = cle
            self.maj_resultats()

        details = [
            ("Durée critique", res.duree_critique_hm),
            ("Pluie", f"{res.hauteur_pluie_mm:.1f} mm"),
            ("Intensité", f"{res.intensite_ls_ha:.0f} l/s/ha"),
            ("Débit entrant", f"{res.debit_entrant_ls:.1f} l/s"),
            ("Débit de sortie", f"{res.debit_sortant_ls:.2f} l/s"),
            ("Vidange après la pluie", res.temps_vidange_hm),
        ]
        return ft.Container(
            content=ft.Column(
                [
                    ft.Row(
                        [
                            ft.Icon(ICONES[cle], color=couleur, size=20),
                            ft.Text(LIBELLES_SCENARIOS[cle], size=13, weight=ft.FontWeight.W_700,
                                    expand=True, max_lines=2, overflow=ft.TextOverflow.ELLIPSIS),
                            ft.Icon(ft.Icons.CHECK_CIRCLE if actif else ft.Icons.RADIO_BUTTON_UNCHECKED,
                                    color=theme.BLEU if actif else theme.GRIS, size=18),
                        ],
                        spacing=8,
                    ),
                    ft.Text(DESCRIPTIONS[cle], size=11, color=theme.GRIS, max_lines=3),
                    ft.Row(
                        [
                            ft.Text(res.volume_affiche, size=28, weight=ft.FontWeight.W_800,
                                    color=couleur),
                            ft.Text("m³ de temporisation", size=11, color=theme.GRIS, expand=True),
                        ],
                        spacing=8,
                        vertical_alignment=ft.CrossAxisAlignment.END,
                    ),
                    ft.Column(
                        [
                            ft.Row([ft.Text(k, size=11, color=theme.GRIS, expand=True),
                                    ft.Text(v, size=11, weight=ft.FontWeight.W_600, no_wrap=True)])
                            for k, v in details
                        ],
                        spacing=2,
                    ),
                    theme.etiquette(
                        "Conforme" if res.conforme else "Non conforme",
                        theme.VERT if res.conforme else theme.ROUGE,
                        theme.VERT_CLAIR if res.conforme else theme.ROUGE_CLAIR,
                        ft.Icons.CHECK if res.conforme else ft.Icons.PRIORITY_HIGH,
                    ),
                ],
                spacing=8,
            ),
            padding=14,
            border_radius=theme.RAYON,
            bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
            border=ft.border.all(2 if actif else 1,
                                 theme.BLEU if actif else ft.Colors.OUTLINE_VARIANT),
            on_click=choisir,
            ink=True,
            col={"xs": 12, "sm": 6, "xl": 3},
        )

    def resultats(self) -> List[ft.Control]:
        p = self.etat.projet
        res = self.etat.resultat
        q_inf = res.debit_infiltration_ls

        info_debits = ft.Row(
            [
                theme.etiquette(f"Q infiltration = {q_inf:.3f} l/s", theme.VERT, theme.VERT_CLAIR,
                                ft.Icons.WATER_DROP),
                theme.etiquette(f"Q ajutage = {p.debit_ajutage_ls:.3f} l/s", theme.BLEU,
                                theme.BLEU_CLAIR, ft.Icons.CIRCLE_OUTLINED),
                theme.etiquette(f"Débit de fuite admissible = {p.debit_fuite_admissible_ls:.3f} l/s",
                                theme.GRIS, theme.GRIS_CLAIR, ft.Icons.RULE),
                theme.etiquette(f"K = {p.k_infiltration_ms:.2e} m/s", theme.ARDOISE,
                                theme.GRIS_CLAIR, ft.Icons.TERRAIN),
            ],
            wrap=True,
            spacing=8,
            run_spacing=8,
        )

        tuiles = ft.ResponsiveRow(
            [
                ft.Container(theme.tuile(res.volume_affiche, "Volume à mettre en œuvre", "m³",
                                         theme.BLEU, ft.Icons.WATER),
                             col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(res.duree_critique_hm if res.dimensionnable else "—",
                                         "Durée de pluie critique", "",
                                         theme.ARDOISE, ft.Icons.TIMER),
                             col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(
                    "—" if res.surface_infiltration_min_m2 is None else f"{res.surface_infiltration_min_m2:.1f}",
                    "Surface d'infiltration minimale", "m²", theme.VERT, ft.Icons.GRASS,
                    f"pour vidanger en {p.temps_vidange_max_h:.0f} h"),
                    col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(theme.tuile(
                    "—" if res.debit_ajutage_min_ls is None else f"{res.debit_ajutage_min_ls:.3f}",
                    "Débit d'ajutage minimal", "l/s", theme.ORANGE, ft.Icons.TUNE,
                    f"pour vidanger en {p.temps_vidange_max_h:.0f} h"),
                    col={"xs": 12, "sm": 6, "md": 3}),
            ],
            spacing=12,
            run_spacing=12,
        )

        if not res.dimensionnable:
            tuiles = theme.message(
                "Aucun débit de sortie n'est encodé : sans infiltration ni ajutage, le bassin ne se "
                "vidange jamais et aucun volume ne peut être déterminé. Encodez une surface "
                "d'infiltration et/ou un débit d'ajutage ci-dessus.", "erreur")

        alertes = [theme.message(a, "alerte") for a in res.alertes]
        alertes += [theme.message(m, "info") for m in res.messages]

        return [
            info_debits,
            theme.section(
                "Scénarios étudiés",
                ft.Column(
                    [
                        ft.Text("Touchez un scénario pour le retenir comme scénario de projet.",
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
                ft.Column([tuiles] + alertes + [graphiques.construire(self._graphique_volume(), 280)],
                          spacing=14),
                ft.Icons.INSIGHTS,
                "V(t) = h(t) × S_pondérée / 1000 − Q_sortie × t × 60 / 1000",
            ),
        ]

    def construire(self) -> List[ft.Control]:
        self.zone.controls = self.resultats()
        return [
            theme.section(
                "Sol, exutoire et contraintes",
                ft.Column(
                    [
                        self._formulaire(),
                        ft.Row([theme.bouton_secondaire("Recalculer", ft.Icons.REFRESH,
                                                        lambda _: self.maj_resultats())]),
                    ],
                    spacing=14,
                ),
                ft.Icons.TERRAIN,
                "Q_infiltration = 1000 × S × K / coefficient de sécurité",
            ),
            self.zone,
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
