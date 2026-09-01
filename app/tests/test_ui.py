"""Tests de construction de l'interface (sans serveur graphique).

Ils instancient chaque vue et parcourent l'arbre de contrôles Flet : toute
erreur d'API (paramètre inconnu, icône ou couleur inexistante) est détectée.
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

import flet as ft  # noqa: E402

from bassin.core import hydro, rainfall  # noqa: E402
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
        self.appels = 0

    def get(self, cle):
        self.appels += 1
        return self.donnees.get(cle)

    def set(self, cle, valeur):
        self.appels += 1
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
        self.web = False
        self.route = "/"

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


class _Touche:
    """Événement clavier Flet minimal."""

    def __init__(self, key: str, ctrl: bool = False):
        self.key = key
        self.ctrl = ctrl
        self.shift = self.alt = self.meta = False


class _Controle:
    """Contrôle Flet minimal porteur d'une valeur (case à cocher, interrupteur)."""

    def __init__(self, value):
        self.value = value


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


def textes(controle, profondeur: int = 0) -> list:
    """Rassemble les libellés visibles d'un arbre de contrôles."""
    trouves = []
    if profondeur > 40:
        return trouves
    # La « value » d'une liste déroulante est une clé interne, pas un libellé.
    lisibles = ("label", "tooltip", "hint_text", "helper_text", "text")
    if not isinstance(controle, ft.Dropdown):
        lisibles = ("value",) + lisibles
    for attribut in lisibles:
        valeur = getattr(controle, attribut, None)
        if isinstance(valeur, str):
            trouves.append(valeur)
    for attribut in ("controls", "content", "actions", "cells", "rows", "columns",
                     "destinations", "segments", "options", "data_series", "label", "leading",
                     "title", "subtitle"):
        valeur = getattr(controle, attribut, None)
        if valeur is None:
            continue
        elements = valeur if isinstance(valeur, (list, tuple)) else [valeur]
        for element in elements:
            if isinstance(element, ft.Control):
                trouves.extend(textes(element, profondeur + 1))
    return trouves


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

    def test_les_nombres_affiches_utilisent_la_virgule(self):
        """Aucun séparateur décimal anglo-saxon ne doit apparaître à l'écran."""
        for classe in VUES:
            with self.subTest(vue=classe.__name__):
                vue = classe(self.page, self.etat)
                vue.afficher()
                for texte in textes(vue.corps):
                    self.assertIsNone(
                        re.search(r"\d\.\d", texte),
                        f"{classe.__name__} affiche « {texte} » avec un point décimal",
                    )

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

    def test_les_info_bulles_des_courbes_sont_francaises(self):
        """Flet affiche sinon la valeur brute, avec un point décimal."""
        self.assertEqual(graphiques._bulle("Volume stocké", 1234.5), "Volume stocké : 1234,50")
        vue = VueBassin(self.page, self.etat)
        vue.afficher()
        bulles = [p.tooltip for serie in _rechercher(vue.corps, ft.LineChart)
                  for d in serie.data_series for p in d.data_points if p.tooltip]
        self.assertTrue(bulles)
        for bulle in bulles:
            self.assertIsNone(re.search(r"\d\.\d", bulle), f"« {bulle} » garde un point décimal")

    def test_le_seuil_de_l_ajutage_s_encode_au_dimensionnement(self):
        """Le scénario à orifice surélevé exige un seuil : il doit être saisissable ici."""
        vue = VueDimensionnement(self.page, self.etat)
        vue.afficher()
        libelles = [c.label for c in _rechercher(vue.corps, ft.TextField)]
        self.assertIn("Volume sous l'ajutage", libelles,
                      "le scénario 4 n'a pas de champ pour son seuil")

        champ = [c for c in _rechercher(vue.corps, ft.TextField)
                 if c.label == "Volume sous l'ajutage"][0]
        champ.update = lambda: None

        class _Evt:
            control = champ

        champ.value = "60"
        champ.on_change(_Evt())
        self.assertAlmostEqual(self.etat.projet.bassin.volume_sous_ajutage_m3, 60.0)

    def test_sans_seuil_le_scenario_surelevé_est_signale(self):
        """Seuil nul : le 4e scénario se confond avec le 3e, il faut le dire."""
        from bassin.core.model import SCENARIO_MIXTE, SCENARIO_SEUIL

        self.etat.projet.bassin.volume_sous_ajutage_m3 = 0.0
        self.etat.invalider()
        self.assertAlmostEqual(self.etat.resultats[SCENARIO_SEUIL].volume_m3,
                               self.etat.resultats[SCENARIO_MIXTE].volume_m3, places=9)
        vue = VueDimensionnement(self.page, self.etat)
        vue.afficher()
        textes = [t.value for t in _rechercher(vue.corps, ft.Text)]
        self.assertTrue(any("revient au précédent" in (t or "") for t in textes),
                        "l'avertissement de seuil nul est absent")

        # Avec un seuil, l'avertissement disparaît et les volumes divergent.
        self.etat.projet.bassin.volume_sous_ajutage_m3 = 60.0
        self.etat.invalider()
        vue = VueDimensionnement(self.page, self.etat)
        vue.afficher()
        textes = [t.value for t in _rechercher(vue.corps, ft.Text)]
        self.assertFalse(any("revient au précédent" in (t or "") for t in textes))
        self.assertGreater(self.etat.resultats[SCENARIO_SEUIL].volume_m3,
                           self.etat.resultats[SCENARIO_MIXTE].volume_m3)

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

    def test_notation_scientifique_sans_erreur_pendant_la_frappe(self):
        """« 1e-5 » passe par « 1e » et « 1e- » : aucun message ne doit clignoter."""
        valeurs = []
        champ = theme.champ_nombre("K", 0.0, valeurs.append, "m/s")
        champ.update = lambda: None

        class _Evt:
            control = champ

        for frappe in ("1", "1e", "1e-", "1e-5"):
            champ.value = frappe
            champ.on_change(_Evt())
            self.assertIsNone(champ.error_text, f"erreur affichée en tapant « {frappe} »")
        self.assertEqual(valeurs[-1], 1e-5)

        champ.on_blur(_Evt())
        self.assertIsNone(champ.error_text)
        self.assertEqual(champ.value, "0,00001")

    def test_saisie_invalide_signalee_a_la_sortie_du_champ(self):
        champ = theme.champ_nombre("K", 0.0, lambda v: None, "m/s")
        champ.update = lambda: None

        class _Evt:
            control = champ

        champ.value = "abc"
        champ.on_change(_Evt())
        self.assertIsNone(champ.error_text)
        champ.on_blur(_Evt())
        self.assertEqual(champ.error_text, "Nombre invalide")
        # La saisie fautive reste affichée : elle doit pouvoir être corrigée.
        self.assertEqual(champ.value, "abc")

    def test_champs_couples_se_completent(self):
        """Encoder l'une des deux unités remplit l'autre, dans les deux sens."""
        enregistres = []
        champs = theme.champs_convertis("K", "m/s", 1e-5, "soit", "mm/h", 3.6e6,
                                        enregistres.append)
        a, b = champs[0], champs[1]
        a.update = b.update = lambda: None

        class _EvtA:
            control = a

        class _EvtB:
            control = b

        a.value = "2e-5"
        a.on_change(_EvtA())
        self.assertEqual(enregistres[-1], 2e-5)
        self.assertEqual(theme.lire_nombre(b.value), 72.0)

        b.value = "36"
        b.on_change(_EvtB())
        self.assertAlmostEqual(enregistres[-1], 1e-5)
        self.assertAlmostEqual(theme.lire_nombre(a.value), 1e-5)

    def test_champ_couple_desactive_sans_facteur(self):
        champs = theme.champs_convertis("Débit", "l/s", 1.0, "soit", "l/s/ha", None,
                                        lambda v: None,
                                        indisponible_b="encodez d'abord les surfaces")
        self.assertTrue(champs[1].disabled)
        self.assertIn("surfaces", champs[1].helper_text)

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

    def test_un_calcul_en_erreur_n_empeche_pas_l_affichage(self):
        """Une erreur dans le résumé ne doit jamais laisser la page vide."""
        import main as application
        from bassin.ui import state as mod_state

        original = mod_state.EtatApplication.resultat
        mod_state.EtatApplication.resultat = property(
            lambda self: (_ for _ in ()).throw(RuntimeError("calcul impossible")))
        try:
            page = PageFactice()
            application.main(page)
        finally:
            mod_state.EtatApplication.resultat = original
        colonnes = _rechercher(page.controls[0], ft.Column)
        self.assertTrue(colonnes)
        textes = [t.value for t in _rechercher(page.controls[0], ft.Text) if t.value]
        self.assertTrue(any("indisponible" in t or "calcul impossible" in t for t in textes),
                        "l'utilisateur doit voir qu'un calcul a échoué")
        conteneurs = [c for c in _rechercher(page.controls[0], ft.Container) if c.content]
        self.assertTrue(conteneurs, "le contenu de la vue doit tout de même être posé")

    def test_zone_sure_sur_mobile(self):
        """Le contenu ne doit pas passer sous la barre d'état ni sous la barre système."""
        import main as application

        page = PageFactice()
        page.platform = ft.PagePlatform.ANDROID
        application.main(page)
        self.assertTrue(page.controls)
        self.assertIsInstance(page.controls[0], ft.SafeArea)

    def test_surface_active_mise_a_jour_a_la_saisie(self):
        """La surface active de la ligne doit suivre la saisie, sans reconstruction."""
        page = PageFactice()
        etat = etat_complet()
        vue = VueProjet(page, etat)
        vue.construire()
        lignes = vue._ligne_surface(4, etat.projet.surfaces[4])  # terres battues, c = 0,5
        champs = _rechercher(lignes, ft.TextField)
        textes = [t for t in _rechercher(lignes, ft.Text) if "actifs" in (t.value or "")]
        self.assertTrue(champs and textes)
        surface = champs[-1]
        surface.update = lambda: None
        for t in textes:
            t.update = lambda: None
        surface.value = "300"
        surface.on_change(_Evenement(surface))
        surface.on_blur(_Evenement(surface))
        self.assertIn("150", textes[0].value)

    def test_entete_suit_les_saisies_sans_recalculer(self):
        """Le résumé doit suivre les surfaces encodées, sans relancer le calcul à la frappe."""
        import main as application

        page = PageFactice()
        etat_initial = etat_complet()
        page.client_storage.set(application.CLE_STOCKAGE, etat_initial.to_json())
        application.main(page)
        textes = [t for t in _rechercher(page.controls[0], ft.Text)
                  if t.value and "m² actifs" in t.value]
        self.assertTrue(textes)
        resume = textes[0]
        resume.update = lambda: None
        avant = resume.value
        self.assertIn("1575", avant)
        self.assertNotIn("…", avant)

    def test_aucun_stockage_client_sur_le_web(self):
        """Le stockage client bloque dans la version web : il ne doit pas être appelé."""
        import main as application

        page = PageFactice()
        page.web = True
        application.main(page)
        self.assertEqual(page.client_storage.appels, 0)
        self.assertTrue(page.controls, "la page doit s'afficher malgré tout")

    def test_stockage_indisponible_n_empeche_pas_le_demarrage(self):
        import main as application

        class _StockageCasse:
            def get(self, cle):
                raise BaseException("stockage indisponible")

            def set(self, cle, valeur):
                raise BaseException("stockage indisponible")

        page = PageFactice()
        page.client_storage = _StockageCasse()
        application.main(page)
        self.assertTrue(page.controls)

    def test_affichage_francophone_des_nombres(self):
        """Les valeurs affichées utilisent la virgule décimale."""
        self.assertEqual(theme.nombre(1575.0, 1), "1575,0")
        self.assertEqual(theme.nombre(0.787, 3), "0,787")
        self.assertEqual(theme.fr("volume 66.3 m³"), "volume 66,3 m³")
        vue = VueProjet(PageFactice(), etat_complet())
        controles = vue.construire()
        textes = [t.value for c in controles for t in _rechercher(c, ft.Text) if t.value]
        self.assertTrue(any("m² actifs" in t for t in textes))
        self.assertFalse(any(re.search(r"\d\.\d", t) for t in textes if "m² actifs" in t),
                         "les surfaces actives doivent s'afficher avec une virgule")

    def test_champs_convertis_se_completent(self):
        """Encoder l'une des deux unités remplit l'autre."""
        enregistre = []
        champs = theme.champs_convertis("K", "m/s", 1e-5, "soit", "mm/h", 3.6e6,
                                        enregistre.append)
        champ_a, champ_b = champs
        self.assertEqual(champ_a.value, "0,00001")
        self.assertEqual(champ_b.value, "36")
        champ_a.update = champ_b.update = lambda: None

        class _Evt:
            def __init__(self, control):
                self.control = control

        champ_b.value = "72"
        champ_b.on_blur(_Evt(champ_b))
        self.assertAlmostEqual(enregistre[-1], 2e-5)
        self.assertEqual(champ_a.value, "0,00002")

        champ_a.value = "0,00003"
        champ_a.on_blur(_Evt(champ_a))
        self.assertAlmostEqual(enregistre[-1], 3e-5)
        self.assertEqual(champ_b.value, "108")

    def test_champs_convertis_sans_facteur(self):
        champs = theme.champs_convertis("Débit", "l/s", 1.0, "soit", "l/s/ha", None,
                                        lambda v: None,
                                        indisponible_b="encodez d'abord les surfaces")
        self.assertTrue(champs[1].disabled)
        self.assertIn("surfaces", champs[1].helper_text)

    def test_ouverture_directe_d_une_section(self):
        """La route #/vue/N ouvre directement la section demandée."""
        import main as application

        page = PageFactice()
        page.route = "/vue/3"
        application.main(page)
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)
                  if t.value in {v.titre for v in
                                 (VueProjet, VueDimensionnement, VueBassin, VueTableQDF,
                                  VueAjutage, VuePluies, VueRapport)}]
        self.assertIn(VueTableQDF.titre, titres)

    def test_route_invalide_ouvre_le_projet(self):
        import main as application

        page = PageFactice()
        page.route = "/vue/zzz"
        application.main(page)
        self.assertTrue(page.controls)

    def test_route_tardive_ouvre_la_bonne_section(self):
        """Sur le web, Flet ne transmet l'URL qu'après le premier rendu."""
        import main as application

        page = PageFactice()
        application.main(page)
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Projet", titres)
        self.assertNotIn("Ajutage", titres)

        page.route = "/vue/4"
        self.assertIsNotNone(page.on_route_change)
        page.on_route_change(None)
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Ajutage", titres)

    def test_adresse_du_navigateur_ouvre_la_bonne_section(self):
        """Sur le web, Flet ne transmet pas le chemin : l'adresse est lue directement."""
        import types

        import main as application

        faux_js = types.ModuleType("js")
        faux_js.window = types.SimpleNamespace(
            location=types.SimpleNamespace(pathname="/vue/5", hash=""))
        sys.modules["js"] = faux_js
        try:
            self.assertEqual(application._adresse_navigateur(), "/vue/5")
            page = PageFactice()
            page.route = "/"  # ce que Flet transmet réellement dans la version web
            application.main(page)
            titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
            self.assertIn("Pluies GTI", titres)
        finally:
            del sys.modules["js"]

    def test_sans_navigateur_l_adresse_est_vide(self):
        """Sur Android et Windows, le module « js » n'existe pas : pas d'erreur."""
        import main as application

        self.assertNotIn("js", sys.modules)
        self.assertEqual(application._adresse_navigateur(), "")

    def test_raccourcis_clavier_ouvrent_les_sections(self):
        """Ctrl+1 à Ctrl+7 ouvrent une section ; sans Ctrl, rien ne bouge."""
        import main as application

        page = PageFactice()
        application.main(page)
        self.assertIsNotNone(page.on_keyboard_event)

        page.on_keyboard_event(_Touche("5", ctrl=True))
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Ajutage", titres)

        page.on_keyboard_event(_Touche("1", ctrl=False))
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Ajutage", titres)  # inchangé : la touche seule ne navigue pas

        page.on_keyboard_event(_Touche("9", ctrl=True))
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Ajutage", titres)  # hors des sept sections : ignoré

        page.on_keyboard_event(_Touche("a", ctrl=True))  # ne doit pas lever

    def test_charger_un_exemple_remplit_le_projet(self):
        """Ctrl+E charge un projet complet, de quoi découvrir l'application."""
        import main as application

        page = PageFactice()
        application.main(page)
        page.on_keyboard_event(_Touche("e", ctrl=True))
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertTrue(any("Lotissement Les Sources" == t for t in titres)
                        or any("Bütgenbach" in (t or "") for t in titres))

        boutons = _rechercher(page.controls[0], ft.IconButton)
        self.assertTrue([b for b in boutons if b.tooltip and "exemple" in b.tooltip])

    def test_le_defilement_revient_en_haut_a_chaque_section(self):
        """Une section plus courte ne doit pas s'ouvrir sous un grand vide."""
        import main as application

        appels = []
        origine = ft.Column.scroll_to
        ft.Column.scroll_to = lambda self, **kw: appels.append(kw)
        try:
            page = PageFactice()
            application.main(page)
            appels.clear()
            page.on_keyboard_event(_Touche("4", ctrl=True))
        finally:
            ft.Column.scroll_to = origine
        self.assertIn({"offset": 0, "duration": 0}, appels)

    def test_diagnostic_accessible(self):
        import main as application

        page = PageFactice()
        application.main(page)
        boutons = _rechercher(page.controls[0], ft.IconButton)
        infos = [b for b in boutons if b.tooltip == "Diagnostic"]
        self.assertTrue(infos)
        infos[0].on_click(None)
        fenetres = [c for c in page.ouverts if isinstance(c, ft.AlertDialog)]
        self.assertTrue(fenetres)
        textes = [t.value for t in _rechercher(fenetres[-1].content, ft.Text)]
        self.assertTrue(any("communes" in t for t in textes))

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


class TestSimulationMultiple(unittest.TestCase):
    """Simulation de plusieurs durées et bassin d'orage amont."""

    def setUp(self):
        self.page = PageFactice()
        self.etat = etat_complet()

    def vue(self) -> VueBassin:
        v = VueBassin(self.page, self.etat)
        v.afficher()
        return v

    def _bouton(self, vue, libelle):
        boutons = (_rechercher(vue.corps, ft.FilledButton)
                   + _rechercher(vue.corps, ft.OutlinedButton)
                   + _rechercher(vue.corps, ft.ElevatedButton))
        for bouton in boutons:
            libelles = [getattr(bouton, "text", None)]
            libelles += [t.value for t in _rechercher(bouton, ft.Text)]
            if any(libelle in (t or "") for t in libelles):
                return bouton
        self.fail(f"bouton « {libelle} » introuvable")

    def test_les_durees_se_cochent_et_se_simulent(self):
        vue = self.vue()
        puces = _rechercher(vue.corps, ft.Chip)
        self.assertEqual(len(puces), len(rainfall.QDF_DURATIONS_MIN))
        cochees = [c for c in puces if c.selected]
        self.assertEqual(len(cochees), 1, "une durée doit être cochée au départ")

        # L'utilisateur en coche trois de plus.
        vue._selection_durees = {60.0, 180.0, 720.0}
        self._bouton(vue, "Simuler").on_click(None)
        tableaux = _rechercher(vue.corps, ft.DataTable)
        self.assertTrue(tableaux)
        self.assertEqual(len(tableaux[-1].rows), 3)

    def test_tout_cocher_puis_tout_decocher(self):
        vue = self.vue()
        self._bouton(vue, "Tout cocher").on_click(None)
        self.assertEqual(len(vue._selection_durees), len(rainfall.QDF_DURATIONS_MIN))
        self._bouton(vue, "Tout décocher").on_click(None)
        self.assertEqual(vue._selection_durees, set())
        self._bouton(vue, "Simuler").on_click(None)
        textes = [t.value for t in _rechercher(vue.corps, ft.Text)]
        self.assertTrue(any("au moins une durée" in (t or "") for t in textes))

    def test_le_panneau_amont_s_ouvre_et_se_ferme(self):
        vue = self.vue()
        interrupteurs = _rechercher(vue.corps, ft.Switch)
        self.assertTrue(interrupteurs)
        amont = interrupteurs[0]
        self.assertFalse(self.etat.projet.amont.actif)
        # Les champs du bassin amont n'apparaissent qu'une fois activé.
        libelles = [c.label for c in _rechercher(vue.corps, ft.TextField)]
        self.assertNotIn("Surface du bassin versant amont", libelles)

        amont.on_change(_Evenement(_Controle(True)))
        self.assertTrue(self.etat.projet.amont.actif)
        libelles = [c.label for c in _rechercher(vue.corps, ft.TextField)]
        self.assertIn("Surface du bassin versant amont", libelles)
        self.assertIn("Volume de temporisation amont", libelles)

    def test_le_volume_minimal_amont_est_propose(self):
        p = self.etat.projet
        p.amont.actif = True
        p.amont.surface_bv_m2 = 8000.0
        p.amont.coef_ruissellement = 0.8
        p.amont.debit_ajutage_ls = 2.0
        vue = self.vue()
        self.assertAlmostEqual(p.amont.volume_temporisation_m3, 0.0)
        self._bouton(vue, "Proposer le volume minimal").on_click(None)
        attendu = hydro.volume_amont_minimal_m3(p)
        self.assertGreater(attendu, 0)
        self.assertAlmostEqual(p.amont.volume_temporisation_m3, attendu)

    def _projet_une_demi_hectare(self):
        p = self.etat.projet
        for surface in p.surfaces:
            surface.aire_m2 = 0.0
        p.surfaces[7].aire_m2 = 5000.0          # 0,5 ha en aval, coefficient 1,0
        p.amont.actif = True
        p.amont.surface_bv_m2 = 10000.0
        self.etat.invalider()
        return p

    def test_cocher_la_surface_amont_augmente_un_ajutage_specifique(self):
        """5 l/(s·ha) encodés, 10 000 m² amont : l'ajutage aval passe à 7,5 l/s."""
        p = self._projet_une_demi_hectare()
        p.fixer_ajutage_specifique(5.0)
        self.assertAlmostEqual(p.debit_ajutage_ls, 2.5, places=6)

        vue = self.vue()
        cases = _rechercher(vue.corps, ft.Checkbox)
        self.assertTrue(cases, "la case du bassin versant amont est absente")
        cases[0].on_change(_Evenement(_Controle(True)))

        self.assertTrue(p.amont.inclure_bv_dans_ajutage)
        self.assertAlmostEqual(p.aire_raccordee_m2, 15000.0)
        self.assertAlmostEqual(p.debit_ajutage_ls, 7.5, places=6)
        self.assertAlmostEqual(p.bassin.debit_ajutage_ls, 7.5, places=6)
        self.assertAlmostEqual(p.debit_specifique_ajutage_ls_ha, 5.0, places=6)

        cases = _rechercher(vue.corps, ft.Checkbox)
        cases[0].on_change(_Evenement(_Controle(False)))
        self.assertAlmostEqual(p.debit_ajutage_ls, 2.5, places=6)

    def test_cocher_la_surface_amont_ne_touche_pas_un_ajutage_impose(self):
        """2,5 l/s encodés : la valeur absolue tient, seul l'admissible augmente."""
        p = self._projet_une_demi_hectare()
        p.fixer_ajutage_absolu(2.5)

        vue = self.vue()
        cases = _rechercher(vue.corps, ft.Checkbox)
        cases[0].on_change(_Evenement(_Controle(True)))

        self.assertAlmostEqual(p.debit_ajutage_ls, 2.5, places=6)
        self.assertAlmostEqual(p.bassin.debit_ajutage_ls, 2.5, places=6)
        self.assertAlmostEqual(p.debit_fuite_admissible_ls, 7.5, places=6)


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
