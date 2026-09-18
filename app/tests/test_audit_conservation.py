"""Non-régression : conventions de capacité et conservation de la masse.

Tirés de l'audit hydraulique du 18/09/2026, qui a établi que le moteur
d'intégration est juste à mieux que 0,005 % mais que **la sémantique d'une
capacité nulle** était contradictoire : le balayage de dimensionnement y voyait
un réservoir illimité, le routage du réseau un simple passage. Un ouvrage laissé
à 0 m³ stockait donc 306 m³ sans jamais déborder, sous un statut « OK », et
restituait à 5 l/s ce que le réseau transmettait à 90 l/s.

Ces tests fixent la convention : **capacité nulle = aucun stockage**, partout
sauf dans les balayages qui cherchent le volume à construire et le demandent
explicitement.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core import reseau  # noqa: E402
from bassin.core.model import Bassin, SurfaceIncidente  # noqa: E402


def bilan(res):
    """Intègre la chronique : les débits du pas k portent sur l'intervalle (k-1, k]."""
    v_in = v_inf = v_aj = v_deb = 0.0
    for a, b in zip(res.pas, res.pas[1:]):
        f = (b.t_min - a.t_min) * 60.0 / 1000.0
        v_in += b.q_entrant_ls * f
        v_inf += b.q_infiltration_ls * f
        v_aj += b.q_ajutage_ls * f
        v_deb += b.q_debordement_ls * f
    return v_in, v_inf, v_aj, v_deb, res.pas[-1].volume_m3


def systeme(v_cap_amont):
    """Deux ouvrages en série, le volume de l'amont étant le paramètre d'essai."""
    s = reseau.Systeme(commune_ins="62063", commune_nom="Liège",
                       periode_retour=25, source_pluie="montana")
    amont = reseau.ouvrage_neuf(s, "BO1")
    amont.id, amont.aval_id = "bo1", "bo2"
    amont.etude.k_infiltration_ms = 1e-5
    amont.etude.surface_infiltration_m2 = 150.0
    amont.etude.debit_ajutage_ls = 5.0
    amont.etude.bassin = Bassin(volume_total_m3=v_cap_amont,
                                surface_dispersion_m2=150.0, debit_ajutage_ls=5.0)
    s.ouvrages.append(amont)
    aval = reseau.ouvrage_neuf(s, "BO2")
    aval.id, aval.aval_id = "bo2", ""
    aval.etude.k_infiltration_ms = 1e-5
    aval.etude.surface_infiltration_m2 = 200.0
    aval.etude.debit_ajutage_ls = 8.0
    aval.etude.bassin = Bassin(volume_total_m3=1200.0,
                               surface_dispersion_m2=200.0, debit_ajutage_ls=8.0)
    s.ouvrages.append(aval)
    for identifiant, aire, coef in (("bo1", 10000.0, 0.95), ("bo2", 5000.0, 0.60)):
        versant = reseau.versant_neuf(s, "BV " + identifiant, identifiant)
        versant.id = "bv" + identifiant
        versant.surfaces = [SurfaceIncidente("s", coef, aire)]
        s.bassins_versants.append(versant)
    s.ouvrage_courant = "bo1"
    s.synchroniser()
    return s


class TestConservation(unittest.TestCase):

    def test_bilan_de_masse_par_ouvrage(self):
        """Ce qui entre ressort, s'infiltre, déborde ou reste stocké. Rien d'autre."""
        for v_cap in (0.0, 150.0, 900.0):
            for duree in (30.0, 60.0, 180.0, 1440.0):
                sim = reseau.simuler(systeme(v_cap), duree, 25, n_points=2000)
                for ouvrage, res in sim.resultats:
                    v_in, v_inf, v_aj, v_deb, v_fin = bilan(res)
                    with self.subTest(v_cap=v_cap, duree=duree, ouvrage=ouvrage.nom):
                        self.assertAlmostEqual(v_in, v_inf + v_aj + v_deb + v_fin,
                                               delta=max(v_in * 5e-3, 1e-6))
                        self.assertAlmostEqual(
                            v_in, res.volume_ruissele_m3 + res.volume_amont_m3,
                            delta=max(v_in * 5e-3, 1e-6))

    def test_la_chronique_amont_est_ce_que_l_aval_recoit(self):
        """La liaison entre ouvrages ne crée ni ne perd de débit — y compris V = 0."""
        for v_cap in (0.0, 150.0, 900.0):
            sim = reseau.simuler(systeme(v_cap), 60.0, 25, n_points=2000)
            _v_in, _v_inf, v_aj, v_deb, _v_fin = bilan(sim.resultat("bo1"))
            with self.subTest(v_cap=v_cap):
                self.assertAlmostEqual(sim.resultat("bo2").volume_amont_m3, v_aj + v_deb,
                                       delta=max((v_aj + v_deb) * 5e-3, 1e-6))

    def test_ouvrage_sans_volume_ne_stocke_rien(self):
        """Capacité nulle = ouvrage de transit, jamais réservoir infini."""
        sim = reseau.simuler(systeme(0.0), 60.0, 25, n_points=2000)
        amont = sim.resultat("bo1")
        self.assertAlmostEqual(amont.volume_max_m3, 0.0, delta=1e-6)
        self.assertEqual(amont.statut, "NON ENCODE")
        self.assertNotEqual(sim.statut, "OK")

    def test_la_simulation_et_le_routage_disent_la_meme_chose(self):
        """Les deux chemins d'intégration décrivent le même ouvrage.

        C'est l'invariant que la double convention violait : la chronique de
        l'ouvrage restituait 324,63 m³ à 5 l/s quand le routage en transmettait
        autant à 90 l/s, et sur d'autres configurations les volumes eux-mêmes
        s'écartaient de 74 à 100 %.
        """
        for v_cap in (0.0, 150.0, 900.0):
            for duree in (30.0, 60.0, 180.0):
                s = systeme(v_cap)
                sim = reseau.simuler(s, duree, 25, n_points=2000)
                noeuds = s.noeuds()
                for ouvrage, res in sim.resultats:
                    _v_in, _v_inf, v_aj, v_deb, _v_fin = bilan(res)
                    attendu = v_aj + (v_deb if ouvrage.surverse_vers_aval else 0.0)
                    routage = reseau.restitution(noeuds[ouvrage.id], sim.hauteur_mm,
                                                 duree).volume_m3
                    with self.subTest(v_cap=v_cap, duree=duree, ouvrage=ouvrage.nom):
                        self.assertAlmostEqual(routage, attendu,
                                               delta=max(attendu * 5e-3, 0.05))

    def test_ouvrage_sans_volume_signale_en_anomalie(self):
        self.assertTrue(any("volume" in m.lower() for m in systeme(0.0).anomalies()))

    def test_ouvrage_sans_exutoire_nest_pas_ok(self):
        """Vidange infinie : le statut ne peut pas être « OK »."""
        s = systeme(900.0)
        aval = s.ouvrage("bo2")
        aval.etude.debit_ajutage_ls = 0.0
        aval.etude.surface_infiltration_m2 = 0.0
        aval.etude.bassin.debit_ajutage_ls = 0.0
        aval.etude.bassin.surface_dispersion_m2 = 0.0
        s.synchroniser()
        res = reseau.simuler(s, 60.0, 25, n_points=1000).resultat("bo2")
        self.assertEqual(res.temps_vidange_h, float("inf"))
        self.assertNotEqual(res.statut, "OK")

    def test_une_vidange_trop_longue_n_est_pas_ok(self):
        """Le délai admis est porté par le résultat, pas seulement par la vue."""
        s = systeme(900.0)
        aval = s.ouvrage("bo2")
        aval.etude.bassin.volume_total_m3 = 3000.0
        aval.etude.bassin.volume_sous_ajutage_m3 = 60.0
        aval.etude.bassin.debit_ajutage_ls = 0.0
        aval.etude.bassin.surface_dispersion_m2 = 0.0
        aval.etude.debit_ajutage_ls = 0.0
        aval.etude.surface_infiltration_m2 = 0.0
        s.synchroniser()
        res = reseau.simuler(s, 60.0, 25, n_points=1000).resultat("bo2")
        self.assertGreater(res.temps_vidange_h, res.temps_vidange_max_h)
        self.assertEqual(res.statut, "NON CONFORME")

    def test_la_surface_active_annoncee_est_celle_qui_ruisselle(self):
        """Un bassin versant orphelin ne ruisselle nulle part : il ne se compte pas."""
        s = systeme(900.0)
        orphelin = reseau.versant_neuf(s, "Orphelin", "")
        orphelin.id = "bvorphelin"
        orphelin.bassin_id = ""
        orphelin.surfaces = [SurfaceIncidente("s", 1.0, 27000.0)]
        s.bassins_versants.append(orphelin)
        s.synchroniser()
        sim = reseau.simuler_evenement_critique(s)
        self.assertAlmostEqual(s.aire_ponderee_routee_m2, 12500.0, delta=1e-6)
        self.assertAlmostEqual(s.aire_ponderee_m2, 39500.0, delta=1e-6)
        # Le volume ruisselé simulé ne porte que sur la surface routée.
        attendu = sim.hauteur_mm * s.aire_ponderee_routee_m2 / 1000.0
        self.assertAlmostEqual(sim.volume_ruissele_m3, attendu, delta=attendu * 1e-6)


if __name__ == "__main__":
    unittest.main()
