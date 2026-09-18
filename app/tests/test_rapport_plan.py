"""Le dossier suit le plan que l'utilisateur compose.

Trois choses doivent tenir ensemble : le plan **par défaut** rend exactement le
dossier d'avant cette fonction — rubriques, ordre et numérotation —, chaque
geste de l'utilisateur se retrouve dans le PDF comme dans le Word, et le plan
survit à l'enregistrement du projet.
"""

import base64
import json
import os
import re
import struct
import sys
import tempfile
import unittest
import zipfile

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(RACINE, "src"))

from bassin.core import exemple, orifice, rapport, reseau  # noqa: E402
from test_ui import _Evenement  # noqa: E402
from bassin.reports import charts, docx_report, images, pdf_report, pdf_writer  # noqa: E402
from bassin.reports import dossier as mod_dossier  # noqa: E402

#: Plan du dossier tel qu'il était avant que l'utilisateur puisse le composer.
TITRES_RESEAU = [
    "1. Données d'entrée du projet", "2. Synthèse du réseau", "3. Pluie de projet",
    "4. Bassin d'orage du lotissement", "5. Bassin d'orage de la voirie", "6. Conclusion",
]
TITRES_BASSIN_SEUL = [
    "1. Données d'entrée", "2. Pluie de projet", "3. Comparaison des scénarios",
    "4. Vérification de l'ouvrage encodé", "5. Pluies absorbées sans débordement",
    "6. Dimensionnement de l'ajutage", "7. Conclusion",
]


def png_essai(largeur=160, hauteur=90, couleur=(5, 150, 105)) -> bytes:
    canevas = charts.Canevas(largeur, hauteur, (255, 255, 255))
    canevas.rectangle(4, 4, largeur - 4, hauteur - 4, couleur)
    return canevas.png()


def jpeg_entete(largeur: int, hauteur: int, composantes: int) -> bytes:
    """En-tête JPEG minimal : SOI, SOF0 aux dimensions et composantes dites, EOI."""
    sof = (b"\x08" + struct.pack(">HH", hauteur, largeur) + bytes([composantes])
           + b"".join(bytes([i + 1, 0x11, 0]) for i in range(composantes)))
    return (b"\xff\xd8" + b"\xff\xc0" + struct.pack(">H", len(sof) + 2) + sof + b"\xff\xd9")


def titres_pdf(chemin) -> list:
    from test_reports import texte_pdf

    return [l.strip() for l in texte_pdf(chemin).split("\n")
            if re.match(r"^\d+\. ", l.strip())]


def texte_docx(chemin) -> list:
    contenu = zipfile.ZipFile(chemin).read("word/document.xml").decode()
    return re.findall(r'<w:t xml:space="preserve">([^<]*)</w:t>', contenu)


def ecrire(dossier, repertoire, base="essai"):
    pdf = os.path.join(repertoire, base + ".pdf")
    docx = os.path.join(repertoire, base + ".docx")
    pdf_report.ecrire(dossier, pdf)
    docx_report.ecrire(dossier, docx)
    return pdf, docx


class TestPlanParDefaut(unittest.TestCase):
    """Sans rien toucher, le dossier est celui d'avant."""

    def test_le_reseau_garde_son_plan(self):
        systeme = exemple.systeme_demonstration()
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, docx = ecrire(mod_dossier.construire(systeme.courant.etude, systeme=systeme),
                               repertoire)
            self.assertEqual(titres_pdf(pdf), TITRES_RESEAU)
            for titre in TITRES_RESEAU:
                self.assertIn(titre, texte_docx(docx))

    def test_le_bassin_isole_garde_son_plan(self):
        projet = exemple.projet_demonstration()
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, _docx = ecrire(mod_dossier.construire(projet), repertoire)
            self.assertEqual(titres_pdf(pdf), TITRES_BASSIN_SEUL)

    def test_un_dossier_sans_systeme_a_quand_meme_un_plan(self):
        """Un bassin isolé monté à la main n'a pas de système : il n'en manque rien."""
        dossier = mod_dossier.construire(exemple.projet_demonstration())
        self.assertEqual([r.cle for r in dossier.plan.rubriques],
                         [cle for cle, _l, _p in rapport.RUBRIQUES_ORIGINE])


class TestRubriquesRetenues(unittest.TestCase):

    def setUp(self):
        self.systeme = exemple.systeme_demonstration()
        self.plan = self.systeme.plan_rapport

    def dossier(self):
        return mod_dossier.construire(self.systeme.courant.etude, systeme=self.systeme)

    def test_une_rubrique_decochee_disparait_des_deux_formats(self):
        self.plan.rubrique("qdf").active = False
        self.plan.rubrique("signature").active = False
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, docx = ecrire(self.dossier(), repertoire)
            from test_reports import texte_pdf

            texte = texte_pdf(pdf)
            self.assertNotIn("Pluies absorbées", texte)
            self.assertNotIn("Fait à", texte)
            mots = " ".join(texte_docx(docx))
            self.assertNotIn("Pluies absorbées", mots)
            self.assertNotIn("Fait à", mots)

    def test_les_rubriques_restantes_restent_numerotees_sans_trou(self):
        self.plan.rubrique("reseau").active = False
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, _docx = ecrire(self.dossier(), repertoire)
            numeros = [int(t.split(".")[0]) for t in titres_pdf(pdf)]
            self.assertEqual(numeros, list(range(1, len(numeros) + 1)))

    def test_l_ordre_du_plan_est_celui_du_dossier(self):
        plan = self.plan
        plan.deplacer(plan.rubriques.index(plan.rubrique("reseau")), -1)
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, _docx = ecrire(self.dossier(), repertoire)
            titres = titres_pdf(pdf)
        self.assertLess(titres.index("1. Synthèse du réseau"),
                        titres.index("2. Données d'entrée du projet"))

    def test_l_ordre_des_rubriques_d_ouvrage_suit_le_plan(self):
        plan = self.plan
        i = plan.rubriques.index(plan.rubrique("ajutage"))
        plan.deplacer(i, -1)          # l'ajutage passe avant la table QDF
        self.assertEqual([r.cle for r in plan.ouvrage_actives()],
                         ["donnees", "scenarios", "verification", "ajutage", "qdf"])


class TestRubriqueLibre(unittest.TestCase):

    def setUp(self):
        self.systeme = exemple.systeme_demonstration()
        self.rubrique = self.systeme.plan_rapport.ajouter_libre("Contexte du projet", index=1)
        self.rubrique.blocs = [
            rapport.Bloc(texte="Un paragraphe en gras.", gras=True),
            rapport.Bloc(genre=rapport.BLOC_INTERTITRE, texte="Hypothèses"),
            rapport.Bloc(texte="Un paragraphe bleu souligné.", souligne=True, couleur="#1D4ED8"),
            rapport.Bloc(genre=rapport.BLOC_IMAGE, largeur_cm=8.0, legende="Plan d'implantation",
                         image_base64=base64.b64encode(png_essai()).decode("ascii")),
        ]

    def dossier(self):
        return mod_dossier.construire(self.systeme.courant.etude, systeme=self.systeme)

    def test_elle_s_ecrit_dans_les_deux_formats(self):
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, docx = ecrire(self.dossier(), repertoire)
            from test_reports import texte_pdf

            texte = texte_pdf(pdf)
            self.assertIn("1. Contexte du projet", titres_pdf(pdf))
            self.assertIn("Un paragraphe en gras.", texte)
            self.assertIn("Hypothèses", texte)
            self.assertIn("Plan d'implantation", texte)
            mots = " ".join(texte_docx(docx))
            self.assertIn("Un paragraphe bleu souligné.", mots)

    def test_la_mise_en_forme_arrive_dans_le_word(self):
        with tempfile.TemporaryDirectory() as repertoire:
            _pdf, docx = ecrire(self.dossier(), repertoire)
            contenu = zipfile.ZipFile(docx).read("word/document.xml").decode()
        self.assertIn('<w:u w:val="single"/>', contenu)
        self.assertIn("1D4ED8", contenu)

    def test_l_image_est_bien_dans_les_deux_fichiers(self):
        import pymupdf

        with tempfile.TemporaryDirectory() as repertoire:
            pdf, docx = ecrire(self.dossier(), repertoire)
            images_pdf = sum(len(page.get_images()) for page in pymupdf.open(pdf))
            medias = [n for n in zipfile.ZipFile(docx).namelist() if "media/" in n]
        # Le dossier porte déjà les graphiques ; l'image ajoutée s'y compte en plus.
        self.assertGreater(images_pdf, 0)
        self.assertGreater(len(medias), 0)

    def test_une_image_illisible_est_annoncee_au_lieu_d_un_trou(self):
        self.rubrique.blocs[3].image_base64 = base64.b64encode(b"pas une image").decode("ascii")
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, docx = ecrire(self.dossier(), repertoire)
            from test_reports import texte_pdf

            self.assertIn("non reprise", texte_pdf(pdf))
            self.assertIn("non reprise", " ".join(texte_docx(docx)))


class TestEditeurDuPlan(unittest.TestCase):
    """Les gestes de l'écran Rapport, exercés sur les vrais contrôles.

    Un plan correct derrière un bouton qui n'appelle rien ne sert à rien : ces
    cas passent par les gestionnaires posés sur les contrôles, pas par le modèle.
    """

    def _vue(self):
        import flet as ft

        from test_ui import PageFactice
        from bassin.ui.state import EtatApplication
        from bassin.ui.vues.rapport import VueRapport

        etat = EtatApplication()
        etat.systeme = exemple.systeme_demonstration()
        vue = VueRapport(PageFactice(), etat)
        vue.construire()
        return vue, ft

    def _boutons(self, carte, ft, infobulle):
        from test_ui import _rechercher

        return [b for b in _rechercher(carte, ft.IconButton) if b.tooltip == infobulle]

    def _cases(self, carte, ft):
        from test_ui import _rechercher

        return _rechercher(carte, ft.Checkbox)

    def test_la_case_a_cocher_ecarte_la_rubrique_du_dossier(self):
        vue, ft = self._vue()
        plan = vue.etat.systeme.plan_rapport
        rang = plan.rubriques.index(plan.rubrique("qdf"))
        case = self._cases(vue.editeur.carte(), ft)[rang]
        from test_ui import _Controle

        case.on_change(_Evenement(_Controle(False)))
        self.assertFalse(plan.active("qdf"))
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, docx = ecrire(mod_dossier.construire(vue.etat.systeme.courant.etude,
                                                      systeme=vue.etat.systeme), repertoire)
            from test_reports import texte_pdf

            self.assertNotIn("Pluies absorbées", texte_pdf(pdf))
            self.assertFalse(any("Pluies absorbées" in t for t in texte_docx(docx)))

    def test_le_bouton_ajoute_une_rubrique_libre_que_le_dossier_reprend(self):
        vue, ft = self._vue()
        from test_ui import _rechercher

        plan = vue.etat.systeme.plan_rapport
        avant = len(plan.rubriques)
        boutons = [b for b in _rechercher(vue.editeur.carte(), ft.OutlinedButton)
                   if b.text == "Ajouter une rubrique"]
        self.assertTrue(boutons, "le bouton d'ajout doit exister")
        boutons[0].on_click(None)
        self.assertEqual(len(plan.rubriques), avant + 1)
        libre = plan.rubriques[-1]
        self.assertTrue(libre.libre)
        libre.titre = "Annexe"
        libre.blocs.append(rapport.Bloc(texte="Texte d'annexe."))
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, _ = ecrire(mod_dossier.construire(vue.etat.systeme.courant.etude,
                                                   systeme=vue.etat.systeme), repertoire)
            from test_reports import texte_pdf

            self.assertIn("Annexe", texte_pdf(pdf))

    def test_les_fleches_changent_l_ordre_et_le_dossier_suit(self):
        vue, ft = self._vue()
        plan = vue.etat.systeme.plan_rapport
        libre = plan.ajouter_libre("Préambule")
        depart = plan.rubriques.index(libre)
        for _ in range(depart - 1):
            self._boutons(vue.editeur.carte(), ft, "Monter")[plan.rubriques.index(libre)].on_click(None)
        self.assertEqual(plan.rubriques.index(libre), 1)
        self._boutons(vue.editeur.carte(), ft, "Descendre")[1].on_click(None)
        self.assertEqual(plan.rubriques.index(libre), 2)

    def test_une_rubrique_d_origine_n_a_pas_de_bouton_de_suppression(self):
        vue, ft = self._vue()
        plan = vue.etat.systeme.plan_rapport
        self.assertEqual(self._boutons(vue.editeur.carte(), ft, "Supprimer"), [])
        plan.ajouter_libre("Jetable")
        supprimer = self._boutons(vue.editeur.carte(), ft, "Supprimer")
        self.assertEqual(len(supprimer), 1)
        supprimer[0].on_click(None)
        self.assertTrue(all(not r.libre for r in plan.rubriques))

    def test_les_blocs_s_ajoutent_se_deplacent_et_se_suppriment(self):
        vue, ft = self._vue()
        from test_ui import _Controle, _rechercher

        plan = vue.etat.systeme.plan_rapport
        libre = plan.ajouter_libre("Notes")
        vue.editeur.depliee = plan.rubriques.index(libre)
        carte = vue.editeur.carte()
        ajouts = [b for b in _rechercher(carte, ft.OutlinedButton) if b.text == "Paragraphe"]
        self.assertTrue(ajouts, "le bouton « Paragraphe » doit exister")
        ajouts[0].on_click(None)
        ajouts = [b for b in _rechercher(vue.editeur.carte(), ft.OutlinedButton)
                  if b.text == "Intertitre"]
        ajouts[0].on_click(None)
        # Une rubrique libre naît avec un paragraphe vide, pour qu'il y ait où
        # écrire : les deux blocs ajoutés viennent après lui.
        self.assertEqual([b.genre for b in libre.blocs],
                         [rapport.BLOC_PARAGRAPHE, rapport.BLOC_PARAGRAPHE,
                          rapport.BLOC_INTERTITRE])

        # Mise en forme : la case « Gras » du premier bloc.
        carte = vue.editeur.carte()
        gras = [c for c in _rechercher(carte, ft.Checkbox) if c.label == "Gras"]
        self.assertTrue(gras)
        gras[0].on_change(_Evenement(_Controle(True)))
        self.assertTrue(libre.blocs[0].gras)

        # La flèche « Monter » du dernier bloc le fait passer devant l'avant-dernier.
        self._boutons(vue.editeur.carte(), ft, "Monter")[-1].on_click(None)
        self.assertEqual([b.genre for b in libre.blocs],
                         [rapport.BLOC_PARAGRAPHE, rapport.BLOC_INTERTITRE,
                          rapport.BLOC_PARAGRAPHE])
        self._boutons(vue.editeur.carte(), ft, "Supprimer")[-1].on_click(None)
        self.assertEqual([b.genre for b in libre.blocs],
                         [rapport.BLOC_PARAGRAPHE, rapport.BLOC_INTERTITRE])


class TestEnregistrementDuPlan(unittest.TestCase):
    """Le plan voyage avec le projet, ou il ne sert à rien."""

    def test_aller_retour_complet(self):
        systeme = exemple.systeme_demonstration()
        plan = systeme.plan_rapport
        plan.rubrique("qdf").active = False
        plan.deplacer(plan.rubriques.index(plan.rubrique("reseau")), -1)
        libre = plan.ajouter_libre("Contexte", index=0)
        libre.blocs = [rapport.Bloc(texte="Texte", gras=True, souligne=True, couleur="#DC2626"),
                       rapport.Bloc(genre=rapport.BLOC_IMAGE, largeur_cm=6.5, legende="Vue",
                                    image_base64=base64.b64encode(png_essai()).decode("ascii"))]

        relu = reseau.Systeme.from_dict(json.loads(json.dumps(systeme.to_dict())))
        self.assertEqual([r.cle or r.titre for r in relu.plan_rapport.rubriques],
                         [r.cle or r.titre for r in plan.rubriques])
        self.assertFalse(relu.plan_rapport.active("qdf"))
        bloc, image = relu.plan_rapport.rubriques[0].blocs
        self.assertEqual((bloc.texte, bloc.gras, bloc.souligne, bloc.couleur),
                         ("Texte", True, True, "#DC2626"))
        self.assertEqual(image.legende, "Vue")
        self.assertEqual(image.image(), png_essai())

    def test_un_projet_sans_plan_recoit_celui_d_origine(self):
        """Les projets enregistrés avant cette fonction restent lisibles."""
        systeme = exemple.systeme_demonstration()
        donnees = systeme.to_dict()
        donnees.pop("plan_rapport")
        relu = reseau.Systeme.from_dict(donnees)
        self.assertEqual([r.cle for r in relu.plan_rapport.rubriques],
                         [cle for cle, _l, _p in rapport.RUBRIQUES_ORIGINE])

    def test_une_rubrique_ajoutee_au_logiciel_apparait_dans_un_plan_ancien(self):
        plan = rapport.PlanRapport.from_dict(
            {"rubriques": [{"cle": "identification"}, {"cle": "conclusion", "active": False}]})
        cles = [r.cle for r in plan.rubriques]
        self.assertEqual(cles, [cle for cle, _l, _p in rapport.RUBRIQUES_ORIGINE])
        self.assertFalse(plan.active("conclusion"))

    def test_une_rubrique_d_origine_ne_se_supprime_pas(self):
        plan = rapport.plan_par_defaut()
        self.assertFalse(plan.supprimer(0))
        plan.ajouter_libre("Libre")
        self.assertTrue(plan.supprimer(len(plan.rubriques) - 1))


class TestImportExportDuProjet(unittest.TestCase):
    """Le plan composé doit survivre au fichier de projet, pas seulement au dict."""

    def _etat(self):
        from bassin.ui.state import EtatApplication

        return EtatApplication()

    def test_le_plan_compose_survit_a_l_export_et_au_reimport(self):
        depart = self._etat()
        depart.systeme = exemple.systeme_demonstration()
        plan = depart.systeme.plan_rapport
        plan.rubrique("signature").active = False
        libre = plan.ajouter_libre("Annexe photographique", index=1)
        libre.blocs = [
            rapport.Bloc(genre=rapport.BLOC_INTERTITRE, texte="Relevé du 12 mars"),
            rapport.Bloc(texte="Photographies prises depuis la voirie.", italique=True,
                         couleur="#1D4ED8"),
            rapport.Bloc(genre=rapport.BLOC_IMAGE, largeur_cm=9.0, legende="Point de rejet",
                         image_base64=base64.b64encode(png_essai()).decode("ascii")),
        ]

        arrivee = self._etat()
        arrivee.importer_texte(depart.to_json(indente=True))
        relu = arrivee.systeme.plan_rapport
        self.assertEqual([(r.cle, r.titre, r.active) for r in relu.rubriques],
                         [(r.cle, r.titre, r.active) for r in plan.rubriques])
        blocs = relu.rubriques[1].blocs
        self.assertEqual([b.genre for b in blocs],
                         [rapport.BLOC_INTERTITRE, rapport.BLOC_PARAGRAPHE,
                          rapport.BLOC_IMAGE])
        self.assertEqual(blocs[1].couleur, "#1D4ED8")
        self.assertEqual(blocs[2].image(), png_essai())

        # Le dossier relu est celui d'avant l'enregistrement, page pour page.
        with tempfile.TemporaryDirectory() as repertoire:
            avant, _ = ecrire(mod_dossier.construire(depart.systeme.courant.etude,
                                                     systeme=depart.systeme),
                              repertoire, "avant")
            apres, _ = ecrire(mod_dossier.construire(arrivee.systeme.courant.etude,
                                                     systeme=arrivee.systeme),
                              repertoire, "apres")
            from test_reports import texte_pdf

            self.assertEqual(texte_pdf(avant), texte_pdf(apres))
            self.assertIn("Annexe photographique", texte_pdf(apres))

    def test_un_fichier_a_bassin_unique_recoit_le_plan_d_origine(self):
        """Un projet enregistré avant les réseaux s'ouvre toujours."""
        etat = self._etat()
        etat.importer_texte(json.dumps({"projet": exemple.projet_demonstration().to_dict()}))
        self.assertEqual([r.cle for r in etat.systeme.plan_rapport.rubriques],
                         [cle for cle, _l, _p in rapport.RUBRIQUES_ORIGINE])
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, _ = ecrire(mod_dossier.construire(etat.systeme.courant.etude,
                                                   systeme=etat.systeme), repertoire)
            self.assertTrue(titres_pdf(pdf))


class TestDiametreCommercial(unittest.TestCase):
    """Rien n'oblige un ouvrage à être percé au calibre d'une plaque du commerce."""

    def test_les_trois_reglages(self):
        propose = orifice.dimensionner_orifice(2.5, 1.0, 0.6)
        self.assertEqual(propose.diametre_commercial_mm, 30)

        sans = orifice.dimensionner_orifice(2.5, 1.0, 0.6, commercial=False)
        self.assertIsNone(sans.diametre_commercial_mm)
        self.assertIsNone(sans.debit_commercial_ls)
        self.assertAlmostEqual(sans.diametre_mm, propose.diametre_mm, places=9)

        choisi = orifice.dimensionner_orifice(2.5, 1.0, 0.6, diametre_retenu_mm=45)
        self.assertEqual(choisi.diametre_commercial_mm, 45)
        self.assertAlmostEqual(choisi.debit_commercial_ls,
                               orifice.debit_orifice_ls(45, 1.0, 0.6), places=9)

    def test_le_choix_suit_le_projet_et_son_dossier(self):
        systeme = exemple.systeme_demonstration()
        etude = systeme.courant.etude
        etude.ajutage_diametre_commercial = False
        relu = reseau.Systeme.from_dict(json.loads(json.dumps(systeme.to_dict())))
        self.assertFalse(relu.ouvrage(relu.ouvrage_courant).etude.ajutage_diametre_commercial)

        dossier = mod_dossier.construire(etude, systeme=systeme)
        self.assertIsNone(dossier.orifice.diametre_commercial_mm)
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, _docx = ecrire(dossier, repertoire)
            from test_reports import texte_pdf

            texte = texte_pdf(pdf)
        self.assertIn("Diamètre requis", texte)


class TestDiametreQuiDepasse(unittest.TestCase):
    """Choisir un diamètre plus large que le nécessaire se paie en débit."""

    def _dossier(self, choisi):
        projet = exemple.projet_demonstration()
        projet.ajutage_diametre_commercial = True
        projet.diametre_ajutage_mm = choisi
        return mod_dossier.construire(projet)

    def test_le_diametre_propose_ne_depasse_jamais(self):
        o = self._dossier(None).orifice
        self.assertLessEqual(o.debit_commercial_ls, o.debit_ls)
        self.assertFalse(o.depasse_le_debit_vise)

    def test_un_diametre_choisi_trop_large_est_signale_dans_les_deux_dossiers(self):
        dossier = self._dossier(60.0)
        o = dossier.orifice
        self.assertGreater(o.debit_commercial_ls, o.debit_ls)
        self.assertTrue(o.depasse_le_debit_vise)
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, docx = ecrire(dossier, repertoire)
            from test_reports import texte_pdf

            phrase = "soit plus que le débit de fuite visé"
            self.assertIn(phrase, texte_pdf(pdf).replace("\n", " "))
            self.assertTrue(any(phrase in t for t in texte_docx(docx)),
                            "le Word doit dire ce que dit le PDF")

    def test_rien_n_est_dit_quand_le_debit_est_tenu(self):
        with tempfile.TemporaryDirectory() as repertoire:
            pdf, _ = ecrire(self._dossier(None), repertoire)
            from test_reports import texte_pdf

            self.assertNotIn("soit plus que le débit de fuite visé", texte_pdf(pdf))


class TestLectureDesImages(unittest.TestCase):

    def test_un_png_se_relit_pixel_par_pixel(self):
        octets = png_essai(40, 24, (220, 30, 30))
        decrite = images.lire(octets)
        self.assertEqual((decrite.format, decrite.largeur, decrite.hauteur), ("png", 40, 24))
        brut = images.png_en_brut(octets)
        self.assertEqual(len(brut.octets), brut.largeur * brut.hauteur * brut.canaux)
        self.assertEqual(tuple(brut.octets[0:3]), (255, 255, 255))     # coin blanc
        milieu = ((12 * 40) + 20) * 3
        self.assertEqual(tuple(brut.octets[milieu:milieu + 3]), (220, 30, 30))

    def test_un_jpeg_donne_ses_dimensions(self):
        # En-tête JPEG minimal : SOI, APP0, SOF0 (12 x 34), EOI.
        jpeg = (b"\xff\xd8" + b"\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
                + b"\xff\xc0\x00\x11\x08\x00\x22\x00\x0c\x03\x01\x11\x00\x02\x11\x01\x03\x11\x01"
                + b"\xff\xd9")
        decrite = images.lire(jpeg)
        self.assertEqual((decrite.format, decrite.largeur, decrite.hauteur), ("jpeg", 12, 34))
        self.assertEqual(decrite.canaux, 3)

    def test_l_espace_de_couleur_annonce_est_celui_du_fichier(self):
        """Le PDF affiche le JPEG sans le décoder : il doit dire son espace.

        Annoncé en RVB quelle qu'elle soit, une photo en niveaux de gris
        s'affichait en trois bandes noircies — vérifié au rendu, puis tenu ici
        sur l'objet écrit.
        """
        for composantes, attendu in ((1, "/DeviceGray"), (3, "/DeviceRGB")):
            with self.subTest(composantes=composantes):
                pdf = pdf_writer.Pdf()
                self.assertTrue(pdf.image(jpeg_entete(20, 10, composantes), largeur_pt=100))
                with tempfile.TemporaryDirectory() as repertoire:
                    chemin = os.path.join(repertoire, "image.pdf")
                    pdf.enregistrer(chemin)
                    octets = open(chemin, "rb").read()
                self.assertIn(attendu.encode(), octets)

    def test_un_jpeg_en_quadrichromie_est_refuse_plutot_que_deforme(self):
        """Le CMJN ne s'affiche pas correctement en RVB : le dossier le dit."""
        quadri = jpeg_entete(20, 10, 4)
        self.assertEqual(images.lire(quadri).canaux, 4)
        self.assertFalse(pdf_writer.Pdf().image(quadri, largeur_pt=100))

    def test_ce_qui_n_est_pas_une_image_est_refuse(self):
        self.assertIsNone(images.lire(b"pas une image"))
        self.assertIsNone(images.lire(b""))
        self.assertIsNone(images.png_en_brut(b"pas une image"))


if __name__ == "__main__":
    unittest.main()
