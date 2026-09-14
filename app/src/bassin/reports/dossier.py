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
    #: Réseau complet, quand le projet en décrit un (importé tardivement pour
    #: ne pas créer de dépendance circulaire entre le dossier et le réseau).
    systeme: object = None
    fiches: List = field(default_factory=list)
    simulation_systeme: object = None
    #: Fiche de l'ouvrage que *ce* dossier détaille. Vide : l'ouvrage courant
    #: du système, ou le bassin isolé quand il n'y a pas de réseau.
    fiche: object = None

    @property
    def reseau_multiple(self) -> bool:
        """Vrai quand le dossier décrit plus d'un ouvrage ou d'un bassin versant."""
        if self.systeme is None:
            return False
        return len(self.systeme.ouvrages) > 1 or len(self.systeme.bassins_versants) > 1

    @property
    def ouvrage_courant(self):
        """Ouvrage décrit en détail par le dossier, s'il vient d'un réseau."""
        if self.fiche is not None:
            return self.fiche.ouvrage
        return self.systeme.courant if self.systeme is not None else None

    def par_ouvrage(self) -> List["Dossier"]:
        """Un dossier complet par bassin d'orage du réseau.

        Le rapport ne détaillait que l'ouvrage courant : surfaces, scénarios,
        vérification, table QDF et ajutage ne parlaient que de lui. Il fallait
        changer d'ouvrage dans l'application et régénérer le dossier pour voir
        les autres — autant de documents séparés pour une seule étude.

        Chaque ouvrage a maintenant son chapitre, écrit par le même code : il
        suffit de lui donner son propre dossier. Le réseau, lui, n'est
        redimensionné pour aucun d'eux — fiches et simulation d'ensemble sont
        partagées, elles décrivent le système et non un ouvrage.
        """
        if self.systeme is None or not self.fiches:
            return [self]
        dossiers: List["Dossier"] = []
        for fiche in self.fiches:
            sous = construire(fiche.ouvrage.etude, fiche.ouvrage.scenario,
                              avec_simulation=self.simulation is not None
                              or fiche.ouvrage.etude.bassin.volume_total_m3 > 0)
            sous.date = self.date
            sous.systeme = self.systeme
            sous.fiches = self.fiches
            sous.simulation_systeme = self.simulation_systeme
            sous.fiche = fiche
            dossiers.append(sous)
        return dossiers

    @property
    def resultat_principal(self) -> hydro.Resultat:
        return self.resultats[self.scenario_principal]

    @property
    def commune(self) -> str:
        return self.projet.commune_nom

    @property
    def libelle_source(self) -> str:
        return self.source_pluie.libelle_source

    @property
    def source_pluie(self) -> rainfall.SourcePluie:
        return rainfall.SourcePluie(
            self.projet.commune_ins, self.projet.periode_retour, self.projet.source_pluie)

    def phrase_minimum(self, valeur, decimales: int, unite: str, libelle: str,
                       autre_organe: str) -> str:
        """Phrase d'un minimum, qui dit pourquoi il vaut zéro.

        Un « 0,0 m² » sous un scénario « infiltration + orifice » se lit « pas
        d'infiltration nécessaire » alors qu'il signifie « l'autre organe
        vidange déjà à lui seul dans le délai ». L'écran le disait ; les
        livrables, eux, annonçaient encore la valeur nue.
        """
        res = self.resultat_principal
        delai = f"{self.projet.temps_vidange_max_h:.0f} h"
        if valeur is None:
            return f"{libelle} : sans objet pour le scénario retenu."
        if valeur <= 0:
            return (f"{libelle} : aucun complément nécessaire — {autre_organe} seul vidange "
                    f"en {res.temps_vidange_hm}, soit moins que les {delai} admises.")
        return (f"{libelle} pour un temps de vidange de {delai} : "
                f"{valeur:.{decimales}f} {unite}.")

    @property
    def source_pluies_datee(self) -> str:
        """Source des pluies **et** édition du référentiel GTI.

        La fiche officielle demande de vérifier qu'on travaille sur la dernière
        version parue. Un dossier signé doit donc dire de quelle édition il
        sort, sans quoi personne ne peut refaire ce contrôle après coup.
        """
        return f"{self.libelle_source} · données GTI {rainfall.MILLESIME_GTI}"

    @property
    def titre_table_volumes(self) -> str:
        """Intitulé du tableau des volumes requis, source nommée.

        Le même tableau se remplit depuis Montana ou depuis les tables QDF, et
        les deux diffèrent jusqu'à 5 %. Un lecteur du dossier doit voir laquelle
        a servi sans avoir à retrouver l'onglet d'où il vient.
        """
        return self.source_pluie.titre_tableau_volumes

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
            series=charts.series_debits(sim, unites=True),
            reperes=[charts.Repere(sim.duree_pluie_min, "Fin de la pluie", charts.GRIS,
                                   vertical=True)],
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
               avec_simulation: bool = True, systeme=None) -> Dossier:
    """Calcule tout ce qui est nécessaire au rapport.

    ``systeme`` décrit le réseau complet quand le projet en fait partie : le
    rapport y ajoute alors la synthèse du système (schéma, tableau ouvrage par
    ouvrage, simulation d'ensemble), sans rien retirer au dossier de l'ouvrage
    détaillé.
    """
    resultats = {s: hydro.dimensionner(projet, s) for s in ORDRE_SCENARIOS}
    bassin = projet.bassin
    sim = None
    table = None
    duree, hauteur = 0.0, 0.0
    if avec_simulation and bassin.volume_total_m3 > 0 and projet.aire_ponderee_m2 > 0:
        duree, hauteur = simulation.evenement_critique(projet, bassin)
        sim = simulation.simuler_evenement_critique(projet, bassin)
        table = simulation.table_acceptation(projet, bassin)
    q_ajutage = bassin.debit_ajutage_ls or projet.debit_ajutage_ls
    res_orifice = None
    if q_ajutage > 0 and projet.hauteur_charge_m > 0:
        res_orifice = orifice.dimensionner_orifice(q_ajutage, projet.hauteur_charge_m, projet.coef_debit_orifice)
    fiches: List = []
    sim_systeme = None
    if systeme is not None and systeme.aire_ponderee_m2 > 0:
        from ..core import reseau as _reseau

        fiches = _reseau.dimensionner(systeme)
        if avec_simulation:
            sim_systeme = _reseau.simuler_evenement_critique(systeme)
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
        systeme=systeme,
        fiches=fiches,
        simulation_systeme=sim_systeme,
    )


def synthese_scenarios(dossier: Dossier) -> List[List[str]]:
    """Tableau de synthèse (entete + lignes) pour les rapports."""
    lignes = [[
        "Scénario", "Volume [m³]", "dont au-dessus de l'ajutage [m³]", "Durée critique",
        "Pluie [mm]", "Q sortie [l/s]", "Vidange après pluie", "S infiltration min [m²]",
        "Q ajutage min [l/s]",
    ]]
    for s in ORDRE_SCENARIOS:
        r = dossier.resultats[s]
        lignes.append([
            LIBELLES_SCENARIOS[s],
            f"{r.volume_m3:.1f}",
            # Le volume mort sous l'axe est une donnée d'entrée : c'est la part
            # au-dessus qu'il reste à creuser.
            f"{r.volume_au_dessus_ajutage_m3:.1f}" if s == SCENARIO_SEUIL else "—",
            r.duree_critique_hm,
            f"{r.hauteur_pluie_mm:.1f}",
            f"{r.debit_sortant_ls:.2f}",
            r.temps_vidange_hm if r.temps_vidange_h != float("inf") else "-",
            "-" if r.surface_infiltration_min_m2 is None else f"{r.surface_infiltration_min_m2:.1f}",
            "-" if r.debit_ajutage_min_ls is None else f"{r.debit_ajutage_min_ls:.2f}",
        ])
    return lignes


def synthese_versants(dossier: Dossier) -> List[List[str]]:
    """Tableau des bassins versants du projet (entête + lignes)."""
    lignes = [["Bassin versant", "Raccordé à", "Surface [m²]", "C moyen",
               "Surface active [m²]", "Surface de référence [m²]"]]
    systeme = dossier.systeme
    if systeme is None:
        return lignes
    for bv in systeme.bassins_versants:
        cible = systeme.ouvrage(bv.bassin_id)
        lignes.append([
            bv.nom,
            cible.nom if cible is not None else "non raccordé",
            f"{bv.aire_totale_m2:.0f}",
            f"{bv.coefficient_moyen:.3f}",
            f"{bv.aire_ponderee_m2:.1f}",
            f"{bv.surface_reference_m2:.0f}",
        ])
    lignes.append(["TOTAL", "", f"{systeme.aire_totale_m2:.0f}",
                   f"{systeme.coefficient_moyen:.3f}", f"{systeme.aire_ponderee_m2:.1f}", ""])
    return lignes


def synthese_reseau(dossier: Dossier) -> List[List[str]]:
    """Tableau du dimensionnement ouvrage par ouvrage (entête + lignes)."""
    lignes = [["Bassin d'orage", "Se déverse vers", "Surverse", "S active propre [m²]",
               "S active amont [m²]", "V minimal [m³]", "V encodé [m³]", "Pluie critique",
               "Vidange", "S infiltration min [m²]", "Q ajutage min [l/s]"]]
    systeme = dossier.systeme
    if systeme is None:
        return lignes
    for fiche in dossier.fiches:
        o = fiche.ouvrage
        aval = systeme.aval(o.id)
        res = fiche.resultat
        lignes.append([
            o.nom,
            aval.nom if aval is not None else "exutoire",
            ("milieu naturel" if o.surverse_vers_milieu_naturel or aval is None
             else f"vers {aval.nom}"),
            f"{fiche.aire_ponderee_propre_m2:.0f}",
            f"{fiche.aire_ponderee_amont_m2:.0f}",
            f"{fiche.volume_minimal_m3:.1f}",
            f"{fiche.volume_encode_m3:.1f}",
            res.duree_critique_hm,
            res.temps_vidange_hm if res.temps_vidange_h != float("inf") else "-",
            "-" if res.surface_infiltration_min_m2 is None
            else f"{res.surface_infiltration_min_m2:.1f}",
            "-" if res.debit_ajutage_min_ls is None else f"{res.debit_ajutage_min_ls:.3f}",
        ])
    return lignes


def synthese_simulation_systeme(dossier: Dossier) -> List[List[str]]:
    """Tableau de la simulation d'ensemble (entête + lignes)."""
    lignes = [["Bassin d'orage", "Pointe [m³]", "Capacité [m³]", "Remplissage [%]",
               "Débordement [m³]", "Apport amont [m³]", "Vidange", "Statut"]]
    sim = dossier.simulation_systeme
    if sim is None:
        return lignes
    for ouvrage, res in sim.resultats:
        lignes.append([
            ouvrage.nom,
            f"{res.volume_max_m3:.1f}",
            f"{res.volume_capacite_m3:.1f}",
            f"{res.taux_remplissage * 100:.0f}",
            f"{res.volume_debordement_m3:.2f}",
            f"{res.volume_amont_m3:.1f}",
            res.temps_vidange_h_texte,
            res.statut,
        ])
    return lignes
