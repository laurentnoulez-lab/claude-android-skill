"""Cohérence du numéro de version entre tous les fichiers qui le portent.

Le numéro apparaît dans le paquet Python, le manifeste Flet, le script Inno
Setup et les deux workflows de compilation. Un oubli dans l'un d'eux ne se voit
qu'une fois le livrable produit, sous un nom qui ne correspond plus à rien.
"""

import os
import re
import sys
import unittest

RACINE_APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RACINE = os.path.dirname(RACINE_APP)
sys.path.insert(0, os.path.join(RACINE_APP, "src"))

import bassin  # noqa: E402


def _lire(*morceaux):
    chemin = os.path.join(*morceaux)
    if not os.path.exists(chemin):
        return None
    with open(chemin, encoding="utf-8") as fh:
        return fh.read()


class TestVersion(unittest.TestCase):
    def test_le_numero_est_bien_forme(self):
        self.assertRegex(bassin.__version__, r"^\d+\.\d+\.\d+$")

    def test_pyproject_suit_le_paquet(self):
        texte = _lire(RACINE_APP, "pyproject.toml")
        self.assertIsNotNone(texte)
        self.assertIn(f'version = "{bassin.__version__}"', texte)
        self.assertIn(f'build_version = "{bassin.__version__}"', texte)

    def test_installateur_windows_suit_le_paquet(self):
        texte = _lire(RACINE_APP, "packaging", "windows", "hydrobassin.iss")
        if texte is None:
            self.skipTest("script Inno Setup absent")
        self.assertIn(f'#define MaVersion "{bassin.__version__}"', texte)

    def test_les_workflows_suivent_le_paquet(self):
        v = bassin.__version__
        android = _lire(RACINE, ".github", "workflows", "build-android.yml")
        windows = _lire(RACINE, ".github", "workflows", "build-windows.yml")
        if android is None or windows is None:
            self.skipTest("workflows absents (dépôt partiel)")
        self.assertIn(f"--build-version {v}", android)
        self.assertIn(f"HydroBassin-{v}.apk", android)
        self.assertIn(f'VERSION: "{v}"', windows)

    def test_aucun_numero_perime_ne_traine(self):
        """Un ancien numéro oublié quelque part nommerait mal le livrable."""
        v = bassin.__version__
        fichiers = [
            os.path.join(RACINE_APP, "pyproject.toml"),
            os.path.join(RACINE_APP, "packaging", "windows", "hydrobassin.iss"),
            os.path.join(RACINE, ".github", "workflows", "build-android.yml"),
            os.path.join(RACINE, ".github", "workflows", "build-windows.yml"),
        ]
        motif = re.compile(r"HydroBassin[-\w]*?(\d+\.\d+\.\d+)|MaVersion \"(\d+\.\d+\.\d+)\""
                           r"|build-version (\d+\.\d+\.\d+)|VERSION: \"(\d+\.\d+\.\d+)\"")
        for chemin in fichiers:
            texte = _lire(chemin)
            if texte is None:
                continue
            for trouve in motif.finditer(texte):
                numero = next(g for g in trouve.groups() if g)
                with self.subTest(fichier=os.path.basename(chemin), numero=numero):
                    self.assertEqual(numero, v)


if __name__ == "__main__":
    unittest.main()
