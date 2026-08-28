"""Etat applicatif partage par les vues."""

from __future__ import annotations

import json
import os
import tempfile
from typing import Callable, Dict, List, Optional, Tuple

from ..core import hydro, orifice, rainfall, simulation
from ..core.model import (
    Bassin,
    LIBELLES_SCENARIOS,
    Projet,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_SEUIL,
    SCENARIO_TEMPORISATION,
)
from ..reports.dossier import ORDRE_SCENARIOS, Dossier, construire

CLE_STOCKAGE = "hydrobassin.projet"


def _ecriture_possible(chemin: str) -> bool:
    """Vérifie réellement qu'on peut écrire dans ce répertoire."""
    try:
        os.makedirs(chemin, exist_ok=True)
        temoin = os.path.join(chemin, ".hydrobassin_test")
        with open(temoin, "w", encoding="utf-8") as fh:
            fh.write("ok")
        os.remove(temoin)
        return True
    except Exception:
        return False


def repertoires_candidats() -> List[str]:
    """Répertoires de destination possibles, du plus souhaitable au plus sûr."""
    candidats: List[str] = []
    # Android : dossier de téléchargement public, puis stockage applicatif fourni par Flet
    for public in ("/storage/emulated/0/Download", "/sdcard/Download"):
        if os.path.isdir(public):
            candidats.append(os.path.join(public, "HydroBassin"))
    for var in ("FLET_APP_STORAGE_DATA", "FLET_APP_STORAGE_TEMP"):
        base = os.environ.get(var)
        if base:
            candidats.append(os.path.join(base, "rapports"))
    # Bureau
    for base in (os.path.join(os.path.expanduser("~"), "Documents"), os.path.expanduser("~")):
        if os.path.isdir(base):
            candidats.append(os.path.join(base, "HydroBassin"))
    candidats.append(os.path.join(tempfile.gettempdir(), "HydroBassin"))
    vus = []
    for c in candidats:
        if c not in vus:
            vus.append(c)
    return vus


def repertoire_documents() -> str:
    """Premier répertoire réellement accessible en écriture."""
    for chemin in repertoires_candidats():
        if _ecriture_possible(chemin):
            return chemin
    return tempfile.gettempdir()


def diagnostic_stockage() -> List[Tuple[str, bool]]:
    """(répertoire, accessible en écriture) — affiché en cas de problème."""
    return [(c, _ecriture_possible(c)) for c in repertoires_candidats()]


class EtatApplication:
    """Projet courant, resultats derives et abonnements des vues."""

    def __init__(self) -> None:
        self.projet = Projet(surfaces=Projet.surfaces_par_defaut())
        self.projet.commune_ins = "63013"
        self.projet.commune_nom = "Bütgenbach"
        self.scenario_principal = SCENARIO_MIXTE
        self._abonnes: List[Callable[[], None]] = []
        self._resultats: Optional[Dict[str, hydro.Resultat]] = None
        self._simulation: Optional[simulation.ResultatSimulation] = None
        self._table: Optional[simulation.TableAcceptation] = None

    # -- abonnements -------------------------------------------------------
    def abonner(self, rappel: Callable[[], None]) -> None:
        self._abonnes.append(rappel)

    def invalider(self) -> None:
        """Invalide les resultats et previent les vues."""
        self._resultats = None
        self._simulation = None
        self._table = None
        for rappel in list(self._abonnes):
            rappel()

    # -- resultats ---------------------------------------------------------
    @property
    def resultats(self) -> Dict[str, hydro.Resultat]:
        if self._resultats is None:
            self._resultats = {s: hydro.dimensionner(self.projet, s) for s in ORDRE_SCENARIOS}
        return self._resultats

    @property
    def resultats_disponibles(self) -> bool:
        """Vrai si les résultats sont déjà en cache (pas de calcul déclenché)."""
        return self._resultats is not None

    @property
    def resultat(self) -> hydro.Resultat:
        return self.resultats[self.scenario_principal]

    @property
    def bassin(self) -> Bassin:
        return self.projet.bassin

    @property
    def simulation(self) -> Optional[simulation.ResultatSimulation]:
        if self._simulation is None and self.bassin_valide:
            duree, hauteur = simulation.evenement_critique(self.projet, self.bassin)
            self._simulation = simulation.simuler(self.projet, self.bassin, hauteur, duree)
        return self._simulation

    @property
    def table_acceptation(self) -> Optional[simulation.TableAcceptation]:
        if self._table is None and self.bassin_valide:
            self._table = simulation.table_acceptation(self.projet, self.bassin)
        return self._table

    @property
    def bassin_valide(self) -> bool:
        return self.bassin.volume_total_m3 > 0 and self.projet.aire_ponderee_m2 > 0

    @property
    def orifice(self) -> Optional[orifice.ResultatOrifice]:
        q = self.bassin.debit_ajutage_ls or self.projet.debit_ajutage_ls
        if q <= 0 or self.projet.hauteur_charge_m <= 0:
            return None
        return orifice.dimensionner_orifice(q, self.projet.hauteur_charge_m, self.projet.coef_debit_orifice)

    def dossier(self) -> Dossier:
        return construire(self.projet, self.scenario_principal)

    # -- modifications -----------------------------------------------------
    def definir_commune(self, commune: rainfall.Commune) -> None:
        self.projet.commune_ins = commune.ins
        self.projet.commune_nom = commune.nom
        if not commune.a_montana:
            self.projet.source_pluie = rainfall.SOURCE_QDF
        self.invalider()

    def definir(self, champ: str, valeur) -> None:
        setattr(self.projet, champ, valeur)
        self.invalider()

    def definir_bassin(self, champ: str, valeur) -> None:
        setattr(self.projet.bassin, champ, valeur)
        self.invalider()

    def reprendre_dimensionnement(self) -> None:
        """Pre-remplit l'ouvrage avec le resultat du scenario retenu."""
        res = self.resultat
        b = self.projet.bassin
        b.volume_total_m3 = round(res.volume_m3 * 1.05, 1)
        b.surface_dispersion_m2 = (
            self.projet.surface_infiltration_m2
            if self.scenario_principal != SCENARIO_TEMPORISATION else 0.0
        )
        b.debit_ajutage_ls = (
            self.projet.debit_ajutage_ls if self.scenario_principal != SCENARIO_DISPERSION else 0.0
        )
        self.invalider()

    # -- persistance -------------------------------------------------------
    def to_json(self) -> str:
        return json.dumps(
            {"projet": self.projet.to_dict(), "scenario": self.scenario_principal},
            ensure_ascii=False,
        )

    def charger_json(self, texte: str) -> bool:
        try:
            data = json.loads(texte)
            self.projet = Projet.from_dict(data["projet"])
            self.scenario_principal = data.get("scenario", SCENARIO_MIXTE)
        except Exception:
            return False
        self.invalider()
        return True

    def nom_fichier(self, extension: str) -> str:
        base = (self.projet.nom_projet or f"bassin_{self.projet.commune_nom}").strip()
        base = "".join(c if c.isalnum() or c in "-_ " else "_" for c in base).replace(" ", "_")
        return os.path.join(repertoire_documents(), f"{base}_T{self.projet.periode_retour}.{extension}")
