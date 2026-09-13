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
        self.assertIn(f"HydroBassinPlus-{v}.apk", android)
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


class TestIdentiteDistincteDeLaVersionPrecedente(unittest.TestCase):

    """HydroBassin+ s'installe à côté de la 2.0.0, il ne la remplace pas.

    Sur Android, deux paquets ne cohabitent que si leur identifiant
    d'application diffère : à identifiant égal, le téléphone propose une mise à
    jour et écrase l'application précédente, ses projets enregistrés compris.
    Inno Setup raisonne de même sur son ``AppId``. Ces identifiants ne se
    relisent jamais après coup — rien dans l'application ne les montre — d'où
    ce test.
    """

    #: Ceux de la 2.0.0, à ne jamais reprendre.
    ANCIEN_PAQUET = "be.hydrobassin.hydrobassin"
    ANCIEN_APPID = "7B5C1E44-2E4B-4C1E-9F2E-2A1D6C3B8E10"

    #: Ceux de la 3.0.0.
    PAQUET = "be.hydrobassin.hydrobassinplus"
    APPID = "F6730013-F45E-4180-AEFB-00F8DB4A0405"

    def test_android_porte_son_propre_identifiant(self):
        texte = _lire(RACINE, ".github", "workflows", "build-android.yml")
        if texte is None:
            self.skipTest("workflow absent (dépôt partiel)")
        # L'identifiant se construit de deux façons — « org + project » et
        # « bundle-id » — qui doivent tomber sur la même valeur.
        self.assertIn(f"--bundle-id {self.PAQUET}", texte)
        self.assertIn("--project HydroBassinPlus", texte)
        self.assertIn("--org be.hydrobassin", texte)

    def test_le_manifeste_flet_porte_le_meme_identifiant(self):
        texte = _lire(RACINE_APP, "pyproject.toml")
        self.assertIsNotNone(texte)
        self.assertIn(f'bundle_id = "{self.PAQUET}"', texte)
        # Sans drapeau explicite, l'identifiant vient du nom du paquet Python.
        self.assertIn('name = "hydrobassinplus"', texte)

    def test_l_installateur_windows_porte_son_propre_appid(self):
        texte = _lire(RACINE_APP, "packaging", "windows", "hydrobassin.iss")
        if texte is None:
            self.skipTest("script Inno Setup absent")
        self.assertIn(self.APPID, texte)
        self.assertNotIn(self.ANCIEN_APPID, texte)

    @staticmethod
    def _sans_commentaires(texte: str, marqueurs: str) -> str:
        """Retire les commentaires : ce test juge la configuration, pas la prose.

        Expliquer en commentaire d'où vient l'identifiant de la 2.0.0, et
        pourquoi il ne faut pas le reprendre, est utile — le citer ne doit pas
        déclencher l'alerte.
        """
        lignes = []
        for ligne in texte.splitlines():
            depouillee = ligne.lstrip()
            if depouillee[:1] in marqueurs and depouillee[:1]:
                continue
            lignes.append(ligne)
        return "\n".join(lignes)

    def test_l_identifiant_de_la_2_0_0_ne_reparait_nulle_part(self):
        fichiers = [
            (os.path.join(RACINE_APP, "pyproject.toml"), "#"),
            (os.path.join(RACINE_APP, "packaging", "windows", "hydrobassin.iss"), ";"),
            (os.path.join(RACINE, ".github", "workflows", "build-android.yml"), "#"),
            (os.path.join(RACINE, ".github", "workflows", "build-windows.yml"), "#"),
        ]
        for chemin, marqueur in fichiers:
            texte = _lire(chemin)
            if texte is None:
                continue
            reglages = self._sans_commentaires(texte, marqueur)
            with self.subTest(fichier=os.path.basename(chemin)):
                # « be.hydrobassin.hydrobassin » est un préfixe du nouveau :
                # on cherche donc l'ancien suivi d'autre chose qu'un « plus ».
                self.assertIsNone(
                    re.search(re.escape(self.ANCIEN_PAQUET) + r"(?!plus)", reglages),
                    f"l'identifiant de la 2.0.0 subsiste dans {os.path.basename(chemin)}")
                self.assertNotIn(self.ANCIEN_APPID, reglages)

    def test_le_nom_affiche_distingue_les_deux_applications(self):
        """Deux icônes identiques sur un téléphone ne servent à rien."""
        self.assertEqual(bassin.__app_name__, "HydroBassin+")
        for fichier in ("build-android.yml", "build-windows.yml"):
            texte = _lire(RACINE, ".github", "workflows", fichier)
            if texte is None:
                continue
            with self.subTest(fichier=fichier):
                self.assertIn('--product "HydroBassin+"', texte)
        iss = _lire(RACINE_APP, "packaging", "windows", "hydrobassin.iss")
        if iss is not None:
            self.assertIn('#define MonApp "HydroBassin+"', iss)


if __name__ == "__main__":
    unittest.main()
