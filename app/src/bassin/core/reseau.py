"""Réseau de bassins versants et de bassins d'orage.

Un projet décrit désormais un **système** : plusieurs bassins versants nommés,
plusieurs bassins d'orage nommés, et les raccordements entre eux.

.. code::

    bassin versant ──┐
    bassin versant ──┴─► bassin d'orage A ─► bassin d'orage B ─► exutoire
                                    │
                                    └─ surverse : vers l'aval, ou vers le milieu naturel

Règles de raccordement (celles qu'a demandées l'utilisateur) :

* un **bassin versant** se raccorde à **un seul** bassin d'orage ;
* un **bassin d'orage** se raccorde soit à un autre bassin d'orage, soit à
  l'exutoire ;
* sa **surverse** part vers le bassin aval quand il y en a un, sauf si
  l'utilisateur a déclaré qu'elle rejoint le milieu naturel ;
* les collecteurs sont supposés véhiculer tout le débit et les temps de
  parcours sont négligés — l'hypothèse déjà retenue pour le bassin amont
  unique des versions précédentes.

Il n'y a **qu'une règle de dimensionnement** : celle de
:func:`bassin.core.hydro.volume_de_dimensionnement`. Chaque ouvrage est décrit
par un :class:`~bassin.core.model.Projet` (son « étude »), dont l'apport amont
est branché sur l'hydrogramme calculé par ce module. Le tableau des scénarios,
la courbe volume = f(durée), le temps de vidange, les minima, la table QDF et
la simulation d'un ouvrage du réseau passent donc exactement par le code déjà
éprouvé du bassin isolé.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from functools import lru_cache
from typing import Dict, Iterable, List, NamedTuple, Optional, Tuple

from . import hydro, rainfall, simulation
from . import rapport as mod_rapport
from .model import (
    assainir_valeurs,
    Bassin,
    LIBELLES_SCENARIOS,
    BassinAmont,
    COEF_SECURITE_INFILTRATION,
    Projet,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_TEMPORISATION,
    SurfaceIncidente,
    TEMPS_VIDANGE_LIMITE_H,
    texte_si_besoin,
    debit_infiltration_ls,
)
from .simulation import Apport

#: Destination d'un bassin d'orage qui n'est pas raccordé à un autre bassin.
EXUTOIRE = ""

#: Commune de repli quand celle du fichier est absente des données du GTI.
COMMUNE_PAR_DEFAUT = "63013"


# ---------------------------------------------------------------------------
# Données
# ---------------------------------------------------------------------------
@dataclass
class BassinVersant:
    """Un bassin versant nommé, raccordé à un seul bassin d'orage."""

    id: str = ""
    nom: str = ""
    #: Identifiant du bassin d'orage qui le reçoit (vide : non raccordé).
    bassin_id: str = ""
    surface_reference_m2: float = 0.0
    surfaces: List[SurfaceIncidente] = field(default_factory=list)
    note: str = ""

    @property
    def aire_totale_m2(self) -> float:
        return sum(s.aire_m2 for s in self.surfaces)

    @property
    def aire_ponderee_m2(self) -> float:
        return sum(s.aire_ponderee_m2 for s in self.surfaces)

    @property
    def coefficient_moyen(self) -> float:
        total = self.aire_totale_m2
        return self.aire_ponderee_m2 / total if total > 0 else 0.0

    def surfaces_non_vides(self) -> List[SurfaceIncidente]:
        return [s for s in self.surfaces if s.aire_m2 > 0]


@dataclass
class Ouvrage:
    """Un bassin d'orage nommé du réseau.

    ``etude`` porte toutes les données par ouvrage : sol, exutoire, géométrie,
    ajutage. C'est un :class:`~bassin.core.model.Projet` complet, ce qui permet
    de lui appliquer tel quel le moteur de dimensionnement du bassin isolé.
    """

    id: str = ""
    nom: str = ""
    #: Bassin d'orage aval ; vide (``EXUTOIRE``) pour un rejet à l'exutoire.
    aval_id: str = EXUTOIRE
    #: La surverse rejoint-elle le milieu naturel plutôt que le bassin aval ?
    surverse_vers_milieu_naturel: bool = False
    #: Compter les bassins versants situés en amont dans la surface raccordée
    #: (débit de fuite admissible et ajutage encodé en l/(s·ha)).
    compter_bv_amont_dans_ajutage: bool = False
    scenario: str = SCENARIO_MIXTE
    note: str = ""
    etude: Projet = field(default_factory=Projet)

    @property
    def bassin(self) -> Bassin:
        return self.etude.bassin

    @property
    def vers_exutoire(self) -> bool:
        return not self.aval_id

    @property
    def surverse_vers_aval(self) -> bool:
        """La surverse aggrave-t-elle la pointe en aval ?"""
        return bool(self.aval_id) and not self.surverse_vers_milieu_naturel

    def debit_infiltration_ls(self) -> float:
        return debit_infiltration_ls(self.etude.bassin.surface_dispersion_m2,
                                     self.etude.k_bassin_ms,
                                     self.etude.coef_securite_infiltration)

    def debit_sortant_ls(self) -> float:
        """Débit de fuite total de l'ouvrage encodé (infiltration + ajutage)."""
        return self.debit_infiltration_ls() + self.etude.bassin.debit_ajutage_ls


@dataclass
class Systeme:
    """Le projet complet : pluie, bassins versants, bassins d'orage, réseau."""

    # Pluie de projet et contraintes, communes à tout le système
    commune_ins: str = "63013"
    commune_nom: str = "Bütgenbach"
    periode_retour: int = 25
    source_pluie: str = "montana"
    coef_securite_infiltration: float = COEF_SECURITE_INFILTRATION
    temps_vidange_max_h: float = TEMPS_VIDANGE_LIMITE_H

    # Identification
    nom_projet: str = ""
    auteur: str = ""
    localisation: str = ""
    remarques: str = ""

    bassins_versants: List[BassinVersant] = field(default_factory=list)
    ouvrages: List[Ouvrage] = field(default_factory=list)
    #: Ouvrage affiché par les onglets de détail.
    ouvrage_courant: str = ""
    #: Composition du dossier : rubriques retenues, ajoutées, ordonnées.
    plan_rapport: mod_rapport.PlanRapport = field(
        default_factory=mod_rapport.plan_par_defaut)

    # ---- accès ----------------------------------------------------------
    def ouvrage(self, identifiant: str) -> Optional[Ouvrage]:
        for o in self.ouvrages:
            if o.id == identifiant:
                return o
        return None

    def versant(self, identifiant: str) -> Optional[BassinVersant]:
        for bv in self.bassins_versants:
            if bv.id == identifiant:
                return bv
        return None

    @property
    def courant(self) -> Ouvrage:
        """Ouvrage affiché ; le premier du réseau à défaut, jamais ``None``."""
        trouve = self.ouvrage(self.ouvrage_courant)
        if trouve is not None:
            return trouve
        if not self.ouvrages:
            self.ouvrages.append(ouvrage_neuf(self, "Bassin d'orage 1"))
        self.ouvrage_courant = self.ouvrages[0].id
        return self.ouvrages[0]

    def versants_de(self, identifiant: str) -> List[BassinVersant]:
        return [bv for bv in self.bassins_versants if bv.bassin_id == identifiant]

    def versants_orphelins(self) -> List[BassinVersant]:
        connus = {o.id for o in self.ouvrages}
        return [bv for bv in self.bassins_versants if bv.bassin_id not in connus]

    def amonts_directs(self, identifiant: str) -> List[Ouvrage]:
        liens = _liens_sains(self)
        return [o for o in self.ouvrages if liens.get(o.id, EXUTOIRE) == identifiant]

    def aval(self, identifiant: str) -> Optional[Ouvrage]:
        o = self.ouvrage(identifiant)
        if o is None:
            return None
        return self.ouvrage(_liens_sains(self).get(o.id, EXUTOIRE))

    def amonts_transitifs(self, identifiant: str) -> List[Ouvrage]:
        """Tous les ouvrages qui finissent par se déverser dans celui-ci."""
        resultat: List[Ouvrage] = []
        a_voir = [identifiant]
        vus = {identifiant}
        while a_voir:
            courant = a_voir.pop()
            for amont in self.amonts_directs(courant):
                if amont.id in vus:
                    continue
                vus.add(amont.id)
                resultat.append(amont)
                a_voir.append(amont.id)
        return resultat

    @property
    def exutoires(self) -> List[Ouvrage]:
        liens = _liens_sains(self)
        return [o for o in self.ouvrages if not liens.get(o.id, EXUTOIRE)]

    # ---- grandeurs globales ---------------------------------------------
    @property
    def aire_totale_m2(self) -> float:
        return sum(bv.aire_totale_m2 for bv in self.bassins_versants)

    @property
    def aire_ponderee_m2(self) -> float:
        return sum(bv.aire_ponderee_m2 for bv in self.bassins_versants)

    @property
    def coefficient_moyen(self) -> float:
        total = self.aire_totale_m2
        return self.aire_ponderee_m2 / total if total > 0 else 0.0

    @property
    def volume_total_m3(self) -> float:
        return sum(o.etude.bassin.volume_total_m3 for o in self.ouvrages)

    @property
    def aire_ponderee_routee_m2(self) -> float:
        """Surface active effectivement raccordée à un ouvrage.

        :attr:`aire_ponderee_m2` décrit ce qui est encodé, orphelins compris ;
        celle-ci décrit ce qui ruisselle vers le réseau, et c'est elle que la
        simulation met en jeu.
        """
        return sum(self.aire_ponderee_de(o.id) for o in self.ouvrages)

    @property
    def aire_routee_m2(self) -> float:
        """Surface incidente effectivement raccordée à un ouvrage."""
        return sum(self.aire_de(o.id) for o in self.ouvrages)

    def aire_ponderee_amont_m2(self, identifiant: str) -> float:
        """Surface active qui transite par les ouvrages situés en amont."""
        return sum(self.aire_ponderee_de(a.id) for a in self.amonts_transitifs(identifiant))

    def aire_amont_m2(self, identifiant: str) -> float:
        """Surface incidente totale des bassins versants situés en amont."""
        return sum(self.aire_de(a.id) for a in self.amonts_transitifs(identifiant))

    def aire_de(self, identifiant: str) -> float:
        return sum(bv.aire_totale_m2 for bv in self.versants_de(identifiant))

    def aire_ponderee_de(self, identifiant: str) -> float:
        return sum(bv.aire_ponderee_m2 for bv in self.versants_de(identifiant))

    # ---- cohérence ------------------------------------------------------
    def ordre_amont_aval(self) -> List[Ouvrage]:
        """Ouvrages triés de l'amont vers l'aval (tri topologique).

        Un raccordement circulaire est ignoré plutôt que de faire tourner
        l'application en rond : :meth:`anomalies` le signale à l'utilisateur.
        """
        liens = _liens_sains(self)
        restants = {o.id: o for o in self.ouvrages}
        sortie: List[Ouvrage] = []
        while restants:
            libres = [o for o in restants.values()
                      if not any(liens.get(a, EXUTOIRE) == o.id for a in restants)]
            if not libres:  # pragma: no cover - garde-fou, les cycles sont coupés
                sortie.extend(restants.values())
                break
            for o in libres:
                sortie.append(o)
                del restants[o.id]
        return sortie

    def anomalies(self) -> List[str]:
        """Défauts de construction du réseau, en clair."""
        messages: List[str] = list(self.normaliser_pluie()) + list(self.assainir())
        if not self.ouvrages:
            messages.append("Aucun bassin d'orage : le réseau est vide.")
        noms: Dict[str, int] = {}
        for o in self.ouvrages:
            noms[o.nom.strip().lower()] = noms.get(o.nom.strip().lower(), 0) + 1
        for nom, n in noms.items():
            if n > 1 and nom:
                messages.append(f"{n} bassins d'orage portent le nom « {nom} » : "
                                "renommez-les pour pouvoir les distinguer.")
        vus: Dict[str, int] = {}
        for o in self.ouvrages:
            vus[o.id] = vus.get(o.id, 0) + 1
        for identifiant, combien in vus.items():
            if combien > 1:
                # Deux ouvrages sous le même identifiant : tous les
                # raccordements qui le citent en désignent un au hasard. Sans
                # ce message, le réseau se décrivait « déversé dans lui-même ».
                messages.append(
                    f"{combien} bassins d'orage portent l'identifiant « {identifiant} » : "
                    "les raccordements qui le citent sont ambigus. Le fichier du projet "
                    "est à reprendre.")
        for o in self.ouvrages:
            if o.aval_id and self.ouvrage(o.aval_id) is None:
                messages.append(f"« {o.nom} » se déverse dans un bassin qui n'existe plus.")
            if o.aval_id == o.id:
                messages.append(f"« {o.nom} » se déverse dans lui-même.")
        for boucle in self.cycles():
            messages.append("Raccordement circulaire : "
                            + " → ".join(boucle) + f" → {boucle[0]}. "
                            "Ce lien est ignoré tant qu'il n'est pas corrigé.")
        for bv in self.versants_orphelins():
            messages.append(f"Le bassin versant « {bv.nom} » n'est raccordé à aucun "
                            "bassin d'orage : son ruissellement n'est compté nulle part.")
        sans_versant = [o for o in self.ouvrages
                        if not self.versants_de(o.id) and not self.amonts_directs(o.id)]
        for o in sans_versant:
            messages.append(f"« {o.nom} » ne reçoit ni bassin versant ni bassin amont : "
                            "il ne reçoit aucune eau.")
        for o in self.ouvrages:
            # De l'eau arrive, mais rien ne la retient : la simulation traite
            # alors l'ouvrage en simple passage. Le dire, sans quoi la synthèse
            # décrit un réseau dont il manque une pièce sans l'annoncer.
            if o.etude.bassin.volume_total_m3 > 0 or o in sans_versant:
                continue
            messages.append(
                f"« {o.nom} » n'a aucun volume de temporisation encodé : la simulation le "
                "traite comme un ouvrage de transit, tout ce qu'il reçoit repart vers l'aval "
                "sans laminage. Encodez son volume ou lancez le dimensionnement en cascade.")
        return messages

    def cycles(self) -> List[List[str]]:
        """Boucles de raccordement, décrites par les noms des ouvrages."""
        boucles: List[List[str]] = []
        vus: set = set()
        for depart in self.ouvrages:
            if depart.id in vus:
                continue
            chemin: List[str] = []
            courant: Optional[str] = depart.id
            local: Dict[str, int] = {}
            while courant:
                if courant in local:
                    boucle = chemin[local[courant]:]
                    if boucle and sorted(boucle) not in [sorted(b) for b in boucles]:
                        boucles.append([self.ouvrage(i).nom if self.ouvrage(i) else i
                                        for i in boucle])
                    break
                if courant in vus:
                    break
                local[courant] = len(chemin)
                chemin.append(courant)
                suivant = self.ouvrage(courant)
                courant = suivant.aval_id if suivant else EXUTOIRE
            vus.update(chemin)
        return boucles

    # ---- mise en cohérence ----------------------------------------------
    def synchroniser(self) -> None:
        """Reporte les données globales et les raccordements dans chaque étude.

        Appelée avant tout calcul : c'est elle qui garantit qu'un ouvrage voit
        les surfaces de ses bassins versants, la pluie du projet, et
        l'hydrogramme de ses ouvrages amont. Les :class:`SurfaceIncidente` sont
        **partagées** avec les bassins versants, pas recopiées : les modifier
        d'un côté les modifie de l'autre.
        """
        if not self.ouvrages:
            self.ouvrages.append(ouvrage_neuf(self, "Bassin d'orage 1"))
        if self.ouvrage(self.ouvrage_courant) is None:
            self.ouvrage_courant = self.ouvrages[0].id
        self.normaliser_pluie()
        self.assainir()
        noeuds = self.noeuds()
        for o in self.ouvrages:
            e = o.etude
            e.commune_ins = self.commune_ins
            e.commune_nom = self.commune_nom
            e.periode_retour = self.periode_retour
            e.source_pluie = self.source_pluie
            e.coef_securite_infiltration = self.coef_securite_infiltration
            e.temps_vidange_max_h = self.temps_vidange_max_h
            e.nom_projet = self.nom_projet
            e.auteur = self.auteur
            e.localisation = self.localisation
            e.remarques = self.remarques
            versants = self.versants_de(o.id)
            e.surfaces = [s for bv in versants for s in bv.surfaces]
            e.surface_reference_m2 = sum(bv.surface_reference_m2 for bv in versants)
            e.surface_amont_raccordee_m2 = (
                self.aire_amont_m2(o.id) if o.compter_bv_amont_dans_ajutage else 0.0)
            noeud = noeuds[o.id]
            if noeud.amonts:
                # Le panneau « bassin amont » des versions précédentes est
                # neutralisé dès qu'un ouvrage du réseau se déverse ici : cumuler
                # les deux compterait deux fois le même apport. Sans amont dans le
                # réseau il reste opérant, ce qui garde lisibles les projets et les
                # calculs d'avant.
                e.amont = BassinAmont()
                e.brancher_apport(_brancher(noeud))
            else:
                e.brancher_apport(None)
            e.recalculer_ajutage()

    def normaliser_pluie(self) -> List[str]:
        """Ramène la commune et la récurrence dans les données du GTI.

        Un fichier de projet retouché à la main, ou écrit par une version à
        venir, peut nommer une commune inconnue ou une récurrence hors table.
        L'application ne peut alors rien calculer : plutôt que de s'arrêter sur
        une exception au premier tracé de courbe, elle revient à une pluie
        valable et **dit ce qu'elle a substitué** — :meth:`anomalies` le porte
        à l'écran comme au dossier. Se taire reviendrait à livrer un calcul fait
        sur une autre pluie que celle qu'annonce l'entête.

        La substitution est retenue tant qu'elle tient : dès que l'utilisateur
        choisit lui-même une autre commune ou une autre récurrence, le message
        disparaît sans qu'il ait à le faire taire.
        """
        retenues: Dict[str, Tuple[object, str]] = getattr(self, "_substitutions_pluie", {})
        # Une substitution que l'utilisateur a depuis remplacée n'a plus à être dite.
        def tient_encore(champ: str, valeur) -> bool:
            """La substitution vaut-elle encore, ou l'utilisateur a-t-il choisi ?"""
            if champ.startswith("scenario:"):
                ouvrage = self.ouvrage(valeur[0])
                return ouvrage is not None and ouvrage.scenario == valeur[1]
            return getattr(self, champ) == valeur

        retenues = {champ: (valeur, message) for champ, (valeur, message) in retenues.items()
                    if tient_encore(champ, valeur)}

        if (not rainfall.a_donnees_montana(self.commune_ins)
                and not rainfall.a_donnees_qdf(self.commune_ins)):
            ancienne = self.commune_ins or "(vide)"
            self.commune_ins = COMMUNE_PAR_DEFAUT
            commune = rainfall.commune_par_ins(COMMUNE_PAR_DEFAUT)
            self.commune_nom = commune.nom if commune else self.commune_nom
            retenues["commune_ins"] = (self.commune_ins, (
                f"Commune INS {ancienne} absente des données du GTI : le calcul est fait "
                f"sur {self.commune_nom} (INS {COMMUNE_PAR_DEFAUT}). Choisissez la commune "
                "du projet."))
        if int(self.periode_retour) not in rainfall.RETURN_PERIODS:
            ancienne = self.periode_retour
            self.periode_retour = min(rainfall.RETURN_PERIODS,
                                      key=lambda rp: (abs(rp - int(ancienne)), rp))
            retenues["periode_retour"] = (self.periode_retour, (
                f"Période de retour de {ancienne} ans absente du GTI : le calcul est fait "
                f"à {self.periode_retour} ans. Les récurrences tabulées sont "
                + ", ".join(str(rp) for rp in rainfall.RETURN_PERIODS) + " ans."))
        if self.source_pluie not in (rainfall.SOURCE_MONTANA, rainfall.SOURCE_QDF):
            ancienne = self.source_pluie
            self.source_pluie = rainfall.SOURCE_MONTANA
            retenues["source_pluie"] = (self.source_pluie, (
                f"Source de pluie « {ancienne} » inconnue : les formules de Montana du GTI "
                "ont été retenues."))
        for ouvrage in self.ouvrages:
            if ouvrage.scenario not in LIBELLES_SCENARIOS:
                ancienne = ouvrage.scenario
                ouvrage.scenario = SCENARIO_MIXTE
                retenues[f"scenario:{ouvrage.id}"] = ((ouvrage.id, ouvrage.scenario), (
                    f"« {ouvrage.nom} » : scénario « {ancienne} » inconnu, le scénario "
                    "mixte a été retenu. Vérifiez le dispositif d'évacuation."))
        self._substitutions_pluie = retenues
        return [message for _valeur, message in retenues.values()]

    def assainir(self) -> List[str]:
        """Retire du projet les valeurs qui ne sont pas des nombres.

        Appelée avant tout calcul : l'infini et le NaN sont écartés **à
        l'entrée**, une bonne fois, plutôt que rattrapés à chaque endroit qui
        les afficherait ou les tracerait — il y en a trop pour les tenir tous,
        et il en resterait toujours un. Ce qui est simplement hors domaine, lui,
        n'est pas touché : c'est un chiffre, et c'est à l'utilisateur de le
        revoir, averti par :func:`model.valeurs_hors_domaine`.
        """
        dits: List[str] = list(getattr(self, "_valeurs_assainies", []))
        for ouvrage in self.ouvrages:
            for message in assainir_valeurs(ouvrage.etude):
                dits.append(f"« {ouvrage.nom} » — {message}")
        for versant in self.bassins_versants:
            for message in _assainir_versant(versant):
                dits.append(f"« {versant.nom} » — {message}")
        # Un même défaut ne se dit qu'une fois, quel que soit le nombre de
        # recalculs : le message reste tant que le projet n'a pas été réenregistré.
        uniques: List[str] = []
        for message in dits:
            if message not in uniques:
                uniques.append(message)
        self._valeurs_assainies = uniques
        return uniques

    def noeuds(self) -> Dict[str, "Noeud"]:
        """Description hydrologique figée de chaque ouvrage, amont compris."""
        construits: Dict[str, Noeud] = {}
        for o in self.ordre_amont_aval():
            construits[o.id] = Noeud(
                aire_ponderee_m2=self.aire_ponderee_de(o.id),
                q_infiltration_ls=o.debit_infiltration_ls(),
                q_ajutage_ls=o.etude.bassin.debit_ajutage_ls,
                volume_sous_ajutage_m3=o.etude.bassin.volume_sous_ajutage_m3,
                volume_total_m3=o.etude.bassin.volume_total_m3,
                surverse_vers_aval=o.surverse_vers_aval,
                amonts=tuple(construits[a.id] for a in self.amonts_directs(o.id)
                             if a.id in construits),
            )
        return construits

    def etude_de(self, identifiant: str) -> Projet:
        """Étude d'un ouvrage, prête pour le moteur de dimensionnement."""
        self.synchroniser()
        o = self.ouvrage(identifiant)
        return o.etude if o is not None else self.courant.etude

    # ---- sérialisation ---------------------------------------------------
    #: Champs de l'étude d'un ouvrage que :meth:`synchroniser` recalcule : les
    #: enregistrer reviendrait à écrire deux fois la même donnée, et à laisser
    #: croire qu'on peut la corriger là.
    DERIVES = ("commune_ins", "commune_nom", "periode_retour", "source_pluie",
               "coef_securite_infiltration", "temps_vidange_max_h", "nom_projet",
               "auteur", "localisation", "remarques", "surfaces",
               "surface_reference_m2", "surface_amont_raccordee_m2")

    def to_dict(self) -> Dict:
        self.synchroniser()
        donnees = asdict(self)
        for ouvrage in donnees.get("ouvrages", []):
            etude = ouvrage.get("etude", {})
            for champ in self.DERIVES:
                etude.pop(champ, None)
        # Le plan s'écrit par sa propre méthode : ``asdict`` en donnerait une
        # forme dépendante des champs internes, que la relecture devrait suivre.
        donnees["plan_rapport"] = self.plan_rapport.to_dict()
        return donnees

    @classmethod
    def from_dict(cls, data: Dict) -> "Systeme":
        data = dict(data)
        versants = [
            BassinVersant(
                **{**_connus(BassinVersant, bv),
                   "surfaces": [SurfaceIncidente(**_connus(SurfaceIncidente, s))
                                for s in bv.get("surfaces", [])]}
            )
            for bv in data.pop("bassins_versants", [])
        ]
        ouvrages = [
            Ouvrage(**{**_connus(Ouvrage, o), "etude": Projet.from_dict(o.get("etude", {}))})
            for o in data.pop("ouvrages", [])
        ]
        plan = mod_rapport.PlanRapport.from_dict(data.pop("plan_rapport", None))
        systeme = cls(bassins_versants=versants, ouvrages=ouvrages, plan_rapport=plan,
                      **_connus(cls, data))
        systeme.synchroniser()
        return systeme


#: Champs que :meth:`Systeme.from_dict` construit lui-même, et que le filtre
#: générique ne doit donc pas transmettre au constructeur sous leur forme brute.
_MONTES_A_PART = ("surfaces", "etude", "plan_rapport")


def _connus(classe, data: Dict) -> Dict:
    """Filtre un dictionnaire enregistré sur les champs actuels d'une dataclasse.

    Les champs de texte sont ramenés à du texte : un nom d'ouvrage arrivé sous
    forme de nombre faisait tomber l'application bien plus loin, au premier
    ``.strip()``.
    """
    champs = classe.__dataclass_fields__
    return {k: texte_si_besoin(champs[k], v) for k, v in dict(data).items()
            if k in champs and k not in _MONTES_A_PART}


def _assainir_versant(versant: BassinVersant) -> List[str]:
    """Valeurs non numériques d'un bassin versant, retirées et dites."""
    from .model import _porteurs_versant

    return _porteurs_versant(versant)


def _liens_sains(systeme: Systeme) -> Dict[str, str]:
    """Raccordements aval, les liens circulaires ou cassés étant coupés.

    Un réseau incohérent doit rester calculable : l'utilisateur est prévenu par
    :meth:`Systeme.anomalies`, mais l'application ne doit ni tourner en boucle
    ni refuser d'afficher.
    """
    connus = {o.id for o in systeme.ouvrages}
    liens = {o.id: (o.aval_id if o.aval_id in connus and o.aval_id != o.id else EXUTOIRE)
             for o in systeme.ouvrages}
    for depart in list(liens):
        vus = {depart}
        courant = liens[depart]
        while courant:
            if courant in vus:
                liens[depart] = EXUTOIRE
                break
            vus.add(courant)
            courant = liens.get(courant, EXUTOIRE)
    return liens


# ---------------------------------------------------------------------------
# Routage hydrologique
# ---------------------------------------------------------------------------
class Noeud(NamedTuple):
    """Description figée d'un ouvrage et de tout ce qui se déverse dedans.

    Immuable et hachable : elle sert de clé de cache. Deux ouvrages décrits par
    les mêmes nombres ont le même hydrogramme de sortie, il est donc inutile de
    l'intégrer deux fois — c'est ce qui rend le balayage des durées abordable
    sur un réseau de plusieurs bassins.
    """

    aire_ponderee_m2: float
    q_infiltration_ls: float
    q_ajutage_ls: float
    volume_sous_ajutage_m3: float
    volume_total_m3: float
    surverse_vers_aval: bool
    amonts: Tuple["Noeud", ...] = ()


@lru_cache(maxsize=8192)
def apport_amont(noeud: Noeud, hauteur_mm: float, duree_min: float) -> Apport:
    """Hydrogramme arrivant dans un ouvrage depuis les ouvrages situés au-dessus.

    L'``Apport`` renvoyé est partagé entre appelants : il se lit, il ne se
    modifie pas.
    """
    if not noeud.amonts or duree_min <= 0:
        return Apport()
    return simulation.somme_apports([restitution(a, hauteur_mm, duree_min)
                                     for a in noeud.amonts])


@lru_cache(maxsize=8192)
def restitution(noeud: Noeud, hauteur_mm: float, duree_min: float) -> Apport:
    """Hydrogramme qu'un ouvrage envoie vers l'aval, pendant et après l'averse."""
    if duree_min <= 0:
        return Apport()
    # Volume ruisselé puis débit moyen, dans cet ordre : c'est le chemin de
    # calcul du bassin amont des versions précédentes, et le reprendre à
    # l'identique garantit que le réseau rend les mêmes chiffres au dernier bit.
    v_in_m3 = hauteur_mm * noeud.aire_ponderee_m2 / 1000.0
    q_direct = v_in_m3 * 1000.0 / (duree_min * 60.0)
    return simulation.hydrogramme_sortant(
        q_direct, duree_min, apport_amont(noeud, hauteur_mm, duree_min),
        noeud.q_infiltration_ls, noeud.q_ajutage_ls, noeud.volume_sous_ajutage_m3,
        noeud.volume_total_m3, surverse_vers_aval=noeud.surverse_vers_aval)


def _brancher(noeud: Noeud):
    """Fournisseur d'apport amont branché sur l'étude d'un ouvrage."""

    def fournir(hauteur_mm: float, duree_min: float) -> Apport:
        return apport_amont(noeud, hauteur_mm, duree_min)

    return fournir


# ---------------------------------------------------------------------------
# Création
# ---------------------------------------------------------------------------
def _identifiant(prefixe: str, existants: Iterable[str]) -> str:
    pris = set(existants)
    i = 1
    while f"{prefixe}{i}" in pris:
        i += 1
    return f"{prefixe}{i}"


def ouvrage_neuf(systeme: Systeme, nom: str = "") -> Ouvrage:
    """Bassin d'orage vierge, prêt à être raccordé."""
    identifiant = _identifiant("bo", (o.id for o in systeme.ouvrages))
    numero = len(systeme.ouvrages) + 1
    return Ouvrage(
        id=identifiant,
        nom=nom or f"Bassin d'orage {numero}",
        etude=Projet(
            commune_ins=systeme.commune_ins,
            commune_nom=systeme.commune_nom,
            periode_retour=systeme.periode_retour,
            source_pluie=systeme.source_pluie,
            coef_securite_infiltration=systeme.coef_securite_infiltration,
            temps_vidange_max_h=systeme.temps_vidange_max_h,
        ),
    )


def versant_neuf(systeme: Systeme, nom: str = "", bassin_id: str = "") -> BassinVersant:
    """Bassin versant vierge, avec la grille de coefficients du GTI."""
    identifiant = _identifiant("bv", (bv.id for bv in systeme.bassins_versants))
    numero = len(systeme.bassins_versants) + 1
    return BassinVersant(
        id=identifiant,
        nom=nom or f"Bassin versant {numero}",
        bassin_id=bassin_id or (systeme.ouvrages[0].id if systeme.ouvrages else ""),
        surfaces=Projet.surfaces_par_defaut(),
    )


def systeme_neuf() -> Systeme:
    """Système par défaut : un bassin versant raccordé à un bassin d'orage."""
    systeme = Systeme()
    ouvrage = ouvrage_neuf(systeme, "Bassin d'orage 1")
    systeme.ouvrages.append(ouvrage)
    systeme.ouvrage_courant = ouvrage.id
    systeme.bassins_versants.append(versant_neuf(systeme, "Bassin versant 1", ouvrage.id))
    systeme.synchroniser()
    return systeme


def depuis_projet(projet: Projet) -> Systeme:
    """Convertit un projet « bassin isolé » en système à un ou deux ouvrages.

    C'est le chemin de reprise des projets enregistrés par les versions
    antérieures : les surfaces deviennent un bassin versant, l'ouvrage étudié un
    bassin d'orage raccordé à l'exutoire, et le bassin d'orage amont — s'il était
    déclaré — un second ouvrage raccordé au premier, avec son propre bassin
    versant.
    """
    systeme = Systeme(
        commune_ins=projet.commune_ins,
        commune_nom=projet.commune_nom,
        periode_retour=projet.periode_retour,
        source_pluie=projet.source_pluie,
        coef_securite_infiltration=projet.coef_securite_infiltration,
        temps_vidange_max_h=projet.temps_vidange_max_h,
        nom_projet=projet.nom_projet,
        auteur=projet.auteur,
        localisation=projet.localisation,
        remarques=projet.remarques,
    )
    aval = ouvrage_neuf(systeme, "Bassin d'orage")
    aval.etude = projet
    aval.compter_bv_amont_dans_ajutage = bool(projet.amont.inclure_bv_dans_ajutage)
    systeme.ouvrages.append(aval)
    systeme.ouvrage_courant = aval.id

    versant = versant_neuf(systeme, "Bassin versant", aval.id)
    versant.surfaces = list(projet.surfaces) or Projet.surfaces_par_defaut()
    versant.surface_reference_m2 = projet.surface_reference_m2
    systeme.bassins_versants.append(versant)

    amont = projet.amont
    if amont.actif:
        ouvrage_amont = ouvrage_neuf(systeme, "Bassin d'orage amont")
        ouvrage_amont.aval_id = aval.id
        ouvrage_amont.etude.k_infiltration_ms = amont.k_infiltration_ms
        ouvrage_amont.etude.surface_infiltration_m2 = amont.surface_dispersion_m2
        ouvrage_amont.etude.debit_ajutage_ls = amont.debit_ajutage_ls
        ouvrage_amont.etude.bassin = Bassin(
            volume_total_m3=amont.volume_temporisation_m3,
            surface_dispersion_m2=amont.surface_dispersion_m2,
            debit_ajutage_ls=amont.debit_ajutage_ls,
        )
        systeme.ouvrages.append(ouvrage_amont)
        versant_amont = versant_neuf(systeme, "Bassin versant amont", ouvrage_amont.id)
        versant_amont.surfaces = [
            SurfaceIncidente("Bassin versant amont (coefficient moyen)",
                             amont.coef_ruissellement, amont.surface_bv_m2)]
        systeme.bassins_versants.append(versant_amont)
    # Le réseau reprend la main sur l'apport amont : le panneau historique est
    # neutralisé pour ne pas compter deux fois le même ouvrage.
    projet.amont = BassinAmont()
    systeme.synchroniser()
    return systeme


# ---------------------------------------------------------------------------
# Dimensionnement du réseau
# ---------------------------------------------------------------------------
@dataclass
class FicheOuvrage:
    """Ce que le réseau a calculé pour un ouvrage."""

    ouvrage: Ouvrage
    resultat: hydro.Resultat
    aire_ponderee_propre_m2: float = 0.0
    aire_ponderee_amont_m2: float = 0.0
    apport_amont_m3: float = 0.0
    q_amont_max_ls: float = 0.0
    simulation: Optional[simulation.ResultatSimulation] = None

    @property
    def nom(self) -> str:
        return self.ouvrage.nom

    @property
    def volume_minimal_m3(self) -> float:
        """Volume minimal à mettre en œuvre pour éviter la surverse."""
        return self.resultat.volume_m3

    @property
    def volume_encode_m3(self) -> float:
        return self.ouvrage.etude.bassin.volume_total_m3

    @property
    def suffisant(self) -> bool:
        return self.volume_encode_m3 + 1e-6 >= self.volume_minimal_m3

    @property
    def statut(self) -> str:
        if self.volume_encode_m3 <= 0:
            return "NON ENCODE"
        if not self.suffisant:
            return "DEBORDEMENT"
        if self.volume_minimal_m3 > 0.95 * self.volume_encode_m3:
            return "LIMITE"
        return "OK"


def dimensionner(systeme: Systeme, avec_minima: bool = True) -> List[FicheOuvrage]:
    """Dimensionne chaque ouvrage du réseau, de l'amont vers l'aval.

    Chaque ouvrage est dimensionné sur le scénario qui lui est propre, à partir
    de ses bassins versants **et** de ce que lui restituent les ouvrages amont
    tels qu'ils sont encodés. Un ouvrage amont sous-dimensionné surverse : son
    trop-plein arrive sans laminage et gonfle le volume à prévoir en aval, ce
    que le balayage voit puisqu'il intègre l'hydrogramme réel.
    """
    systeme.synchroniser()
    noeuds = systeme.noeuds()
    fiches: List[FicheOuvrage] = []
    for o in systeme.ordre_amont_aval():
        res = hydro.dimensionner(o.etude, o.scenario, avec_minima=avec_minima)
        fiche = FicheOuvrage(
            ouvrage=o,
            resultat=res,
            aire_ponderee_propre_m2=systeme.aire_ponderee_de(o.id),
            aire_ponderee_amont_m2=systeme.aire_ponderee_amont_m2(o.id),
        )
        if res.duree_critique_min > 0:
            apport = apport_amont(noeuds[o.id], res.hauteur_pluie_mm, res.duree_critique_min)
            fiche.apport_amont_m3 = apport.volume_m3
            fiche.q_amont_max_ls = max((q for _, _, q in apport.segments), default=0.0)
        fiches.append(fiche)
    return fiches


@dataclass
class RetenuCascade:
    """Ce que la cascade a fait d'un ouvrage — ou n'a pas pu en faire."""

    nom: str
    volume_m3: float
    dimensionne: bool = True
    raison: str = ""

    def __iter__(self):
        """Se laisse encore lire comme un couple ``(nom, volume)``."""
        return iter((self.nom, self.volume_m3))


def dimensionner_en_cascade(systeme: Systeme,
                            marge: float = 1.05) -> List["RetenuCascade"]:
    """Propose un volume pour chaque ouvrage, de l'amont vers l'aval.

    L'ordre compte : tant qu'un ouvrage amont surverse, l'ouvrage aval doit
    encaisser un trop-plein non laminé. En le dimensionnant d'abord, l'aval est
    calculé sur un amont qui retient réellement ce qu'il doit retenir.

    Les **exutoires de l'ouvrage suivent le scénario retenu**, comme le fait
    « Reprendre le dimensionnement » pour un bassin isolé. Sans cela l'ouvrage
    encodé et l'hypothèse de calcul se contrediraient : un volume calculé avec
    une infiltration que l'ouvrage n'a pas déborderait en simulation.

    Un ouvrage sans exutoire — ni infiltration, ni ajutage — ne se dimensionne
    pas : son temps de vidange est infini et aucun volume ne le rend conforme.
    La cascade le laissait alors à zéro sans un mot, et le volume proposé en
    aval encaissait la totalité de son ruissellement : on pouvait croire le
    réseau dimensionné alors qu'un ouvrage manquait au calcul. Chaque ouvrage
    revient donc avec ce qui lui est arrivé.

    Renvoie la liste des :class:`RetenuCascade`, dans l'ordre de calcul.
    """
    systeme.synchroniser()
    retenus: List[Tuple[str, float]] = []
    for o in systeme.ordre_amont_aval():
        systeme.synchroniser()   # l'amont vient d'être fixé : l'apport change
        res = hydro.dimensionner(o.etude, o.scenario, avec_minima=False)
        bassin = o.etude.bassin
        # Les exutoires d'abord : le volume requis en dépend.
        bassin.surface_dispersion_m2 = (
            o.etude.surface_infiltration_m2 if o.scenario != SCENARIO_TEMPORISATION else 0.0)
        bassin.debit_ajutage_ls = (
            o.etude.debit_ajutage_ls if o.scenario != SCENARIO_DISPERSION else 0.0)
        bassin.volume_total_m3 = round(res.volume_m3 * marge, 1) if res.dimensionnable else 0.0
        if res.dimensionnable:
            retenus.append(RetenuCascade(o.nom, bassin.volume_total_m3))
        else:
            if res.debit_sortant_ls <= 0:
                raison = ("aucun exutoire encodé (ni infiltration, ni ajutage) : temps de "
                          "vidange infini, volume non calculable. Encodez une surface "
                          "d'infiltration et/ou un débit d'ajutage.")
            elif o.etude.aire_raccordee_m2 <= 0:
                raison = ("aucune surface raccordée : raccordez-lui un bassin versant.")
            else:
                raison = "volume nul : rien à retenir pour cet ouvrage."
            retenus.append(RetenuCascade(o.nom, 0.0, dimensionne=False, raison=raison))
    systeme.synchroniser()
    return retenus


# ---------------------------------------------------------------------------
# Simulation du système complet
# ---------------------------------------------------------------------------
@dataclass
class SimulationSysteme:
    """Comportement de tous les ouvrages pour une même averse."""

    duree_min: float
    hauteur_mm: float
    periode_retour: int
    resultats: List[Tuple[Ouvrage, simulation.ResultatSimulation]] = field(default_factory=list)

    @property
    def volume_stocke_m3(self) -> float:
        return sum(r.volume_max_m3 for _, r in self.resultats)

    @property
    def volume_debordement_m3(self) -> float:
        return sum(r.volume_debordement_m3 for _, r in self.resultats)

    @property
    def volume_ruissele_m3(self) -> float:
        return sum(r.volume_ruissele_m3 for _, r in self.resultats)

    @property
    def temps_vidange_max_h(self) -> float:
        return max((r.temps_vidange_h for _, r in self.resultats), default=0.0)

    @property
    def ouvrages_en_debordement(self) -> List[Ouvrage]:
        return [o for o, r in self.resultats if r.debordement]

    @property
    def ouvrages_non_encodes(self) -> List[Ouvrage]:
        """Ouvrages dont le volume n'a pas encore été encodé."""
        return [o for o, r in self.resultats if r.statut == "NON ENCODE"]

    @property
    def statut(self) -> str:
        """Ce que vaut le système pour cette averse.

        Tant qu'un ouvrage n'est pas encodé, la simulation ne décrit pas le
        réseau projeté : annoncer « OK » — et « aucun ouvrage ne déborde » —
        revenait à valider un réseau dont il manque une pièce.
        """
        if self.ouvrages_non_encodes:
            return "NON ENCODE"
        if self.ouvrages_en_debordement:
            return "DEBORDEMENT"
        if any(r.statut == "NON CONFORME" for _, r in self.resultats):
            return "NON CONFORME"
        if any(r.statut == "LIMITE" for _, r in self.resultats):
            return "LIMITE"
        return "OK"

    def resultat(self, identifiant: str) -> Optional[simulation.ResultatSimulation]:
        for o, r in self.resultats:
            if o.id == identifiant:
                return r
        return None


def simuler(systeme: Systeme, duree_min: float, periode_retour: Optional[int] = None,
            n_points: int = 400) -> SimulationSysteme:
    """Simule une averse de durée donnée sur l'ensemble du réseau."""
    from . import rainfall

    systeme.synchroniser()
    rp = periode_retour or systeme.periode_retour
    src = rainfall.SourcePluie(systeme.commune_ins, rp, systeme.source_pluie)
    hauteur = src.hauteur(duree_min)
    noeuds = systeme.noeuds()
    sortie = SimulationSysteme(duree_min=duree_min, hauteur_mm=hauteur, periode_retour=rp)
    for o in systeme.ordre_amont_aval():
        apport = apport_amont(noeuds[o.id], hauteur, duree_min)
        res = simulation.simuler(o.etude, o.etude.bassin, hauteur, duree_min,
                                 n_points=n_points, apport=apport)
        sortie.resultats.append((o, res))
    return sortie


def duree_critique_systeme(systeme: Systeme, periode_retour: Optional[int] = None) -> float:
    """Durée d'averse la plus défavorable pour l'ensemble du réseau.

    Chaque ouvrage a sa propre durée critique — un petit bassin versant
    imperméable culmine en quelques minutes, un grand ensemble tamponné en
    plusieurs heures. Pour juger du système, on retient la durée qui met le plus
    de volume en jeu : celle qui maximise la somme des volumes à maîtriser, le
    débordement étant départagé en premier.
    """
    from . import rainfall

    systeme.synchroniser()
    rp = periode_retour or systeme.periode_retour
    src = rainfall.SourcePluie(systeme.commune_ins, rp, systeme.source_pluie)
    durees = src.durees_de_balayage(hydro.DUREE_MIN, hydro.DUREE_MAX, hydro.PAS_DUREE)
    noeuds = systeme.noeuds()

    def score(duree: float) -> Tuple[float, float]:
        hauteur = src.hauteur(duree)
        debord = 0.0
        stocke = 0.0
        for o in systeme.ouvrages:
            noeud = noeuds[o.id]
            apport = apport_amont(noeud, hauteur, duree)
            q_direct = hauteur * noeud.aire_ponderee_m2 / (duree * 60.0)
            # Même expression que ``hydro.volume_pointe_amont`` : le score du
            # système doit s'accorder au volume annoncé ouvrage par ouvrage.
            requis = simulation.pic_volume_m3(q_direct, duree, apport,
                                              noeud.q_infiltration_ls, noeud.q_ajutage_ls,
                                              noeud.volume_sous_ajutage_m3)
            stocke += requis
            if noeud.volume_total_m3 > 0:
                debord += max(requis - noeud.volume_total_m3, 0.0)
        return debord, stocke

    # Même stratégie que le balayage d'un ouvrage isolé : une grille dégrossie,
    # puis un affinage autour du maximum. L'intégration exacte est trop coûteuse
    # pour être répétée sur les 17 280 durées de la grille GTI.
    grossier = 160
    pas = max(1, len(durees) // grossier)
    indices = list(range(0, len(durees), pas))
    if indices[-1] != len(durees) - 1:
        indices.append(len(durees) - 1)
    meilleur = max(indices, key=lambda i: score(float(durees[i])))
    debut, fin = max(0, meilleur - pas), min(len(durees) - 1, meilleur + pas)
    return float(max(durees[debut:fin + 1], key=lambda t: score(float(t))))


def simuler_evenement_critique(systeme: Systeme, periode_retour: Optional[int] = None,
                               n_points: int = 400) -> SimulationSysteme:
    """Simule l'averse la plus défavorable pour le réseau."""
    duree = duree_critique_systeme(systeme, periode_retour)
    return simuler(systeme, duree, periode_retour, n_points=n_points)


def debit_a_l_exutoire_ls(systeme: Systeme, hauteur_mm: float, duree_min: float) -> Apport:
    """Hydrogramme rejeté au milieu naturel par les ouvrages terminaux."""
    systeme.synchroniser()
    noeuds = systeme.noeuds()
    sorties: List[Apport] = []
    for o in systeme.exutoires:
        noeud = noeuds[o.id]
        # Un ouvrage terminal rejette tout ce qu'il ne retient pas : son ajutage
        # et sa surverse partent au milieu naturel, quel que soit le réglage de
        # la case « surverse vers le milieu naturel » qui, elle, ne concerne que
        # les ouvrages raccordés à un bassin aval.
        v_in_m3 = hauteur_mm * noeud.aire_ponderee_m2 / 1000.0
        sorties.append(simulation.hydrogramme_sortant(
            v_in_m3 * 1000.0 / (duree_min * 60.0) if duree_min > 0 else 0.0,
            duree_min, apport_amont(noeud, hauteur_mm, duree_min),
            noeud.q_infiltration_ls, noeud.q_ajutage_ls, noeud.volume_sous_ajutage_m3,
            noeud.volume_total_m3, surverse_vers_aval=True))
    return simulation.somme_apports(sorties)
