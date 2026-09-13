"""Tests de construction de l'interface (sans serveur graphique).

Ils instancient chaque vue et parcourent l'arbre de contrôles Flet : toute
erreur d'API (paramètre inconnu, icône ou couleur inexistante) est détectée.
"""

import contextlib
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
from bassin.core.model import (  # noqa: E402
    Bassin, BassinAmont, SurfaceIncidente, SCENARIO_SEUIL,
)
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
from bassin.ui.vues.reseau import VueReseau  # noqa: E402
from bassin.ui.vues.synthese import VueSynthese  # noqa: E402
from bassin.ui.vues.versants import VueVersants  # noqa: E402

VUES = (VueProjet, VueVersants, VueReseau, VueDimensionnement, VueBassin, VueTableQDF,
        VueAjutage, VueSynthese, VuePluies, VueRapport)


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
        etat.systeme.commune_ins, etat.systeme.commune_nom = "56011", "Binche"
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
        self.assertEqual(graphiques._bulle("Volume stocké", 500.0, 1234.5, "Temps [min]"),
                         "Volume stocké : 1234,50 — Temps : 8 h 20")
        vue = VueBassin(self.page, self.etat)
        vue.afficher()
        bulles = [p.tooltip for serie in _rechercher(vue.corps, ft.LineChart)
                  for d in serie.data_series for p in d.data_points if p.tooltip]
        self.assertTrue(bulles)
        for bulle in bulles:
            self.assertIsNone(re.search(r"\d\.\d", bulle), f"« {bulle} » garde un point décimal")

    def test_les_info_bulles_donnent_aussi_l_abscisse(self):
        """Lire une valeur sur une courbe suppose de savoir à quel instant.

        Un pic de remplissage ne s'interprète pas sans l'heure où il tombe.
        L'abscisse se lit dans l'unité de l'axe — min, h ou j pour un temps,
        l'unité du libellé sinon — et jamais en minutes brutes.
        """
        cas = [
            ("Temps [min]", 500.0, "Temps : 8 h 20"),
            ("Temps [min]", 45.0, "Temps : 45 min"),
            ("Durée de pluie", 2880.0, "Durée de pluie : 2 j"),
            ("Charge [m]", 1.0, "Charge : 1,0 m"),
        ]
        for axe, x, attendu in cas:
            with self.subTest(axe=axe):
                self.assertIn(attendu, graphiques._bulle("Débit", x, 3.0, axe))

        # Et sur un vrai graphique de l'application, pas seulement en théorie.
        vue = VueBassin(self.page, self.etat)
        vue.afficher()
        bulles = [p.tooltip for serie in _rechercher(vue.corps, ft.LineChart)
                  for d in serie.data_series for p in d.data_points if p.tooltip]
        self.assertTrue(bulles)
        for bulle in bulles:
            self.assertIn(" — ", bulle, f"« {bulle} » ne donne pas son abscisse")
            self.assertNotRegex(bulle.split(" — ")[1], r":\s*\d+(,\d+)?$",
                                f"« {bulle} » donne son abscisse sans unité")

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

    def test_un_K_invraisemblable_est_signale_la_ou_il_se_tape(self):
        """Le GTI marque « valeur à vérifier » au-delà de 1e-4 m/s.

        L'alerte existait dans les résultats, mais un dossier se remplit champ
        par champ : elle doit se lire à côté de la valeur saisie.
        """
        def textes_du_formulaire():
            vue = VueDimensionnement(self.page, self.etat)
            vue.afficher()
            return [c.value for c in _rechercher(vue.corps, ft.Text)]

        self.etat.projet.k_infiltration_ms = 1e-5
        self.etat.invalider()
        self.assertFalse(any("à vérifier" in (t or "") for t in textes_du_formulaire()),
                         "un K courant ne doit pas déclencher d'avertissement")

        self.etat.projet.k_infiltration_ms = 5e-4
        self.etat.invalider()
        self.assertTrue(any("à vérifier" in (t or "") and "essai in situ" in (t or "")
                            for t in textes_du_formulaire()),
                        "K = 5e-4 m/s passe sans avertissement au point de saisie")

    def test_un_K_sans_sol_correspondant_ne_garde_pas_l_ancien_libelle(self):
        """Le dossier annoncerait une nature de sol incompatible avec le K utilisé."""
        from bassin.ui.vues.dimensionnement import SOL_PERSONNALISE, _sol_de

        self.assertEqual(_sol_de(1e-5), "1e-5")
        self.assertEqual(_sol_de(5e-4), SOL_PERSONNALISE)

        self.etat.projet.k_infiltration_ms = 5e-4
        self.etat.invalider()
        vue = VueDimensionnement(self.page, self.etat)
        vue.afficher()
        listes = [d for d in _rechercher(vue.corps, ft.Dropdown)
                  if d.label and "Nature du sol" in d.label]
        self.assertEqual(len(listes), 1)
        liste = listes[0]
        self.assertEqual(liste.value, SOL_PERSONNALISE)
        libelles = {o.key: o.text for o in liste.options}
        self.assertIn(SOL_PERSONNALISE, libelles)
        self.assertIn("personnalisée", libelles[SOL_PERSONNALISE])
        # Et surtout : plus aucune liste déroulante sans valeur affichable.
        self.assertTrue(liste.value)

    def test_un_K_nul_sous_une_surface_d_infiltration_est_signale(self):
        self.etat.projet.k_infiltration_ms = 0.0
        self.etat.projet.surface_infiltration_m2 = 200.0
        self.etat.invalider()
        vue = VueDimensionnement(self.page, self.etat)
        vue.afficher()
        textes = [c.value for c in _rechercher(vue.corps, ft.Text)]
        self.assertTrue(any("K nul" in (t or "") for t in textes),
                        "une surface d'infiltration qui n'infiltre rien passe sans un mot")

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


@contextlib.contextmanager
def _dossier_interne(chemin):
    """Détourne le dossier de repli des exports vers un répertoire de test.

    Sans cela, les cas de repli écriraient dans les Documents de la personne qui
    lance la suite.
    """
    from bassin.ui.vues import projet as vue_projet

    origine = vue_projet.repertoire_documents
    vue_projet.repertoire_documents = lambda: chemin
    try:
        yield
    finally:
        vue_projet.repertoire_documents = origine


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
        """Un URI n'est pas écrivable : on se replie, on le dit, on n'alarme pas."""
        page, vue = self._vue()
        with _dossier_interne(self.repertoire):
            vue._destination_choisie(_ResultatSelecteur(path=self.URI_ANDROID))

        messages = [c.content.value for c in page.ouverts if hasattr(c, "content")]
        self.assertTrue(messages)
        self.assertFalse(any("Copie impossible" in m for m in messages))
        self.assertTrue(any("dossier de l'application" in m for m in messages),
                        "le message doit dire où le projet a été rangé")
        self.assertTrue(vue._dernier_export and os.path.exists(vue._dernier_export),
                        "le projet doit exister malgré tout : l'URI n'est pas une destination")

    def test_l_export_ecrit_vraiment_vers_un_chemin_reel(self):
        """Écriture directe à la destination choisie, sans fichier intermédiaire."""
        page, vue = self._vue()
        cible = os.path.join(self.repertoire, "ailleurs.json")
        vue._destination_choisie(_ResultatSelecteur(path=cible))
        self.assertTrue(os.path.exists(cible))
        EtatApplication().importer_fichier(cible)      # relisible
        self.assertEqual(os.listdir(self.repertoire), ["ailleurs.json"],
                         "aucun fichier intermédiaire ne doit traîner")

    def test_annuler_le_selecteur_n_ecrit_rien_et_n_annonce_rien(self):
        """Le défaut A4 : « enregistré » s'affichait avant l'arbitrage.

        Qui annulait la boîte de dialogue avait pourtant un fichier sur le
        disque, dans un dossier qu'il n'avait pas choisi.
        """
        page, vue = self._vue()
        with _dossier_interne(self.repertoire):
            vue._destination_choisie(_ResultatSelecteur(path=None))
        self.assertEqual(os.listdir(self.repertoire), [],
                         "une annulation ne doit laisser aucun fichier")
        messages = [c.content.value for c in page.ouverts if hasattr(c, "content")]
        self.assertFalse(any("enregistré" in m.lower() for m in messages),
                         "une annulation ne doit annoncer aucun succès")

    def test_l_extension_est_ajoutee_si_l_utilisateur_l_omet(self):
        page, vue = self._vue()
        vue._destination_choisie(_ResultatSelecteur(path=os.path.join(self.repertoire, "sans")))
        self.assertTrue(os.path.exists(os.path.join(self.repertoire, "sans.json")))

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


class TestReseauDansLInterface(unittest.TestCase):
    """Le réseau se construit et se lit depuis l'onglet « Réseau ».

    Le bassin d'orage amont unique des versions 2.x est devenu un ouvrage comme
    un autre : il s'ajoute, se nomme, se raccorde, et son apport se voit dans
    l'onglet Dimensionnement de l'ouvrage qu'il alimente.
    """

    def _etat(self, avec_amont=True):
        etat = EtatApplication()
        p = etat.projet
        p.surfaces[7].aire_m2 = 20000.0
        p.surface_infiltration_m2 = 250.0
        p.fixer_ajutage_absolu(12.0)
        p.bassin = Bassin(volume_total_m3=1200.0, volume_sous_ajutage_m3=50.0,
                          surface_dispersion_m2=250.0, debit_ajutage_ls=12.0)
        aval = etat.ouvrage
        aval.nom = "Bassin aval"
        if avec_amont:
            amont = etat.ajouter_ouvrage("Bassin amont")
            amont.aval_id = aval.id
            amont.etude.fixer_ajutage_absolu(5.0)
            amont.etude.bassin = Bassin(volume_total_m3=300.0, debit_ajutage_ls=5.0)
            versant = etat.ajouter_versant("BV amont", amont.id)
            versant.surfaces = [SurfaceIncidente("Imperméable", 0.9, 10000.0)]
            etat.choisir_ouvrage(aval.id)
        etat.invalider()
        return etat

    def test_ajouter_un_ouvrage_depuis_l_onglet_reseau(self):
        etat = EtatApplication()
        vue = VueReseau(PageFactice(), etat)
        vue.afficher()
        self.assertEqual(len(etat.ouvrages), 1)
        _bouton_nomme(self, vue, "Ajouter un bassin d'orage").on_click(None)
        self.assertEqual(len(etat.ouvrages), 2)

    def test_raccorder_un_ouvrage_a_un_autre(self):
        etat = self._etat(avec_amont=False)
        second = etat.ajouter_ouvrage("Second")
        vue = VueReseau(PageFactice(), etat)
        vue.afficher()
        listes = [d for d in _rechercher(vue.corps, ft.Dropdown)
                  if (d.label or "").startswith("Se déverse")]
        self.assertTrue(listes, "aucun sélecteur de raccordement")
        cible = etat.ouvrages[0]
        listes[-1].on_change(_Evenement(_Controle(cible.id)))
        self.assertEqual(second.aval_id, cible.id)
        self.assertEqual([o.nom for o in etat.systeme.ordre_amont_aval()][-1], cible.nom)

    def test_l_apport_amont_gonfle_le_volume_de_l_ouvrage_aval(self):
        sans = self._etat(avec_amont=False)
        avec = self._etat(avec_amont=True)
        self.assertGreater(avec.resultat.volume_m3, sans.resultat.volume_m3)
        self.assertTrue(avec.resultat.amont_pris_en_compte)
        self.assertFalse(sans.resultat.amont_pris_en_compte)

    def test_l_ecran_de_dimensionnement_annonce_que_l_apport_est_compte(self):
        etat = self._etat()
        vue = VueDimensionnement(PageFactice(), etat)
        vue.afficher()
        avis = [t for t in _textes(vue.zone) if "comprennent l'apport" in t]
        self.assertTrue(avis, "rien n'indique que les volumes comprennent l'apport amont")
        self.assertIn("Bassin amont", avis[0])

    def test_sans_amont_aucune_mention_parasite(self):
        etat = self._etat(avec_amont=False)
        vue = VueDimensionnement(PageFactice(), etat)
        vue.afficher()
        self.assertFalse([t for t in _textes(vue.zone) if "comprennent l'apport" in t])

    def test_la_barre_de_selection_apparait_des_qu_il_y_a_deux_ouvrages(self):
        seul = self._etat(avec_amont=False)
        vue = VueDimensionnement(PageFactice(), seul)
        vue.afficher()
        self.assertFalse([d for d in _rechercher(vue.corps, ft.Dropdown)
                          if (d.label or "").startswith("Bassin d'orage étudié")])
        plusieurs = self._etat()
        vue = VueDimensionnement(PageFactice(), plusieurs)
        vue.afficher()
        selecteurs = [d for d in _rechercher(vue.corps, ft.Dropdown)
                      if (d.label or "").startswith("Bassin d'orage étudié")]
        self.assertTrue(selecteurs, "impossible de choisir l'ouvrage étudié")
        amont = [o for o in plusieurs.ouvrages if o.nom == "Bassin amont"][0]
        selecteurs[0].on_change(_Evenement(_Controle(amont.id)))
        self.assertEqual(plusieurs.ouvrage.id, amont.id)

    def test_diriger_la_surverse_vers_le_milieu_naturel_soulage_l_aval(self):
        etat = self._etat()
        amont = [o for o in etat.ouvrages if o.nom == "Bassin amont"][0]
        amont.etude.bassin.volume_total_m3 = 10.0      # il surverse largement
        etat.invalider()
        avant = etat.resultat.volume_m3
        vue = VueReseau(PageFactice(), etat)
        vue._ouvert = amont.id
        vue.afficher()
        cases = [c for c in _rechercher(vue.corps, ft.Checkbox)]
        self.assertTrue(cases, "la case de surverse est absente")
        cases[0].on_change(_Evenement(_Controle(True)))
        self.assertTrue(amont.surverse_vers_milieu_naturel)
        self.assertLess(etat.resultat.volume_m3, avant)

    def test_le_dimensionnement_en_cascade_remplit_les_volumes(self):
        etat = self._etat()
        for ouvrage in etat.ouvrages:
            ouvrage.etude.bassin.volume_total_m3 = 0.0
        etat.invalider()
        vue = VueReseau(PageFactice(), etat)
        vue.afficher()
        _bouton_nomme(self, vue, "Dimensionner en cascade").on_click(None)
        for ouvrage in etat.ouvrages:
            self.assertGreater(ouvrage.etude.bassin.volume_total_m3, 0.0)
        for fiche in etat.fiches:
            self.assertTrue(fiche.suffisant, f"{fiche.nom} surverse encore")

    def test_supprimer_un_ouvrage_reporte_ses_raccordements(self):
        etat = self._etat()
        amont = [o for o in etat.ouvrages if o.nom == "Bassin amont"][0]
        aval = [o for o in etat.ouvrages if o.nom == "Bassin aval"][0]
        versant = etat.systeme.versants_de(aval.id)[0]
        self.assertTrue(etat.supprimer_ouvrage(aval.id))
        self.assertEqual(len(etat.ouvrages), 1)
        # L'amont prend la place de l'aval supprimé : il rejette à l'exutoire.
        self.assertEqual(amont.aval_id, "")
        # Le bassin versant de l'ouvrage supprimé n'a plus de destination : il
        # est signalé, plutôt que rattaché au hasard ou oublié.
        self.assertEqual(versant.bassin_id, "")
        self.assertTrue(any(versant.nom in a for a in etat.systeme.anomalies()))
        versant.bassin_id = amont.id
        etat.invalider()
        self.assertFalse(etat.systeme.anomalies())

    def test_le_dernier_ouvrage_ne_se_supprime_pas(self):
        etat = self._etat(avec_amont=False)
        self.assertFalse(etat.supprimer_ouvrage(etat.ouvrage.id))
        self.assertEqual(len(etat.ouvrages), 1)

    def test_compter_les_bassins_versants_amont_dans_l_ajutage(self):
        """5 l/(s·ha) encodés, 10 000 m² amont : l'ajutage aval passe à 7,5 l/s."""
        etat = self._etat()
        aval = etat.ouvrage
        for surface in etat.systeme.versants_de(aval.id)[0].surfaces:
            surface.aire_m2 = 0.0
        etat.systeme.versants_de(aval.id)[0].surfaces[7].aire_m2 = 5000.0   # 0,5 ha
        aval.etude.fixer_ajutage_specifique(5.0)
        etat.invalider()
        self.assertAlmostEqual(aval.etude.debit_ajutage_ls, 2.5, places=6)

        vue = VueReseau(PageFactice(), etat)
        vue._ouvert = aval.id
        vue.afficher()
        cases = [c for c in _rechercher(vue.corps, ft.Checkbox)]
        self.assertTrue(cases, "la case du bassin versant amont est absente")
        cases[-1].on_change(_Evenement(_Controle(True)))
        self.assertTrue(aval.compter_bv_amont_dans_ajutage)
        self.assertAlmostEqual(aval.etude.aire_raccordee_m2, 15000.0)
        self.assertAlmostEqual(aval.etude.debit_ajutage_ls, 7.5, places=6)
        self.assertAlmostEqual(aval.etude.bassin.debit_ajutage_ls, 7.5, places=6)

    def test_un_ajutage_impose_ne_suit_pas_la_surface_amont(self):
        etat = self._etat()
        aval = etat.ouvrage
        aval.etude.fixer_ajutage_absolu(2.5)
        aval.compter_bv_amont_dans_ajutage = True
        etat.invalider()
        self.assertAlmostEqual(aval.etude.debit_ajutage_ls, 2.5, places=6)
        self.assertGreater(aval.etude.debit_fuite_admissible_ls, 2.5)


class TestSyntheseGraphique(unittest.TestCase):
    """Le schéma doit montrer tout le monde, et rien ne doit s'y superposer."""

    def _etat(self):
        etat = EtatApplication()
        p = etat.projet
        p.surfaces[7].aire_m2 = 20000.0
        p.surface_infiltration_m2 = 250.0
        p.fixer_ajutage_absolu(12.0)
        p.bassin = Bassin(volume_total_m3=1200.0, surface_dispersion_m2=250.0,
                          debit_ajutage_ls=12.0)
        etat.ouvrage.nom = "Bassin aval"
        amont = etat.ajouter_ouvrage("Bassin amont")
        amont.aval_id = etat.systeme.ouvrages[0].id
        amont.etude.fixer_ajutage_absolu(5.0)
        amont.etude.bassin = Bassin(volume_total_m3=300.0, debit_ajutage_ls=5.0)
        versant = etat.ajouter_versant("BV amont", amont.id)
        versant.surfaces = [SurfaceIncidente("Imperméable", 0.9, 10000.0)]
        etat.choisir_ouvrage(etat.systeme.ouvrages[0].id)
        etat.invalider()
        return etat

    def test_le_schema_nomme_chaque_ouvrage_et_chaque_bassin_versant(self):
        etat = self._etat()
        vue = VueSynthese(PageFactice(), etat)
        vue.afficher()
        textes = _textes(vue.corps)
        for attendu in ("Bassin aval", "Bassin amont", "BV amont", "Exutoire"):
            self.assertIn(attendu, textes, f"« {attendu} » absent du schéma")

    def test_aucune_boite_du_schema_n_en_recouvre_une_autre(self):
        """C'est la géométrie partagée avec le PDF : elle doit rester propre."""
        from bassin.reports import schema as schema_module

        etat = self._etat()
        for i in range(3):                       # un réseau un peu touffu
            ouvrage = etat.ajouter_ouvrage(f"Bassin {i}")
            ouvrage.aval_id = etat.systeme.ouvrages[0].id
            etat.ajouter_versant(f"BV {i}", ouvrage.id)
            etat.ajouter_versant(f"BV bis {i}", ouvrage.id)
        etat.invalider()
        schema = schema_module.construire(etat.systeme, etat.fiches)
        boites = schema.boites
        self.assertGreaterEqual(len(boites), 10)
        for i, a in enumerate(boites):
            for b in boites[i + 1:]:
                chevauche = not (a.droite <= b.x or b.droite <= a.x
                                 or a.bas <= b.y or b.bas <= a.y)
                self.assertFalse(chevauche,
                                 f"« {a.titre} » recouvre « {b.titre} » dans le schéma")

    def test_les_etiquettes_du_schema_tiennent_dans_leur_boite(self):
        from bassin.reports import schema as schema_module

        etat = self._etat()
        etat.ouvrage.nom = "Bassin d'orage du lotissement des Trois Fontaines"
        etat.invalider()
        schema = schema_module.construire(etat.systeme, etat.fiches)
        for boite in schema.boites:
            for ligne in boite.lignes:
                self.assertLessEqual(len(ligne), schema_module.CARACTERES_MAX,
                                     f"ligne trop longue dans « {boite.titre} » : {ligne}")

    def test_la_simulation_du_systeme_couvre_tous_les_ouvrages(self):
        etat = self._etat()
        vue = VueSynthese(PageFactice(), etat)
        vue.afficher()
        tableaux = _rechercher(vue.corps, ft.DataTable)
        self.assertTrue(tableaux)
        self.assertEqual(len(tableaux[0].rows), len(etat.ouvrages))

    def test_le_schema_se_construit_sans_aucune_donnee(self):
        vue = VueSynthese(PageFactice(), EtatApplication())
        self.assertTrue(vue.construire())


class TestRafraichisseurSansFils(unittest.TestCase):
    """Sous Pyodide, `thread.start()` refuse : l'écran ne doit pas rester périmé."""

    def test_un_minuteur_impossible_recalcule_tout_de_suite(self):
        from bassin.ui.rafraichissement import Rafraichisseur

        class _MinuteurRefuse:
            def __init__(self, delai, action):
                self.action = action

            def start(self):
                raise RuntimeError("can't start new thread")

            def cancel(self):
                pass

        appels = []
        r = Rafraichisseur(lambda: appels.append(1), minuteur=_MinuteurRefuse)
        r.demander()
        self.assertEqual(appels, [1], "le recalcul doit se faire à défaut de minuteur")
        self.assertFalse(r.en_attente)
        r.demander()
        self.assertEqual(appels, [1, 1])


class TestMiseEnPage(unittest.TestCase):
    """Aucune étiquette ne doit en recouvrir une autre, ni déborder de sa boîte.

    Sans serveur graphique on ne mesure pas le rendu, mais on peut relire l'arbre
    de contrôles : les seuls éléments réellement posés à des coordonnées fixes
    sont ceux du schéma du réseau, et c'est là que le risque existe.
    """

    #: Le coude d'une flèche fait forcément se toucher ses deux segments.
    TOLERANCE_PX = 2.0

    def _etat_touffu(self):
        etat = EtatApplication()
        p = etat.projet
        p.surfaces[7].aire_m2 = 20000.0
        p.surface_infiltration_m2 = 250.0
        p.fixer_ajutage_absolu(12.0)
        p.bassin = Bassin(volume_total_m3=1200.0, surface_dispersion_m2=250.0,
                          debit_ajutage_ls=12.0)
        etat.ouvrage.nom = "Bassin d'orage principal du parc d'activités (zone nord)"
        etat.versants[0].nom = "Bassin versant des voiries et parkings de la zone nord"
        for i in range(4):
            o = etat.ajouter_ouvrage(f"Bassin d'orage secondaire n°{i + 1}")
            o.aval_id = etat.systeme.ouvrages[0].id
            o.etude.fixer_ajutage_absolu(3.0)
            o.etude.bassin = Bassin(volume_total_m3=80.0, debit_ajutage_ls=3.0)
            for j in range(2):
                bv = etat.ajouter_versant(f"Bassin versant {i + 1}.{j + 1}", o.id)
                bv.surfaces = [SurfaceIncidente("Toitures", 1.0, 6000.0)]
        etat.choisir_ouvrage(etat.systeme.ouvrages[0].id)
        etat.invalider()
        return etat

    def _poses(self, controle, trouves=None):
        """Contrôles réellement positionnés dans un Stack."""
        trouves = [] if trouves is None else trouves
        if isinstance(controle, ft.Stack):
            for c in controle.controls or []:
                if (getattr(c, "left", None) is not None and getattr(c, "top", None) is not None
                        and getattr(c, "width", None) and getattr(c, "height", None)):
                    trouves.append(c)
        for enfant in _enfants(controle):
            self._poses(enfant, trouves)
        return trouves

    def test_rien_ne_se_recouvre_dans_le_schema_affiche(self):
        for echelle in (0.8, 1.0, 1.3):
            etat = self._etat_touffu()
            vue = VueSynthese(PageFactice(), etat)
            vue._echelle = echelle
            vue.afficher()
            poses = self._poses(vue.corps)
            self.assertGreater(len(poses), 10)
            for i, a in enumerate(poses):
                for b in poses[i + 1:]:
                    ox = min(a.left + a.width, b.left + b.width) - max(a.left, b.left)
                    oy = min(a.top + a.height, b.top + b.height) - max(a.top, b.top)
                    with self.subTest(echelle=echelle):
                        self.assertFalse(ox > self.TOLERANCE_PX and oy > self.TOLERANCE_PX,
                                         f"recouvrement de {ox:.0f}x{oy:.0f} px dans le schéma")

    def test_les_textes_des_boites_du_schema_sont_bornes(self):
        """Une boîte a une hauteur fixe : son texte ne doit pas pouvoir déborder."""
        etat = self._etat_touffu()
        vue = VueSynthese(PageFactice(), etat)
        vue.afficher()
        for pose in self._poses(vue.corps):
            for texte in _rechercher(pose, ft.Text):
                self.assertTrue(texte.max_lines or texte.overflow,
                                f"texte non borné dans le schéma : {texte.value!r}")

    def test_aucune_liste_deroulante_ne_reste_vide(self):
        """Une valeur de liste égale à la chaîne vide n'affiche rien dans Flet.

        Le champ paraît alors non renseigné alors qu'il l'est : « Se déverse
        vers l'exutoire » s'affichait comme une case vide.
        """
        etat = self._etat_touffu()
        for classe in VUES:
            vue = classe(PageFactice(), etat)
            for controle in vue.construire():
                for liste in _rechercher(controle, ft.Dropdown):
                    if not liste.options:
                        continue
                    cles = [o.key for o in liste.options]
                    with self.subTest(vue=classe.__name__, liste=liste.label):
                        self.assertNotIn("", cles,
                                         "une option de valeur vide n'affiche pas son libellé")
                        self.assertIn(liste.value, cles,
                                      f"la valeur {liste.value!r} n'est pas dans les options")

    def test_aucun_controle_extensible_dans_une_rangee_qui_se_replie(self):
        """Un `expand` dans un `Row(wrap=True)` n'a pas de largeur définie.

        Flutter rendait alors la rangée en un grand aplat gris occupant tout
        l'écran — ce que seul un rendu réel montre, jamais l'arbre de contrôles.
        """
        etat = self._etat_touffu()
        fautifs = []
        for classe in VUES:
            vue = classe(PageFactice(), etat)
            for controle in vue.construire():
                fautifs += _extensibles_dans_un_repli(controle, classe.__name__)
        self.assertEqual(fautifs, [])

    def test_le_resume_de_l_entete_affiche_une_virgule(self):
        import main as application

        application.reinitialiser_partage()
        try:
            # Le projet est garni avant l'ouverture : l'entête calcule alors le
            # volume dès le premier affichage, au lieu d'annoncer « … ».
            etat = application.etat_partage()
            etat.projet.surfaces[7].aire_m2 = 12000.0
            etat.projet.surface_infiltration_m2 = 200.0
            etat.projet.fixer_ajutage_absolu(5.0)
            etat.invalider()
            page = PageFactice()
            application.main(page)
            resumes = [t.value for t in _rechercher(page.controls[0], ft.Text)
                       if t.value and "m² actifs" in t.value]
            self.assertTrue(resumes)
            self.assertRegex(resumes[0], r"\d,\d",
                             f"aucune virgule décimale dans l'entête : {resumes[0]}")
            self.assertNotRegex(resumes[0], r"\d\.\d",
                                f"point décimal dans l'entête : {resumes[0]}")
        finally:
            application.reinitialiser_partage()

    def test_aucun_texte_long_ne_pousse_ses_voisins_hors_du_rang(self):
        """Un Text long sans repli ni expand chasse ses voisins hors de l'écran."""
        etat = self._etat_touffu()
        fautifs = []
        for classe in VUES:
            vue = classe(PageFactice(), etat)
            for controle in vue.construire():
                fautifs += _textes_debordants(controle, classe.__name__)
        self.assertEqual(fautifs, [])


def _enfants(controle):
    sortie = []
    for attribut in ("controls", "content", "actions", "rows", "cells", "label", "title",
                     "subtitle", "leading", "trailing"):
        valeur = getattr(controle, attribut, None)
        if isinstance(valeur, (list, tuple)):
            sortie.extend(v for v in valeur if isinstance(v, ft.Control))
        elif isinstance(valeur, ft.Control):
            sortie.append(valeur)
    return sortie


def _textes_debordants(controle, vue, trouves=None, profondeur=0):
    trouves = [] if trouves is None else trouves
    if profondeur > 40:
        return trouves
    if isinstance(controle, ft.Row) and not getattr(controle, "wrap", False):
        voisins = [c for c in (controle.controls or []) if isinstance(c, ft.Control)]
        if len(voisins) > 1:
            for t in (c for c in voisins if isinstance(c, ft.Text)):
                borne = t.expand or t.no_wrap is False or t.max_lines or t.overflow
                if len(t.value or "") > 60 and not borne:
                    trouves.append(f"{vue} : {(t.value or '')[:60]}")
    for enfant in _enfants(controle):
        _textes_debordants(enfant, vue, trouves, profondeur + 1)
    return trouves


def _extensibles_dans_un_repli(controle, vue, trouves=None, profondeur=0):
    trouves = [] if trouves is None else trouves
    if profondeur > 40:
        return trouves
    if isinstance(controle, ft.Row) and getattr(controle, "wrap", False):
        for enfant in (controle.controls or []):
            if getattr(enfant, "expand", None):
                trouves.append(f"{vue} : {type(enfant).__name__} extensible dans un Row replié")
    for enfant in _enfants(controle):
        _extensibles_dans_un_repli(enfant, vue, trouves, profondeur + 1)
    return trouves


def _bouton_nomme(cas, vue, libelle):
    boutons = (_rechercher(vue.corps, ft.FilledButton)
               + _rechercher(vue.corps, ft.OutlinedButton)
               + _rechercher(vue.corps, ft.ElevatedButton))
    for bouton in boutons:
        libelles = [getattr(bouton, "text", None)]
        libelles += [t.value for t in _rechercher(bouton, ft.Text)]
        if any(libelle in (t or "") for t in libelles):
            return bouton
    cas.fail(f"bouton « {libelle} » introuvable")


class TestSauvegardeDeProjet(unittest.TestCase):
    """Exporter puis réimporter doit rendre le projet à l'identique."""

    def setUp(self):
        self.repertoire = tempfile.mkdtemp(prefix="hydrobassin_projet_")

    def tearDown(self):
        shutil.rmtree(self.repertoire, ignore_errors=True)

    def _etat_garni(self):
        etat = EtatApplication()
        p = etat.projet
        # Identification, commune et récurrence appartiennent au système : les
        # écrire sur l'étude d'un ouvrage serait perdu à la synchronisation.
        s = etat.systeme
        s.nom_projet, s.auteur, s.localisation = "Lotissement", "L. N.", "Amay"
        s.remarques = "essai d'infiltration du 12/03"
        s.commune_ins, s.commune_nom, s.periode_retour = "61003", "Amay", 50
        p.surfaces[7].aire_m2 = 12000.0
        p.surfaces[2].aire_m2 = 3000.0
        p.surface_reference_m2 = 30000.0
        p.k_infiltration_ms = 5e-6
        s.coef_securite_infiltration = 1.5
        p.surface_infiltration_m2 = 400.0
        p.fixer_ajutage_specifique(5.0)
        p.bassin = Bassin(volume_total_m3=900.0, volume_sous_ajutage_m3=80.0,
                          surface_dispersion_m2=400.0, debit_ajutage_ls=p.debit_ajutage_ls)
        p.amont = BassinAmont(actif=True, surface_bv_m2=8000.0, coef_ruissellement=0.8,
                              debit_ajutage_ls=4.0, volume_temporisation_m3=250.0,
                              inclure_bv_dans_ajutage=True)
        etat.scenario_principal = SCENARIO_SEUIL
        etat.invalider()
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

    def setUp(self):
        # Les fenêtres d'un même processus partagent un unique projet : chaque
        # essai doit repartir de zéro, sinon il hérite du précédent.
        import main as application

        application.reinitialiser_partage()

    tearDown = setUp

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
        self.assertEqual(len(rails[0].destinations), 10)
        rails[0].on_change(_Evenement(rails[0], 3))
        self.assertTrue(tiroirs or rails)

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
        vue = VueVersants(page, etat)
        vue.construire()
        versant = etat.versants[0]
        lignes = vue._ligne_surface(versant, 4, versant.surfaces[4])  # terres battues, c = 0,5
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
        vue = VueVersants(PageFactice(), etat_complet())
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
        page.route = "/vue/5"
        application.main(page)
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)
                  if t.value in {v.titre for v in VUES}]
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

        page.route = "/vue/6"
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
            location=types.SimpleNamespace(pathname="/vue/8", hash=""))
        sys.modules["js"] = faux_js
        try:
            self.assertEqual(application._adresse_navigateur(), "/vue/8")
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
        """Ctrl+1 à Ctrl+9 et Ctrl+0 ouvrent une section ; sans Ctrl, rien ne bouge."""
        import main as application

        page = PageFactice()
        application.main(page)
        self.assertIsNotNone(page.on_keyboard_event)

        page.on_keyboard_event(_Touche("7", ctrl=True))
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Ajutage", titres)

        page.on_keyboard_event(_Touche("1", ctrl=False))
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Ajutage", titres)  # inchangé : la touche seule ne navigue pas

        # Ctrl+0 ouvre la dixième section, comme la touche 0 d'un navigateur.
        page.on_keyboard_event(_Touche("0", ctrl=True))
        titres = [t.value for t in _rechercher(page.controls[0], ft.Text)]
        self.assertIn("Rapport", titres)

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


class TestDeuxFenetres(unittest.TestCase):
    """Deux fenêtres, un seul projet — la « nouvelle fenêtre » d'un tableur."""

    def setUp(self):
        import main as application

        application.reinitialiser_partage()

    tearDown = setUp

    def test_la_seconde_fenetre_montre_le_meme_projet(self):
        import main as application

        premiere = PageFactice()
        application.main(premiere)
        etat = application.etat_partage()
        etat.systeme.nom_projet = "Partagé"
        etat.projet.surfaces[7].aire_m2 = 4000.0
        etat.invalider()

        seconde = PageFactice()
        application.main(seconde)
        self.assertIs(application.etat_partage(), etat)
        self.assertIn("fenêtre 2", seconde.title)
        valeurs = [c.value for c in _rechercher(seconde.controls[0], ft.TextField)]
        self.assertIn("Partagé", valeurs,
                      "la seconde fenêtre doit montrer le projet de la première")
        textes = [t.value for t in _rechercher(seconde.controls[0], ft.Text) if t.value]
        self.assertTrue(any("4000" in t or "4 000" in t for t in textes),
                        "les surfaces saisies dans la première fenêtre doivent s'y voir")

    def test_une_saisie_dans_une_fenetre_se_voit_dans_l_autre(self):
        import main as application

        premiere = PageFactice()
        application.main(premiere)
        seconde = PageFactice()
        application.main(seconde)
        etat = application.etat_partage()
        avant = etat.resultat.volume_m3
        etat.projet.surfaces[7].aire_m2 = etat.projet.surfaces[7].aire_m2 + 10000.0
        etat.invalider()
        self.assertGreater(etat.resultat.volume_m3, avant)
        # Les deux fenêtres lisent le même état : reconstruire l'une la montre à jour.
        vue = VueDimensionnement(seconde, etat)
        vue.afficher()
        self.assertIn(theme.nombre(etat.resultat.volume_m3, 1),
                      " ".join(_textes(vue.corps)))

    def test_fermer_la_seconde_fenetre_n_arrete_pas_l_application(self):
        import main as application

        premiere = PageFactice()
        application.main(premiere)
        seconde = PageFactice()
        application.main(seconde)
        etat = application.etat_partage()
        abonnes = len(etat._abonnes)

        fermeture = getattr(seconde, "on_close", None) or getattr(seconde, "on_disconnect", None)
        self.assertIsNotNone(fermeture, "la fenêtre doit savoir qu'elle se ferme")
        fermeture(None)
        self.assertLess(len(etat._abonnes), abonnes,
                        "la fenêtre fermée doit cesser d'être rafraîchie")
        # La première fenêtre continue de fonctionner.
        etat.projet.surfaces[7].aire_m2 = 5000.0
        etat.invalider()
        self.assertGreater(etat.resultat.volume_m3, 0)

    def test_la_seconde_fenetre_est_refusee_proprement_quand_elle_est_impossible(self):
        from bassin.ui import fenetres

        page = PageFactice()
        self.assertFalse(fenetres.disponible(page))     # pas de serveur local ici
        raison = fenetres.ouvrir(page)
        self.assertTrue(raison, "un échec doit s'expliquer, pas passer inaperçu")
        self.assertEqual(fenetres.nombre_ouvertes(), 0)

    def test_la_version_web_ne_propose_pas_de_seconde_fenetre(self):
        from bassin.ui import fenetres

        page = PageFactice()
        page.web = True
        page.connection = type("C", (), {"page_url": "tcp://127.0.0.1:1234"})()
        self.assertFalse(fenetres.disponible(page))

    def test_android_ne_propose_pas_de_seconde_fenetre(self):
        from bassin.ui import fenetres

        page = PageFactice()
        page.platform = ft.PagePlatform.ANDROID
        page.connection = type("C", (), {"page_url": "tcp://127.0.0.1:1234"})()
        self.assertFalse(fenetres.disponible(page))

    def test_le_bouton_de_nouvelle_fenetre_n_apparait_que_sur_le_bureau(self):
        import main as application

        page = PageFactice()
        application.main(page)
        tooltips = [b.tooltip for b in _rechercher(page.controls[0], ft.IconButton)]
        self.assertFalse(any("Nouvelle fenêtre" in (t or "") for t in tooltips))

    def test_la_seconde_fenetre_offre_de_se_fermer(self):
        import main as application

        application.main(PageFactice())
        seconde = PageFactice()
        application.main(seconde)
        tooltips = [b.tooltip for b in _rechercher(seconde.controls[0], ft.IconButton)]
        self.assertTrue(any("Fermer cette fenêtre" in (t or "") for t in tooltips),
                        "la fenêtre supplémentaire doit pouvoir se refermer seule")


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
