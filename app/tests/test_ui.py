"""Tests de construction de l'interface (sans serveur graphique).

Ils instancient chaque vue et parcourent l'arbre de contrôles Flet : toute
erreur d'API (paramètre inconnu, icône ou couleur inexistante) est détectée.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

import flet as ft  # noqa: E402

from bassin.core.model import Bassin  # noqa: E402
from bassin.reports import charts  # noqa: E402
from bassin.ui import graphiques, theme  # noqa: E402
from bassin.ui.state import EtatApplication  # noqa: E402
from bassin.ui.vues.ajutage import VueAjutage  # noqa: E402
from bassin.ui.vues.bassin import VueBassin  # noqa: E402
from bassin.ui.vues.dimensionnement import VueDimensionnement  # noqa: E402
from bassin.ui.vues.pluies import VuePluies  # noqa: E402
from bassin.ui.vues.projet import VueProjet  # noqa: E402
from bassin.ui.vues.qdf import VueTableQDF  # noqa: E402
from bassin.ui.vues.rapport import VueRapport  # noqa: E402

VUES = (VueProjet, VueDimensionnement, VueBassin, VueTableQDF, VueAjutage, VuePluies, VueRapport)


class _Stockage:
    def __init__(self):
        self.donnees = {}

    def get(self, cle):
        return self.donnees.get(cle)

    def set(self, cle, valeur):
        self.donnees[cle] = valeur


class _Fenetre:
    width = 0
    height = 0
    min_width = 0
    min_height = 0


class PageFactice:
    """Page Flet minimale : suffit à la construction des vues."""

    def __init__(self):
        self.width = 1280
        self.height = 900
        self.controls = []
        self.ouverts = []
        self.client_storage = _Stockage()
        self.window = _Fenetre()
        self.theme = None
        self.dark_theme = None
        self.theme_mode = None
        self.bgcolor = None
        self.padding = 0
        self.title = ""
        self.on_resized = None

    def open(self, controle):
        self.ouverts.append(controle)

    def close(self, controle):
        pass

    def update(self):
        pass

    def add(self, *controls):
        self.controls.extend(controls)


def etat_complet() -> EtatApplication:
    etat = EtatApplication()
    p = etat.projet
    p.commune_ins, p.commune_nom = "63013", "Bütgenbach"
    p.surface_reference_m2 = 2000.0
    p.surfaces[7].aire_m2 = 1500.0
    p.surfaces[1].aire_m2 = 500.0
    p.k_infiltration_ms = 1e-5
    p.surface_infiltration_m2 = 120.0
    p.debit_ajutage_ls = 1.0
    p.nom_projet = "Test"
    p.bassin = Bassin(volume_total_m3=90.0, volume_sous_ajutage_m3=10.0,
                      surface_dispersion_m2=120.0, debit_ajutage_ls=1.0)
    etat.invalider()
    return etat


def parcourir(controle, profondeur: int = 0) -> int:
    """Parcourt récursivement l'arbre de contrôles et compte les nœuds."""
    total = 1
    if profondeur > 40:
        return total
    for attribut in ("controls", "content", "actions", "cells", "rows", "columns",
                     "destinations", "segments", "options", "data_series", "label", "leading"):
        valeur = getattr(controle, attribut, None)
        if valeur is None:
            continue
        elements = valeur if isinstance(valeur, (list, tuple)) else [valeur]
        for element in elements:
            if isinstance(element, ft.Control):
                total += parcourir(element, profondeur + 1)
    return total


class TestConstructionDesVues(unittest.TestCase):
    def setUp(self):
        self.page = PageFactice()
        self.etat = etat_complet()

    def test_toutes_les_vues_se_construisent(self):
        for classe in VUES:
            with self.subTest(vue=classe.__name__):
                controles = classe(self.page, self.etat).construire()
                self.assertTrue(controles)
                self.assertGreater(sum(parcourir(c) for c in controles), 5)

    def test_vues_sans_bassin_encode(self):
        etat = EtatApplication()
        etat.projet.surfaces[7].aire_m2 = 800.0
        etat.invalider()
        for classe in VUES:
            with self.subTest(vue=classe.__name__):
                classe(self.page, etat).construire()

    def test_vues_sans_aucune_surface(self):
        etat = EtatApplication()
        for classe in VUES:
            with self.subTest(vue=classe.__name__):
                classe(self.page, etat).construire()

    def test_vues_commune_sans_montana(self):
        etat = etat_complet()
        etat.projet.commune_ins, etat.projet.commune_nom = "56011", "Binche"
        etat.invalider()
        for classe in VUES:
            with self.subTest(vue=classe.__name__):
                classe(self.page, etat).construire()

    def test_affichage_et_rafraichissement(self):
        for classe in VUES:
            vue = classe(self.page, self.etat)
            self.assertIsInstance(vue.afficher(), ft.Column)
            vue.rafraichir()

    def test_selecteur_de_commune(self):
        vue = VueProjet(self.page, self.etat)
        vue.construire()
        vue._ouvrir_selecteur_commune()
        self.assertTrue(self.page.ouverts)

    def test_theme_et_composants(self):
        theme.appliquer_theme(self.page, sombre=True)
        self.assertIsNotNone(self.page.theme)
        for controle in (
            theme.tuile("12", "Volume", "m³"),
            theme.message("test", "alerte"),
            theme.etiquette_statut("DEBORDEMENT"),
            theme.section("Titre", ft.Text("x"), ft.Icons.WATER_DROP),
            theme.champ_nombre("Débit", 1.5, lambda v: None, "l/s"),
            theme.bouton_principal("OK", ft.Icons.CHECK, lambda e: None),
            theme.bouton_secondaire("Non", ft.Icons.CLOSE, lambda e: None),
        ):
            self.assertIsInstance(controle, ft.Control)

    def test_graphique_flet(self):
        vue = VueDimensionnement(self.page, self.etat)
        self.assertGreater(parcourir(graphiques.construire(vue._graphique_volume(), 260)), 3)
        self.assertIsInstance(graphiques.construire(charts.Graphique(), 200), ft.Control)
        self.assertIsInstance(graphiques.image_png(vue._graphique_volume(), 400, 200), ft.Control)

    def test_etat_persistance(self):
        etat = etat_complet()
        autre = EtatApplication()
        self.assertTrue(autre.charger_json(etat.to_json()))
        self.assertAlmostEqual(autre.projet.aire_ponderee_m2, etat.projet.aire_ponderee_m2)
        self.assertAlmostEqual(autre.bassin.volume_total_m3, 90.0)
        self.assertFalse(autre.charger_json("{invalide"))

    def test_reprise_du_dimensionnement(self):
        etat = etat_complet()
        etat.reprendre_dimensionnement()
        self.assertGreater(etat.bassin.volume_total_m3, 0)

    def test_champ_nombre_accepte_la_virgule(self):
        valeurs = []
        champ = theme.champ_nombre("Test", 0.0, valeurs.append, "m")
        champ.update = lambda: None

        class _Evt:
            control = champ

        champ.value = "12,5"
        champ.on_change(_Evt())
        self.assertEqual(valeurs, [12.5])
        champ.value = "abc"
        champ.on_change(_Evt())
        self.assertEqual(valeurs, [12.5])
        champ.value = ""
        champ.on_change(_Evt())
        self.assertEqual(valeurs, [12.5, 0.0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
