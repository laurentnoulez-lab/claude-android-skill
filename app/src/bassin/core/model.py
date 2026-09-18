"""Modèle de données du projet (surfaces, sol, ouvrage)."""

from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional, Tuple

import math

from ..formats import fr, nombre

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


@dataclass(frozen=True)
class Domaine:
    """Bornes physiques d'une grandeur encodée, et la raison de ces bornes.

    Déclarées **une fois**, elles servent au moteur — qui annonce toute valeur
    qui en sort —, à l'interface — qui refuse la saisie — et au classeur — qui
    pose la même contrainte sur la cellule. Énumérer les cas absurdes au fil des
    signalements ne marche pas : il en reste toujours un.
    """

    libelle: str
    unite: str = ""
    mini: Optional[float] = None
    maxi: Optional[float] = None
    raison: str = ""
    decimales: int = 3
    #: La borne basse est-elle exclue ? Une charge nulle n'est pas une charge.
    mini_exclu: bool = False

    def ecart(self, valeur: float) -> Optional[str]:
        """En quoi cette valeur sort-elle du domaine ? ``None`` si elle y est."""
        try:
            v = float(valeur)
        except (TypeError, ValueError):
            return "valeur illisible"
        if v != v or v in (float("inf"), float("-inf")):
            return "valeur non numérique"
        if self.mini is not None:
            if self.mini_exclu and v <= self.mini:
                return f"doit être strictement supérieur à {self.mini:g}"
            if not self.mini_exclu and v < self.mini:
                return f"ne peut pas être inférieur à {self.mini:g}"
        if self.maxi is not None and v > self.maxi:
            return f"ne peut pas dépasser {self.maxi:g}"
        return None


#: Domaine physique de chaque grandeur encodée, par nom d'attribut.
DOMAINES: Dict[str, Domaine] = {
    "surface_reference_m2": Domaine(
        "Surface de référence", "m²", mini=0.0, decimales=1,
        raison="une surface ne peut pas être négative"),
    "aire_m2": Domaine(
        "Surface incidente", "m²", mini=0.0, decimales=1,
        raison="une surface ne peut pas être négative"),
    "coefficient": Domaine(
        "Coefficient de ruissellement", "", mini=0.0, maxi=1.0, decimales=2,
        raison="c'est la fraction de la pluie qui ruisselle"),
    "coef_ruissellement": Domaine(
        "Coefficient de ruissellement", "", mini=0.0, maxi=1.0, decimales=2,
        raison="c'est la fraction de la pluie qui ruisselle"),
    "k_infiltration_ms": Domaine(
        "Vitesse d'infiltration K", "m/s", mini=0.0, maxi=1e-2, decimales=8,
        raison="au-delà de 1e-2 m/s (36 000 mm/h) il ne s'agit plus d'un sol"),
    "coef_securite_infiltration": Domaine(
        "Coefficient de sécurité sur l'infiltration", "", mini=1.0, decimales=2,
        raison="il minore le débit d'infiltration, il ne le majore pas"),
    "surface_infiltration_m2": Domaine(
        "Surface d'infiltration", "m²", mini=0.0, decimales=1,
        raison="une surface ne peut pas être négative"),
    "surface_dispersion_m2": Domaine(
        "Surface d'infiltration", "m²", mini=0.0, decimales=1,
        raison="une surface ne peut pas être négative"),
    "surface_bv_m2": Domaine(
        "Surface du bassin versant amont", "m²", mini=0.0, decimales=1,
        raison="une surface ne peut pas être négative"),
    "debit_ajutage_ls": Domaine(
        "Débit d'ajutage", "l/s", mini=0.0,
        raison="un ajutage évacue de l'eau, il n'en apporte pas"),
    "debit_ajutage_specifique_ls_ha": Domaine(
        "Débit d'ajutage spécifique", "l/(s·ha)", mini=0.0, decimales=2,
        raison="un ajutage évacue de l'eau, il n'en apporte pas"),
    "temps_vidange_max_h": Domaine(
        "Temps de vidange maximum admis", "h", mini=0.0, mini_exclu=True, decimales=1,
        raison="une vidange doit disposer d'un délai"),
    "volume_total_m3": Domaine(
        "Volume tampon total", "m³", mini=0.0, decimales=1,
        raison="un volume ne peut pas être négatif"),
    "volume_sous_ajutage_m3": Domaine(
        "Volume sous l'axe de l'ajutage", "m³", mini=0.0, decimales=1,
        raison="un volume ne peut pas être négatif"),
    "volume_temporisation_m3": Domaine(
        "Volume de temporisation du bassin amont", "m³", mini=0.0, decimales=1,
        raison="un volume ne peut pas être négatif"),
    "hauteur_charge_m": Domaine(
        "Charge sur l'ajutage", "m", mini=0.0, mini_exclu=True, decimales=2,
        raison="sans charge, la formule de Torricelli ne donne aucun débit"),
    "coef_debit_orifice": Domaine(
        "Coefficient de débit de l'orifice", "", mini=0.0, maxi=1.0, mini_exclu=True,
        decimales=2,
        raison="un orifice ne débite jamais plus que la vitesse théorique"),
    "diametre_ajutage_mm": Domaine(
        "Diamètre d'ajutage retenu", "mm", mini=0.0, maxi=2000.0, mini_exclu=True,
        decimales=1,
        raison="l'orifice doit rester petit devant la charge : au-delà de 2 m, "
               "ce n'est plus un ajutage mais une ouverture"),
}


def _hors_domaine(objet, champs: Tuple[str, ...], ou: str = "") -> List[str]:
    """Grandeurs d'un objet qui sortent de leur domaine, dites en clair."""
    messages: List[str] = []
    for champ in champs:
        domaine = DOMAINES.get(champ)
        valeur = getattr(objet, champ, None)
        if domaine is None or valeur is None:
            continue
        ecart = domaine.ecart(valeur)
        if ecart is None:
            continue
        precision = f" {ou}" if ou else ""
        # La valeur fautive est citée telle qu'elle a été saisie — mais lisible :
        # « inf » n'est pas un nombre que l'utilisateur reconnaîtra.
        try:
            brut = float(valeur)
        except (TypeError, ValueError):
            chiffre = str(valeur)
        else:
            chiffre = (nombre(brut, domaine.decimales) if not math.isfinite(brut)
                       else f"{brut:.{domaine.decimales}f}".rstrip("0").rstrip(".") or "0")
        messages.append(fr(
            f"{domaine.libelle}{precision} : {chiffre} {domaine.unite}".rstrip()
            + f" — {ecart}"
            + (f" ({domaine.raison})." if domaine.raison else ".")
        ))
    return messages


@dataclass
class Bassin:
    """Ouvrage encodé par l'utilisateur (vérification / simulation)."""

    volume_total_m3: float = 0.0
    volume_sous_ajutage_m3: float = 0.0
    surface_dispersion_m2: float = 0.0
    debit_ajutage_ls: float = 0.0
    #: Vitesse d'infiltration du fond réellement construit [m/s]. ``None``
    #: reprend celle du dimensionnement. Les deux parties ne parlent pas de la
    #: même chose : le dimensionnement cherche les minima sous une hypothèse de
    #: sol, l'ouvrage construit se vérifie sur le sol qu'on y a effectivement
    #: trouvé — un essai d'infiltration en fond de fouille donne rarement la
    #: valeur supposée au départ.
    k_infiltration_ms: Optional[float] = None

    @property
    def volume_tampon_m3(self) -> float:
        """Volume utile situé au-dessus de l'ajutage."""
        return max(self.volume_total_m3 - self.volume_sous_ajutage_m3, 0.0)

    @property
    def ajutage_au_dessus_du_trop_plein(self) -> bool:
        """Le volume mort dépasse-t-il le volume tampon total ?

        Géométriquement impossible : l'axe de l'orifice serait au-dessus du
        trop-plein, et l'ouvrage ne pourrait évacuer que par son fond. Tant que
        personne ne le dit, l'application doit au moins en donner **une** seule
        lecture : le niveau ne peut jamais atteindre l'axe, donc l'ajutage ne
        débite pas — c'est ce que fait le routage du réseau, et la simulation
        s'y range.
        """
        return self.volume_total_m3 > 0 and self.volume_sous_ajutage_m3 > self.volume_total_m3

    @property
    def k_propre(self) -> bool:
        """Le bassin construit a-t-il sa propre vitesse d'infiltration ?"""
        return self.k_infiltration_ms is not None and self.k_infiltration_ms > 0


#: Objets porteurs de grandeurs, et les champs à surveiller sur chacun.
def _porteurs(projet):
    """(objet, grandeurs surveillées, grandeurs qui ont le droit d'être vides).

    Une grandeur « vide » a un sens pour trois d'entre elles seulement : le sol
    propre du bassin construit (vide = on reprend celui du dimensionnement),
    l'ajutage spécifique (vide = c'est le débit absolu qui fait foi) et le
    diamètre d'ajutage retenu (vide = aucun diamètre n'a été choisi dans
    l'abaque). Partout ailleurs, un ``null`` dans le fichier est une donnée
    perdue, pas un choix.
    """
    yield projet, ("surface_reference_m2", "k_infiltration_ms",
                   "coef_securite_infiltration", "surface_infiltration_m2",
                   "debit_ajutage_ls", "debit_ajutage_specifique_ls_ha",
                   "temps_vidange_max_h", "hauteur_charge_m",
                   "coef_debit_orifice", "diametre_ajutage_mm"), (
                       "debit_ajutage_specifique_ls_ha", "diametre_ajutage_mm")
    yield projet.bassin, ("volume_total_m3", "volume_sous_ajutage_m3",
                          "surface_dispersion_m2", "debit_ajutage_ls",
                          "k_infiltration_ms"), ("k_infiltration_ms",)
    yield projet.amont, ("surface_bv_m2", "coef_ruissellement", "debit_ajutage_ls",
                         "surface_dispersion_m2", "k_infiltration_ms",
                         "volume_temporisation_m3"), ()
    for surface in projet.surfaces:
        yield surface, ("aire_m2", "coefficient"), ()


def assainir_valeurs(projet) -> List[str]:
    """Remplace les valeurs non numériques, et dit lesquelles.

    L'infini et le NaN ne sont pas des grandeurs : on ne peut ni les comparer,
    ni les tracer, ni les écrire. Un fichier retouché à la main en apporte, et
    tout ce qui vient ensuite — courbes, tuiles, dossier — s'effondre ou affiche
    « inf m³ ». Ils sont donc ramenés à la borne basse de leur domaine **dès
    l'entrée**, une bonne fois, plutôt que rattrapés à chaque endroit qui les
    affiche : il y en a trop pour les tenir tous.

    Une valeur simplement hors domaine — un volume négatif, un coefficient de
    1,8 — n'est en revanche pas touchée : c'est un chiffre, l'utilisateur doit
    le revoir lui-même et :func:`valeurs_hors_domaine` le lui dit.
    """
    corrections: List[str] = []
    for porteur, champs, optionnels in _porteurs(projet):
        corrections += _assainir(porteur, champs, optionnels)
    return corrections


def _assainir(porteur, champs: Tuple[str, ...], optionnels: Tuple[str, ...] = ()) -> List[str]:
    """Ramène à sa borne basse toute grandeur qui n'est pas un nombre."""
    corrections: List[str] = []
    for champ in champs:
        domaine = DOMAINES.get(champ)
        if domaine is None:
            continue
        valeur = getattr(porteur, champ, None)
        if valeur is None and champ in optionnels:
            continue                       # vide a un sens pour cette grandeur-là
        try:
            brut = float(valeur)           # ``None`` lève ici, et c'est voulu
        except (TypeError, ValueError):
            brut = float("nan")
        if math.isfinite(brut):
            continue
        repli = domaine.mini if domaine.mini is not None else 0.0
        setattr(porteur, champ, repli)
        corrections.append(fr(
            f"{domaine.libelle} : valeur non numérique ({nombre(brut, 0)}) dans le fichier "
            f"du projet, ramenée à {repli:g} {domaine.unite}".rstrip() + "."))
    return corrections


def _porteurs_versant(versant) -> List[str]:
    """Assainit un bassin versant : sa surface de référence et ses surfaces."""
    messages = _assainir(versant, ("surface_reference_m2",))
    for surface in versant.surfaces:
        messages += _assainir(surface, ("aire_m2", "coefficient"))
    return messages


def valeurs_hors_domaine(projet) -> List[str]:
    """Toutes les grandeurs d'un projet qui sortent de leur domaine physique.

    Un seul balayage, sur la table :data:`DOMAINES` : ajouter une grandeur au
    modèle sans lui donner de domaine est la seule façon de lui échapper, et
    c'est visible à la lecture de la table.
    """
    messages = _hors_domaine(projet, (
        "surface_reference_m2", "k_infiltration_ms", "coef_securite_infiltration",
        "surface_infiltration_m2", "debit_ajutage_ls", "debit_ajutage_specifique_ls_ha",
        "temps_vidange_max_h", "hauteur_charge_m", "coef_debit_orifice",
        "diametre_ajutage_mm"))
    messages += _hors_domaine(projet.bassin, (
        "volume_total_m3", "volume_sous_ajutage_m3", "surface_dispersion_m2",
        "debit_ajutage_ls", "k_infiltration_ms"), "de l'ouvrage encodé")
    if projet.amont.actif:
        messages += _hors_domaine(projet.amont, (
            "surface_bv_m2", "coef_ruissellement", "debit_ajutage_ls",
            "surface_dispersion_m2", "k_infiltration_ms", "volume_temporisation_m3"),
            "du bassin amont")
    for surface in projet.surfaces:
        for message in _hors_domaine(surface, ("aire_m2", "coefficient"),
                                     f"« {surface.libelle} »"):
            messages.append(message)
    return messages


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
    #: Débit d'ajutage encodé en l/(s·ha). Renseigné, c'est lui qui fait foi et le
    #: débit absolu se recalcule sur la surface raccordée ; vide, c'est le débit
    #: absolu qui est fixé et le spécifique n'est qu'un affichage.
    debit_ajutage_specifique_ls_ha: Optional[float] = None
    temps_vidange_max_h: float = TEMPS_VIDANGE_LIMITE_H
    #: Surface des bassins versants situés en amont dans le réseau, comptée dans
    #: la surface raccordée à cet ouvrage (débit de fuite admissible et ajutage
    #: spécifique). Le panneau « bassin amont » historique passe, lui, par
    #: ``amont.inclure_bv_dans_ajutage`` : les deux s'additionnent sans se
    #: recouvrir, un projet n'utilisant jamais les deux mécanismes à la fois.
    surface_amont_raccordee_m2: float = 0.0

    # Ouvrage a verifier
    bassin: Bassin = field(default_factory=Bassin)

    # Bassin d'orage amont eventuel
    amont: BassinAmont = field(default_factory=BassinAmont)

    # Ajutage (Torricelli)
    hauteur_charge_m: float = 1.0
    coef_debit_orifice: float = 0.60
    #: Retenir un diamètre de l'abaque commercial. Faux : l'ouvrage s'en tient
    #: au diamètre théorique, que l'utilisateur percera comme il l'entend.
    ajutage_diametre_commercial: bool = True
    #: Diamètre choisi dans l'abaque [mm] ; vide, c'est celui que l'application
    #: propose — le plus grand qui ne dépasse pas le débit visé.
    diametre_ajutage_mm: Optional[float] = None

    # Identification
    nom_projet: str = ""
    auteur: str = ""
    localisation: str = ""
    remarques: str = ""

    # ---- grandeurs derivees -------------------------------------------------
    @property
    def a_un_apport_amont(self) -> bool:
        """Un ouvrage amont alimente-t-il celui-ci ?

        Deux formes d'amont coexistent : le bassin amont unique décrit par
        :class:`BassinAmont` (projets d'avant le réseau, et cas simple), et le
        raccordement d'un réseau de bassins, branché sur cette étude par
        :meth:`Systeme.synchroniser` via :meth:`brancher_apport`. Les deux
        passent par le même chemin de calcul en aval de ce prédicat : il n'y a
        qu'une règle de dimensionnement, quelle que soit la provenance de
        l'apport.
        """
        return self.amont.actif or self._fournisseur_apport is not None

    @property
    def k_bassin_ms(self) -> float:
        """Vitesse d'infiltration du bassin construit.

        Celle encodée dans l'onglet « Bassin réel » si elle l'a été, sinon
        celle du dimensionnement. Reprendre l'hypothèse de départ doit rester
        possible, mais jamais obligatoire.
        """
        return self.bassin.k_infiltration_ms if self.bassin.k_propre else self.k_infiltration_ms

    @property
    def a_un_apport(self) -> bool:
        """De l'eau arrive-t-elle à cet ouvrage ?

        Son propre bassin versant, **ou** ce que lui restituent les ouvrages
        amont. Un ouvrage de fin de réseau n'a souvent aucun versant en direct
        — il ne fait que reprendre l'aval d'un autre bassin : exiger une
        surface incidente propre le privait de sa simulation, de sa table QDF
        et de son chapitre de vérification au dossier, alors que c'est
        précisément là qu'on veut savoir quelle récurrence il encaisse.
        """
        return self.aire_ponderee_m2 > 0 or self.a_un_apport_amont

    @property
    def _fournisseur_apport(self):
        """Hydrogramme amont fourni par le réseau, s'il y en a un.

        Attribut hors dataclass : il n'est ni sérialisé ni comparé, c'est un
        branchement de calcul et non une donnée du projet.
        """
        return self.__dict__.get("_apport_amont")

    def brancher_apport(self, fournisseur) -> None:
        """Branche (ou débranche avec ``None``) l'hydrogramme amont du réseau."""
        self.__dict__["_apport_amont"] = fournisseur

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
        aire = self.aire_totale_m2 + max(self.surface_amont_raccordee_m2, 0.0)
        if self.amont.actif and self.amont.inclure_bv_dans_ajutage:
            aire += max(self.amont.surface_bv_m2, 0.0)
        return aire

    @property
    def debit_specifique_ajutage_ls_ha(self) -> float:
        """Débit d'ajutage rapporté à la surface raccordée [l/(s·ha)].

        C'est la valeur encodée quand l'utilisateur a saisi des l/(s·ha), sinon
        l'équivalent calculé du débit absolu qu'il a fixé.
        """
        if self.debit_ajutage_specifique_ls_ha is not None:
            return self.debit_ajutage_specifique_ls_ha
        aire = self.aire_raccordee_m2
        return self.debit_ajutage_ls * 10000.0 / aire if aire > 0 else 0.0

    @property
    def ajutage_suit_la_surface(self) -> bool:
        """Vrai quand le débit absolu se recalcule sur la surface raccordée."""
        return self.debit_ajutage_specifique_ls_ha is not None

    def fixer_ajutage_absolu(self, debit_ls: float) -> None:
        """L'utilisateur encode des l/s : le débit est figé en valeur absolue."""
        self.debit_ajutage_specifique_ls_ha = None
        self.debit_ajutage_ls = max(debit_ls, 0.0)
        self.bassin.debit_ajutage_ls = self.debit_ajutage_ls

    def fixer_ajutage_specifique(self, debit_ls_ha: float) -> None:
        """L'utilisateur encode des l/(s·ha) : le débit absolu en découle."""
        self.debit_ajutage_specifique_ls_ha = max(debit_ls_ha, 0.0)
        self.recalculer_ajutage()

    def recalculer_ajutage(self) -> float:
        """Recalcule le débit absolu quand la surface raccordée a changé.

        Sans effet si l'utilisateur a fixé un débit en valeur absolue : c'est
        alors une contrainte de rejet, qui ne dépend pas de la surface.
        """
        if self.debit_ajutage_specifique_ls_ha is not None:
            self.debit_ajutage_ls = (
                self.debit_ajutage_specifique_ls_ha * self.aire_raccordee_m2 / 10000.0)
            self.bassin.debit_ajutage_ls = self.debit_ajutage_ls
        return self.debit_ajutage_ls

    def compter_surface_amont(self, inclure: bool) -> float:
        """Compte (ou non) le bassin versant amont dans la surface raccordée.

        Si l'ajutage a été encodé en l/(s·ha), le débit absolu suit la nouvelle
        surface : un bassin versant amont d'un hectare à 5 l/(s·ha) ajoute 5 l/s.
        S'il a été fixé en l/s, il ne bouge pas — seul l'équivalent spécifique
        affiché diminue, ainsi que le débit de fuite admissible.

        Renvoie le débit d'ajutage [l/s] après bascule.
        """
        self.amont.inclure_bv_dans_ajutage = bool(inclure)
        return self.recalculer_ajutage()

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
        surfaces = [SurfaceIncidente(**_champs_connus(SurfaceIncidente, s))
                    for s in data.pop("surfaces", [])]
        bassin = Bassin(**_champs_connus(Bassin, data.pop("bassin", {})))
        amont = BassinAmont(**_champs_connus(BassinAmont, data.pop("amont", {})))
        propre = _champs_connus(cls, data)
        return cls(surfaces=surfaces, bassin=bassin, amont=amont, **propre)


def _champs_connus(classe, data: Dict) -> Dict:
    """Filtre un dictionnaire enregistré sur les champs actuels d'une dataclasse.

    Un projet enregistré par une version antérieure ne connaît pas les champs
    ajoutés depuis : il doit pouvoir se recharger malgré tout. Les champs de
    texte sont ramenés à du texte : un nom d'ouvrage arrivé sous forme de nombre
    faisait tomber l'application au premier ``.strip()``, bien loin du
    chargement — et le message n'aurait rien appris à personne.
    """
    champs = classe.__dataclass_fields__
    return {k: texte_si_besoin(champs[k], v) for k, v in dict(data).items() if k in champs}


def texte_si_besoin(champ, valeur):
    """Ramène à du texte ce qui est déclaré comme tel dans le modèle."""
    if str(champ.type).strip("'\"") != "str" or isinstance(valeur, str):
        return valeur
    return "" if valeur is None else str(valeur)


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
