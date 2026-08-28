"""Tests des générateurs de rapports (XLSX, DOCX, PDF) et des graphiques."""

import os
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core.model import Bassin, Projet  # noqa: E402
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
        self.assertEqual(len(lignes[0]), 8)

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
