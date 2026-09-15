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
from ..composants import barre_ouvrage
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

#: Au-delà, la fiche GTI affiche « /!\ Valeur à vérifier » ('Infiltration seule'!E30).
K_A_VERIFIER_MS = 1e-4

#: Sentinelle du sélecteur : un K saisi à la main ne correspond à aucun sol listé.
SOL_PERSONNALISE = "__personnalise__"


def _tuile_minimum(valeur, decimales, res, projet, libelle, unite, couleur, icone,
                   autre_organe: str) -> ft.Control:
    """Tuile d'un minimum, qui dit pourquoi il vaut zéro.

    Un « 0,0 m² » sous un scénario « infiltration + orifice » se lit comme
    « pas d'infiltration nécessaire » alors qu'il signifie « l'autre organe
    vidange déjà à lui seul dans le délai ». C'est exact, et trompeur.
    """
    if valeur is None:
        return theme.tuile("—", libelle, unite, couleur, icone,
                           "sans objet pour ce scénario")
    if valeur <= 0:
        return theme.tuile("—", libelle, unite, couleur, icone,
                           theme.fr(f"{autre_organe} seul vidange en "
                                    f"{res.temps_vidange_hm}"))
    return theme.tuile(theme.fr(f"{valeur:.{decimales}f}"), libelle, unite, couleur, icone,
                       f"minimum pour vidanger en {projet.temps_vidange_max_h:.0f} h")


def _sol_de(k_ms: float) -> str:
    """Clé du sol correspondant à K, ou la sentinelle « valeur personnalisée ».

    Le sélecteur affichait un sol dont le K n'était plus celui du projet : le
    dossier aurait annoncé une nature de sol incompatible avec la perméabilité
    utilisée. Quand K ne correspond à aucun sol listé, il faut le dire.
    """
    for cle, _, valeur in SOLS:
        if abs(valeur - k_ms) < valeur * 0.01:
            return cle
    return SOL_PERSONNALISE


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
            # Redessiner, et pas seulement recalculer : le libellé « Nature du
            # sol » et l'avertissement sur K dépendent de la valeur saisie. Sans
            # cela le sélecteur restait sur le sol précédent, et le dossier
            # aurait annoncé une nature de sol incompatible avec le K utilisé.
            ancien = p.k_infiltration_ms
            p.k_infiltration_ms = max(v, 0.0)
            self.etat.invalider()
            if _sol_de(ancien) != _sol_de(p.k_infiltration_ms):
                self.rafraichir()

        def maj_seuil(v: float) -> None:
            """Volume situé sous l'axe de l'ajutage (scénario à orifice surélevé)."""
            p.bassin.volume_sous_ajutage_m3 = max(v, 0.0)
            self.etat.invalider()

        def maj_ajutage(v: float) -> None:
            """L/s encodés : le débit est figé en valeur absolue."""
            p.fixer_ajutage_absolu(v)
            self.etat.invalider()

        def maj_ajutage_specifique(v: float) -> None:
            """L/(s·ha) encodés : le débit absolu suit la surface raccordée."""
            p.fixer_ajutage_specifique(v)
            self.etat.invalider()

        def choisir_sol(e: ft.ControlEvent) -> None:
            if e.control.value == SOL_PERSONNALISE:
                return  # entrée d'état, pas un choix : K se saisit dans son champ
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
            aide_a="essai in situ · 1e-5 ou 0,00001",
            aide_b="équivalent, modifiable aussi", domaine="k_infiltration_ms",
            col_a={"xs": 12, "sm": 6, "md": 3}, col_b={"xs": 12, "sm": 6, "md": 3},
        )

        # L'ajutage s'encode en l/s ou en l/s/ha : la case laissée vide se remplit.
        hectares = p.aire_raccordee_m2 / 10000.0
        champs_ajutage = theme.champs_convertis(
            "Débit d'ajutage", "l/s", p.debit_ajutage_ls,
            "soit", "l/s/ha", (1.0 / hectares) if hectares > 0 else None,
            maj_ajutage, on_valide=self.maj_resultats,
            appliquer_b=maj_ajutage_specifique,
            aide_a=("calculé sur la surface raccordée" if p.ajutage_suit_la_surface
                    else "orifice calibré · valeur imposée"),
            aide_b=f"rapporté aux {p.aire_raccordee_m2:.0f} m² raccordés"
                   f" · maximum GTI : 5 l/s/ha",
            indisponible_b="encodez d'abord les surfaces incidentes",
            decimales_a=3, decimales_b=2, domaine="debit_ajutage_ls",
            col_a={"xs": 12, "sm": 6, "md": 3}, col_b={"xs": 12, "sm": 6, "md": 3},
        )

        # L'alerte sur K figure déjà dans les résultats, mais un dossier se
        # remplit champ par champ : elle doit se lire là où la valeur se tape.
        avis: List[ft.Control] = []
        if p.k_infiltration_ms > K_A_VERIFIER_MS:
            avis.append(theme.message(
                f"K = {p.k_infiltration_ms:.0e} m/s dépasse "
                f"{K_A_VERIFIER_MS:.0e} m/s : valeur à vérifier, à justifier par un essai in "
                "situ (le GTI signale ce seuil).", "alerte"))
        elif p.k_infiltration_ms <= 0 and p.surface_infiltration_m2 > 0:
            avis.append(theme.message(
                "K nul : la surface d'infiltration encodée n'infiltre rien. Encodez la "
                "perméabilité du fond, ou ramenez la surface d'infiltration à zéro.", "alerte"))

        sol = _sol_de(p.k_infiltration_ms)
        options = [(c, t) for c, t, _ in SOLS]
        if sol == SOL_PERSONNALISE:
            options.append((SOL_PERSONNALISE, "Valeur personnalisée (essai in situ)"))

        return ft.Column(
            avis
            + [
                ft.ResponsiveRow(
                    [
                        theme.selecteur(
                            "Nature du sol (valeur indicative)",
                            sol, options, choisir_sol,
                            col={"xs": 12, "md": 6},
                        ),
                        champs_k[0],
                        champs_k[1],
                        theme.champ_nombre("Surface d'infiltration", p.surface_infiltration_m2,
                                           maj("surface_infiltration_m2"), "m²",
                                           "fond du dispositif", on_valide=self.maj_resultats,
                                           col={"xs": 12, "sm": 6, "md": 3},
                                           domaine="surface_infiltration_m2"),
                        champs_ajutage[0],
                        champs_ajutage[1],
                        theme.champ_nombre(
                            "Volume sous l'ajutage", p.bassin.volume_sous_ajutage_m3,
                            maj_seuil, "m³",
                            "orifice surélevé · scénario 4 · partagé avec l'onglet Bassin réel",
                            on_valide=self.maj_resultats,
                            col={"xs": 12, "sm": 6, "md": 3},
                            domaine="volume_sous_ajutage_m3"),
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
            ("Intensité", theme.fr(f"{res.intensite_ls_ha:.1f} l/s/ha")),
            ("Débit entrant", f"{res.debit_entrant_ls:.1f} l/s"),
            ("Débit de sortie", f"{res.debit_sortant_ls:.2f} l/s"),
            ("Vidange après la pluie", res.temps_vidange_hm),
        ]
        if cle == SCENARIO_SEUIL:
            # C'est la part au-dessus de l'axe qu'il reste à creuser : le volume
            # mort sous l'ajutage est déjà une donnée d'entrée.
            details.insert(1, ("dont au-dessus de l'ajutage",
                               theme.nombre(res.volume_au_dessus_ajutage_m3, 1, "m³")))
            details.insert(2, ("dont sous l'ajutage",
                               theme.nombre(res.volume_sous_ajutage_m3, 1, "m³")))
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
                    # Sans seuil encodé, ce scénario se confond avec le précédent :
                    # mieux vaut le dire que de laisser croire à deux résultats.
                    ft.Text("Encodez un volume sous l'ajutage : sans lui, ce scénario "
                            "revient au précédent.", size=11, color=theme.ORANGE, max_lines=3)
                    if cle == SCENARIO_SEUIL and res.volume_sous_ajutage_m3 <= 0
                    else ft.Container(height=0),
                    ft.Row(
                        [
                            ft.Text(theme.fr(res.volume_affiche), size=28, weight=ft.FontWeight.W_800,
                                    color=couleur),
                            ft.Text("m³ de temporisation", size=11, color=theme.GRIS, expand=True),
                        ],
                        spacing=8,
                        vertical_alignment=ft.CrossAxisAlignment.END,
                    ),
                    ft.Column(
                        [
                            ft.Row([ft.Text(k, size=11, color=theme.GRIS, expand=True),
                                    ft.Text(theme.fr(v), size=11, weight=ft.FontWeight.W_600, no_wrap=True)])
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
                ft.Container(theme.tuile(
                    theme.nombre(res.volume_au_dessus_ajutage_m3, 1),
                    "Volume au-dessus de l'ajutage", "m³", theme.BLEU_FONCE, ft.Icons.STAIRS,
                    f"volume mort sous l'axe : {theme.nombre(res.volume_sous_ajutage_m3, 1)} m³"),
                    col={"xs": 12, "sm": 6, "md": 3})
                if self.etat.scenario_principal == SCENARIO_SEUIL else
                ft.Container(theme.tuile(res.duree_critique_hm if res.dimensionnable else "—",
                                         "Durée de pluie critique", "",
                                         theme.ARDOISE, ft.Icons.TIMER),
                             col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(_tuile_minimum(
                    res.surface_infiltration_min_m2, 1, res, p,
                    "Surface d'infiltration minimale", "m²", theme.VERT, ft.Icons.GRASS,
                    "l'ajutage"),
                    col={"xs": 12, "sm": 6, "md": 3}),
                ft.Container(_tuile_minimum(
                    res.debit_ajutage_min_ls, 3, res, p,
                    "Débit d'ajutage minimal", "l/s", theme.ORANGE, ft.Icons.TUNE,
                    "l'infiltration"),
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
        amonts = self.etat.systeme.amonts_directs(self.etat.ouvrage.id)
        if res.amont_pris_en_compte and amonts:
            # Sans le dire, l'utilisateur ne peut pas savoir que ces volumes
            # comprennent l'apport d'ouvrages déclarés dans l'onglet Réseau.
            noms = ", ".join(f"« {o.nom} »" for o in amonts)
            restitue = sum(o.debit_sortant_ls() for o in amonts)
            alertes.insert(0, theme.message(
                theme.fr(
                    f"Ces volumes comprennent l'apport des bassins d'orage amont ({noms}) : "
                    f"{self.etat.systeme.aire_ponderee_amont_m2(self.etat.ouvrage.id):.0f} m² "
                    f"actifs en amont, restituant jusqu'à {restitue:.3f} l/s au fil de l'eau. "
                    f"Cet apport varie dans le temps et se poursuit après l'averse : le volume "
                    f"est obtenu par intégration exacte, et non par la formule fermée."), "info"))

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
            self.bloc_derive(lambda: barre_ouvrage(self)),
            theme.section(
                "Sol et exutoire de cet ouvrage",
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
            reperes=[charts.Repere(res.volume_m3, theme.nombre(res.volume_m3, 1, "m³ retenus"), charts.ROUGE)],
        )
        if res.duree_critique_min:
            g.reperes.append(charts.Repere(res.duree_critique_min,
                                           f"Durée critique {res.duree_critique_hm}",
                                           charts.ORANGE, vertical=True))
        return g
