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


def systeme_demonstration() -> "reseau.Systeme":
    """Réseau de démonstration : deux bassins versants, deux bassins d'orage.

    Un lotissement et la voirie qui le dessert aboutissent à deux ouvrages en
    série : le bassin du lotissement se déverse dans celui de la voirie, lequel
    rejette au ruisseau. De quoi montrer d'emblée ce que la version 3 apporte —
    des raccordements, un dimensionnement en cascade et une synthèse graphique.
    """
    from . import reseau
    from .model import SurfaceIncidente

    systeme = reseau.Systeme(
        commune_ins="63013",
        commune_nom="Bütgenbach",
        periode_retour=25,
        nom_projet="Lotissement Les Sources",
        auteur="Bureau d'études",
        localisation="Rue du Moulin 12",
        remarques="Exemple fourni avec l'application : deux bassins versants, deux "
                  "bassins d'orage en série, rejet au ruisseau.",
    )

    aval = reseau.ouvrage_neuf(systeme, "Bassin d'orage de la voirie")
    aval.etude.k_infiltration_ms = 1e-5
    aval.etude.surface_infiltration_m2 = 180.0
    aval.etude.fixer_ajutage_absolu(2.5)
    aval.etude.bassin = Bassin(volume_total_m3=210.0, volume_sous_ajutage_m3=15.0,
                               surface_dispersion_m2=180.0, debit_ajutage_ls=2.5)
    aval.note = "Rejet au ruisseau de la Warche."
    systeme.ouvrages.append(aval)
    systeme.ouvrage_courant = aval.id

    amont = reseau.ouvrage_neuf(systeme, "Bassin d'orage du lotissement")
    amont.aval_id = aval.id
    amont.etude.k_infiltration_ms = 1e-5
    amont.etude.surface_infiltration_m2 = 120.0
    amont.etude.fixer_ajutage_absolu(1.0)
    amont.etude.bassin = Bassin(volume_total_m3=95.0, volume_sous_ajutage_m3=10.0,
                                surface_dispersion_m2=120.0, debit_ajutage_ls=1.0)
    systeme.ouvrages.append(amont)

    lotissement = reseau.versant_neuf(systeme, "Lotissement", amont.id)
    lotissement.surface_reference_m2 = 2000.0
    lotissement.surfaces = Projet.surfaces_par_defaut()
    lotissement.surfaces[7].aire_m2 = 1500.0     # toitures et allées
    lotissement.surfaces[1].aire_m2 = 500.0      # prairies et jardins
    systeme.bassins_versants.append(lotissement)

    voirie = reseau.versant_neuf(systeme, "Voirie de desserte", aval.id)
    voirie.surface_reference_m2 = 3200.0
    voirie.surfaces = [
        SurfaceIncidente("Voirie et filets d'eau", 1.00, 2600.0),
        SurfaceIncidente("Accotements enherbés", 0.15, 600.0),
    ]
    systeme.bassins_versants.append(voirie)

    systeme.synchroniser()
    return systeme
