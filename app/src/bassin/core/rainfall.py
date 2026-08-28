"""Accès àux pluies statistiques du GTI (Guide Technique d'Infiltration, RW).

Deux jeux de données sont embarqués dans l'application :

* **Montana** : coefficients a/b par commune (563 communes belges) et par période
  de retour. L'intensité s'écrit ``i[mm/h] = a * t[min]^(-b)`` avec trois jeux de
  coefficients selon la durée :

  ==================  ==================
  durée t             coefficients
  ==================  ==================
  t < 25 min          a1, b1
  25 <= t <= 6000 min a2, b2
  t > 6000 min        a3, b3
  ==================  ==================

  Le modèle de Montana est continu : il permet de balayer toutes les durées de
  pluie et donc de trouver la durée critique exacte.

* **QDF** : hauteurs de pluie mesurées (mm) pour 19 durées normalisées et
  262 communes wallonnes. Utilisé pour l'affichage des tableaux QDF et comme
  source alternative de calcul.
"""

from __future__ import annotations

import gzip
import json
import os
import sys
from dataclasses import dataclass
from functools import lru_cache
from typing import Dict, List, Optional, Sequence, Tuple

DATA_FILE = "gti_rainfall.json.gz"

#: Periodes de retour disponibles dans le GTI (annees).
RETURN_PERIODS: Tuple[int, ...] = (2, 5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 200)

#: Durées normalisées des tableaux QDF, en minutes.
QDF_DURATIONS_MIN: Tuple[int, ...] = (
    10, 20, 30, 60, 120, 180, 360, 720,
    1440, 2880, 4320, 5760, 7200, 10080, 14400, 21600, 28800, 36000, 43200,
)

#: Libellés des durées QDF.
QDF_DURATION_LABELS: Tuple[str, ...] = (
    "10 min", "20 min", "30 min", "1 h", "2 h", "3 h", "6 h", "12 h",
    "1 j", "2 j", "3 j", "4 j", "5 j", "7 j", "10 j", "15 j", "20 j", "25 j", "30 j",
)


def _data_path() -> str:
    """Chemin du jeu de données, y compris dans une application empaquetée."""
    candidats = [os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", DATA_FILE)]
    base = getattr(sys, "_MEIPASS", None)  # exécutable PyInstaller
    if base:
        candidats.append(os.path.join(base, "bassin", "data", DATA_FILE))
        candidats.append(os.path.join(base, "data", DATA_FILE))
    for chemin in candidats:
        if os.path.exists(chemin):
            return chemin
    return candidats[0]


#: Renseigne d'où proviennent effectivement les données (diagnostic).
SOURCE_DONNEES = {"origine": "inconnue"}


def _octets_donnees() -> bytes:
    """Contenu compressé du référentiel GTI.

    Trois sources successives, car l'empaquetage diffère selon la cible
    (fichier sur disque en développement et sous Windows, ressource de paquet
    sur Android et sur le web, module Python en dernier recours).
    """
    try:
        from importlib import resources

        octets = resources.files("bassin.data").joinpath(DATA_FILE).read_bytes()
        SOURCE_DONNEES["origine"] = "ressource de paquet"
        return octets
    except Exception:
        pass
    chemin = _data_path()
    if os.path.exists(chemin):
        with open(chemin, "rb") as fh:
            SOURCE_DONNEES["origine"] = f"fichier {chemin}"
            return fh.read()
    from ..data.gti_embarque import DONNEES  # repli embarqué dans le code

    SOURCE_DONNEES["origine"] = "module embarqué"
    return DONNEES


@lru_cache(maxsize=1)
def _raw() -> dict:
    return json.loads(gzip.decompress(_octets_donnees()).decode("utf-8"))


@dataclass(frozen=True)
class Commune:
    """Une commune du référentiel GTI."""

    ins: str
    nom: str
    wallonne: bool
    a_qdf: bool
    a_montana: bool

    @property
    def label(self) -> str:
        return f"{self.nom} ({self.ins})"


@lru_cache(maxsize=1)
def communes() -> Tuple[Commune, ...]:
    """Toutes les communes disponibles, triées par nom."""
    items = [
        Commune(
            ins=ins,
            nom=c["n"],
            wallonne=bool(c["w"]),
            a_qdf=bool(c["q"]),
            a_montana=bool(c["m"]),
        )
        for ins, c in _raw()["communes"].items()
    ]
    items.sort(key=lambda c: (_fold(c.nom), c.ins))
    return tuple(items)


@lru_cache(maxsize=1)
def communes_wallonnes() -> Tuple[Commune, ...]:
    return tuple(c for c in communes() if c.wallonne)


def _fold(text: str) -> str:
    """Normalisation simple pour le tri et la recherche (sans unicodedata)."""
    table = str.maketrans(
        "ÀÁÂÃÄÅàáâãäåÈÉÊËèéêëÌÍÎÏìíîïÒÓÔÕÖòóôõöÙÚÛÜùúûüÇçÑñ",
        "AAAAAAaaaaaaEEEEeeeeIIIIiiiiOOOOOoooooUUUUuuuuCcNn",
    )
    return text.upper().translate(table)


def rechercher_communes(motif: str, limite: int = 40, wallonnes_seulement: bool = False) -> List[Commune]:
    """Recherche insensible aux accents/casse sur le nom ou le code INS."""
    src = communes_wallonnes() if wallonnes_seulement else communes()
    motif = _fold(motif.strip())
    if not motif:
        return list(src[:limite])
    debut = [c for c in src if _fold(c.nom).startswith(motif) or c.ins.startswith(motif)]
    contient = [c for c in src if c not in debut and (motif in _fold(c.nom) or motif in c.ins)]
    return (debut + contient)[:limite]


def commune_par_ins(ins: str) -> Optional[Commune]:
    for c in communes():
        if c.ins == ins:
            return c
    return None


# --------------------------------------------------------------------------
# Montana
# --------------------------------------------------------------------------
def montana_coeffs(ins: str, periode_retour: int) -> Tuple[float, float, float, float, float, float]:
    """(a1, b1, a2, b2, a3, b3) pour une commune et une période de retour."""
    try:
        return tuple(_raw()["montana"][ins][str(int(periode_retour))])  # type: ignore[return-value]
    except KeyError as exc:
        raise KeyError(f"Pas de coefficients Montana pour INS={ins}, T={periode_retour} ans") from exc


def intensite_montana(ins: str, periode_retour: int, duree_min: float) -> float:
    """Intensité de pluie [mm/h] pour une durée en minutes."""
    if duree_min <= 0:
        return 0.0
    a1, b1, a2, b2, a3, b3 = montana_coeffs(ins, periode_retour)
    if duree_min < 25:
        a, b = a1, b1
    elif duree_min <= 6000:
        a, b = a2, b2
    else:
        a, b = a3, b3
    return a * duree_min ** (-b)


def hauteur_montana(ins: str, periode_retour: int, duree_min: float) -> float:
    """Hauteur de pluie [mm] cumulée sur la durée (mm/h * h)."""
    return intensite_montana(ins, periode_retour, duree_min) * duree_min / 60.0


# --------------------------------------------------------------------------
# QDF
# --------------------------------------------------------------------------
def a_donnees_qdf(ins: str) -> bool:
    return ins in _raw()["qdf"]


def a_donnees_montana(ins: str) -> bool:
    return ins in _raw()["montana"]


def hauteurs_qdf(ins: str, periode_retour: int) -> List[Optional[float]]:
    """Hauteurs de pluie [mm] pour les 19 durées normalisées."""
    table = _raw()["qdf"].get(ins)
    if table is None:
        raise KeyError(f"Pas de données QDF pour INS={ins}")
    return list(table[str(int(periode_retour))])


def hauteur_qdf(ins: str, periode_retour: int, duree_min: float) -> float:
    """Hauteur de pluie [mm] interpolée (log-log) dans la table QDF."""
    vals = hauteurs_qdf(ins, periode_retour)
    pts = [(d, v) for d, v in zip(QDF_DURATIONS_MIN, vals) if v is not None]
    if not pts:
        return 0.0
    if duree_min <= pts[0][0]:
        return pts[0][1] * (duree_min / pts[0][0])
    if duree_min >= pts[-1][0]:
        return pts[-1][1]
    for (d0, v0), (d1, v1) in zip(pts, pts[1:]):
        if d0 <= duree_min <= d1:
            import math

            if v0 <= 0 or v1 <= 0:
                return v0 + (v1 - v0) * (duree_min - d0) / (d1 - d0)
            f = (math.log(duree_min) - math.log(d0)) / (math.log(d1) - math.log(d0))
            return math.exp(math.log(v0) + f * (math.log(v1) - math.log(v0)))
    return pts[-1][1]


# --------------------------------------------------------------------------
# Source de pluie unifiee
# --------------------------------------------------------------------------
SOURCE_MONTANA = "montana"
SOURCE_QDF = "qdf"


class SourcePluie:
    """Fournit la hauteur de pluie [mm] pour une commune / récurrence / durée."""

    def __init__(self, ins: str, periode_retour: int, source: str = SOURCE_MONTANA):
        if source == SOURCE_QDF and not a_donnees_qdf(ins):
            source = SOURCE_MONTANA
        if source == SOURCE_MONTANA and not a_donnees_montana(ins):
            # 11 communes wallonnes ne disposent pas des coefficients de Montana
            # dans le GTI : on bascule automatiquement sur les tables QDF.
            source = SOURCE_QDF
        self.ins = ins
        self.periode_retour = int(periode_retour)
        self.source = source

    def hauteur(self, duree_min: float) -> float:
        if self.source == SOURCE_QDF:
            return hauteur_qdf(self.ins, self.periode_retour, duree_min)
        return hauteur_montana(self.ins, self.periode_retour, duree_min)

    def intensite_mmh(self, duree_min: float) -> float:
        if duree_min <= 0:
            return 0.0
        return self.hauteur(duree_min) * 60.0 / duree_min

    def intensite_ls_ha(self, duree_min: float) -> float:
        """Intensité en l/s/ha (1 mm/h = 2.7778 l/s/ha)."""
        return self.intensite_mmh(duree_min) * 10000.0 / 3600.0

    @property
    def libelle_source(self) -> str:
        return "Montana (formule continue)" if self.source == SOURCE_MONTANA else "QDF (valeurs tabulées)"


def table_qdf_mm(ins: str, source: str = SOURCE_QDF) -> List[List[Optional[float]]]:
    """Tableau QDF en mm : lignes = durées normalisées, colonnes = périodes de retour."""
    lignes: List[List[Optional[float]]] = []
    for i, duree in enumerate(QDF_DURATIONS_MIN):
        ligne: List[Optional[float]] = []
        for rp in RETURN_PERIODS:
            src = SourcePluie(ins, rp, source)
            ligne.append(hauteurs_qdf(ins, rp)[i] if src.source == SOURCE_QDF else hauteur_montana(ins, rp, duree))
        lignes.append(ligne)
    return lignes


def table_qdf_ls_ha(ins: str, source: str = SOURCE_QDF) -> List[List[Optional[float]]]:
    """Même tableau converti en débit spécifique [l/s/ha]."""
    mm = table_qdf_mm(ins, source)
    out: List[List[Optional[float]]] = []
    for duree, ligne in zip(QDF_DURATIONS_MIN, mm):
        out.append([None if v is None else v / (duree * 60.0) * 10000.0 for v in ligne])
    return out
