"""Etat applicatif partage par les vues."""

from __future__ import annotations

import json
import os
import tempfile
from typing import Callable, Dict, List, Optional, Tuple

from ..core import hydro, orifice, rainfall, reseau, simulation
from ..core.model import (
    Bassin,
    LIBELLES_SCENARIOS,
    Projet,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_TEMPORISATION,
)
from ..core.reseau import BassinVersant, Ouvrage, Systeme
from ..reports.dossier import ORDRE_SCENARIOS, Dossier, construire

CLE_STOCKAGE = "hydrobassin.projet"

#: Marqueur écrit dans les fichiers de projet, pour reconnaître ce qu'on ouvre.
MARQUE_FICHIER = "HydroBassin"
#: 1 : un seul bassin d'orage (versions 1.x et 2.x). 2 : réseau de bassins.
VERSION_FICHIER = 2
EXTENSION_PROJET = "json"

#: Champs communs à tout le système : les modifier sur une étude d'ouvrage
#: n'aurait aucun effet, la synchronisation les réécrirait aussitôt.
#: Au-delà, la recherche des minima de chaque ouvrage (deux dichotomies par
#: bassin, chacune rebalayant les durées) devient trop lente pour se refaire à
#: chaque frappe : elle n'est alors calculée que sur demande.
SEUIL_MINIMA_AUTOMATIQUES = 6

CHAMPS_GLOBAUX = frozenset({
    "commune_ins", "commune_nom", "periode_retour", "source_pluie",
    "coef_securite_infiltration", "temps_vidange_max_h",
    "nom_projet", "auteur", "localisation", "remarques",
})


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


def destination_utilisable(chemin: str) -> bool:
    """Le chemin renvoyé par le sélecteur du système est-il vraiment ouvrable ?

    Sous Android, le sélecteur renvoie un URI du Storage Access Framework, de la
    forme ``/document/primary:Documents/projet.json``. Ce n'est pas un chemin de
    fichier : Python ne peut ni l'ouvrir ni y copier quoi que ce soit, et
    l'utilisateur ne récoltait qu'un « No such file or directory » alors que son
    projet était bel et bien enregistré ailleurs.

    Le test est direct : peut-on écrire dans le répertoire visé ?
    """
    if not chemin:
        return False
    repertoire = os.path.dirname(chemin)
    if not repertoire:
        return False
    return os.path.isdir(repertoire) and os.access(repertoire, os.W_OK)


def source_utilisable(chemin: str) -> bool:
    """Le fichier désigné par le sélecteur est-il réellement lisible ?"""
    return bool(chemin) and os.path.isfile(chemin) and os.access(chemin, os.R_OK)


def diagnostic_stockage() -> List[Tuple[str, bool]]:
    """(répertoire, accessible en écriture) — affiché en cas de problème."""
    return [(c, _ecriture_possible(c)) for c in repertoires_candidats()]


class EtatApplication:
    """Système courant, résultats dérivés et abonnements des vues.

    Le projet n'est plus un bassin isolé mais un :class:`~bassin.core.reseau.Systeme`
    de bassins versants et de bassins d'orage. Les onglets de détail travaillent
    sur l'**ouvrage courant**, dont l'étude est un
    :class:`~bassin.core.model.Projet` complet : le moteur de dimensionnement du
    bassin isolé s'y applique tel quel, apport des ouvrages amont compris.
    """

    def __init__(self) -> None:
        self.systeme = reseau.systeme_neuf()
        self._abonnes: List[Callable[[], None]] = []
        self._resultats: Optional[Dict[str, hydro.Resultat]] = None
        self._simulation: Optional[simulation.ResultatSimulation] = None
        self._table: Optional[simulation.TableAcceptation] = None
        self._fiches: Optional[List[reseau.FicheOuvrage]] = None
        self._fiches_minima: Optional[List[reseau.FicheOuvrage]] = None
        self._simulation_systeme: Optional[reseau.SimulationSysteme] = None
        #: L'utilisateur a demandé les minima sur un réseau où ils ne sont pas
        #: calculés d'office.
        self.minima_demandes = False
        #: Dossier choisi pour les livrables. Vide : celui de l'application.
        #: Réglage de session, hors du projet enregistré — il décrit le poste,
        #: pas l'étude, et n'aurait aucun sens chez un confrère.
        self.dossier_livrables = ""

    # -- abonnements -------------------------------------------------------
    def abonner(self, rappel: Callable[[], None]) -> None:
        self._abonnes.append(rappel)

    def desabonner(self, rappel: Callable[[], None]) -> None:
        """Une fenêtre refermée ne doit plus être rafraîchie."""
        if rappel in self._abonnes:
            self._abonnes.remove(rappel)

    def invalider(self) -> None:
        """Remet le système en cohérence, invalide les résultats, prévient les vues."""
        self._resultats = None
        self._simulation = None
        self._table = None
        self._fiches = None
        self._fiches_minima = None
        self._simulation_systeme = None
        self.systeme.synchroniser()
        for rappel in list(self._abonnes):
            rappel()

    # -- ouvrage courant ---------------------------------------------------
    @property
    def ouvrage(self) -> Ouvrage:
        return self.systeme.courant

    @property
    def ouvrages(self) -> List[Ouvrage]:
        return self.systeme.ouvrages

    @property
    def versants(self) -> List[BassinVersant]:
        return self.systeme.bassins_versants

    def choisir_ouvrage(self, identifiant: str) -> None:
        if self.systeme.ouvrage(identifiant) is not None:
            self.systeme.ouvrage_courant = identifiant
            self.invalider()

    @property
    def projet(self) -> Projet:
        """Étude de l'ouvrage courant."""
        return self.systeme.courant.etude

    @projet.setter
    def projet(self, projet: Projet) -> None:
        """Installe une étude de bassin isolé sur l'ouvrage courant.

        Sert à charger un exemple ou à repartir d'un projet construit à la main :
        les surfaces du projet deviennent celles du bassin versant raccordé, et
        ses données générales celles du système.
        """
        systeme = self.systeme
        ouvrage = systeme.courant
        ouvrage.etude = projet
        for champ in CHAMPS_GLOBAUX:
            setattr(systeme, champ, getattr(projet, champ))
        versants = systeme.versants_de(ouvrage.id)
        for en_trop in versants[1:]:
            systeme.bassins_versants.remove(en_trop)
        if versants:
            versant = versants[0]
        else:
            versant = reseau.versant_neuf(systeme, "Bassin versant", ouvrage.id)
            systeme.bassins_versants.append(versant)
        versant.surfaces = projet.surfaces
        versant.surface_reference_m2 = projet.surface_reference_m2
        self.invalider()

    @property
    def scenario_principal(self) -> str:
        return self.systeme.courant.scenario

    @scenario_principal.setter
    def scenario_principal(self, scenario: str) -> None:
        self.systeme.courant.scenario = scenario

    # -- resultats ---------------------------------------------------------
    @property
    def resultats(self) -> Dict[str, hydro.Resultat]:
        if self._resultats is None:
            self.systeme.synchroniser()
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
            self.systeme.synchroniser()
            self._simulation = simulation.simuler_evenement_critique(self.projet, self.bassin)
        return self._simulation

    @property
    def table_acceptation(self) -> Optional[simulation.TableAcceptation]:
        if self._table is None and self.bassin_valide:
            self.systeme.synchroniser()
            self._table = simulation.table_acceptation(self.projet, self.bassin)
        return self._table

    @property
    def bassin_valide(self) -> bool:
        return self.bassin.volume_total_m3 > 0 and self.projet.a_un_apport

    # -- réseau ------------------------------------------------------------
    @property
    def minima_disponibles(self) -> bool:
        """Les minima par ouvrage sont-ils calculés dans les fiches du réseau ?"""
        return (len(self.systeme.ouvrages) <= SEUIL_MINIMA_AUTOMATIQUES
                or self.minima_demandes)

    @property
    def fiches(self) -> List[reseau.FicheOuvrage]:
        """Dimensionnement de chaque ouvrage du réseau, mis en cache.

        Les minima — surface d'infiltration et ajutage — coûtent deux
        dichotomies par ouvrage, chacune rebalayant les durées de pluie. Sur un
        grand réseau cela se compte en secondes : ils ne sont alors calculés que
        si l'utilisateur les demande. Le volume minimal, lui, est toujours là :
        c'est le résultat principal.
        """
        if self.minima_disponibles:
            if self._fiches_minima is None:
                self._fiches_minima = reseau.dimensionner(self.systeme, avec_minima=True)
            return self._fiches_minima
        if self._fiches is None:
            self._fiches = reseau.dimensionner(self.systeme, avec_minima=False)
        return self._fiches

    def fiche(self, identifiant: str) -> Optional[reseau.FicheOuvrage]:
        for f in self.fiches:
            if f.ouvrage.id == identifiant:
                return f
        return None

    @property
    def simulation_systeme(self) -> Optional[reseau.SimulationSysteme]:
        """Simulation de l'averse la plus défavorable pour tout le réseau."""
        if self._simulation_systeme is None and self.systeme.aire_ponderee_m2 > 0:
            self._simulation_systeme = reseau.simuler_evenement_critique(self.systeme)
        return self._simulation_systeme

    @property
    def reseau_multiple(self) -> bool:
        """Vrai dès que le projet décrit plus qu'un bassin versant et un bassin."""
        return len(self.systeme.ouvrages) > 1 or len(self.systeme.bassins_versants) > 1

    def ajouter_ouvrage(self, nom: str = "") -> Ouvrage:
        ouvrage = reseau.ouvrage_neuf(self.systeme, nom)
        # Un nouvel ouvrage hérite du sol et des contraintes de l'ouvrage courant :
        # dans un même projet, le sol change rarement d'un bassin à l'autre.
        modele = self.systeme.courant.etude
        ouvrage.etude.k_infiltration_ms = modele.k_infiltration_ms
        ouvrage.etude.hauteur_charge_m = modele.hauteur_charge_m
        ouvrage.etude.coef_debit_orifice = modele.coef_debit_orifice
        ouvrage.scenario = self.systeme.courant.scenario
        self.systeme.ouvrages.append(ouvrage)
        self.systeme.ouvrage_courant = ouvrage.id
        self.invalider()
        return ouvrage

    def supprimer_ouvrage(self, identifiant: str) -> bool:
        """Retire un ouvrage ; ce qui pointait vers lui est reporté sur son aval."""
        if len(self.systeme.ouvrages) <= 1:
            return False
        ouvrage = self.systeme.ouvrage(identifiant)
        if ouvrage is None:
            return False
        aval = ouvrage.aval_id
        for autre in self.systeme.ouvrages:
            if autre.aval_id == identifiant:
                autre.aval_id = aval
        for versant in self.systeme.bassins_versants:
            if versant.bassin_id == identifiant:
                versant.bassin_id = aval or ""
        self.systeme.ouvrages.remove(ouvrage)
        if self.systeme.ouvrage_courant == identifiant:
            self.systeme.ouvrage_courant = self.systeme.ouvrages[0].id
        self.invalider()
        return True

    def ajouter_versant(self, nom: str = "", bassin_id: str = "") -> BassinVersant:
        versant = reseau.versant_neuf(self.systeme, nom,
                                      bassin_id or self.systeme.courant.id)
        self.systeme.bassins_versants.append(versant)
        self.invalider()
        return versant

    def supprimer_versant(self, identifiant: str) -> bool:
        versant = self.systeme.versant(identifiant)
        if versant is None or len(self.systeme.bassins_versants) <= 1:
            return False
        self.systeme.bassins_versants.remove(versant)
        self.invalider()
        return True

    @property
    def orifice(self) -> Optional[orifice.ResultatOrifice]:
        q = self.bassin.debit_ajutage_ls or self.projet.debit_ajutage_ls
        if q <= 0 or self.projet.hauteur_charge_m <= 0:
            return None
        return orifice.dimensionner_orifice(
            q, self.projet.hauteur_charge_m, self.projet.coef_debit_orifice,
            commercial=self.projet.ajutage_diametre_commercial,
            diametre_retenu_mm=self.projet.diametre_ajutage_mm)

    def dossier(self) -> Dossier:
        self.systeme.synchroniser()
        return construire(self.projet, self.scenario_principal, systeme=self.systeme)

    # -- modifications -----------------------------------------------------
    def definir_commune(self, commune: rainfall.Commune) -> None:
        self.systeme.commune_ins = commune.ins
        self.systeme.commune_nom = commune.nom
        if not commune.a_montana:
            self.systeme.source_pluie = rainfall.SOURCE_QDF
        self.invalider()

    def definir(self, champ: str, valeur) -> None:
        """Modifie une donnée du projet, au bon niveau.

        Les grandeurs communes (commune, récurrence, contraintes, identification)
        appartiennent au système ; les autres à l'ouvrage courant. Les écrire au
        mauvais endroit reviendrait à les perdre à la synchronisation suivante.
        """
        if champ in CHAMPS_GLOBAUX:
            setattr(self.systeme, champ, valeur)
        elif champ == "surface_reference_m2":
            versants = self.systeme.versants_de(self.systeme.courant.id)
            if versants:
                versants[0].surface_reference_m2 = valeur
            else:
                setattr(self.projet, champ, valeur)
        else:
            setattr(self.projet, champ, valeur)
        self.invalider()

    def definir_bassin(self, champ: str, valeur) -> None:
        setattr(self.projet.bassin, champ, valeur)
        self.invalider()

    def reprendre_dimensionnement(self) -> None:
        """Pre-remplit l'ouvrage courant avec le resultat du scenario retenu."""
        res = self.resultat
        b = self.projet.bassin
        # Les exutoires d'abord : le volume requis en dépend.
        b.surface_dispersion_m2 = (
            self.projet.surface_infiltration_m2
            if self.scenario_principal != SCENARIO_TEMPORISATION else 0.0
        )
        # Reprendre le dimensionnement, c'est aussi en reprendre l'hypothèse de
        # sol : le K propre au bassin construit s'efface.
        b.k_infiltration_ms = None
        b.debit_ajutage_ls = (
            self.projet.debit_ajutage_ls if self.scenario_principal != SCENARIO_DISPERSION else 0.0
        )
        volume = res.volume_m3
        if self.projet.a_un_apport_amont:
            # Un ouvrage amont se déverse ici : le volume à prévoir est celui de
            # l'événement critique combiné, et non celui du seul bassin versant
            # propre — sinon l'ouvrage proposé déborderait en simulation.
            duree, hauteur = simulation.evenement_critique(self.projet, b)
            volume = max(volume, simulation.volume_requis_m3(self.projet, b, hauteur, duree))
        b.volume_total_m3 = round(volume * 1.05, 1)
        self.invalider()

    def dimensionner_le_reseau(self) -> List[Tuple[str, float]]:
        """Propose un volume pour chaque ouvrage, de l'amont vers l'aval."""
        retenus = reseau.dimensionner_en_cascade(self.systeme)
        self.invalider()
        return retenus

    # -- persistance -------------------------------------------------------
    def to_json(self, indente: bool = False) -> str:
        """Projet complet, tel qu'il sera relu — reprise automatique et fichier."""
        self.systeme.synchroniser()
        return json.dumps(
            {
                "application": MARQUE_FICHIER,
                "version": VERSION_FICHIER,
                "systeme": self.systeme.to_dict(),
            },
            ensure_ascii=False,
            indent=2 if indente else None,
        )

    def charger_json(self, texte: str) -> bool:
        """Reprise silencieuse au démarrage : un échec ne doit rien casser."""
        try:
            self.importer_texte(texte)
        except Exception:
            return False
        return True

    def importer_texte(self, texte: str) -> None:
        """Charge un projet, en disant clairement ce qui cloche le cas échéant.

        Les projets enregistrés par les versions à bassin unique restent
        lisibles : leurs surfaces deviennent un bassin versant, leur ouvrage un
        bassin d'orage, et leur bassin amont éventuel un second ouvrage raccordé.
        """
        try:
            data = json.loads(texte)
        except Exception as exc:
            raise ValueError(f"Ce fichier n'est pas un projet lisible ({exc}).") from exc
        if not isinstance(data, dict) or ("systeme" not in data and "projet" not in data):
            raise ValueError("Ce fichier ne contient pas de projet HydroBassin.")
        marque = data.get("application")
        if marque not in (None, MARQUE_FICHIER):
            raise ValueError(f"Ce fichier vient d'une autre application ({marque}).")
        try:
            if "systeme" in data:
                systeme = Systeme.from_dict(data["systeme"])
            else:
                projet = Projet.from_dict(data["projet"])
                systeme = reseau.depuis_projet(projet)
                scenario = data.get("scenario", SCENARIO_MIXTE)
                if scenario in LIBELLES_SCENARIOS:
                    systeme.courant.scenario = scenario
        except Exception as exc:
            raise ValueError(f"Projet illisible : {type(exc).__name__} — {exc}") from exc
        for ouvrage in systeme.ouvrages:
            if ouvrage.scenario not in LIBELLES_SCENARIOS:
                ouvrage.scenario = SCENARIO_MIXTE
        self.systeme = systeme
        self.invalider()

    def exporter_vers(self, chemin: str) -> str:
        """Écrit le projet et renvoie le chemin réellement utilisé."""
        repertoire = os.path.dirname(chemin)
        if repertoire:
            os.makedirs(repertoire, exist_ok=True)
        with open(chemin, "w", encoding="utf-8") as fh:
            fh.write(self.to_json(indente=True))
        return chemin

    def importer_fichier(self, chemin: str) -> None:
        with open(chemin, "r", encoding="utf-8") as fh:
            self.importer_texte(fh.read())

    def nom_fichier_projet(self) -> str:
        return self.nom_fichier(EXTENSION_PROJET)

    def nom_fichier(self, extension: str) -> str:
        base = (self.systeme.nom_projet or f"bassin_{self.systeme.commune_nom}").strip()
        base = "".join(c if c.isalnum() or c in "-_ " else "_" for c in base).replace(" ", "_")
        return os.path.join(repertoire_documents(),
                            f"{base}_T{self.systeme.periode_retour}.{extension}")
