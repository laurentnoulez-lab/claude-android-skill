"""Les outils de vérification doivent dire quand ils n'ont rien vérifié.

Les captures d'interface sont relues à chaque tour : c'est par elles que
passent les défauts de mise en page, qui n'apparaissent nulle part ailleurs.
Encore faut-il qu'elles montrent ce qu'elles prétendent montrer — neuf des dix
captures « bas » étaient l'exacte copie de celle du haut, le pointeur de la
molette étant posé sur la barre de navigation, qui ne défile pas.
"""

import os
import sys
import tempfile
import unittest

RACINE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(RACINE, "src"))
sys.path.insert(0, os.path.join(RACINE, "tools"))

import captures_ui  # noqa: E402


class PageFactice:
    """Page de navigateur réduite à ce dont le défilement a besoin."""

    #: Largeur de la barre de navigation du format bureau, qui ne défile pas.
    RAIL_PX = 220

    def __init__(self, defile: bool = True):
        self.defile = defile
        self.position = (0.0, 0.0)
        self.molette = 0
        #: Défilement le plus bas atteint : c'est là que la capture est prise,
        #: le retour vers le haut qui suit n'y change rien.
        self.molette_max = 0

    class _Souris:
        def __init__(self, page):
            self.page = page

        def move(self, x, y):
            self.page.position = (x, y)

        def wheel(self, _dx, dy):
            # Seul un pointeur posé sur le contenu fait défiler la page.
            if self.page.position[0] > PageFactice.RAIL_PX:
                self.page.molette += dy
                self.page.molette_max = max(self.page.molette_max, self.page.molette)

    @property
    def mouse(self):
        return PageFactice._Souris(self)

    def wait_for_timeout(self, _ms):
        pass

    def screenshot(self, path):
        contenu = b"BAS" if (self.defile and self.molette > 0) else b"HAUT"
        with open(path, "wb") as fh:
            fh.write(contenu)
        return contenu


class TestCapturesInterface(unittest.TestCase):

    def _defiler(self, page):
        journal: list = []
        with tempfile.TemporaryDirectory() as repertoire:
            captures_ui.defiler_et_capturer(page, repertoire, "bureau", 3, "Essai",
                                            1440, 960, b"HAUT", journal)
        return journal

    def test_la_molette_agit_sur_le_contenu_pas_sur_la_barre_de_navigation(self):
        page = PageFactice()
        self._defiler(page)
        self.assertGreater(page.position[0], PageFactice.RAIL_PX,
                           "le pointeur retombe sur la barre de navigation : "
                           "la molette ne défilera rien")
        self.assertGreater(page.molette_max, 0, "aucun défilement n'a eu lieu")

    def test_la_page_qui_bouge_est_distinguee_de_celle_qui_ne_bouge_pas(self):
        page = PageFactice(defile=True)
        journal: list = []
        with tempfile.TemporaryDirectory() as repertoire:
            self.assertTrue(captures_ui.defiler_et_capturer(
                page, repertoire, "bureau", 3, "Essai", 1440, 960, b"HAUT", journal))
            self.assertFalse(captures_ui.defiler_et_capturer(
                PageFactice(defile=False), repertoire, "bureau", 3, "Essai",
                1440, 960, b"HAUT", journal))
        self.assertEqual(journal, [], "le constat seul n'est pas une anomalie")


class TestAnomaliesDeDefilement(unittest.TestCase):
    """Un contrôle qui alerte à tort finit par être ignoré."""

    #: telephone 844 px < bureau 960 px < tablette 1180 px.
    FORMATS = captures_ui.FORMATS

    def test_un_ecran_court_qui_tient_dans_la_grande_fenetre_ne_dit_rien(self):
        """Cas réel : quatre écrans ne défilent qu'en tablette, la plus haute."""
        defilements = {("telephone", "Rapport"): True, ("bureau", "Rapport"): True,
                       ("tablette", "Rapport"): False}
        self.assertEqual(captures_ui.anomalies_de_defilement(defilements, self.FORMATS), [])

    def test_une_grande_fenetre_qui_defile_quand_la_petite_ne_defile_pas_est_signalee(self):
        """Le même écran est forcément plus long dans la plus petite fenêtre.

        C'est la signature du défaut d'origine : le format bureau ne défilait
        sur aucun écran, quand le téléphone défilait sur tous.
        """
        defilements = {("telephone", "Synthèse"): False, ("bureau", "Synthèse"): False,
                       ("tablette", "Synthèse"): True}
        anomalies = captures_ui.anomalies_de_defilement(defilements, self.FORMATS)
        self.assertTrue(any("[telephone] Synthèse" in a for a in anomalies), anomalies)
        self.assertTrue(any("[bureau] Synthèse" in a for a in anomalies), anomalies)

    def test_un_ecran_qui_defile_partout_ne_dit_rien(self):
        defilements = {(f, "Réseau"): True for f in self.FORMATS}
        self.assertEqual(captures_ui.anomalies_de_defilement(defilements, self.FORMATS), [])

    def test_un_ecran_qui_ne_defile_nulle_part_ne_dit_rien(self):
        """Rien ne permet de trancher : aucune fenêtre n'a montré de contenu au-delà."""
        defilements = {(f, "Ajutage"): False for f in self.FORMATS}
        self.assertEqual(captures_ui.anomalies_de_defilement(defilements, self.FORMATS), [])


if __name__ == "__main__":
    unittest.main()
