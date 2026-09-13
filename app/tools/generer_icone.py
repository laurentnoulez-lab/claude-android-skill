"""Génère l'icône de l'application (PNG) sans dépendance graphique externe.

    python3 tools/generer_icone.py
"""

from __future__ import annotations

import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.reports.charts import Canevas  # noqa: E402

BLEU_FONCE = (23, 58, 160)
BLEU = (37, 99, 235)
BLEU_CIEL = (96, 165, 250)
BLANC = (255, 255, 255)
SABLE = (226, 232, 240)


def melange(a, b, f):
    return tuple(int(round(a[i] + (b[i] - a[i]) * f)) for i in range(3))


def dessiner(taille: int, echelle: int = 4, fond: bool = True) -> Canevas:
    """Bassin d'orage stylisé : cuvette, eau, goutte de pluie."""
    n = taille * echelle
    c = Canevas(n, n, BLANC)
    r = n * 0.22  # rayon des coins

    for y in range(n):
        for x in range(n):
            if fond:
                dx = min(x, n - 1 - x)
                dy = min(y, n - 1 - y)
                dans = True
                if dx < r and dy < r:
                    dans = (r - dx) ** 2 + (r - dy) ** 2 <= r * r
                if not dans:
                    continue
                couleur = melange(BLEU_FONCE, BLEU_CIEL, (x + y) / (2.0 * n))
                c.point(x, y, couleur)

    # Cuvette du bassin (deux talus + fond d'eau)
    haut = n * 0.56
    bas = n * 0.80
    largeur_haut = n * 0.72
    largeur_bas = n * 0.34
    niveau = n * 0.66
    for y in range(int(haut), int(bas)):
        f = (y - haut) / (bas - haut)
        demi = (largeur_haut + (largeur_bas - largeur_haut) * f) / 2.0
        x0, x1 = int(n / 2 - demi), int(n / 2 + demi)
        for x in range(x0, x1):
            if y >= niveau:
                c.point(x, y, melange(BLEU_CIEL, BLANC, 0.25 * (1 - f)))
            else:
                c.point(x, y, melange(BLANC, SABLE, 0.15))

    # Berges
    for y in range(int(haut), int(bas)):
        f = (y - haut) / (bas - haut)
        demi = (largeur_haut + (largeur_bas - largeur_haut) * f) / 2.0
        for e in range(int(n * 0.018)):
            c.point(int(n / 2 - demi) - e, y, BLANC)
            c.point(int(n / 2 + demi) + e, y, BLANC)

    # Goutte de pluie centrale
    cx, cy, rg = n / 2.0, n * 0.36, n * 0.13
    for y in range(int(cy - rg * 2.2), int(cy + rg * 1.4)):
        for x in range(int(cx - rg * 1.3), int(cx + rg * 1.3)):
            dx, dy = x - cx, y - cy
            if dy >= 0:
                dans = dx * dx + dy * dy <= rg * rg
            else:
                largeur = rg * (1 + dy / (rg * 2.2))
                dans = abs(dx) <= max(largeur, 0)
            if dans:
                c.point(x, y, BLANC)

    # Réduction (anti-crénelage par moyenne)
    sortie = Canevas(taille, taille, BLANC)
    for y in range(taille):
        for x in range(taille):
            r_ = v_ = b_ = 0
            for dy in range(echelle):
                for dx in range(echelle):
                    i = (((y * echelle + dy) * n) + (x * echelle + dx)) * 3
                    r_ += c.px[i]
                    v_ += c.px[i + 1]
                    b_ += c.px[i + 2]
            k = echelle * echelle
            sortie.point(x, y, (r_ // k, v_ // k, b_ // k))
    return sortie


def main() -> None:
    base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src", "assets")
    os.makedirs(base, exist_ok=True)
    for nom, taille in (("icon.png", 512), ("splash_android.png", 384), ("icon_512.png", 512)):
        chemin = os.path.join(base, nom)
        with open(chemin, "wb") as fh:
            fh.write(dessiner(taille).png())
        print("écrit :", chemin)


if __name__ == "__main__":
    main()
