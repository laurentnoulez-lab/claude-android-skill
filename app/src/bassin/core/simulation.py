"""Simulation du remplissage / vidange d'un bassin et table QDF d'acceptation."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from . import rainfall
from .model import Bassin, Projet, debit_infiltration_ls
from .hydro import formater_duree, temps_vidange_h


@dataclass
class PasSimulation:
    t_min: float
    volume_m3: float
    q_entrant_ls: float
    q_infiltration_ls: float
    q_ajutage_ls: float
    q_debordement_ls: float

    @property
    def q_sortant_ls(self) -> float:
        return self.q_infiltration_ls + self.q_ajutage_ls


@dataclass
class ResultatSimulation:
    """Sortie d'une simulation d'événement pluvieux sur un bassin."""

    duree_pluie_min: float
    hauteur_pluie_mm: float
    periode_retour: int
    volume_ruissele_m3: float = 0.0
    volume_max_m3: float = 0.0
    volume_capacite_m3: float = 0.0
    volume_debordement_m3: float = 0.0
    t_volume_max_min: float = 0.0
    t_debordement_min: Optional[float] = None
    temps_vidange_h: float = 0.0
    temps_retour_a_vide_min: float = 0.0
    q_entrant_ls: float = 0.0
    q_infiltration_ls: float = 0.0
    q_ajutage_ls: float = 0.0
    pas: List[PasSimulation] = field(default_factory=list)

    @property
    def debordement(self) -> bool:
        return self.volume_debordement_m3 > 1e-6

    @property
    def taux_remplissage(self) -> float:
        if self.volume_capacite_m3 <= 0:
            return 0.0
        return self.volume_max_m3 / self.volume_capacite_m3

    @property
    def statut(self) -> str:
        if self.debordement:
            return "DEBORDEMENT"
        if self.taux_remplissage > 0.95:
            return "LIMITE"
        return "OK"


def _debits(projet: Projet, bassin: Bassin) -> Tuple[float, float]:
    q_inf = debit_infiltration_ls(bassin.surface_dispersion_m2, projet.k_infiltration_ms,
                                  projet.coef_securite_infiltration)
    return q_inf, bassin.debit_ajutage_ls


def volume_necessaire(projet: Projet, bassin: Bassin, hauteur_mm: float, duree_min: float) -> float:
    """Volume de stockage requis (sans limite de capacité) pour une pluie donnée.

    Prend en compte l'ajutage surélevée : sous le volume mort il n'y a que
    l'infiltration.
    """
    q_inf, q_aj = _debits(projet, bassin)
    v_in = hauteur_mm * projet.aire_ponderee_m2 / 1000.0
    if duree_min <= 0 or v_in <= 0:
        return 0.0
    v_sous = bassin.volume_sous_ajutage_m3
    if v_sous > 0:
        q_in = v_in * 1000.0 / (duree_min * 60.0)
        q_net = q_in - q_inf
        if q_net <= 0:
            return 0.0
        t_seuil = v_sous * 1000.0 / q_net / 60.0
        t_aj = max(duree_min - t_seuil, 0.0)
        v_out = (q_inf * duree_min + q_aj * t_aj) * 60.0 / 1000.0
    else:
        v_out = (q_inf + q_aj) * duree_min * 60.0 / 1000.0
    return max(v_in - v_out, 0.0)


TOLERANCE_M3 = 1e-9


def _grille(duree_pluie_min: float, horizon_min: float, n_points: int) -> List[float]:
    """Instants d'échantillonnage, avec un nœud exactement à la fin de l'averse.

    Sans ce nœud, le dernier pas « humide » déverse de la pluie au-delà de
    l'averse : le bassin recevait plus que la pluie de projet et pouvait
    déborder alors que le dimensionnement le disait suffisant.
    """
    n_points = max(n_points, 40)
    fin_pluie = min(duree_pluie_min, horizon_min)
    part = fin_pluie / horizon_min if horizon_min > 0 else 1.0
    n_pluie = max(int(round(n_points * part)), 10)
    n_apres = max(n_points - n_pluie, 10)
    noeuds = [fin_pluie * i / n_pluie for i in range(n_pluie + 1)]
    if horizon_min > fin_pluie:
        reste = horizon_min - fin_pluie
        noeuds += [fin_pluie + reste * i / n_apres for i in range(1, n_apres + 1)]
    return noeuds


def _debits_sortants(v: float, q_in_ls: float, q_inf_ls: float, q_aj_ls: float,
                     v_sous: float) -> Tuple[float, float]:
    """Débits (infiltration, ajutage) [l/s] pour un volume stocké donné.

    Au ras d'un seuil, c'est l'eau qui arrive qui tranche : un bassin vide mais
    alimenté évacue déjà par le fond et par l'orifice de fond, et un niveau qui
    atteint l'axe de l'ajutage le met aussitôt en service. Sans cela, un bassin
    parti de zéro n'évacuait rien pendant tout le premier pas de calcul et la
    simulation débordait là où le dimensionnement annonçait la capacité juste.
    """
    if v <= TOLERANCE_M3:
        # Bassin vide : on n'évacue que ce qui arrive, sans jamais passer sous zéro.
        q_inf_eff = min(q_in_ls, q_inf_ls)
        reste = max(q_in_ls - q_inf_eff, 0.0)
        q_aj_eff = min(reste, q_aj_ls) if v_sous <= TOLERANCE_M3 else 0.0
        return q_inf_eff, q_aj_eff
    if v > v_sous + TOLERANCE_M3:
        return q_inf_ls, q_aj_ls
    if abs(v - v_sous) <= TOLERANCE_M3 and q_in_ls > q_inf_ls:
        # Le niveau est sur l'axe de l'orifice : l'ajutage se met en service, mais
        # il ne peut pas évacuer plus que l'apport — sinon le niveau y stagne.
        return q_inf_ls, min(q_aj_ls, q_in_ls - q_inf_ls)
    return q_inf_ls, 0.0


def _avancer(v: float, duree_min: float, q_in_ls: float, q_inf_ls: float, q_aj_ls: float,
             v_sous: float, v_cap: float) -> Tuple[float, float, float, Optional[float]]:
    """Fait évoluer le volume pendant ``duree_min`` à débit entrant constant.

    Entre deux seuils les débits sont constants, donc le volume varie
    linéairement : l'intervalle est découpé aux instants exacts où l'ajutage
    démarre ou s'arrête, où le bassin se vide et où il atteint le trop-plein.
    Le résultat ne dépend donc pas de la finesse de l'échantillonnage.

    Renvoie (volume final, volume débordé, volume maximal, délai du premier
    débordement).
    """
    illimite = v_cap <= 0
    reste = duree_min
    debord = 0.0
    v_max = v
    t_debord: Optional[float] = None
    ecoule = 0.0
    garde = 0
    while reste > 1e-12 and garde < 64:
        garde += 1
        q_infiltre, q_ajute = _debits_sortants(v, q_in_ls, q_inf_ls, q_aj_ls, v_sous)
        pente = (q_in_ls - q_infiltre - q_ajute) * 60.0 / 1000.0   # m³ par minute
        plein = (not illimite) and v >= v_cap - TOLERANCE_M3
        if plein and pente > 0:
            # Bassin plein : tout l'excédent part au trop-plein.
            debord += pente * reste
            if t_debord is None:
                t_debord = ecoule
            v_max = max(v_max, v)
            ecoule += reste
            reste = 0.0
            continue
        if abs(pente) < 1e-15:
            v_max = max(v_max, v)
            ecoule += reste
            break
        # Prochain seuil franchi par le volume.
        cibles = []
        if pente > 0:
            if v < v_sous - TOLERANCE_M3:
                cibles.append(v_sous)
            if not illimite:
                cibles.append(v_cap)
        else:
            if v > v_sous + TOLERANCE_M3:
                cibles.append(v_sous)
            cibles.append(0.0)
        delais = [(c - v) / pente for c in cibles]
        delais = [d for d in delais if d > 1e-12]
        pas = min([reste] + delais)
        v = v + pente * pas
        if not illimite:
            v = min(v, v_cap)
        v = max(v, 0.0)
        v_max = max(v_max, v)
        ecoule += pas
        reste -= pas
    return v, debord, v_max, t_debord


def simuler(projet: Projet, bassin: Bassin, hauteur_mm: float, duree_pluie_min: float,
            n_points: int = 400, marge_vidange: float = 1.25) -> ResultatSimulation:
    """Simulation du remplissage puis de la vidange du bassin.

    Hypothèses : pluie de projet à intensité constante (bloc), infiltration
    constante sur la surface de dispersion tant qu'il reste de l'eau, ajutage à
    débit constant dès que le niveau dépasse l'axe de l'orifice. L'intégration
    est exacte entre les seuils, si bien que le volume maximal coïncide avec le
    volume annoncé par le dimensionnement.
    """
    q_inf, q_aj = _debits(projet, bassin)
    v_cap = bassin.volume_total_m3
    v_sous = min(bassin.volume_sous_ajutage_m3, v_cap) if v_cap > 0 else bassin.volume_sous_ajutage_m3

    res = ResultatSimulation(
        duree_pluie_min=duree_pluie_min,
        hauteur_pluie_mm=hauteur_mm,
        periode_retour=projet.periode_retour,
        volume_capacite_m3=v_cap,
        q_infiltration_ls=q_inf,
        q_ajutage_ls=q_aj,
    )
    v_in_total = hauteur_mm * projet.aire_ponderee_m2 / 1000.0
    res.volume_ruissele_m3 = v_in_total
    if duree_pluie_min <= 0:
        return res
    q_in = v_in_total * 1000.0 / (duree_pluie_min * 60.0)   # l/s
    res.q_entrant_ls = q_in

    # Horizon : l'averse, puis la vidange estimée.
    v_pointe = min(volume_necessaire(projet, bassin, hauteur_mm, duree_pluie_min),
                   v_cap if v_cap > 0 else 1e12)
    t_vid = temps_vidange_h(v_pointe, q_inf, q_aj, v_sous) * 60.0
    if t_vid == float("inf") or t_vid > 30 * 1440:
        t_vid = 30 * 1440.0
    horizon = duree_pluie_min + max(t_vid * marge_vidange, duree_pluie_min * 0.25) + 1.0

    noeuds = _grille(duree_pluie_min, horizon, n_points)
    v = 0.0
    v_max = 0.0
    t_vmax = 0.0
    v_debord = 0.0
    t_debord: Optional[float] = None
    t_vide: Optional[float] = None
    pas: List[PasSimulation] = [PasSimulation(0.0, 0.0, q_in, 0.0, 0.0, 0.0)]

    for t0, t1 in zip(noeuds, noeuds[1:]):
        pluie = t0 < duree_pluie_min - 1e-9
        qi = q_in if pluie else 0.0
        v_avant = v
        v, debord, sommet, delai_debord = _avancer(
            v, t1 - t0, qi, q_inf, q_aj, v_sous, v_cap)
        v_debord += debord
        if debord > 0 and t_debord is None and delai_debord is not None:
            t_debord = t0 + delai_debord
        if sommet > v_max:
            v_max = sommet
            t_vmax = t1 if v >= v_avant else t0
        q_infiltre, q_ajute = _debits_sortants(max(v_avant, v), qi, q_inf, q_aj, v_sous)
        q_deb = debord * 1000.0 / ((t1 - t0) * 60.0) if t1 > t0 else 0.0
        if t_vide is None and t1 > duree_pluie_min and v <= 1e-6:
            t_vide = t1
        pas.append(PasSimulation(t1, v, qi, q_infiltre, q_ajute, q_deb))

    res.pas = pas
    res.volume_max_m3 = v_max
    res.t_volume_max_min = t_vmax
    res.volume_debordement_m3 = v_debord
    res.t_debordement_min = t_debord
    res.temps_vidange_h = temps_vidange_h(v_max, q_inf, q_aj, v_sous)
    res.temps_retour_a_vide_min = (t_vide - duree_pluie_min) if t_vide else res.temps_vidange_h * 60.0
    return res


# ---------------------------------------------------------------------------
# Table QDF d'acceptation
# ---------------------------------------------------------------------------
@dataclass
class CelluleQDF:
    duree_min: float
    periode_retour: int
    hauteur_mm: float
    volume_requis_m3: float
    capacite_m3: float
    temps_vidange_h: float

    @property
    def taux(self) -> float:
        return self.volume_requis_m3 / self.capacite_m3 if self.capacite_m3 > 0 else float("inf")

    @property
    def accepte(self) -> bool:
        return self.volume_requis_m3 <= self.capacite_m3 + 1e-9

    @property
    def statut(self) -> str:
        if not self.accepte:
            return "DEBORDEMENT"
        if self.taux > 0.95:
            return "LIMITE"
        return "OK"


@dataclass
class TableAcceptation:
    durees_min: Tuple[float, ...]
    periodes_retour: Tuple[int, ...]
    cellules: List[List[CelluleQDF]]
    source: str
    capacite_m3: float

    def periode_retour_max_acceptee(self) -> Optional[int]:
        """Plus grande récurrence entièrement absorbée (toutes durées)."""
        meilleure = None
        for j, rp in enumerate(self.periodes_retour):
            if all(self.cellules[i][j].accepte for i in range(len(self.durees_min))):
                meilleure = rp
        return meilleure

    def durees_critiques(self, periode_retour: int) -> List[float]:
        j = self.periodes_retour.index(periode_retour)
        return [self.durees_min[i] for i in range(len(self.durees_min)) if not self.cellules[i][j].accepte]


def table_acceptation(projet: Projet, bassin: Bassin,
                      durees: Optional[Tuple[float, ...]] = None) -> TableAcceptation:
    """Construit la table QDF : quelles pluies le bassin encaisse sans déborder."""
    durees = durees or tuple(float(d) for d in rainfall.QDF_DURATIONS_MIN)
    rps = rainfall.RETURN_PERIODS
    q_inf, q_aj = _debits(projet, bassin)
    lignes: List[List[CelluleQDF]] = []
    for d in durees:
        ligne: List[CelluleQDF] = []
        for rp in rps:
            src = rainfall.SourcePluie(projet.commune_ins, rp, projet.source_pluie)
            h = src.hauteur(d)
            v = volume_necessaire(projet, bassin, h, d)
            v_stocke = min(v, bassin.volume_total_m3) if bassin.volume_total_m3 > 0 else v
            ligne.append(
                CelluleQDF(
                    duree_min=d,
                    periode_retour=rp,
                    hauteur_mm=h,
                    volume_requis_m3=v,
                    capacite_m3=bassin.volume_total_m3,
                    temps_vidange_h=temps_vidange_h(v_stocke, q_inf, q_aj, bassin.volume_sous_ajutage_m3),
                )
            )
        lignes.append(ligne)
    return TableAcceptation(
        durees_min=tuple(durees),
        periodes_retour=rps,
        cellules=lignes,
        source=projet.source_pluie,
        capacite_m3=bassin.volume_total_m3,
    )


def evenement_critique(projet: Projet, bassin: Bassin, periode_retour: Optional[int] = None) -> Tuple[float, float]:
    """(durée critique [min], hauteur [mm]) maximisant le volume requis."""
    rp = periode_retour or projet.periode_retour
    src = rainfall.SourcePluie(projet.commune_ins, rp, projet.source_pluie)
    from .hydro import DUREE_MAX, DUREE_MIN, PAS_DUREE

    meilleure = (float(DUREE_MIN), 0.0, -1.0)
    for t in range(DUREE_MIN, DUREE_MAX + 1, PAS_DUREE):
        h = src.hauteur(t)
        v = volume_necessaire(projet, bassin, h, float(t))
        if v > meilleure[2]:
            meilleure = (float(t), h, v)
    return meilleure[0], meilleure[1]
