"""Schéma du réseau : description commune à l'écran, au PDF et aux rapports.

Le schéma se lit de gauche à droite, de l'amont vers l'exutoire. Chaque bassin
d'orage occupe une **colonne** déterminée par sa distance à l'exutoire, si bien
qu'un raccordement relie toujours deux colonnes voisines : aucune flèche ne
traverse une boîte, et rien ne se superpose.

.. code::

    colonne 0          colonne 1          colonne 2
    ┌─────────┐        ┌─────────┐        ┌─────────┐
    │ BV amont│──┐     │         │        │         │
    └─────────┘  └────►│ BO amont│───────►│ BO aval │───► exutoire
                       └─────────┘        └─────────┘

Ce module ne dessine rien : il calcule des boîtes, des flèches et leurs
positions. Le PDF les trace au vecteur, l'interface les rend en contrôles Flet,
et les rapports Word et Excel en tirent un arbre indenté.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from ..formats import fr

#: Genres de boîte, pour le style.
VERSANT = "versant"
OUVRAGE = "ouvrage"
EXUTOIRE = "exutoire"

#: Géométrie nominale (unités arbitraires, mises à l'échelle au tracé).
LARGEUR_BOITE = 172.0
LARGEUR_VERSANT = 140.0
HAUTEUR_LIGNE = 12.0
HAUTEUR_TITRE = 19.0
MARGE_BOITE = 8.0
ESPACE_COLONNE = 66.0
ESPACE_LIGNE = 16.0
ESPACE_VERSANT = 8.0

#: Au-delà, une ligne de boîte serait tronquée à l'affichage : mieux vaut la
#: couper à la source que laisser un texte déborder sur son voisin.
CARACTERES_MAX = 33


@dataclass
class Boite:
    """Un rectangle du schéma : bassin versant, bassin d'orage ou exutoire."""

    cle: str
    genre: str
    titre: str
    lignes: List[str] = field(default_factory=list)
    statut: str = "OK"
    colonne: int = 0
    x: float = 0.0
    y: float = 0.0
    largeur: float = LARGEUR_BOITE
    hauteur: float = 0.0

    @property
    def milieu_y(self) -> float:
        return self.y + self.hauteur / 2.0

    @property
    def droite(self) -> float:
        return self.x + self.largeur

    @property
    def bas(self) -> float:
        return self.y + self.hauteur


@dataclass
class Fleche:
    """Un raccordement, tracé d'une boîte à l'autre."""

    depuis: str
    vers: str
    points: List[Tuple[float, float]] = field(default_factory=list)
    libelle: str = ""
    pointille: bool = False


@dataclass
class Schema:
    titre: str = ""
    sous_titre: str = ""
    boites: List[Boite] = field(default_factory=list)
    fleches: List[Fleche] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)
    largeur: float = 0.0
    hauteur: float = 0.0

    def colonnes(self) -> List[List[Boite]]:
        """Boîtes regroupées par colonne, de l'amont vers l'exutoire."""
        if not self.boites:
            return []
        n = max(b.colonne for b in self.boites) + 1
        groupes: List[List[Boite]] = [[] for _ in range(n)]
        for b in self.boites:
            groupes[b.colonne].append(b)
        return groupes

    def boite(self, cle: str) -> Optional[Boite]:
        for b in self.boites:
            if b.cle == cle:
                return b
        return None

    def ouvrages(self) -> List[Boite]:
        return [b for b in self.boites if b.genre == OUVRAGE]


def _hauteur(nb_lignes: int) -> float:
    return HAUTEUR_TITRE + nb_lignes * HAUTEUR_LIGNE + 2 * MARGE_BOITE


def rangs(systeme) -> Dict[str, int]:
    """Distance de chaque ouvrage à son exutoire (0 = rejet au milieu naturel).

    C'est ce rang qui garantit qu'un raccordement ne saute jamais de colonne :
    un ouvrage est toujours exactement un rang plus haut que celui qui le reçoit.
    """
    valeurs: Dict[str, int] = {}

    def calculer(identifiant: str, vus: Tuple[str, ...] = ()) -> int:
        if identifiant in valeurs:
            return valeurs[identifiant]
        if identifiant in vus:      # boucle : coupée, elle est signalée ailleurs
            return 0
        aval = systeme.aval(identifiant)
        rang = 0 if aval is None else 1 + calculer(aval.id, vus + (identifiant,))
        valeurs[identifiant] = rang
        return rang

    for o in systeme.ouvrages:
        calculer(o.id)
    return valeurs


def construire(systeme, fiches=None, simulation_systeme=None) -> Schema:
    """Construit le schéma du réseau, données de chaque ouvrage comprises."""
    par_id = {f.ouvrage.id: f for f in (fiches or [])}
    distances = rangs(systeme)
    maxi = max(distances.values(), default=0)

    schema = Schema(
        titre="Schéma du réseau",
        sous_titre=(f"{systeme.commune_nom} · pluie de projet T = {systeme.periode_retour} ans · "
                    f"vidange maximale admise {systeme.temps_vidange_max_h:.0f} h"),
    )

    # Une colonne par rang, l'amont à gauche ; l'exutoire occupe la dernière.
    colonnes: Dict[int, List[Boite]] = {}
    for o in systeme.ouvrages:
        colonne = maxi - distances.get(o.id, 0)
        fiche = par_id.get(o.id)
        lignes = _lignes_ouvrage(systeme, o, fiche, simulation_systeme)
        boite = Boite(cle=o.id, genre=OUVRAGE, titre=o.nom, lignes=lignes,
                      statut=fiche.statut if fiche else "OK", colonne=colonne,
                      hauteur=_hauteur(len(lignes)))
        colonnes.setdefault(colonne, []).append(boite)

    exutoire = Boite(cle="__exutoire__", genre=EXUTOIRE, titre="Exutoire",
                     lignes=["milieu naturel"], colonne=maxi + 1,
                     largeur=LARGEUR_VERSANT, hauteur=_hauteur(1))
    colonnes.setdefault(maxi + 1, []).append(exutoire)

    # Les bassins versants se rangent dans la colonne à gauche de leur ouvrage ;
    # la colonne -1 accueille ceux des ouvrages de tête.
    versants_par_ouvrage: Dict[str, List[Boite]] = {}
    for bv in systeme.bassins_versants:
        cible = systeme.ouvrage(bv.bassin_id)
        lignes = [
            fr(f"{bv.aire_totale_m2:,.0f} m²".replace(",", " ")),
            fr(f"C moyen {bv.coefficient_moyen:.2f}"),
            fr(f"{bv.aire_ponderee_m2:,.0f} m² actifs".replace(",", " ")),
        ]
        lignes = [_couper(ligne) for ligne in lignes]
        boite = Boite(cle=f"bv:{bv.id}", genre=VERSANT, titre=bv.nom, lignes=lignes,
                      largeur=LARGEUR_VERSANT, hauteur=_hauteur(len(lignes)),
                      statut="OK" if cible is not None else "DEBORDEMENT")
        versants_par_ouvrage.setdefault(bv.bassin_id, []).append(boite)

    schema.boites = _placer(colonnes, versants_par_ouvrage, schema)
    schema.fleches = _relier(systeme, schema)
    schema.notes = _notes(systeme, fiches, simulation_systeme)
    return schema


def _lignes_ouvrage(systeme, ouvrage, fiche, simulation_systeme) -> List[str]:
    """Les chiffres qui comptent pour cet ouvrage, en quelques lignes."""
    etude = ouvrage.etude
    bassin = etude.bassin
    lignes = [fr(f"Volume {bassin.volume_total_m3:.1f} m³")]
    if fiche is not None:
        lignes.append(fr(f"minimum {fiche.volume_minimal_m3:.1f} m³"))
        if fiche.resultat.duree_critique_min:
            lignes.append(fr(f"pluie critique {fiche.resultat.duree_critique_hm}"))
            lignes.append(fr(f"hauteur {fiche.resultat.hauteur_pluie_mm:.1f} mm"))
        lignes.append(fr(f"vidange {fiche.resultat.temps_vidange_hm}"))
    lignes.append(fr(f"Q ajutage {bassin.debit_ajutage_ls:.2f} l/s"))
    q_inf = ouvrage.debit_infiltration_ls()
    if q_inf > 0:
        lignes.append(fr(f"Q infiltration {q_inf:.2f} l/s"))
    if bassin.volume_sous_ajutage_m3 > 0:
        lignes.append(fr(f"dont {bassin.volume_sous_ajutage_m3:.1f} m³ sous l'axe"))
    if simulation_systeme is not None:
        res = simulation_systeme.resultat(ouvrage.id)
        if res is not None and res.debordement:
            lignes.append(fr(f"déborde de {res.volume_debordement_m3:.1f} m³"))
    return [_couper(ligne) for ligne in lignes]


def _couper(texte: str) -> str:
    """Tronque une ligne trop longue plutôt que de la laisser déborder."""
    return texte if len(texte) <= CARACTERES_MAX else texte[:CARACTERES_MAX - 1] + "…"


def _placer(colonnes, versants_par_ouvrage, schema: Schema) -> List[Boite]:
    """Donne à chaque boîte sa place, sans recouvrement possible.

    Chaque colonne d'ouvrages est précédée, si besoin, d'un **couloir** réservé
    à ses bassins versants : sans ce couloir, les versants d'une colonne se
    posaient sur les ouvrages de la colonne précédente. Verticalement, les
    versants d'un ouvrage sont empilés en face de lui et leur hauteur cumulée
    fixe celle de la cellule, si bien que l'ouvrage suivant commence en dessous
    de tout ce qui précède.
    """
    boites: List[Boite] = []
    indices = sorted(colonnes)
    largeurs = {c: max((b.largeur for b in colonnes[c]), default=LARGEUR_BOITE)
                for c in indices}
    couloirs = {c: any(versants_par_ouvrage.get(b.cle) for b in colonnes[c])
                for c in indices}

    x_colonne: Dict[int, float] = {}
    x = 0.0
    for colonne in indices:
        if couloirs[colonne]:
            x += LARGEUR_VERSANT + ESPACE_COLONNE
        x_colonne[colonne] = x
        x += largeurs[colonne] + ESPACE_COLONNE
    largeur_totale = max(x - ESPACE_COLONNE, 0.0)

    hauteur_totale = 0.0
    for colonne in indices:
        y = 0.0
        for boite in colonnes[colonne]:
            versants = versants_par_ouvrage.get(boite.cle, [])
            hauteur_versants = (sum(v.hauteur for v in versants)
                                + ESPACE_VERSANT * max(len(versants) - 1, 0))
            hauteur_cellule = max(boite.hauteur, hauteur_versants)
            boite.x = x_colonne[colonne]
            boite.y = y + (hauteur_cellule - boite.hauteur) / 2.0
            boites.append(boite)
            x_versant = x_colonne[colonne] - ESPACE_COLONNE - LARGEUR_VERSANT
            y_versant = y + (hauteur_cellule - hauteur_versants) / 2.0
            for versant in versants:
                versant.colonne = max(colonne - 1, 0)
                versant.x = x_versant
                versant.y = y_versant
                y_versant += versant.hauteur + ESPACE_VERSANT
                boites.append(versant)
            y += hauteur_cellule + ESPACE_LIGNE
        hauteur_totale = max(hauteur_totale, y - ESPACE_LIGNE)

    # Les bassins versants qui ne mènent nulle part se rangent au bout, sous le
    # réseau : ils sont signalés plutôt que passés sous silence.
    connus = {b.cle for b in boites}
    orphelins = [b for cle, groupe in versants_par_ouvrage.items() if cle not in connus
                 for b in groupe]
    if orphelins:
        y = hauteur_totale + ESPACE_LIGNE
        for versant in orphelins:
            versant.x = 0.0
            versant.y = y
            versant.colonne = 0
            boites.append(versant)
            y += versant.hauteur + ESPACE_VERSANT
        hauteur_totale = y - ESPACE_VERSANT
        largeur_totale = max(largeur_totale, LARGEUR_VERSANT)

    schema.largeur = largeur_totale
    schema.hauteur = hauteur_totale
    return boites


def _relier(systeme, schema: Schema) -> List[Fleche]:
    """Trace les raccordements : bassins versants, réseau, surverses."""
    fleches: List[Fleche] = []

    def coude(depart: Boite, arrivee: Boite, libelle: str = "",
              pointille: bool = False) -> Fleche:
        milieu = (depart.droite + arrivee.x) / 2.0
        return Fleche(
            depuis=depart.cle, vers=arrivee.cle,
            points=[(depart.droite, depart.milieu_y), (milieu, depart.milieu_y),
                    (milieu, arrivee.milieu_y), (arrivee.x, arrivee.milieu_y)],
            libelle=libelle, pointille=pointille)

    for bv in systeme.bassins_versants:
        depart = schema.boite(f"bv:{bv.id}")
        arrivee = schema.boite(bv.bassin_id)
        if depart is not None and arrivee is not None:
            fleches.append(coude(depart, arrivee))
    for o in systeme.ouvrages:
        depart = schema.boite(o.id)
        if depart is None:
            continue
        aval = systeme.aval(o.id)
        arrivee = schema.boite(aval.id) if aval is not None else schema.boite("__exutoire__")
        if arrivee is None:
            continue
        if aval is None:
            fleches.append(coude(depart, arrivee, "rejet"))
        elif o.surverse_vers_milieu_naturel:
            fleches.append(coude(depart, arrivee, "ajutage · surverse au milieu naturel",
                                 pointille=True))
        else:
            fleches.append(coude(depart, arrivee, "ajutage + surverse"))
    return fleches


def _notes(systeme, fiches, simulation_systeme) -> List[str]:
    notes: List[str] = []
    notes.append(fr(f"Surface active totale : {systeme.aire_ponderee_m2:.0f} m² "
                    f"sur {systeme.aire_totale_m2:.0f} m² incidents "
                    f"(C moyen {systeme.coefficient_moyen:.3f})."))
    notes.append(fr(f"Volume de temporisation encodé : {systeme.volume_total_m3:.1f} m³ "
                    f"sur {len(systeme.ouvrages)} ouvrage(s)."))
    if fiches:
        total = sum(f.volume_minimal_m3 for f in fiches)
        notes.append(fr(f"Volume minimal cumulé pour éviter toute surverse : {total:.1f} m³."))
    if simulation_systeme is not None:
        notes.append(fr(
            f"Averse la plus défavorable du système : {simulation_systeme.hauteur_mm:.1f} mm "
            f"en {simulation_systeme.duree_min:.0f} min ; vidange la plus longue "
            f"{simulation_systeme.temps_vidange_max_h:.1f} h."))
    notes.extend(systeme.anomalies())
    return notes


def arbre_texte(systeme, fiches=None) -> List[str]:
    """Le même réseau en arbre indenté — pour Word, Excel et le texte brut."""
    par_id = {f.ouvrage.id: f for f in (fiches or [])}
    lignes: List[str] = []

    def decrire(ouvrage, profondeur: int) -> None:
        marge = "    " * profondeur
        fiche = par_id.get(ouvrage.id)
        bassin = ouvrage.etude.bassin
        detail = f"{bassin.volume_total_m3:.1f} m³"
        if fiche is not None:
            detail += f" (minimum {fiche.volume_minimal_m3:.1f} m³)"
        lignes.append(fr(f"{marge}+-- {ouvrage.nom} : {detail}"))
        for bv in systeme.versants_de(ouvrage.id):
            lignes.append(fr(f"{marge}    |   bassin versant « {bv.nom} » : "
                             f"{bv.aire_totale_m2:.0f} m², C moyen {bv.coefficient_moyen:.2f}, "
                             f"{bv.aire_ponderee_m2:.0f} m² actifs"))
        for amont in systeme.amonts_directs(ouvrage.id):
            decrire(amont, profondeur + 1)

    for terminal in systeme.exutoires:
        lignes.append(fr(f"Exutoire <- {terminal.nom}"))
        decrire(terminal, 0)
    for bv in systeme.versants_orphelins():
        lignes.append(fr(f"(non raccordé) bassin versant « {bv.nom} » : "
                         f"{bv.aire_totale_m2:.0f} m²"))
    return lignes
