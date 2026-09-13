"""Dimensionnement de l'ajutage (orifice calibré en paroi mince) - Torricelli.

Débit d'un orifice noyé en paroi mince :

.. code::

    Q [m³/s] = Cd * A [m²] * sqrt( 2 * g * h [m] )

*h* est la charge disponible, mesurée entre l'axe de l'orifice et le niveau du
trop-plein (charge maximale). Le débit d'ajutage est suppose constant sur toute
la phase de remplissage au-delà de l'orifice, conformement à l'hypothèse de
dimensionnement retenue.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List, Optional, Tuple

G = 9.81

#: Coefficients de débit usuels.
COEFFICIENTS_DEBIT = (
    ("Orifice en paroi mince (usuel)", 0.60),
    ("Orifice en paroi mince, arête vive", 0.61),
    ("Ajutage cylindrique extérieur", 0.82),
    ("Ajutage conique convergent", 0.94),
    ("Orifice avec grille de protection", 0.50),
)

#: Diametres commerciaux courants (mm) pour les plaques d'ajutage.
DIAMETRES_COMMERCIAUX_MM = (
    20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 90, 100,
    110, 125, 140, 150, 160, 175, 200, 225, 250, 300,
)


@dataclass
class ResultatOrifice:
    debit_ls: float
    charge_m: float
    coef_debit: float
    section_m2: float
    diametre_mm: float
    vitesse_ms: float
    diametre_commercial_mm: Optional[int] = None
    debit_commercial_ls: Optional[float] = None

    @property
    def section_cm2(self) -> float:
        return self.section_m2 * 1e4


def section_requise_m2(debit_ls: float, charge_m: float, coef_debit: float = 0.60) -> float:
    """Section d'orifice nécessaire pour évacuer un débit sous une charge donnée."""
    if debit_ls <= 0 or charge_m <= 0 or coef_debit <= 0:
        return 0.0
    return (debit_ls / 1000.0) / (coef_debit * math.sqrt(2.0 * G * charge_m))


def diametre_requis_mm(debit_ls: float, charge_m: float, coef_debit: float = 0.60) -> float:
    """Diamètre d'orifice circulaire equivalent [mm]."""
    a = section_requise_m2(debit_ls, charge_m, coef_debit)
    if a <= 0:
        return 0.0
    return 2.0 * math.sqrt(a / math.pi) * 1000.0


def debit_orifice_ls(diametre_mm: float, charge_m: float, coef_debit: float = 0.60) -> float:
    """Débit [l/s] d'un orifice circulaire sous une charge donnée."""
    if diametre_mm <= 0 or charge_m <= 0:
        return 0.0
    a = math.pi * (diametre_mm / 1000.0) ** 2 / 4.0
    return coef_debit * a * math.sqrt(2.0 * G * charge_m) * 1000.0


def vitesse_ms(charge_m: float, coef_debit: float = 0.60) -> float:
    """Vitesse moyenne dans l'orifice (Cv ~ Cd pour une paroi mince)."""
    if charge_m <= 0:
        return 0.0
    return coef_debit * math.sqrt(2.0 * G * charge_m)


def dimensionner_orifice(debit_ls: float, charge_m: float, coef_debit: float = 0.60,
                         diametres: Tuple[int, ...] = DIAMETRES_COMMERCIAUX_MM) -> ResultatOrifice:
    """Dimensionne l'orifice et propose le diamètre commercial immédiatement inférieur.

    On retient le diamètre commercial le plus proche par defaut afin de ne pas
    dépasser le débit de fuite autorisé.
    """
    a = section_requise_m2(debit_ls, charge_m, coef_debit)
    d = diametre_requis_mm(debit_ls, charge_m, coef_debit)
    res = ResultatOrifice(
        debit_ls=debit_ls,
        charge_m=charge_m,
        coef_debit=coef_debit,
        section_m2=a,
        diametre_mm=d,
        vitesse_ms=vitesse_ms(charge_m, coef_debit),
    )
    candidats = [dc for dc in diametres if dc <= d]
    if candidats:
        res.diametre_commercial_mm = candidats[-1]
        res.debit_commercial_ls = debit_orifice_ls(candidats[-1], charge_m, coef_debit)
    elif diametres:
        res.diametre_commercial_mm = diametres[0]
        res.debit_commercial_ls = debit_orifice_ls(diametres[0], charge_m, coef_debit)
    return res


def abaque_diametres(charge_m: float, coef_debit: float = 0.60,
                     diametres: Tuple[int, ...] = DIAMETRES_COMMERCIAUX_MM) -> List[Tuple[int, float, float]]:
    """Abaque (diamètre [mm], section [cm²], débit [l/s]) pour la charge donnée."""
    out = []
    for d in diametres:
        a = math.pi * (d / 1000.0) ** 2 / 4.0
        out.append((d, a * 1e4, debit_orifice_ls(d, charge_m, coef_debit)))
    return out


def courbe_hauteur_debit(diametre_mm: float, charge_max_m: float, coef_debit: float = 0.60,
                         n: int = 50) -> List[Tuple[float, float]]:
    """Courbe Q = f(h) réelle de l'orifice (pour comparaison avec l'hypothèse Q constant)."""
    if charge_max_m <= 0:
        return []
    return [
        (h, debit_orifice_ls(diametre_mm, h, coef_debit))
        for h in (charge_max_m * i / n for i in range(n + 1))
    ]
