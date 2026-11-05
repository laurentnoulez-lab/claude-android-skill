"""Ce que l'application fait devant une saisie absurde.

Un projet peut arriver avec n'importe quoi dedans : un chiffre tapé de travers,
deux valeurs interverties, un fichier retouché à la main, un enregistrement
d'une version à venir. Aucune de ces saisies ne doit **faire tomber**
l'application, aucune ne doit **passer en silence**, et aucune ne doit produire
un affichage qui n'est pas du français.

Traiter les cas un par un, au fil des signalements, ne suffit pas : il en reste
toujours un. Ce module balaie donc un éventail de valeurs pathologiques sur
**toutes** les grandeurs encodées, et vérifie les invariants qui doivent tenir
quelle que soit l'entrée. Le dernier test est le garde-fou du garde-fou : il
refuse qu'une grandeur du modèle échappe à la table des domaines.
"""

import math
import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core import rainfall, reseau as mod_reseau  # noqa: E402
from bassin.core.model import (  # noqa: E402
    Bassin, BassinAmont, DOMAINES, Projet, SurfaceIncidente,
    SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL, SCENARIO_TEMPORISATION,
)
from bassin.reports import dossier as mod_dossier  # noqa: E402

#: Un nombre non fini affiché tel quel : « inf h », « nan m³ ».
NON_FINI = re.compile(r"\b(nan|[-+]?inf)\b", re.IGNORECASE)


def systeme_sain():
    ouvrage = mod_reseau.Ouvrage(id="a", nom="Ouvrage", scenario=SCENARIO_MIXTE)
    etude = ouvrage.etude
    etude.k_infiltration_ms = 1e-5
    etude.surface_infiltration_m2 = 150.0
    etude.debit_ajutage_ls = 2.0
    etude.bassin = Bassin(volume_total_m3=300.0, surface_dispersion_m2=150.0,
                          debit_ajutage_ls=2.0)
    systeme = mod_reseau.Systeme(
        commune_ins="63013", commune_nom="Bütgenbach", periode_retour=25,
        source_pluie=rainfall.SOURCE_MONTANA, coef_securite_infiltration=2.0,
        temps_vidange_max_h=48.0, nom_projet="Robustesse")
    systeme.ouvrages = [ouvrage]
    systeme.bassins_versants = [mod_reseau.BassinVersant(
        id="v", nom="Versant", bassin_id="a", surface_reference_m2=2500.0,
        surfaces=[SurfaceIncidente("Toitures", 1.0, 2000.0),
                  SurfaceIncidente("Prairies", 0.15, 500.0)])]
    systeme.ouvrage_courant = "a"
    systeme.synchroniser()
    return systeme


#: (chemin d'accès depuis le système, valeurs pathologiques)
CHEMINS = (
    (("ouvrages", 0, "etude", "bassin", "volume_total_m3"), (-50.0, float("inf"))),
    (("ouvrages", 0, "etude", "bassin", "volume_sous_ajutage_m3"), (-10.0, 400.0)),
    (("ouvrages", 0, "etude", "bassin", "surface_dispersion_m2"), (-150.0,)),
    (("ouvrages", 0, "etude", "bassin", "debit_ajutage_ls"), (-2.0,)),
    (("ouvrages", 0, "etude", "bassin", "k_infiltration_ms"), (-1e-5, 1.0)),
    (("ouvrages", 0, "etude", "k_infiltration_ms"), (-1e-5, 1.0)),
    (("ouvrages", 0, "etude", "surface_infiltration_m2"), (-150.0,)),
    (("ouvrages", 0, "etude", "debit_ajutage_ls"), (-2.0,)),
    (("ouvrages", 0, "etude", "debit_ajutage_specifique_ls_ha"), (-3.0,)),
    (("ouvrages", 0, "etude", "hauteur_charge_m"), (0.0, -1.0)),
    (("ouvrages", 0, "etude", "coef_debit_orifice"), (0.0, 1.4)),
    (("coef_securite_infiltration",), (0.0, -2.0)),
    (("temps_vidange_max_h",), (0.0, -48.0)),
    (("bassins_versants", 0, "surface_reference_m2"), (-2500.0,)),
    (("bassins_versants", 0, "surfaces", 0, "aire_m2"), (-2000.0,)),
    (("bassins_versants", 0, "surfaces", 0, "coefficient"), (-1.0, 5.0)),
)

#: Mots par lesquels l'application dit qu'une saisie ne va pas.
AVEUX = ("ne peut pas", "doit être", "ne peut jamais", "absente des données",
         "absente du GTI", "inconnue", "au-dessus du trop-plein",
         "ne se vidange pas", "valeur non numérique", "Encodez", "encodez")


def poser(systeme, chemin, valeur):
    cible = systeme
    for pas in chemin[:-1]:
        cible = cible[pas] if isinstance(pas, int) else getattr(cible, pas)
    setattr(cible, chemin[-1], valeur)


def cas_absurdes():
    for chemin, valeurs in CHEMINS:
        for valeur in valeurs:
            libelle = ".".join(str(p) for p in chemin)
            systeme = systeme_sain()
            poser(systeme, chemin, valeur)
            yield f"{libelle} = {valeur!r}", systeme


def textes_affiches(systeme):
    """Tout ce que les dix onglets écrivent pour ce projet."""
    import flet as ft
    from test_ui import PageFactice, VUES
    from bassin.ui.state import EtatApplication

    etat = EtatApplication()
    etat.systeme = systeme
    etat.invalider()
    sortie = []
    for classe in VUES:
        vus = set()

        def parcourir(controle):
            if id(controle) in vus:
                return
            vus.add(id(controle))
            if isinstance(controle, ft.Text) and controle.value:
                sortie.append((classe.__name__, controle.value))
            for attribut in ("content", "controls", "actions", "tabs", "rows", "cells",
                             "title", "label", "subtitle"):
                valeur = getattr(controle, attribut, None)
                if valeur is None:
                    continue
                for enfant in (valeur if isinstance(valeur, (list, tuple)) else [valeur]):
                    if hasattr(enfant, "__dict__"):
                        parcourir(enfant)

        for controle in classe(PageFactice(), etat).construire():
            parcourir(controle)
    return sortie


class TestSaisiesAbsurdes(unittest.TestCase):

    def test_aucune_saisie_absurde_ne_fait_tomber_l_application(self):
        """Moteur, onglets et livrables : rien ne lève d'exception."""
        from bassin.reports import docx_report, pdf_report, xlsx_report

        repertoire = tempfile.mkdtemp(prefix="hydrobassin_robustesse_")
        try:
            for libelle, systeme in cas_absurdes():
                with self.subTest(saisie=libelle):
                    mod_reseau.dimensionner(systeme, avec_minima=False)
                    mod_reseau.simuler_evenement_critique(systeme)
                    dossier = mod_dossier.construire(systeme.courant.etude, systeme=systeme)
                    for ecrivain, ext in ((pdf_report, "pdf"), (docx_report, "docx"),
                                          (xlsx_report, "xlsx")):
                        ecrivain.ecrire(dossier, os.path.join(repertoire, f"essai.{ext}"))
                    textes_affiches(systeme)
        finally:
            import shutil
            shutil.rmtree(repertoire, ignore_errors=True)

    def test_aucune_saisie_absurde_ne_passe_en_silence(self):
        """Une grandeur hors de son domaine physique est toujours dite."""
        for libelle, systeme in cas_absurdes():
            dits = [alerte
                    for fiche in mod_reseau.dimensionner(systeme, avec_minima=False)
                    for alerte in fiche.resultat.alertes]
            dits += systeme.anomalies()
            with self.subTest(saisie=libelle):
                self.assertTrue(any(aveu in dit for dit in dits for aveu in AVEUX),
                                f"aucune alerte ne signale {libelle} ; alertes = {dits}")

    def test_aucun_nombre_non_fini_ne_s_affiche(self):
        """« inf h » n'est pas une durée, « nan m³ » n'est pas un volume."""
        for libelle, systeme in cas_absurdes():
            for vue, texte in textes_affiches(systeme):
                if NON_FINI.search(texte):
                    self.fail(f"{libelle} — {vue} affiche « {texte[:90]} »")

    def test_les_resultats_restent_des_nombres(self):
        """Aucun résultat du moteur ne vaut NaN : ce serait un calcul perdu."""
        for libelle, systeme in cas_absurdes():
            for fiche in mod_reseau.dimensionner(systeme, avec_minima=False):
                res = fiche.resultat
                for champ in ("volume_m3", "duree_critique_min", "hauteur_pluie_mm",
                              "debit_sortant_ls", "temps_vidange_h"):
                    valeur = getattr(res, champ)
                    with self.subTest(saisie=libelle, champ=champ):
                        self.assertFalse(isinstance(valeur, float) and valeur != valeur,
                                         f"{champ} vaut NaN")


class TestPluieHorsDonnees(unittest.TestCase):
    """Un fichier peut nommer une commune ou une récurrence que le GTI ignore."""

    def test_la_substitution_est_faite_et_annoncee(self):
        for champ, valeur, attendu, mot in (
                ("commune_ins", "00000", "63013", "absente des données du GTI"),
                ("periode_retour", 1000, 200, "absente du GTI"),
                ("periode_retour", 33, 30, "absente du GTI"),
                ("source_pluie", "grêle", rainfall.SOURCE_MONTANA, "inconnue")):
            with self.subTest(champ=champ, valeur=valeur):
                systeme = systeme_sain()
                setattr(systeme, champ, valeur)
                anomalies = systeme.anomalies()
                self.assertEqual(getattr(systeme, champ), attendu)
                self.assertTrue(any(mot in a for a in anomalies),
                                f"substitution muette : {anomalies}")
                # Et le calcul aboutit, au lieu de s'arrêter sur une exception.
                fiches = mod_reseau.dimensionner(systeme, avec_minima=False)
                self.assertGreater(fiches[0].volume_minimal_m3, 0.0)

    def test_le_message_disparait_quand_l_utilisateur_choisit_lui_meme(self):
        systeme = systeme_sain()
        systeme.periode_retour = 1000
        self.assertTrue(systeme.anomalies())
        systeme.periode_retour = 50
        self.assertEqual(systeme.anomalies(), [])

    def test_le_moteur_appele_directement_dit_ce_qui_manque(self):
        with self.assertRaises(rainfall.PluieIndisponible):
            rainfall.SourcePluie("00000", 25)
        with self.assertRaises(rainfall.PluieIndisponible):
            rainfall.SourcePluie("63013", 1000)


#: Fichiers de projet abîmés : (libellé, façon de l'abîmer).
def fichiers_abimes():
    def poser(chemin, valeur):
        def abimer(data):
            cible = data
            for pas in chemin[:-1]:
                cible = cible[pas]
            cible[chemin[-1]] = valeur
        return abimer

    yield "volume = texte", poser(("systeme", "ouvrages", 0, "etude", "bassin",
                                   "volume_total_m3"), "beaucoup")
    yield "volume = null", poser(("systeme", "ouvrages", 0, "etude", "bassin",
                                  "volume_total_m3"), None)
    yield "volume = liste", poser(("systeme", "ouvrages", 0, "etude", "bassin",
                                   "volume_total_m3"), [1, 2])
    yield "coefficient = texte", poser(("systeme", "bassins_versants", 0, "surfaces", 0,
                                        "coefficient"), "un")
    yield "nom d'ouvrage = nombre", poser(("systeme", "ouvrages", 0, "nom"), 12345)
    yield "aval inconnu", poser(("systeme", "ouvrages", 0, "aval_id"), "fantome")
    yield "ouvrage aval de lui-même", poser(("systeme", "ouvrages", 0, "aval_id"), "bo1")
    yield "versant raccordé à rien", poser(("systeme", "bassins_versants", 0,
                                            "bassin_id"), "nulle-part")
    yield "scénario inconnu", poser(("systeme", "ouvrages", 0, "scenario"), "magique")
    yield "ouvrage courant inconnu", poser(("systeme", "ouvrage_courant"), "fantome")
    yield "champ inattendu", poser(("systeme", "chose_inconnue"), 42)
    yield "aucun ouvrage", poser(("systeme", "ouvrages"), [])

    def boucle(data):
        data["systeme"]["ouvrages"][0]["aval_id"] = "bo2"
        data["systeme"]["ouvrages"][1]["aval_id"] = "bo1"
    yield "raccordement circulaire", boucle

    def memes_identifiants(data):
        data["systeme"]["ouvrages"][1]["id"] = data["systeme"]["ouvrages"][0]["id"]
    yield "deux ouvrages, un identifiant", memes_identifiants

    def sans_bassin(data):
        data["systeme"]["ouvrages"][0]["etude"].pop("bassin")
    yield "ouvrage sans bassin encodé", sans_bassin

    def sans_etude(data):
        data["systeme"]["ouvrages"][0].pop("etude")
    yield "ouvrage sans étude", sans_etude


def projet_sur_disque():
    """Le système sain, tel qu'il s'enregistre."""
    import json

    from bassin.ui.state import EtatApplication

    etat = EtatApplication()
    etat.systeme = systeme_sain()
    etat.systeme.ouvrages.append(mod_reseau.Ouvrage(id="bo2", nom="Aval", aval_id=""))
    etat.systeme.ouvrages[0].id = "bo1"
    etat.systeme.ouvrages[0].aval_id = "bo2"
    etat.systeme.bassins_versants[0].bassin_id = "bo1"
    etat.systeme.ouvrage_courant = "bo1"
    etat.systeme.synchroniser()
    return json.loads(etat.to_json())


class TestFichierAbime(unittest.TestCase):
    """Un fichier de projet retouché, tronqué ou écrit par une autre version.

    Rien n'oblige un fichier ``.json`` à rester cohérent entre deux sessions :
    il s'édite, il se copie, il se génère. Chacun de ces cas doit **soit** se
    charger en disant ce qui cloche, **soit** être refusé par un message clair.
    Une exception nue, elle, laisse l'utilisateur devant un écran vide.
    """

    def test_aucun_fichier_abime_ne_fait_tomber_l_application(self):
        import copy
        import json

        from bassin.reports import docx_report, pdf_report, xlsx_report
        from bassin.ui.state import EtatApplication

        sain = projet_sur_disque()
        repertoire = tempfile.mkdtemp(prefix="hydrobassin_fichier_")
        try:
            for libelle, abimer in fichiers_abimes():
                with self.subTest(fichier=libelle):
                    data = copy.deepcopy(sain)
                    abimer(data)
                    etat = EtatApplication()
                    try:
                        etat.importer_texte(json.dumps(data))
                    except ValueError:
                        continue          # refus net, avec un message : c'est correct
                    systeme = etat.systeme
                    systeme.anomalies()
                    mod_reseau.dimensionner(systeme, avec_minima=False)
                    mod_reseau.simuler_evenement_critique(systeme)
                    dossier = mod_dossier.construire(systeme.courant.etude, systeme=systeme)
                    for ecrivain, ext in ((pdf_report, "pdf"), (docx_report, "docx"),
                                          (xlsx_report, "xlsx")):
                        ecrivain.ecrire(dossier, os.path.join(repertoire, f"essai.{ext}"))
                    textes_affiches(systeme)
        finally:
            import shutil
            shutil.rmtree(repertoire, ignore_errors=True)

    def test_un_reseau_abime_dit_ce_qui_cloche(self):
        """Les défauts de construction se nomment, ils ne se devinent pas."""
        import copy
        import json

        from bassin.ui.state import EtatApplication

        sain = projet_sur_disque()
        attendus = {
            "aval inconnu": "se déverse dans un bassin qui n'existe plus",
            "ouvrage aval de lui-même": "se déverse dans lui-même",
            "versant raccordé à rien": "n'est raccordé à aucun bassin d'orage",
            "raccordement circulaire": "Raccordement circulaire",
            "deux ouvrages, un identifiant": "portent l'identifiant",
            "scénario inconnu": "inconnu, le scénario mixte a été retenu",
            "volume = null": "valeur non numérique",
            "volume = texte": "valeur non numérique",
            "coefficient = texte": "valeur non numérique",
        }
        for libelle, abimer in fichiers_abimes():
            if libelle not in attendus:
                continue
            with self.subTest(fichier=libelle):
                data = copy.deepcopy(sain)
                abimer(data)
                etat = EtatApplication()
                etat.importer_texte(json.dumps(data))
                anomalies = etat.systeme.anomalies()
                self.assertTrue(any(attendus[libelle] in a for a in anomalies),
                                f"défaut passé sous silence ; anomalies = {anomalies}")


class TestTraceDefensif(unittest.TestCase):
    """Le tracé est la dernière barrière : il ne tombe pas, même mal nourri.

    Les valeurs non numériques sont écartées à l'entrée du projet, si bien que
    les graphiques n'en voient plus. Cette barrière-là n'en reste pas moins
    nécessaire : un graphique se construit aussi à partir de grandeurs
    *calculées* — un temps de vidange infini, un rapport dont le dénominateur
    s'annule —, que rien n'assainit. Sans elle, tout le dossier tombait sur un
    ``ValueError`` levé au moment de convertir la coordonnée en pixel.
    """

    def test_une_coordonnee_non_finie_ne_se_dessine_pas(self):
        from bassin.reports import charts

        for valeur in (float("inf"), float("-inf"), float("nan")):
            with self.subTest(valeur=valeur):
                canevas = charts.Canevas(40, 30, charts.BLANC)
                canevas.ligne(0, 0, 39, valeur, charts.NOIR)
                canevas.ligne(valeur, valeur, valeur, valeur, charts.NOIR, 3)
                canevas.rectangle(0, 0, valeur, 10, charts.NOIR)
                canevas.disque(valeur, 5, 3, charts.NOIR)
                canevas.texte(valeur, 2, "essai", charts.NOIR)
                self.assertTrue(canevas.png())

    def test_un_axe_non_fini_n_a_pas_de_graduation(self):
        from bassin.reports import charts

        self.assertEqual(charts.graduations(0.0, float("inf")), [0.0])
        self.assertEqual(charts.graduations(float("nan"), 10.0), [])


class TestTableDesDomaines(unittest.TestCase):
    """Le garde-fou du garde-fou : aucune grandeur n'échappe à la table."""

    #: Grandeurs calculées par l'application, jamais encodées par l'utilisateur.
    DERIVEES = {"surface_amont_raccordee_m2"}

    def test_toute_grandeur_encodee_a_son_domaine(self):
        for classe in (Projet, Bassin, BassinAmont, SurfaceIncidente,
                       mod_reseau.BassinVersant, mod_reseau.Systeme):
            for nom, champ in classe.__dataclass_fields__.items():
                annotation = str(champ.type)
                if "float" not in annotation or nom in self.DERIVEES:
                    continue
                with self.subTest(classe=classe.__name__, champ=nom):
                    self.assertIn(nom, DOMAINES,
                                  "grandeur encodée sans domaine physique déclaré : "
                                  "ajoutez-la à model.DOMAINES")

    def test_chaque_domaine_refuse_ce_qu_il_doit_refuser(self):
        for nom, domaine in DOMAINES.items():
            with self.subTest(grandeur=nom):
                self.assertIsNotNone(domaine.ecart(float("nan")))
                self.assertIsNotNone(domaine.ecart(float("inf")))
                if domaine.mini is not None:
                    self.assertIsNotNone(domaine.ecart(domaine.mini - 1.0))
                if domaine.maxi is not None:
                    self.assertIsNotNone(domaine.ecart(domaine.maxi + 1.0))
                self.assertTrue(domaine.raison, "un refus sans raison n'aide personne")


if __name__ == "__main__":
    unittest.main()
