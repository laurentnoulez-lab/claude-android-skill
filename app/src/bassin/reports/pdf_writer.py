"""Générateur PDF minimaliste en Python pur (aucune dépendance).

Produit un PDF 1.4 : texte (polices de base Helvetica), traits, rectangles
pleins, avec mise en page A4. Suffisant pour un rapport technique et, surtout,
sans aucune extension native - le meme code fonctionne sur Windows et Android.
"""

from __future__ import annotations

import zlib
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

from ..formats import fr

A4 = (595.28, 841.89)  # points

# Largeurs Helvetica (unités/1000) pour l'ASCII imprimable.
_W_REG = {
    " ": 278, "!": 278, '"': 355, "#": 556, "$": 556, "%": 889, "&": 667, "'": 191,
    "(": 333, ")": 333, "*": 389, "+": 584, ",": 278, "-": 333, ".": 278, "/": 278,
    ":": 278, ";": 278, "<": 584, "=": 584, ">": 584, "?": 556, "@": 1015,
    "A": 667, "B": 667, "C": 722, "D": 722, "E": 667, "F": 611, "G": 778, "H": 722,
    "I": 278, "J": 500, "K": 667, "L": 556, "M": 833, "N": 722, "O": 778, "P": 667,
    "Q": 778, "R": 722, "S": 667, "T": 611, "U": 722, "V": 667, "W": 944, "X": 667,
    "Y": 667, "Z": 611, "[": 278, "\\": 278, "]": 278, "^": 469, "_": 556, "`": 333,
    "a": 556, "b": 556, "c": 500, "d": 556, "e": 556, "f": 278, "g": 556, "h": 556,
    "i": 222, "j": 222, "k": 500, "l": 222, "m": 833, "n": 556, "o": 556, "p": 556,
    "q": 556, "r": 333, "s": 500, "t": 278, "u": 556, "v": 500, "w": 722, "x": 500,
    "y": 500, "z": 500, "{": 334, "|": 260, "}": 334, "~": 584,
}
_W_BOLD = dict(_W_REG)
_W_BOLD.update({
    "A": 722, "B": 722, "C": 722, "D": 722, "J": 556, "K": 722, "L": 611,
    "a": 556, "b": 611, "c": 556, "d": 611, "e": 556, "f": 333, "g": 611, "h": 611,
    "i": 278, "j": 278, "k": 556, "l": 278, "m": 889, "n": 611, "o": 611, "p": 611,
    "q": 611, "r": 389, "s": 556, "t": 333, "u": 611, "v": 556, "w": 778, "x": 556,
    "y": 556, "z": 500, "'": 238, "-": 333, ":": 333, ";": 333,
})
for _c in "0123456789":
    _W_REG[_c] = 556
    _W_BOLD[_c] = 556

#: Equivalents ASCII pour la mesure des caracteres accentues.
_BASE = {
    "à": "a", "â": "a", "ä": "a", "á": "a", "ç": "c", "é": "e", "è": "e", "ê": "e",
    "ë": "e", "î": "i", "ï": "i", "í": "i", "ô": "o", "ö": "o", "ó": "o", "ù": "u",
    "û": "u", "ü": "u", "ú": "u", "ÿ": "y", "ñ": "n",
    "À": "A", "Â": "A", "Ä": "A", "Ç": "C", "É": "E", "È": "E", "Ê": "E", "Ë": "E",
    "Î": "I", "Ï": "I", "Ô": "O", "Ö": "O", "Ù": "U", "Û": "U", "Ü": "U", "Ñ": "N",
    "²": "2", "³": "3", "°": "o", "«": '"', "»": '"',
}

#: Substitutions pour rester dans l'encodage WinAnsi.
_SUBST = {
    "œ": "oe", "Œ": "OE", "≤": "<=", "≥": ">=", "→": "->", "–": "-", "—": "-",
    "’": "'", "‘": "'", "“": '"', "”": '"', "…": "...", "×": "x", " ": " ",
}

Couleur = Tuple[float, float, float]


def rgb(r: int, v: int, b: int) -> Couleur:
    return (r / 255.0, v / 255.0, b / 255.0)


NOIR = rgb(15, 23, 42)
BLEU = rgb(29, 78, 216)
GRIS = rgb(100, 116, 139)
GRIS_CLAIR = rgb(226, 232, 240)
BLANC = (1.0, 1.0, 1.0)
VERT = rgb(22, 101, 52)
VERT_PALE = rgb(220, 252, 231)
ROUGE = rgb(185, 28, 28)
ROUGE_PALE = rgb(254, 226, 226)
ORANGE_PALE = rgb(254, 243, 199)
BLEU_PALE = rgb(219, 234, 254)


def nettoyer(texte: str, convertir: bool = True) -> str:
    """Virgule décimale francophone, puis caractères hors WinAnsi remplacés.

    convertir=False pour les titres : la numérotation « 1.1 » garde son point.
    """
    if convertir:
        texte = fr(texte)
    for k, v in _SUBST.items():
        texte = texte.replace(k, v)
    return "".join(c if ord(c) < 256 else "?" for c in texte)


def largeur_texte(texte: str, taille: float, gras: bool = False) -> float:
    table = _W_BOLD if gras else _W_REG
    total = 0
    for ch in nettoyer(texte):
        total += table.get(_BASE.get(ch, ch), 556)
    return total * taille / 1000.0


class Pdf:
    """Document PDF simple, orienté "flux vertical" avec curseur."""

    def __init__(self, marge: float = 42.0, format_page: Tuple[float, float] = A4):
        self.largeur, self.hauteur = format_page
        self.marge = marge
        self.pages: List[List[str]] = []
        self._flux: List[str] = []
        self.y = 0.0
        self.numero = 0
        self.pied: str = ""
        self.nouvelle_page()

    # -- structure ---------------------------------------------------------
    @property
    def largeur_utile(self) -> float:
        return self.largeur - 2 * self.marge

    def nouvelle_page(self) -> None:
        if self._flux:
            self._terminer_page()
        self._flux = []
        self.pages.append(self._flux)
        self.numero = len(self.pages)
        self.y = self.marge

    def _terminer_page(self) -> None:
        if self.pied:
            self._texte_brut(self.marge, self.hauteur - self.marge + 14,
                             self.pied, 7.5, GRIS)
            num = f"{len(self.pages)}"
            self._texte_brut(self.largeur - self.marge - largeur_texte(num, 7.5),
                             self.hauteur - self.marge + 14, num, 7.5, GRIS)

    def espace(self, h: float) -> None:
        self.y += h

    def besoin(self, hauteur: float) -> None:
        """Saute à la page suivante si la place manque."""
        if self.y + hauteur > self.hauteur - self.marge - 18:
            self.nouvelle_page()

    # -- primitives graphiques --------------------------------------------
    def _c(self, valeur: float) -> str:
        return f"{valeur:.2f}"

    def rectangle(self, x: float, y: float, w: float, h: float, couleur: Couleur,
                  bord: Optional[Couleur] = None, epaisseur: float = 0.5) -> None:
        ops = [f"{couleur[0]:.3f} {couleur[1]:.3f} {couleur[2]:.3f} rg"]
        style = "f"
        if bord:
            ops.append(f"{bord[0]:.3f} {bord[1]:.3f} {bord[2]:.3f} RG {epaisseur} w")
            style = "B"
        ops.append(f"{self._c(x)} {self._c(self.hauteur - y - h)} {self._c(w)} {self._c(h)} re {style}")
        self._flux.append(" ".join(ops))

    def ligne(self, x0: float, y0: float, x1: float, y1: float, couleur: Couleur = GRIS,
              epaisseur: float = 0.6, pointilles: bool = False) -> None:
        d = "[2 2] 0 d " if pointilles else "[] 0 d "
        self._flux.append(
            f"{couleur[0]:.3f} {couleur[1]:.3f} {couleur[2]:.3f} RG {epaisseur} w {d}"
            f"{self._c(x0)} {self._c(self.hauteur - y0)} m {self._c(x1)} {self._c(self.hauteur - y1)} l S [] 0 d"
        )

    def _texte_brut(self, x: float, y: float, texte: str, taille: float,
                    couleur: Couleur = NOIR, gras: bool = False, italique: bool = False,
                    convertir: bool = True) -> None:
        police = "F2" if gras else ("F3" if italique else "F1")
        contenu = nettoyer(texte, convertir).replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")
        octets = "".join(f"\\{ord(c):03o}" if ord(c) > 126 else c for c in contenu)
        self._flux.append(
            f"BT /{police} {taille} Tf {couleur[0]:.3f} {couleur[1]:.3f} {couleur[2]:.3f} rg "
            f"{self._c(x)} {self._c(self.hauteur - y)} Td ({octets}) Tj ET"
        )

    # -- flux de contenu ---------------------------------------------------
    def texte(self, texte: str, taille: float = 9.5, gras: bool = False, italique: bool = False,
              couleur: Couleur = NOIR, x: Optional[float] = None, interligne: float = 1.45,
              apres: float = 2.0, centre: bool = False) -> None:
        x = self.marge if x is None else x
        for ligne in self._decouper(texte, self.largeur_utile - (x - self.marge), taille, gras):
            self.besoin(taille * interligne + 2)
            xx = x
            if centre:
                xx = self.marge + (self.largeur_utile - largeur_texte(ligne, taille, gras)) / 2
            self._texte_brut(xx, self.y + taille, ligne, taille, couleur, gras, italique)
            self.y += taille * interligne
        self.y += apres

    def puce(self, texte: str, taille: float = 9.5, couleur: Couleur = NOIR) -> None:
        self.besoin(taille * 1.6)
        self._texte_brut(self.marge + 4, self.y + taille, "•" if False else "-", taille, BLEU, True)
        self.texte(texte, taille, couleur=couleur, x=self.marge + 14)

    def titre(self, texte: str, taille: float = 17.0, couleur: Couleur = BLEU) -> None:
        self.besoin(taille * 2.2)
        self._texte_brut(self.marge, self.y + taille, texte, taille, couleur, True,
                         convertir=False)
        self.y += taille * 1.5

    def titre1(self, texte: str) -> None:
        self.besoin(46)
        self.y += 8
        self._texte_brut(self.marge, self.y + 12, texte, 12.5, BLEU, True, convertir=False)
        self.y += 17
        self.ligne(self.marge, self.y, self.largeur - self.marge, self.y, rgb(191, 219, 254), 1.0)
        self.y += 8

    def titre2(self, texte: str) -> None:
        self.besoin(30)
        self.y += 5
        self._texte_brut(self.marge, self.y + 10.5, texte, 10.5, NOIR, True, convertir=False)
        self.y += 16

    def encadre(self, texte: str, fond: Couleur = BLEU_PALE, couleur: Couleur = NOIR,
                taille: float = 10.0) -> None:
        lignes = self._decouper(texte, self.largeur_utile - 16, taille, True)
        h = len(lignes) * taille * 1.4 + 12
        self.besoin(h + 6)
        self.rectangle(self.marge, self.y, self.largeur_utile, h, fond)
        y = self.y + 8
        for ligne in lignes:
            self._texte_brut(self.marge + 8, y + taille * 0.9, ligne, taille, couleur, True)
            y += taille * 1.4
        self.y += h + 8

    def tableau(self, lignes: Sequence[Sequence], largeurs: Sequence[float], entete: bool = True,
                taille: float = 8.0, fonds: Optional[Dict[Tuple[int, int], Couleur]] = None,
                alignements: Optional[Sequence[str]] = None) -> None:
        """Tableau simple. `largeurs` en points, `fonds` par (ligne, colonne)."""
        fonds = fonds or {}
        alignements = alignements or ["left"] * len(largeurs)
        hauteur_ligne = taille * 1.9
        for i, ligne in enumerate(lignes):
            cellules = [self._decouper(str(v), largeurs[j] - 8, taille, entete and i == 0)
                        for j, v in enumerate(ligne[:len(largeurs)])]
            h = max(hauteur_ligne, max(len(c) for c in cellules) * taille * 1.3 + 6)
            self.besoin(h + 2)
            x = self.marge
            for j, contenu in enumerate(cellules):
                fond = fonds.get((i, j))
                if entete and i == 0:
                    fond = BLEU
                if fond:
                    self.rectangle(x, self.y, largeurs[j], h, fond)
                self.rectangle(x, self.y, largeurs[j], h, fond or BLANC, bord=GRIS_CLAIR, epaisseur=0.4)
                couleur = BLANC if (entete and i == 0) else NOIR
                gras = entete and i == 0
                yy = self.y + (h - len(contenu) * taille * 1.2) / 2 + taille * 0.95
                for texte in contenu:
                    if alignements[j] == "center":
                        xx = x + (largeurs[j] - largeur_texte(texte, taille, gras)) / 2
                    elif alignements[j] == "right":
                        xx = x + largeurs[j] - 4 - largeur_texte(texte, taille, gras)
                    else:
                        xx = x + 4
                    self._texte_brut(xx, yy, texte, taille, couleur, gras)
                    yy += taille * 1.2
                x += largeurs[j]
            self.y += h
        self.y += 6

    def _decouper(self, texte: str, largeur: float, taille: float, gras: bool) -> List[str]:
        lignes: List[str] = []
        for brut in str(texte).split("\n"):
            mots = brut.split(" ")
            courant = ""
            for mot in mots:
                essai = (courant + " " + mot).strip()
                if courant and largeur_texte(essai, taille, gras) > largeur:
                    lignes.append(courant)
                    courant = mot
                else:
                    courant = essai
            lignes.append(courant)
        return lignes or [""]

    # -- enregistrement ----------------------------------------------------
    def enregistrer(self, chemin: str) -> str:
        self._terminer_page()
        objets: List[bytes] = []

        def ajouter(contenu: bytes) -> int:
            objets.append(contenu)
            return len(objets)

        polices = {
            "F1": b"/Helvetica",
            "F2": b"/Helvetica-Bold",
            "F3": b"/Helvetica-Oblique",
        }
        ids_polices = {}
        for cle, nom in polices.items():
            ids_polices[cle] = ajouter(
                b"<< /Type /Font /Subtype /Type1 /BaseFont " + nom + b" /Encoding /WinAnsiEncoding >>"
            )
        ressources = ("<< /Font << " + " ".join(f"/{c} {ids_polices[c]} 0 R" for c in polices) + " >> >>").encode()

        id_pages = ajouter(b"")  # reserve
        ids_pages: List[int] = []
        for flux in self.pages:
            data = zlib.compress("\n".join(flux).encode("latin-1", "replace"))
            id_contenu = ajouter(
                b"<< /Length " + str(len(data)).encode() + b" /Filter /FlateDecode >>\nstream\n" + data + b"\nendstream"
            )
            ids_pages.append(ajouter(
                f"<< /Type /Page /Parent {id_pages} 0 R /MediaBox [0 0 {self.largeur:.2f} {self.hauteur:.2f}] "
                f"/Resources ".encode() + ressources + f" /Contents {id_contenu} 0 R >>".encode()
            ))
        objets[id_pages - 1] = (
            "<< /Type /Pages /Count " + str(len(ids_pages)) + " /Kids ["
            + " ".join(f"{i} 0 R" for i in ids_pages) + "] >>"
        ).encode()
        id_catalogue = ajouter(f"<< /Type /Catalog /Pages {id_pages} 0 R >>".encode())

        sortie = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = [0]
        for i, obj in enumerate(objets, start=1):
            offsets.append(len(sortie))
            sortie += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
        depart_xref = len(sortie)
        sortie += f"xref\n0 {len(objets) + 1}\n".encode()
        sortie += b"0000000000 65535 f \n"
        for off in offsets[1:]:
            sortie += f"{off:010d} 00000 n \n".encode()
        sortie += (f"trailer\n<< /Size {len(objets) + 1} /Root {id_catalogue} 0 R >>\n"
                   f"startxref\n{depart_xref}\n%%EOF\n").encode()
        with open(chemin, "wb") as fh:
            fh.write(bytes(sortie))
        return chemin
