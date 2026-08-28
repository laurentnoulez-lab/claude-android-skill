"""Assemblage du dossier de calcul commun aux trois formats de rapport."""

from __future__ import annotations

import datetime as _dt
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from ..core import hydro, orifice, rainfall, simulation
from ..core.model import (
    Bassin,
    LIBELLES_SCENARIOS,
    Projet,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_SEUIL,
    SCENARIO_TEMPORISATION,
    debit_infiltration_ls,
)
from . import charts

ORDRE_SCENARIOS = (SCENARIO_TEMPORISATION, SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL)


@dataclass
class Dossier:
    """Toutes les données nécessaires à la production d'un rapport."""

    projet: Projet
    scenario_principal: str
    resultats: Dict[str, hydro.Resultat]
    simulation: Optional[simulation.ResultatSimulation]
    table: Optional[simulation.TableAcceptation]
    orifice: Optional[orifice.ResultatOrifice]
    date: str
    duree_critique_min: float = 0.0
    hauteur_critique_mm: float = 0.0

    @property
    def resultat_principal(self) -> hydro.Resultat:
        return self.resultats[self.scenario_principal]

    @property
    def commune(self) -> str:
        return self.projet.commune_nom

    @property
    def libelle_source(self) -> str:
        return rainfall.SourcePluie(
            self.projet.commune_ins, self.projet.periode_retour, self.projet.source_pluie
        ).libelle_source

    # ---- graphiques ----------------------------------------------------
    def graphique_dimensionnement(self) -> charts.Graphique:
        res = self.resultat_principal
        pts = hydro.courbe_volume(self.projet, self.scenario_principal)
        g = charts.Graphique(
            titre="Volume à maîtriser en fonction de la durée de pluie",
            axe_x="Durée de pluie",
            axe_y="Volume [m³]",
            x_log=True,
            series=[charts.Serie("Volume à maîtriser [m³]", pts, charts.BLEU, aire=True)],
            reperes=[charts.Repere(res.volume_m3, f"Volume de dimensionnement {res.volume_m3:.1f} m³", charts.ROUGE)],
        )
        if res.duree_critique_min:
            g.reperes.append(
                charts.Repere(res.duree_critique_min, f"Durée critique {res.duree_critique_hm}",
                              charts.ORANGE, vertical=True)
            )
        return g

    def graphique_simulation(self) -> Optional[charts.Graphique]:
        if not self.simulation or not self.simulation.pas:
            return None
        sim = self.simulation
        pts = [(p.t_min, p.volume_m3) for p in sim.pas]
        g = charts.Graphique(
            titre="Remplissage et vidange du bassin",
            axe_x="Temps [min]",
            axe_y="Volume stocké [m³]",
            series=[charts.Serie("Volume stocké [m³]", pts, charts.BLEU, aire=True)],
        )
        if sim.volume_capacite_m3 > 0:
            g.reperes.append(charts.Repere(sim.volume_capacite_m3,
                                           f"Capacité {sim.volume_capacite_m3:.1f} m³", charts.ROUGE))
        vs = self.projet.bassin.volume_sous_ajutage_m3
        if vs > 0:
            g.reperes.append(charts.Repere(vs, f"Axe de l'ajutage {vs:.1f} m³", charts.VIOLET))
        g.reperes.append(charts.Repere(sim.duree_pluie_min, "Fin de la pluie", charts.GRIS, vertical=True))
        return g

    def graphique_debits(self) -> Optional[charts.Graphique]:
        if not self.simulation or not self.simulation.pas:
            return None
        sim = self.simulation
        return charts.Graphique(
            titre="Débits entrant et sortant",
            axe_x="Temps [min]",
            axe_y="Débit [l/s]",
            series=[
                charts.Serie("Débit entrant [l/s]", [(p.t_min, p.q_entrant_ls) for p in sim.pas], charts.BLEU),
                charts.Serie("Débit sortant [l/s]", [(p.t_min, p.q_sortant_ls) for p in sim.pas], charts.VERT),
                charts.Serie("Débordement [l/s]", [(p.t_min, p.q_debordement_ls) for p in sim.pas], charts.ROUGE),
            ],
        )

    def graphique_orifice(self) -> Optional[charts.Graphique]:
        if not self.orifice or not self.orifice.diametre_mm:
            return None
        d = self.orifice.diametre_commercial_mm or self.orifice.diametre_mm
        pts = orifice.courbe_hauteur_debit(d, self.orifice.charge_m, self.orifice.coef_debit)
        return charts.Graphique(
            titre=f"Débit de l'ajutage DN {d:.0f} mm",
            axe_x="Charge [m]",
            axe_y="Débit [l/s]",
            series=[charts.Serie("Q = Cd.A.racine(2gh)", pts, charts.VERT)],
            reperes=[charts.Repere(self.orifice.debit_ls, f"Débit de projet {self.orifice.debit_ls:.2f} l/s",
                                   charts.ROUGE)],
        )


def construire(projet: Projet, scenario_principal: str = SCENARIO_MIXTE,
               avec_simulation: bool = True) -> Dossier:
    """Calcule tout ce qui est nécessaire au rapport."""
    resultats = {s: hydro.dimensionner(projet, s) for s in ORDRE_SCENARIOS}
    bassin = projet.bassin
    sim = None
    table = None
    duree, hauteur = 0.0, 0.0
    if avec_simulation and bassin.volume_total_m3 > 0 and projet.aire_ponderee_m2 > 0:
        duree, hauteur = simulation.evenement_critique(projet, bassin)
        sim = simulation.simuler(projet, bassin, hauteur, duree)
        table = simulation.table_acceptation(projet, bassin)
    q_ajutage = bassin.debit_ajutage_ls or projet.debit_ajutage_ls
    res_orifice = None
    if q_ajutage > 0 and projet.hauteur_charge_m > 0:
        res_orifice = orifice.dimensionner_orifice(q_ajutage, projet.hauteur_charge_m, projet.coef_debit_orifice)
    return Dossier(
        projet=projet,
        scenario_principal=scenario_principal,
        resultats=resultats,
        simulation=sim,
        table=table,
        orifice=res_orifice,
        date=_dt.date.today().strftime("%d/%m/%Y"),
        duree_critique_min=duree,
        hauteur_critique_mm=hauteur,
    )


def synthese_scenarios(dossier: Dossier) -> List[List[str]]:
    """Tableau de synthèse (entete + lignes) pour les rapports."""
    lignes = [[
        "Scénario", "Volume [m³]", "Durée critique", "Pluie [mm]",
        "Q sortie [l/s]", "Vidange", "S infiltration min [m²]", "Q ajutage min [l/s]",
    ]]
    for s in ORDRE_SCENARIOS:
        r = dossier.resultats[s]
        lignes.append([
            LIBELLES_SCENARIOS[s],
            f"{r.volume_m3:.1f}",
            r.duree_critique_hm,
            f"{r.hauteur_pluie_mm:.1f}",
            f"{r.debit_sortant_ls:.2f}",
            r.temps_vidange_hm if r.temps_vidange_h != float("inf") else "-",
            "-" if r.surface_infiltration_min_m2 is None else f"{r.surface_infiltration_min_m2:.1f}",
            "-" if r.debit_ajutage_min_ls is None else f"{r.debit_ajutage_min_ls:.2f}",
        ])
    return lignes
