"""Graphiques : description commune + rasterisation PNG en Python pur.

Aucune dépendance native (ni matplotlib, ni Pillow) : le PNG est encodé a la
main (zlib + CRC32), ce qui permet d'embarquer les graphiques dans les rapports
DOCX aussi bien sur Windows que sur Android.
"""

from __future__ import annotations

import math
import struct
import zlib
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Tuple

Couleur = Tuple[int, int, int]

BLEU = (37, 99, 235)
BLEU_CLAIR = (147, 197, 253)
VERT = (5, 150, 105)
ORANGE = (234, 88, 12)
ROUGE = (220, 38, 38)
VIOLET = (124, 58, 237)
GRIS = (100, 116, 139)
GRIS_CLAIR = (226, 232, 240)
NOIR = (15, 23, 42)
BLANC = (255, 255, 255)


@dataclass
class Serie:
    nom: str
    points: List[Tuple[float, float]]
    couleur: Couleur = BLEU
    aire: bool = False
    pointilles: bool = False
    epaisseur: int = 2


@dataclass
class Repere:
    """Ligne de référence horizontale ou verticale."""

    valeur: float
    nom: str
    couleur: Couleur = ROUGE
    vertical: bool = False


@dataclass
class Graphique:
    titre: str = ""
    axe_x: str = ""
    axe_y: str = ""
    series: List[Serie] = field(default_factory=list)
    reperes: List[Repere] = field(default_factory=list)
    x_log: bool = False
    y_min_zero: bool = True


# ---------------------------------------------------------------------------
# Police 5x7 minimale (chiffres et quelques symboles)
# ---------------------------------------------------------------------------
_FONT = {
    "0": ("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    "1": ("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    "2": ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    "3": ("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    "4": ("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    "5": ("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    "6": ("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    "7": ("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    "8": ("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    "9": ("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    ".": ("00000", "00000", "00000", "00000", "00000", "01100", "01100"),
    ",": ("00000", "00000", "00000", "00000", "01100", "01100", "01000"),
    "-": ("00000", "00000", "00000", "01110", "00000", "00000", "00000"),
    ":": ("00000", "01100", "01100", "00000", "01100", "01100", "00000"),
    "/": ("00001", "00010", "00010", "00100", "01000", "01000", "10000"),
    "%": ("11001", "11010", "00010", "00100", "01000", "01011", "10011"),
    "h": ("10000", "10000", "10110", "11001", "10001", "10001", "10001"),
    "j": ("00010", "00000", "00110", "00010", "00010", "10010", "01100"),
    "m": ("00000", "00000", "11010", "10101", "10101", "10101", "10101"),
    "i": ("00100", "00000", "01100", "00100", "00100", "00100", "01110"),
    "n": ("00000", "00000", "10110", "11001", "10001", "10001", "10001"),
    " ": ("00000",) * 7,
}


class Canevas:
    """Petit canevas RGB avec export PNG."""

    def __init__(self, largeur: int, hauteur: int, fond: Couleur = BLANC):
        self.w = largeur
        self.h = hauteur
        self.px = bytearray(bytes(fond) * (largeur * hauteur))

    def point(self, x: int, y: int, c: Couleur) -> None:
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 3
            self.px[i:i + 3] = bytes(c)

    def disque(self, x: int, y: int, r: int, c: Couleur) -> None:
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if dx * dx + dy * dy <= r * r:
                    self.point(x + dx, y + dy, c)

    def rectangle(self, x0: int, y0: int, x1: int, y1: int, c: Couleur, plein: bool = True) -> None:
        x0, x1 = sorted((x0, x1))
        y0, y1 = sorted((y0, y1))
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if plein or y in (y0, y1) or x in (x0, x1):
                    self.point(x, y, c)

    def ligne(self, x0: float, y0: float, x1: float, y1: float, c: Couleur,
              epaisseur: int = 1, pointilles: bool = False) -> None:
        x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        n = 0
        r = max(epaisseur - 1, 0)
        while True:
            if not pointilles or (n // 4) % 2 == 0:
                if r:
                    self.disque(x0, y0, r, c)
                else:
                    self.point(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy
            n += 1

    def texte(self, x: int, y: int, texte: str, c: Couleur = NOIR, echelle: int = 1) -> None:
        cx = x
        for ch in texte:
            glyphe = _FONT.get(ch)
            if glyphe is None:
                cx += 6 * echelle
                continue
            for gy, ligne in enumerate(glyphe):
                for gx, bit in enumerate(ligne):
                    if bit == "1":
                        for ey in range(echelle):
                            for ex in range(echelle):
                                self.point(cx + gx * echelle + ex, y + gy * echelle + ey, c)
            cx += 6 * echelle

    def largeur_texte(self, texte: str, echelle: int = 1) -> int:
        return len(texte) * 6 * echelle

    def png(self) -> bytes:
        brut = bytearray()
        for y in range(self.h):
            brut.append(0)
            brut.extend(self.px[y * self.w * 3:(y + 1) * self.w * 3])

        def bloc(typ: bytes, data: bytes) -> bytes:
            entete = struct.pack(">I", len(data)) + typ + data
            return entete + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)

        ihdr = struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0)
        return (b"\x89PNG\r\n\x1a\n" + bloc(b"IHDR", ihdr)
                + bloc(b"IDAT", zlib.compress(bytes(brut), 9)) + bloc(b"IEND", b""))


# ---------------------------------------------------------------------------
# Echelles et graduations
# ---------------------------------------------------------------------------
def graduations(vmin: float, vmax: float, n: int = 5) -> List[float]:
    """Graduations "rondes" couvrant l'intervalle."""
    if vmax <= vmin:
        return [vmin]
    brut = (vmax - vmin) / max(n, 1)
    magnitude = 10 ** math.floor(math.log10(brut))
    pas = magnitude
    for mult in (1, 2, 2.5, 5, 10):
        pas = magnitude * mult
        if brut <= pas:
            break
    debut = math.floor(vmin / pas) * pas
    out: List[float] = []
    v = debut
    while v <= vmax + pas * 0.001:
        if v >= vmin - pas * 0.001:
            out.append(round(v, 10))
        v += pas
    return out


def format_nombre(v: float) -> str:
    a = abs(v)
    if a >= 100:
        return f"{v:.0f}"
    if a >= 10:
        return f"{v:.0f}" if abs(v - round(v)) < 0.05 else f"{v:.1f}"
    if a >= 1:
        return f"{v:.1f}"
    if a == 0:
        return "0"
    return f"{v:.2f}"


def format_duree_courte(minutes: float) -> str:
    if minutes < 60:
        return f"{minutes:.0f}min"
    if minutes < 1440:
        return f"{minutes / 60:.0f}h"
    return f"{minutes / 1440:.0f}j"


@dataclass
class Cadre:
    """Zone de trace et transformation coordonnées -> pixels."""

    x0: int
    y0: int
    x1: int
    y1: int
    xmin: float
    xmax: float
    ymin: float
    ymax: float
    x_log: bool = False

    def px(self, x: float) -> float:
        if self.x_log:
            lx = math.log10(max(x, 1e-9))
            lmin = math.log10(max(self.xmin, 1e-9))
            lmax = math.log10(max(self.xmax, 1e-9))
            f = (lx - lmin) / (lmax - lmin) if lmax > lmin else 0.0
        else:
            f = (x - self.xmin) / (self.xmax - self.xmin) if self.xmax > self.xmin else 0.0
        return self.x0 + f * (self.x1 - self.x0)

    def py(self, y: float) -> float:
        f = (y - self.ymin) / (self.ymax - self.ymin) if self.ymax > self.ymin else 0.0
        return self.y1 - f * (self.y1 - self.y0)


def bornes(graphique: Graphique) -> Tuple[float, float, float, float]:
    xs = [x for s in graphique.series for x, _ in s.points]
    ys = [y for s in graphique.series for _, y in s.points]
    ys += [r.valeur for r in graphique.reperes if not r.vertical]
    xs += [r.valeur for r in graphique.reperes if r.vertical]
    if not xs or not ys:
        return 0.0, 1.0, 0.0, 1.0
    xmin, xmax = min(xs), max(xs)
    ymin, ymax = min(ys), max(ys)
    if graphique.y_min_zero:
        ymin = min(ymin, 0.0)
    if ymax <= ymin:
        ymax = ymin + 1.0
    ymax += (ymax - ymin) * 0.08
    if xmax <= xmin:
        xmax = xmin + 1.0
    return xmin, xmax, ymin, ymax


def rendre_png(graphique: Graphique, largeur: int = 900, hauteur: int = 460, echelle_texte: int = 2) -> bytes:
    """Rasterise le graphique et renvoie les octets PNG."""
    c = Canevas(largeur, hauteur)
    marge_g, marge_d, marge_h, marge_b = 70, 24, 26, 48
    xmin, xmax, ymin, ymax = bornes(graphique)
    cadre = Cadre(marge_g, marge_h, largeur - marge_d, hauteur - marge_b, xmin, xmax, ymin, ymax, graphique.x_log)

    if graphique.x_log:
        ticks_x: List[float] = []
        d = 10 ** math.floor(math.log10(max(xmin, 1e-9)))
        while d <= xmax * 10:
            for m in (1, 2, 5):
                v = d * m
                if xmin <= v <= xmax:
                    ticks_x.append(v)
            d *= 10
    else:
        ticks_x = graduations(xmin, xmax, 6)
    duree_en_x = graphique.axe_x.lower().startswith("duree") or graphique.axe_x.lower().startswith("temps")
    for v in ticks_x:
        x = cadre.px(v)
        c.ligne(x, cadre.y0, x, cadre.y1, GRIS_CLAIR, 1)
        etiquette = format_duree_courte(v) if duree_en_x else format_nombre(v)
        c.texte(int(x) - c.largeur_texte(etiquette, echelle_texte) // 2, cadre.y1 + 8, etiquette, GRIS, echelle_texte)
    for v in graduations(ymin, ymax, 5):
        y = cadre.py(v)
        c.ligne(cadre.x0, y, cadre.x1, y, GRIS_CLAIR, 1)
        etiquette = format_nombre(v)
        c.texte(cadre.x0 - 8 - c.largeur_texte(etiquette, echelle_texte), int(y) - 3 * echelle_texte,
                etiquette, GRIS, echelle_texte)

    for s in graphique.series:
        if not s.points or not s.aire:
            continue
        base = cadre.py(max(ymin, 0.0))
        pastel = tuple(min(255, int(v + (255 - v) * 0.80)) for v in s.couleur)
        for (x0, y0), (x1, y1) in zip(s.points, s.points[1:]):
            px0, px1 = cadre.px(x0), cadre.px(x1)
            py0, py1 = cadre.py(y0), cadre.py(y1)
            col0, col1 = int(math.floor(px0)), int(math.ceil(px1))
            for col in range(col0, col1 + 1):
                f = (col - px0) / (px1 - px0) if px1 > px0 else 0.0
                f = min(max(f, 0.0), 1.0)
                c.ligne(col, py0 + (py1 - py0) * f, col, base, pastel, 1)

    for r in graphique.reperes:
        if r.vertical:
            c.ligne(cadre.px(r.valeur), cadre.y0, cadre.px(r.valeur), cadre.y1, r.couleur, 1, pointilles=True)
        else:
            c.ligne(cadre.x0, cadre.py(r.valeur), cadre.x1, cadre.py(r.valeur), r.couleur, 2, pointilles=True)

    for s in graphique.series:
        for p0, p1 in zip(s.points, s.points[1:]):
            c.ligne(cadre.px(p0[0]), cadre.py(p0[1]), cadre.px(p1[0]), cadre.py(p1[1]),
                    s.couleur, s.epaisseur, s.pointilles)

    c.ligne(cadre.x0, cadre.y0, cadre.x0, cadre.y1, GRIS, 1)
    c.ligne(cadre.x0, cadre.y1, cadre.x1, cadre.y1, GRIS, 1)

    x = cadre.x0
    y = hauteur - 16
    for s in graphique.series:
        c.rectangle(x, y, x + 18, y + 8, s.couleur)
        x += 26
    for r in graphique.reperes:
        c.rectangle(x, y + 2, x + 18, y + 6, r.couleur)
        x += 26
    return c.png()


def legende_texte(graphique: Graphique) -> str:
    """Légende textuelle (les PNG générés ne contiennent que des pastilles)."""
    return " | ".join([s.nom for s in graphique.series] + [r.nom for r in graphique.reperes])
