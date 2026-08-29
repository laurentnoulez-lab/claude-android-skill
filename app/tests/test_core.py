"""Tests unitaires du moteur de calcul (python -m unittest discover app/tests)."""

import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core import hydro, orifice, rainfall, simulation  # noqa: E402
from bassin.core.model import (  # noqa: E402
    Bassin,
    BassinAmont,
    Projet,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_SEUIL,
    SCENARIO_TEMPORISATION,
    debit_infiltration_ls,
    surface_infiltration_requise_m2,
)

BUTGENBACH = "63013"


def projet_type(**kw) -> Projet:
    p = Projet(commune_ins=BUTGENBACH, commune_nom="Butgenbach", periode_retour=25,
               surfaces=Projet.surfaces_par_defaut(), surface_reference_m2=2000.0)
    p.surfaces[7].aire_m2 = 1500.0   # toitures / voiries, c = 1.0
    p.surfaces[1].aire_m2 = 500.0    # prairies, c = 0.15
    p.k_infiltration_ms = 1e-5
    for k, v in kw.items():
        setattr(p, k, v)
    return p


class TestPluies(unittest.TestCase):
    """Valeurs de reference extraites du classeur GTI (feuille 'Pluie', Butgenbach 25 ans)."""

    REFERENCES = {  # duree [min] -> intensite [mm/h] mise en cache par Excel
        10: 114.12981983046772,
        15: 96.19645256341528,
        20: 85.20888561097043,
        25: 77.58103899085853,
        30: 67.64859844449096,
        55: 42.90018738415969,
        455: 8.76875529429982,
        5955: 1.2697597467926425,
        6455: 1.2167279273427172,
    }

    def test_intensites_montana(self):
        for duree, attendu in self.REFERENCES.items():
            self.assertAlmostEqual(rainfall.intensite_montana(BUTGENBACH, 25, duree), attendu, places=9)

    def test_bascule_des_jeux_de_coefficients(self):
        a1, b1, a2, b2, a3, b3 = rainfall.montana_coeffs(BUTGENBACH, 25)
        self.assertAlmostEqual(rainfall.intensite_montana(BUTGENBACH, 25, 24), a1 * 24 ** -b1, places=9)
        self.assertAlmostEqual(rainfall.intensite_montana(BUTGENBACH, 25, 25), a2 * 25 ** -b2, places=9)
        self.assertAlmostEqual(rainfall.intensite_montana(BUTGENBACH, 25, 6001), a3 * 6001 ** -b3, places=9)

    def test_hauteur_est_integrale_de_l_intensite(self):
        self.assertAlmostEqual(
            rainfall.hauteur_montana(BUTGENBACH, 25, 60),
            rainfall.intensite_montana(BUTGENBACH, 25, 60),
            places=9,
        )

    def test_conversion_ls_ha(self):
        src = rainfall.SourcePluie(BUTGENBACH, 25)
        self.assertAlmostEqual(src.intensite_ls_ha(60), src.intensite_mmh(60) * 10000.0 / 3600.0, places=9)

    def test_referentiel_communes(self):
        self.assertGreater(len(rainfall.communes()), 500)
        self.assertEqual(len(rainfall.communes_wallonnes()), 262)
        self.assertTrue(all(c.a_qdf for c in rainfall.communes_wallonnes()))

    def test_bascule_qdf_si_montana_absent(self):
        src = rainfall.SourcePluie("56011", 25)  # Binche : pas de coefficients Montana
        self.assertEqual(src.source, rainfall.SOURCE_QDF)
        self.assertGreater(src.hauteur(60), 0)

    def test_interpolation_qdf_aux_noeuds(self):
        valeurs = rainfall.hauteurs_qdf("25005", 25)
        for i, duree in enumerate(rainfall.QDF_DURATIONS_MIN):
            self.assertAlmostEqual(rainfall.hauteur_qdf("25005", 25, duree), valeurs[i], places=6)

    def test_le_balayage_qdf_se_limite_aux_durees_tabulees(self):
        """Les tables QDF sont discrètes : pas de durée critique à 12 h 05."""
        src = rainfall.SourcePluie("81001", 50, rainfall.SOURCE_QDF)
        self.assertEqual(src.durees_de_balayage(), tuple(float(d) for d in rainfall.QDF_DURATIONS_MIN))
        self.assertTrue(src.durees_tabulees)

        continu = rainfall.SourcePluie("81001", 50, rainfall.SOURCE_MONTANA)
        self.assertFalse(continu.durees_tabulees)
        self.assertGreater(len(continu.durees_de_balayage()), 1000)

    def test_duree_critique_tabulee_avec_la_source_qdf(self):
        tabulees = {float(d) for d in rainfall.QDF_DURATIONS_MIN}
        for T in (10, 25, 50, 100):
            for scenario in (SCENARIO_TEMPORISATION, SCENARIO_MIXTE):
                with self.subTest(periode_retour=T, scenario=scenario):
                    p = Projet(commune_ins="81001", commune_nom="Arlon", periode_retour=T,
                               surfaces=Projet.surfaces_par_defaut(), source_pluie="qdf")
                    p.surfaces[7].aire_m2 = 10000.0
                    p.debit_ajutage_ls = 4.0
                    p.surface_infiltration_m2 = 200.0
                    res = hydro.dimensionner(p, scenario)
                    self.assertIn(res.duree_critique_min, tabulees)

    def test_les_deux_sources_restent_proches(self):
        """Montana est un ajustement des mesures QDF : pas d'écart systématique."""
        ecarts = []
        for commune in rainfall.communes_wallonnes()[:40]:
            if not (commune.a_qdf and commune.a_montana):
                continue
            for T in (10, 25, 100):
                mesures = rainfall.hauteurs_qdf(commune.ins, T)
                for duree, qdf in zip(rainfall.QDF_DURATIONS_MIN, mesures):
                    if qdf:
                        montana = rainfall.hauteur_montana(commune.ins, T, duree)
                        ecarts.append((montana - qdf) / qdf)
        self.assertGreater(len(ecarts), 500)
        ecarts.sort()
        mediane = ecarts[len(ecarts) // 2]
        self.assertLess(abs(mediane), 0.02, "écart systématique entre les deux sources")
        self.assertLess(max(abs(e) for e in ecarts), 0.25)

    def test_pluie_croissante_avec_la_recurrence(self):
        h = [rainfall.hauteur_montana(BUTGENBACH, rp, 120) for rp in rainfall.RETURN_PERIODS]
        self.assertEqual(h, sorted(h))

    def test_tables_qdf(self):
        mm = rainfall.table_qdf_mm("25005")
        lsha = rainfall.table_qdf_ls_ha("25005")
        self.assertEqual(len(mm), len(rainfall.QDF_DURATIONS_MIN))
        self.assertEqual(len(mm[0]), len(rainfall.RETURN_PERIODS))
        self.assertAlmostEqual(lsha[0][0], mm[0][0] / (10 * 60.0) * 10000.0, places=6)


class TestChargementDesDonnees(unittest.TestCase):
    """Le référentiel doit rester disponible quel que soit l'empaquetage."""

    def test_origine_renseignee(self):
        rainfall.communes()
        self.assertNotEqual(rainfall.SOURCE_DONNEES["origine"], "inconnue")

    def test_le_repli_embarque_est_identique_au_fichier(self):
        import gzip
        import json

        from bassin.data.gti_embarque import DONNEES

        with open(rainfall._data_path(), "rb") as fh:
            depuis_fichier = json.loads(gzip.decompress(fh.read()).decode("utf-8"))
        depuis_module = json.loads(gzip.decompress(DONNEES).decode("utf-8"))
        self.assertEqual(depuis_module.keys(), depuis_fichier.keys())
        self.assertEqual(len(depuis_module["communes"]), len(depuis_fichier["communes"]))
        self.assertEqual(depuis_module["montana"]["63013"], depuis_fichier["montana"]["63013"])

    def test_le_repli_seul_suffit_a_charger_les_pluies(self):
        import gzip
        import json

        from bassin.data.gti_embarque import DONNEES

        donnees = json.loads(gzip.decompress(DONNEES).decode("utf-8"))
        a1, b1 = donnees["montana"]["63013"]["25"][:2]
        self.assertAlmostEqual(a1 * 10 ** -b1, rainfall.intensite_montana(BUTGENBACH, 25, 10), places=9)


class TestMethodeRationnelle(unittest.TestCase):
    def test_surfaces_ponderees(self):
        p = projet_type()
        self.assertEqual(p.aire_totale_m2, 2000.0)
        self.assertEqual(p.aire_ponderee_m2, 1500.0 + 0.15 * 500.0)
        self.assertAlmostEqual(p.coefficient_moyen, 1575.0 / 2000.0)

    def test_volume_conforme_a_la_formule_gti(self):
        p = projet_type(debit_ajutage_ls=1.0)
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        h = rainfall.hauteur_montana(BUTGENBACH, 25, res.duree_critique_min)
        attendu = h * p.aire_ponderee_m2 / 1000.0 - 1.0 * res.duree_critique_min * 60.0 / 1000.0
        self.assertAlmostEqual(res.volume_m3, attendu, places=6)

    def test_duree_critique_maximise_le_volume(self):
        p = projet_type(debit_ajutage_ls=1.0)
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        serie = hydro.serie_projet(p)
        for t, h in zip(*serie):
            v = h * p.aire_ponderee_m2 / 1000.0 - 1.0 * t * 60.0 / 1000.0
            self.assertLessEqual(v, res.volume_m3 + 1e-9)

    def test_debit_infiltration_avec_coefficient_de_securite(self):
        self.assertAlmostEqual(debit_infiltration_ls(100.0, 1e-5), 0.5)
        self.assertAlmostEqual(surface_infiltration_requise_m2(0.5, 1e-5), 100.0)

    def test_le_volume_decroit_quand_le_debit_de_fuite_augmente(self):
        p = projet_type()
        volumes = [hydro.dimensionner(p, SCENARIO_TEMPORISATION, debit_ajutage=q, avec_minima=False).volume_m3
                   for q in (0.5, 1.0, 2.0, 5.0)]
        self.assertEqual(volumes, sorted(volumes, reverse=True))

    def test_scenario_mixte_plus_favorable_que_les_scenarios_simples(self):
        p = projet_type(debit_ajutage_ls=1.0, surface_infiltration_m2=100.0)
        v_tempo = hydro.dimensionner(p, SCENARIO_TEMPORISATION, avec_minima=False).volume_m3
        v_disp = hydro.dimensionner(p, SCENARIO_DISPERSION, avec_minima=False).volume_m3
        v_mixte = hydro.dimensionner(p, SCENARIO_MIXTE, avec_minima=False).volume_m3
        self.assertLess(v_mixte, v_tempo)
        self.assertLess(v_mixte, v_disp)

    def test_scenario_seuil_entre_dispersion_et_mixte(self):
        p = projet_type(debit_ajutage_ls=1.0, surface_infiltration_m2=100.0)
        p.bassin = Bassin(volume_sous_ajutage_m3=20.0, surface_dispersion_m2=100.0, debit_ajutage_ls=1.0)
        v_disp = hydro.dimensionner(p, SCENARIO_DISPERSION, avec_minima=False).volume_m3
        v_mixte = hydro.dimensionner(p, SCENARIO_MIXTE, avec_minima=False).volume_m3
        v_seuil = hydro.dimensionner(p, SCENARIO_SEUIL, avec_minima=False).volume_m3
        self.assertLessEqual(v_mixte, v_seuil + 1e-9)
        self.assertLessEqual(v_seuil, v_disp + 1e-9)

    def test_seuil_nul_equivaut_au_scenario_mixte(self):
        p = projet_type(debit_ajutage_ls=1.0, surface_infiltration_m2=100.0)
        p.bassin = Bassin(volume_sous_ajutage_m3=0.0)
        v_mixte = hydro.dimensionner(p, SCENARIO_MIXTE, avec_minima=False).volume_m3
        v_seuil = hydro.dimensionner(p, SCENARIO_SEUIL, avec_minima=False).volume_m3
        self.assertAlmostEqual(v_mixte, v_seuil, places=6)

    def test_temps_de_vidange(self):
        self.assertAlmostEqual(hydro.temps_vidange_h(36.0, 0.0, 1.0), 10.0)
        self.assertAlmostEqual(hydro.temps_vidange_h(36.0, 0.5, 0.5), 10.0)
        # volume mort vidange par la seule infiltration
        self.assertAlmostEqual(hydro.temps_vidange_h(20.0, 1.0, 1.0, volume_sous_ajutage_m3=10.0),
                               10.0 * 1000 / 2.0 / 3600 + 10.0 * 1000 / 1.0 / 3600)
        self.assertEqual(hydro.temps_vidange_h(10.0, 0.0, 0.0), float("inf"))

    def test_surface_minimale_atteint_le_temps_de_vidange_cible(self):
        p = projet_type(temps_vidange_max_h=48.0)
        s = hydro.surface_infiltration_minimale(p, SCENARIO_DISPERSION)
        self.assertIsNotNone(s)
        p2 = projet_type(surface_infiltration_m2=s)
        res = hydro.dimensionner(p2, SCENARIO_DISPERSION, avec_minima=False)
        self.assertLessEqual(res.temps_vidange_h, 48.0 + 1e-3)
        p3 = projet_type(surface_infiltration_m2=s * 0.9)
        self.assertGreater(hydro.dimensionner(p3, SCENARIO_DISPERSION, avec_minima=False).temps_vidange_h, 48.0)

    def test_debit_ajutage_minimal(self):
        p = projet_type()
        q = hydro.debit_ajutage_minimal(p, SCENARIO_TEMPORISATION)
        self.assertIsNotNone(q)
        res = hydro.dimensionner(projet_type(debit_ajutage_ls=q), SCENARIO_TEMPORISATION, avec_minima=False)
        self.assertLessEqual(res.temps_vidange_h, 48.0 + 1e-3)

    def test_alerte_temps_de_vidange(self):
        p = projet_type(surface_infiltration_m2=10.0)
        res = hydro.dimensionner(p, SCENARIO_DISPERSION)
        self.assertFalse(res.conforme)
        self.assertTrue(any("vidange" in a for a in res.alertes))

    def test_alerte_debit_de_fuite_admissible(self):
        p = projet_type(debit_ajutage_ls=5.0)   # admissible = 5 l/s/ha * 0.2 ha = 1 l/s
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        self.assertTrue(any("admissible" in a for a in res.alertes))

    def test_alerte_periode_de_retour(self):
        p = projet_type(periode_retour=10, debit_ajutage_ls=1.0)
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        self.assertTrue(any("période de retour" in a for a in res.alertes))

    def test_sans_debit_de_sortie_le_volume_n_a_pas_de_sens(self):
        p = projet_type()  # ni infiltration ni ajutage
        res = hydro.dimensionner(p, SCENARIO_MIXTE)
        self.assertFalse(res.dimensionnable)
        self.assertEqual(res.volume_affiche, "—")
        self.assertEqual(res.temps_vidange_hm, "infini")
        self.assertFalse(res.conforme)
        res_ok = hydro.dimensionner(projet_type(debit_ajutage_ls=1.0), SCENARIO_TEMPORISATION)
        self.assertTrue(res_ok.dimensionnable)
        self.assertEqual(res_ok.volume_affiche, f"{res_ok.volume_m3:.1f}")

    def test_courbe_volume(self):
        p = projet_type(debit_ajutage_ls=1.0)
        pts = hydro.courbe_volume(p, SCENARIO_TEMPORISATION)
        self.assertGreater(len(pts), 50)
        self.assertTrue(all(v >= 0 for _, v in pts))

    def test_formatage_duree(self):
        self.assertEqual(hydro.formater_duree(45), "45 min")
        self.assertEqual(hydro.formater_duree(120), "2 h")
        self.assertEqual(hydro.formater_duree(150), "2 h 30")
        self.assertEqual(hydro.formater_duree(2880), "2 j")


class _AvecBassin(unittest.TestCase):
    """Fabrique commune aux tests de simulation."""

    def bassin_type(self, **kw) -> Bassin:
        params = dict(volume_total_m3=90.0, volume_sous_ajutage_m3=0.0,
                      surface_dispersion_m2=120.0, debit_ajutage_ls=1.0)
        params.update(kw)
        return Bassin(**params)


class TestSimulation(_AvecBassin):

    def test_simulation_conserve_les_volumes(self):
        p = projet_type()
        b = self.bassin_type()
        p.bassin = b
        res = simulation.simuler(p, b, 40.0, 120.0, n_points=2000)
        entre = sum(pas.q_entrant_ls for pas in res.pas[1:])
        self.assertGreater(res.volume_max_m3, 0)
        self.assertAlmostEqual(res.volume_ruissele_m3, 40.0 * p.aire_ponderee_m2 / 1000.0, places=6)
        self.assertGreater(entre, 0)

    def test_pointe_simulee_egale_au_calcul_analytique(self):
        """L'intégration est exacte entre les seuils : aucun écart, même grossièrement
        échantillonnée."""
        p = projet_type()
        b = self.bassin_type(volume_total_m3=10000.0)
        p.bassin = b
        for duree in (30.0, 120.0, 720.0):
            for points in (60, 400, 4000):
                with self.subTest(duree=duree, points=points):
                    h = rainfall.hauteur_montana(BUTGENBACH, 25, duree)
                    attendu = simulation.volume_necessaire(p, b, h, duree)
                    res = simulation.simuler(p, b, h, duree, n_points=points)
                    self.assertAlmostEqual(res.volume_max_m3, attendu, places=6)

    def test_pointe_simulee_avec_ajutage_sureleve(self):
        p = projet_type()
        for v_sous in (5.0, 15.0, 40.0):
            with self.subTest(volume_sous_ajutage=v_sous):
                b = self.bassin_type(volume_total_m3=10000.0, volume_sous_ajutage_m3=v_sous)
                p.bassin = b
                h = rainfall.hauteur_montana(BUTGENBACH, 25, 240.0)
                attendu = simulation.volume_necessaire(p, b, h, 240.0)
                res = simulation.simuler(p, b, h, 240.0)
                self.assertAlmostEqual(res.volume_max_m3, attendu, places=6)

    def test_la_pluie_injectee_est_exactement_la_pluie_de_projet(self):
        """Le pas de calcul ne doit jamais déverser de pluie au-delà de l'averse."""
        p = projet_type()
        b = self.bassin_type(volume_total_m3=10000.0)
        p.bassin = b
        for duree in (17.0, 725.0, 1000.0):   # durées non multiples du pas
            with self.subTest(duree=duree):
                h = rainfall.hauteur_montana(BUTGENBACH, 25, duree)
                res = simulation.simuler(p, b, h, duree)
                injecte = sum(fin.q_entrant_ls * (fin.t_min - debut.t_min) * 60.0 / 1000.0
                              for debut, fin in zip(res.pas, res.pas[1:]))
                self.assertAlmostEqual(injecte, res.volume_ruissele_m3, places=6)

    def test_un_bassin_au_volume_dimensionne_ne_deborde_pas(self):
        """Cas signalé : 548,2 m³ annoncés, débordement simulé à 550 m³ (Arlon, T=50)."""
        p = Projet(commune_ins="81001", commune_nom="Arlon", periode_retour=50,
                   surfaces=Projet.surfaces_par_defaut(), source_pluie="qdf")
        p.surfaces[7].aire_m2 = 10000.0
        p.debit_ajutage_ls = 4.0
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        b = Bassin(volume_total_m3=res.volume_m3, volume_sous_ajutage_m3=0.0,
                   surface_dispersion_m2=0.0, debit_ajutage_ls=4.0)
        p.bassin = b
        sim = simulation.simuler(p, b, res.hauteur_pluie_mm, res.duree_critique_min)
        self.assertFalse(sim.debordement)
        self.assertAlmostEqual(sim.volume_max_m3, res.volume_m3, places=6)

    def test_le_dimensionnement_est_le_volume_juste_suffisant(self):
        """Un litre de moins et le bassin déborde : la frontière est exacte."""
        p = projet_type()
        p.debit_ajutage_ls = 1.0
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        for facteur, attendu in ((1.0, False), (0.999, True)):
            with self.subTest(facteur=facteur):
                b = Bassin(volume_total_m3=res.volume_m3 * facteur, volume_sous_ajutage_m3=0.0,
                           surface_dispersion_m2=0.0, debit_ajutage_ls=p.debit_ajutage_ls)
                p.bassin = b
                sim = simulation.simuler(p, b, res.hauteur_pluie_mm, res.duree_critique_min)
                self.assertEqual(sim.debordement, attendu)

    def test_detection_de_debordement(self):
        p = projet_type()
        b = self.bassin_type(volume_total_m3=5.0)
        p.bassin = b
        res = simulation.simuler(p, b, 60.0, 180.0)
        self.assertTrue(res.debordement)
        self.assertIsNotNone(res.t_debordement_min)
        self.assertAlmostEqual(res.volume_max_m3, 5.0, places=6)

    def test_bassin_se_vidange_apres_la_pluie(self):
        p = projet_type()
        b = self.bassin_type(volume_total_m3=10000.0)
        p.bassin = b
        res = simulation.simuler(p, b, 30.0, 60.0, n_points=3000)
        self.assertLess(res.pas[-1].volume_m3, 1e-3)
        self.assertGreater(res.temps_vidange_h, 0)

    def test_un_bassin_dimensionne_ne_deborde_jamais(self):
        """Propriété générale : sur tous les scénarios, sources et récurrences,
        un bassin construit au volume annoncé encaisse la pluie de projet."""
        communes = [c for c in rainfall.communes_wallonnes() if c.a_qdf and c.a_montana][:6]
        pire = 0.0
        cas = 0
        for commune in communes:
            for source in ("montana", "qdf"):
                for scenario in (SCENARIO_TEMPORISATION, SCENARIO_DISPERSION,
                                 SCENARIO_MIXTE, SCENARIO_SEUIL):
                    p = Projet(commune_ins=commune.ins, commune_nom=commune.nom,
                               periode_retour=25, surfaces=Projet.surfaces_par_defaut(),
                               source_pluie=source)
                    p.surfaces[7].aire_m2 = 5000.0
                    p.debit_ajutage_ls = 2.0
                    p.surface_infiltration_m2 = 200.0
                    p.k_infiltration_ms = 1e-5
                    v_sous = 20.0 if scenario == SCENARIO_SEUIL else 0.0
                    p.bassin = Bassin(volume_total_m3=1e9, volume_sous_ajutage_m3=v_sous,
                                      surface_dispersion_m2=200.0, debit_ajutage_ls=2.0)
                    res = hydro.dimensionner(p, scenario)
                    if not res.dimensionnable:
                        continue
                    infiltre = scenario in (SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL)
                    ajute = scenario in (SCENARIO_TEMPORISATION, SCENARIO_MIXTE, SCENARIO_SEUIL)
                    b = Bassin(volume_total_m3=res.volume_m3, volume_sous_ajutage_m3=v_sous,
                               surface_dispersion_m2=200.0 if infiltre else 0.0,
                               debit_ajutage_ls=2.0 if ajute else 0.0)
                    p.bassin = b
                    sim = simulation.simuler(p, b, res.hauteur_pluie_mm, res.duree_critique_min)
                    cas += 1
                    pire = max(pire, sim.volume_debordement_m3)
        self.assertGreater(cas, 20)
        self.assertLess(pire, 1e-6, "le bassin dimensionné déborde en simulation")

class TestBassinAmont(unittest.TestCase):
    """Bassin d'orage amont qui se déverse dans l'ouvrage étudié."""

    def projet_avec_amont(self, **kw) -> Projet:
        p = Projet(commune_ins="81001", commune_nom="Arlon", periode_retour=25,
                   surfaces=Projet.surfaces_par_defaut(), source_pluie="qdf")
        p.surfaces[7].aire_m2 = 5000.0
        p.debit_ajutage_ls = 3.0
        params = dict(actif=True, surface_bv_m2=8000.0, coef_ruissellement=0.8,
                      debit_ajutage_ls=2.0, surface_dispersion_m2=0.0,
                      k_infiltration_ms=1e-5, volume_temporisation_m3=1e6)
        params.update(kw)
        p.amont = BassinAmont(**params)
        p.bassin = Bassin(volume_total_m3=1e6, volume_sous_ajutage_m3=0.0,
                          surface_dispersion_m2=0.0, debit_ajutage_ls=3.0)
        return p

    def test_ce_qui_tombe_en_amont_arrive_ou_s_infiltre(self):
        """Conservation : la pluie du bassin versant amont s'infiltre ou descend."""
        for surface_dispersion in (0.0, 150.0):
            with self.subTest(surface_dispersion=surface_dispersion):
                p = self.projet_avec_amont(surface_dispersion_m2=surface_dispersion)
                res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
                h, t = res.hauteur_pluie_mm, res.duree_critique_min
                apport = simulation.hydrogramme_amont(p, h, t)
                tombe = h * p.amont.aire_ponderee_m2 / 1000.0
                q_inf = p.amont.debit_infiltration_ls(p.coef_securite_infiltration)
                self.assertLessEqual(apport.volume_m3, tombe + 1e-6)
                if q_inf == 0:
                    self.assertAlmostEqual(apport.volume_m3, tombe, places=6)
                else:
                    self.assertLess(apport.volume_m3, tombe)

    def test_un_amont_inactif_ne_change_rien(self):
        p = self.projet_avec_amont(actif=False)
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        h, t = res.hauteur_pluie_mm, res.duree_critique_min
        apport = simulation.hydrogramme_amont(p, h, t)
        self.assertEqual(apport.segments, [])
        sans = simulation.simuler(p, p.bassin, h, t)
        avec = simulation.simuler(p, p.bassin, h, t, apport=apport)
        self.assertAlmostEqual(sans.volume_max_m3, avec.volume_max_m3, places=9)

    def test_l_amont_augmente_la_pointe_aval(self):
        p = self.projet_avec_amont()
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        h, t = res.hauteur_pluie_mm, res.duree_critique_min
        apport = simulation.hydrogramme_amont(p, h, t)
        sans = simulation.simuler(p, p.bassin, h, t)
        avec = simulation.simuler(p, p.bassin, h, t, apport=apport)
        self.assertGreater(avec.volume_max_m3, sans.volume_max_m3)
        self.assertAlmostEqual(avec.volume_amont_m3, apport.volume_m3, places=6)

    def test_un_amont_sous_dimensionne_deborde_et_restitue_plus_vite(self):
        """Sous le volume minimal, le trop-plein amont arrive d'un coup en aval."""
        p = self.projet_avec_amont()
        minimal = hydro.volume_amont_minimal_m3(p)
        self.assertGreater(minimal, 0)
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        h, t = res.hauteur_pluie_mm, res.duree_critique_min

        p.amont.volume_temporisation_m3 = minimal
        juste = simulation.simuler(p, p.bassin, h, t,
                                   apport=simulation.hydrogramme_amont(p, h, t))
        p.amont.volume_temporisation_m3 = minimal * 0.5
        petit = simulation.simuler(p, p.bassin, h, t,
                                   apport=simulation.hydrogramme_amont(p, h, t))
        self.assertGreater(petit.q_amont_max_ls, juste.q_amont_max_ls)
        self.assertGreater(petit.volume_max_m3, juste.volume_max_m3)

    def test_toutes_les_vues_decrivent_le_meme_evenement(self):
        """Écran de synthèse, table QDF et rapport doivent concorder."""
        from bassin.ui.state import EtatApplication

        p = self.projet_avec_amont()
        p.bassin = Bassin(volume_total_m3=400.0, volume_sous_ajutage_m3=0.0,
                          surface_dispersion_m2=0.0, debit_ajutage_ls=3.0)
        etat = EtatApplication()
        etat.projet = p
        sim = etat.simulation
        self.assertIsNotNone(sim)
        self.assertGreater(sim.volume_amont_m3, 0, "l'apport amont doit être pris en compte")
        requis = simulation.volume_requis_m3(p, p.bassin, sim.hauteur_pluie_mm, sim.duree_pluie_min)
        self.assertAlmostEqual(sim.volume_max_m3, min(requis, 400.0), places=6)

    def test_reprendre_le_dimensionnement_donne_un_ouvrage_qui_tient(self):
        """Le volume proposé (+5 %) ne doit pas déborder, même avec un amont."""
        from bassin.ui.state import EtatApplication

        for actif in (False, True):
            with self.subTest(amont=actif):
                p = self.projet_avec_amont()
                p.amont.actif = actif
                p.bassin = Bassin()
                etat = EtatApplication()
                etat.projet = p
                etat.scenario_principal = SCENARIO_TEMPORISATION
                etat.reprendre_dimensionnement()
                self.assertGreater(p.bassin.volume_total_m3, 0)
                sim = etat.simulation
                self.assertFalse(sim.debordement,
                                 "l'ouvrage proposé déborde dès la pluie de projet")

    def test_la_table_qdf_tient_compte_de_l_amont(self):
        """La table d'acceptation ne doit pas contredire la simulation."""
        p = self.projet_avec_amont()
        p.bassin = Bassin(volume_total_m3=300.0, volume_sous_ajutage_m3=0.0,
                          surface_dispersion_m2=0.0, debit_ajutage_ls=3.0)
        p.amont.actif = False
        sans = simulation.table_acceptation(p, p.bassin)
        p.amont.actif = True
        avec = simulation.table_acceptation(p, p.bassin)
        i = len(sans.durees_min) // 2
        j = len(sans.periodes_retour) - 1
        self.assertGreater(avec.cellules[i][j].volume_requis_m3,
                           sans.cellules[i][j].volume_requis_m3)

    def test_l_evenement_critique_reste_exact_malgre_le_balayage_en_deux_passes(self):
        """Le balayage dégrossi puis affiné doit retrouver le vrai maximum."""
        p = self.projet_avec_amont()
        p.source_pluie = "montana"
        p.bassin = Bassin(volume_total_m3=300.0, volume_sous_ajutage_m3=0.0,
                          surface_dispersion_m2=0.0, debit_ajutage_ls=3.0)
        duree, hauteur = simulation.evenement_critique(p, p.bassin)
        retenu = simulation.volume_requis_m3(p, p.bassin, hauteur, duree)
        src = rainfall.SourcePluie(p.commune_ins, p.periode_retour, p.source_pluie)
        for t in range(10, 20000, 250):
            autre = simulation.volume_requis_m3(p, p.bassin, src.hauteur(t), float(t))
            self.assertLessEqual(autre, retenu * (1 + 1e-6))

    def test_le_volume_minimal_amont_evite_le_debordement(self):
        p = self.projet_avec_amont()
        p.amont.volume_temporisation_m3 = hydro.volume_amont_minimal_m3(p)
        res = hydro.dimensionner(p, SCENARIO_TEMPORISATION)
        apport = simulation.hydrogramme_amont(p, res.hauteur_pluie_mm, res.duree_critique_min)
        # Sans débordement, le débit restitué ne dépasse jamais l'ajutage amont.
        self.assertLessEqual(max(q for _, _, q in apport.segments),
                             p.amont.debit_ajutage_ls + 1e-6)

    def test_la_surface_amont_ne_compte_que_si_elle_est_cochee(self):
        p = self.projet_avec_amont(inclure_bv_dans_ajutage=False)
        self.assertAlmostEqual(p.aire_raccordee_m2, 5000.0)
        p.amont.inclure_bv_dans_ajutage = True
        self.assertAlmostEqual(p.aire_raccordee_m2, 13000.0)
        self.assertAlmostEqual(p.debit_fuite_admissible_ls, 5.0 * 13000.0 / 10000.0)
        p.amont.actif = False
        self.assertAlmostEqual(p.aire_raccordee_m2, 5000.0)

    def test_un_projet_ancien_se_recharge(self):
        """Un enregistrement antérieur ne connaît pas le bassin amont."""
        ancien = {"commune_ins": "81001", "commune_nom": "Arlon", "periode_retour": 25,
                  "surfaces": [], "bassin": {"volume_total_m3": 40.0}}
        p = Projet.from_dict(ancien)
        self.assertFalse(p.amont.actif)
        self.assertAlmostEqual(p.bassin.volume_total_m3, 40.0)


class TestTableAcceptation(_AvecBassin):
    def test_table_acceptation(self):
        p = projet_type()
        b = self.bassin_type(volume_total_m3=90.0)
        p.bassin = b
        table = simulation.table_acceptation(p, b)
        self.assertEqual(len(table.cellules), len(rainfall.QDF_DURATIONS_MIN))
        self.assertEqual(len(table.cellules[0]), len(rainfall.RETURN_PERIODS))
        rp_max = table.periode_retour_max_acceptee()
        self.assertIsNotNone(rp_max)
        # au-dela de la recurrence acceptee, au moins une duree deborde
        idx = list(rainfall.RETURN_PERIODS).index(rp_max)
        if idx + 1 < len(rainfall.RETURN_PERIODS):
            suivant = rainfall.RETURN_PERIODS[idx + 1]
            self.assertTrue(table.durees_critiques(suivant))

    def test_capacite_plus_grande_accepte_plus(self):
        p = projet_type()
        petit = simulation.table_acceptation(p, self.bassin_type(volume_total_m3=40.0))
        grand = simulation.table_acceptation(p, self.bassin_type(volume_total_m3=400.0))
        self.assertLessEqual(petit.periode_retour_max_acceptee() or 0, grand.periode_retour_max_acceptee() or 0)

    def test_evenement_critique(self):
        p = projet_type()
        b = self.bassin_type()
        duree, hauteur = simulation.evenement_critique(p, b)
        v = simulation.volume_necessaire(p, b, hauteur, duree)
        for t in (10.0, 60.0, 600.0, 6000.0):
            h = rainfall.hauteur_montana(BUTGENBACH, 25, t)
            self.assertLessEqual(simulation.volume_necessaire(p, b, h, t), v + 1e-9)


class TestTempsDeVidange(unittest.TestCase):
    """Le temps de vidange annoncé doit être celui mesuré APRÈS la pluie."""

    def _projet(self) -> Projet:
        p = projet_type()
        p.surface_infiltration_m2 = 120.0
        return p

    def _comparer(self, bassin: Bassin, tolerance_relative: float = 0.02) -> None:
        p = self._projet()
        p.bassin = bassin
        duree, hauteur = simulation.evenement_critique(p, bassin)
        res = simulation.simuler(p, bassin, hauteur, duree, n_points=20000)
        annonce = res.temps_vidange_h * 60.0                 # calcul analytique
        mesure = res.temps_retour_a_vide_min                 # fin de pluie -> bassin vide
        self.assertGreater(mesure, 0)
        self.assertAlmostEqual(annonce, mesure, delta=max(tolerance_relative * mesure, 1.0))

    def test_temporisation_seule(self):
        self._comparer(Bassin(volume_total_m3=1e6, surface_dispersion_m2=0.0, debit_ajutage_ls=1.0))

    def test_dispersion_seule(self):
        self._comparer(Bassin(volume_total_m3=1e6, surface_dispersion_m2=120.0, debit_ajutage_ls=0.0))

    def test_temporisation_et_dispersion(self):
        self._comparer(Bassin(volume_total_m3=1e6, surface_dispersion_m2=120.0, debit_ajutage_ls=1.0))

    def test_ajutage_sureleve(self):
        """Deux régimes successifs : infiltration + ajutage, puis infiltration seule."""
        self._comparer(Bassin(volume_total_m3=1e6, volume_sous_ajutage_m3=10.0,
                              surface_dispersion_m2=120.0, debit_ajutage_ls=1.0))

    def test_le_volume_mort_allonge_la_vidange(self):
        p = self._projet()
        sans = Bassin(volume_total_m3=1e6, surface_dispersion_m2=120.0, debit_ajutage_ls=1.0)
        avec = Bassin(volume_total_m3=1e6, volume_sous_ajutage_m3=10.0,
                      surface_dispersion_m2=120.0, debit_ajutage_ls=1.0)
        durees = []
        for bassin in (sans, avec):
            p.bassin = bassin
            duree, hauteur = simulation.evenement_critique(p, bassin)
            durees.append(simulation.simuler(p, bassin, hauteur, duree).temps_vidange_h)
        self.assertGreater(durees[1], durees[0])

    def test_la_vidange_commence_a_la_fin_de_la_pluie(self):
        """Le volume de pointe est atteint en fin de pluie : la vidange part de là."""
        p = self._projet()
        bassin = Bassin(volume_total_m3=1e6, surface_dispersion_m2=120.0, debit_ajutage_ls=1.0)
        p.bassin = bassin
        duree, hauteur = simulation.evenement_critique(p, bassin)
        res = simulation.simuler(p, bassin, hauteur, duree, n_points=20000)
        self.assertAlmostEqual(res.t_volume_max_min, duree, delta=max(0.02 * duree, 1.0))


class TestOrifice(unittest.TestCase):
    def test_torricelli(self):
        q = orifice.debit_orifice_ls(100.0, 1.0, 0.6)
        attendu = 0.6 * math.pi * 0.05 ** 2 * math.sqrt(2 * 9.81 * 1.0) * 1000.0
        self.assertAlmostEqual(q, attendu, places=9)

    def test_aller_retour_diametre_debit(self):
        d = orifice.diametre_requis_mm(2.5, 1.2, 0.6)
        self.assertAlmostEqual(orifice.debit_orifice_ls(d, 1.2, 0.6), 2.5, places=9)

    def test_diametre_commercial_par_defaut(self):
        res = orifice.dimensionner_orifice(1.0, 1.0)
        self.assertLessEqual(res.diametre_commercial_mm, res.diametre_mm)
        self.assertLessEqual(res.debit_commercial_ls, 1.0)

    def test_debit_croissant_avec_la_charge(self):
        debits = [orifice.debit_orifice_ls(80.0, h) for h in (0.2, 0.5, 1.0, 2.0)]
        self.assertEqual(debits, sorted(debits))

    def test_abaque(self):
        ab = orifice.abaque_diametres(1.0)
        self.assertEqual(len(ab), len(orifice.DIAMETRES_COMMERCIAUX_MM))
        self.assertTrue(all(q > 0 for _, _, q in ab))


if __name__ == "__main__":
    unittest.main(verbosity=2)
