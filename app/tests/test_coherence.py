"""Cohérence de l'application sur un éventail de configurations réelles.

Tous les défauts signalés à l'usage relèvent de la même famille : **deux
nombres qui décrivent la même chose, calculés par des chemins différents et
affichés côte à côte sans se réconcilier**. Un bandeau annonçait « 0,0 m³
requis » au-dessus d'une table pleine de volumes ; un ouvrage nourri par
l'amont se voyait refuser sa table QDF ; un chapitre du dossier sortait
amputé sans le dire.

Les tester un par un ne suffit pas — il en restait toujours un autre. Ce
module balaie donc un **éventail de configurations** (bassin seul, réseau en
série, ouvrage sans versant propre, volume mort, sol propre au bassin
construit, source QDF, ouvrage non encodé…) et vérifie sur chacune les
invariants qui doivent tenir partout.
"""

import os
import re
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core import hydro, rainfall, reseau as mod_reseau, simulation  # noqa: E402
from bassin.core.model import (  # noqa: E402
    Bassin, SurfaceIncidente,
    SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL, SCENARIO_TEMPORISATION,
)
from bassin.reports import dossier as mod_dossier  # noqa: E402


def _ouvrage(id_, nom, scenario=SCENARIO_MIXTE, **kw):
    o = mod_reseau.Ouvrage(id=id_, nom=nom, scenario=scenario)
    e = o.etude
    e.k_infiltration_ms = kw.get("k", 1e-5)
    e.surface_infiltration_m2 = kw.get("s_inf", 150.0)
    e.debit_ajutage_ls = kw.get("q_aj", 2.0)
    e.bassin = Bassin(volume_total_m3=kw.get("v", 300.0),
                      volume_sous_ajutage_m3=kw.get("v_sous", 0.0),
                      surface_dispersion_m2=kw.get("s_disp", 150.0),
                      debit_ajutage_ls=kw.get("q_aj_b", kw.get("q_aj", 2.0)),
                      k_infiltration_ms=kw.get("k_bassin"))
    return o


def _versant(id_, nom, bassin_id, imper, enherbe=0.0):
    return mod_reseau.BassinVersant(
        id=id_, nom=nom, bassin_id=bassin_id, surface_reference_m2=imper + enherbe,
        surfaces=[SurfaceIncidente("Toitures, routes", 1.0, imper),
                  SurfaceIncidente("Prairies", 0.15, enherbe)])


def _systeme(ouvrages, versants, **kw):
    s = mod_reseau.Systeme(commune_ins=kw.get("ins", "63013"),
                           commune_nom=kw.get("nom", "Bütgenbach"),
                           periode_retour=kw.get("T", 25),
                           source_pluie=kw.get("source", rainfall.SOURCE_MONTANA),
                           coef_securite_infiltration=2.0, temps_vidange_max_h=48.0,
                           nom_projet="Essai de cohérence")
    s.ouvrages = ouvrages
    s.bassins_versants = versants
    s.ouvrage_courant = ouvrages[0].id
    s.synchroniser()
    return s


def configurations():
    """Les cas qu'un projet réel rencontre, y compris ceux qui ont mordu."""
    cas = [("bassin unique",
            _systeme([_ouvrage("a", "Bassin unique")],
                     [_versant("v", "Versant", "a", 2000.0, 500.0)]))]

    for sc in (SCENARIO_TEMPORISATION, SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL):
        cas.append((f"volume mort sous l'ajutage, scénario {sc}",
                    _systeme([_ouvrage("a", "Bassin unique", scenario=sc, v_sous=20.0)],
                             [_versant("v", "Versant", "a", 2000.0)])))

    def _serie(**kw):
        am = _ouvrage("am", "Amont", v=kw.pop("v_amont", 150.0))
        av = _ouvrage("av", "Aval", v=400.0, s_inf=300.0, s_disp=300.0, q_aj=4.0)
        am.aval_id = "av"
        am.surverse_vers_milieu_naturel = kw.pop("surverse_nature", False)
        return am, av

    am, av = _serie()
    cas.append(("deux ouvrages en série",
                _systeme([am, av], [_versant("v1", "Versant amont", "am", 2000.0),
                                    _versant("v2", "Versant aval", "av", 3000.0)])))
    am, av = _serie()
    cas.append(("aval sans bassin versant propre",
                _systeme([am, av], [_versant("v1", "Versant amont", "am", 2000.0)])))
    am, av = _serie(v_amont=5.0)
    cas.append(("amont sous-dimensionné qui surverse vers l'aval",
                _systeme([am, av], [_versant("v1", "Versant", "am", 5000.0)])))
    am, av = _serie(v_amont=5.0, surverse_nature=True)
    cas.append(("surverse dirigée vers le milieu naturel",
                _systeme([am, av], [_versant("v1", "Versant", "am", 5000.0)])))

    cas.append(("sans infiltration",
                _systeme([_ouvrage("a", "Sans infiltration", s_inf=0.0, s_disp=0.0,
                                   scenario=SCENARIO_TEMPORISATION)],
                         [_versant("v", "Versant", "a", 2000.0)])))
    cas.append(("sans ajutage",
                _systeme([_ouvrage("a", "Sans ajutage", q_aj=0.0, q_aj_b=0.0,
                                   scenario=SCENARIO_DISPERSION)],
                         [_versant("v", "Versant", "a", 2000.0)])))
    cas.append(("sol propre au bassin construit",
                _systeme([_ouvrage("a", "Sol mesuré", k_bassin=2e-6)],
                         [_versant("v", "Versant", "a", 2000.0)])))
    cas.append(("source QDF",
                _systeme([_ouvrage("a", "Bassin QDF")],
                         [_versant("v", "Versant", "a", 2000.0)],
                         ins="61003", nom="Amay", source=rainfall.SOURCE_QDF)))
    cas.append(("ouvrage non encodé",
                _systeme([_ouvrage("a", "Non encodé", v=0.0, s_disp=0.0)],
                         [_versant("v", "Versant", "a", 2000.0)])))
    cas.append(("aucun bassin versant",
                _systeme([_ouvrage("a", "Orphelin")], [])))
    # Géométrie impossible relevée sur un projet réel : le volume mort dépasse
    # le volume tampon total. L'application en donnait deux lectures — l'ajutage
    # débitait dans la simulation de l'ouvrage, pas dans le routage du réseau.
    am, av = _serie()
    av.etude.bassin.volume_total_m3 = 8.1
    av.etude.bassin.volume_sous_ajutage_m3 = 10.0
    cas.append(("ajutage au-dessus du trop-plein",
                _systeme([am, av], [_versant("v1", "Versant amont", "am", 2000.0)])))
    return cas


class TestCoherenceDesConfigurations(unittest.TestCase):

    def test_chaque_livrable_se_produit_et_nomme_ce_qu_il_decrit(self):
        from bassin.reports import docx_report, pdf_report, xlsx_report

        repertoire = tempfile.mkdtemp(prefix="hydrobassin_coherence_")
        try:
            for nom, systeme in configurations():
                dossier = mod_dossier.construire(systeme.courant.etude, systeme=systeme)
                base = re.sub(r"[^a-z0-9]+", "_", nom.lower())
                chemins = {}
                for ecrivain, ext in ((pdf_report, "pdf"), (docx_report, "docx"),
                                      (xlsx_report, "xlsx")):
                    with self.subTest(configuration=nom, format=ext):
                        chemin = os.path.join(repertoire, f"{base}.{ext}")
                        ecrivain.ecrire(dossier, chemin)
                        self.assertGreater(os.path.getsize(chemin), 1000,
                                           "livrable anormalement petit")
                        chemins[ext] = chemin
                texte = _texte_pdf(chemins["pdf"])
                for ouvrage in systeme.ouvrages:
                    with self.subTest(configuration=nom, ouvrage=ouvrage.nom):
                        self.assertIn(ouvrage.nom, texte,
                                      "le dossier ne nomme pas cet ouvrage")
                for versant in systeme.bassins_versants:
                    with self.subTest(configuration=nom, versant=versant.nom):
                        self.assertIn(versant.nom, texte,
                                      "le dossier ne nomme pas ce bassin versant")
        finally:
            import shutil
            shutil.rmtree(repertoire, ignore_errors=True)

    def test_un_ouvrage_qui_recoit_de_l_eau_a_toujours_sa_verification(self):
        """Table QDF et simulation ne dépendent pas d'un versant *propre*."""
        for nom, systeme in configurations():
            for ouvrage in systeme.ouvrages:
                p = ouvrage.etude
                if p.bassin.volume_total_m3 <= 0 or not p.a_un_apport:
                    continue
                with self.subTest(configuration=nom, ouvrage=ouvrage.nom):
                    self.assertIsNotNone(
                        simulation.simuler_evenement_critique(p, p.bassin))
                    table = simulation.table_acceptation(p, p.bassin)
                    self.assertEqual(len(table.durees_min),
                                     len(rainfall.QDF_DURATIONS_MIN))

    def test_deux_chiffres_de_volume_requis_ne_divergent_jamais_en_silence(self):
        """Le défaut signalé : « 0,0 m³ requis » au-dessus d'une table pleine.

        Le bandeau montre le volume du **dimensionnement** (un scénario), la
        table celui de l'**ouvrage encodé**. Ils peuvent légitimement différer,
        mais alors l'application doit le dire.
        """
        for nom, systeme in configurations():
            for fiche in mod_reseau.dimensionner(systeme):
                p = fiche.ouvrage.etude
                if p.bassin.volume_total_m3 <= 0 or not p.a_un_apport:
                    continue
                requis = simulation.table_acceptation(p, p.bassin).volume_requis_max_m3(
                    p.periode_retour)
                bandeau = fiche.volume_minimal_m3
                if abs(requis - bandeau) <= max(bandeau * 0.05, 0.5):
                    continue
                alertes = hydro.dimensionner(p, fiche.ouvrage.scenario).alertes
                with self.subTest(configuration=nom, ouvrage=fiche.nom):
                    self.assertTrue(
                        any("ne correspond pas aux hypothèses" in a for a in alertes),
                        f"bandeau {bandeau:.1f} m³ contre table {requis:.1f} m³, "
                        f"sans que rien ne l'explique")

    def test_ce_qu_un_ouvrage_restitue_est_ce_que_le_reseau_lui_fait_restituer(self):
        """Un ouvrage n'a qu'une sortie, elle ne peut pas avoir deux valeurs.

        La simulation de l'ouvrage et le routage du réseau intègrent la même
        averse par deux chemins. Sur un projet réel, ils divergeaient : la
        simulation rabotait le volume mort à la capacité — posant l'axe de
        l'orifice pile au trop-plein, où l'ajutage se met en service — quand le
        routage ne le rabotait pas et le laissait fermé. Le même ouvrage
        annonçait 21,8 m³ de surverse et en envoyait 37,4 m³ à l'aval.
        """
        for nom, systeme in configurations():
            sim = mod_reseau.simuler_evenement_critique(systeme)
            noeuds = systeme.noeuds()
            for ouvrage, res in sim.resultats:
                if ouvrage.etude.bassin.volume_total_m3 <= 0:
                    # Volume non encodé : les deux conventions diffèrent à
                    # dessein — le balayage y voit une capacité illimitée, le
                    # routage un simple passage. Rien n'est encore construit,
                    # il n'y a pas de sortie à réconcilier.
                    continue
                ajute = sum(b.q_ajutage_ls * (b.t_min - a.t_min) * 60.0 / 1000.0
                            for a, b in zip(res.pas, res.pas[1:]))
                attendu = ajute + (res.volume_debordement_m3
                                   if ouvrage.surverse_vers_aval else 0.0)
                routage = mod_reseau.restitution(noeuds[ouvrage.id], sim.hauteur_mm,
                                                 sim.duree_min).volume_m3
                with self.subTest(configuration=nom, ouvrage=ouvrage.nom):
                    self.assertAlmostEqual(
                        routage, attendu, delta=max(attendu * 0.005, 0.05),
                        msg=f"la simulation restitue {attendu:.2f} m³ et le réseau "
                            f"en route {routage:.2f} m³")

    def test_une_geometrie_impossible_est_annoncee(self):
        """Volume mort supérieur au volume total : l'utilisateur doit le savoir."""
        for nom, systeme in configurations():
            for fiche in mod_reseau.dimensionner(systeme, avec_minima=False):
                bassin = fiche.ouvrage.etude.bassin
                if not bassin.ajutage_au_dessus_du_trop_plein:
                    continue
                with self.subTest(configuration=nom, ouvrage=fiche.nom):
                    self.assertTrue(
                        any("au-dessus du trop-plein" in a for a in fiche.resultat.alertes),
                        "aucune alerte sur un orifice placé au-dessus du trop-plein")
                    self.assertFalse(fiche.resultat.conforme)

    def test_le_classeur_affiche_les_nombres_a_la_francaise(self):
        """Une cellule de texte ne montre jamais un point décimal.

        Les tableaux et les formules portent des nombres, mis en forme par
        Excel ; mais les phrases — alertes du moteur, entête de la simulation —
        sont formatées en Python et arrivaient telles quelles : « 2.50 l/s »
        dans le classeur en face de « 2,50 l/s » à l'écran et au dossier.
        """
        import re

        from bassin.reports import xlsx_report
        import openpyxl

        point = re.compile(r"(?<=\d)\.(?=\d)")
        repertoire = tempfile.mkdtemp(prefix="hydrobassin_virgule_")
        try:
            for nom, systeme in configurations():
                chemin = os.path.join(repertoire, "essai.xlsx")
                xlsx_report.ecrire(
                    mod_dossier.construire(systeme.courant.etude, systeme=systeme), chemin)
                classeur = openpyxl.load_workbook(chemin)
                for feuille in classeur:
                    for ligne in feuille.iter_rows():
                        for cellule in ligne:
                            valeur = cellule.value
                            if not isinstance(valeur, str) or valeur.startswith("="):
                                continue      # une formule s'écrit en syntaxe Excel
                            with self.subTest(configuration=nom, feuille=feuille.title,
                                              cellule=cellule.coordinate):
                                self.assertIsNone(point.search(valeur), valeur[:120])
        finally:
            import shutil
            shutil.rmtree(repertoire, ignore_errors=True)

    def test_la_tuile_de_vidange_dit_la_meme_chose_que_son_tableau(self):
        """Deux « vidanges les plus longues » sur un écran, rien pour les distinguer.

        La tuile portait le maximum des fiches de **dimensionnement** — 12,5 h
        sur le réseau de démonstration — juste au-dessus d'un tableau qui
        affichait 16 h 13 pour les mêmes ouvrages, et d'une alerte qui contrôle
        les 48 h sur cette seconde valeur. Le lecteur y voit une seule grandeur.
        C'est celle de l'ouvrage construit qui fait foi.
        """
        import flet as ft

        from test_ui import PageFactice
        from bassin.core import exemple
        from bassin.ui import theme
        from bassin.ui.state import EtatApplication
        from bassin.ui.vues.synthese import VueSynthese

        systeme = exemple.systeme_demonstration()
        sim = mod_reseau.simuler_evenement_critique(systeme)
        fiches = mod_reseau.dimensionner(systeme)
        dimensionnement = max(f.resultat.temps_vidange_h for f in fiches)
        # Le cas n'a d'intérêt que si les deux valeurs diffèrent réellement.
        self.assertNotAlmostEqual(dimensionnement, sim.temps_vidange_max_h, places=1)

        etat = EtatApplication()
        etat.systeme = systeme
        etat.invalider()
        textes, vus = [], set()

        def parcourir(controle):
            if id(controle) in vus:
                return
            vus.add(id(controle))
            if isinstance(controle, ft.Text) and controle.value:
                textes.append(controle.value)
            for attribut in ("content", "controls", "rows", "cells", "title", "label"):
                valeur = getattr(controle, attribut, None)
                if valeur is None:
                    continue
                for enfant in (valeur if isinstance(valeur, (list, tuple)) else [valeur]):
                    if hasattr(enfant, "__dict__"):
                        parcourir(enfant)

        for controle in VueSynthese(PageFactice(), etat).construire():
            parcourir(controle)
        affiche = textes[textes.index("VIDANGE LA PLUS LONGUE") + 1]
        self.assertEqual(affiche, theme.nombre(sim.temps_vidange_max_h, 1),
                         "la tuile ne montre pas la vidange des ouvrages encodés")
        self.assertNotEqual(affiche, theme.nombre(dimensionnement, 1))

    def test_la_synthese_totalise_bien_ses_ouvrages(self):
        for nom, systeme in configurations():
            sim = mod_reseau.simuler_evenement_critique(systeme)
            if not sim.resultats:
                continue
            with self.subTest(configuration=nom):
                self.assertAlmostEqual(
                    sim.volume_stocke_m3,
                    sum(r.volume_max_m3 for _o, r in sim.resultats), places=6)
                self.assertAlmostEqual(
                    sim.temps_vidange_max_h,
                    max(r.temps_vidange_h for _o, r in sim.resultats), places=6)

    def test_le_dossier_reprend_les_chiffres_du_moteur(self):
        for nom, systeme in configurations():
            fiches = {f.ouvrage.id: f for f in mod_reseau.dimensionner(systeme)}
            dossier = mod_dossier.construire(systeme.courant.etude, systeme=systeme)
            for sous in dossier.par_ouvrage():
                fiche = fiches.get(sous.ouvrage_courant.id)
                if fiche is None:
                    continue
                with self.subTest(configuration=nom, ouvrage=sous.ouvrage_courant.nom):
                    self.assertAlmostEqual(sous.resultat_principal.volume_m3,
                                           fiche.volume_minimal_m3, places=6)


class TestCoherenceDesVues(unittest.TestCase):
    """Chaque onglet s'affiche, sans porte fautive ni nombre mal formé."""

    def setUp(self):
        from test_ui import VUES, PageFactice
        from bassin.ui.state import EtatApplication

        self.VUES, self.PageFactice, self.Etat = VUES, PageFactice, EtatApplication

    def test_chaque_onglet_s_affiche_dans_chaque_configuration(self):
        from test_ui import textes

        for nom, systeme in configurations():
            etat = self.Etat()
            etat.systeme = systeme
            etat.invalider()
            for ouvrage in systeme.ouvrages:
                etat.choisir_ouvrage(ouvrage.id)
                for classe in self.VUES:
                    with self.subTest(configuration=nom, ouvrage=ouvrage.nom,
                                      vue=classe.__name__):
                        controles = classe(self.PageFactice(), etat).construire()
                        lus = [t for c in controles for t in textes(c)]
                        contenu = "\n".join(lus)
                        # La condition se lit sur le MOTEUR, pas sur le
                        # prédicat de l'interface : s'appuyer sur
                        # ``etat.bassin_valide`` reviendrait à tester une
                        # chose par elle-même, et le garde-fou ne mordrait
                        # jamais (vérifié en réintroduisant le défaut).
                        etude = ouvrage.etude
                        if etude.bassin.volume_total_m3 > 0 and etude.a_un_apport:
                            self.assertNotIn(
                                "Encodez d'abord", contenu,
                                "onglet refusé alors que de l'eau arrive à un ouvrage encodé")
                        for texte in lus:
                            self.assertIsNone(
                                re.search(r"\d\.\d", texte),
                                f"point décimal affiché : {texte[:80]}")
                            self.assertIsNone(
                                re.search(r"\b(nan|inf|None)\b", texte),
                                f"valeur non calculée affichée : {texte[:80]}")


def _texte_pdf(chemin):
    from test_reports import texte_pdf

    return texte_pdf(chemin)


if __name__ == "__main__":
    unittest.main()
