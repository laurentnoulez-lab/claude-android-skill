"""Tests des générateurs de rapports (XLSX, DOCX, PDF) et des graphiques."""

import os
import re
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core import rainfall  # noqa: E402
from bassin.core.model import Bassin, BassinAmont, Projet, SCENARIO_SEUIL  # noqa: E402
from bassin.reports import charts, docx_report, dossier as mod_dossier, pdf_report, xlsx_report  # noqa: E402
from bassin.reports.pdf_writer import Pdf, largeur_texte, nettoyer  # noqa: E402


def projet_complet() -> Projet:
    p = Projet(commune_ins="63013", commune_nom="Bütgenbach", periode_retour=25,
               surfaces=Projet.surfaces_par_defaut(), surface_reference_m2=2000.0,
               nom_projet="Lotissement Les Sources", auteur="Bureau d'études", localisation="Rue du Moulin")
    p.surfaces[7].aire_m2 = 1500.0
    p.surfaces[1].aire_m2 = 500.0
    p.k_infiltration_ms = 1e-5
    p.surface_infiltration_m2 = 120.0
    p.debit_ajutage_ls = 1.0
    p.bassin = Bassin(volume_total_m3=90.0, volume_sous_ajutage_m3=10.0,
                      surface_dispersion_m2=120.0, debit_ajutage_ls=1.0)
    return p


class BaseRapport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dossier = mod_dossier.construire(projet_complet())
        cls.repertoire = tempfile.mkdtemp(prefix="hydrobassin_")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.repertoire, ignore_errors=True)

    def chemin(self, nom: str) -> str:
        return os.path.join(self.repertoire, nom)


class TestDossier(BaseRapport):
    def test_contenu_du_dossier(self):
        d = self.dossier
        self.assertEqual(len(d.resultats), 4)
        self.assertIsNotNone(d.simulation)
        self.assertIsNotNone(d.table)
        self.assertIsNotNone(d.orifice)
        self.assertGreater(d.resultat_principal.volume_m3, 0)

    def test_synthese(self):
        lignes = mod_dossier.synthese_scenarios(self.dossier)
        self.assertEqual(len(lignes), 5)
        self.assertEqual(len(lignes[0]), 9)
        # Toutes les lignes ont la largeur de l'entête, sinon le tableau se décale.
        for ligne in lignes:
            self.assertEqual(len(ligne), len(lignes[0]))

    def test_la_synthese_isole_le_volume_au_dessus_de_l_ajutage(self):
        """Le volume mort est une donnée d'entrée : c'est le reste qu'on creuse."""
        colonne = mod_dossier.synthese_scenarios(self.dossier)[0]
        i = colonne.index("dont au-dessus de l'ajutage [m³]")
        lignes = mod_dossier.synthese_scenarios(self.dossier)[1:]
        for scenario, ligne in zip(mod_dossier.ORDRE_SCENARIOS, lignes):
            res = self.dossier.resultats[scenario]
            if scenario == SCENARIO_SEUIL:
                self.assertEqual(ligne[i], f"{res.volume_au_dessus_ajutage_m3:.1f}")
                self.assertAlmostEqual(
                    res.volume_au_dessus_ajutage_m3 + res.volume_sous_ajutage_m3,
                    res.volume_m3, places=6)
            else:
                self.assertEqual(ligne[i], "—")

    def test_graphiques(self):
        for g in (self.dossier.graphique_dimensionnement(), self.dossier.graphique_simulation(),
                  self.dossier.graphique_debits(), self.dossier.graphique_orifice()):
            self.assertIsNotNone(g)
            self.assertTrue(g.series)
            png = charts.rendre_png(g, 400, 220)
            self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
            largeur, hauteur = struct.unpack(">II", png[16:24])
            self.assertEqual((largeur, hauteur), (400, 220))

    def test_dossier_sans_bassin(self):
        projet = projet_complet()
        projet.bassin = Bassin()
        d = mod_dossier.construire(projet)
        self.assertIsNone(d.simulation)
        self.assertIsNone(d.table)


class TestExcel(BaseRapport):
    def test_generation(self):
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("rapport.xlsx"))
        self.assertTrue(os.path.getsize(chemin) > 10000)
        wb = openpyxl.load_workbook(chemin)
        self.assertEqual(
            wb.sheetnames,
            ["Projet", "Pluie de projet", "Scénarios", "Bassin - table QDF", "Ajutage",
             "Pluies statistiques"],
        )
        noms = set(wb.defined_names)
        for attendu in ("S_ponderee", "Q_infiltration", "Q_ajutage", "V_bassin", "V_sous_ajutage"):
            self.assertIn(attendu, noms)

    def test_le_classeur_contient_des_formules(self):
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("formules.xlsx"))
        wb = openpyxl.load_workbook(chemin)
        ws = wb["Pluie de projet"]
        formules = [c.value for ligne in ws.iter_rows(min_row=9, max_row=12) for c in ligne
                    if isinstance(c.value, str) and c.value.startswith("=")]
        self.assertGreater(len(formules), 10)
        self.assertTrue(any("S_ponderee" in f for f in formules))
        ws = wb["Scénarios"]
        self.assertTrue(str(ws["B5"].value).startswith("=MAX("))

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_TEST_FORMULES"),
                         "évaluation des formules Excel (variable HYDROBASSIN_TEST_FORMULES)")
    def test_les_formules_reproduisent_le_moteur(self):
        """Recalcule le classeur avec la bibliothèque `formulas` et compare au moteur Python."""
        import formulas  # dépendance de test uniquement

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("verif.xlsx"))
        modele = formulas.ExcelModel().loads(chemin).finish()
        solution = modele.calculate()
        base = os.path.basename(chemin).upper()

        def valeur(feuille, cellule):
            cle = f"'[{base}]{feuille}'!{cellule}"
            for k, v in solution.items():
                if k.upper().endswith(cle):
                    try:
                        return float(v.value[0, 0])
                    except Exception:
                        return v
            raise KeyError(cle)

        for colonne, scenario in zip("BCDE", mod_dossier.ORDRE_SCENARIOS):
            attendu = self.dossier.resultats[scenario]
            self.assertAlmostEqual(valeur("SCÉNARIOS", f"{colonne}5"), attendu.volume_m3, places=6)
            self.assertAlmostEqual(valeur("SCÉNARIOS", f"{colonne}6"), attendu.duree_critique_min, places=6)
            self.assertAlmostEqual(valeur("SCÉNARIOS", f"{colonne}12"), attendu.temps_vidange_h, places=6)
        self.assertAlmostEqual(valeur("AJUTAGE", "B10"), self.dossier.orifice.diametre_mm, places=6)


class TestExcelQDF(unittest.TestCase):
    """Avec les tables QDF, le classeur doit balayer les mêmes durées que l'application."""

    @classmethod
    def setUpClass(cls):
        p = projet_complet()
        p.source_pluie = "qdf"
        cls.dossier = mod_dossier.construire(p)
        cls.repertoire = tempfile.mkdtemp(prefix="hydrobassin_qdf_")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.repertoire, ignore_errors=True)

    def test_le_classeur_ne_balaie_que_les_durees_tabulees(self):
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, os.path.join(self.repertoire, "qdf.xlsx"))
        classeur = openpyxl.load_workbook(chemin)
        feuille = classeur["Pluie de projet"]
        durees = [c.value for c in feuille["A"][8:] if isinstance(c.value, (int, float))]
        self.assertEqual(sorted(durees), sorted(float(d) for d in rainfall.QDF_DURATIONS_MIN))

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_TEST_FORMULES"),
                         "évaluation des formules Excel (variable HYDROBASSIN_TEST_FORMULES)")
    def test_les_formules_qdf_reproduisent_le_moteur(self):
        import formulas

        chemin = xlsx_report.ecrire(self.dossier, os.path.join(self.repertoire, "verif_qdf.xlsx"))
        modele = formulas.ExcelModel().loads(chemin).finish()
        solution = modele.calculate()
        base = os.path.basename(chemin).upper()

        def valeur(feuille, cellule):
            cle = f"'[{base}]{feuille}'!{cellule}"
            for k, v in solution.items():
                if k.upper().endswith(cle):
                    try:
                        return float(v.value[0, 0])
                    except Exception:
                        return v
            raise KeyError(cle)

        for colonne, scenario in zip("BCDE", mod_dossier.ORDRE_SCENARIOS):
            attendu = self.dossier.resultats[scenario]
            self.assertAlmostEqual(valeur("SCÉNARIOS", f"{colonne}5"), attendu.volume_m3, places=6)
            self.assertAlmostEqual(valeur("SCÉNARIOS", f"{colonne}6"),
                                   attendu.duree_critique_min, places=6)


class TestWord(BaseRapport):
    def test_generation(self):
        chemin = docx_report.ecrire(self.dossier, self.chemin("rapport.docx"))
        with zipfile.ZipFile(chemin) as z:
            noms = z.namelist()
            self.assertIn("word/document.xml", noms)
            self.assertIn("[Content_Types].xml", noms)
            self.assertGreaterEqual(len([n for n in noms if n.startswith("word/media/")]), 3)
            document = z.read("word/document.xml").decode("utf-8")
        ET.fromstring(document)  # XML bien formé
        self.assertIn("bassin d'orage", document)
        self.assertIn("Bütgenbach", document)

    def test_les_images_sont_referencees(self):
        chemin = docx_report.ecrire(self.dossier, self.chemin("images.docx"))
        with zipfile.ZipFile(chemin) as z:
            rels = z.read("word/_rels/document.xml.rels").decode()
            document = z.read("word/document.xml").decode()
            medias = [n.split("/")[-1] for n in z.namelist() if n.startswith("word/media/")]
        for media in medias:
            self.assertIn(media, rels)
        for i in range(1, len(medias) + 1):
            self.assertIn(f'r:embed="rIdImg{i}"', document)


class TestRapportAvecAmont(unittest.TestCase):
    """Les trois formats documentent le bassin d'orage amont."""

    @classmethod
    def setUpClass(cls):
        p = projet_complet()
        p.amont = BassinAmont(actif=True, surface_bv_m2=8000.0, coef_ruissellement=0.8,
                              debit_ajutage_ls=2.0, surface_dispersion_m2=100.0,
                              k_infiltration_ms=1e-5, volume_temporisation_m3=300.0,
                              inclure_bv_dans_ajutage=True)
        cls.dossier = mod_dossier.construire(p)
        cls.repertoire = tempfile.mkdtemp(prefix="hydrobassin_amont_")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.repertoire, ignore_errors=True)

    def chemin(self, nom: str) -> str:
        return os.path.join(self.repertoire, nom)

    def test_le_bassin_amont_figure_meme_sans_ouvrage_encode(self):
        """C'est une donnée d'entrée : elle ne doit pas dépendre de l'ouvrage aval."""
        projet = projet_complet()
        projet.bassin = Bassin()          # aucun ouvrage encodé
        projet.amont = BassinAmont(actif=True, surface_bv_m2=10000.0,
                                   coef_ruissellement=0.8, debit_ajutage_ls=2.0,
                                   volume_temporisation_m3=120.0)
        sans_ouvrage = mod_dossier.construire(projet)
        self.assertIsNone(sans_ouvrage.simulation)
        chemin = docx_report.ecrire(sans_ouvrage, self.chemin("amont_sans_ouvrage.docx"))
        with zipfile.ZipFile(chemin) as z:
            document = z.read("word/document.xml").decode("utf-8")
        self.assertIn("1.3 Bassin d", document)
        self.assertIn("bassin versant amont", document)
        self.assertIn("sous-dimensionn", document)

    def test_word_decrit_le_bassin_amont(self):
        chemin = docx_report.ecrire(self.dossier, self.chemin("amont.docx"))
        with zipfile.ZipFile(chemin) as z:
            document = z.read("word/document.xml").decode("utf-8")
        ET.fromstring(document)
        self.assertIn("bassin versant amont", document)
        self.assertIn("Volume de temporisation amont", document)

    def test_pdf_decrit_le_bassin_amont(self):
        chemin = pdf_report.ecrire(self.dossier, self.chemin("amont.pdf"))
        self.assertGreater(os.path.getsize(chemin), 5000)
        with open(chemin, "rb") as fh:
            self.assertTrue(fh.read().startswith(b"%PDF"))

    def test_excel_compte_la_surface_amont(self):
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("amont.xlsx"))
        classeur = openpyxl.load_workbook(chemin)
        libelles = [c.value for ligne in classeur["Projet"].iter_rows()
                    for c in ligne if isinstance(c.value, str)]
        self.assertTrue(any("Bassin d'orage amont" in (l or "") for l in libelles))
        self.assertTrue(any("BV amont compris" in (l or "") for l in libelles))


    def test_excel_annonce_le_volume_avec_l_apport_amont(self):
        """Les formules vives ignorent l'amont : le classeur doit le dire et donner
        la valeur qui fait foi, sinon il contredit le rapport PDF."""
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("amont_volume.xlsx"))
        feuille = openpyxl.load_workbook(chemin)["Scénarios"]
        textes = [c.value for ligne in feuille.iter_rows()
                  for c in ligne if isinstance(c.value, str)]
        self.assertTrue(any("intégration pas à pas" in (t or "") for t in textes))
        ligne = next(l for l in feuille.iter_rows()
                     if isinstance(l[0].value, str)
                     and l[0].value.startswith("Volume à maîtriser, apport du bassin amont"))
        valeurs = [c.value for c in ligne[1:5]]
        attendus = [round(self.dossier.resultats[s].volume_m3, 1) for s in mod_dossier.ORDRE_SCENARIOS]
        self.assertEqual(valeurs, attendus)


class TestVirguleDecimale(BaseRapport):

    """Les rapports francophones affichent une virgule décimale, pas un point."""

    #: Les numéros de section (« 1.1 Surfaces incidentes ») gardent leur point.
    TITRE = re.compile(r"^\d+(?:\.\d+)* ")

    def _fautifs(self, textes):
        return [t for t in textes
                if re.search(r"\d\.\d", t) and not self.TITRE.match(t.strip())]

    def test_word(self):
        chemin = docx_report.ecrire(self.dossier, self.chemin("virgules.docx"))
        with zipfile.ZipFile(chemin) as z:
            document = z.read("word/document.xml").decode("utf-8")
        textes = re.findall(r"<w:t[^>]*>(.*?)</w:t>", document, re.S)
        self.assertTrue(any(re.search(r"\d,\d", t) for t in textes))
        self.assertEqual(self._fautifs(textes), [])

    def test_pdf(self):
        chemin = pdf_report.ecrire(self.dossier, self.chemin("virgules.pdf"))
        with open(chemin, "rb") as fh:
            brut = fh.read()
        flux = []
        for bloc in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", brut, re.S):
            try:
                flux.append(zlib.decompress(bloc.group(1)).decode("latin-1"))
            except zlib.error:
                continue
        textes = [t[1:-1] for t in re.findall(r"\((?:[^()\\]|\\.)*\)", "\n".join(flux))]
        self.assertTrue(any(re.search(r"\d,\d", t) for t in textes))
        self.assertEqual(self._fautifs(textes), [])

    def test_graduations_de_graphique(self):
        self.assertEqual(charts.format_nombre(66.3), "66,3")
        self.assertEqual(charts.format_nombre(1200), "1200")
        self.assertIn(",", charts._FONT)  # la police 5x7 sait tracer la virgule


class TestPdf(BaseRapport):
    def test_generation(self):
        chemin = pdf_report.ecrire(self.dossier, self.chemin("rapport.pdf"))
        with open(chemin, "rb") as fh:
            data = fh.read()
        self.assertTrue(data.startswith(b"%PDF-1.4"))
        self.assertTrue(data.rstrip().endswith(b"%%EOF"))
        self.assertGreater(data.count(b"/Type /Page"), 3)
        self.assertIn(b"startxref", data)

    def test_moteur_pdf(self):
        pdf = Pdf()
        pdf.pied = "test"
        pdf.titre("Titre é à ç")
        pdf.texte("Un paragraphe " * 40)
        pdf.tableau([["a", "b"], ["1", "2"]], [200, 200])
        pdf.encadre("Encadré")
        pdf.nouvelle_page()
        pdf.texte("page 2")
        chemin = pdf.enregistrer(self.chemin("moteur.pdf"))
        with open(chemin, "rb") as fh:
            data = fh.read()
        self.assertEqual(data.count(b"/Type /Page "), 2)

    def test_nettoyage_hors_winansi(self):
        self.assertEqual(nettoyer("cœur ≤ 3"), "coeur <= 3")
        self.assertNotIn("œ", nettoyer("œuf"))

    def test_largeur_texte(self):
        self.assertGreater(largeur_texte("MMMM", 10), largeur_texte("iiii", 10))
        self.assertAlmostEqual(largeur_texte("é", 10), largeur_texte("e", 10))


class TestGraphiques(unittest.TestCase):
    def test_graduations(self):
        self.assertEqual(charts.graduations(0, 10, 5), [0, 2, 4, 6, 8, 10])
        self.assertTrue(charts.graduations(0, 1, 4))

    def test_format(self):
        self.assertEqual(charts.format_duree_courte(30), "30min")
        self.assertEqual(charts.format_duree_courte(120), "2h")
        self.assertEqual(charts.format_duree_courte(2880), "2j")
        self.assertEqual(charts.format_nombre(0), "0")

    def test_deux_graduations_ne_portent_jamais_la_meme_etiquette(self):
        """L'axe de temps d'une simulation de réseau se compte en jours."""
        # Arrondies au jour, 1,2 j et 1,7 j s'écrivaient toutes deux « 1j ».
        confuses = [0.0, 720.0, 1440.0, 2160.0, 2880.0]
        etiquettes = charts.etiquettes_de_temps(confuses)
        self.assertEqual(len(set(etiquettes)), len(confuses))
        self.assertIn("1,5j", etiquettes)
        # Un axe déjà lisible garde ses étiquettes entières.
        claires = [0.0, 1440.0, 2880.0, 4320.0]
        self.assertEqual(charts.etiquettes_de_temps(claires),
                         ["0min", "1j", "2j", "3j"])

    def test_un_axe_de_temps_se_reconnait_malgre_l_accent(self):
        """« Durée de pluie » porte un temps autant que « Temps [min] ».

        Le rendu PNG, celui qu'emporte le rapport Word, testait « duree » sans
        accent : il graduait donc cet axe en minutes brutes et superposait
        « 10000 » et « 20000 », là où le PDF écrivait « 17h » et « 1j » sur le
        même graphique.
        """
        for axe in ("Durée de pluie", "Duree de pluie", "Temps [min]", "temps"):
            with self.subTest(axe=axe):
                self.assertTrue(charts.axe_est_temporel(axe))
        for axe in ("Charge [m]", "Volume [m³]", ""):
            with self.subTest(axe=axe):
                self.assertFalse(charts.axe_est_temporel(axe))

    def test_le_png_gradue_la_duree_de_pluie_comme_le_pdf(self):
        """Les deux rapports montrent le même graphique : mêmes graduations."""
        g = charts.Graphique(
            axe_x="Durée de pluie", axe_y="Volume [m³]", x_log=True,
            series=[charts.Serie("V", [(10, 20), (600, 110), (43200, 5)], charts.BLEU)],
        )
        ticks = [10.0, 600.0, 43200.0]
        self.assertEqual(charts.etiquettes_de_temps(ticks), ["10min", "10h", "30j"])
        # Le PNG se contente d'exister ; ce qui compte est la voie prise.
        self.assertTrue(charts.axe_est_temporel(g.axe_x))
        self.assertTrue(charts.rendre_png(g, 600, 300).startswith(b"\x89PNG"))

    def test_les_trois_traceurs_partagent_cet_etiquetage(self):
        """PNG, PDF et écran dessinent le même axe : un seul point de vérité.

        Le fichier de l'écran est relu comme texte : l'importer tirerait Flet
        dans les tests de rapports, qui s'en passent.
        """
        racine = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        fichiers = [
            os.path.join(racine, "src", "bassin", "reports", "charts.py"),
            os.path.join(racine, "src", "bassin", "reports", "pdf_report.py"),
            os.path.join(racine, "src", "bassin", "ui", "graphiques.py"),
        ]
        for chemin in fichiers:
            with self.subTest(fichier=os.path.basename(chemin)):
                with open(chemin, encoding="utf-8") as fh:
                    source = fh.read()
                self.assertIn("etiquettes_de_temps", source)

    def test_png_valide(self):
        g = charts.Graphique(
            axe_x="Durée de pluie", axe_y="V",
            series=[charts.Serie("v", [(10, 1), (100, 5), (1000, 2)], charts.BLEU, aire=True)],
            reperes=[charts.Repere(3, "seuil"), charts.Repere(100, "t", vertical=True)],
            x_log=True,
        )
        png = charts.rendre_png(g, 300, 180)
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
        self.assertIn("v", charts.legende_texte(g))

    def test_graphique_vide(self):
        png = charts.rendre_png(charts.Graphique(), 120, 80)
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")


if __name__ == "__main__":
    unittest.main(verbosity=2)


class TestRapportDeReseau(unittest.TestCase):
    """Les trois formats documentent le réseau, et disent la même chose."""

    @classmethod
    def setUpClass(cls):
        from bassin.core import exemple

        cls.systeme = exemple.systeme_demonstration()
        cls.dossier = mod_dossier.construire(
            cls.systeme.courant.etude, cls.systeme.courant.scenario, systeme=cls.systeme)
        cls.repertoire = tempfile.mkdtemp(prefix="hydrobassin_reseau_")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.repertoire, ignore_errors=True)

    def chemin(self, nom: str) -> str:
        return os.path.join(self.repertoire, nom)

    def _texte_pdf(self, chemin: str) -> str:
        with open(chemin, "rb") as fh:
            brut = fh.read()
        flux = []
        for bloc in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", brut, re.S):
            try:
                flux.append(zlib.decompress(bloc.group(1)).decode("latin-1"))
            except zlib.error:
                continue
        morceaux = re.findall(r"\((?:[^()\\]|\\.)*\)", "\n".join(flux))
        octal = re.compile(r"\\(\d{3})")
        return "\n".join(octal.sub(lambda m: chr(int(m.group(1), 8)), t[1:-1])
                         for t in morceaux)

    # -- le dossier ----------------------------------------------------
    def test_le_dossier_porte_le_reseau(self):
        d = self.dossier
        self.assertTrue(d.reseau_multiple)
        self.assertEqual(len(d.fiches), 2)
        self.assertIsNotNone(d.simulation_systeme)
        self.assertEqual(d.ouvrage_courant.id, self.systeme.ouvrage_courant)

    def test_un_projet_a_un_seul_ouvrage_n_a_pas_de_section_reseau(self):
        simple = mod_dossier.construire(projet_complet())
        self.assertFalse(simple.reseau_multiple)
        texte = self._texte_pdf(pdf_report.ecrire(simple, self.chemin("simple.pdf")))
        self.assertNotIn("Synthèse du réseau", texte)
        # Et la numérotation d'origine est conservée.
        self.assertIn("2. Pluie de projet", texte)

    def test_les_tableaux_de_synthese(self):
        versants = mod_dossier.synthese_versants(self.dossier)
        self.assertEqual(len(versants), len(self.systeme.bassins_versants) + 2)  # entête + total
        self.assertEqual(versants[-1][0], "TOTAL")
        reseau = mod_dossier.synthese_reseau(self.dossier)
        self.assertEqual(len(reseau), len(self.systeme.ouvrages) + 1)
        simulation = mod_dossier.synthese_simulation_systeme(self.dossier)
        self.assertEqual(len(simulation), len(self.systeme.ouvrages) + 1)

    # -- PDF -----------------------------------------------------------
    def test_le_pdf_contient_la_synthese_et_le_schema(self):
        chemin = pdf_report.ecrire(self.dossier, self.chemin("reseau.pdf"))
        texte = self._texte_pdf(chemin)
        self.assertIn("Synthèse du réseau", texte)
        for ouvrage in self.systeme.ouvrages:
            self.assertIn(ouvrage.nom, texte)
        for versant in self.systeme.bassins_versants:
            self.assertIn(versant.nom, texte)
        self.assertIn("Exutoire", texte)
        self.assertIn("Simulation du système complet", texte)

    def test_le_schema_du_pdf_est_celui_de_l_ecran(self):
        """Même géométrie des deux côtés : une seule description du schéma."""
        from bassin.reports import schema as mod_schema

        schema = mod_schema.construire(self.systeme, self.dossier.fiches)
        pdf = Pdf()
        self.assertTrue(pdf_report.dessiner_schema(pdf, schema))
        self.assertGreater(len(schema.boites), len(self.systeme.ouvrages))
        self.assertTrue(all(b.hauteur > 0 for b in schema.boites))

    def test_un_reseau_trop_grand_bascule_sur_l_arbre(self):
        """Plutôt un arbre lisible qu'un schéma illisible."""
        from bassin.core import reseau as coeur
        from bassin.reports import schema as mod_schema

        systeme = coeur.systeme_neuf()
        precedent = systeme.ouvrages[0]
        for i in range(14):
            ouvrage = coeur.ouvrage_neuf(systeme, f"Bassin {i}")
            ouvrage.aval_id = precedent.id
            systeme.ouvrages.append(ouvrage)
            precedent = ouvrage
        systeme.synchroniser()
        schema = mod_schema.construire(systeme)
        pdf = Pdf()
        self.assertFalse(pdf_report.dessiner_schema(pdf, schema))
        lignes = mod_schema.arbre_texte(systeme)
        self.assertGreaterEqual(len(lignes), 15)

    # -- Word ----------------------------------------------------------
    def test_le_word_decrit_le_reseau(self):
        chemin = docx_report.ecrire(self.dossier, self.chemin("reseau.docx"))
        with zipfile.ZipFile(chemin) as z:
            document = z.read("word/document.xml").decode("utf-8")
        ET.fromstring(document)
        self.assertIn("Synth", document)
        self.assertIn("Raccordements", document)
        for ouvrage in self.systeme.ouvrages:
            self.assertIn(ouvrage.nom.replace("'", "'"), document)
        self.assertIn("Simulation du syst", document)

    # -- Excel ---------------------------------------------------------
    def test_le_classeur_a_une_feuille_reseau(self):
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("reseau.xlsx"))
        classeur = openpyxl.load_workbook(chemin)
        self.assertIn("Réseau", classeur.sheetnames)
        feuille = classeur["Réseau"]
        textes = [c.value for ligne in feuille.iter_rows()
                  for c in ligne if isinstance(c.value, str)]
        for ouvrage in self.systeme.ouvrages:
            self.assertTrue(any(ouvrage.nom in t for t in textes), ouvrage.nom)
        # Le classeur dit pourquoi ces valeurs ne sont pas des formules vives.
        self.assertTrue(any("aucune formule de cellule" in t for t in textes))

    def test_le_classeur_ecrit_des_nombres_exploitables(self):
        """Un tableau de synthèse doit rester calculable, pas seulement lisible."""
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("reseau_nombres.xlsx"))
        feuille = openpyxl.load_workbook(chemin)["Réseau"]
        ligne = next(l for l in feuille.iter_rows()
                     if isinstance(l[0].value, str)
                     and l[0].value == self.systeme.ouvrages[0].nom
                     and isinstance(l[5].value, (int, float)))
        fiche = [f for f in self.dossier.fiches
                 if f.ouvrage.id == self.systeme.ouvrages[0].id][0]
        self.assertAlmostEqual(ligne[5].value, round(fiche.volume_minimal_m3, 1), places=6)

    # -- cohérence entre formats ---------------------------------------
    def test_les_trois_formats_annoncent_les_memes_volumes(self):
        import openpyxl

        attendus = [f"{f.volume_minimal_m3:.1f}".replace(".", ",") for f in self.dossier.fiches]
        texte_pdf = self._texte_pdf(pdf_report.ecrire(self.dossier, self.chemin("coherence.pdf")))
        for attendu in attendus:
            self.assertIn(attendu, texte_pdf, f"volume {attendu} absent du PDF")

        chemin = docx_report.ecrire(self.dossier, self.chemin("coherence.docx"))
        with zipfile.ZipFile(chemin) as z:
            document = z.read("word/document.xml").decode("utf-8")
        for attendu in attendus:
            self.assertIn(attendu, document, f"volume {attendu} absent du Word")

        feuille = openpyxl.load_workbook(
            xlsx_report.ecrire(self.dossier, self.chemin("coherence.xlsx")))["Réseau"]
        nombres = [c.value for ligne in feuille.iter_rows()
                   for c in ligne if isinstance(c.value, (int, float))]
        for fiche in self.dossier.fiches:
            self.assertTrue(
                any(abs(n - round(fiche.volume_minimal_m3, 1)) < 1e-6 for n in nombres),
                f"volume de « {fiche.nom} » absent du classeur")

    def test_virgule_decimale_dans_la_section_reseau(self):
        titre = re.compile(r"^\d+(?:\.\d+)* ")
        texte = self._texte_pdf(pdf_report.ecrire(self.dossier, self.chemin("virgule.pdf")))
        fautifs = [t for t in texte.split("\n")
                   if re.search(r"\d\.\d", t) and not titre.match(t.strip())]
        self.assertEqual(fautifs, [])


class TestLargeurDesTableauxWord(unittest.TestCase):
    """Un tableau plus large que la page déborde dans la marge, ou pire.

    La page est un A4 avec 2 cm de marge de chaque côté : il reste 17 cm. Rien
    ne le signale à la génération — le tableau se contente de sortir du cadre à
    l'impression.
    """

    LARGEUR_UTILE_CM = 17.0

    def test_aucun_tableau_ne_deborde_de_la_page(self):
        from bassin.core import exemple
        from bassin.reports.docx_writer import TWIP_PAR_CM

        repertoire = tempfile.mkdtemp(prefix="hydrobassin_largeurs_")
        try:
            systeme = exemple.systeme_demonstration()
            dossier = mod_dossier.construire(systeme.courant.etude,
                                             systeme.courant.scenario, systeme=systeme)
            chemin = docx_report.ecrire(dossier, os.path.join(repertoire, "largeurs.docx"))
            with zipfile.ZipFile(chemin) as z:
                document = z.read("word/document.xml").decode("utf-8")
        finally:
            shutil.rmtree(repertoire, ignore_errors=True)
        grilles = re.findall(r"<w:tblGrid>(.*?)</w:tblGrid>", document, re.S)
        self.assertGreater(len(grilles), 6)
        for i, grille in enumerate(grilles):
            twips = [int(v) for v in re.findall(r'<w:gridCol w:w="(\d+)"/>', grille)]
            largeur = sum(twips) / TWIP_PAR_CM
            with self.subTest(tableau=i):
                self.assertLessEqual(largeur, self.LARGEUR_UTILE_CM,
                                     f"tableau {i} large de {largeur:.1f} cm")


class TestNumerotationDesSections(unittest.TestCase):
    """Les sections se numérotent au fil de l'écriture : la suite doit rester droite.

    La synthèse du réseau s'insère en deuxième position quand le projet compte
    plusieurs ouvrages, et tout ce qui suit se décale. Un numéro sauté ou répété
    rend le dossier incohérent, sans que rien ne le signale.
    """

    TITRE = re.compile(r"^(\d+)\. \S")

    def _numeros_pdf(self, dossier, chemin):
        pdf_report.ecrire(dossier, chemin)
        with open(chemin, "rb") as fh:
            brut = fh.read()
        flux = []
        for bloc in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", brut, re.S):
            try:
                flux.append(zlib.decompress(bloc.group(1)).decode("latin-1"))
            except zlib.error:
                continue
        morceaux = re.findall(r"\((?:[^()\\]|\\.)*\)", "\n".join(flux))
        octal = re.compile(r"\\(\d{3})")
        numeros = []
        for texte in morceaux:
            lisible = octal.sub(lambda m: chr(int(m.group(1), 8)), texte[1:-1])
            trouve = self.TITRE.match(lisible)
            if trouve:
                numeros.append(int(trouve.group(1)))
        return numeros

    def _numeros_word(self, dossier, chemin):
        docx_report.ecrire(dossier, chemin)
        with zipfile.ZipFile(chemin) as z:
            document = z.read("word/document.xml").decode("utf-8")
        numeros = []
        for texte in re.findall(r"<w:t[^>]*>(.*?)</w:t>", document, re.S):
            trouve = self.TITRE.match(texte)
            if trouve:
                numeros.append(int(trouve.group(1)))
        return numeros

    def _verifier(self, numeros, libelle):
        self.assertGreaterEqual(len(numeros), 6, libelle)
        self.assertEqual(numeros, list(range(1, len(numeros) + 1)),
                         f"{libelle} : sections numérotées {numeros}")

    def test_la_suite_est_droite_dans_les_deux_modes(self):
        from bassin.core import exemple

        repertoire = tempfile.mkdtemp(prefix="hydrobassin_numeros_")
        try:
            simple = mod_dossier.construire(projet_complet())
            systeme = exemple.systeme_demonstration()
            reseau = mod_dossier.construire(systeme.courant.etude,
                                            systeme.courant.scenario, systeme=systeme)
            for dossier, libelle in ((simple, "bassin unique"), (reseau, "réseau")):
                with self.subTest(projet=libelle, format="PDF"):
                    self._verifier(self._numeros_pdf(
                        dossier, os.path.join(repertoire, f"{libelle}.pdf")), libelle)
                with self.subTest(projet=libelle, format="Word"):
                    self._verifier(self._numeros_word(
                        dossier, os.path.join(repertoire, f"{libelle}.docx")), libelle)
            # Et le réseau compte bien une section de plus.
            self.assertEqual(
                len(self._numeros_pdf(reseau, os.path.join(repertoire, "r.pdf"))),
                len(self._numeros_pdf(simple, os.path.join(repertoire, "s.pdf"))) + 1)
        finally:
            shutil.rmtree(repertoire, ignore_errors=True)
