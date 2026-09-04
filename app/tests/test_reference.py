"""Confrontation du moteur à un modèle de référence écrit indépendamment.

Le moteur intègre l'événement par bonds exacts d'un seuil au suivant : c'est
rapide et sans erreur de discrétisation, mais toute la justesse tient au
raisonnement. Trois défauts de la même famille s'y sont succédé — une formule
fermée restée valable pour le cas simple et devenue fausse dès qu'un ajutage
surélevé ou un bassin amont changeait les hypothèses.

Ce module oppose donc au moteur un modèle naïf, écrit à partir de la physique
seule : de tout petits pas de temps, aucune formule fermée, aucun code partagé
avec l'application. Il est bien trop lent pour l'application, mais il ne peut
pas se tromper de la même façon — et c'est tout l'intérêt.
"""

import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core import hydro, rainfall, simulation  # noqa: E402
from bassin.core.model import (  # noqa: E402
    Bassin, BassinAmont, Projet,
    SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL, SCENARIO_TEMPORISATION,
)

SCENARIOS = (SCENARIO_TEMPORISATION, SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL)


# ---------------------------------------------------------------------------
# Modèle de référence : petits pas de temps, physique nue
# ---------------------------------------------------------------------------
def _restitution_amont(hauteur_mm, duree_min, s_pond, q_inf, q_aj, v_cap, n, dt):
    """Débit rendu à l'aval par le bassin amont, pas par pas [l/s].

    Ce qu'il infiltre est perdu pour l'aval ; ce qu'il déverse au trop-plein s'y
    ajoute sans laminage.
    """
    v_in = hauteur_mm * s_pond / 1000.0
    q_in = v_in * 1000.0 / (duree_min * 60.0) if duree_min > 0 else 0.0
    v = 0.0
    sortie = []
    for i in range(n):
        qi = q_in if i * dt < duree_min else 0.0
        dispo = v * 1000.0 / (dt * 60.0) + qi
        q_i = min(q_inf, dispo)
        q_a = min(q_aj, max(dispo - q_i, 0.0))
        v += (qi - q_i - q_a) * dt * 60.0 / 1000.0
        q_deb = 0.0
        if v_cap > 0 and v > v_cap:
            q_deb = (v - v_cap) * 1000.0 / (dt * 60.0)
            v = v_cap
        v = max(v, 0.0)
        sortie.append(q_a + q_deb)
    return sortie


def simuler_naivement(hauteur_mm, duree_min, s_pond, q_inf, q_aj, v_sous, v_cap,
                      horizon_min, amont=None, dt=0.05):
    """Renvoie (volume maximal, instant de retour à vide, volume débordé).

    Seule subtilité, et c'est celle qui a coûté cher : **l'ajutage surélevé ne
    débite que ce qui se tient au-dessus de son axe**. Sous l'axe l'orifice est
    hors d'eau, il ne laisse rien passer, et seul le fond infiltre.
    """
    n = int(horizon_min / dt) + 1
    v_in = hauteur_mm * s_pond / 1000.0
    q_direct = v_in * 1000.0 / (duree_min * 60.0) if duree_min > 0 else 0.0
    apport = (_restitution_amont(hauteur_mm, duree_min, *amont, n, dt)
              if amont else [0.0] * n)

    v = 0.0
    v_max = 0.0
    v_deborde = 0.0
    t_vide = None
    for i in range(n):
        t = i * dt
        qi = (q_direct if t < duree_min else 0.0) + apport[i]
        dispo = v * 1000.0 / (dt * 60.0) + qi
        q_i = min(q_inf, dispo)
        v_apres = v + (qi - q_i) * dt * 60.0 / 1000.0
        q_a = min(q_aj, max(v_apres - v_sous, 0.0) * 1000.0 / (dt * 60.0))
        v = max(v_apres - q_a * dt * 60.0 / 1000.0, 0.0)
        if v_cap > 0 and v > v_cap:
            v_deborde += v - v_cap
            v = v_cap
        v_max = max(v_max, v)
        if t_vide is None and t > duree_min and v <= 1e-6:
            t_vide = t + dt
    return v_max, t_vide, v_deborde


def _amont_de(projet):
    if not projet.amont.actif:
        return None
    return (projet.amont.aire_ponderee_m2,
            projet.amont.debit_infiltration_ls(projet.coef_securite_infiltration),
            projet.amont.debit_ajutage_ls,
            max(projet.amont.volume_temporisation_m3, 0.0))


# ---------------------------------------------------------------------------
class ComparaisonAuModeleNaif(unittest.TestCase):
    """Chaque grandeur publiée doit résister au modèle de référence."""

    #: Tolérances : le modèle naïf a sa propre erreur de discrétisation.
    TOL_VOLUME = 0.005      # 0,5 %
    TOL_VIDANGE = 0.02      # 2 %

    def _confronter(self, projet, scenarios=SCENARIOS):
        """Compare dimensionnement et simulation au modèle naïf."""
        if projet.aire_ponderee_m2 <= 0:
            return 0
        amont = _amont_de(projet)
        controles = 0
        for scenario in scenarios:
            res = hydro.dimensionner(projet, scenario, avec_minima=False)
            duree, hauteur = res.duree_critique_min, res.hauteur_pluie_mm
            if duree <= 0 or duree > 20000 or res.volume_m3 <= 1.0:
                continue
            if res.temps_vidange_h == float("inf") or res.temps_vidange_h > 400:
                continue
            v_sous = res.volume_sous_ajutage_m3 if scenario == SCENARIO_SEUIL else 0.0
            horizon = duree + res.temps_vidange_h * 60.0 * 1.5 + 1000.0
            v_ref, t_vide, _ = simuler_naivement(
                hauteur, duree, projet.aire_ponderee_m2, res.debit_infiltration_ls,
                res.debit_ajutage_ls, v_sous, 0.0, horizon, amont)
            with self.subTest(scenario=scenario, grandeur="volume"):
                self.assertLessEqual(abs(res.volume_m3 - v_ref) / max(v_ref, 1.0),
                                     self.TOL_VOLUME,
                                     f"volume {scenario} : {res.volume_m3:.2f} vs {v_ref:.2f}")
            if t_vide is not None:
                ref_h = (t_vide - duree) / 60.0
                with self.subTest(scenario=scenario, grandeur="vidange"):
                    self.assertLessEqual(
                        abs(res.temps_vidange_h - ref_h) / max(ref_h, 0.1), self.TOL_VIDANGE,
                        f"vidange {scenario} : {res.temps_vidange_h:.3f} h vs {ref_h:.3f} h")
            controles += 1

        sim = simulation.simuler_evenement_critique(projet, projet.bassin)
        if (0 < sim.duree_pluie_min <= 20000 and sim.temps_vidange_h != float("inf")
                and sim.temps_vidange_h <= 400 and sim.volume_max_m3 > 1.0):
            horizon = sim.duree_pluie_min + sim.temps_vidange_h * 60.0 * 1.5 + 1000.0
            v_ref, t_vide, deb_ref = simuler_naivement(
                sim.hauteur_pluie_mm, sim.duree_pluie_min, projet.aire_ponderee_m2,
                sim.q_infiltration_ls, sim.q_ajutage_ls, projet.bassin.volume_sous_ajutage_m3,
                projet.bassin.volume_total_m3, horizon, amont)
            with self.subTest(grandeur="V max simulation"):
                self.assertLessEqual(abs(sim.volume_max_m3 - v_ref) / max(v_ref, 1.0),
                                     self.TOL_VOLUME)
            with self.subTest(grandeur="débordement"):
                self.assertLessEqual(
                    abs(sim.volume_debordement_m3 - deb_ref) / max(deb_ref, 1.0),
                    self.TOL_VOLUME)
            if t_vide is not None:
                ref_h = (t_vide - sim.duree_pluie_min) / 60.0
                with self.subTest(grandeur="vidange simulation"):
                    self.assertLessEqual(
                        abs(sim.temps_vidange_h - ref_h) / max(ref_h, 0.1), self.TOL_VIDANGE)
            controles += 1
        return controles

    def _confronter_courbe(self, projet, scenario, durees):
        """Confronte la courbe « volume à maîtriser = f(durée) », pas seulement sa pointe.

        Le « ressaut » se logeait précisément là : la pointe retenue restait
        juste, mais la courbe tracée s'effondrait à zéro sur toute une plage de
        durées longues, là où le bassin garde en réalité son volume mort.
        """
        res = hydro.dimensionner(projet, scenario, avec_minima=False)
        v_sous = projet.bassin.volume_sous_ajutage_m3 if scenario == SCENARIO_SEUIL else 0.0
        amont = _amont_de(projet)
        source = rainfall.SourcePluie(projet.commune_ins, projet.periode_retour,
                                      projet.source_pluie)
        controles = 0
        for duree in durees:
            hauteur = source.hauteur(duree)
            attendu = hydro.volume_pointe_amont(
                projet, duree, hauteur, res.debit_infiltration_ls, res.debit_ajutage_ls, v_sous
            ) if projet.amont.actif else hydro.volume_pointe_seuil(
                hauteur * projet.aire_ponderee_m2 / 1000.0, duree,
                res.debit_infiltration_ls, res.debit_ajutage_ls, v_sous)
            pas = min(0.25, max(duree / 4000.0, 1e-3))
            v_ref, _, _ = simuler_naivement(
                hauteur, duree, projet.aire_ponderee_m2, res.debit_infiltration_ls,
                res.debit_ajutage_ls, v_sous, 0.0, duree + pas, amont, dt=pas)
            with self.subTest(scenario=scenario, duree=duree):
                self.assertLessEqual(
                    abs(attendu - v_ref) / max(v_ref, 1.0), self.TOL_VOLUME,
                    f"{duree:.0f} min : courbe {attendu:.2f} m³ vs référence {v_ref:.2f} m³")
            controles += 1
        return controles

    def test_la_courbe_entiere_du_scenario_sureleve(self):
        """Toute la courbe, pas seulement sa pointe : c'est là qu'était le ressaut."""
        durees = [30.0, 120.0, 360.0, 720.0, 1440.0, 2880.0, 4320.0, 5760.0, 8640.0]
        for cas in ({"v_sous": 50.0}, {"v_sous": 100.0, "q_aj": 6.9, "s_inf": 250.0},
                    {"v_sous": 50.0, "ins": "61003", "rp": 25}):
            projet = self._projet(**cas)
            with self.subTest(**cas):
                self.assertGreater(
                    self._confronter_courbe(projet, SCENARIO_SEUIL, durees), 0)

    def test_la_courbe_entiere_avec_un_amont(self):
        durees = [60.0, 300.0, 720.0, 1440.0, 2880.0, 5760.0]
        projet = self._projet(v_sous=100.0, amont=True, v_am=409.1, q_aj=17.5,
                              s_inf=1000.0, k=5e-6, cs=1.5, ins="21006", rp=50)
        self.assertGreater(self._confronter_courbe(projet, SCENARIO_SEUIL, durees), 0)

    # -- cas fixes, toujours joués -----------------------------------------
    def _projet(self, **kw):
        p = Projet(commune_ins=kw.get("ins", "63013"), periode_retour=kw.get("rp", 25),
                   source_pluie=kw.get("source", "montana"),
                   surfaces=Projet.surfaces_par_defaut())
        p.surfaces[7].aire_m2 = kw.get("aire", 20000.0)
        p.surface_reference_m2 = p.aire_totale_m2 * 2
        p.k_infiltration_ms = kw.get("k", 1e-5)
        p.coef_securite_infiltration = kw.get("cs", 2.0)
        p.surface_infiltration_m2 = kw.get("s_inf", 250.0)
        p.fixer_ajutage_absolu(kw.get("q_aj", 12.0))
        p.bassin = Bassin(volume_total_m3=kw.get("v_cap", 1000.0),
                          volume_sous_ajutage_m3=kw.get("v_sous", 0.0),
                          surface_dispersion_m2=p.surface_infiltration_m2,
                          debit_ajutage_ls=p.debit_ajutage_ls)
        if kw.get("amont"):
            p.amont = BassinAmont(actif=True, surface_bv_m2=kw.get("bv", 10000.0),
                                  coef_ruissellement=0.9,
                                  debit_ajutage_ls=kw.get("q_am", 5.0),
                                  surface_dispersion_m2=0.0, k_infiltration_ms=1e-5,
                                  volume_temporisation_m3=kw.get("v_am", 200.0),
                                  inclure_bv_dans_ajutage=True)
        return p

    def test_cas_courant(self):
        self.assertGreater(self._confronter(self._projet()), 0)

    def test_ajutage_sureleve(self):
        """L'orifice ne débite pas sous son axe : c'était le « ressaut »."""
        self.assertGreater(self._confronter(self._projet(v_sous=50.0)), 0)

    def test_bassin_amont(self):
        self.assertGreater(self._confronter(self._projet(amont=True)), 0)

    def test_amont_et_ajutage_sureleve(self):
        """Les deux pièges à la fois : le cas rapporté par l'utilisateur."""
        self.assertGreater(
            self._confronter(self._projet(v_sous=100.0, amont=True, v_am=409.1,
                                          q_aj=17.5, s_inf=1000.0, k=5e-6, cs=1.5,
                                          ins="21006", rp=50)), 0)

    def test_amont_sous_dimensionne_qui_surverse(self):
        self.assertGreater(self._confronter(self._projet(v_sous=50.0, amont=True, v_am=0.0)), 0)

    def test_amont_plus_gourmand_que_l_infiltration(self):
        """L'apport dépasse le fond : le niveau se bloque sur l'axe de l'ajutage."""
        p = self._projet(v_sous=100.0, amont=True, q_am=20.0, v_am=3000.0, s_inf=250.0)
        self.assertGreater(self._confronter(p), 0)

    def test_sans_dispersion(self):
        self.assertGreater(self._confronter(self._projet(s_inf=0.0, v_sous=50.0)), 0)

    def test_source_qdf(self):
        self.assertGreater(self._confronter(self._projet(ins="61003", source="qdf",
                                                         v_sous=50.0, amont=True)), 0)

    # -- campagne aléatoire, sur demande -----------------------------------
    @unittest.skipUnless(os.environ.get("HYDROBASSIN_CAMPAGNE_REFERENCE"),
                         "campagne aléatoire longue (variable HYDROBASSIN_CAMPAGNE_REFERENCE)")
    def test_campagne_aleatoire(self):
        rnd = random.Random(2026)
        communes = ["63013", "61003", "62063", "52011", "92003", "21006", "81001"]
        controles = 0
        for _ in range(int(os.environ.get("HYDROBASSIN_CAMPAGNE_REFERENCE", "40"))):
            p = Projet(commune_ins=rnd.choice(communes),
                       periode_retour=rnd.choice([20, 25, 50, 100]),
                       source_pluie=rnd.choice(["montana", "qdf"]),
                       surfaces=Projet.surfaces_par_defaut())
            for i in rnd.sample(range(8), rnd.randint(1, 3)):
                p.surfaces[i].aire_m2 = rnd.choice([2000.0, 10000.0, 25000.0])
            p.surface_reference_m2 = p.aire_totale_m2 * 2
            p.k_infiltration_ms = rnd.choice([1e-6, 5e-6, 1e-5])
            p.coef_securite_infiltration = rnd.choice([1.5, 2.0])
            p.surface_infiltration_m2 = rnd.choice([0.0, 250.0, 1000.0])
            p.fixer_ajutage_absolu(rnd.choice([2.0, 5.0, 17.5]))
            p.bassin = Bassin(volume_total_m3=rnd.choice([300.0, 1000.0, 5000.0]),
                              volume_sous_ajutage_m3=rnd.choice([0.0, 0.0, 50.0, 100.0]),
                              surface_dispersion_m2=p.surface_infiltration_m2,
                              debit_ajutage_ls=p.debit_ajutage_ls)
            if rnd.random() < 0.6:
                p.amont = BassinAmont(
                    actif=True, surface_bv_m2=rnd.choice([5000.0, 10000.0, 40000.0]),
                    coef_ruissellement=rnd.choice([0.3, 0.9]),
                    debit_ajutage_ls=rnd.choice([1.0, 5.0, 20.0]),
                    surface_dispersion_m2=rnd.choice([0.0, 200.0]), k_infiltration_ms=1e-5,
                    volume_temporisation_m3=rnd.choice([0.0, 200.0, 2000.0]),
                    inclure_bv_dans_ajutage=rnd.choice([True, False]))
            controles += self._confronter(p)
        self.assertGreater(controles, 50)


if __name__ == "__main__":
    unittest.main()
