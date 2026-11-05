"""Contrôle mécanique des captures d'écran produites par « Captures d'interface ».

    python3 tools/controle_captures.py ../captures

Cherche dans chaque image un aplat **rectangulaire d'un seul tenant**. Le fond
des cartes couvre légitimement 60 à 90 % d'un écran, mais il est partout percé
de textes, de champs et de bordures : son plus grand rectangle uniforme reste
petit. Un contrôle mal posé produit au contraire un pavé plein — c'est ainsi
que s'est manifesté, sur cinq onglets à la fois, un contrôle extensible placé
dans une rangée repliable, que l'arbre de contrôles ne trahissait pas.

Ce n'est pas un test : il signale ce qui mérite un coup d'œil. Un tableau
étroit sur une carte large dépasse le seuil sans être un défaut. Relire les
images reste la seule vérification qui vaille ; ceci dit seulement par où
commencer.
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import struct
import sys
import zlib
from typing import List, Sequence, Tuple

#: Part de l'écran qu'un seul pavé plein ne devrait pas dépasser. Sur la 3.0.0,
#: les captures saines plafonnent à 21 % une fois mis à part l'abaque des
#: diamètres, un tableau à trois colonnes sur une carte de tablette.
SEUIL = 0.30

#: La barre de titre est un aplat légitime : elle sort du calcul.
BANDE_TITRE = 0.06

#: Un pixel sur trois suffit à révéler un pavé, et l'analyse reste rapide.
PAS = 3


def lire_png(chemin: pathlib.Path) -> Tuple[int, int, int, List[bytes]]:
    """Décode un PNG RVB(A) non entrelacé, sans dépendance externe."""
    data = chemin.read_bytes()
    position, morceaux, canaux = 8, [], 3
    largeur = hauteur = 0
    while position < len(data):
        taille = struct.unpack(">I", data[position:position + 4])[0]
        nom = data[position + 4:position + 8]
        contenu = data[position + 8:position + 8 + taille]
        if nom == b"IHDR":
            largeur, hauteur, profondeur, couleur = struct.unpack(">IIBB", contenu[:10])
            if profondeur != 8 or couleur not in (2, 6):
                raise ValueError(f"{chemin.name} : PNG ni RVB ni RVBA sur 8 bits")
            canaux = 3 if couleur == 2 else 4
        elif nom == b"IDAT":
            morceaux.append(contenu)
        elif nom == b"IEND":
            break
        position += 12 + taille
    brut = zlib.decompress(b"".join(morceaux))
    lignes: List[bytes] = []
    precedente = bytearray(largeur * canaux)
    index = 0
    for _ in range(hauteur):
        filtre = brut[index]
        index += 1
        ligne = bytearray(brut[index:index + largeur * canaux])
        index += largeur * canaux
        if filtre:
            _defiltrer(ligne, precedente, filtre, canaux)
        lignes.append(bytes(ligne))
        precedente = ligne
    return largeur, hauteur, canaux, lignes


def _defiltrer(ligne: bytearray, precedente: bytearray, filtre: int, canaux: int) -> None:
    """Applique en place l'un des cinq filtres de la norme PNG."""
    for x in range(len(ligne)):
        a = ligne[x - canaux] if x >= canaux else 0
        b = precedente[x]
        c = precedente[x - canaux] if x >= canaux else 0
        if filtre == 1:
            ligne[x] = (ligne[x] + a) & 255
        elif filtre == 2:
            ligne[x] = (ligne[x] + b) & 255
        elif filtre == 3:
            ligne[x] = (ligne[x] + (a + b) // 2) & 255
        else:
            p = a + b - c
            pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            ligne[x] = (ligne[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255


def plus_grand_pave(masque: Sequence[Sequence[bool]], largeur: int, hauteur: int) -> int:
    """Aire du plus grand rectangle plein d'un masque binaire.

    Classique : chaque ligne devient un histogramme des hauteurs courantes, dont
    on prend le plus grand rectangle à la pile.
    """
    meilleur = 0
    hauteurs = [0] * largeur
    for y in range(hauteur):
        ligne = masque[y]
        for x in range(largeur):
            hauteurs[x] = hauteurs[x] + 1 if ligne[x] else 0
        pile: List[Tuple[int, int]] = []
        for x in range(largeur + 1):
            h = hauteurs[x] if x < largeur else 0
            depart = x
            while pile and pile[-1][1] >= h:
                depart, hauteur_pile = pile.pop()
                meilleur = max(meilleur, hauteur_pile * (x - depart))
            pile.append((depart, h))
    return meilleur


def mesurer(chemin: pathlib.Path) -> Tuple[float, str]:
    """Part de l'écran occupée par le plus grand pavé plein, et sa couleur."""
    largeur, hauteur, canaux, lignes = lire_png(chemin)
    depart = int(hauteur * BANDE_TITRE)
    colonnes = range(0, largeur, PAS)
    rangees = range(depart, hauteur, PAS)
    pixels = [[lignes[y][x * canaux:x * canaux + 3] for x in colonnes] for y in rangees]
    if not pixels or not pixels[0]:
        return 0.0, ""
    dominante, _ = collections.Counter(p for ligne in pixels for p in ligne).most_common(1)[0]
    masque = [[p == dominante for p in ligne] for ligne in pixels]
    aire = plus_grand_pave(masque, len(pixels[0]), len(pixels))
    return aire / (len(pixels) * len(pixels[0])), dominante.hex()


def main(argv: Sequence[str] | None = None) -> int:
    analyse = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    analyse.add_argument("dossier", type=pathlib.Path, help="dossier des captures")
    analyse.add_argument("--seuil", type=float, default=SEUIL,
                         help=f"part de l'écran à ne pas dépasser (défaut {SEUIL:.0%})")
    options = analyse.parse_args(argv)

    images = sorted(options.dossier.glob("*.png"))
    if not images:
        print(f"aucune capture dans {options.dossier}", file=sys.stderr)
        return 2

    mesures = [(mesurer(chemin), chemin) for chemin in images]
    mesures.sort(key=lambda m: m[0][0], reverse=True)
    signales = [(part, couleur, chemin) for (part, couleur), chemin in mesures
                if part > options.seuil]

    print(f"{len(images)} captures analysées")
    for part, couleur, chemin in signales:
        print(f"  À RELIRE  {chemin.name} : pavé #{couleur} sur {part:.0%} de l'écran")
    if not signales:
        pire, chemin = mesures[0][0][0], mesures[0][1]
        print(f"  aucun pavé au-delà de {options.seuil:.0%} "
              f"(le plus étendu : {pire:.0%}, {chemin.name})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
