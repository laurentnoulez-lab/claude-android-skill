"""Modèle de données du projet (surfaces, sol, ouvrage)."""

from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional

#: Coefficients de ruissellement du GTI par type d'occupation du sol.
TYPES_SURFACES = (
    ("Forêts, bois", 0.05),
    ("Prairies, jardins, zones enherbées", 0.15),
    ("Champs cultivés, landes, bruyères", 0.25),
    ("Dalles gazon, toitures vertes", 0.40),
    ("Terres battues, chemins de terre", 0.50),
    ("Pavés à joints écartés, pavés drainants", 0.70),
    ("Allées pavées, trottoirs, graviers compactés", 0.90),
    ("Toitures, routes, plans d'eau, surfaces imperméables", 1.00),
)

#: Contraintes réglementaires du GTI.
TEMPS_VIDANGE_LIMITE_H = 48.0
DEBIT_FUITE_SPECIFIQUE_MAX_LS_HA = 5.0
POURCENTAGE_SURFACE_INFILTRATION_LIMITE = 0.10
PERIODE_RETOUR_MINIMALE = 25
COEF_SECURITE_INFILTRATION = 2.0

SCENARIO_TEMPORISATION = "temporisation"
SCENARIO_DISPERSION = "dispersion"
SCENARIO_MIXTE = "mixte"
SCENARIO_SEUIL = "seuil"

LIBELLES_SCENARIOS = {
    SCENARIO_TEMPORISATION: "Temporisation seule (sans dispersion)",
    SCENARIO_DISPERSION: "Dispersion seule (sans exutoire ajuté)",
    SCENARIO_MIXTE: "Temporisation et dispersion (infiltration + orifice calibré)",
    SCENARIO_SEUIL: "Dispersion seule avec temporisation au-delà d'un seuil",
}


@dataclass
class SurfaceIncidente:
    """Une surface incidente du projet."""

    libelle: str
    coefficient: float
    aire_m2: float = 0.0
    note: str = ""

    @property
    def aire_ponderee_m2(self) -> float:
        return self.coefficient * self.aire_m2


@dataclass
class Bassin:
    """Ouvrage encodé par l'utilisateur (vérification / simulation)."""

    volume_total_m3: float = 0.0
    volume_sous_ajutage_m3: float = 0.0
    surface_dispersion_m2: float = 0.0
    debit_ajutage_ls: float = 0.0

    @property
    def volume_tampon_m3(self) -> float:
        """Volume utile situé au-dessus de l'ajutage."""
        return max(self.volume_total_m3 - self.volume_sous_ajutage_m3, 0.0)


@dataclass
class BassinAmont:
    """Bassin d'orage situé en amont, qui se déverse dans l'ouvrage étudié.

    Son propre bassin versant ruisselle vers lui pendant la même averse ; il
    tamponne puis restitue à son débit de fuite, lequel devient un apport
    supplémentaire pour le bassin aval.
    """

    actif: bool = False
    surface_bv_m2: float = 0.0
    coef_ruissellement: float = 0.9
    debit_ajutage_ls: float = 0.0
    surface_dispersion_m2: float = 0.0
    k_infiltration_ms: float = 1e-5
    volume_temporisation_m3: float = 0.0
    #: La surface du bassin versant amont compte-t-elle pour le débit de fuite
    #: admissible et l'ajutage spécifique du bassin aval ?
    inclure_bv_dans_ajutage: bool = False

    @property
    def aire_ponderee_m2(self) -> float:
        return max(self.surface_bv_m2, 0.0) * max(self.coef_ruissellement, 0.0)

    def debit_infiltration_ls(self, coef_securite: float = COEF_SECURITE_INFILTRATION) -> float:
        return debit_infiltration_ls(self.surface_dispersion_m2, self.k_infiltration_ms, coef_securite)

    def debit_sortant_ls(self, coef_securite: float = COEF_SECURITE_INFILTRATION) -> float:
        """Débit restitué vers l'aval : ajutage + infiltration."""
        return self.debit_ajutage_ls + self.debit_infiltration_ls(coef_securite)


@dataclass
class Projet:
    """Ensemble des données d'entrée d'un dimensionnement."""

    commune_ins: str = "63013"
    commune_nom: str = "Butgenbach"
    periode_retour: int = 25
    source_pluie: str = "montana"

    surface_reference_m2: float = 0.0
    surfaces: List[SurfaceIncidente] = field(default_factory=list)

    # Sol et exutoire
    k_infiltration_ms: float = 1e-5
    coef_securite_infiltration: float = COEF_SECURITE_INFILTRATION
    surface_infiltration_m2: float = 0.0
    debit_ajutage_ls: float = 0.0
    temps_vidange_max_h: float = TEMPS_VIDANGE_LIMITE_H

    # Ouvrage a verifier
    bassin: Bassin = field(default_factory=Bassin)

    # Bassin d'orage amont eventuel
    amont: BassinAmont = field(default_factory=BassinAmont)

    # Ajutage (Torricelli)
    hauteur_charge_m: float = 1.0
    coef_debit_orifice: float = 0.60

    # Identification
    nom_projet: str = ""
    auteur: str = ""
    localisation: str = ""
    remarques: str = ""

    # ---- grandeurs derivees -------------------------------------------------
    @property
    def aire_totale_m2(self) -> float:
        return sum(s.aire_m2 for s in self.surfaces)

    @property
    def aire_ponderee_m2(self) -> float:
        return sum(s.aire_ponderee_m2 for s in self.surfaces)

    @property
    def coefficient_moyen(self) -> float:
        tot = self.aire_totale_m2
        return self.aire_ponderee_m2 / tot if tot > 0 else 0.0

    @property
    def aire_raccordee_m2(self) -> float:
        """Surface raccordée à l'ouvrage, bassin versant amont compris s'il est pris en compte.

        C'est cette surface qui sert au débit de fuite admissible et à la
        conversion de l'ajutage en l/(s·ha).
        """
        aire = self.aire_totale_m2
        if self.amont.actif and self.amont.inclure_bv_dans_ajutage:
            aire += max(self.amont.surface_bv_m2, 0.0)
        return aire

    @property
    def debit_fuite_admissible_ls(self) -> float:
        """Débit de rejet maximal admissible (5 l/s/ha de surface raccordée)."""
        return DEBIT_FUITE_SPECIFIQUE_MAX_LS_HA * self.aire_raccordee_m2 / 10000.0

    def surfaces_non_vides(self) -> List[SurfaceIncidente]:
        return [s for s in self.surfaces if s.aire_m2 > 0]

    @staticmethod
    def surfaces_par_defaut() -> List[SurfaceIncidente]:
        return [SurfaceIncidente(libelle=l, coefficient=c) for l, c in TYPES_SURFACES]

    # ---- serialisation ------------------------------------------------------
    def to_dict(self) -> Dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict) -> "Projet":
        data = dict(data)
        surfaces = [SurfaceIncidente(**s) for s in data.pop("surfaces", [])]
        bassin = Bassin(**_champs_connus(Bassin, data.pop("bassin", {})))
        amont = BassinAmont(**_champs_connus(BassinAmont, data.pop("amont", {})))
        champs = {f for f in cls.__dataclass_fields__}  # type: ignore[attr-defined]
        propre = {k: v for k, v in data.items() if k in champs}
        return cls(surfaces=surfaces, bassin=bassin, amont=amont, **propre)


def _champs_connus(classe, data: Dict) -> Dict:
    """Filtre un dictionnaire enregistré sur les champs actuels d'une dataclasse.

    Un projet enregistré par une version antérieure ne connaît pas les champs
    ajoutés depuis : il doit pouvoir se recharger malgré tout.
    """
    champs = set(classe.__dataclass_fields__)
    return {k: v for k, v in dict(data).items() if k in champs}


def debit_infiltration_ls(surface_m2: float, k_ms: float, coef_securite: float = COEF_SECURITE_INFILTRATION) -> float:
    """Débit d'infiltration [l/s] : Q = 1000 * S * K / coef. de sécurité.

    Le GTI impose un coefficient de sécurité de 2 sur la perméabilité mesurée.
    """
    if surface_m2 <= 0 or k_ms <= 0:
        return 0.0
    return 1000.0 * surface_m2 * k_ms / max(coef_securite, 1e-9)


def surface_infiltration_requise_m2(debit_ls: float, k_ms: float, coef_securite: float = COEF_SECURITE_INFILTRATION) -> float:
    """Surface d'infiltration [m²] nécessaire pour évacuer un débit donné."""
    if debit_ls <= 0 or k_ms <= 0:
        return 0.0
    return debit_ls * coef_securite / (1000.0 * k_ms)
