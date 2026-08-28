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
        self.overlay = []
        self.drawer = None
        self.platform = None

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


class _Evenement:
    """Événement Flet minimal (control + index sélectionné)."""

    def __init__(self, control, index=None):
        self.control = control
        if index is not None:
            control.selected_index = index


def _rechercher(controle, classe, profondeur: int = 0):
    """Retourne tous les contrôles d'une classe donnée dans l'arbre."""
    trouves = [controle] if isinstance(controle, classe) else []
    if profondeur > 40:
        return trouves
    for attribut in ("controls", "content", "destinations", "actions"):
        valeur = getattr(controle, attribut, None)
        if valeur is None:
            continue
        elements = valeur if isinstance(valeur, (list, tuple)) else [valeur]
        for element in elements:
            if isinstance(element, ft.Control):
                trouves.extend(_rechercher(element, classe, profondeur + 1))
    return trouves


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


class TestCoquilleApplication(unittest.TestCase):
    """Construction complète de l'application (barre, navigation, première vue)."""

    def test_demarrage(self):
        import main as application

        page = PageFactice()
        application.main(page)
        self.assertTrue(page.controls)
        self.assertGreater(parcourir(page.controls[0]), 20)

    def test_demarrage_en_largeur_telephone(self):
        import main as application

        page = PageFactice()
        page.width, page.height = 380, 780
        application.main(page)
        self.assertTrue(page.controls)
        if page.on_resized:
            page.on_resized(None)

    def test_navigation_par_tiroir_sur_android(self):
        import main as application

        page = PageFactice()
        page.platform = ft.PagePlatform.ANDROID
        page.width, page.height = 1280, 800  # largeur signalée parfois trompeuse
        application.main(page)
        page.on_resized(None)
        rails = _rechercher(page.controls[0], ft.NavigationRail)
        self.assertTrue(rails)
        self.assertFalse(rails[0].visible, "le rail latéral doit être masqué sur téléphone")

    def test_toutes_les_sections_sont_accessibles(self):
        import main as application

        page = PageFactice()
        application.main(page)
        tiroirs = [c for c in page.ouverts if isinstance(c, ft.NavigationDrawer)]
        rails = _rechercher(page.controls[0], ft.NavigationRail)
        self.assertEqual(len(rails[0].destinations), 7)
        rails[0].on_change(_Evenement(rails[0], 3))

    def test_une_vue_en_erreur_affiche_le_detail(self):
        """Une page blanche est inacceptable : l'erreur doit être visible."""
        import main as application
        from bassin.ui.vues import projet as mod_projet

        original = mod_projet.VueProjet.construire
        mod_projet.VueProjet.construire = lambda self: (_ for _ in ()).throw(RuntimeError("panne simulée"))
        try:
            page = PageFactice()
            application.main(page)
        finally:
            mod_projet.VueProjet.construire = original
        textes = [c.value for c in _rechercher(page.controls[0], ft.Text) if getattr(c, "value", None)]
        self.assertTrue(any("panne simulée" in t for t in textes),
                        "la trace de l'erreur doit apparaître à l'écran")

    def test_reprise_d_un_projet_enregistre(self):
        import main as application

        page = PageFactice()
        page.client_storage.set(application.CLE_STOCKAGE, etat_complet().to_json())
        application.main(page)
        self.assertTrue(page.controls)


class TestGenerationDesRapports(unittest.TestCase):
    """La génération lancée depuis l'interface doit produire les fichiers ou dire pourquoi."""

    def setUp(self):
        import tempfile

        from bassin.ui.vues import rapport as mod_rapport

        self.page = PageFactice()
        self.etat = etat_complet()
        self.repertoire = tempfile.mkdtemp(prefix="hydrobassin_ui_")
        self.mod = mod_rapport
        self._destination = mod_rapport.repertoire_documents
        mod_rapport.repertoire_documents = lambda: self.repertoire
        self.vue = mod_rapport.VueRapport(self.page, self.etat)
        self.vue.construire()

    def tearDown(self):
        import shutil

        self.mod.repertoire_documents = self._destination
        shutil.rmtree(self.repertoire, ignore_errors=True)

    def test_les_trois_formats_sont_ecrits(self):
        self.vue._generer(["xlsx", "docx", "pdf"])
        self.assertEqual(self.vue.erreurs, [])
        self.assertEqual(len(self.vue.produits), 3)
        for chemin in self.vue.produits:
            self.assertTrue(os.path.exists(chemin), chemin)
            self.assertGreater(os.path.getsize(chemin), 1000, chemin)
        self.assertTrue(self.vue.resultats())

    def test_sans_surface_l_erreur_est_affichee(self):
        etat = EtatApplication()
        vue = self.mod.VueRapport(self.page, etat)
        vue.construire()
        vue._generer(["pdf"])
        self.assertTrue(vue.erreurs)
        self.assertIn("surface", vue.erreurs[0].lower())
        self.assertTrue(vue.resultats())

    def test_une_erreur_d_ecriture_est_remontee(self):
        def exploser(dossier, chemin):
            raise OSError("disque plein")

        originaux = dict(self.mod.ECRIVAINS)
        self.mod.ECRIVAINS["pdf"] = exploser
        try:
            self.vue._generer(["pdf"])
        finally:
            self.mod.ECRIVAINS.update(originaux)
        self.assertTrue(self.vue.erreurs)
        self.assertIn("disque plein", self.vue.erreurs[0])
        self.assertFalse(self.vue.produits)
        self.assertTrue(any(isinstance(c, ft.Control) for c in self.vue.resultats()))


class TestDestinationDesRapports(unittest.TestCase):
    def test_le_repertoire_retourne_est_accessible_en_ecriture(self):
        from bassin.ui.state import repertoire_documents

        chemin = repertoire_documents()
        temoin = os.path.join(chemin, ".test_ecriture")
        with open(temoin, "w", encoding="utf-8") as fh:
            fh.write("ok")
        os.remove(temoin)

    def test_diagnostic(self):
        from bassin.ui.state import diagnostic_stockage

        lignes = diagnostic_stockage()
        self.assertTrue(lignes)
        self.assertTrue(any(ok for _, ok in lignes))


if __name__ == "__main__":
    unittest.main(verbosity=2)
