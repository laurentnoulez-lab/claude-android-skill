"""Régénère le module de repli contenant le référentiel GTI.

    python3 tools/generer_donnees_embarquees.py
"""

from __future__ import annotations

import base64
import os

RACINE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src", "bassin", "data")
SOURCE = os.path.join(RACINE, "gti_rainfall.json.gz")
CIBLE = os.path.join(RACINE, "gti_embarque.py")

ENTETE = '''"""Référentiel GTI embarqué dans le code (repli d'empaquetage).

Généré par ``tools/generer_donnees_embarquees.py`` à partir de
``gti_rainfall.json.gz`` : ce module garantit que les pluies sont présentes
même si l'empaqueteur d'une plateforme n'emporte pas les fichiers de données.
"""

import base64

_B85 = (
'''


def main() -> int:
    with open(SOURCE, "rb") as fh:
        texte = base64.b85encode(fh.read()).decode()
    morceaux = [texte[i:i + 88] for i in range(0, len(texte), 88)]
    with open(CIBLE, "w", encoding="utf-8") as fh:
        fh.write(ENTETE + "\n".join(f'    "{m}"' for m in morceaux) + "\n)\n\nDONNEES = base64.b85decode(_B85)\n")
    print(f"écrit : {CIBLE} ({os.path.getsize(CIBLE) // 1024} Ko)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
