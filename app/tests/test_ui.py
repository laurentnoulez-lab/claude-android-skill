"""Tests de construction de l'interface (sans serveur graphique).

Ils instancient chaque vue et parcourent l'arbre de contrôles Flet : toute
erreur d'API (paramètre inconnu, icône ou couleur inexistante) est détectée.
"""

import os
import re
import sys
import json
import shutil
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

import flet as ft  # noqa: E402

from bassin.core import hydro, rainfall  # noqa: E402
from bassin.core.model import Bassin, BassinAmont, SCENARIO_SEUIL  # noqa: E402
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

    def test_les_textes_d_aide_se_replient(self):
        """Sur téléphone, une aide un peu longue était coupée net à droite."""
        champ = theme.champ_nombre("Volume sous l'ajutage", 0.0, lambda v: None, "m³",
                                   "orifice surélevé · scénario 4 · partagé avec l'onglet Bassin")
        self.assertGreater(champ.helper_max_lines or 1, 1)
        for controle in theme.champs_convertis("Débit", "l/s", 1.0, "soit", "l/s/ha", 2.0,
                                               lambda v: None, aide_b="un texte d'aide long"):
            self.assertGreater(controle.helper_max_lines or 1, 1)

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


def _champs_texte(controle, trouves=None):
    """Tous les TextField de l'arbre, dans l'ordre."""
    trouves = [] if trouves is None else trouves
    if isinstance(controle, ft.TextField):
        trouves.append(controle)
    for attribut in ("controls", "content", "controle", "actions"):
        valeur = getattr(controle, attribut, None)
        if isinstance(valeur, list):
            for enfant in valeur:
                _champs_texte(enfant, trouves)
        elif valeur is not None and hasattr(valeur, "__dict__"):
            _champs_texte(valeur, trouves)
    return trouves


def _textes(controle, trouves=None):
    trouves = [] if trouves is None else trouves
    valeur = getattr(controle, "value", None)
    if isinstance(controle, ft.Text) and isinstance(valeur, str):
        trouves.append(valeur)
    for attribut in ("controls", "content", "controle", "actions"):
        v = getattr(controle, attribut, None)
        if isinstance(v, list):
            for enfant in v:
                _textes(enfant, trouves)
        elif v is not None and hasattr(v, "__dict__"):
            _textes(v, trouves)
    return trouves


class _Saisie:
    """Événement Flet minimal : seul ``control`` est lu par les gestionnaires."""

    def __init__(self, controle):
        self.control = controle


class TestRafraichissementApresSaisie(unittest.TestCase):
    """Une valeur saisie doit se voir à l'écran, même si le focus ne se perd pas.

    Sous Windows, cliquer dans une zone non saisissable ne déclenche pas
    toujours ``on_blur`` : l'écran gardait alors les anciens résultats, sans
    aucun signe que la saisie n'avait pas été prise en compte.
    """

    def _vue_prete(self):
        page = PageFactice()
        etat = EtatApplication()
        etat.projet.surfaces[7].aire_m2 = 20000.0
        etat.projet.surface_infiltration_m2 = 250.0
        etat.projet.debit_ajutage_ls = 10.0
        vue = VueDimensionnement(page, etat)
        vue.afficher()
        return page, etat, vue

    def _volume_affiche(self, vue):
        return [t for t in _textes(vue.zone) if t.replace(",", ".").replace(" ", "")
                .replace("m³", "").strip().replace(".", "").isdigit()]

    def test_la_saisie_seule_programme_un_rafraichissement(self):
        """Sans blur, le volume affiché restait celui d'avant la saisie."""
        page, etat, vue = self._vue_prete()
        avant = etat.resultat.volume_m3
        affiche_avant = _textes(vue.zone)

        champ = next(c for c in _champs_texte(vue.corps)
                     if "infiltration" in (c.label or "").lower()
                     and (c.suffix_text or "") == "m²")
        champ.value = "5000"
        champ.on_change(_Saisie(champ))          # frappe, sans quitter le champ

        # Le modèle a changé, et un recalcul est programmé.
        self.assertEqual(etat.projet.surface_infiltration_m2, 5000.0)
        self.assertNotAlmostEqual(etat.resultat.volume_m3, avant, places=3)
        self.assertTrue(vue.rafraichisseur.en_attente,
                        "aucun rafraîchissement n'a été programmé après la saisie")

        vue.rafraichisseur.executer_maintenant()
        self.assertNotEqual(_textes(vue.zone), affiche_avant,
                            "la zone de résultats n'a pas été rafraîchie après la saisie")
        self.assertFalse(vue.rafraichisseur.en_attente)

    def test_une_vue_masquee_ne_recalcule_pas(self):
        """Sept vues qui recalculent à chaque frappe rendraient la saisie inutilisable."""
        page, etat, vue = self._vue_prete()
        vue.masquer()
        etat.projet.surface_infiltration_m2 = 5000.0
        etat.invalider()
        self.assertFalse(vue.rafraichisseur.en_attente)

    def test_le_minuteur_finit_par_jouer_tout_seul(self):
        """C'est lui le filet quand la sortie de champ n'a pas lieu."""
        from bassin.ui.rafraichissement import Rafraichisseur

        joues = []
        fini = threading.Event()

        def action():
            joues.append(1)
            fini.set()

        r = Rafraichisseur(action, delai_s=0.01)
        for _ in range(5):          # cinq frappes rapprochées
            r.demander()
        self.assertTrue(fini.wait(2.0), "le minuteur n'a jamais joué")
        self.assertEqual(joues, [1], "les frappes doivent être regroupées en un seul recalcul")

    def test_une_panne_de_calcul_se_voit(self):
        """Un écran figé ne dit rien : la panne doit s'afficher."""
        page, etat, vue = self._vue_prete()
        vue.resultats = lambda: (_ for _ in ()).throw(ValueError("boum"))
        vue.maj_resultats()
        self.assertTrue(any("recalcul" in t.lower() for t in _textes(vue.zone)),
                        "la panne de recalcul n'est pas signalée à l'écran")

    def test_le_blur_rafraichit_toujours(self):
        page, etat, vue = self._vue_prete()
        affiche_avant = _textes(vue.zone)
        champ = next(c for c in _champs_texte(vue.corps)
                     if "infiltration" in (c.label or "").lower()
                     and (c.suffix_text or "") == "m²")
        champ.value = "5000"
        champ.on_blur(_Saisie(champ))
        self.assertEqual(etat.projet.surface_infiltration_m2, 5000.0)
        self.assertNotEqual(_textes(vue.zone), affiche_avant)


class _ResultatSelecteur:
    """Événement du sélecteur de fichiers du système."""

    def __init__(self, path=None, files=None):
        self.path = path
        self.files = files


class _FichierChoisi:
    def __init__(self, path):
        self.path = path


class TestSauvegardeSousAndroid(unittest.TestCase):
    """Le sélecteur d'Android rend un URI de document, pas un chemin de fichier.

    Signalé depuis un téléphone : « Copie impossible : [Errno 2] No such file or
    directory: '/document/primary:Documents/test_T25 (1).json' ». Le projet était
    pourtant bien enregistré — seule la copie vers l'URI échouait, et le message
    laissait croire que l'export n'avait pas marché.
    """

    URI_ANDROID = "/document/primary:Documents/test_T25 (1).json"

    def setUp(self):
        self.repertoire = tempfile.mkdtemp(prefix="hydrobassin_android_")

    def tearDown(self):
        shutil.rmtree(self.repertoire, ignore_errors=True)

    def test_un_uri_de_document_n_est_pas_une_destination(self):
        from bassin.ui.state import destination_utilisable, source_utilisable

        self.assertFalse(destination_utilisable(self.URI_ANDROID))
        self.assertFalse(destination_utilisable(
            "content://com.android.externalstorage.documents/document/primary:Download/p.json"))
        self.assertFalse(source_utilisable(self.URI_ANDROID))
        # Un vrai chemin, lui, reste utilisable.
        reel = os.path.join(self.repertoire, "projet.json")
        self.assertTrue(destination_utilisable(reel))
        with open(reel, "w", encoding="utf-8") as fh:
            fh.write("{}")
        self.assertTrue(source_utilisable(reel))

    def _vue(self):
        page = PageFactice()
        vue = VueProjet(page, EtatApplication())
        vue.afficher()
        return page, vue

    def test_l_export_ne_crie_pas_a_l_echec_sur_un_uri(self):
        page, vue = self._vue()
        source = vue.etat.exporter_vers(os.path.join(self.repertoire, "source.json"))
        vue._enregistrer_sous(source)          # installe le sélecteur
        vue._selecteur_export.data = source
        vue._selecteur_export.on_result(_ResultatSelecteur(path=self.URI_ANDROID))

        messages = [c.content.value for c in page.ouverts if hasattr(c, "content")]
        self.assertTrue(messages)
        dernier = messages[-1]
        self.assertNotIn("Copie impossible", dernier)
        self.assertIn(source, dernier, "le message doit dire où le projet se trouve réellement")

    def test_l_export_copie_vraiment_vers_un_chemin_reel(self):
        page, vue = self._vue()
        source = vue.etat.exporter_vers(os.path.join(self.repertoire, "source.json"))
        cible = os.path.join(self.repertoire, "ailleurs.json")
        vue._enregistrer_sous(source)
        vue._selecteur_export.data = source
        vue._selecteur_export.on_result(_ResultatSelecteur(path=cible))
        self.assertTrue(os.path.exists(cible))
        EtatApplication().importer_fichier(cible)      # relisible

    def test_l_import_d_un_uri_est_refuse_avec_un_conseil(self):
        page, vue = self._vue()
        avant = vue.etat.to_json()
        self.assertFalse(vue.charger_fichier(self.URI_ANDROID))
        self.assertEqual(vue.etat.to_json(), avant)
        messages = [c.content.value for c in page.ouverts if hasattr(c, "content")]
        self.assertTrue(any("Téléchargements" in m for m in messages),
                        "le message doit dire quoi faire")

    def test_sur_mobile_l_export_n_ouvre_pas_le_selecteur(self):
        """Inutile de proposer un sélecteur qui ne rendra qu'un URI."""
        page = PageFactice()
        page.platform = ft.PagePlatform.ANDROID
        vue = VueProjet(page, EtatApplication())
        vue.afficher()
        vue._exporter()
        self.assertIsNone(vue._selecteur_export,
                          "aucun sélecteur ne doit être ouvert sur téléphone")
        self.assertTrue(vue._dernier_export and os.path.exists(vue._dernier_export))
        messages = [c.content.value for c in page.ouverts if hasattr(c, "content")]
        self.assertTrue(any("enregistré" in m for m in messages))


class TestBassinAmontDansLeDimensionnement(unittest.TestCase):
    """Le bassin amont se déclare et se voit depuis l'onglet Dimensionnement.

    Le moteur l'intégrait déjà au volume à mettre en œuvre, mais l'onglet n'en
    disait rien et ne permettait pas de l'encoder : il fallait le deviner dans
    l'onglet Bassin, et rien à l'écran ne signalait qu'il comptait.
    """

    def _etat(self, actif=True):
        etat = EtatApplication()
        p = etat.projet
        p.surfaces[7].aire_m2 = 20000.0
        p.surface_infiltration_m2 = 250.0
        p.fixer_ajutage_absolu(12.0)
        p.bassin = Bassin(volume_total_m3=1200.0, volume_sous_ajutage_m3=50.0,
                          surface_dispersion_m2=250.0, debit_ajutage_ls=12.0)
        p.amont = BassinAmont(actif=actif, surface_bv_m2=10000.0, coef_ruissellement=0.9,
                              debit_ajutage_ls=5.0, volume_temporisation_m3=300.0)
        return etat

    def test_le_panneau_amont_est_dans_les_deux_onglets(self):
        etat = self._etat()
        dim = VueDimensionnement(PageFactice(), etat)
        bas = VueBassin(PageFactice(), etat)
        dim.afficher()
        bas.afficher()
        for vue, nom in ((dim, "Dimensionnement"), (bas, "Bassin")):
            with self.subTest(vue=nom):
                textes = _textes(vue.corps)
                self.assertIn("Bassin d'orage amont", textes)
                champs = [c.label for c in _champs_texte(vue.corps) if c.label]
                self.assertTrue(any("bassin versant amont" in (l or "").lower() for l in champs),
                                f"pas de champ de saisie de l'amont dans l'onglet {nom}")

    def test_encoder_l_amont_depuis_le_dimensionnement_change_les_volumes(self):
        """C'est tout l'objet de la 2.0 : le déclarer là où l'on dimensionne."""
        etat = self._etat(actif=False)
        vue = VueDimensionnement(PageFactice(), etat)
        vue.afficher()
        sans = etat.resultat.volume_m3

        etat.projet.amont.actif = True
        etat.invalider()
        vue.rafraichir()
        champ = next(c for c in _champs_texte(vue.corps)
                     if "bassin versant amont" in (c.label or "").lower())
        champ.value = "40000"
        champ.on_blur(_Saisie(champ))

        self.assertAlmostEqual(etat.projet.amont.surface_bv_m2, 40000.0)
        self.assertGreater(etat.resultat.volume_m3, sans,
                           "le bassin amont encodé ici doit gonfler le volume à mettre en œuvre")
        self.assertTrue(etat.resultat.amont_pris_en_compte)

    def test_l_ecran_annonce_que_l_apport_est_compte(self):
        etat = self._etat()
        vue = VueDimensionnement(PageFactice(), etat)
        vue.afficher()
        avis = [t for t in _textes(vue.zone) if "comprennent l'apport" in t]
        self.assertTrue(avis, "rien n'indique que les volumes comprennent l'apport amont")
        self.assertIn("5,000 l/s", avis[0])

    def test_sans_amont_aucune_mention_parasite(self):
        etat = self._etat(actif=False)
        vue = VueDimensionnement(PageFactice(), etat)
        vue.afficher()
        self.assertFalse([t for t in _textes(vue.zone) if "comprennent l'apport" in t])

    def test_les_deux_onglets_partagent_le_meme_amont(self):
        """Un seul ouvrage amont, deux endroits pour le décrire."""
        etat = self._etat()
        dim = VueDimensionnement(PageFactice(), etat)
        bas = VueBassin(PageFactice(), etat)
        dim.afficher()
        bas.afficher()
        champ = next(c for c in _champs_texte(dim.corps)
                     if "ajutage amont" in (c.label or "").lower())
        champ.value = "2,5"
        champ.on_blur(_Saisie(champ))
        self.assertAlmostEqual(etat.projet.amont.debit_ajutage_ls, 2.5)
        bas.rafraichir()
        valeurs = [c.value for c in _champs_texte(bas.corps)
                   if "ajutage amont" in (c.label or "").lower()]
        self.assertEqual(len(valeurs), 1)
        self.assertAlmostEqual(float(valeurs[0].replace(",", ".")), 2.5,
                               msg="l'onglet Bassin doit montrer ce qui a été encodé ailleurs")


class TestSauvegardeDeProjet(unittest.TestCase):
    """Exporter puis réimporter doit rendre le projet à l'identique."""

    def setUp(self):
        self.repertoire = tempfile.mkdtemp(prefix="hydrobassin_projet_")

    def tearDown(self):
        shutil.rmtree(self.repertoire, ignore_errors=True)

    def _etat_garni(self):
        etat = EtatApplication()
        p = etat.projet
        p.nom_projet, p.auteur, p.localisation = "Lotissement", "L. N.", "Amay"
        p.remarques = "essai d'infiltration du 12/03"
        p.commune_ins, p.commune_nom, p.periode_retour = "61003", "Amay", 50
        p.surfaces[7].aire_m2 = 12000.0
        p.surfaces[2].aire_m2 = 3000.0
        p.surface_reference_m2 = 30000.0
        p.k_infiltration_ms, p.coef_securite_infiltration = 5e-6, 1.5
        p.surface_infiltration_m2 = 400.0
        p.fixer_ajutage_specifique(5.0)
        p.bassin = Bassin(volume_total_m3=900.0, volume_sous_ajutage_m3=80.0,
                          surface_dispersion_m2=400.0, debit_ajutage_ls=p.debit_ajutage_ls)
        p.amont = BassinAmont(actif=True, surface_bv_m2=8000.0, coef_ruissellement=0.8,
                              debit_ajutage_ls=4.0, volume_temporisation_m3=250.0,
                              inclure_bv_dans_ajutage=True)
        etat.scenario_principal = SCENARIO_SEUIL
        return etat

    def test_aller_retour_par_fichier(self):
        etat = self._etat_garni()
        chemin = etat.exporter_vers(os.path.join(self.repertoire, "essai.json"))
        self.assertTrue(os.path.getsize(chemin) > 0)

        relu = EtatApplication()
        relu.importer_fichier(chemin)
        self.assertEqual(relu.to_json(), etat.to_json())
        # Et les résultats calculés coïncident, pas seulement les données.
        self.assertAlmostEqual(relu.resultat.volume_m3, etat.resultat.volume_m3, places=9)
        self.assertEqual(relu.scenario_principal, SCENARIO_SEUIL)
        self.assertTrue(relu.projet.amont.actif)
        self.assertTrue(relu.projet.ajutage_suit_la_surface)

    def test_le_fichier_est_lisible_a_l_oeil(self):
        """Un projet doit pouvoir s'inspecter et se corriger dans un éditeur."""
        etat = self._etat_garni()
        chemin = etat.exporter_vers(os.path.join(self.repertoire, "essai.json"))
        with open(chemin, encoding="utf-8") as fh:
            texte = fh.read()
        self.assertIn("\n", texte, "le fichier doit être indenté")
        self.assertIn("HydroBassin", texte)
        self.assertIn("Lotissement", texte)

    def test_la_vue_charge_un_fichier(self):
        etat_source = self._etat_garni()
        chemin = etat_source.exporter_vers(os.path.join(self.repertoire, "essai.json"))

        page = PageFactice()
        vue = VueProjet(page, EtatApplication())
        vue.afficher()
        self.assertTrue(vue.charger_fichier(chemin))
        self.assertEqual(vue.etat.projet.nom_projet, "Lotissement")
        self.assertEqual(vue.etat.projet.commune_ins, "61003")
        self.assertAlmostEqual(vue.etat.projet.aire_totale_m2, 15000.0)

    def test_un_fichier_etranger_est_refuse_avec_un_message(self):
        page = PageFactice()
        vue = VueProjet(page, EtatApplication())
        vue.afficher()
        avant = vue.etat.to_json()
        chemin = os.path.join(self.repertoire, "autre.json")
        with open(chemin, "w", encoding="utf-8") as fh:
            fh.write('{"application": "AutreLogiciel", "projet": {}}')
        self.assertFalse(vue.charger_fichier(chemin))
        self.assertEqual(vue.etat.to_json(), avant, "le projet courant doit rester intact")
        self.assertTrue(page.ouverts, "aucun message n'a été montré à l'utilisateur")

    def test_un_projet_d_une_version_anterieure_se_recharge(self):
        """Les champs ajoutés depuis ne doivent pas empêcher la relecture."""
        ancien = json.dumps({
            "projet": {"commune_ins": "61003", "commune_nom": "Amay", "periode_retour": 25,
                       "surfaces": [{"libelle": "Toitures", "coefficient": 1.0,
                                     "aire_m2": 5000.0, "note": ""}],
                       "champ_disparu": 42},
            "scenario": "mixte",
        })
        etat = EtatApplication()
        etat.importer_texte(ancien)
        self.assertEqual(etat.projet.commune_ins, "61003")
        self.assertAlmostEqual(etat.projet.aire_totale_m2, 5000.0)


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
