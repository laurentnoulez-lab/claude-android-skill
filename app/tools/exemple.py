"""Génère un dossier de calcul de démonstration (Excel, Word, PDF).

    python3 tools/exemple.py --sortie ../rapports_demo
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core.exemple import projet_demonstration  # noqa: E402,F401
from bassin.core.model import SCENARIO_SEUIL  # noqa: E402
from bassin.reports import docx_report, dossier as mod_dossier, pdf_report, xlsx_report  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sortie", default="rapports_demo", help="répertoire de destination")
    parser.add_argument("--scenario", default=SCENARIO_SEUIL, help="scénario retenu")
    args = parser.parse_args()

    os.makedirs(args.sortie, exist_ok=True)
    dossier = mod_dossier.construire(projet_demonstration(), args.scenario)
    res = dossier.resultat_principal
    print(f"Scénario retenu   : {res.libelle}")
    print(f"Volume            : {res.volume_m3:.1f} m³")
    print(f"Durée critique    : {res.duree_critique_hm}")
    print(f"Temps de vidange  : {res.temps_vidange_hm}")
    if dossier.table:
        print(f"Récurrence absorbée : {dossier.table.periode_retour_max_acceptee()} ans")

    for ecrire, nom in (
        (xlsx_report.ecrire, "dossier_bassin_orage.xlsx"),
        (docx_report.ecrire, "dossier_bassin_orage.docx"),
        (pdf_report.ecrire, "dossier_bassin_orage.pdf"),
    ):
        chemin = ecrire(dossier, os.path.join(args.sortie, nom))
        print(f"écrit : {chemin} ({os.path.getsize(chemin) // 1024} Ko)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
