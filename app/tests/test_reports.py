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


def texte_pdf(chemin: str) -> str:
    """Texte d'un PDF, échappements octaux WinAnsi décodés.

    Les accents y sont écrits « \\351 » : sans ce décodage, un test qui cherche
    « débordement » ne trouve rien et conclut à tort que le mot manque.
    """
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


def _texte_word(chemin: str) -> str:
    """Texte visible d'un .docx, une exécution de texte par ligne."""
    with zipfile.ZipFile(chemin) as z:
        document = z.read("word/document.xml").decode("utf-8")
    return "\n".join(re.findall(r"<w:t(?:\s[^>]*)?>(.*?)</w:t>", document, re.S))


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


class TestMinimumNulDansLesLivrables(unittest.TestCase):

    """Un minimum nul doit s'expliquer partout, pas seulement à l'écran.

    « 0,0 m² » sous un scénario « infiltration + orifice » se lit « pas
    d'infiltration nécessaire » là où il faut comprendre « l'ajutage seul
    vidange déjà dans le délai ». L'écran le disait ; les livrables non.
    """

    @classmethod
    def setUpClass(cls):
        from bassin.core import exemple

        cls.systeme = exemple.systeme_demonstration()
        cls.systeme.synchroniser()
        cls.dossier = mod_dossier.construire(cls.systeme.courant.etude, systeme=cls.systeme)
        cls.repertoire = tempfile.mkdtemp(prefix="hydrobassin_minima_")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.repertoire, ignore_errors=True)

    def test_la_phrase_dit_pourquoi_le_minimum_est_nul(self):
        phrase = self.dossier.phrase_minimum(0.0, 1, "m²", "Surface d'infiltration minimale",
                                             "l'ajutage")
        self.assertIn("aucun complément", phrase)
        self.assertIn("l'ajutage seul vidange", phrase)
        self.assertNotIn("0,0 m²", phrase)

        # Un minimum réel garde sa valeur.
        phrase = self.dossier.phrase_minimum(0.316, 3, "l/s", "Débit d'ajutage minimal",
                                             "l'infiltration")
        self.assertIn("0.316", phrase.replace(",", "."))

    def test_le_pdf_et_le_word_portent_cette_explication(self):
        chemin = pdf_report.ecrire(self.dossier,
                                   os.path.join(self.repertoire, "minima.pdf"))
        with open(chemin, "rb") as fh:
            brut = fh.read()
        flux = []
        for bloc in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", brut, re.S):
            try:
                flux.append(zlib.decompress(bloc.group(1)).decode("latin-1"))
            except zlib.error:
                continue
        pdf = "\n".join(flux)
        self.assertIn("aucun compl", pdf, "le PDF annonce encore la valeur nue")

        chemin = docx_report.ecrire(self.dossier,
                                    os.path.join(self.repertoire, "minima.docx"))
        with zipfile.ZipFile(chemin) as z:
            document = z.read("word/document.xml").decode("utf-8")
        textes = " ".join(re.findall(r"<w:t[^>]*>(.*?)</w:t>", document, re.S))
        self.assertIn("aucun complément", textes)

    def test_le_pied_de_page_du_pdf_est_accentue(self):
        """« donnees GTI » : la faute que le correctif du millésime avait posée."""
        chemin = pdf_report.ecrire(self.dossier, os.path.join(self.repertoire, "pied.pdf"))
        with open(chemin, "rb") as fh:
            brut = fh.read()
        flux = []
        for bloc in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", brut, re.S):
            try:
                flux.append(zlib.decompress(bloc.group(1)).decode("latin-1"))
            except zlib.error:
                continue
        pdf = "\n".join(flux)
        self.assertNotIn("donnees GTI", pdf)
        self.assertIn("donn\\351es GTI", pdf)


class TestOrthographeDesLivrables(unittest.TestCase):

    """Un participe passé manquant dans un document signé se remarque."""

    #: Relevés à l'audit dans les trois formats.
    FAUTES = ("ruisselle de pointe", "Volume déborde", "V ruisselle",
              "au-dela", "sureleve")

    def test_aucune_faute_relevee_ne_subsiste_dans_les_generateurs(self):
        racine = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        for module in ("pdf_report.py", "docx_report.py", "xlsx_report.py"):
            chemin = os.path.join(racine, "src", "bassin", "reports", module)
            with open(chemin, encoding="utf-8") as fh:
                source = fh.read()
            for faute in self.FAUTES:
                with self.subTest(module=module, faute=faute):
                    self.assertNotIn(faute, source)

    def test_les_accents_survivent_dans_le_pdf(self):
        """L'audit lisait « Evénement » : c'était son extracteur, pas le PDF."""
        chemin = pdf_report.ecrire(mod_dossier.construire(projet_complet()),
                                   os.path.join(tempfile.mkdtemp(prefix="hydrobassin_ortho_"),
                                                "accents.pdf"))
        with open(chemin, "rb") as fh:
            brut = fh.read()
        flux = []
        for bloc in re.finditer(rb"stream\r?\n(.*?)\r?\nendstream", brut, re.S):
            try:
                flux.append(zlib.decompress(bloc.group(1)).decode("latin-1"))
            except zlib.error:
                continue
        texte = "\n".join(flux)
        # \311 et \351 sont É et é en WinAnsi : c'est ainsi qu'un PDF les porte.
        self.assertIn("\\311", texte, "les capitales accentuées ont disparu du PDF")
        self.assertIn("\\351", texte, "les minuscules accentuées ont disparu du PDF")


class TestSourceDesPluiesNommee(unittest.TestCase):

    """Un tableau doit nommer la source dont il sort, pas celle qu'il pourrait avoir.

    Les valeurs Montana et les tables QDF diffèrent jusqu'à 5 % (Liège, 6 h,
    T = 25 ans). Un tableau de valeurs Montana intitulé « Tables QDF », collé
    dans une note de calcul, est indéfendable.
    """

    def _dossier(self, source):
        p = projet_complet()
        p.source_pluie = source
        return mod_dossier.construire(p)

    def test_les_deux_sources_donnent_bien_des_valeurs_differentes(self):
        """Sans quoi la confusion de titre serait sans conséquence."""
        m = rainfall.table_qdf_mm("62063", rainfall.SOURCE_MONTANA)
        q = rainfall.table_qdf_mm("62063", rainfall.SOURCE_QDF)
        j = rainfall.RETURN_PERIODS.index(25)
        ecarts = [abs(m[i][j] - q[i][j]) / q[i][j]
                  for i in range(len(m)) if m[i][j] and q[i][j]]
        self.assertGreater(max(ecarts), 0.04, "les deux sources devraient diverger d'au moins 4 %")

    def test_l_intitule_suit_la_source_active(self):
        montana = rainfall.SourcePluie("62063", 25, rainfall.SOURCE_MONTANA)
        qdf = rainfall.SourcePluie("62063", 25, rainfall.SOURCE_QDF)
        self.assertIn("Montana", montana.titre_tableau_hauteurs)
        self.assertNotIn("QDF", montana.titre_tableau_hauteurs)
        self.assertIn("QDF", qdf.titre_tableau_hauteurs)
        self.assertIn("Montana", montana.titre_tableau_volumes)
        self.assertIn("QDF", qdf.titre_tableau_volumes)

    def test_aucun_livrable_ne_titre_qdf_des_valeurs_montana(self):
        """Le défaut se lisait dans les trois formats à la fois."""
        repertoire = tempfile.mkdtemp(prefix="hydrobassin_source_")
        try:
            dossier = self._dossier(rainfall.SOURCE_MONTANA)
            self.assertIn("Montana", dossier.titre_table_volumes)
            self.assertNotIn("QDF", dossier.titre_table_volumes)

            chemin = docx_report.ecrire(dossier, os.path.join(repertoire, "source.docx"))
            with zipfile.ZipFile(chemin) as z:
                document = z.read("word/document.xml").decode("utf-8")
            textes = " ".join(re.findall(r"<w:t[^>]*>(.*?)</w:t>", document, re.S))
            self.assertIn("Montana", textes)
            self.assertNotIn("(table QDF)", textes,
                             "le titre annonce une table QDF alors que la source est Montana")

            chemin = xlsx_report.ecrire(dossier, os.path.join(repertoire, "source.xlsx"))
            import openpyxl
            ws = openpyxl.load_workbook(chemin)["Bassin - table QDF"]
            self.assertIn("Montana", str(ws["A2"].value))
        finally:
            shutil.rmtree(repertoire, ignore_errors=True)


class TestGrilleDureesDuClasseur(unittest.TestCase):

    """Une seule durée critique doit circuler dans tout le dossier.

    Le classeur ne peut pas balayer les 17 280 durées de l'application sans
    devenir illisible : il en échantillonne une centaine. Un échantillon libre
    tombe parfois plus près de l'optimum continu que le meilleur multiple de 5,
    et le `MAX()` du classeur retenait alors une durée critique que
    l'application n'avait jamais affichée — deux valeurs dans un même dossier,
    indéfendables devant un pouvoir adjudicateur.
    """

    @staticmethod
    def _volume(src, t, s_ponderee, q_sortie):
        """V(t) tel que l'écrit la feuille « Pluie de projet »."""
        return max(src.hauteur(t) * s_ponderee / 1000.0 - q_sortie * t * 60.0 / 1000.0, 0.0)

    def test_la_grille_du_classeur_est_incluse_dans_celle_du_moteur(self):
        """C'est l'inclusion qui garantit l'accord, pas un réglage heureux."""
        p = projet_complet()
        p.source_pluie = "montana"
        dossier = mod_dossier.construire(p)
        grille_classeur = xlsx_report._grille_durees(dossier)
        src = rainfall.SourcePluie(p.commune_ins, p.periode_retour, p.source_pluie)
        grille_moteur = set(src.durees_de_balayage())
        hors = [d for d in grille_classeur if d not in grille_moteur]
        self.assertEqual(hors, [], "le classeur balaie des durées que le moteur ignore")
        self.assertGreater(len(grille_classeur), 80, "grille trop pauvre pour tracer une courbe")

    def test_le_classeur_retient_la_meme_duree_critique_que_le_moteur(self):
        """Sur de vraies affaires : le défaut ne se manifestait qu'une fois sur six.

        La grille est demandée à `_grille_durees`, pas recalculée ici : un test
        qui refait le calcul de la production ne vérifie que lui-même.
        """
        desaccords = []
        for ins, commune in (("62063", "Liège"), ("63013", "Bütgenbach")):
            for periode in (2, 25, 100):
                for ajutage in (0.5, 2.0, 5.0, 10.0):
                    p = projet_complet()
                    p.commune_ins, p.commune_nom = ins, commune
                    p.periode_retour = periode
                    p.source_pluie = "montana"
                    p.debit_ajutage_ls = ajutage
                    p.surface_infiltration_m2 = 0.0
                    dossier = mod_dossier.construire(p)
                    resultat = dossier.resultats[mod_dossier.ORDRE_SCENARIOS[0]]
                    src = rainfall.SourcePluie(ins, periode, "montana")
                    q_sortie = resultat.debit_sortant_ls
                    retenue = max(xlsx_report._grille_durees(dossier),
                                  key=lambda t: self._volume(src, t, p.aire_ponderee_m2, q_sortie))
                    if abs(retenue - resultat.duree_critique_min) > 1e-9:
                        desaccords.append(
                            f"{commune} T={periode} ajutage={ajutage} : "
                            f"classeur {retenue} min, application {resultat.duree_critique_min} min")
        self.assertEqual(desaccords, [], "\n".join([""] + desaccords))


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


class TestClasseurDeReseau(unittest.TestCase):

    """Le classeur détaille tout le système, et se recalcule.

    Il ne portait qu'un ouvrage : ses formules visaient des noms globaux définis
    sur la feuille « Projet », si bien qu'il fallait régénérer le classeur
    autant de fois qu'il y a de bassins pour les voir tous.
    """

    @classmethod
    def setUpClass(cls):
        from bassin.core import exemple

        cls.systeme = exemple.systeme_demonstration()
        cls.systeme.synchroniser()
        cls.dossier = mod_dossier.construire(cls.systeme.courant.etude, systeme=cls.systeme)
        cls.repertoire = tempfile.mkdtemp(prefix="hydrobassin_classeur_")
        cls.chemin = os.path.join(cls.repertoire, "reseau.xlsx")
        xlsx_report.ecrire(cls.dossier, cls.chemin)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.repertoire, ignore_errors=True)

    def _classeur(self):
        import openpyxl
        return openpyxl.load_workbook(self.chemin)

    def test_chaque_ouvrage_a_ses_feuilles_de_calcul(self):
        wb = self._classeur()
        n = len(self.dossier.fiches)
        self.assertGreater(n, 1, "le réseau de démonstration doit compter plusieurs ouvrages")
        for i in range(1, n + 1):
            self.assertIn(f"Pluie {i}", wb.sheetnames)
            self.assertIn(f"Scénarios {i}", wb.sheetnames)

    def test_les_noms_de_feuilles_sont_acceptables_par_excel(self):
        """31 caractères, aucun des caractères interdits, et deux à deux distincts."""
        wb = self._classeur()
        for nom in wb.sheetnames:
            with self.subTest(feuille=nom):
                self.assertLessEqual(len(nom), 31)
                for interdit in "[]:*?/\\":
                    self.assertNotIn(interdit, nom)
        self.assertEqual(len(wb.sheetnames), len(set(wb.sheetnames)))

    def test_les_surfaces_et_les_ouvrages_sont_des_donnees_vives(self):
        wb = self._classeur()
        for feuille, attendu in (("Bassins versants", 5), ("Ouvrages", 1)):
            ws = wb[feuille]
            formules = [c.value for ligne in ws.iter_rows() for c in ligne
                        if isinstance(c.value, str) and c.value.startswith("=")]
            with self.subTest(feuille=feuille):
                self.assertGreaterEqual(len(formules), attendu)

    def test_chaque_feuille_pointe_vers_son_propre_ouvrage(self):
        """Sans quoi tous les onglets recalculeraient le même bassin."""
        wb = self._classeur()
        lignes = set()
        for i in range(1, len(self.dossier.fiches) + 1):
            ws = wb[f"Pluie {i}"]
            refs = {c.value for ligne in ws.iter_rows() for c in ligne
                    if isinstance(c.value, str) and "Ouvrages!$C$" in c.value}
            self.assertTrue(refs, f"la feuille Pluie {i} ne référence aucune surface")
            lignes.add(tuple(sorted(r[r.index("Ouvrages!$C$"):][:14] for r in list(refs)[:1])))
        self.assertEqual(len(lignes), len(self.dossier.fiches),
                         "deux ouvrages partagent la même ligne de données")

    def test_un_volume_avec_apport_amont_n_est_pas_une_somme(self):
        """L'apport amont ne s'ajoute pas : il s'intègre.

        Le classeur écrivait « volume isolé + apport amont », ce qui surestime
        de 33 % sur le réseau de démonstration : l'apport arrive étalé dans le
        temps et s'évacue en partie au fur et à mesure. Cette valeur-là vient
        du moteur, et doit rester un nombre signalé comme tel.
        """
        from bassin.core import hydro

        wb = self._classeur()
        ws = wb["Ouvrages"]
        for i, fiche in enumerate(self.dossier.fiches, start=5):
            cellule = ws.cell(row=i, column=16).value
            with self.subTest(ouvrage=fiche.nom):
                if fiche.apport_amont_m3 > 0:
                    self.assertNotIsInstance(
                        cellule, str,
                        "le volume d'un ouvrage à apport amont ne peut pas être une formule")
                    self.assertAlmostEqual(cellule, round(fiche.volume_minimal_m3, 1), places=6)
                else:
                    self.assertIsInstance(cellule, str)
                    self.assertTrue(cellule.startswith("="))

    def test_la_somme_surestimerait_vraiment_le_volume(self):
        """Sans cet écart, la règle ci-dessus serait une précaution gratuite."""
        from bassin.core import hydro

        for fiche in self.dossier.fiches:
            if fiche.apport_amont_m3 <= 0:
                continue
            etude = fiche.ouvrage.etude
            branche = etude.__dict__.pop("_apport_amont", None)
            try:
                isole = hydro.dimensionner(etude, fiche.ouvrage.scenario,
                                           avec_minima=False).volume_m3
            finally:
                if branche is not None:
                    etude.__dict__["_apport_amont"] = branche
            with self.subTest(ouvrage=fiche.nom):
                self.assertGreater(isole + fiche.apport_amont_m3,
                                   fiche.volume_minimal_m3 * 1.05,
                                   "la somme devrait surestimer nettement le volume")

    def _ligne_des_minima(self, ws):
        """Où la feuille range « Surface d'infiltration minimale »."""
        for ligne in ws.iter_rows(min_col=1, max_col=1):
            valeur = ligne[0].value
            if isinstance(valeur, str) and valeur.startswith("Surface d'infiltration minimale"):
                return ligne[0].row
        self.fail("la feuille de scénarios n'annonce plus ses minima")

    def test_les_minima_se_calculent_au_lieu_d_etre_recopies(self):
        """Une surface minimale figée ment dès qu'on retouche K ou la vidange.

        Le temps de vidange se tient tant que V x 1000 / Q / 3600 <= T max. En
        y portant V = h x S / 1000 - Q x t x 60 / 1000, le débit sort du
        maximum et la condition se résout en Q >= (h x S / 1000) /
        (3,6 T + 0,06 t) : plus besoin de dichotomie, une colonne et un MAX
        suffisent. Restent deux cas sans forme fermée — le scénario à seuil et
        un ouvrage alimenté par l'amont — qui gardent la valeur du moteur.
        """
        wb = self._classeur()
        for i, fiche in enumerate(self.dossier.fiches, start=1):
            ws = wb[f"Scénarios {i}"]
            r = self._ligne_des_minima(ws)
            amont = fiche.apport_amont_m3 > 0
            # [2] dispersion : surface minimale ; [1] temporisation : ajutage minimal.
            for cellule, libelle in ((ws.cell(row=r, column=3), "surface (dispersion)"),
                                     (ws.cell(row=r + 1, column=2), "ajutage (temporisation)")):
                with self.subTest(ouvrage=fiche.nom, minimum=libelle):
                    if amont:
                        self.assertNotIsInstance(
                            cellule.value, str,
                            "un ouvrage à apport amont ne peut pas se mettre en formule")
                    else:
                        self.assertIsInstance(cellule.value, str, "minimum figé")
                        self.assertTrue(cellule.value.startswith("="))
            # Le scénario à seuil reste une valeur du moteur, dans tous les cas.
            with self.subTest(ouvrage=fiche.nom, minimum="seuil"):
                self.assertNotIsInstance(ws.cell(row=r, column=5).value, str)

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_TEST_FORMULES"),
                         "évaluation des formules Excel (variable HYDROBASSIN_TEST_FORMULES)")
    def test_les_minima_calcules_retombent_sur_le_moteur(self):
        import formulas
        from bassin.core import hydro
        from bassin.core.model import (SCENARIO_DISPERSION, SCENARIO_MIXTE,
                                       SCENARIO_TEMPORISATION)

        solution = formulas.ExcelModel().loads(self.chemin).finish().calculate()

        def valeur(feuille, cellule):
            cle = f"]{feuille.upper()}'!{cellule}"
            for k, v in solution.items():
                if k.upper().endswith(cle):
                    return float(v.value[0, 0])
            raise KeyError(cle)

        wb = self._classeur()
        for i, fiche in enumerate(self.dossier.fiches, start=1):
            if fiche.apport_amont_m3 > 0:
                continue
            r = self._ligne_des_minima(wb[f"Scénarios {i}"])
            etude = fiche.ouvrage.etude
            essais = (
                ("C", r, hydro.surface_infiltration_minimale(etude, SCENARIO_DISPERSION), 0.5),
                ("D", r, hydro.surface_infiltration_minimale(etude, SCENARIO_MIXTE), 0.5),
                ("B", r + 1, hydro.debit_ajutage_minimal(etude, SCENARIO_TEMPORISATION), 0.01),
                ("D", r + 1, hydro.debit_ajutage_minimal(etude, SCENARIO_MIXTE), 0.01),
            )
            for colonne, ligne, attendu, absolu in essais:
                if attendu is None:
                    continue
                with self.subTest(ouvrage=fiche.nom, cellule=f"{colonne}{ligne}"):
                    self.assertAlmostEqual(valeur(f"Scénarios {i}", f"{colonne}{ligne}"),
                                           attendu, delta=max(attendu * 0.01, absolu))

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_TEST_FORMULES"),
                         "évaluation des formules Excel (variable HYDROBASSIN_TEST_FORMULES)")
    def test_changer_K_ou_la_vidange_change_les_minima(self):
        """C'est le reproche exact auquel ces formules répondent."""
        import formulas
        import openpyxl

        wb = self._classeur()
        rang = next(i for i, f in enumerate(self.dossier.fiches, start=1)
                    if f.apport_amont_m3 <= 0)
        r = self._ligne_des_minima(wb[f"Scénarios {rang}"])

        def minima(chemin):
            solution = formulas.ExcelModel().loads(chemin).finish().calculate()
            lus = []
            for cellule in (f"C{r}", f"B{r + 1}"):
                cle = f"]SCÉNARIOS {rang}'!{cellule}"
                lus.append(next(float(v.value[0, 0]) for k, v in solution.items()
                                if k.upper().endswith(cle)))
            return lus

        surface0, debit0 = minima(self.chemin)
        self.assertGreater(surface0, 0)

        # K divisé par dix : il faut dix fois plus de surface pour le même débit.
        divise = os.path.join(self.repertoire, "k_divise.xlsx")
        shutil.copy(self.chemin, divise)
        classeur = openpyxl.load_workbook(divise)
        cellule_k = classeur["Ouvrages"].cell(row=4 + rang, column=6)
        cellule_k.value = cellule_k.value / 10.0
        classeur.save(divise)
        surface1, debit1 = minima(divise)
        self.assertAlmostEqual(surface1, surface0 * 10.0, delta=surface0 * 0.02)
        self.assertAlmostEqual(debit1, debit0, delta=max(debit0 * 0.01, 1e-4),
                               msg="K ne doit pas déplacer le débit d'ajutage minimal")

        # Temps de vidange réduit : il faut évacuer plus vite, donc davantage.
        presse = os.path.join(self.repertoire, "vidange_courte.xlsx")
        shutil.copy(self.chemin, presse)
        classeur = openpyxl.load_workbook(presse)
        feuille, coord = list(classeur.defined_names["T_vidange_max"].destinations)[0]
        classeur[feuille][coord.replace("$", "")] = 24.0
        classeur.save(presse)
        surface2, debit2 = minima(presse)
        self.assertGreater(surface2, surface0 * 1.2,
                           "diviser le temps de vidange n'a pas augmenté la surface minimale")
        self.assertGreater(debit2, debit0 * 1.2,
                           "diviser le temps de vidange n'a pas augmenté l'ajutage minimal")

    def test_la_periode_de_retour_commande_tout_le_classeur(self):
        """Sinon, la cellule la plus regardée du classeur ne sert à rien.

        Les coefficients de Montana étaient figés sur la récurrence choisie
        dans l'application : passer de 25 à 100 ans sur la feuille « Projet »
        ne changeait pas un seul volume. Ils se cherchent désormais dans la
        table des douze périodes de retour.
        """
        wb = self._classeur()
        for i in range(1, len(self.dossier.fiches) + 1):
            ws = wb[f"Pluie {i}"]
            for colonne in "BCDEFG":
                valeur = ws[f"{colonne}5"].value
                with self.subTest(feuille=f"Pluie {i}", cellule=f"{colonne}5"):
                    self.assertIsInstance(valeur, str, "coefficient de Montana figé")
                    self.assertIn("Projet!$B$10", valeur,
                                  "le coefficient ne dépend pas de la période de retour")

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_TEST_FORMULES"),
                         "évaluation des formules Excel (variable HYDROBASSIN_TEST_FORMULES)")
    def test_changer_la_periode_de_retour_recalcule_les_volumes(self):
        import copy

        import formulas
        import openpyxl
        from bassin.core import hydro

        fiche = next(f for f in self.dossier.fiches if f.apport_amont_m3 <= 0)
        rang = self.dossier.fiches.index(fiche) + 1
        etude = fiche.ouvrage.etude

        def volume(chemin):
            solution = formulas.ExcelModel().loads(chemin).finish().calculate()
            cle = f"]SCÉNARIOS {rang}'!D5"
            return next(float(v.value[0, 0]) for k, v in solution.items()
                        if k.upper().endswith(cle))

        reference = volume(self.chemin)
        for periode in (5, 100):
            chemin = os.path.join(self.repertoire, f"T{periode}.xlsx")
            shutil.copy(self.chemin, chemin)
            classeur = openpyxl.load_workbook(chemin)
            classeur["Projet"]["B10"] = periode
            classeur.save(chemin)
            obtenu = volume(chemin)
            autre = copy.deepcopy(etude)
            autre.periode_retour = periode
            attendu = hydro.dimensionner(autre, "mixte", avec_minima=False).volume_m3
            with self.subTest(periode_retour=periode):
                self.assertNotAlmostEqual(obtenu, reference, places=1,
                                          msg="changer la période de retour n'a rien changé")
                self.assertAlmostEqual(obtenu, attendu, delta=max(attendu * 0.01, 0.1))

    def test_les_intensites_qdf_se_lisent_dans_la_table_du_gti(self):
        """En mode QDF, toute la colonne des intensités était écrite en dur.

        Les durées balayées y sont exactement celles du GTI — la table ne
        connaît que des durées normalisées —, donc chaque ligne se lit dans la
        feuille « Pluies statistiques » sans interpolation.
        """
        from bassin.core import rainfall

        projet = projet_complet()
        projet.commune_ins, projet.commune_nom = "61003", "Amay"
        projet.source_pluie = rainfall.SOURCE_QDF
        repertoire = tempfile.mkdtemp(prefix="hydrobassin_qdf_")
        try:
            import openpyxl

            dossier = mod_dossier.construire(projet)
            self.assertEqual(dossier.projet.source_pluie, rainfall.SOURCE_QDF)
            chemin = xlsx_report.ecrire(dossier, os.path.join(repertoire, "qdf.xlsx"))
            ws = openpyxl.load_workbook(chemin)["Pluie de projet"]
            figees = [c.coordinate for ligne in ws.iter_rows(min_col=4, max_col=4)
                      for c in ligne if isinstance(c.value, (int, float))]
            self.assertEqual(figees, [], "des intensités QDF restent écrites en dur")
            self.assertIn("Pluies statistiques", str(ws["D9"].value))
            self.assertIn("Projet!$B$10", str(ws["D9"].value))
        finally:
            shutil.rmtree(repertoire, ignore_errors=True)

    def test_ce_qui_se_deduit_ne_se_recopie_pas(self):
        """Chasse aux doublons : une donnée saisie deux fois finit par diverger."""
        wb = self._classeur()
        # La surface active amont se déduit des ouvrages situés en amont.
        ouvrages = wb["Ouvrages"]
        for i, fiche in enumerate(self.dossier.fiches, start=5):
            if fiche.aire_ponderee_amont_m2 <= 0:
                continue
            with self.subTest(ouvrage=fiche.nom):
                self.assertIsInstance(ouvrages.cell(row=i, column=4).value, str,
                                      "la surface active amont est recopiée")
        # La feuille « Projet » ne recopie rien : ses tableaux renvoient aux
        # feuilles où les données sont réellement saisies.
        projet = wb["Projet"]
        renvois = [c.value for ligne in projet.iter_rows()
                   for c in ligne if isinstance(c.value, str) and c.value.startswith("=")]
        self.assertTrue(any("Bassins versants" in v for v in renvois),
                        "les surfaces de la feuille Projet sont recopiées")
        self.assertTrue(any("Ouvrages!" in v for v in renvois),
                        "les ouvrages de la feuille Projet sont recopiés")
        # Le diamètre commercial suit la charge, le Cd et le débit visé.
        for i in range(1, len(self.dossier.fiches) + 1):
            with self.subTest(feuille=f"Ajutage {i}"):
                self.assertIsInstance(wb[f"Ajutage {i}"]["B12"].value, str,
                                      "le diamètre commercial est figé")

    def test_toute_cellule_figee_a_une_raison_d_etre(self):
        """Le garde-fou : une valeur figée nouvelle doit se justifier.

        Corriger les cellules qu'on vous signale ne suffit pas — il en reste
        toujours d'autres. Ce test classe **chaque** nombre écrit en dur dans
        une catégorie légitime : donnée source du GTI, abaque, constante
        physique, grille de durées, cellule de saisie, ou grandeur qui demande
        une intégration pas à pas. Une cellule qui n'entre dans aucune de ces
        cases fait échouer la suite : c'est un calcul qu'on a recopié au lieu
        de l'écrire.
        """
        wb = self._classeur()
        # Par feuille : les colonnes (1-indexées) dont les nombres sont
        # légitimement figés, et pourquoi.
        saisie_ouvrages = {5, 6, 8, 9, 10, 11, 13}   # hypothèses et ouvrage construit
        moteur_ouvrages = {4, 14, 15, 16}            # apport amont et ce qui en dépend
        orphelines = []
        for ws in wb:
            titre = ws.title
            for ligne in ws.iter_rows():
                for c in ligne:
                    if not isinstance(c.value, (int, float)) or isinstance(c.value, bool):
                        continue
                    if titre == "Pluies statistiques":
                        continue                      # tables du GTI
                    if titre.startswith("Pluie") and c.column == 1:
                        continue                      # grille des durées balayées
                    if titre.startswith("Ajutage"):
                        continue                      # g et abaque des diamètres
                    if titre in ("Projet", "Bassins versants"):
                        continue                      # cellules de saisie
                    if titre == "Ouvrages" and c.column in saisie_ouvrages | moteur_ouvrages:
                        continue
                    if titre == "Réseau":
                        continue                      # simulation du système
                    if titre.startswith("Table QDF"):
                        continue                      # simulation de l'ouvrage
                    if titre.startswith("Scénarios"):
                        continue                      # minima sans forme fermée
                    orphelines.append(f"{titre}!{c.coordinate} = {c.value!r}")
        self.assertEqual(orphelines, [],
                         "cellules figées sans justification :\n" + "\n".join(orphelines))

    def test_les_valeurs_figees_du_moteur_sont_signalees(self):
        """Une valeur non recalculable doit se voir, sinon elle trompe.

        Le classeur annonce qu'il recalcule : les quelques cellules qui font
        exception portent un fond orange et la feuille dit pourquoi.
        """
        wb = self._classeur()
        for i, fiche in enumerate(self.dossier.fiches, start=5):
            if fiche.apport_amont_m3 <= 0:
                continue
            for colonne in (14, 16):
                cellule = wb["Ouvrages"].cell(row=i, column=colonne)
                with self.subTest(ouvrage=fiche.nom, colonne=colonne):
                    self.assertIsInstance(cellule.value, (int, float))
                    self.assertEqual(cellule.fill.fgColor.rgb[-6:].upper(), "FEF3C7",
                                     "une valeur du moteur n'est pas signalée")
        notes = [c.value for nom in wb.sheetnames for ligne in wb[nom].iter_rows()
                 for c in ligne
                 if isinstance(c.value, str) and "ne se recalcule" in c.value]
        self.assertTrue(notes, "aucune feuille n'explique ses cellules figées")

    def test_la_feuille_projet_decrit_le_systeme_et_non_un_ouvrage(self):
        """Le classeur sert pour toute l'étude : sa première feuille aussi.

        Elle annonçait « Ouvrage détaillé par ce classeur » et ne montrait que
        lui — ses surfaces, son sol, son bassin encodé. C'était le classeur
        mono-bassin qui subsistait sous un classeur de système, alors que
        chaque ouvrage a sa ligne sur « Ouvrages » et ses propres feuilles.
        """
        wb = self._classeur()
        ws = wb["Projet"]
        textes = [c.value for ligne in ws.iter_rows() for c in ligne
                  if isinstance(c.value, str)]
        self.assertNotIn("Ouvrage détaillé par ce classeur", textes,
                         "la feuille Projet désigne encore un seul ouvrage")

        # Tous les bassins versants du projet y figurent...
        for versant in self.dossier.systeme.bassins_versants:
            with self.subTest(versant=versant.nom):
                self.assertIn(versant.nom, textes)
        # ...et tous les bassins d'orage, par renvoi à la feuille « Ouvrages ».
        renvois = "\n".join(c.value for ligne in ws.iter_rows() for c in ligne
                            if isinstance(c.value, str) and c.value.startswith("="))
        for i in range(len(self.dossier.fiches)):
            with self.subTest(ouvrage=self.dossier.fiches[i].nom):
                self.assertIn(f"Ouvrages!$A${5 + i}", renvois)

        # Les noms définis qui restent sont ceux qui valent pour tout le projet.
        self.assertEqual(sorted(wb.defined_names),
                         sorted(["Cd_orifice", "Charge_orifice", "Coef_securite",
                                 "T_vidange_max"]),
                         "un nom défini décrit encore un ouvrage particulier")

    def test_aucun_livrable_n_annonce_un_ouvrage_detaille(self):
        """Le dossier porte sur l'étude entière : son entête doit le dire.

        Les trois formats affichaient encore « Ouvrage détaillé : <nom> », hérité
        du temps où ils s'arrêtaient à l'ouvrage affiché à l'écran.
        """
        repertoire = tempfile.mkdtemp(prefix="hydrobassin_entete_")
        try:
            pdf = texte_pdf(pdf_report.ecrire(
                self.dossier, os.path.join(repertoire, "e.pdf")))
            mot = _texte_word(docx_report.ecrire(
                self.dossier, os.path.join(repertoire, "e.docx")))
            classeur = self._classeur()
            feuille = "\n".join(str(c.value) for ligne in classeur["Projet"].iter_rows()
                                for c in ligne if c.value is not None)
            for nom, texte in (("PDF", pdf), ("Word", mot), ("Excel", feuille)):
                with self.subTest(format=nom):
                    self.assertNotIn("Ouvrage détaillé", texte)
                    self.assertIn("Composition", texte,
                                  "l'entête ne dit pas ce que couvre le dossier")
        finally:
            shutil.rmtree(repertoire, ignore_errors=True)

    def test_la_periode_de_retour_se_choisit_dans_une_liste(self):
        """Douze récurrences possibles : on choisit, on ne tape pas.

        Libre, la cellule laissait saisir « 35 ans » — une valeur que le GTI ne
        connaît pas — et chaque feuille de pluie renvoyait alors un #N/A muet.
        """
        from bassin.core import rainfall

        wb = self._classeur()
        listes = [dv for dv in wb["Projet"].data_validations.dataValidation
                  if dv.type == "list" and "B10" in str(dv.sqref)]
        self.assertEqual(len(listes), 1, "aucune liste déroulante sur la période de retour")
        for rp in rainfall.RETURN_PERIODS:
            with self.subTest(periode_retour=rp):
                self.assertIn(str(rp), listes[0].formula1)

    def test_les_cellules_de_saisie_refusent_l_impossible(self):
        """Un coefficient de 12 ou un volume négatif doivent se refuser à l'entrée."""
        wb = self._classeur()
        attendus = {
            "Bassins versants": ["D", "E"],          # coefficient, surface
            "Ouvrages": ["E", "F", "H", "I", "J", "K", "M"],
            "Projet": ["B"],                          # contraintes communes
        }
        for feuille, colonnes in attendus.items():
            protegees = {str(dv.sqref)[0] for dv in wb[feuille].data_validations.dataValidation
                         if dv.type == "decimal"}
            for colonne in colonnes:
                with self.subTest(feuille=feuille, colonne=colonne):
                    self.assertIn(colonne, protegees,
                                  "colonne de saisie sans garde-fou")

    def test_aucune_formule_ne_garde_un_gabarit_non_remplace(self):
        """Une accolade dans une formule trahit un f manquant devant la chaîne.

        Le cas s'est produit : un dictionnaire de formules n'était pas fait de
        f-strings, et « ={ancrage.q_infiltration}+… » partait tel quel dans le
        classeur, où Excel n'y voyait qu'une erreur de valeur.
        """
        wb = self._classeur()
        fautives = []
        for nom in wb.sheetnames:
            for ligne in wb[nom].iter_rows():
                for c in ligne:
                    v = c.value
                    if isinstance(v, str) and v.startswith("=") and ("{" in v or "}" in v):
                        fautives.append(f"{nom}!{c.coordinate} : {v[:70]}")
        self.assertEqual(fautives, [], "\n".join([""] + fautives))

    def test_le_recapitulatif_du_reseau_se_recalcule(self):
        wb = self._classeur()
        ws = wb["Réseau"]
        formules = [c.value for ligne in ws.iter_rows() for c in ligne
                    if isinstance(c.value, str) and c.value.startswith("=")]
        self.assertTrue(any("Scénarios" in f for f in formules),
                        "la synthèse réseau ne tire rien des feuilles des ouvrages")

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_TEST_FORMULES"),
                         "évaluation des formules Excel (variable HYDROBASSIN_TEST_FORMULES)")
    def test_les_formules_de_chaque_ouvrage_reproduisent_le_moteur(self):
        import formulas
        from bassin.core import hydro

        modele = formulas.ExcelModel().loads(self.chemin).finish()
        solution = modele.calculate()

        def valeur(feuille, cellule):
            cle = f"]{feuille.upper()}'!{cellule}"
            for k, v in solution.items():
                if k.upper().endswith(cle):
                    return float(v.value[0, 0])
            raise KeyError(cle)

        for i, fiche in enumerate(self.dossier.fiches, start=1):
            etude = fiche.ouvrage.etude
            for j, scenario in enumerate(mod_dossier.ORDRE_SCENARIOS):
                colonne = chr(ord("B") + j)
                obtenu = valeur(f"Scénarios {i}", f"{colonne}5")
                # La feuille calcule le bassin seul : l'apport amont est une
                # colonne à part, car il s'intègre pas à pas.
                branche = etude.__dict__.pop("_apport_amont", None)
                try:
                    attendu = hydro.dimensionner(etude, scenario).volume_m3
                finally:
                    if branche is not None:
                        etude.__dict__["_apport_amont"] = branche
                with self.subTest(ouvrage=fiche.nom, scenario=scenario):
                    self.assertAlmostEqual(obtenu, attendu, delta=max(attendu * 0.005, 0.05))

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_TEST_FORMULES"),
                         "évaluation des formules Excel (variable HYDROBASSIN_TEST_FORMULES)")
    def test_modifier_une_surface_change_le_volume_du_bon_bassin(self):
        """C'est l'objet même d'un classeur à formules vives."""
        import formulas
        import openpyxl

        def volumes(chemin):
            modele = formulas.ExcelModel().loads(chemin).finish()
            solution = modele.calculate()
            lus = {}
            for i in range(1, len(self.dossier.fiches) + 1):
                cle = f"]SCÉNARIOS {i}'!B5"
                for k, v in solution.items():
                    if k.upper().endswith(cle):
                        lus[i] = float(v.value[0, 0])
                        break
            return lus

        avant = volumes(self.chemin)
        self.assertEqual(len(avant), len(self.dossier.fiches))

        modifie = os.path.join(self.repertoire, "modifie.xlsx")
        shutil.copy(self.chemin, modifie)
        wb = openpyxl.load_workbook(modifie)
        ws = wb["Bassins versants"]
        cible = next(l[4] for l in ws.iter_rows(min_row=5)
                     if isinstance(l[4].value, (int, float)) and l[4].value > 0)
        cible.value = cible.value * 2
        wb.save(modifie)

        apres = volumes(modifie)
        self.assertGreater(apres[1], avant[1],
                           "doubler une surface n'a pas augmenté le volume du bassin")
        for i in range(2, len(self.dossier.fiches) + 1):
            self.assertAlmostEqual(apres[i], avant[i], places=6,
                                   msg="un autre bassin a bougé alors que sa surface n'a pas changé")


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
        return texte_pdf(chemin)

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

    def test_la_synthese_du_reseau_se_recalcule(self):
        """Elle recopiait des nombres qui vivent ailleurs.

        Modifier une surface laissait la synthèse inchangée : le classeur se
        contredisait lui-même. Chaque ligne tire désormais ses valeurs des
        feuilles « Bassins versants », « Ouvrages » et « Scénarios n ».
        """
        import openpyxl

        chemin = xlsx_report.ecrire(self.dossier, self.chemin("reseau_nombres.xlsx"))
        feuille = openpyxl.load_workbook(chemin)["Réseau"]
        for fiche in self.dossier.fiches:
            ligne = next((l for l in feuille.iter_rows()
                          if isinstance(l[0].value, str) and l[0].value == fiche.nom
                          and isinstance(l[4].value, str) and l[4].value.startswith("=")), None)
            with self.subTest(ouvrage=fiche.nom):
                self.assertIsNotNone(ligne, "aucune ligne de synthèse calculée")
                self.assertTrue(ligne[2].value.startswith("=Ouvrages!"),
                                "la surface active est recopiée au lieu d'être calculée")
                self.assertIn("Ouvrages!", ligne[4].value)

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

        # Le classeur, lui, ne recopie plus ces nombres : il les calcule. On
        # vérifie donc qu'il vise la bonne cellule, et — sous
        # HYDROBASSIN_TEST_FORMULES — que le calcul retombe sur la même valeur.
        chemin = xlsx_report.ecrire(self.dossier, self.chemin("coherence.xlsx"))
        feuille = openpyxl.load_workbook(chemin)["Ouvrages"]
        formules = [c.value for ligne in feuille.iter_rows()
                    for c in ligne if isinstance(c.value, str) and c.value.startswith("=")]
        self.assertTrue(any("Scénarios" in f for f in formules),
                        "le volume minimal ne vient pas des feuilles de scénarios")
        if os.environ.get("HYDROBASSIN_TEST_FORMULES"):
            import formulas

            solution = formulas.ExcelModel().loads(chemin).finish().calculate()
            for i, fiche in enumerate(self.dossier.fiches, start=5):
                cle = f"]OUVRAGES'!P{i}"
                valeur = next(float(v.value[0, 0]) for k, v in solution.items()
                              if k.upper().endswith(cle))
                with self.subTest(ouvrage=fiche.nom):
                    self.assertAlmostEqual(valeur, fiche.volume_minimal_m3,
                                           delta=max(fiche.volume_minimal_m3 * 0.01, 0.2))

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
            # Et le rapport de réseau consacre un chapitre à chaque ouvrage :
            # c'est là ce qui le distingue, pas son nombre de sections. Les
            # deux formats doivent le faire ; le Word s'arrêtait au seul
            # ouvrage courant.
            chemin = pdf_report.ecrire(reseau, os.path.join(repertoire, "r.pdf"))
            texte = texte_pdf(chemin)
            mot = _texte_word(docx_report.ecrire(reseau, os.path.join(repertoire, "r.docx")))
            for fiche in reseau.fiches:
                with self.subTest(ouvrage=fiche.nom, format="PDF"):
                    self.assertIn(fiche.nom, texte,
                                  "un ouvrage du réseau n'a pas son chapitre")
                with self.subTest(ouvrage=fiche.nom, format="Word"):
                    self.assertIn(fiche.nom, mot,
                                  "un ouvrage du réseau n'a pas son chapitre")
                    # Le chapitre n'est pas qu'un titre : il porte le volume
                    # propre de l'ouvrage. Le Word écrit les décimales à la
                    # virgule.
                    volume = f"{fiche.resultat.volume_m3:.1f}".replace(".", ",")
                    self.assertIn(volume, mot,
                                  "le chapitre Word ne donne pas le volume de l'ouvrage")
        finally:
            shutil.rmtree(repertoire, ignore_errors=True)
