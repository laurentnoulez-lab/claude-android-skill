"""Lecture des images que l'utilisateur ajoute à son dossier.

Le rapport n'embarque aucune bibliothèque : les PNG que l'application produit
elle-même sont écrits à la main dans :mod:`charts`. Une image apportée par
l'utilisateur doit être lue de la même façon — assez pour connaître ses
dimensions, et pour la donner au PDF sous une forme qu'il sait afficher.

Deux formats suffisent à couvrir ce qu'un bureau d'études glisse dans un
dossier : le **PNG** d'une capture ou d'un plan, le **JPEG** d'une photo de
terrain. Le Word les accepte tels quels ; le PDF affiche le JPEG tel quel
(``/DCTDecode``) et demande que le PNG soit ramené à ses octets bruts.
"""

from __future__ import annotations

import struct
import zlib
from typing import NamedTuple, Optional

SIGNATURE_PNG = b"\x89PNG\r\n\x1a\n"

#: Nombre de canaux par type de couleur PNG.
_CANAUX = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


class Image(NamedTuple):
    """Une image lisible, décrite par ce dont les écrivains ont besoin."""

    format: str          # "png" ou "jpeg"
    largeur: int
    hauteur: int
    octets: bytes        # le fichier d'origine, tel qu'il a été fourni
    #: Composantes de couleur : 1 (gris), 3 (RVB) ou 4 (quadrichromie CMJN).
    #: Le PDF affiche le JPEG sans le décoder : il doit donc annoncer l'espace
    #: de couleur du fichier. L'annoncer en RVB quel qu'il soit rendait une
    #: photo en niveaux de gris en trois bandes noircies.
    canaux: int = 3


def est_png(donnees: bytes) -> bool:
    return donnees[:8] == SIGNATURE_PNG


def est_jpeg(donnees: bytes) -> bool:
    return donnees[:2] == b"\xff\xd8"


def lire(donnees: bytes) -> Optional[Image]:
    """Décrit une image, ou ``None`` si le format n'est pas reconnu."""
    if not donnees:
        return None
    if est_png(donnees):
        entete = _entete_png(donnees)
        if entete is None:
            return None
        largeur, hauteur, canaux = entete
        return Image("png", largeur, hauteur, donnees, canaux)
    if est_jpeg(donnees):
        entete = _entete_jpeg(donnees)
        if entete is None:
            return None
        largeur, hauteur, canaux = entete
        return Image("jpeg", largeur, hauteur, donnees, canaux)
    return None


#: Composantes de couleur d'un PNG une fois la palette étendue et l'alpha écarté.
_CANAUX_AFFICHES = {0: 1, 2: 3, 3: 3, 4: 1, 6: 3}


def _entete_png(donnees: bytes):
    """(largeur, hauteur, composantes de couleur) lues dans l'IHDR."""
    if len(donnees) < 26 or donnees[12:16] != b"IHDR":
        return None
    largeur, hauteur = struct.unpack(">II", donnees[16:24])
    canaux = _CANAUX_AFFICHES.get(donnees[25], 3)
    return (largeur, hauteur, canaux) if largeur and hauteur else None


def _entete_jpeg(donnees: bytes):
    """(largeur, hauteur, composantes) lues dans le segment SOF.

    Le nombre de composantes suit immédiatement les dimensions : 1 pour une
    image en niveaux de gris, 3 pour du RVB (YCbCr), 4 pour de la
    quadrichromie. Les autres segments sont sautés par leur longueur.
    """
    i = 2
    taille = len(donnees)
    while i + 9 < taille:
        if donnees[i] != 0xFF:
            i += 1
            continue
        marqueur = donnees[i + 1]
        if marqueur in (0xD8, 0xD9) or 0xD0 <= marqueur <= 0xD7 or marqueur == 0x01:
            i += 2
            continue
        longueur = struct.unpack(">H", donnees[i + 2:i + 4])[0]
        # SOF0..SOF15, hormis les marqueurs qui ne décrivent pas une trame.
        if 0xC0 <= marqueur <= 0xCF and marqueur not in (0xC4, 0xC8, 0xCC):
            hauteur, largeur = struct.unpack(">HH", donnees[i + 5:i + 9])
            composantes = donnees[i + 9] if i + 9 < taille else 3
            return (largeur, hauteur, composantes) if largeur and hauteur else None
        i += 2 + longueur
    return None


class PngBrut(NamedTuple):
    """PNG ramené à ses échantillons, prêt pour un objet image PDF."""

    largeur: int
    hauteur: int
    #: 1 (gris) ou 3 (rouge-vert-bleu).
    canaux: int
    octets: bytes


def png_en_brut(donnees: bytes) -> Optional[PngBrut]:
    """Défiltre un PNG et rend ses échantillons, sans canal alpha.

    Renvoie ``None`` pour ce qui n'est pas traité — entrelacement Adam7,
    palette sans table, profondeur inhabituelle : mieux vaut ne pas afficher
    l'image que la déformer. L'appelant le dit alors à l'utilisateur.
    """
    if not est_png(donnees):
        return None
    largeur = hauteur = profondeur = couleur = entrelacement = 0
    donnees_idat = bytearray()
    palette = b""
    i = 8
    while i + 8 <= len(donnees):
        longueur = struct.unpack(">I", donnees[i:i + 4])[0]
        genre = donnees[i + 4:i + 8]
        corps = donnees[i + 8:i + 8 + longueur]
        if genre == b"IHDR":
            largeur, hauteur, profondeur, couleur = struct.unpack(">IIBB", corps[:10])
            entrelacement = corps[12]
        elif genre == b"PLTE":
            palette = bytes(corps)
        elif genre == b"IDAT":
            donnees_idat += corps
        elif genre == b"IEND":
            break
        i += 12 + longueur
    if not (largeur and hauteur) or entrelacement or profondeur not in (8, 16):
        return None
    canaux = _CANAUX.get(couleur)
    if canaux is None or (couleur == 3 and not palette):
        return None

    octets_par_pixel = canaux * (profondeur // 8)
    par_ligne = largeur * octets_par_pixel
    try:
        flux = zlib.decompress(bytes(donnees_idat))
    except zlib.error:
        return None
    if len(flux) < (par_ligne + 1) * hauteur:
        return None

    lignes = bytearray()
    precedente = bytearray(par_ligne)
    position = 0
    for _ in range(hauteur):
        filtre = flux[position]
        ligne = bytearray(flux[position + 1:position + 1 + par_ligne])
        position += par_ligne + 1
        _defiltrer(filtre, ligne, precedente, octets_par_pixel)
        lignes += ligne
        precedente = ligne

    return _sans_alpha(largeur, hauteur, profondeur, couleur, canaux, bytes(lignes), palette)


def _defiltrer(filtre: int, ligne: bytearray, precedente: bytearray, pas: int) -> None:
    """Applique à rebours le filtre d'une ligne PNG (norme, § 9.2)."""
    if filtre == 0:
        return
    for k in range(len(ligne)):
        gauche = ligne[k - pas] if k >= pas else 0
        haut = precedente[k]
        coin = precedente[k - pas] if k >= pas else 0
        if filtre == 1:
            ligne[k] = (ligne[k] + gauche) & 0xFF
        elif filtre == 2:
            ligne[k] = (ligne[k] + haut) & 0xFF
        elif filtre == 3:
            ligne[k] = (ligne[k] + (gauche + haut) // 2) & 0xFF
        elif filtre == 4:
            p = gauche + haut - coin
            pa, pb, pc = abs(p - gauche), abs(p - haut), abs(p - coin)
            voisin = gauche if (pa <= pb and pa <= pc) else (haut if pb <= pc else coin)
            ligne[k] = (ligne[k] + voisin) & 0xFF
        else:
            return


def _sans_alpha(largeur, hauteur, profondeur, couleur, canaux, brut, palette):
    """Ramène les échantillons à 8 bits, sans transparence ni palette.

    Le PDF affiche du gris ou du rouge-vert-bleu ; la transparence, qu'il
    faudrait porter par un masque, est simplement écartée — une image de
    dossier s'imprime sur du blanc.
    """
    pas = 2 if profondeur == 16 else 1
    utiles = {0: 1, 2: 3, 3: 1, 4: 1, 6: 3}[couleur]
    sortie = bytearray(largeur * hauteur * (3 if couleur == 3 else utiles))
    j = 0
    for i in range(0, len(brut), canaux * pas):
        if couleur == 3:
            index = brut[i]
            sortie[j:j + 3] = palette[index * 3:index * 3 + 3] or b"\x00\x00\x00"
            j += 3
            continue
        for c in range(utiles):
            sortie[j] = brut[i + c * pas]
            j += 1
    return PngBrut(largeur, hauteur, 3 if couleur == 3 else utiles, bytes(sortie[:j]))
