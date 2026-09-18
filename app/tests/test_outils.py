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

    def test_une_capture_du_bas_identique_a_celle_du_haut_est_signalee(self):
        """Sans ce message, le contrôle se croit fait alors qu'il ne l'est pas."""
        journal = self._defiler(PageFactice(defile=False))
        self.assertTrue(any("identique" in m for m in journal),
                        f"défilement sans effet passé sous silence : {journal}")

    def test_une_capture_du_bas_qui_montre_le_bas_ne_dit_rien(self):
        self.assertEqual(self._defiler(PageFactice(defile=True)), [])


if __name__ == "__main__":
    unittest.main()
