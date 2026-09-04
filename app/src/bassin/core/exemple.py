"""Projet de démonstration, partagé par l'application et les outils.

Sert à découvrir l'application sans rien encoder, et à produire le dossier de
démonstration ainsi que les captures d'interface.
"""

from __future__ import annotations

from .model import Bassin, Projet


def projet_demonstration() -> Projet:
    """Lotissement fictif à Bütgenbach : 2 000 m², bassin de 90 m³, ajutage 1 l/s."""
    projet = Projet(
        commune_ins="63013",
        commune_nom="Bütgenbach",
        periode_retour=25,
        surfaces=Projet.surfaces_par_defaut(),
        surface_reference_m2=2000.0,
        nom_projet="Lotissement Les Sources",
        auteur="Bureau d'études",
        localisation="Rue du Moulin 12",
        remarques="Exemple fourni avec l'application.",
    )
    projet.surfaces[7].aire_m2 = 1500.0   # toitures et voiries
    projet.surfaces[1].aire_m2 = 500.0    # prairies
    projet.k_infiltration_ms = 1e-5
    projet.surface_infiltration_m2 = 120.0
    projet.debit_ajutage_ls = 1.0
    projet.hauteur_charge_m = 1.0
    projet.bassin = Bassin(
        volume_total_m3=90.0,
        volume_sous_ajutage_m3=10.0,
        surface_dispersion_m2=120.0,
        debit_ajutage_ls=1.0,
    )
    return projet
