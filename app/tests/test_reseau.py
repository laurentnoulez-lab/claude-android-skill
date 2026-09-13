"""Réseau de bassins : topologie, routage et confrontation au modèle naïf.

Comme pour le bassin isolé, le moteur intègre par bonds exacts d'un seuil au
suivant. Ce module lui oppose un **modèle de référence écrit indépendamment** :
un réseau simulé à tout petits pas de temps, sans aucune formule fermée et sans
code partagé avec l'application.

Il vérifie aussi la propriété qui garantit l'absence de régression : un réseau
réduit à un seul ouvrage doit rendre **exactement** ce que rendait le moteur du
bassin isolé, et un réseau à deux ouvrages exactement ce que rendait le panneau
« bassin d'orage amont ».
"""

import os
import random
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from bassin.core import hydro, rainfall, reseau, simulation  # noqa: E402
from bassin.core.model import (  # noqa: E402
    Bassin, BassinAmont, Projet, SurfaceIncidente,
    SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL, SCENARIO_TEMPORISATION,
)

SCENARIOS = (SCENARIO_TEMPORISATION, SCENARIO_DISPERSION, SCENARIO_MIXTE, SCENARIO_SEUIL)


# ---------------------------------------------------------------------------
# Modèle de référence : réseau simulé à petits pas, physique nue
# ---------------------------------------------------------------------------
def _pas_a_pas(q_entrant, q_inf, q_aj, v_sous, v_cap, surverse_vers_aval, dt):
    """Fait évoluer un ouvrage pas par pas et renvoie (sortie, v_max, v_débordé, index vide).

    ``v_cap`` vaut ``None`` pour une capacité illimitée (c'est le cas quand on
    cherche le volume à mettre en œuvre) ; un nombre sinon, zéro compris — un
    ouvrage sans volume ne retient rien.

    L'ajutage surélevé ne débite que ce qui se tient **au-dessus** de son axe :
    sous l'axe l'orifice est hors d'eau et seul le fond infiltre.
    """
    v = 0.0
    v_max = 0.0
    v_deborde = 0.0
    sortie = []
    for qi in q_entrant:
        dispo = v * 1000.0 / (dt * 60.0) + qi
        q_i = min(q_inf, dispo)
        v_apres = v + (qi - q_i) * dt * 60.0 / 1000.0
        q_a = min(q_aj, max(v_apres - v_sous, 0.0) * 1000.0 / (dt * 60.0))
        v = max(v_apres - q_a * dt * 60.0 / 1000.0, 0.0)
        q_deb = 0.0
        if v_cap is not None and v > v_cap:
            q_deb = (v - v_cap) * 1000.0 / (dt * 60.0)
            v_deborde += v - v_cap
            v = v_cap
        v_max = max(v_max, v)
        sortie.append(q_a + (q_deb if surverse_vers_aval else 0.0))
    return sortie, v_max, v_deborde


def reseau_naif(noeuds, hauteur_mm, duree_min, horizon_min, dt=0.05):
    """Simule tout le réseau pas par pas et renvoie ``{clé: (v_max, v_débordé)}``.

    ``noeuds`` est une liste de dictionnaires, **déjà triée de l'amont vers
    l'aval** : ``cle``, ``s_pond``, ``q_inf``, ``q_aj``, ``v_sous``, ``v_cap``
    (``None`` = illimité), ``amonts`` (liste de clés), ``surverse_vers_aval``.
    """
    n = int(horizon_min / dt) + 1
    sorties = {}
    bilan = {}
    for noeud in noeuds:
        q_direct = (hauteur_mm * noeud["s_pond"] / (duree_min * 60.0)) if duree_min > 0 else 0.0
        entrant = []
        for i in range(n):
            q = q_direct if i * dt < duree_min else 0.0
            for amont in noeud["amonts"]:
                q += sorties[amont][i]
            entrant.append(q)
        sortie, v_max, v_deborde = _pas_a_pas(
            entrant, noeud["q_inf"], noeud["q_aj"], noeud["v_sous"], noeud["v_cap"],
            noeud.get("surverse_vers_aval", True), dt)
        sorties[noeud["cle"]] = sortie
        bilan[noeud["cle"]] = (v_max, v_deborde)
    return bilan, sorties


# ---------------------------------------------------------------------------
def systeme_essai(volumes=(200.0, 0.0), surverses=(False, False), aires=(10000.0, 20000.0),
                  ajutages=(5.0, 12.0), s_inf=(0.0, 250.0), ins="63013", rp=25,
                  v_sous=(0.0, 0.0), source="montana"):
    """Réseau « amont → aval → exutoire » avec des valeurs maîtrisées."""
    s = reseau.Systeme(commune_ins=ins, commune_nom="Essai", periode_retour=rp,
                       source_pluie=source)
    aval = reseau.ouvrage_neuf(s, "Aval")
    s.ouvrages.append(aval)
    amont = reseau.ouvrage_neuf(s, "Amont")
    amont.aval_id = aval.id
    amont.surverse_vers_milieu_naturel = surverses[0]
    s.ouvrages.append(amont)
    s.ouvrage_courant = aval.id
    for ouvrage, aire, volume, q_aj, surface, vs in zip(
            (amont, aval), aires, volumes, ajutages, s_inf, v_sous):
        versant = reseau.versant_neuf(s, f"BV {ouvrage.nom}", ouvrage.id)
        versant.surfaces = [SurfaceIncidente("Imperméable", 1.0, aire)]
        s.bassins_versants.append(versant)
        ouvrage.etude.k_infiltration_ms = 1e-5
        ouvrage.etude.surface_infiltration_m2 = surface
        ouvrage.etude.fixer_ajutage_absolu(q_aj)
        ouvrage.etude.bassin = Bassin(volume_total_m3=volume, volume_sous_ajutage_m3=vs,
                                      surface_dispersion_m2=surface, debit_ajutage_ls=q_aj)
    s.synchroniser()
    return s, amont, aval


def _description(systeme):
    """Traduit le système en description pour le modèle naïf (amont → aval)."""
    noeuds = []
    for o in systeme.ordre_amont_aval():
        noeuds.append({
            "cle": o.id,
            "s_pond": systeme.aire_ponderee_de(o.id),
            "q_inf": o.debit_infiltration_ls(),
            "q_aj": o.etude.bassin.debit_ajutage_ls,
            "v_sous": o.etude.bassin.volume_sous_ajutage_m3,
            "v_cap": o.etude.bassin.volume_total_m3,
            "amonts": [a.id for a in systeme.amonts_directs(o.id)],
            "surverse_vers_aval": o.surverse_vers_aval,
        })
    return noeuds


class ReseauContreModeleNaif(unittest.TestCase):
    """Le routage du réseau doit résister à une simulation pas à pas indépendante."""

    TOL = 0.01   # 1 % — le modèle naïf a sa propre erreur de discrétisation

    def _confronter_dimensionnement(self, systeme, ouvrage, scenario=SCENARIO_MIXTE):
        """Le volume à mettre en œuvre d'un ouvrage, vu par le modèle naïf."""
        systeme.synchroniser()
        res = hydro.dimensionner(ouvrage.etude, scenario, avec_minima=False)
        self.assertGreater(res.volume_m3, 1.0)
        duree, hauteur = res.duree_critique_min, res.hauteur_pluie_mm
        noeuds = _description(systeme)
        for noeud in noeuds:
            if noeud["cle"] == ouvrage.id:
                # Le dimensionnement cherche le volume : l'ouvrage étudié n'est
                # pas encore borné, et ses débits sont ceux du scénario.
                noeud["v_cap"] = None
                noeud["q_inf"] = res.debit_infiltration_ls
                noeud["q_aj"] = res.debit_ajutage_ls
                noeud["v_sous"] = (res.volume_sous_ajutage_m3
                                   if scenario == SCENARIO_SEUIL else 0.0)
        # Un ouvrage sans aucun débit de sortie ne se vide jamais : le modèle
        # naïf n'a alors pas d'horizon fini et la comparaison n'a pas de sens.
        if res.temps_vidange_h == float("inf") or res.temps_vidange_h > 400:
            self.skipTest("ouvrage sans exutoire : pas d'horizon fini à simuler")
        horizon = duree + max(res.temps_vidange_h, 1.0) * 60.0 * 1.5 + 1000.0
        bilan, _ = reseau_naif(noeuds, hauteur, duree, horizon)
        attendu = bilan[ouvrage.id][0]
        self.assertLessEqual(abs(res.volume_m3 - attendu) / max(attendu, 1.0), self.TOL,
                             f"volume {ouvrage.nom} : {res.volume_m3:.2f} vs {attendu:.2f}")
        return res

    def test_deux_ouvrages_en_serie(self):
        systeme, _amont, aval = systeme_essai()
        self._confronter_dimensionnement(systeme, aval)

    def test_amont_sous_dimensionne_qui_surverse_vers_l_aval(self):
        systeme, _amont, aval = systeme_essai(volumes=(0.0, 0.0))
        self._confronter_dimensionnement(systeme, aval)

    def test_surverse_dirigee_vers_le_milieu_naturel(self):
        """Ce que l'amont déverse au trop-plein quitte le réseau : l'aval en profite."""
        vers_aval, _a1, aval1 = systeme_essai(volumes=(10.0, 0.0), surverses=(False, False))
        vers_nature, _a2, aval2 = systeme_essai(volumes=(10.0, 0.0), surverses=(True, False))
        res_aval = self._confronter_dimensionnement(vers_aval, aval1)
        res_nature = self._confronter_dimensionnement(vers_nature, aval2)
        self.assertLess(res_nature.volume_m3, res_aval.volume_m3,
                        "diriger la surverse vers le milieu naturel doit soulager l'aval")

    def test_ajutage_sureleve_dans_le_reseau(self):
        systeme, _amont, aval = systeme_essai(volumes=(300.0, 0.0), v_sous=(0.0, 80.0),
                                              s_inf=(0.0, 400.0))
        self._confronter_dimensionnement(systeme, aval, SCENARIO_SEUIL)

    def test_deux_bassins_versants_sur_le_meme_ouvrage(self):
        systeme, _amont, aval = systeme_essai()
        second = reseau.versant_neuf(systeme, "BV secondaire", aval.id)
        second.surfaces = [SurfaceIncidente("Prairie", 0.15, 30000.0)]
        systeme.bassins_versants.append(second)
        systeme.synchroniser()
        self.assertAlmostEqual(systeme.aire_ponderee_de(aval.id), 20000.0 + 4500.0, places=6)
        self._confronter_dimensionnement(systeme, aval)

    def test_trois_ouvrages_en_cascade(self):
        systeme, amont, aval = systeme_essai(volumes=(150.0, 0.0))
        tete = reseau.ouvrage_neuf(systeme, "Tête")
        tete.aval_id = amont.id
        tete.etude.k_infiltration_ms = 1e-5
        tete.etude.fixer_ajutage_absolu(3.0)
        tete.etude.bassin = Bassin(volume_total_m3=120.0, debit_ajutage_ls=3.0)
        systeme.ouvrages.append(tete)
        versant = reseau.versant_neuf(systeme, "BV Tête", tete.id)
        versant.surfaces = [SurfaceIncidente("Imperméable", 1.0, 8000.0)]
        systeme.bassins_versants.append(versant)
        systeme.synchroniser()
        self.assertEqual([o.nom for o in systeme.ordre_amont_aval()],
                         ["Tête", "Amont", "Aval"])
        self._confronter_dimensionnement(systeme, aval)

    def test_deux_ouvrages_amont_sur_le_meme_aval(self):
        """Deux apports simultanés s'additionnent palier par palier."""
        systeme, _amont, aval = systeme_essai(volumes=(150.0, 0.0))
        second = reseau.ouvrage_neuf(systeme, "Amont bis")
        second.aval_id = aval.id
        second.etude.k_infiltration_ms = 1e-5
        second.etude.fixer_ajutage_absolu(2.0)
        second.etude.bassin = Bassin(volume_total_m3=60.0, debit_ajutage_ls=2.0)
        systeme.ouvrages.append(second)
        versant = reseau.versant_neuf(systeme, "BV Amont bis", second.id)
        versant.surfaces = [SurfaceIncidente("Imperméable", 1.0, 12000.0)]
        systeme.bassins_versants.append(versant)
        systeme.synchroniser()
        self._confronter_dimensionnement(systeme, aval)

    def test_la_simulation_du_systeme_suit_le_modele_naif(self):
        systeme, _amont, aval = systeme_essai(volumes=(200.0, 900.0))
        sim = reseau.simuler_evenement_critique(systeme)
        noeuds = _description(systeme)
        horizon = sim.duree_min + max(sim.temps_vidange_max_h, 1.0) * 60.0 * 1.5 + 1000.0
        bilan, _ = reseau_naif(noeuds, sim.hauteur_mm, sim.duree_min, horizon)
        for ouvrage, res in sim.resultats:
            v_ref, deb_ref = bilan[ouvrage.id]
            with self.subTest(ouvrage=ouvrage.nom, grandeur="volume"):
                self.assertLessEqual(abs(res.volume_max_m3 - v_ref) / max(v_ref, 1.0), self.TOL)
            with self.subTest(ouvrage=ouvrage.nom, grandeur="débordement"):
                self.assertLessEqual(
                    abs(res.volume_debordement_m3 - deb_ref) / max(deb_ref, 1.0), self.TOL)

    @unittest.skipUnless(os.environ.get("HYDROBASSIN_CAMPAGNE_RESEAU"),
                         "campagne aléatoire longue (variable HYDROBASSIN_CAMPAGNE_RESEAU)")
    def test_campagne_aleatoire(self):
        rnd = random.Random(2027)
        communes = ["63013", "61003", "21006", "52011"]
        controles = 0
        for _ in range(int(os.environ.get("HYDROBASSIN_CAMPAGNE_RESEAU", "20"))):
            systeme, _amont, aval = systeme_essai(
                volumes=(rnd.choice([0.0, 150.0, 800.0]), 0.0),
                surverses=(rnd.random() < 0.4, False),
                aires=(rnd.choice([5000.0, 20000.0]), rnd.choice([10000.0, 40000.0])),
                ajutages=(rnd.choice([1.0, 5.0, 20.0]), rnd.choice([5.0, 12.0])),
                s_inf=(rnd.choice([0.0, 200.0]), rnd.choice([0.0, 250.0, 800.0])),
                v_sous=(0.0, rnd.choice([0.0, 0.0, 60.0])),
                ins=rnd.choice(communes), rp=rnd.choice([25, 50, 100]))
            scenario = SCENARIO_SEUIL if aval.etude.bassin.volume_sous_ajutage_m3 else SCENARIO_MIXTE
            res = hydro.dimensionner(aval.etude, scenario, avec_minima=False)
            if (res.volume_m3 <= 1.0 or res.temps_vidange_h == float("inf")
                    or res.temps_vidange_h > 400 or res.duree_critique_min > 20000):
                continue      # cas dégénéré : rien à confronter
            self._confronter_dimensionnement(systeme, aval, scenario)
            controles += 1
        self.assertGreater(controles, 5)


class EquivalenceAvecLeBassinIsole(unittest.TestCase):
    """Aucune régression : le réseau doit refaire à l'identique l'ancien calcul."""

    def _projet_isole(self, avec_amont=False):
        p = Projet(commune_ins="63013", commune_nom="Bütgenbach", periode_retour=25,
                   surfaces=Projet.surfaces_par_defaut())
        p.surfaces[7].aire_m2 = 15000.0
        p.surfaces[1].aire_m2 = 5000.0
        p.surface_reference_m2 = 40000.0
        p.k_infiltration_ms = 1e-5
        p.surface_infiltration_m2 = 300.0
        p.fixer_ajutage_absolu(8.0)
        p.bassin = Bassin(volume_total_m3=900.0, volume_sous_ajutage_m3=40.0,
                          surface_dispersion_m2=300.0, debit_ajutage_ls=8.0)
        if avec_amont:
            p.amont = BassinAmont(actif=True, surface_bv_m2=12000.0, coef_ruissellement=0.9,
                                  debit_ajutage_ls=5.0, surface_dispersion_m2=120.0,
                                  k_infiltration_ms=1e-5, volume_temporisation_m3=250.0)
        return p

    def test_un_seul_ouvrage_rend_exactement_le_calcul_isole(self):
        reference = {s: hydro.dimensionner(self._projet_isole(), s) for s in SCENARIOS}
        systeme = reseau.depuis_projet(self._projet_isole())
        ouvrage = systeme.ouvrages[0]
        for scenario in SCENARIOS:
            obtenu = hydro.dimensionner(ouvrage.etude, scenario)
            attendu = reference[scenario]
            with self.subTest(scenario=scenario):
                self.assertEqual(obtenu.volume_m3, attendu.volume_m3)
                self.assertEqual(obtenu.duree_critique_min, attendu.duree_critique_min)
                # Le temps de vidange passe, lui, de la formule fermée à
                # l'intégration sur les paliers : mathématiquement la même
                # chose, aux derniers bits près.
                self.assertAlmostEqual(obtenu.temps_vidange_h, attendu.temps_vidange_h,
                                       places=9)
                self.assertEqual(obtenu.surface_infiltration_min_m2,
                                 attendu.surface_infiltration_min_m2)
                self.assertEqual(obtenu.debit_ajutage_min_ls, attendu.debit_ajutage_min_ls)

    def test_deux_ouvrages_rendent_exactement_le_bassin_amont_historique(self):
        attendu = hydro.dimensionner(self._projet_isole(avec_amont=True), SCENARIO_MIXTE)
        systeme = reseau.depuis_projet(self._projet_isole(avec_amont=True))
        self.assertEqual(len(systeme.ouvrages), 2)
        aval = systeme.ouvrage(systeme.ouvrage_courant)
        obtenu = hydro.dimensionner(aval.etude, SCENARIO_MIXTE)
        self.assertEqual(obtenu.volume_m3, attendu.volume_m3)
        self.assertEqual(obtenu.temps_vidange_h, attendu.temps_vidange_h)
        self.assertEqual(obtenu.surface_infiltration_min_m2, attendu.surface_infiltration_min_m2)

    def test_un_ouvrage_amont_sans_eau_ne_change_rien(self):
        """Le branchement change le chemin de calcul : il ne doit pas changer le résultat.

        Dès qu'un ouvrage est raccordé au-dessus, le volume passe par
        l'intégration exacte au lieu de la formule fermée, et par un balayage en
        deux passes au lieu des 17 280 durées. Un amont qui ne restitue rien doit
        rendre exactement ce que rendait le calcul sans amont.
        """
        for scenario, v_sous in ((SCENARIO_MIXTE, 0.0), (SCENARIO_SEUIL, 60.0)):
            avec, amont, aval = systeme_essai(volumes=(200.0, 0.0), v_sous=(0.0, v_sous))
            for bv in avec.versants_de(amont.id):
                bv.surfaces = [SurfaceIncidente("Aucune surface", 1.0, 0.0)]
            avec.synchroniser()

            sans, amont_seul, aval_seul = systeme_essai(volumes=(200.0, 0.0),
                                                        v_sous=(0.0, v_sous))
            amont_seul.aval_id = ""                    # plus rien en amont de l'aval
            for bv in list(sans.versants_de(amont_seul.id)):
                sans.bassins_versants.remove(bv)
            sans.synchroniser()

            attendu = hydro.dimensionner(aval_seul.etude, scenario, avec_minima=False)
            obtenu = hydro.dimensionner(aval.etude, scenario, avec_minima=False)
            with self.subTest(scenario=scenario):
                self.assertTrue(obtenu.amont_pris_en_compte)
                self.assertEqual(obtenu.volume_m3, attendu.volume_m3)
                self.assertEqual(obtenu.duree_critique_min, attendu.duree_critique_min)
                self.assertEqual(obtenu.temps_vidange_h, attendu.temps_vidange_h)

    def test_la_simulation_d_un_ouvrage_du_reseau_rend_le_calcul_isole(self):
        p = self._projet_isole(avec_amont=True)
        attendu = simulation.simuler_evenement_critique(p, p.bassin)
        systeme = reseau.depuis_projet(self._projet_isole(avec_amont=True))
        aval = systeme.ouvrage(systeme.ouvrage_courant)
        obtenu = simulation.simuler_evenement_critique(aval.etude, aval.etude.bassin)
        self.assertEqual(obtenu.volume_max_m3, attendu.volume_max_m3)
        self.assertEqual(obtenu.volume_debordement_m3, attendu.volume_debordement_m3)
        self.assertEqual(obtenu.temps_vidange_h, attendu.temps_vidange_h)

    def test_la_table_qdf_d_un_ouvrage_du_reseau_rend_le_calcul_isole(self):
        p = self._projet_isole(avec_amont=True)
        attendue = simulation.table_acceptation(p, p.bassin)
        systeme = reseau.depuis_projet(self._projet_isole(avec_amont=True))
        aval = systeme.ouvrage(systeme.ouvrage_courant)
        obtenue = simulation.table_acceptation(aval.etude, aval.etude.bassin)
        for i in range(len(attendue.durees_min)):
            for j in range(len(attendue.periodes_retour)):
                with self.subTest(i=i, j=j):
                    self.assertEqual(obtenue.cellules[i][j].volume_requis_m3,
                                     attendue.cellules[i][j].volume_requis_m3)


class Topologie(unittest.TestCase):
    """Un réseau mal construit doit être signalé, jamais faire tourner en rond."""

    def test_un_raccordement_circulaire_est_signale_et_coupe(self):
        systeme, amont, aval = systeme_essai()
        aval.aval_id = amont.id          # boucle amont -> aval -> amont
        anomalies = systeme.anomalies()
        self.assertTrue(any("circulaire" in a for a in anomalies), anomalies)
        # Le calcul reste possible : le lien fautif est ignoré.
        self.assertEqual(len(systeme.ordre_amont_aval()), 2)
        systeme.synchroniser()
        self.assertGreater(hydro.dimensionner(aval.etude, SCENARIO_MIXTE,
                                              avec_minima=False).volume_m3, 0)

    def test_un_ouvrage_qui_se_deverse_dans_lui_meme_est_signale(self):
        systeme, _amont, aval = systeme_essai()
        aval.aval_id = aval.id
        self.assertTrue(any("lui-même" in a for a in systeme.anomalies()))

    def test_un_bassin_versant_orphelin_est_signale(self):
        systeme, _amont, aval = systeme_essai()
        perdu = reseau.versant_neuf(systeme, "Orphelin", "")
        perdu.bassin_id = ""
        systeme.bassins_versants.append(perdu)
        self.assertTrue(any("Orphelin" in a for a in systeme.anomalies()))

    def test_l_ordre_va_de_l_amont_vers_l_aval(self):
        systeme, amont, aval = systeme_essai()
        ordre = [o.id for o in systeme.ordre_amont_aval()]
        self.assertLess(ordre.index(amont.id), ordre.index(aval.id))

    def test_les_exutoires_sont_les_ouvrages_sans_aval(self):
        systeme, _amont, aval = systeme_essai()
        self.assertEqual([o.id for o in systeme.exutoires], [aval.id])

    def test_les_amonts_transitifs_remontent_toute_la_chaine(self):
        systeme, amont, aval = systeme_essai()
        tete = reseau.ouvrage_neuf(systeme, "Tête")
        tete.aval_id = amont.id
        systeme.ouvrages.append(tete)
        noms = sorted(o.nom for o in systeme.amonts_transitifs(aval.id))
        self.assertEqual(noms, ["Amont", "Tête"])


class SurfacesEtRaccordements(unittest.TestCase):
    def test_les_surfaces_sont_partagees_avec_le_bassin_versant(self):
        """Modifier une surface dans l'onglet des bassins versants doit se voir partout."""
        systeme, _amont, aval = systeme_essai()
        versant = systeme.versants_de(aval.id)[0]
        versant.surfaces[0].aire_m2 = 50000.0
        systeme.synchroniser()
        self.assertAlmostEqual(aval.etude.aire_totale_m2, 50000.0)

    def test_raccorder_un_versant_ailleurs_deplace_sa_surface(self):
        systeme, amont, aval = systeme_essai()
        versant = systeme.versants_de(aval.id)[0]
        versant.bassin_id = amont.id
        systeme.synchroniser()
        self.assertAlmostEqual(aval.etude.aire_totale_m2, 0.0)
        self.assertAlmostEqual(amont.etude.aire_totale_m2, 10000.0 + 20000.0)

    def test_compter_les_bassins_versants_amont_dans_l_ajutage(self):
        systeme, _amont, aval = systeme_essai()
        aval.etude.fixer_ajutage_specifique(5.0)
        systeme.synchroniser()
        self.assertAlmostEqual(aval.etude.debit_ajutage_ls, 5.0 * 20000.0 / 10000.0)
        aval.compter_bv_amont_dans_ajutage = True
        systeme.synchroniser()
        self.assertAlmostEqual(aval.etude.debit_ajutage_ls, 5.0 * 30000.0 / 10000.0)
        self.assertAlmostEqual(aval.etude.debit_fuite_admissible_ls, 5.0 * 30000.0 / 10000.0)

    def test_un_debit_absolu_ne_suit_pas_la_surface_amont(self):
        systeme, _amont, aval = systeme_essai()
        aval.etude.fixer_ajutage_absolu(7.5)
        aval.compter_bv_amont_dans_ajutage = True
        systeme.synchroniser()
        self.assertAlmostEqual(aval.etude.debit_ajutage_ls, 7.5)


class Serialisation(unittest.TestCase):
    def test_aller_retour_par_dictionnaire(self):
        systeme, amont, aval = systeme_essai(volumes=(200.0, 800.0), v_sous=(0.0, 50.0))
        amont.surverse_vers_milieu_naturel = True
        aval.note = "exutoire ruisseau"
        systeme.synchroniser()
        copie = reseau.Systeme.from_dict(systeme.to_dict())
        self.assertEqual([o.nom for o in copie.ouvrages], [o.nom for o in systeme.ouvrages])
        self.assertEqual(copie.ouvrage(amont.id).surverse_vers_milieu_naturel, True)
        self.assertEqual(copie.ouvrage(aval.id).note, "exutoire ruisseau")
        self.assertAlmostEqual(copie.ouvrage(aval.id).etude.bassin.volume_sous_ajutage_m3, 50.0)
        self.assertAlmostEqual(copie.aire_ponderee_m2, systeme.aire_ponderee_m2)
        avant = hydro.dimensionner(aval.etude, SCENARIO_MIXTE, avec_minima=False).volume_m3
        apres = hydro.dimensionner(copie.ouvrage(aval.id).etude, SCENARIO_MIXTE,
                                   avec_minima=False).volume_m3
        self.assertAlmostEqual(avant, apres, places=9)

    def test_un_champ_inconnu_ne_bloque_pas_la_relecture(self):
        systeme, _amont, aval = systeme_essai()
        donnees = systeme.to_dict()
        donnees["champ_venu_du_futur"] = 42
        donnees["ouvrages"][0]["autre_champ"] = "x"
        copie = reseau.Systeme.from_dict(donnees)
        self.assertEqual(len(copie.ouvrages), 2)

    def test_migration_d_un_projet_de_la_version_precedente(self):
        p = Projet(surfaces=Projet.surfaces_par_defaut(), nom_projet="Ancien")
        p.surfaces[7].aire_m2 = 5000.0
        p.amont = BassinAmont(actif=True, surface_bv_m2=10000.0, coef_ruissellement=0.9,
                              debit_ajutage_ls=4.0, volume_temporisation_m3=300.0)
        systeme = reseau.depuis_projet(p)
        self.assertEqual(len(systeme.ouvrages), 2)
        self.assertEqual(len(systeme.bassins_versants), 2)
        self.assertEqual(systeme.nom_projet, "Ancien")
        amont = [o for o in systeme.ouvrages if o.aval_id][0]
        self.assertAlmostEqual(amont.etude.bassin.volume_total_m3, 300.0)
        self.assertAlmostEqual(systeme.aire_ponderee_de(amont.id), 9000.0)
        # Le panneau historique est neutralisé : l'amont ne doit pas compter deux fois.
        for o in systeme.ouvrages:
            self.assertFalse(o.etude.amont.actif)


class DimensionnementDuReseau(unittest.TestCase):
    def test_le_dimensionnement_en_cascade_part_de_l_amont(self):
        systeme, amont, aval = systeme_essai(volumes=(0.0, 0.0))
        sans_cascade = hydro.dimensionner(aval.etude, aval.scenario, avec_minima=False).volume_m3
        retenus = reseau.dimensionner_en_cascade(systeme)
        self.assertEqual([nom for nom, _ in retenus], ["Amont", "Aval"])
        self.assertGreater(amont.etude.bassin.volume_total_m3, 0.0)
        avec_cascade = hydro.dimensionner(aval.etude, aval.scenario, avec_minima=False).volume_m3
        self.assertLess(avec_cascade, sans_cascade,
                        "un amont correctement dimensionné ne surverse plus : l'aval diminue")

    def test_apres_cascade_aucun_ouvrage_ne_deborde(self):
        systeme, _amont, _aval = systeme_essai(volumes=(0.0, 0.0))
        reseau.dimensionner_en_cascade(systeme)
        for fiche in reseau.dimensionner(systeme, avec_minima=False):
            with self.subTest(ouvrage=fiche.nom):
                self.assertTrue(fiche.suffisant,
                                f"{fiche.nom} : {fiche.volume_encode_m3} m³ encodés pour "
                                f"{fiche.volume_minimal_m3} m³ nécessaires")

    def test_la_cascade_accorde_les_exutoires_au_scenario(self):
        """Un volume calculé avec une infiltration que l'ouvrage n'a pas déborderait."""
        systeme, _amont, aval = systeme_essai(volumes=(0.0, 0.0), s_inf=(0.0, 500.0))
        aval.etude.bassin.surface_dispersion_m2 = 0.0     # l'ouvrage n'infiltre pas
        aval.scenario = SCENARIO_MIXTE                    # mais le calcul y compte
        reseau.dimensionner_en_cascade(systeme)
        self.assertAlmostEqual(aval.etude.bassin.surface_dispersion_m2, 500.0)
        for duree in rainfall.QDF_DURATIONS_MIN:
            sim = reseau.simuler(systeme, float(duree))
            self.assertLess(sim.resultat(aval.id).volume_debordement_m3, 1e-6,
                            f"débordement à {duree} min")

    def test_la_cascade_n_ouvre_pas_d_exutoire_hors_scenario(self):
        systeme, _amont, aval = systeme_essai(volumes=(0.0, 0.0), s_inf=(0.0, 500.0))
        aval.scenario = SCENARIO_TEMPORISATION
        reseau.dimensionner_en_cascade(systeme)
        self.assertAlmostEqual(aval.etude.bassin.surface_dispersion_m2, 0.0,
                               msg="la temporisation seule n'infiltre pas")

    def test_les_minima_sont_calcules_par_ouvrage(self):
        systeme, _amont, aval = systeme_essai(volumes=(200.0, 0.0))
        fiches = reseau.dimensionner(systeme)
        fiche = [f for f in fiches if f.ouvrage.id == aval.id][0]
        self.assertIsNotNone(fiche.resultat.surface_infiltration_min_m2)
        self.assertIsNotNone(fiche.resultat.debit_ajutage_min_ls)
        self.assertGreater(fiche.apport_amont_m3, 0.0)

    def test_la_surface_minimale_respecte_bien_le_temps_de_vidange(self):
        """Le minimum annoncé doit effectivement ramener la vidange sous la limite."""
        systeme, _amont, aval = systeme_essai(volumes=(200.0, 0.0), s_inf=(0.0, 10.0))
        fiche = [f for f in reseau.dimensionner(systeme) if f.ouvrage.id == aval.id][0]
        minimum = fiche.resultat.surface_infiltration_min_m2
        self.assertIsNotNone(minimum)
        aval.etude.surface_infiltration_m2 = minimum
        systeme.synchroniser()
        res = hydro.dimensionner(aval.etude, aval.scenario, avec_minima=False)
        self.assertLessEqual(res.temps_vidange_h, systeme.temps_vidange_max_h + 0.02)

    def test_l_ajutage_minimal_respecte_bien_le_temps_de_vidange(self):
        systeme, _amont, aval = systeme_essai(volumes=(200.0, 0.0), ajutages=(5.0, 0.5),
                                              s_inf=(0.0, 0.0))
        aval.scenario = SCENARIO_TEMPORISATION
        fiche = [f for f in reseau.dimensionner(systeme) if f.ouvrage.id == aval.id][0]
        minimum = fiche.resultat.debit_ajutage_min_ls
        self.assertIsNotNone(minimum)
        aval.etude.fixer_ajutage_absolu(minimum)
        systeme.synchroniser()
        res = hydro.dimensionner(aval.etude, SCENARIO_TEMPORISATION, avec_minima=False)
        self.assertLessEqual(res.temps_vidange_h, systeme.temps_vidange_max_h + 0.02)

    def test_le_volume_annonce_est_celui_qui_evite_la_surverse(self):
        """Dimensionner puis simuler avec ce volume : plus de débordement."""
        systeme, _amont, aval = systeme_essai(volumes=(200.0, 0.0))
        fiche = [f for f in reseau.dimensionner(systeme, avec_minima=False)
                 if f.ouvrage.id == aval.id][0]
        aval.etude.bassin.volume_total_m3 = fiche.volume_minimal_m3
        systeme.synchroniser()
        pire = 0.0
        for duree in rainfall.QDF_DURATIONS_MIN:
            sim = reseau.simuler(systeme, float(duree))
            pire = max(pire, sim.resultat(aval.id).volume_debordement_m3)
        self.assertLess(pire, 1e-6, f"débordement résiduel de {pire:.4f} m³")


class SimulationDuSysteme(unittest.TestCase):
    def test_la_duree_critique_du_systeme_est_dans_la_grille(self):
        systeme, _amont, _aval = systeme_essai(volumes=(200.0, 900.0))
        duree = reseau.duree_critique_systeme(systeme)
        self.assertGreaterEqual(duree, hydro.DUREE_MIN)
        self.assertLessEqual(duree, hydro.DUREE_MAX)

    def test_la_simulation_couvre_tous_les_ouvrages(self):
        systeme, _amont, _aval = systeme_essai(volumes=(200.0, 900.0))
        sim = reseau.simuler_evenement_critique(systeme)
        self.assertEqual(len(sim.resultats), len(systeme.ouvrages))
        self.assertGreater(sim.volume_ruissele_m3, 0.0)
        self.assertGreater(sim.volume_stocke_m3, 0.0)

    def test_le_debit_a_l_exutoire_ne_depasse_pas_les_ouvrages_terminaux(self):
        systeme, _amont, aval = systeme_essai(volumes=(200.0, 5000.0))
        sim = reseau.simuler_evenement_critique(systeme)
        sortie = reseau.debit_a_l_exutoire_ls(systeme, sim.hauteur_mm, sim.duree_min)
        pointe = max((q for _, _, q in sortie.segments), default=0.0)
        # L'ouvrage terminal ne déborde pas : il ne rejette que son ajutage.
        self.assertLessEqual(pointe, aval.etude.bassin.debit_ajutage_ls + 1e-9)

    def test_un_ouvrage_amont_qui_surverse_se_voit_a_l_aval(self):
        petit, _a1, aval1 = systeme_essai(volumes=(5.0, 5000.0))
        grand, _a2, aval2 = systeme_essai(volumes=(2000.0, 5000.0))
        sim_petit = reseau.simuler(petit, 60.0)
        sim_grand = reseau.simuler(grand, 60.0)
        self.assertGreater(sim_petit.resultat(aval1.id).q_amont_max_ls,
                           sim_grand.resultat(aval2.id).q_amont_max_ls)


if __name__ == "__main__":
    unittest.main()
