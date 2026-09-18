"""Plan du rapport : ce que le dossier contient, dans quel ordre.

Le dossier PDF et Word suivait un plan figé dans le code. L'utilisateur peut
désormais le composer : désactiver une rubrique qui ne sert pas à son étude,
en ajouter de son cru — texte mis en forme, images —, et les ordonner.

Le plan est une **liste ordonnée de rubriques**. Chacune est soit une rubrique
d'origine, désignée par sa clé et écrite par le code du rapport, soit une
rubrique libre dont l'utilisateur fournit le titre et le contenu. Les deux se
déplacent et s'activent de la même façon : le plan ne fait pas de différence,
seuls les écrivains savent laquelle ils ont sous la main.

Les rubriques portant sur un ouvrage (``PORTEE_OUVRAGE``) sont écrites dans le
chapitre de chaque bassin d'orage. Le bloc des chapitres prend, dans le
document, la place de la **première** d'entre elles : c'est ce qui permet de
n'avoir qu'une seule liste, sans imbrication, tout en laissant l'utilisateur
ordonner aussi bien les rubriques du document que celles des chapitres.
"""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

#: Rubriques d'origine, dans leur ordre par défaut : clé, libellé, portée.
PORTEE_DOCUMENT = "document"
PORTEE_OUVRAGE = "ouvrage"

RUBRIQUES_ORIGINE: Tuple[Tuple[str, str, str], ...] = (
    ("identification", "Bandeau d'identification", PORTEE_DOCUMENT),
    ("versants", "Données d'entrée du projet", PORTEE_DOCUMENT),
    ("reseau", "Synthèse du réseau", PORTEE_DOCUMENT),
    ("donnees", "Données d'entrée de l'ouvrage", PORTEE_OUVRAGE),
    ("pluie", "Pluie de projet", PORTEE_DOCUMENT),
    ("scenarios", "Comparaison des scénarios", PORTEE_OUVRAGE),
    ("verification", "Vérification de l'ouvrage encodé", PORTEE_OUVRAGE),
    ("qdf", "Pluies absorbées sans débordement", PORTEE_OUVRAGE),
    ("ajutage", "Dimensionnement de l'ajutage", PORTEE_OUVRAGE),
    ("conclusion", "Conclusion", PORTEE_DOCUMENT),
    ("signature", "Cartouche de signature", PORTEE_DOCUMENT),
)

LIBELLES_ORIGINE: Dict[str, str] = {cle: libelle for cle, libelle, _p in RUBRIQUES_ORIGINE}
PORTEES_ORIGINE: Dict[str, str] = {cle: portee for cle, _l, portee in RUBRIQUES_ORIGINE}

#: Genres de blocs d'une rubrique libre.
BLOC_PARAGRAPHE = "paragraphe"
BLOC_INTERTITRE = "intertitre"
BLOC_IMAGE = "image"

#: Couleurs proposées pour le texte, du plus sobre au plus voyant.
COULEURS_TEXTE: Tuple[Tuple[str, str], ...] = (
    ("", "Couleur du document"),
    ("#0F172A", "Noir"),
    ("#334155", "Gris ardoise"),
    ("#1D4ED8", "Bleu"),
    ("#059669", "Vert"),
    ("#D97706", "Orange"),
    ("#DC2626", "Rouge"),
)


@dataclass
class Bloc:
    """Un élément d'une rubrique libre : un paragraphe, un intertitre, une image.

    La mise en forme porte sur le bloc entier. Un paragraphe mêlant du gras et
    de l'italique s'obtient en écrivant deux blocs : c'est moins souple qu'un
    traitement de texte, mais tout ce qui est affiché est modifiable, et rien
    ne se cache dans un balisage que l'utilisateur devrait apprendre.
    """

    genre: str = BLOC_PARAGRAPHE
    texte: str = ""
    gras: bool = False
    italique: bool = False
    souligne: bool = False
    #: Couleur « #RRGGBB » ; vide, le texte prend celle du document.
    couleur: str = ""
    #: Image encodée en base64 (PNG ou JPEG), avec sa largeur d'impression.
    image_base64: str = ""
    largeur_cm: float = 14.0
    legende: str = ""

    @property
    def est_image(self) -> bool:
        return self.genre == BLOC_IMAGE

    def image(self) -> bytes:
        """Octets de l'image, ou vide si le bloc n'en porte pas de lisible."""
        if not self.image_base64:
            return b""
        try:
            return base64.b64decode(self.image_base64, validate=True)
        except (binascii.Error, ValueError):
            return b""

    def resume(self) -> str:
        """Ce que le bloc contient, en une ligne, pour l'éditeur."""
        if self.est_image:
            octets = len(self.image())
            return (f"image {octets // 1024} ko · {self.largeur_cm:.0f} cm"
                    if octets else "image illisible")
        texte = " ".join(self.texte.split())
        return (texte[:80] + "…") if len(texte) > 80 else (texte or "(vide)")


@dataclass
class Rubrique:
    """Une rubrique du rapport, d'origine ou libre."""

    #: Clé d'une rubrique d'origine ; vide pour une rubrique libre.
    cle: str = ""
    titre: str = ""
    active: bool = True
    blocs: List[Bloc] = field(default_factory=list)

    @property
    def libre(self) -> bool:
        return not self.cle

    @property
    def portee(self) -> str:
        return PORTEES_ORIGINE.get(self.cle, PORTEE_DOCUMENT)

    @property
    def libelle(self) -> str:
        return self.titre or LIBELLES_ORIGINE.get(self.cle, "Rubrique")


@dataclass
class PlanRapport:
    """Composition du dossier : les rubriques, dans l'ordre voulu."""

    rubriques: List[Rubrique] = field(default_factory=list)

    # ---- lecture ---------------------------------------------------------
    def actives(self) -> List[Rubrique]:
        return [r for r in self.rubriques if r.active]

    def active(self, cle: str) -> bool:
        """Cette rubrique d'origine est-elle retenue ?

        Une clé absente du plan — projet enregistré avant qu'elle n'existe —
        est retenue : une rubrique nouvelle ne doit pas disparaître en silence
        des dossiers déjà composés.
        """
        for rubrique in self.rubriques:
            if rubrique.cle == cle:
                return rubrique.active
        return True

    def rubrique(self, cle: str) -> Optional[Rubrique]:
        for rubrique in self.rubriques:
            if rubrique.cle == cle:
                return rubrique
        return None

    def ouvrage_actives(self) -> List[Rubrique]:
        """Rubriques d'ouvrage retenues, dans l'ordre du plan."""
        return [r for r in self.actives() if r.portee == PORTEE_OUVRAGE]

    def index_des_chapitres(self) -> int:
        """Rang, parmi les rubriques retenues, où s'insère le bloc des chapitres.

        Sur un réseau, les rubriques d'ouvrage sont écrites dans le chapitre de
        chaque bassin : elles forment un bloc, qui prend la place de la
        **dernière** d'entre elles. Une rubrique de document glissée au milieu
        — la pluie de projet, commune à tout le réseau, l'est par défaut — est
        ainsi écrite avant les chapitres, là où elle se lit, au lieu d'être
        renvoyée derrière eux.

        Sans rubrique d'ouvrage retenue, il n'y a pas de chapitre.
        """
        dernier = -1
        for rang, rubrique in enumerate(self.actives()):
            if rubrique.portee == PORTEE_OUVRAGE:
                dernier = rang
        return dernier

    def document_actives(self) -> List[Tuple[int, Rubrique]]:
        """Rubriques de document retenues, avec leur rang parmi les actives."""
        return [(rang, r) for rang, r in enumerate(self.actives())
                if r.portee == PORTEE_DOCUMENT]

    # ---- modification ----------------------------------------------------
    def deplacer(self, index: int, pas: int) -> bool:
        """Déplace une rubrique dans le plan. Renvoie si quelque chose a bougé."""
        cible = index + pas
        if not (0 <= index < len(self.rubriques)) or not (0 <= cible < len(self.rubriques)):
            return False
        self.rubriques[index], self.rubriques[cible] = (self.rubriques[cible],
                                                        self.rubriques[index])
        return True

    def ajouter_libre(self, titre: str = "Nouvelle rubrique", index: Optional[int] = None) -> Rubrique:
        rubrique = Rubrique(titre=titre, blocs=[Bloc()])
        if index is None:
            index = len(self.rubriques)
        self.rubriques.insert(max(0, min(index, len(self.rubriques))), rubrique)
        return rubrique

    def supprimer(self, index: int) -> bool:
        """Retire une rubrique **libre**. Une rubrique d'origine se désactive.

        Supprimer une rubrique d'origine la ferait disparaître du plan, et
        celui-ci la réintroduirait au chargement suivant — le geste serait sans
        effet durable. La case à cocher, elle, est fidèle.
        """
        if not (0 <= index < len(self.rubriques)) or not self.rubriques[index].libre:
            return False
        del self.rubriques[index]
        return True

    # ---- sérialisation ---------------------------------------------------
    def to_dict(self) -> Dict:
        return {"rubriques": [
            {"cle": r.cle, "titre": r.titre, "active": r.active,
             "blocs": [{"genre": b.genre, "texte": b.texte, "gras": b.gras,
                        "italique": b.italique, "souligne": b.souligne,
                        "couleur": b.couleur, "image_base64": b.image_base64,
                        "largeur_cm": b.largeur_cm, "legende": b.legende}
                       for b in r.blocs]}
            for r in self.rubriques]}

    @classmethod
    def from_dict(cls, data) -> "PlanRapport":
        """Relit un plan enregistré, puis le complète.

        Un projet d'avant cette fonction n'en a pas : il reçoit le plan par
        défaut. Un projet enregistré par une version qui ignorait une rubrique
        la voit apparaître à sa place d'origine, retenue.
        """
        rubriques: List[Rubrique] = []
        if isinstance(data, dict):
            for brut in data.get("rubriques", []) or []:
                if not isinstance(brut, dict):
                    continue
                blocs = [Bloc(**{champ: valeur for champ, valeur in b.items()
                                 if champ in Bloc.__dataclass_fields__})
                         for b in (brut.get("blocs") or []) if isinstance(b, dict)]
                rubriques.append(Rubrique(
                    cle=str(brut.get("cle") or ""),
                    titre=str(brut.get("titre") or ""),
                    active=bool(brut.get("active", True)),
                    blocs=blocs))
        plan = cls(rubriques=rubriques)
        plan.completer()
        return plan

    def completer(self) -> None:
        """Ajoute les rubriques d'origine absentes, chacune à sa place.

        La place d'une rubrique nouvelle se lit dans l'ordre par défaut :
        derrière la dernière de ses devancières présentes dans le plan, ou à
        défaut devant la première de ses suivantes. Le plan composé par
        l'utilisateur est conservé ; la nouveauté se glisse là où elle a un sens.
        """
        ordre = [cle for cle, _l, _p in RUBRIQUES_ORIGINE]
        for rang, cle in enumerate(ordre):
            if any(r.cle == cle for r in self.rubriques):
                continue
            position = None
            for devanciere in reversed(ordre[:rang]):
                for i, rubrique in enumerate(self.rubriques):
                    if rubrique.cle == devanciere:
                        position = i + 1
                        break
                if position is not None:
                    break
            if position is None:
                for suivante in ordre[rang + 1:]:
                    for i, rubrique in enumerate(self.rubriques):
                        if rubrique.cle == suivante:
                            position = i
                            break
                    if position is not None:
                        break
            self.rubriques.insert(len(self.rubriques) if position is None else position,
                                  Rubrique(cle=cle))


def plan_par_defaut() -> PlanRapport:
    """Le plan d'origine : toutes les rubriques, dans l'ordre historique."""
    return PlanRapport(rubriques=[Rubrique(cle=cle) for cle, _l, _p in RUBRIQUES_ORIGINE])
