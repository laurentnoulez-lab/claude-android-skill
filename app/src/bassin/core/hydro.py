"""Méthode rationnelle : dimensionnement des volumes de temporisation.

Principe (GTI) : pour chaque durée de pluie *t* on compare le volume ruisselle
sur les surfaces actives au volume évacué par le dispositif pendant cette meme
durée. La durée critique est celle qui maximise la différence.

.. code::

    V_ruisselle(t) = h(t) [mm] * S_pondérée [m²] / 1000        -> m³
    V_évacué(t)    = Q_sortie [l/s] * t [min] * 60 / 1000      -> m³
    V_a_maîtriser  = max( V_ruisselle(t) - V_évacué(t) , 0 )
"""

from __future__ import annotations

from dataclasses import dataclass, field
from functools import lru_cache
from typing import Dict, List, Optional, Sequence, Tuple

from . import rainfall
from .model import (
    Projet,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_SEUIL,
    SCENARIO_TEMPORISATION,
    debit_infiltration_ls,
    surface_infiltration_requise_m2,
    LIBELLES_SCENARIOS,
    POURCENTAGE_SURFACE_INFILTRATION_LIMITE,
)

#: Grille de durées de pluie balayée (minutes), calquée sur la fiche de calcul du
#: GTI : sa feuille « Pluie » compte 17 280 lignes, de 10 min à 86 405 min par pas
#: de 5 min. Le dernier point compte : quand le débit de sortie est très faible, le
#: volume croît encore en fin de grille et c'est lui qui donne le maximum.
DUREE_MIN = 10
DUREE_MAX = 86405
PAS_DUREE = 5


@lru_cache(maxsize=32)
def serie_pluie(ins: str, periode_retour: int, source: str) -> Tuple[Tuple[float, ...], Tuple[float, ...]]:
    """Serie (durées [min], hauteurs [mm]) mise en cache pour les balayages."""
    src = rainfall.SourcePluie(ins, periode_retour, source)
    # La grille dépend de la source : continue avec Montana, limitée aux durées
    # normalisées avec les tables QDF.
    durees = src.durees_de_balayage(DUREE_MIN, DUREE_MAX, PAS_DUREE)
    hauteurs = tuple(src.hauteur(t) for t in durees)
    return durees, hauteurs


def serie_projet(projet: Projet) -> Tuple[Tuple[float, ...], Tuple[float, ...]]:
    return serie_pluie(projet.commune_ins, projet.periode_retour, projet.source_pluie)


# ---------------------------------------------------------------------------
# Balayages
# ---------------------------------------------------------------------------
def volume_a_maitriser(
    serie: Tuple[Tuple[float, ...], Tuple[float, ...]],
    aire_ponderee_m2: float,
    debit_sortie_ls: float,
) -> Tuple[float, float, float]:
    """Retourne (volume [m³], durée critique [min], hauteur de pluie [mm])."""
    durees, hauteurs = serie
    k_in = aire_ponderee_m2 / 1000.0
    k_out = debit_sortie_ls * 60.0 / 1000.0
    v_max, t_max, h_max = 0.0, float(durees[0]) if durees else 0.0, 0.0
    for t, h in zip(durees, hauteurs):
        v = h * k_in - k_out * t
        if v > v_max:
            v_max, t_max, h_max = v, t, h
    return v_max, t_max, h_max


def volume_pointe_seuil(v_in_m3: float, duree_min: float, debit_infiltration: float,
                        debit_ajutage: float, volume_sous_ajutage_m3: float) -> float:
    """Volume stocké maximal pour un orifice surélevé, à intensité constante.

    Trois régimes se succèdent, et il faut les distinguer :

    * l'apport ne dépasse pas l'infiltration : rien ne s'accumule ;
    * le niveau n'atteint pas l'axe de l'orifice pendant l'averse : seule
      l'infiltration a évacué ;
    * le niveau atteint l'axe : l'ajutage entre en service. Ou bien l'apport
      reste supérieur à l'infiltration **plus** l'ajutage et le niveau continue de
      monter, ou bien il est inférieur et le niveau se stabilise sur l'axe.

    Ce dernier cas était traité comme si l'ajutage continuait à débiter à plein
    régime sous son propre axe : le volume calculé devenait négatif, ramené à
    zéro, alors que le bassin contient en réalité tout son volume mort.
    """
    if duree_min <= 0 or v_in_m3 <= 0:
        return 0.0
    q_in = v_in_m3 * 1000.0 / (duree_min * 60.0)          # l/s, averse en bloc
    q_net = q_in - debit_infiltration                      # remplissage sous l'axe
    if q_net <= 0:
        return 0.0
    if volume_sous_ajutage_m3 <= 0:
        return max(v_in_m3 - (debit_infiltration + debit_ajutage) * duree_min * 60.0 / 1000.0,
                   0.0)
    t_seuil = volume_sous_ajutage_m3 * 1000.0 / q_net / 60.0   # minutes
    if duree_min <= t_seuil:
        # L'eau n'atteint jamais l'axe de l'orifice.
        return q_net * duree_min * 60.0 / 1000.0
    q_haut = q_in - debit_infiltration - debit_ajutage      # remplissage au-dessus
    if q_haut <= 0:
        # Le niveau se stabilise sur l'axe : le bassin garde son volume mort.
        return volume_sous_ajutage_m3
    return volume_sous_ajutage_m3 + q_haut * (duree_min - t_seuil) * 60.0 / 1000.0


def volume_a_maitriser_seuil(
    serie: Tuple[Tuple[float, ...], Tuple[float, ...]],
    aire_ponderee_m2: float,
    debit_infiltration: float,
    debit_ajutage: float,
    volume_sous_ajutage_m3: float,
) -> Tuple[float, float, float]:
    """Variante "orifice surelevé" : l'ajutage ne débite qu'au-delà d'un seuil.

    Tant que le volume stocké reste sous l'axe de l'orifice, seule l'infiltration
    évacue. Au-dela, l'ajutage s'ajoute à l'infiltration.
    """
    durees, hauteurs = serie
    k_in = aire_ponderee_m2 / 1000.0
    v_max, t_max, h_max = 0.0, float(durees[0]) if durees else 0.0, 0.0
    for t, h in zip(durees, hauteurs):
        v = volume_pointe_seuil(h * k_in, t, debit_infiltration, debit_ajutage,
                                volume_sous_ajutage_m3)
        if v > v_max:
            v_max, t_max, h_max = v, t, h
    return v_max, t_max, h_max


def volume_pointe_amont(projet: Projet, duree_min: float, hauteur_mm: float,
                        debit_infiltration: float, debit_ajutage: float,
                        volume_sous_ajutage_m3: float) -> float:
    """Volume a maitriser pour une averse donnée, apport du bassin amont compris.

    Cet apport varie dans le temps — il se poursuit après l'averse, et bondit si
    le bassin amont surverse — ce qu'aucune formule fermée ne décrit. On réutilise
    donc l'intégrateur exact de la simulation.
    """
    from . import simulation

    if duree_min <= 0:
        return 0.0
    apport = simulation.hydrogramme_amont(projet, hauteur_mm, duree_min)
    q_direct = hauteur_mm * projet.aire_ponderee_m2 / (duree_min * 60.0)
    return simulation.pic_volume_m3(q_direct, duree_min, apport, debit_infiltration,
                                    debit_ajutage, volume_sous_ajutage_m3)


def volume_a_maitriser_amont(
    projet: Projet,
    serie: Tuple[Tuple[float, ...], Tuple[float, ...]],
    debit_infiltration: float,
    debit_ajutage: float,
    volume_sous_ajutage_m3: float,
) -> Tuple[float, float, float]:
    """Balayage des durées de pluie tenant compte du bassin d'orage amont.

    L'intégration exacte coûte trop cher pour être répétée sur les 17 280 durées
    de la grille GTI à chaque frappe : on dégrossit sur environ 200 durées, puis
    on affine autour du maximum. Sur les cas testés la valeur retenue est celle
    du balayage exhaustif.
    """
    durees, hauteurs = serie

    def pointe(t: float, h: float) -> float:
        return volume_pointe_amont(projet, t, h, debit_infiltration, debit_ajutage,
                                   volume_sous_ajutage_m3)

    COARSE = 200
    pas = max(1, len(durees) // COARSE)
    if pas > 1:
        indices = list(range(0, len(durees), pas))
        if indices[-1] != len(durees) - 1:
            indices.append(len(durees) - 1)
        meilleur = max(indices, key=lambda i: pointe(durees[i], hauteurs[i]))
        debut, fin = max(0, meilleur - pas), min(len(durees) - 1, meilleur + pas)
        plage = range(debut, fin + 1)
    else:
        plage = range(len(durees))

    v_max, t_max, h_max = 0.0, float(durees[0]) if durees else 0.0, 0.0
    for i in plage:
        v = pointe(durees[i], hauteurs[i])
        if v > v_max:
            v_max, t_max, h_max = v, durees[i], hauteurs[i]
    return v_max, t_max, h_max


# ---------------------------------------------------------------------------
# Resultat
# ---------------------------------------------------------------------------
@dataclass
class Resultat:
    """Résultat d'un dimensionnement par la méthode rationnelle."""

    scenario: str
    libelle: str
    volume_m3: float = 0.0
    duree_critique_min: float = 0.0
    hauteur_pluie_mm: float = 0.0
    intensite_ls_ha: float = 0.0
    intensite_mmh: float = 0.0
    debit_entrant_ls: float = 0.0
    debit_infiltration_ls: float = 0.0
    debit_ajutage_ls: float = 0.0
    debit_sortant_ls: float = 0.0
    temps_vidange_h: float = 0.0
    surface_infiltration_m2: float = 0.0
    surface_infiltration_min_m2: Optional[float] = None
    debit_ajutage_min_ls: Optional[float] = None
    volume_sous_ajutage_m3: float = 0.0
    #: Vrai quand l'apport d'un bassin d'orage amont est inclus dans le volume.
    amont_pris_en_compte: bool = False
    conforme: bool = True
    messages: List[str] = field(default_factory=list)
    alertes: List[str] = field(default_factory=list)

    @property
    def dimensionnable(self) -> bool:
        """Un volume n'a de sens que si le dispositif se vidange."""
        return self.debit_sortant_ls > 0 and self.volume_m3 > 0

    @property
    def volume_affiche(self) -> str:
        return f"{self.volume_m3:.1f}" if self.dimensionnable else "—"

    @property
    def temps_vidange_hm(self) -> str:
        if self.temps_vidange_h == float("inf"):
            return "infini"
        h = int(self.temps_vidange_h)
        m = int(round((self.temps_vidange_h - h) * 60))
        if m == 60:
            h, m = h + 1, 0
        return f"{h} h {m:02d}"

    @property
    def duree_critique_hm(self) -> str:
        return formater_duree(self.duree_critique_min)


def formater_duree(minutes: float) -> str:
    """Formatage lisible d'une durée en minutes."""
    minutes = float(minutes)
    if minutes < 60:
        return f"{minutes:.0f} min"
    if minutes < 1440:
        h, m = divmod(int(round(minutes)), 60)
        return f"{h} h {m:02d}" if m else f"{h} h"
    j = minutes / 1440.0
    return f"{j:.1f} j" if j % 1 else f"{j:.0f} j"


# ---------------------------------------------------------------------------
# Dimensionnement par scenario
# ---------------------------------------------------------------------------
def debits_scenario(projet: Projet, scenario: str, surface_infiltration: Optional[float] = None,
                    debit_ajutage: Optional[float] = None) -> Tuple[float, float]:
    """(débit d'infiltration, débit d'ajutage) [l/s] retenus pour le scénario."""
    s_inf = projet.surface_infiltration_m2 if surface_infiltration is None else surface_infiltration
    q_aj = projet.debit_ajutage_ls if debit_ajutage is None else debit_ajutage
    q_inf = debit_infiltration_ls(s_inf, projet.k_infiltration_ms, projet.coef_securite_infiltration)
    if scenario == SCENARIO_TEMPORISATION:
        return 0.0, q_aj
    if scenario == SCENARIO_DISPERSION:
        return q_inf, 0.0
    return q_inf, q_aj


def volume_de_dimensionnement(
    projet: Projet,
    serie: Tuple[Tuple[float, ...], Tuple[float, ...]],
    scenario: str,
    debit_infiltration: float,
    debit_ajutage: float,
) -> Tuple[float, float, float]:
    """Volume a maitriser, durée critique et hauteur de pluie pour un scénario.

    Règle unique du dimensionnement : un bassin amont impose l'intégration
    exacte de son apport, un ajutage surélevé la formule des trois régimes, et
    le cas courant la formule fermée. La recherche des minima passe par ici
    elle aussi, pour ne pas pouvoir répondre autrement que le tableau.
    """
    v_sous = projet.bassin.volume_sous_ajutage_m3 if scenario == SCENARIO_SEUIL else 0.0
    if projet.amont.actif:
        # Un bassin amont déverse ici : son apport doit entrer dans le volume à
        # prévoir, sans quoi l'ouvrage dimensionné déborderait en simulation.
        return volume_a_maitriser_amont(projet, serie, debit_infiltration, debit_ajutage, v_sous)
    if scenario == SCENARIO_SEUIL:
        return volume_a_maitriser_seuil(serie, projet.aire_ponderee_m2,
                                        debit_infiltration, debit_ajutage, v_sous)
    return volume_a_maitriser(serie, projet.aire_ponderee_m2,
                              debit_infiltration + debit_ajutage)


def dimensionner(projet: Projet, scenario: str, surface_infiltration: Optional[float] = None,
                 debit_ajutage: Optional[float] = None, avec_minima: bool = True) -> Resultat:
    """Dimensionne le volume de temporisation pour un scénario donne."""
    serie = serie_projet(projet)
    s_pond = projet.aire_ponderee_m2
    q_inf, q_aj = debits_scenario(projet, scenario, surface_infiltration, debit_ajutage)
    s_inf = projet.surface_infiltration_m2 if surface_infiltration is None else surface_infiltration
    v_sous = projet.bassin.volume_sous_ajutage_m3

    res = Resultat(scenario=scenario, libelle=LIBELLES_SCENARIOS[scenario])
    res.debit_infiltration_ls = q_inf
    res.debit_ajutage_ls = q_aj
    res.surface_infiltration_m2 = s_inf
    res.volume_sous_ajutage_m3 = v_sous

    res.debit_sortant_ls = q_inf + q_aj
    res.amont_pris_en_compte = projet.amont.actif
    v, t, h = volume_de_dimensionnement(projet, serie, scenario, q_inf, q_aj)

    res.volume_m3 = v
    res.duree_critique_min = t
    res.hauteur_pluie_mm = h
    res.intensite_mmh = h * 60.0 / t if t else 0.0
    res.intensite_ls_ha = res.intensite_mmh * 10000.0 / 3600.0
    res.debit_entrant_ls = h * s_pond / (t * 60.0) if t else 0.0
    res.temps_vidange_h = temps_vidange_apres_pluie_h(
        projet, v, t, h, q_inf, q_aj, v_sous if scenario == SCENARIO_SEUIL else 0.0)

    _controles(projet, res, scenario)
    if avec_minima:
        _minima(projet, res, scenario)
    return res


def dimensionner_amont(projet: Projet) -> Resultat:
    """Dimensionne le bassin d'orage amont sur son propre bassin versant.

    Même méthode rationnelle et même pluie de projet que pour l'ouvrage aval :
    le volume renvoyé est le minimum pour que le bassin amont ne déborde pas.
    """
    amont = projet.amont
    q_inf = amont.debit_infiltration_ls(projet.coef_securite_infiltration)
    q_aj = amont.debit_ajutage_ls
    res = Resultat(scenario="amont", libelle="Bassin d'orage amont")
    res.debit_infiltration_ls = q_inf
    res.debit_ajutage_ls = q_aj
    res.debit_sortant_ls = q_inf + q_aj
    res.surface_infiltration_m2 = amont.surface_dispersion_m2
    s_pond = amont.aire_ponderee_m2
    if s_pond <= 0:
        return res
    v, t, h = volume_a_maitriser(serie_projet(projet), s_pond, res.debit_sortant_ls)
    res.volume_m3 = v
    res.duree_critique_min = t
    res.hauteur_pluie_mm = h
    res.intensite_mmh = h * 60.0 / t if t else 0.0
    res.intensite_ls_ha = res.intensite_mmh * 10000.0 / 3600.0
    res.debit_entrant_ls = res.intensite_ls_ha * s_pond / 10000.0
    res.temps_vidange_h = temps_vidange_h(v, q_inf, q_aj, 0.0)
    if res.debit_sortant_ls <= 0:
        res.conforme = False
        res.alertes.append(
            "Le bassin amont n'a ni ajutage ni infiltration : il ne se vidange pas."
        )
    elif res.temps_vidange_h > projet.temps_vidange_max_h:
        res.alertes.append(
            f"Vidange du bassin amont en {res.temps_vidange_hm}, au-dela du maximum admis."
        )
    return res


def volume_amont_minimal_m3(projet: Projet) -> float:
    """Volume de temporisation minimal du bassin amont pour éviter tout débordement."""
    return dimensionner_amont(projet).volume_m3


def temps_vidange_apres_pluie_h(projet: Projet, volume_m3: float, duree_min: float,
                                hauteur_mm: float, debit_infiltration: float,
                                debit_ajutage: float, volume_sous_ajutage_m3: float) -> float:
    """Temps de vidange après la fin de l'averse [h].

    Sans bassin amont, l'ouvrage est livré à lui-même dès la fin de la pluie et
    la formule fermée suffit. Avec un bassin amont, l'apport se poursuit — il
    peut même dépasser ce que le fond infiltre, et le niveau se maintient alors
    sur l'axe de l'ajutage au lieu de descendre : il faut intégrer.
    """
    if not projet.amont.actif or duree_min <= 0:
        return temps_vidange_h(volume_m3, debit_infiltration, debit_ajutage,
                               volume_sous_ajutage_m3)
    from . import simulation

    apport = simulation.hydrogramme_amont(projet, hauteur_mm, duree_min)
    q_direct = hauteur_mm * projet.aire_ponderee_m2 / (duree_min * 60.0)
    _, vidange = simulation.pic_et_vidange(q_direct, duree_min, apport, debit_infiltration,
                                           debit_ajutage, volume_sous_ajutage_m3)
    return vidange / 60.0 if vidange != float("inf") else float("inf")


def temps_vidange_h(volume_m3: float, q_infiltration_ls: float, q_ajutage_ls: float,
                    volume_sous_ajutage_m3: float = 0.0) -> float:
    """Temps de vidange gravitaire du volume stocké [h]."""
    if volume_m3 <= 0:
        return 0.0
    q_total = q_infiltration_ls + q_ajutage_ls
    if volume_sous_ajutage_m3 > 0:
        v_haut = max(volume_m3 - volume_sous_ajutage_m3, 0.0)
        v_bas = min(volume_m3, volume_sous_ajutage_m3)
        t = 0.0
        if v_haut > 0:
            if q_total <= 0:
                return float("inf")
            t += v_haut * 1000.0 / q_total / 3600.0
        if v_bas > 0:
            if q_infiltration_ls <= 0:
                return float("inf")
            t += v_bas * 1000.0 / q_infiltration_ls / 3600.0
        return t
    if q_total <= 0:
        return float("inf")
    return volume_m3 * 1000.0 / q_total / 3600.0


def _controles(projet: Projet, res: Resultat, scenario: str) -> None:
    from .model import DEBIT_FUITE_SPECIFIQUE_MAX_LS_HA, PERIODE_RETOUR_MINIMALE

    if projet.aire_ponderee_m2 <= 0:
        res.conforme = False
        res.alertes.append("Aucune surface incidente encodée : encodez au moins une surface.")
    if res.debit_sortant_ls <= 0:
        res.conforme = False
        res.alertes.append(
            "Aucun débit de sortie : le dispositif ne se vidange pas. "
            "Encodez une surface d'infiltration et/ou un débit d'ajutage."
        )
    if projet.periode_retour < PERIODE_RETOUR_MINIMALE:
        res.alertes.append(
            f"Le GTI recommande une période de retour >= {PERIODE_RETOUR_MINIMALE} ans "
            f"(actuellement {projet.periode_retour} ans)."
        )
    if projet.k_infiltration_ms > 1e-4 and scenario != SCENARIO_TEMPORISATION:
        res.alertes.append(
            "Coefficient d'infiltration K > 1e-4 m/s : valeur à vérifier par essai in situ."
        )
    if res.temps_vidange_h > projet.temps_vidange_max_h:
        res.conforme = False
        msg = (
            f"Temps de vidange de {res.temps_vidange_hm} supérieur au maximum admis "
            f"({projet.temps_vidange_max_h:.0f} h)."
        )
        if scenario in (SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL):
            msg += " La surface d'infiltration doit être augmentée."
        else:
            msg += " Le débit d'ajutage doit être augmente."
        res.alertes.append(msg)
        if (
            projet.surface_reference_m2 > 0
            and res.surface_infiltration_m2 / projet.surface_reference_m2 >= POURCENTAGE_SURFACE_INFILTRATION_LIMITE
        ):
            res.messages.append(
                "La surface d'infiltration atteint déjà 10 % de la surface de référence : "
                "le GTI admet ce cas comme un maximum raisonnable (rejet complémentaire a prévoir)."
            )
    if scenario in (SCENARIO_TEMPORISATION, SCENARIO_MIXTE, SCENARIO_SEUIL) and res.debit_ajutage_ls > 0:
        q_adm = projet.debit_fuite_admissible_ls
        if q_adm > 0 and res.debit_ajutage_ls > q_adm:
            res.alertes.append(
                f"Débit d'ajutage de {res.debit_ajutage_ls:.2f} l/s supérieur au débit de fuite "
                f"admissible de {q_adm:.2f} l/s "
                f"({DEBIT_FUITE_SPECIFIQUE_MAX_LS_HA:.0f} l/s/ha x {projet.aire_totale_m2:.0f} m²)."
            )


def _minima(projet: Projet, res: Resultat, scenario: str) -> None:
    if scenario in (SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL):
        res.surface_infiltration_min_m2 = surface_infiltration_minimale(projet, scenario)
    if scenario in (SCENARIO_TEMPORISATION, SCENARIO_MIXTE, SCENARIO_SEUIL):
        res.debit_ajutage_min_ls = debit_ajutage_minimal(projet, scenario)


# ---------------------------------------------------------------------------
# Recherche des minima (dichotomie)
# ---------------------------------------------------------------------------
def _temps_vidange_pour(projet: Projet, scenario: str, s_inf: float, q_aj: float,
                        serie: Optional[Tuple[Tuple[float, ...], Tuple[float, ...]]] = None) -> float:
    if serie is None:
        serie = serie_projet(projet)
    q_inf = debit_infiltration_ls(s_inf, projet.k_infiltration_ms, projet.coef_securite_infiltration)
    v_sous = projet.bassin.volume_sous_ajutage_m3 if scenario == SCENARIO_SEUIL else 0.0
    v, t, h = volume_de_dimensionnement(projet, serie, scenario, q_inf, q_aj)
    return temps_vidange_apres_pluie_h(projet, v, t, h, q_inf, q_aj, v_sous)


def surface_infiltration_minimale(projet: Projet, scenario: str, tolerance: float = 0.01) -> Optional[float]:
    """Plus petite surface d'infiltration respectant le temps de vidange maximal."""
    if projet.aire_ponderee_m2 <= 0 or projet.k_infiltration_ms <= 0:
        return None
    q_aj = projet.debit_ajutage_ls if scenario in (SCENARIO_MIXTE, SCENARIO_SEUIL) else 0.0
    cible = projet.temps_vidange_max_h
    serie = serie_projet(projet)
    if _temps_vidange_pour(projet, scenario, 0.0, q_aj, serie) <= cible:
        return 0.0
    lo, hi = 0.0, max(projet.aire_totale_m2, 100.0)
    for _ in range(60):
        if _temps_vidange_pour(projet, scenario, hi, q_aj, serie) <= cible:
            break
        hi *= 2.0
        if hi > 1e7:
            return None
    for _ in range(80):
        mid = 0.5 * (lo + hi)
        if _temps_vidange_pour(projet, scenario, mid, q_aj, serie) <= cible:
            hi = mid
        else:
            lo = mid
        if hi - lo < tolerance:
            break
    return hi


def debit_ajutage_minimal(projet: Projet, scenario: str, tolerance: float = 1e-4) -> Optional[float]:
    """Plus petit débit d'ajutage respectant le temps de vidange maximal."""
    if projet.aire_ponderee_m2 <= 0:
        return None
    s_inf = projet.surface_infiltration_m2 if scenario in (SCENARIO_MIXTE, SCENARIO_SEUIL) else 0.0
    cible = projet.temps_vidange_max_h
    serie = serie_projet(projet)
    if _temps_vidange_pour(projet, scenario, s_inf, 0.0, serie) <= cible:
        return 0.0
    lo, hi = 0.0, 1.0
    for _ in range(60):
        if _temps_vidange_pour(projet, scenario, s_inf, hi, serie) <= cible:
            break
        hi *= 2.0
        if hi > 1e6:
            return None
    for _ in range(80):
        mid = 0.5 * (lo + hi)
        if _temps_vidange_pour(projet, scenario, s_inf, mid, serie) <= cible:
            hi = mid
        else:
            lo = mid
        if hi - lo < tolerance:
            break
    return hi


def dimensionner_tous(projet: Projet) -> Dict[str, Resultat]:
    """Dimensionne les quatre scénarios étudiés."""
    return {
        s: dimensionner(projet, s)
        for s in (SCENARIO_TEMPORISATION, SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL)
    }


def courbe_volume(projet: Projet, scenario: str, n_points: int = 160) -> List[Tuple[float, float]]:
    """Courbe volume à maîtriser = f(durée de pluie), pour le graphique."""
    import math

    q_inf, q_aj = debits_scenario(projet, scenario)
    s_pond = projet.aire_ponderee_m2
    src = rainfall.SourcePluie(projet.commune_ins, projet.periode_retour, projet.source_pluie)
    v_sous = projet.bassin.volume_sous_ajutage_m3
    pts: List[Tuple[float, float]] = []
    lo, hi = math.log(DUREE_MIN), math.log(DUREE_MAX)
    for i in range(n_points):
        t = math.exp(lo + (hi - lo) * i / (n_points - 1))
        h = src.hauteur(t)
        v_in = h * s_pond / 1000.0
        if projet.amont.actif:
            # Même règle que le tableau des scénarios, sans quoi la courbe
            # passerait sous le volume de dimensionnement qu'elle annote.
            seuil = v_sous if scenario == SCENARIO_SEUIL else 0.0
            pts.append((t, volume_pointe_amont(projet, t, h, q_inf, q_aj, seuil)))
            continue
        if scenario == SCENARIO_SEUIL and v_sous > 0:
            pts.append((t, volume_pointe_seuil(v_in, t, q_inf, q_aj, v_sous)))
            continue
        v_out = (q_inf + q_aj) * t * 60.0 / 1000.0
        pts.append((t, max(v_in - v_out, 0.0)))
    return pts
