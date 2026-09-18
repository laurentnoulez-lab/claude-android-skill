"""Rapport Excel (.xlsx) avec formules de calcul vivantes.

Le classeur génère n'est pas une simple exportation de résultats : les feuilles
"Pluie de projet", "Scénarios", "Bassin" et "Ajutage" contiennent les formules
de la méthode rationnelle. L'utilisateur peut modifier les données d'entrée
(surfaces, K, débit d'ajutage, volumes) et voir les résultats se recalculer.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Set, Tuple

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation
from openpyxl.workbook.defined_name import DefinedName

from ..core import hydro as _hydro, rainfall
from ..core.model import (
    LIBELLES_SCENARIOS,
    SCENARIO_DISPERSION,
    SCENARIO_MIXTE,
    SCENARIO_SEUIL,
    SCENARIO_TEMPORISATION,
)
from ..formats import fr
from . import schema as mod_schema
from .dossier import (Dossier, ORDRE_SCENARIOS, synthese_reseau,
                      synthese_simulation_systeme, synthese_versants)

BLEU = "1D4ED8"
BLEU_PALE = "DBEAFE"
GRIS_PALE = "F1F5F9"
VERT_PALE = "DCFCE7"
ROUGE_PALE = "FEE2E2"
ORANGE_PALE = "FEF3C7"
BLANC = "FFFFFF"

_BORDURE = Border(*[Side(style="thin", color="CBD5E1")] * 4)


def _titre(ws, cellule: str, texte: str, taille: int = 14) -> None:
    ws[cellule] = texte
    ws[cellule].font = Font(bold=True, size=taille, color=BLEU)


def _entete(ws, ligne: int, valeurs: Sequence[str], col_debut: int = 1) -> None:
    for i, v in enumerate(valeurs):
        c = ws.cell(row=ligne, column=col_debut + i, value=v)
        c.font = Font(bold=True, color=BLANC)
        c.fill = PatternFill("solid", fgColor=BLEU)
        c.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        c.border = _BORDURE


def _label(ws, ligne: int, texte: str, valeur=None, unite: str = "", format_nombre: Optional[str] = None,
           col: int = 1, gras: bool = False, fond: Optional[str] = None):
    c0 = ws.cell(row=ligne, column=col, value=texte)
    c0.font = Font(bold=gras)
    c1 = ws.cell(row=ligne, column=col + 1, value=valeur)
    if format_nombre:
        c1.number_format = format_nombre
    c1.font = Font(bold=True)
    if fond:
        c1.fill = PatternFill("solid", fgColor=fond)
    ws.cell(row=ligne, column=col + 2, value=unite)
    return c1


def _largeurs(ws, largeurs: Dict[str, int]) -> None:
    for col, w in largeurs.items():
        ws.column_dimensions[col].width = w


def _ref(feuille: str) -> str:
    """Référence de feuille utilisable dans une formule.

    Excel veut l'apostrophe doublée à l'intérieur d'un nom cité : « Bassin
    d'orage » s'écrit 'Bassin d''orage'. Presque tous les noms d'ouvrage d'un
    projet francophone en portent une, et la formule était simplement refusée.
    """
    return "'" + feuille.replace("'", "''") + "'"


@dataclass
class _Ancrage:
    """Où un ouvrage range ses données d'entrée, pour que ses formules y pointent.

    Le classeur ne portait qu'un ouvrage : ses formules visaient des noms
    globaux — ``S_ponderee``, ``Q_ajutage`` — définis sur la feuille « Projet ».
    Un réseau en compte plusieurs, chacun avec ses surfaces et son exutoire :
    chaque ouvrage a donc sa ligne sur la feuille « Ouvrages », et ses feuilles
    de calcul pointent vers **ses** cellules. Modifier une surface ou un ajutage
    dans le classeur recalcule alors l'ouvrage concerné, et lui seul.
    """

    ouvrage_id: str
    nom: str
    feuille_pluie: str
    feuille_scenarios: str
    s_ponderee: str
    q_infiltration: str
    q_ajutage: str
    v_bassin: str
    v_sous_ajutage: str
    apport_amont: str
    #: Coefficient d'infiltration de l'ouvrage, pour convertir un débit en surface.
    k_infiltration: str = "K_infiltration"
    #: Coefficient d'infiltration du bassin réellement construit.
    k_bassin: str = "K_infiltration"
    #: Ouvrage tel qu'il sera construit — distinct des hypothèses ci-dessus.
    s_dispersion: str = ""
    q_infiltration_bassin: str = ""
    q_ajutage_bassin: str = ""
    volume_minimal: str = ""
    ligne: int = 0

    def plage_pluie(self, col: str, r0: int, r1: int) -> str:
        return f"{_ref(self.feuille_pluie)}!${col}${r0}:${col}${r1}"


def _nom_de_feuille(base: str, rang: int) -> str:
    """Nom de feuille : « Pluie 1 », « Scénarios 2 »…

    Un nom d'ouvrage ne convient pas. Excel plafonne à 31 caractères, et
    « Bassin d'orage de la voirie » tronqué donne « Scénarios Bassin d'orage de
    la » — indistinct du suivant. Il faudrait en outre y doubler l'apostrophe
    dans chaque formule, ce que tous les tableurs ne relisent pas de la même
    façon. Le nom de l'ouvrage se lit en titre de sa feuille et dans la colonne
    « Feuilles » de la feuille « Ouvrages ».
    """
    return f"{base} {rang}"


def _grille_durees(dossier: Dossier) -> List[float]:
    """Durées balayées par le classeur, **sous-ensemble** de celles de l'application.

    Avec les tables QDF, seules les 19 durées normalisées ont un sens : ajouter
    une grille logarithmique ferait retenir au classeur une durée critique
    interpolée, différente de celle affichée par l'application.

    Avec Montana, le classeur ne peut pas balayer les 17 280 durées de
    l'application sans devenir illisible ; il en échantillonne une centaine.
    Mais un échantillon *libre* tombe parfois plus près de l'optimum continu que
    le meilleur multiple de 5 : le `MAX()` du classeur retenait alors une durée
    critique — 199,1 min contre 195 — que l'application n'avait jamais affichée,
    et deux durées différentes circulaient dans un même dossier. Chaque durée
    échantillonnée est donc ramenée sur la grille de l'application. Le maximum du
    classeur est dès lors pris sur un sous-ensemble de celui de l'application :
    il ne peut plus le dépasser, et comme les durées critiques y figurent, les
    deux coïncident exactement.
    """
    durees = set(float(d) for d in rainfall.QDF_DURATIONS_MIN)
    src = rainfall.SourcePluie(dossier.projet.commune_ins, dossier.projet.periode_retour,
                               dossier.projet.source_pluie)
    if not src.durees_tabulees:
        grille = src.durees_de_balayage()
        pas = grille[1] - grille[0] if len(grille) > 1 else 5.0
        origine = grille[0]
        for i in range(101):
            brute = 10 * (8640 ** (i / 100.0))
            rangs = round((brute - origine) / pas)
            durees.add(origine + rangs * pas)
        for r in dossier.resultats.values():
            if r.duree_critique_min:
                durees.add(float(r.duree_critique_min))
        if dossier.duree_critique_min:
            durees.add(float(dossier.duree_critique_min))
    return sorted(d for d in durees if 10 <= d <= 86400)


def _renvoi_ouvrage(dossier: Dossier) -> int:
    """Ligne de l'ouvrage courant sur la feuille « Ouvrages », 0 s'il n'y en a pas.

    La feuille « Projet » recopiait les caractéristiques de l'ouvrage courant,
    qui vivent aussi sur « Ouvrages » : deux vérités pour la même donnée, dont
    une seule se recalculait.
    """
    if dossier.systeme is None or not dossier.fiches:
        return 0
    courant = dossier.ouvrage_courant
    for i, fiche in enumerate(dossier.fiches, start=5):
        if courant is not None and fiche.ouvrage.id == courant.id:
            return i
    return 5


def construire_classeur(dossier: Dossier) -> Workbook:
    projet = dossier.projet
    wb = Workbook()

    # ------------------------------------------------------------------ Projet
    ws = wb.active
    ws.title = "Projet"
    _largeurs(ws, {"A": 46, "B": 16, "C": 16, "D": 18, "E": 30})
    _titre(ws, "A1", "DIMENSIONNEMENT D'UN RÉSEAU DE BASSINS D'ORAGE"
           if dossier.reseau_multiple else "DIMENSIONNEMENT D'UN BASSIN D'ORAGE", 16)
    ws["A2"] = "Méthode rationnelle - pluies statistiques du GTI (Région wallonne)"
    ws["A2"].font = Font(italic=True, color="475569")

    _label(ws, 4, "Projet", projet.nom_projet or "-", gras=True)
    _label(ws, 5, "Localisation", projet.localisation or "-")
    _label(ws, 6, "Auteur", projet.auteur or "-")
    _label(ws, 7, "Date", dossier.date)
    _label(ws, 8, "Commune", projet.commune_nom)
    _label(ws, 9, "Code INS", projet.commune_ins)
    # Cellule modifiable, et qui commande vraiment : les coefficients de pluie
    # de chaque ouvrage s'y réfèrent. Elle est signalée comme telle.
    # La période de retour commande tout le classeur, et n'accepte que les douze
    # récurrences du GTI : elle se choisit dans une liste. Libre, elle laissait
    # taper « 35 ans » et renvoyait un #N/A muet dans chaque feuille de pluie.
    _label(ws, 10, "Période de retour", projet.periode_retour, "ans", fond=BLEU_PALE)
    ws.cell(row=10, column=2).number_format = "0"
    _liste(ws, "B10", rainfall.RETURN_PERIODS, "Période de retour",
           "Récurrences du GTI : 2, 5, 10, 15, 20, 25, 30, 40, 50, 75, 100 ou 200 ans. "
           "Tout le classeur se recalcule.")
    _label(ws, 11, "Source des pluies", dossier.source_pluies_datee)
    if dossier.reseau_multiple:
        _label(ws, 12, "Ouvrage détaillé par ce classeur", dossier.ouvrage_courant.nom,
               gras=True, fond=BLEU_PALE)

    # Un classeur de réseau décrit le PROJET, pas l'ouvrage affiché à l'écran :
    # ses bassins versants, ses contraintes communes et ses bassins d'orage. Les
    # données propres à chaque ouvrage vivent sur la feuille « Ouvrages », qui
    # les porte toutes. Cette feuille annonçait encore « Ouvrage détaillé par ce
    # classeur » et ne montrait que lui : c'était le classeur mono-bassin qui
    # subsistait sous un classeur de système.
    if dossier.reseau_multiple:
        _projet_systeme(wb, ws, dossier)
    else:
        _projet_ouvrage(wb, ws, dossier)

    # Un classeur par ouvrage : chacun a ses surfaces, son exutoire et donc ses
    # volumes. Ne détailler que l'ouvrage courant obligeait à régénérer le
    # classeur autant de fois qu'il y a de bassins.
    fiches = list(dossier.fiches) if dossier.systeme is not None else []
    # La feuille des pluies vient en premier : la table des volumes de chaque
    # ouvrage s'y réfère, et son adresse doit être connue avant de l'écrire.
    stats = _feuille_statistiques(wb, dossier)
    if fiches:
        surfaces, totaux_versants, cellules_versants = _feuille_versants(wb, dossier)
        _relier_surfaces_projet(wb, dossier, cellules_versants, totaux_versants)
        ancrages = _feuille_ouvrages(wb, dossier, surfaces)
        if dossier.reseau_multiple:
            _feuille_reseau(wb, dossier, ancrages, totaux_versants)
        sous_dossiers = dossier.par_ouvrage()
        for i, (ancrage, fiche) in enumerate(zip(ancrages, fiches), start=1):
            sous = sous_dossiers[i - 1] if i - 1 < len(sous_dossiers) else dossier
            _feuille_pluie(wb, dossier, ancrage, stats)
            _feuille_scenarios(wb, dossier, ancrage, fiche)
            _feuille_bassin(wb, sous, ancrage, stats, rang=i)
            _feuille_ajutage(wb, sous, ancrage, rang=i)
    else:
        ancrage = _Ancrage(
            ouvrage_id="", nom="Bassin d'orage",
            feuille_pluie="Pluie de projet", feuille_scenarios="Scénarios",
            s_ponderee="S_ponderee", q_infiltration="Q_infiltration",
            q_ajutage="Q_ajutage", v_bassin="V_bassin",
            v_sous_ajutage="V_sous_ajutage", apport_amont="0",
            s_dispersion="S_dispersion",
            q_infiltration_bassin="Q_infiltration_bassin",
            q_ajutage_bassin="Q_ajutage_bassin",
        )
        _feuille_pluie(wb, dossier, ancrage, stats)
        _feuille_scenarios(wb, dossier, ancrage)
        _feuille_bassin(wb, dossier, ancrage, stats)
        _feuille_ajutage(wb, dossier, ancrage)
    # La feuille des pluies se range en fin de classeur : c'est une annexe.
    wb.move_sheet("Pluies statistiques", offset=len(wb.sheetnames))
    _signaler_les_valeurs_en_dur(wb)
    return wb


def _signaler_les_valeurs_en_dur(wb: Workbook) -> None:
    """Toute valeur qui n'est pas une formule se voit, et se voit partout.

    Un classeur qui annonce qu'il recalcule doit dire où il ne recalcule pas.
    Le marquage cellule par cellule à l'écriture se révélait incomplet à chaque
    relecture : une valeur figée ajoutée quelque part passait inaperçue. Cette
    passe finale balaie le classeur entier et applique la règle une fois pour
    toutes — **orange = à vous de la remplir ou de la vérifier, elle ne se
    recalcule pas**.

    Les données sources — tables du GTI, abaque des diamètres commerciaux,
    constante de pesanteur, grille des durées balayées — reçoivent un gris
    discret : elles ne se recalculent pas non plus, mais ce n'est pas à
    l'utilisateur de les remplir, et les noyer d'orange masquerait les
    cellules qui, elles, l'attendent.
    """
    orange = PatternFill("solid", fgColor=ORANGE_PALE)
    gris = PatternFill("solid", fgColor=GRIS_PALE)
    for ws in wb:
        titre = ws.title
        for ligne in ws.iter_rows():
            for c in ligne:
                if c.value is None or isinstance(c.value, str) or isinstance(c.value, bool):
                    continue
                if not isinstance(c.value, (int, float)):
                    continue          # durées : des dates, écrites par formule ou non
                source = (titre == "Pluies statistiques"
                          or (titre.startswith("Pluie") and c.column == 1)
                          or (titre.startswith("Ajutage") and (c.column == 1 or c.row == 7)))
                c.fill = gris if source else orange


def _projet_ouvrage(wb: Workbook, ws, dossier: Dossier) -> None:
    """Feuille « Projet » d'une étude à un seul bassin d'orage.

    Elle décrit cet ouvrage-là : ses surfaces, son sol, son exutoire, le
    bassin encodé. Les noms définis du classeur y pointent.
    """
    projet = dossier.projet
    _titre(ws, "A13", "1. Surfaces incidentes", 12)
    _entete(ws, 14, ["Type d'occupation du sol", "Coeff. ruiss. [-]", "Surface [m²]",
                     "Surface pondérée [m²]", "Notes"])
    ligne = 15
    for s in projet.surfaces:
        ws.cell(row=ligne, column=1, value=s.libelle).border = _BORDURE
        ws.cell(row=ligne, column=2, value=s.coefficient).border = _BORDURE
        ws.cell(row=ligne, column=3, value=s.aire_m2).border = _BORDURE
        c = ws.cell(row=ligne, column=4, value=f"=B{ligne}*C{ligne}")
        c.border = _BORDURE
        c.number_format = "0.0"
        ws.cell(row=ligne, column=5, value=s.note).border = _BORDURE
        ligne += 1
    l_tot = ligne
    ws._plage_surfaces = (15, l_tot - 1)  # type: ignore[attr-defined]
    ws.cell(row=l_tot, column=1, value="TOTAL").font = Font(bold=True)
    for col, formule in ((3, f"=SUM(C15:C{l_tot - 1})"), (4, f"=SUM(D15:D{l_tot - 1})")):
        c = ws.cell(row=l_tot, column=col, value=formule)
        c.font = Font(bold=True)
        c.number_format = "0.0"
        c.fill = PatternFill("solid", fgColor=BLEU_PALE)
        c.border = _BORDURE
    l_coef = l_tot + 1
    c = ws.cell(row=l_coef, column=1, value="Coefficient de ruissellement moyen")
    c.font = Font(bold=True)
    c = ws.cell(row=l_coef, column=4, value=f"=IF(C{l_tot}>0,D{l_tot}/C{l_tot},0)")
    c.number_format = "0.000"
    c.font = Font(bold=True)
    c.fill = PatternFill("solid", fgColor=BLEU_PALE)

    l = l_coef + 2
    _titre(ws, f"A{l}", "2. Sol, exutoire et contraintes", 12)
    l += 1
    c_sref = _label(ws, l, "Surface de référence du projet", projet.surface_reference_m2, "m²", "0.0"); l += 1
    renvoi = _renvoi_ouvrage(dossier)
    c_k = _label(ws, l, "Coefficient d'infiltration K",
                 f"=Ouvrages!$F${renvoi}" if renvoi else projet.k_infiltration_ms,
                 "m/s", "0.00E+00"); l += 1
    c_cs = _label(ws, l, "Coefficient de sécurité sur K", projet.coef_securite_infiltration, "[-]", "0.0"); l += 1
    # Sur un réseau, ces valeurs vivent sur la feuille « Ouvrages » : les
    # recopier ici donnerait deux vérités pour la même donnée, et modifier l'une
    # ne changerait pas l'autre. On y renvoie.
    c_sinf = _label(ws, l, "Surface d'infiltration du dispositif",
                    f"=Ouvrages!$E${renvoi}" if renvoi else projet.surface_infiltration_m2,
                    "m²", "0.0"); l += 1
    c_qaj = _label(ws, l, "Débit d'ajutage (orifice calibré)",
                   f"=Ouvrages!$H${renvoi}" if renvoi else projet.debit_ajutage_ls,
                   "l/s", "0.000"); l += 1
    c_tvid = _label(ws, l, "Temps de vidange maximum admis (après la pluie)", projet.temps_vidange_max_h, "h", "0.0"); l += 1
    c_qinf = _label(ws, l, "Débit d'infiltration Q = 1000.S.K/coef",
                    f"=1000*{c_sinf.coordinate}*{c_k.coordinate}/{c_cs.coordinate}", "l/s", "0.000",
                    fond=VERT_PALE)
    l_qinf = l; l += 1
    # Un bassin versant amont raccordé compte dans la surface qui fixe le débit
    # de fuite admissible, si l'utilisateur a demandé de le prendre en compte.
    amont = projet.amont
    if amont.actif and amont.inclure_bv_dans_ajutage:
        c_qadm = _label(ws, l, "Débit de fuite admissible (5 l/s/ha, BV amont compris)",
                        f"=5*(C{l_tot}+{amont.surface_bv_m2})/10000", "l/s", "0.000"); l += 2
    else:
        c_qadm = _label(ws, l, "Débit de fuite admissible (5 l/s/ha)",
                        f"=5*C{l_tot}/10000", "l/s", "0.000"); l += 2

    if amont.actif:
        _titre(ws, f"A{l}", "2 bis. Bassin d'orage amont", 12)
        l += 1
        c_sam = _label(ws, l, "Surface du bassin versant amont", amont.surface_bv_m2, "m²", "0"); l += 1
        c_cam = _label(ws, l, "Coefficient de ruissellement moyen amont", amont.coef_ruissellement,
                       "[-]", "0.00"); l += 1
        _label(ws, l, "Surface active amont",
               f"={c_sam.coordinate}*{c_cam.coordinate}", "m²", "0.0", fond=VERT_PALE); l += 1
        _label(ws, l, "Volume de temporisation amont", amont.volume_temporisation_m3, "m³", "0.0"); l += 1
        c_sdam = _label(ws, l, "Surface de dispersion amont", amont.surface_dispersion_m2,
                        "m²", "0.0"); l += 1
        c_kam = _label(ws, l, "Vitesse d'infiltration amont", amont.k_infiltration_ms,
                       "m/s", "0.00E+00"); l += 1
        c_qajam = _label(ws, l, "Débit d'ajutage amont", amont.debit_ajutage_ls, "l/s", "0.000"); l += 1
        _label(ws, l, "Débit d'infiltration amont",
               f"=1000*{c_sdam.coordinate}*{c_kam.coordinate}/{c_cs.coordinate}", "l/s", "0.000",
               fond=VERT_PALE); l += 1
        _label(ws, l, "Débit restitué vers l'ouvrage aval",
               f"={c_qajam.coordinate}", "l/s", "0.000", fond=VERT_PALE); l += 2

    _titre(ws, f"A{l}", "3. Ouvrage encodé", 12)
    l += 1
    c_vtot = _label(ws, l, "Volume total du bassin",
                    f"=Ouvrages!$I${renvoi}" if renvoi else projet.bassin.volume_total_m3,
                    "m³", "0.0"); l += 1
    c_vsous = _label(ws, l, "Volume sous l'axe de l'ajutage",
                     f"=Ouvrages!$J${renvoi}" if renvoi else projet.bassin.volume_sous_ajutage_m3,
                     "m³", "0.0"); l += 1
    c_sdisp = _label(ws, l, "Surface de dispersion du bassin",
                     f"=Ouvrages!$K${renvoi}" if renvoi else projet.bassin.surface_dispersion_m2,
                     "m²", "0.0"); l += 1
    c_qbas = _label(ws, l, "Débit d'ajutage du bassin",
                    f"=Ouvrages!$N${renvoi}" if renvoi else projet.bassin.debit_ajutage_ls,
                    "l/s", "0.000"); l += 1
    c_qinfb = _label(ws, l, "Débit d'infiltration du bassin",
                     f"=1000*{c_sdisp.coordinate}*{c_k.coordinate}/{c_cs.coordinate}", "l/s", "0.000",
                     fond=VERT_PALE); l += 1
    c_h = _label(ws, l, "Charge sur l'ajutage (axe -> trop-plein)", projet.hauteur_charge_m, "m", "0.00"); l += 1
    c_cd = _label(ws, l, "Coefficient de débit Cd", projet.coef_debit_orifice, "[-]", "0.00")

    noms = {
        "S_ponderee": f"Projet!$D${l_tot}",
        "S_totale": f"Projet!$C${l_tot}",
        "K_infiltration": f"Projet!${c_k.column_letter}${c_k.row}",
        "Coef_securite": f"Projet!${c_cs.column_letter}${c_cs.row}",
        "S_infiltration": f"Projet!${c_sinf.column_letter}${c_sinf.row}",
        "Q_infiltration": f"Projet!$B${l_qinf}",
        "Q_ajutage": f"Projet!${c_qaj.column_letter}${c_qaj.row}",
        "T_vidange_max": f"Projet!${c_tvid.column_letter}${c_tvid.row}",
        "V_bassin": f"Projet!${c_vtot.column_letter}${c_vtot.row}",
        "V_sous_ajutage": f"Projet!${c_vsous.column_letter}${c_vsous.row}",
        "S_dispersion": f"Projet!${c_sdisp.column_letter}${c_sdisp.row}",
        "Q_ajutage_bassin": f"Projet!${c_qbas.column_letter}${c_qbas.row}",
        "Q_infiltration_bassin": f"Projet!${c_qinfb.column_letter}${c_qinfb.row}",
        "Charge_orifice": f"Projet!${c_h.column_letter}${c_h.row}",
        "Cd_orifice": f"Projet!${c_cd.column_letter}${c_cd.row}",
    }
    for nom, ref in noms.items():
        wb.defined_names.add(DefinedName(nom, attr_text=ref))
    _legende_couleurs(ws, c_cd.row + 2)


def _liste(ws, plage: str, valeurs, titre: str, message: str) -> None:
    """Liste déroulante sur une plage : on choisit, on ne tape pas.

    Une cellule qui commande le classeur mais n'accepte que douze valeurs doit
    le dire. Taper « 35 ans » à la main donnait un #N/A muet là où la liste
    montre d'emblée ce qui existe.
    """
    dv = DataValidation(type="list", formula1='"' + ",".join(str(v) for v in valeurs) + '"',
                        allow_blank=False, showDropDown=False)
    dv.error = message
    dv.errorTitle = titre
    dv.prompt = message
    dv.promptTitle = titre
    ws.add_data_validation(dv)
    dv.add(plage)


def _borne(ws, plage: str, titre: str, message: str, mini=None, maxi=None) -> None:
    """Garde-fou de saisie : une valeur impossible se refuse à l'entrée."""
    if mini is not None and maxi is not None:
        dv = DataValidation(type="decimal", operator="between",
                            formula1=str(mini), formula2=str(maxi), allow_blank=True)
    elif mini is not None:
        dv = DataValidation(type="decimal", operator="greaterThanOrEqual",
                            formula1=str(mini), allow_blank=True)
    else:
        dv = DataValidation(type="decimal", operator="lessThanOrEqual",
                            formula1=str(maxi), allow_blank=True)
    dv.error = message
    dv.errorTitle = titre
    ws.add_data_validation(dv)
    dv.add(plage)


def _legende_couleurs(ws, ligne: int) -> None:
    """Le code couleur du classeur, écrit une fois, là où on le lit d'abord."""
    _titre(ws, f"A{ligne}", "Comment lire ce classeur", 12)
    entrees = (
        (ORANGE_PALE, "Valeur en dur",
         "à vous de la remplir ou de la vérifier : elle ne se recalcule pas."),
        (GRIS_PALE, "Donnée source",
         "tables du GTI, abaque des diamètres, constante g, grille des durées balayées."),
        (VERT_PALE, "Résultat calculé", "formule : il suit ce que vous modifiez."),
    )
    for i, (couleur, titre, explication) in enumerate(entrees, start=1):
        c = ws.cell(row=ligne + i, column=1, value=titre)
        c.fill = PatternFill("solid", fgColor=couleur)
        c.font = Font(bold=True)
        c.border = _BORDURE
        ws.cell(row=ligne + i, column=2, value=explication).font = Font(size=9, color="475569")
    ws.cell(row=ligne + len(entrees) + 1, column=1, value=(
        "Tout le reste est une formule. Une cellule sans couleur et sans formule n'existe "
        "pas : un test du dépôt refuse qu'il en apparaisse une.")).font = Font(
            italic=True, size=9, color="475569")


def _projet_systeme(wb: Workbook, ws, dossier: Dossier) -> None:
    """Feuille « Projet » d'une étude de réseau : elle décrit le SYSTÈME.

    Elle décrivait l'ouvrage affiché à l'écran — ses surfaces, son sol, son
    bassin — sous un intitulé « Ouvrage détaillé par ce classeur » : le
    classeur mono-bassin qui subsistait. Or chaque ouvrage a désormais sa ligne
    sur « Ouvrages » et ses propres feuilles de calcul. Ne restent donc ici que
    ce qui vaut pour tout le projet : ses bassins versants, les contraintes
    communes, et le récapitulatif de ses bassins d'orage.
    """
    projet = dossier.projet
    systeme = dossier.systeme
    ws.cell(row=12, column=1, value="Composition").font = Font(bold=True)
    ws.cell(row=12, column=2, value=(
        f"{len(systeme.bassins_versants)} bassins versants, "
        f"{len(systeme.ouvrages)} bassins d'orage")).font = Font(bold=True, color=BLEU)

    # 1. Bassins versants : les surfaces sont saisies sur leur feuille ; ici on
    # les totalise. Les renvois sont posés par _relier_surfaces_projet, qui
    # connaît les lignes de cette feuille-là.
    _titre(ws, "A13", "1. Bassins versants du projet", 12)
    _entete(ws, 14, ["Bassin versant", "Raccordé au bassin d'orage", "Surface [m²]",
                     "C moyen", "S active [m²]"])
    ligne = 15
    for versant in systeme.bassins_versants:
        aval = systeme.ouvrage(versant.bassin_id)
        ws.cell(row=ligne, column=1, value=versant.nom).border = _BORDURE
        ws.cell(row=ligne, column=2,
                value=aval.nom if aval is not None else "non raccordé").border = _BORDURE
        for col in (3, 4, 5):
            ws.cell(row=ligne, column=col).border = _BORDURE
        ligne += 1
    l_tot = ligne
    ws._plage_versants = (15, l_tot - 1)  # type: ignore[attr-defined]
    ws.cell(row=l_tot, column=1, value="TOTAL").font = Font(bold=True)
    for col, fmt in ((3, "0"), (5, "0.0")):
        lettre = get_column_letter(col)
        c = ws.cell(row=l_tot, column=col, value=f"=SUM({lettre}15:{lettre}{l_tot - 1})")
        c.font = Font(bold=True)
        c.number_format = fmt
        c.fill = PatternFill("solid", fgColor=BLEU_PALE)
        c.border = _BORDURE
    c = ws.cell(row=l_tot, column=4, value=f"=IF(C{l_tot}>0,E{l_tot}/C{l_tot},0)")
    c.font = Font(bold=True)
    c.number_format = "0.000"
    c.fill = PatternFill("solid", fgColor=BLEU_PALE)
    c.border = _BORDURE

    # 2. Ce qui vaut pour tout le projet, et rien d'autre.
    l = l_tot + 2
    _titre(ws, f"A{l}", "2. Contraintes communes à tout le projet", 12); l += 1
    c_cs = _label(ws, l, "Coefficient de sécurité sur K", projet.coef_securite_infiltration,
                  "[-]", "0.0", fond=BLEU_PALE); l += 1
    c_tvid = _label(ws, l, "Temps de vidange maximum admis (après la pluie)",
                    projet.temps_vidange_max_h, "h", "0.0", fond=BLEU_PALE); l += 1
    c_h = _label(ws, l, "Charge sur l'ajutage (axe -> trop-plein)", projet.hauteur_charge_m,
                 "m", "0.00", fond=BLEU_PALE); l += 1
    c_cd = _label(ws, l, "Coefficient de débit Cd", projet.coef_debit_orifice,
                  "[-]", "0.00", fond=BLEU_PALE); l += 1
    ws.cell(row=l, column=1, value=(
        "Surfaces d'infiltration, K, ajutages et volumes construits sont propres à chaque "
        "ouvrage : ils se saisissent sur la feuille « Ouvrages ».")).font = Font(
            italic=True, size=9, color="475569")
    l += 2

    # 3. Les bassins d'orage, tous, avec leurs chiffres tirés d'« Ouvrages ».
    _titre(ws, f"A{l}", "3. Bassins d'orage du projet", 12); l += 1
    _entete(ws, l, ["Bassin d'orage", "Se déverse vers", "V encodé [m³]",
                    "V minimal [m³]", "Feuilles de calcul"])
    premiere = l + 1
    for i, fiche in enumerate(dossier.fiches):
        r, source = premiere + i, 5 + i
        ws.cell(row=r, column=1, value=f"=Ouvrages!$A${source}")
        ws.cell(row=r, column=2, value=f"=Ouvrages!$B${source}")
        ws.cell(row=r, column=3, value=f"=Ouvrages!$I${source}").number_format = "0.0"
        ws.cell(row=r, column=4, value=f"=Ouvrages!$Q${source}").number_format = "0.0"
        ws.cell(row=r, column=5, value=f"=Ouvrages!$S${source}")
        for col in range(1, 6):
            ws.cell(row=r, column=col).border = _BORDURE
    derniere = premiere + len(dossier.fiches) - 1
    l = derniere + 1
    ws.cell(row=l, column=1, value="TOTAL").font = Font(bold=True)
    for col in (3, 4):
        lettre = get_column_letter(col)
        c = ws.cell(row=l, column=col, value=f"=SUM({lettre}{premiere}:{lettre}{derniere})")
        c.font = Font(bold=True)
        c.number_format = "0.0"
        c.fill = PatternFill("solid", fgColor=BLEU_PALE)
        c.border = _BORDURE

    for nom, ref in (("Coef_securite", f"Projet!${c_cs.column_letter}${c_cs.row}"),
                     ("T_vidange_max", f"Projet!${c_tvid.column_letter}${c_tvid.row}"),
                     ("Charge_orifice", f"Projet!${c_h.column_letter}${c_h.row}"),
                     ("Cd_orifice", f"Projet!${c_cd.column_letter}${c_cd.row}")):
        wb.defined_names.add(DefinedName(nom, attr_text=ref))

    _borne(ws, f"B{c_cs.row}", "Coefficient de sécurité",
           "Le coefficient de sécurité sur K vaut au moins 1.", mini=1)
    _borne(ws, f"B{c_tvid.row}", "Temps de vidange",
           "Le temps de vidange maximum admis est un nombre d'heures positif.", mini=0.1)
    _borne(ws, f"B{c_h.row}", "Charge sur l'ajutage",
           "La charge sur l'ajutage est une hauteur positive, en mètres.", mini=0.01)
    _borne(ws, f"B{c_cd.row}", "Coefficient de débit",
           "Le coefficient de débit d'un orifice est compris entre 0 et 1.", mini=0, maxi=1)
    _legende_couleurs(ws, l + 2)


def _relier_surfaces_projet(wb: Workbook, dossier: Dossier,
                            cellules: Dict[str, Dict[str, List[int]]],
                            totaux: Optional[Dict[str, int]] = None) -> None:
    """Le bloc « Surfaces incidentes » de la feuille « Projet » renvoie aux versants.

    Il recopiait les surfaces de l'ouvrage détaillé : deux vérités pour la même
    donnée. Retoucher une surface sur « Bassins versants » — la feuille qui
    annonce pourtant qu'elle commande tout — ne changeait rien ici, et l'inverse
    non plus. Chaque ligne pointe désormais vers les cellules où cette
    occupation du sol est réellement saisie, pour cet ouvrage.
    """
    totaux = totaux or {}
    courant = dossier.ouvrage_courant
    par_libelle = cellules.get(getattr(courant, "id", ""), {})
    ws = wb["Projet"]
    plage_versants = getattr(ws, "_plage_versants", None)
    if plage_versants is not None:
        # Feuille de réseau : le tableau liste les bassins versants du projet,
        # chacun renvoyant aux lignes où ses surfaces sont saisies.
        source = _ref("Bassins versants")
        for i, versant in enumerate(dossier.systeme.bassins_versants):
            r = plage_versants[0] + i
            total = totaux.get(versant.id)
            if total is None:
                continue
            ws.cell(row=r, column=3, value=f"={source}!$E${total}").number_format = "0"
            ws.cell(row=r, column=5, value=f"={source}!$F${total}").number_format = "0.0"
            ws.cell(row=r, column=4,
                    value=f"=IF(C{r}>0,E{r}/C{r},0)").number_format = "0.000"
        return
    plage = getattr(ws, "_plage_surfaces", None)
    if plage is None or not par_libelle:
        return
    source = _ref("Bassins versants")
    for r in range(plage[0], plage[1] + 1):
        lignes = par_libelle.get(ws.cell(row=r, column=1).value)
        if not lignes:
            continue
        ws.cell(row=r, column=3,
                value="=" + "+".join(f"{source}!$E${l}" for l in lignes)).number_format = "0"
        # Le coefficient est le même sur toutes ces lignes : celui de la première.
        ws.cell(row=r, column=2,
                value=f"={source}!$D${lignes[0]}").number_format = "0.00"


def _feuille_versants(wb: Workbook, dossier: Dossier) -> Tuple[
        Dict[str, str], Dict[str, int], Dict[str, Dict[str, List[int]]]]:
    """Surfaces de chaque bassin versant, coefficient par coefficient.

    C'est ici que le classeur devient vivant : retoucher une surface ou un
    coefficient de ruissellement propage la correction jusqu'aux volumes, en
    passant par la surface active de l'ouvrage qui reçoit ce versant.

    Renvoie, par identifiant d'ouvrage, la formule donnant sa surface active
    propre.
    """
    systeme = dossier.systeme
    ws = wb.create_sheet("Bassins versants")
    _largeurs(ws, {"A": 26, "B": 30, "C": 34, "D": 12, "E": 14, "F": 18})
    _titre(ws, "A1", "Bassins versants et surfaces incidentes", 14)
    ws["A2"] = ("S active = coefficient x surface. Modifier une surface ou un coefficient "
                "recalcule l'ouvrage qui reçoit ce bassin versant.")
    ws["A2"].font = Font(italic=True, color="475569")

    _entete(ws, 4, ["Bassin versant", "Raccordé au bassin d'orage",
                    "Occupation du sol", "Coeff. [-]", "Surface [m²]", "S active [m²]"])
    ligne = 5
    plages: Dict[str, List[str]] = {}
    #: Par bassin versant, la ligne qui porte ses totaux — la synthèse s'y réfère
    #: au lieu de recopier des nombres.
    totaux: Dict[str, int] = {}
    #: Par ouvrage puis par occupation du sol, les lignes où la surface est
    #: saisie : la feuille « Projet » y renvoie au lieu de recopier.
    cellules: Dict[str, Dict[str, List[int]]] = {}
    for versant in systeme.bassins_versants:
        aval = systeme.ouvrage(versant.bassin_id)
        debut = ligne
        for surface in versant.surfaces:
            ws.cell(row=ligne, column=1, value=versant.nom if ligne == debut else "")
            ws.cell(row=ligne, column=2,
                    value=(aval.nom if aval is not None else "non raccordé") if ligne == debut else "")
            ws.cell(row=ligne, column=3, value=surface.libelle)
            ws.cell(row=ligne, column=4, value=surface.coefficient).number_format = "0.00"
            ws.cell(row=ligne, column=5, value=surface.aire_m2).number_format = "0"
            c = ws.cell(row=ligne, column=6, value=f"=D{ligne}*E{ligne}")
            c.number_format = "0.0"
            for col in range(1, 7):
                ws.cell(row=ligne, column=col).border = _BORDURE
            if aval is not None:
                cellules.setdefault(aval.id, {}).setdefault(surface.libelle, []).append(ligne)
            ligne += 1
        if aval is not None and ligne > debut:
            # Le nom de feuille qualifie la PLAGE, pas la fonction :
            # « 'Feuille'!SUM(...) » n'est pas une formule valide.
            plages.setdefault(aval.id, []).append(
                f"SUM({_ref('Bassins versants')}!$F${debut}:$F${ligne - 1})")
        if ligne > debut:
            c = ws.cell(row=ligne, column=6, value=f"=SUM(F{debut}:F{ligne - 1})")
            c.font = Font(bold=True)
            c.number_format = "0.0"
            ws.cell(row=ligne, column=3, value=f"Total {versant.nom}").font = Font(bold=True)
            ws.cell(row=ligne, column=5,
                    value=f"=SUM(E{debut}:E{ligne - 1})").number_format = "0"
            totaux[versant.id] = ligne
            ligne += 1
        ligne += 1

    if ligne > 5:
        _borne(ws, f"D5:D{ligne}", "Coefficient de ruissellement",
               "Un coefficient de ruissellement est compris entre 0 et 1.", mini=0, maxi=1)
        _borne(ws, f"E5:E{ligne}", "Surface",
               "Une surface se saisit en m², positive ou nulle.", mini=0)
    return ({ouvrage_id: "=" + "+".join(morceaux)
             for ouvrage_id, morceaux in plages.items()}, totaux, cellules)


def _feuille_ouvrages(wb: Workbook, dossier: Dossier,
                      surfaces: Dict[str, str]) -> List[_Ancrage]:
    """Données d'entrée de chaque ouvrage : une ligne, des cellules modifiables.

    Deux groupes, et il fallait les distinguer : les **hypothèses de
    dimensionnement** (ce que le calcul suppose) et l'**ouvrage construit** (ce
    qu'on mettra en œuvre). Les mélanger, comme le faisait la première version
    de cette feuille, rendait impossible de savoir à quoi une formule se
    rapportait.

    L'apport des ouvrages amont fait exception aux formules : il varie dans le
    temps et se poursuit après l'averse, ce qu'une cellule ne sait pas
    reproduire. Il est repris de l'application, dans une cellule modifiable —
    et les volumes en aval la suivent.
    """
    systeme = dossier.systeme
    ws = wb.create_sheet("Ouvrages")
    _largeurs(ws, {"A": 30, "B": 24, "C": 15, "D": 15, "E": 14, "F": 12, "G": 15, "H": 14,
                   "I": 13, "J": 14, "K": 14, "L": 13, "M": 16, "N": 15, "O": 14,
                   "P": 14, "Q": 15, "R": 18, "S": 20})
    _titre(ws, "A1", "Bassins d'orage - données d'entrée", 14)
    ws["A2"] = ("Une ligne par ouvrage. Les cellules orange se modifient : leur changement se "
                "propage aux feuilles de calcul de l'ouvrage concerné, puis à la synthèse.")
    ws["A2"].font = Font(italic=True, color="475569")

    # Bandeau de groupes, au-dessus des en-têtes de colonnes.
    for texte, debut_col, fin_col, fond in (
            ("Raccordements", 1, 4, GRIS_PALE),
            ("Hypothèses de dimensionnement", 5, 8, BLEU_PALE),
            ("Ouvrage construit", 9, 14, VERT_PALE),
            ("Apport amont (repris de l'application)", 15, 16, ORANGE_PALE),
            ("Résultat", 17, 19, GRIS_PALE)):
        ws.merge_cells(start_row=3, start_column=debut_col, end_row=3, end_column=fin_col)
        c = ws.cell(row=3, column=debut_col, value=texte)
        c.font = Font(bold=True, color=BLEU)
        c.alignment = Alignment(horizontal="center")
        c.fill = PatternFill("solid", fgColor=fond)

    _entete(ws, 4, [
        "Bassin d'orage", "Se déverse vers", "S active propre [m²]", "S active amont [m²]",
        "S infiltration [m²]", "K [m/s]", "Q infiltration [l/s]", "Q ajutage [l/s]",
        "V total [m³]", "V sous ajutage [m³]", "S dispersion [m²]", "K bassin [m/s]",
        "Q infiltration bassin [l/s]", "Q ajutage bassin [l/s]",
        "Apport amont [m³]", "Pointe amont [l/s]",
        "V minimal [m³]", "Surverse", "Feuilles de calcul",
    ])
    ws.row_dimensions[4].height = 40

    bleu = PatternFill("solid", fgColor=BLEU_PALE)
    vert = PatternFill("solid", fgColor=VERT_PALE)
    orange = PatternFill("solid", fgColor=ORANGE_PALE)
    ancrages: List[_Ancrage] = []
    colonne_scenario = {s: get_column_letter(2 + j) for j, s in enumerate(ORDRE_SCENARIOS)}
    for i, fiche in enumerate(dossier.fiches):
        o = fiche.ouvrage
        etude = o.etude
        bassin = etude.bassin
        r = 5 + i
        aval = systeme.aval(o.id)
        feuille_scen = _nom_de_feuille("Scénarios", i + 1)

        ws.cell(row=r, column=1, value=o.nom).font = Font(bold=True)
        ws.cell(row=r, column=2, value=aval.nom if aval is not None else "exutoire")
        ws.cell(row=r, column=3,
                value=surfaces.get(o.id, fiche.aire_ponderee_propre_m2)).number_format = "0.0"
        # La surface active amont est la somme des surfaces propres des ouvrages
        # situés en amont : elle se déduit, elle ne se recopie pas. Figée, elle
        # ne bougeait pas quand on retouchait un bassin versant amont.
        amonts = [a.id for a in systeme.amonts_transitifs(o.id)]
        lignes_amont = [5 + j for j, autre in enumerate(dossier.fiches)
                        if autre.ouvrage.id in amonts]
        if lignes_amont:
            somme = "+".join(f"$C${la}" for la in lignes_amont)
            ws.cell(row=r, column=4, value=f"={somme}").number_format = "0.0"
        else:
            ws.cell(row=r, column=4, value=0.0).number_format = "0.0"
        # Hypothèses de dimensionnement
        ws.cell(row=r, column=5, value=etude.surface_infiltration_m2).number_format = "0"
        ws.cell(row=r, column=6, value=etude.k_infiltration_ms).number_format = "0.00E+00"
        ws.cell(row=r, column=7,
                value=f"=1000*E{r}*F{r}/Coef_securite").number_format = "0.000"
        ws.cell(row=r, column=8, value=etude.debit_ajutage_ls).number_format = "0.000"
        # Ouvrage construit
        ws.cell(row=r, column=9, value=bassin.volume_total_m3).number_format = "0.0"
        ws.cell(row=r, column=10, value=bassin.volume_sous_ajutage_m3).number_format = "0.0"
        ws.cell(row=r, column=11, value=bassin.surface_dispersion_m2).number_format = "0"
        # Le fond réellement construit peut avoir sa propre vitesse
        # d'infiltration : un essai en fond de fouille donne rarement la valeur
        # supposée au dimensionnement. Vide, la colonne reprend l'hypothèse.
        ws.cell(row=r, column=12,
                value=(bassin.k_infiltration_ms if bassin.k_propre
                       else f"=F{r}")).number_format = "0.00E+00"
        ws.cell(row=r, column=13,
                value=f"=1000*K{r}*L{r}/Coef_securite").number_format = "0.000"
        ws.cell(row=r, column=14, value=bassin.debit_ajutage_ls).number_format = "0.000"
        # Apport amont, puis résultat
        ws.cell(row=r, column=15, value=round(fiche.apport_amont_m3, 2)).number_format = "0.00"
        ws.cell(row=r, column=16, value=round(fiche.q_amont_max_ls, 3)).number_format = "0.000"
        col = colonne_scenario[o.scenario]
        # Sans apport amont, le volume minimal est exactement celui que calcule
        # la feuille de scénarios. Avec, il ne s'en déduit pas : l'apport arrive
        # étalé dans le temps et s'évacue en partie au fur et à mesure, si bien
        # que l'ajouter au volume isolé le surestime — de 33 % sur le réseau de
        # démonstration. Seule l'intégration pas à pas donne la valeur juste ;
        # elle est reprise de l'application et signalée comme non recalculable.
        if fiche.apport_amont_m3 > 0:
            c_min = ws.cell(row=r, column=17, value=round(fiche.volume_minimal_m3, 1))
            c_min.fill = orange
        else:
            c_min = ws.cell(row=r, column=17, value=f"={_ref(feuille_scen)}!{col}5")
        c_min.number_format = "0.0"
        ws.cell(row=r, column=18,
                value="milieu naturel" if o.surverse_vers_milieu_naturel else
                      (aval.nom if aval is not None else "exutoire"))
        ws.cell(row=r, column=19, value=f"Pluie {i + 1} · Scénarios {i + 1}")

        for col_i in (5, 6, 8):
            ws.cell(row=r, column=col_i).fill = bleu
        for col_i in (9, 10, 11, 12, 14):
            ws.cell(row=r, column=col_i).fill = vert
        ws.cell(row=r, column=15).fill = orange
        for col_i in range(1, 20):
            ws.cell(row=r, column=col_i).border = _BORDURE

        ancrages.append(_Ancrage(
            ouvrage_id=o.id,
            nom=o.nom,
            feuille_pluie=_nom_de_feuille("Pluie", i + 1),
            feuille_scenarios=feuille_scen,
            s_ponderee=f"Ouvrages!$C${r}",
            q_infiltration=f"Ouvrages!$G${r}",
            q_ajutage=f"Ouvrages!$H${r}",
            v_bassin=f"Ouvrages!$I${r}",
            v_sous_ajutage=f"Ouvrages!$J${r}",
            apport_amont=f"Ouvrages!$O${r}",
            k_infiltration=f"Ouvrages!$F${r}",
            k_bassin=f"Ouvrages!$L${r}",
            s_dispersion=f"Ouvrages!$K${r}",
            q_infiltration_bassin=f"Ouvrages!$M${r}",
            q_ajutage_bassin=f"Ouvrages!$N${r}",
            volume_minimal=f"Ouvrages!$Q${r}",
            ligne=r,
        ))


    derniere = 5 + len(dossier.fiches) - 1
    if dossier.fiches:
        # Les colonnes bleues et vertes sont celles qu'on retouche : une valeur
        # impossible s'y refuse à l'entrée plutôt que de produire un volume
        # absurde trois feuilles plus loin.
        for colonne, titre, message in (
                ("E", "Surface d'infiltration", "Une surface se saisit en m², positive ou nulle."),
                ("I", "Volume total", "Un volume se saisit en m³, positif ou nul."),
                ("J", "Volume sous l'ajutage", "Un volume se saisit en m³, positif ou nul."),
                ("K", "Surface de dispersion", "Une surface se saisit en m², positive ou nulle."),
                ("H", "Débit d'ajutage", "Un débit d'ajutage se saisit en l/s, positif ou nul."),
                ("N", "Débit d'ajutage", "Un débit d'ajutage se saisit en l/s, positif ou nul.")):
            _borne(ws, f"{colonne}5:{colonne}{derniere}", titre, message, mini=0)
        for colonne in ("F", "L"):
            _borne(ws, f"{colonne}5:{colonne}{derniere}", "Coefficient d'infiltration K",
                   "K se saisit en m/s : une vitesse d'infiltration strictement positive "
                   "(1e-7 à 1e-3 pour les sols courants).", mini=1e-12, maxi=1)
    ws.cell(row=5 + len(dossier.fiches) + 1, column=1,
            value="Orange : valeur en dur. Les colonnes de saisie vous appartiennent ; "
                  "l'apport amont et le volume qui en dépend viennent de l'application et ne "
                  "se recalculent pas : "
                  "l'apport d'un ouvrage amont s'intègre pas à pas — il arrive étalé dans le "
                  "temps et s'évacue en partie au fur et à mesure —, ce qu'une formule de "
                  "cellule ne sait pas reproduire. L'ajouter au volume isolé le surestimerait."
            ).font = Font(italic=True, size=9, color="B45309")
    return ancrages


def _feuille_pluie(wb: Workbook, dossier: Dossier, ancrage: _Ancrage,
                   stats: Optional[Dict[str, object]] = None) -> None:
    projet = dossier.projet
    stats = stats or {}
    ws = wb.create_sheet(ancrage.feuille_pluie)
    _largeurs(ws, {"A": 14, "B": 12, "C": 12, "D": 14, "E": 14, "F": 16, "G": 16, "H": 18,
                   "I": 16, "J": 18, "K": 16, "L": 18, "M": 15, "N": 14, "O": 16, "P": 18,
                   "Q": 20})
    _titre(ws, "A1", f"{ancrage.nom} - {projet.commune_nom} - T = {projet.periode_retour} ans", 14)
    ws["A2"] = dossier.libelle_source
    ws["A2"].font = Font(italic=True, color="475569")

    # Les coefficients de Montana ne servent que si l'utilisateur a choisi cette
    # source : sinon le classeur recalculait des intensités de Montana alors que
    # l'application affichait les mesures QDF.
    src_pluie = rainfall.SourcePluie(projet.commune_ins, projet.periode_retour, projet.source_pluie)
    stat = _ref("Pluies statistiques")
    m0, m1 = stats.get("montana_premiere"), stats.get("montana_derniere")
    montana = None
    if src_pluie.source == rainfall.SOURCE_MONTANA:
        montana = rainfall.montana_coeffs(projet.commune_ins, projet.periode_retour)
        _entete(ws, 4, ["Coefficients de Montana", "a1", "b1", "a2", "b2", "a3", "b3"])
        ws.cell(row=5, column=1, value="i [mm/h] = a x t[min]^(-b)")
        for i, v in enumerate(montana):
            # Les coefficients dépendent de la récurrence : ils se cherchent
            # dans la table des douze, sur la période de retour de la feuille
            # « Projet ». Figés, ils rendaient cette cellule inerte — la
            # changer ne recalculait rien.
            if m0 and m1:
                colonne = get_column_letter(3 + i)
                valeur = (f"=INDEX({stat}!${colonne}${m0}:${colonne}${m1},"
                          f"MATCH(Projet!$B$10,{stat}!$B${m0}:$B${m1},0))")
            else:
                valeur = v
            ws.cell(row=5, column=2 + i, value=valeur).number_format = "0.0000"
        ws.cell(row=6, column=1, value="Plages : a1/b1 si t < 25 min | a2/b2 si 25 <= t <= 6000 min | a3/b3 si t > 6000 min")
        ws.cell(row=6, column=1).font = Font(italic=True, size=9, color="475569")

    l0 = 8
    _entete(ws, l0, [
        "Durée [min]", "a", "b", "i [mm/h]", "h [mm]", "V ruisselé [m³]",
        "[1] V évacué [m³]", "[1] V à maîtriser [m³]",
        "[2] V évacué [m³]", "[2] V à maîtriser [m³]",
        "[3] V évacué [m³]", "[3] V à maîtriser [m³]",
        "Q entrant [l/s]", "t seuil [min]", "[4] V évacué [m³]", "[4] V à maîtriser [m³]",
        "Q sortie minimal [l/s]",
    ])
    ws.freeze_panes = f"A{l0 + 1}"

    durees = _grille_durees(dossier)
    src = rainfall.SourcePluie(projet.commune_ins, projet.periode_retour, projet.source_pluie)
    h0, h1 = stats.get("premiere"), stats.get("derniere")
    l_periodes = stats.get("ligne_periodes")
    col_h0 = get_column_letter(int(stats.get("col_premiere", 3)))
    col_hn = get_column_letter(int(stats.get("col_premiere", 3)) + len(rainfall.RETURN_PERIODS) - 1)
    ligne = l0 + 1
    for d in durees:
        r = ligne
        ws.cell(row=r, column=1, value=d).number_format = "0.0"
        if montana:
            ws.cell(row=r, column=2, value=f"=IF(A{r}<25,$B$5,IF(A{r}<=6000,$D$5,$F$5))").number_format = "0.00"
            ws.cell(row=r, column=3, value=f"=IF(A{r}<25,$C$5,IF(A{r}<=6000,$E$5,$G$5))").number_format = "0.0000"
            ws.cell(row=r, column=4, value=f"=B{r}*A{r}^(-C{r})").number_format = "0.00"
        else:
            # Source QDF : les durées balayées SONT celles du GTI (la table ne
            # connaît que des durées normalisées), donc chaque ligne se lit
            # directement dans « Pluies statistiques » — ligne par la durée,
            # colonne par la période de retour. Écrite en dur, cette colonne
            # figeait tout le classeur d'un projet en mode QDF.
            if h0 and h1 and l_periodes:
                ws.cell(row=r, column=4, value=(
                    f"=INDEX({stat}!${col_h0}${h0}:${col_hn}${h1},"
                    f"MATCH($A{r},{stat}!$B${h0}:$B${h1},0),"
                    f"MATCH(Projet!$B$10,{stat}!${col_h0}${l_periodes}:"
                    f"${col_hn}${l_periodes},0))*60/$A{r}")).number_format = "0.00"
            else:
                ws.cell(row=r, column=4, value=src.intensite_mmh(d)).number_format = "0.00"
        ws.cell(row=r, column=5, value=f"=D{r}*A{r}/60").number_format = "0.00"
        ws.cell(row=r, column=6, value=f"=E{r}*{ancrage.s_ponderee}/1000").number_format = "0.00"
        # [1] temporisation seule : ajutage uniquement
        ws.cell(row=r, column=7, value=f"={ancrage.q_ajutage}*A{r}*60/1000").number_format = "0.00"
        ws.cell(row=r, column=8, value=f"=MAX(F{r}-G{r},0)").number_format = "0.00"
        # [2] dispersion seule : infiltration uniquement
        ws.cell(row=r, column=9, value=f"={ancrage.q_infiltration}*A{r}*60/1000").number_format = "0.00"
        ws.cell(row=r, column=10, value=f"=MAX(F{r}-I{r},0)").number_format = "0.00"
        # [3] temporisation + dispersion
        ws.cell(row=r, column=11, value=f"=({ancrage.q_infiltration}+{ancrage.q_ajutage})*A{r}*60/1000").number_format = "0.00"
        ws.cell(row=r, column=12, value=f"=MAX(F{r}-K{r},0)").number_format = "0.00"
        # [4] dispersion + temporisation au-delà du seuil (ajutage surélevé).
        # M : débit ruisselé entrant, N : instant où le niveau atteint l'axe de l'ajutage.
        ws.cell(row=r, column=13, value=f"=F{r}*1000/(A{r}*60)").number_format = "0.000"
        ws.cell(row=r, column=14,
                value=(f'=IF({ancrage.v_sous_ajutage}<=0,0,IF(M{r}-{ancrage.q_infiltration}<=0,"",'
                       f"{ancrage.v_sous_ajutage}*1000/(M{r}-{ancrage.q_infiltration})/60))")).number_format = "0.0"
        ws.cell(row=r, column=15,
                value=(f'=IF(N{r}="",{ancrage.q_infiltration}*A{r}*60/1000,'
                       f"({ancrage.q_infiltration}*A{r}+{ancrage.q_ajutage}*MAX(A{r}-N{r},0))*60/1000)")).number_format = "0.00"
        ws.cell(row=r, column=16, value=f"=MAX(F{r}-O{r},0)").number_format = "0.00"
        # Q : plus petit débit de sortie qui vidange dans le délai, pour CETTE
        # durée de pluie. Inversion de la condition de vidange (voir la note
        # sous le tableau) : le maximum de la colonne est le débit qui tient
        # pour toutes les durées, et donc le minimum cherché.
        ws.cell(row=r, column=17,
                value=f"=F{r}/(3.6*T_vidange_max+0.06*A{r})").number_format = "0.000"
        ligne += 1
    ws["A3"] = f"Plage balayée : {durees[0]:.0f} min a {durees[-1] / 1440:.0f} jours ({len(durees)} durées)"
    ws["A3"].font = Font(italic=True, size=9, color="475569")
    ws.cell(row=ligne + 1, column=1, value=(
        "Colonne Q - le volume se vidange dans le délai tant que V x 1000 / Q / 3600 <= "
        "T vidange max. En y portant V = h x S / 1000 - Q x t x 60 / 1000, le débit "
        "disparaît du maximum et la condition se résout : Q >= (h x S / 1000) / "
        "(3,6 x T vidange max + 0,06 x t). Le maximum de la colonne est donc le débit de "
        "sortie minimal, sans tâtonnement.")).font = Font(italic=True, size=9, color="475569")
    ws._plage_pluie = (l0 + 1, ligne - 1)  # type: ignore[attr-defined]


def _resultats_de(dossier: Dossier, ancrage: _Ancrage, fiche) -> Dict[str, object]:
    """Les quatre scénarios de cet ouvrage, et non ceux de l'ouvrage courant."""
    if fiche is None or dossier.systeme is None:
        return dict(dossier.resultats)
    etude = fiche.ouvrage.etude
    resultats = {}
    for scenario in ORDRE_SCENARIOS:
        try:
            resultats[scenario] = _hydro.dimensionner(etude, scenario)
        except Exception:
            continue
    return resultats


def _feuille_scenarios(wb: Workbook, dossier: Dossier, ancrage: _Ancrage,
                       fiche=None) -> None:
    ws_p = wb[ancrage.feuille_pluie]
    r0, r1 = ws_p._plage_pluie  # type: ignore[attr-defined]
    ws = wb.create_sheet(ancrage.feuille_scenarios)
    projet = dossier.projet
    _largeurs(ws, {"A": 52, "B": 16, "C": 16, "D": 16, "E": 16})
    _titre(ws, "A1", f"{ancrage.nom} - comparaison des quatre scénarios", 14)
    note = ("Les volumes et durées critiques sont recalculés par formules à partir de la "
            f"feuille '{ancrage.feuille_pluie}'.")
    # Sur un réseau, l'apport vient des ouvrages amont et se lit dans une
    # cellule modifiable ; sur un bassin isolé, du panneau « bassin amont ».
    apport_amont = (fiche.apport_amont_m3 > 0 if fiche is not None else projet.amont.actif)
    if apport_amont:
        note += (" Ces formules n'appliquent la méthode rationnelle qu'au bassin versant du "
                 "projet : l'apport du bassin d'orage amont varie dans le temps et se poursuit "
                 "après l'averse, il demande une intégration pas à pas qu'une formule de "
                 "cellule ne peut pas reproduire. Le volume à mettre en œuvre est donc celui "
                 "de la ligne « apport du bassin amont compris », reprise de l'application.")
    ws["A2"] = note
    ws["A2"].font = Font(italic=True, color="475569")

    colonnes = {SCENARIO_TEMPORISATION: ("H", "[1]"), SCENARIO_DISPERSION: ("J", "[2]"),
                SCENARIO_MIXTE: ("L", "[3]"), SCENARIO_SEUIL: ("P", "[4]")}
    _entete(ws, 4, ["Grandeur"] + [f"{colonnes[s][1]} {LIBELLES_SCENARIOS[s]}" for s in ORDRE_SCENARIOS])
    ws.row_dimensions[4].height = 46

    def plage(col: str) -> str:
        return ancrage.plage_pluie(col, r0, r1)

    lignes: List[Tuple[str, str, str]] = [
        ("Volume à maîtriser [m³]", "=MAX({p})", "0.0"),
        ("Durée critique [min]", "=INDEX({f}!$A${r0}:$A${r1},MATCH(MAX({p}),{p},0))", "0"),
        ("Hauteur de pluie [mm]", "=INDEX({f}!$E${r0}:$E${r1},MATCH(MAX({p}),{p},0))", "0.0"),
        ("Intensité [mm/h]", "=INDEX({f}!$D${r0}:$D${r1},MATCH(MAX({p}),{p},0))", "0.00"),
        ("Intensité [l/s/ha]", "=INDEX({f}!$D${r0}:$D${r1},MATCH(MAX({p}),{p},0))*10000/3600", "0.0"),
        ("Débit ruisselé de pointe [l/s]", "=MAX({p})*0+INDEX({f}!$F${r0}:$F${r1},MATCH(MAX({p}),{p},0))*1000/(INDEX({f}!$A${r0}:$A${r1},MATCH(MAX({p}),{p},0))*60)", "0.00"),
    ]
    ligne = 5
    for libelle, modele, fmt in lignes:
        ws.cell(row=ligne, column=1, value=libelle).font = Font(bold=True)
        for j, s in enumerate(ORDRE_SCENARIOS):
            col = colonnes[s][0]
            c = ws.cell(row=ligne, column=2 + j,
                        value=modele.format(p=plage(col), r0=r0, r1=r1,
                                            f=_ref(ancrage.feuille_pluie)))
            c.number_format = fmt
            c.border = _BORDURE
        ligne += 1

    debits = {
        SCENARIO_TEMPORISATION: f"={ancrage.q_ajutage}",
        SCENARIO_DISPERSION: f"={ancrage.q_infiltration}",
        SCENARIO_MIXTE: f"={ancrage.q_infiltration}+{ancrage.q_ajutage}",
        SCENARIO_SEUIL: f"={ancrage.q_infiltration}+{ancrage.q_ajutage}",
    }
    ws.cell(row=ligne, column=1, value="Débit de sortie [l/s]").font = Font(bold=True)
    for j, s in enumerate(ORDRE_SCENARIOS):
        c = ws.cell(row=ligne, column=2 + j, value=debits[s])
        c.number_format = "0.000"
        c.border = _BORDURE
    l_debit = ligne
    ligne += 1

    ws.cell(row=ligne, column=1, value="Temps de vidange après la pluie [h]").font = Font(bold=True)
    for j, s in enumerate(ORDRE_SCENARIOS):
        col_lettre = get_column_letter(2 + j)
        if s == SCENARIO_SEUIL:
            formule = (f"=IF({col_lettre}{l_debit}<=0,\"\",(MAX({col_lettre}5-{ancrage.v_sous_ajutage},0)*1000/"
                       f"{col_lettre}{l_debit}+MIN({col_lettre}5,{ancrage.v_sous_ajutage})*1000/"
                       f"MAX({ancrage.q_infiltration},0.0000001))/3600)")
        else:
            formule = f"=IF({col_lettre}{l_debit}<=0,\"\",{col_lettre}5*1000/{col_lettre}{l_debit}/3600)"
        c = ws.cell(row=ligne, column=2 + j, value=formule)
        c.number_format = "0.0"
        c.border = _BORDURE
        c.fill = PatternFill("solid", fgColor=BLEU_PALE)
    l_vid = ligne
    ligne += 1
    ws.cell(row=ligne, column=1, value="Conforme au temps de vidange maximum ?").font = Font(bold=True)
    for j in range(len(ORDRE_SCENARIOS)):
        col_lettre = get_column_letter(2 + j)
        c = ws.cell(row=ligne, column=2 + j,
                    value=f'=IF({col_lettre}{l_vid}="","-",IF({col_lettre}{l_vid}<=T_vidange_max,"OUI","NON"))')
        c.border = _BORDURE
    ligne += 2

    if apport_amont:
        titre = ("Volume à maîtriser, apport des ouvrages amont compris [m³]" if fiche is not None
                 else "Volume à maîtriser, apport du bassin amont compris [m³]")
        ws.cell(row=ligne, column=1, value=titre).font = Font(bold=True, color=BLEU)
        for j, s_scen in enumerate(ORDRE_SCENARIOS):
            col_lettre = get_column_letter(2 + j)
            if fiche is not None:
                # Pas une somme : l'apport amont s'intègre pas à pas (voir la
                # note de la feuille « Ouvrages »). La valeur vient du moteur.
                valeur = round(_resultats_de(dossier, ancrage, fiche)[s_scen].volume_m3, 1)
            else:
                # Bassin isolé : la valeur qui fait foi vient de l'application.
                valeur = round(dossier.resultats[s_scen].volume_m3, 1)
            c = ws.cell(row=ligne, column=2 + j, value=valeur)
            c.number_format = "0.0"
            c.border = _BORDURE
            c.fill = PatternFill("solid", fgColor=BLEU_PALE)
        ligne += 2

    # Les minima et le scénario retenu décrivent CET ouvrage : sur un réseau, les
    # reprendre du dossier afficherait ceux de l'ouvrage courant sur toutes les
    # feuilles.
    resultats = _resultats_de(dossier, ancrage, fiche)
    scenario_retenu = (fiche.ouvrage.scenario if fiche is not None
                       else dossier.scenario_principal)
    ws.cell(row=ligne, column=1, value="Valeurs minimales pour tenir le temps de vidange").font = Font(bold=True, color=BLEU)
    ligne += 1
    q_mini = f"MAX({plage('Q')})"
    # Ces minima se calculent : le débit de sortie minimal est le maximum de la
    # colonne Q de la feuille des pluies, dont on retranche l'organe déjà en
    # place. Deux cas y échappent et gardent la valeur du moteur : le scénario
    # à seuil, où l'ajutage surélevé ne s'ouvre qu'après un instant qui dépend
    # lui-même de l'infiltration, et un ouvrage alimenté par l'amont, dont la
    # vidange s'intègre pas à pas.
    figes = apport_amont
    formules = {
        "surface_infiltration_min_m2": {
            SCENARIO_DISPERSION: f"=IF({ancrage.k_infiltration}<=0,\"-\","
                                 f"MAX({q_mini},0)*Coef_securite/(1000*{ancrage.k_infiltration}))",
            SCENARIO_MIXTE: f"=IF({ancrage.k_infiltration}<=0,\"-\","
                            f"MAX({q_mini}-{ancrage.q_ajutage},0)*Coef_securite"
                            f"/(1000*{ancrage.k_infiltration}))",
        },
        "debit_ajutage_min_ls": {
            SCENARIO_TEMPORISATION: f"=MAX({q_mini},0)",
            SCENARIO_MIXTE: f"=MAX({q_mini}-{ancrage.q_infiltration},0)",
        },
    }
    for libelle, cle in (("Surface d'infiltration minimale [m²]", "surface_infiltration_min_m2"),
                         ("Débit d'ajutage minimal [l/s]", "debit_ajutage_min_ls")):
        ws.cell(row=ligne, column=1, value=libelle).font = Font(bold=True)
        for j, s in enumerate(ORDRE_SCENARIOS):
            v = getattr(resultats[s], cle) if s in resultats else None
            formule = None if figes else formules[cle].get(s)
            if v is None:
                c = ws.cell(row=ligne, column=2 + j, value="-")
            elif formule is not None:
                c = ws.cell(row=ligne, column=2 + j, value=formule)
            else:
                c = ws.cell(row=ligne, column=2 + j, value=round(v, 3))
                c.fill = PatternFill("solid", fgColor=ORANGE_PALE)
            c.number_format = "0.000"
            c.border = _BORDURE
        ligne += 1
    raison = ("l'apport des ouvrages amont s'intègre pas à pas" if figes
              else "l'ajutage surélevé ne s'ouvre qu'après un instant qui dépend de l'infiltration")
    ws.cell(row=ligne, column=1, value=(
        f"Cellules orange : valeur du moteur, {raison} — une formule de cellule ne sait pas "
        "le reproduire. Les autres se recalculent : modifier K, le temps de vidange maximum "
        "ou une surface les met à jour.")).font = Font(italic=True, size=9, color="B45309")
    ligne += 1

    ligne += 1
    ws.cell(row=ligne, column=1, value="Scénario retenu").font = Font(bold=True)
    ws.cell(row=ligne, column=2, value=LIBELLES_SCENARIOS[scenario_retenu]).font = Font(bold=True, color=BLEU)
    ligne += 2
    principal = resultats.get(scenario_retenu)
    alertes = (principal.alertes + principal.messages) if principal is not None else []
    if alertes:
        ws.cell(row=ligne, column=1, value="Observations").font = Font(bold=True, color="B45309")
        ligne += 1
        for a in alertes:
            # Les alertes du moteur sont formatées en Python : sans ``fr`` elles
            # arrivent dans le classeur avec un point décimal, là où l'écran, le
            # PDF et le Word affichent une virgule.
            ws.cell(row=ligne, column=1, value=fr(a)).fill = PatternFill("solid", fgColor=ORANGE_PALE)
            ligne += 1


def _feuille_bassin(wb: Workbook, dossier: Dossier, ancrage: _Ancrage,
                    stats: Dict[str, object], rang: int = 0) -> None:
    """Pluies absorbées sans débordement, par formules.

    Les 228 volumes de cette table étaient figés : modifier une surface ou un
    ajutage laissait le tableau inchangé, et le classeur se contredisait
    lui-même. Chaque volume se déduit pourtant de la hauteur de pluie — qui est
    sur la feuille « Pluies statistiques » — et des caractéristiques de
    l'ouvrage : V = h x S_active / 1000 - Q_sortie x t x 60 / 1000, jamais
    négatif.
    """
    nom = f"Table QDF {rang}" if rang else "Bassin - table QDF"
    ws = wb.create_sheet(nom)
    _largeurs(ws, {"A": 24, "B": 14})
    for i in range(len(rainfall.RETURN_PERIODS)):
        ws.column_dimensions[get_column_letter(3 + i)].width = 11
    _titre(ws, "A1", f"{ancrage.nom} - pluies absorbées sans débordement", 14)
    ws["A2"] = (f"{dossier.titre_table_volumes}. Volume requis par la pluie (méthode "
                "rationnelle) comparé à la capacité de l'ouvrage construit. "
                "Vert = absorbé, orange = limite (>95 %), rouge = débordement.")
    ws["A2"].font = Font(italic=True, color="475569")

    _label(ws, 4, "Volume total du bassin", f"={ancrage.v_bassin}", "m³", "0.0")
    _label(ws, 5, "Volume sous l'axe de l'ajutage", f"={ancrage.v_sous_ajutage}", "m³", "0.0")
    _label(ws, 6, "Surface de dispersion", f"={ancrage.s_dispersion}", "m²", "0.0")
    _label(ws, 7, "Débit d'infiltration du bassin",
           f"={ancrage.q_infiltration_bassin}", "l/s", "0.000")
    _label(ws, 8, "Débit d'ajutage du bassin", f"={ancrage.q_ajutage_bassin}", "l/s", "0.000")
    _label(ws, 9, "Surface active raccordée", f"={ancrage.s_ponderee}", "m²", "0.0")
    c_sortie = _label(ws, 10, "Débit de sortie total",
                      f"={ancrage.q_infiltration_bassin}+{ancrage.q_ajutage_bassin}",
                      "l/s", "0.000")
    sortie = f"$B${c_sortie.row}"
    surface = "$B$9"

    if dossier.simulation:
        sim = dossier.simulation
        _label(ws, 12, "Événement critique - durée", sim.duree_pluie_min, "min", "0")
        # La hauteur se DÉDUIT de la durée critique : Montana donne
        # i = a x t^(-b) puis h = i x t / 60. La durée de la simulation
        # appartient à la grille fine du moteur, donc la formule s'applique
        # exactement. Recopiée, cette hauteur ne suivait pas la période de
        # retour.
        pluie = _ref(ancrage.feuille_pluie)
        p_bassin = dossier.projet
        if rainfall.a_donnees_montana(p_bassin.commune_ins) and \
                p_bassin.source_pluie == rainfall.SOURCE_MONTANA:
            hauteur = (f"=IF($B$12<25,{pluie}!$B$5*$B$12^(-{pluie}!$C$5),"
                       f"IF($B$12<=6000,{pluie}!$D$5*$B$12^(-{pluie}!$E$5),"
                       f"{pluie}!$F$5*$B$12^(-{pluie}!$G$5)))*$B$12/60")
        else:
            hauteur = sim.hauteur_pluie_mm
        _label(ws, 13, "Événement critique - hauteur", hauteur, "mm", "0.0")
        _label(ws, 14, "Volume stocké maximum", sim.volume_max_m3, "m³", "0.0")
        # Le taux, lui, est un rapport : il suit le volume de l'ouvrage si on
        # le retouche dans le classeur.
        _label(ws, 15, "Taux de remplissage",
               f"=IF({ancrage.v_bassin}<=0,0,$B$14/{ancrage.v_bassin})", "[-]", "0.0%")
        _label(ws, 16, "Volume débordé", sim.volume_debordement_m3, "m³", "0.00")
        _label(ws, 17, "Temps de vidange après la pluie", sim.temps_vidange_h, "h", "0.0")
        _label(ws, 18, "Statut", sim.statut,
               fond=VERT_PALE if not sim.debordement else ROUGE_PALE)
        ws.cell(row=19, column=1,
                value="La simulation s'intègre pas à pas : la durée et la hauteur critiques, "
                      "le volume stocké, le débordement et la vidange viennent de "
                      "l'application et ne se recalculent pas ici.").font = Font(
                          italic=True, size=9, color="475569")

    l0 = 21
    ws.cell(row=l0 - 1, column=1,
            value="Volume requis [m³] — lignes : durée de pluie, colonnes : période de "
                  "retour. Recalculé depuis « Pluies statistiques »."
            ).font = Font(bold=True)
    _entete(ws, l0, ["Durée de pluie", "Durée [min]"]
            + [f"{rp} ans" for rp in rainfall.RETURN_PERIODS])
    ws.freeze_panes = f"C{l0 + 1}"

    premiere = int(stats["premiere"])
    col_h0 = int(stats["col_premiere"])
    feuille_stats = _ref("Pluies statistiques")
    capacite = f"$B$4"
    for i, libelle in enumerate(rainfall.QDF_DURATION_LABELS):
        r = l0 + 1 + i
        ws.cell(row=r, column=1, value=libelle).font = Font(bold=True)
        ws.cell(row=r, column=2,
                value=f"={feuille_stats}!$B${premiere + i}").number_format = "0"
        for j in range(len(rainfall.RETURN_PERIODS)):
            hauteur = f"{feuille_stats}!{get_column_letter(col_h0 + j)}${premiere + i}"
            c = ws.cell(row=r, column=3 + j,
                        value=f"=MAX({hauteur}*{surface}/1000-{sortie}*$B{r}*60/1000,0)")
            c.number_format = "0.0"
            c.border = _BORDURE

    # La couleur suit le volume : c'est une mise en forme conditionnelle, pas
    # une valeur figée, sans quoi elle mentirait dès la première modification.
    from openpyxl.formatting.rule import CellIsRule

    plage = (f"{get_column_letter(3)}{l0 + 1}:"
             f"{get_column_letter(2 + len(rainfall.RETURN_PERIODS))}"
             f"{l0 + len(rainfall.QDF_DURATION_LABELS)}")
    ws.conditional_formatting.add(plage, CellIsRule(
        operator="greaterThan", formula=[capacite],
        fill=PatternFill("solid", bgColor=ROUGE_PALE)))
    ws.conditional_formatting.add(plage, CellIsRule(
        operator="greaterThan", formula=[f"0.95*{capacite}"],
        fill=PatternFill("solid", bgColor=ORANGE_PALE)))
    ws.conditional_formatting.add(plage, CellIsRule(
        operator="lessThanOrEqual", formula=[f"0.95*{capacite}"],
        fill=PatternFill("solid", bgColor=VERT_PALE)))

    l = l0 + 2 + len(rainfall.QDF_DURATION_LABELS)
    table = dossier.table
    if table is not None:
        rp_max = table.periode_retour_max_acceptee()
        _label(ws, l, "Période de retour maximale absorbée sans débordement",
               f"{rp_max} ans" if rp_max else "aucune (déjà dépassée à 2 ans)",
               fond=VERT_PALE if rp_max else ROUGE_PALE)


def _feuille_ajutage(wb: Workbook, dossier: Dossier, ancrage: Optional[_Ancrage] = None,
                     rang: int = 0) -> None:
    ws = wb.create_sheet(f"Ajutage {rang}" if rang else "Ajutage")
    _largeurs(ws, {"A": 46, "B": 16, "C": 14, "D": 16, "E": 16})
    _titre(ws, "A1", (f"{ancrage.nom} - " if ancrage else "")
           + "dimensionnement de l'ajutage - formule de Torricelli", 14)
    ws["A2"] = "Q = Cd x A x racine(2 g h) - orifice en paroi mince, charge h entre l'axe de l'orifice et le trop-plein."
    ws["A2"].font = Font(italic=True, color="475569")

    vise = (f"=IF({ancrage.q_ajutage_bassin}>0,{ancrage.q_ajutage_bassin},{ancrage.q_ajutage})"
            if ancrage else "=IF(Q_ajutage_bassin>0,Q_ajutage_bassin,Q_ajutage)")
    _label(ws, 4, "Débit d'ajutage visé", vise, "l/s", "0.000")
    _label(ws, 5, "Charge h (axe orifice -> trop-plein)", "=Charge_orifice", "m", "0.00")
    _label(ws, 6, "Coefficient de débit Cd", "=Cd_orifice", "[-]", "0.00")
    _label(ws, 7, "Accélération de la pesanteur g", 9.81, "m/s²", "0.00")
    _label(ws, 8, "Section requise A = Q / (Cd.racine(2gh))", "=(B4/1000)/(B6*SQRT(2*B7*B5))", "m²", "0.000000",
           fond=BLEU_PALE)
    _label(ws, 9, "Section requise", "=B8*10000", "cm²", "0.00", fond=BLEU_PALE)
    _label(ws, 10, "Diamètre requis d = racine(4A/pi)", "=2*SQRT(B8/PI())*1000", "mm", "0.0", fond=BLEU_PALE)
    _label(ws, 11, "Vitesse dans l'orifice v = Cd.racine(2gh)", "=B6*SQRT(2*B7*B5)", "m/s", "0.00")

    from ..core.orifice import DIAMETRES_COMMERCIAUX_MM

    l0 = 16
    a0, a1 = l0 + 1, l0 + len(DIAMETRES_COMMERCIAUX_MM)
    if dossier.orifice and dossier.orifice.diametre_commercial_mm:
        # Le moteur retient le plus grand diamètre commercial INFÉRIEUR OU ÉGAL
        # au diamètre requis, pour ne pas dépasser le débit de fuite autorisé.
        # MATCH(...;1) sur l'abaque croissant donne exactement cela ; en dessous
        # du plus petit diamètre, il n'y a pas de choix possible et on prend
        # celui-là. Figé, ce diamètre ne suivait ni la charge, ni le Cd, ni le
        # débit visé.
        _label(ws, 12, "Diamètre commercial retenu (par defaut)",
               f"=IFERROR(INDEX($A${a0}:$A${a1},MATCH($B$10,$A${a0}:$A${a1},1)),$A${a0})",
               "mm", "0")
        _label(ws, 13, "Débit réel du diamètre retenu",
               "=B6*PI()*(B12/1000)^2/4*SQRT(2*B7*B5)*1000", "l/s", "0.000", fond=VERT_PALE)

    ws.cell(row=l0 - 1, column=1, value="Abaque des diamètres commerciaux (charge = h ci-dessus)").font = Font(bold=True)
    _entete(ws, l0, ["Diamètre [mm]", "Section [cm²]", "Débit [l/s]"])
    for i, d in enumerate(DIAMETRES_COMMERCIAUX_MM):
        r = l0 + 1 + i
        ws.cell(row=r, column=1, value=d).border = _BORDURE
        ws.cell(row=r, column=2, value=f"=PI()*(A{r}/1000)^2/4*10000").number_format = "0.00"
        ws.cell(row=r, column=3, value=f"=$B$6*PI()*(A{r}/1000)^2/4*SQRT(2*$B$7*$B$5)*1000").number_format = "0.000"


def _feuille_statistiques(wb: Workbook, dossier: Dossier) -> Dict[str, object]:
    """Hauteurs et intensités du GTI, telles quelles.

    Ce sont des données sources : elles ne se calculent pas. Mais la table des
    volumes requis, elle, s'en déduit — c'est pourquoi cette feuille expose
    l'adresse de son tableau des hauteurs et une colonne de durées en minutes,
    exploitables par une formule.
    """
    projet = dossier.projet
    ws = wb.create_sheet("Pluies statistiques")
    _largeurs(ws, {"A": 16, "B": 13})
    for i in range(len(rainfall.RETURN_PERIODS)):
        ws.column_dimensions[get_column_letter(3 + i)].width = 10
    _titre(ws, "A1", f"Pluies statistiques GTI - {projet.commune_nom} ({projet.commune_ins})", 14)
    ws["A2"] = dossier.source_pluies_datee
    ws["A2"].font = Font(italic=True, color="475569")

    mm = rainfall.table_qdf_mm(projet.commune_ins, projet.source_pluie)
    lsha = rainfall.table_qdf_ls_ha(projet.commune_ins, projet.source_pluie)
    ancre: Dict[str, object] = {}
    for titre, table, fmt, depart in (("Hauteurs de pluie [mm]", mm, "0.0", 4),
                                      ("Intensités [l/s/ha]", lsha, "0.0", 4 + len(mm) + 4)):
        ws.cell(row=depart - 1, column=1, value=titre).font = Font(bold=True, color=BLEU)
        _entete(ws, depart, ["Durée", "Durée [min]"] + [""] * len(rainfall.RETURN_PERIODS))
        # Les périodes de retour sont écrites comme NOMBRES, affichées « 25 ans » :
        # une formule peut alors les retrouver par MATCH, et la période de retour
        # de la feuille « Projet » devient un vrai paramètre du classeur.
        for j, rp in enumerate(rainfall.RETURN_PERIODS):
            c = ws.cell(row=depart, column=3 + j, value=int(rp))
            c.number_format = '0" ans"'
        for i, ligne in enumerate(table):
            r = depart + 1 + i
            ws.cell(row=r, column=1, value=rainfall.QDF_DURATION_LABELS[i]).font = Font(bold=True)
            ws.cell(row=r, column=2,
                    value=float(rainfall.QDF_DURATIONS_MIN[i])).number_format = "0"
            for j, v in enumerate(ligne):
                c = ws.cell(row=r, column=3 + j, value=None if v is None else round(v, 2))
                c.number_format = fmt
                c.border = _BORDURE
                if rainfall.RETURN_PERIODS[j] == projet.periode_retour:
                    c.fill = PatternFill("solid", fgColor=BLEU_PALE)
        if titre.startswith("Hauteurs"):
            ancre = {"premiere": depart + 1, "derniere": depart + len(table),
                     "col_duree": "B", "col_premiere": 3, "ligne_periodes": depart}

    # Coefficients de Montana pour les douze périodes de retour. Sans eux, la
    # période de retour de la feuille « Projet » ne commandait rien : les
    # coefficients de la feuille « Pluie n » étaient figés sur la récurrence
    # choisie dans l'application, et la changer dans le classeur ne recalculait
    # rien du tout.
    if rainfall.a_donnees_montana(projet.commune_ins):
        depart = 4 + len(mm) + 4 + len(lsha) + 4
        ws.cell(row=depart - 1, column=1,
                value="Coefficients de Montana - i [mm/h] = a x t[min]^(-b)").font = Font(
                    bold=True, color=BLEU)
        _entete(ws, depart, ["Période de retour", "T [ans]", "a1", "b1", "a2", "b2", "a3", "b3"])
        for i, rp in enumerate(rainfall.RETURN_PERIODS):
            r = depart + 1 + i
            ws.cell(row=r, column=1, value=f"{rp} ans").font = Font(bold=True)
            ws.cell(row=r, column=2, value=int(rp)).number_format = "0"
            for j, v in enumerate(rainfall.montana_coeffs(projet.commune_ins, rp)):
                c = ws.cell(row=r, column=3 + j, value=round(v, 4))
                c.number_format = "0.0000"
                c.border = _BORDURE
            if rp == projet.periode_retour:
                for j in range(6):
                    ws.cell(row=r, column=3 + j).fill = PatternFill("solid", fgColor=BLEU_PALE)
        ancre["montana_premiere"] = depart + 1
        ancre["montana_derniere"] = depart + len(rainfall.RETURN_PERIODS)
    return ancre


def _tableau(ws, ligne: int, lignes: Sequence[Sequence[str]],
             fonds: Optional[Dict[int, str]] = None,
             colonnes_teintees: Optional[Sequence[int]] = None) -> int:
    """Écrit un tableau (entête + lignes) et renvoie la première ligne libre.

    ``colonnes_teintees`` restreint la teinte d'état à quelques colonnes. Une
    teinte sur toute la ligne entrerait sinon en conflit avec l'orange qui
    signale une valeur non liée : deux codes couleur pour deux choses
    différentes sur la même cellule ne disent plus rien.
    """
    _entete(ws, ligne, list(lignes[0]))
    fonds = fonds or {}
    for i, valeurs in enumerate(lignes[1:], start=1):
        for j, valeur in enumerate(valeurs):
            # Les nombres sont écrits comme nombres : le classeur doit rester
            # exploitable, pas seulement lisible.
            c = ws.cell(row=ligne + i, column=1 + j, value=_nombre_ou_texte(valeur))
            c.border = _BORDURE
            if j <= 1:
                # Les deux premières colonnes portent des noms d'ouvrage, qui
                # peuvent être longs : ils se replient plutôt que d'être coupés
                # par la cellule voisine.
                c.alignment = Alignment(vertical="center", wrap_text=True)
            if i in fonds and (colonnes_teintees is None or (1 + j) in colonnes_teintees):
                c.fill = PatternFill("solid", fgColor=fonds[i])
    return ligne + len(lignes) + 1


def _nombre_ou_texte(valeur: str):
    try:
        return float(valeur)
    except (TypeError, ValueError):
        return valeur


def _feuille_reseau(wb: Workbook, dossier: Dossier,
                    ancrages: Sequence[_Ancrage] = (),
                    totaux_versants: Optional[Dict[str, int]] = None) -> None:
    """Feuille « Réseau » : raccordements, bassins versants, ouvrages, simulation.

    Les formules vives des autres feuilles ne peuvent pas reproduire le réseau :
    une cellule ne sait pas intégrer pas à pas l'hydrogramme d'un ouvrage amont.
    Cette feuille rapporte donc les résultats calculés par l'application, et le
    dit explicitement.
    """
    systeme = dossier.systeme
    ws = wb.create_sheet("Réseau")
    _largeurs(ws, {"A": 40, "B": 28, "C": 24, "D": 16, "E": 16, "F": 16, "G": 16, "H": 16,
                   "I": 16, "J": 18, "K": 18})
    _titre(ws, "A1", "SYNTHÈSE DU RÉSEAU", 16)
    ws["A2"] = (f"{systeme.commune_nom} · pluie de projet T = {systeme.periode_retour} ans · "
                f"vidange maximale admise {systeme.temps_vidange_max_h:.0f} h")
    ws["A2"].font = Font(italic=True, color="475569")
    ws["A3"] = ("Valeurs calculées par l'application : l'apport d'un ouvrage amont s'intègre pas "
                "à pas et aucune formule de cellule ne sait le reproduire.")
    ws["A3"].font = Font(italic=True, color="B45309")

    ligne = 5
    _titre(ws, f"A{ligne}", "1. Raccordements", 12)
    ligne += 1
    for texte in mod_schema.arbre_texte(systeme, dossier.fiches):
        ws.cell(row=ligne, column=1, value=texte)
        ligne += 1
    ligne += 1

    # Ces deux tableaux recopiaient des nombres qui vivent ailleurs : modifier une
    # surface laissait la synthèse inchangée, et le classeur se contredisait.
    _titre(ws, f"A{ligne}", "2. Bassins versants", 12)
    ligne += 1
    _entete(ws, ligne, ["Bassin versant", "Raccordé à", "Surface [m²]", "C moyen",
                        "Surface active [m²]"])
    debut_bv = ligne + 1
    for versant in systeme.bassins_versants:
        ligne += 1
        aval = systeme.ouvrage(versant.bassin_id)
        r_total = (totaux_versants or {}).get(versant.id)
        ws.cell(row=ligne, column=1, value=versant.nom)
        ws.cell(row=ligne, column=2, value=aval.nom if aval is not None else "non raccordé")
        if r_total:
            src = _ref("Bassins versants")
            ws.cell(row=ligne, column=3, value=f"={src}!$E${r_total}").number_format = "0"
            ws.cell(row=ligne, column=4,
                    value=f"=IF({src}!$E${r_total}=0,0,{src}!$F${r_total}/{src}!$E${r_total})"
                    ).number_format = "0.000"
            ws.cell(row=ligne, column=5, value=f"={src}!$F${r_total}").number_format = "0.0"
        else:
            ws.cell(row=ligne, column=3, value=versant.aire_totale_m2).number_format = "0"
            ws.cell(row=ligne, column=4, value=versant.coefficient_moyen).number_format = "0.000"
            ws.cell(row=ligne, column=5, value=versant.aire_ponderee_m2).number_format = "0.0"
        for c in range(1, 6):
            ws.cell(row=ligne, column=c).border = _BORDURE
    ligne += 1
    ws.cell(row=ligne, column=1, value="TOTAL").font = Font(bold=True)
    for col, fmt in ((3, "0"), (5, "0.0")):
        lettre = get_column_letter(col)
        c = ws.cell(row=ligne, column=col,
                    value=f"=SUM({lettre}{debut_bv}:{lettre}{ligne - 1})")
        c.number_format = fmt
        c.font = Font(bold=True)
        c.fill = PatternFill("solid", fgColor=BLEU_PALE)
    c = ws.cell(row=ligne, column=4, value=f"=IF(C{ligne}=0,0,E{ligne}/C{ligne})")
    c.number_format = "0.000"
    c.font = Font(bold=True)
    c.fill = PatternFill("solid", fgColor=BLEU_PALE)
    ligne += 2

    _titre(ws, f"A{ligne}", "3. Dimensionnement de chaque bassin d'orage", 12)
    ligne += 1
    _entete(ws, ligne, ["Bassin d'orage", "Se déverse vers", "S active propre [m²]",
                        "S active amont [m²]", "V minimal [m³]", "V encodé [m³]",
                        "Suffisant ?"])
    for ancrage, fiche in zip(ancrages, dossier.fiches):
        ligne += 1
        aval = systeme.aval(fiche.ouvrage.id)
        r = ancrage.ligne
        ws.cell(row=ligne, column=1, value=ancrage.nom).font = Font(bold=True)
        ws.cell(row=ligne, column=2, value=aval.nom if aval is not None else "exutoire")
        ws.cell(row=ligne, column=3, value=f"=Ouvrages!$C${r}").number_format = "0.0"
        ws.cell(row=ligne, column=4, value=f"=Ouvrages!$D${r}").number_format = "0.0"
        ws.cell(row=ligne, column=5, value=f"={ancrage.volume_minimal}").number_format = "0.0"
        ws.cell(row=ligne, column=6, value=f"={ancrage.v_bassin}").number_format = "0.0"
        ws.cell(row=ligne, column=7,
                value=f'=IF(F{ligne}>=E{ligne},"OUI","NON")')
        for c in range(1, 8):
            ws.cell(row=ligne, column=c).border = _BORDURE
    ligne += 2

    if ancrages:
        _titre(ws, f"A{ligne}", "4. Récapitulatif recalculé par formules", 12)
        ligne += 1
        ws.cell(row=ligne, column=1,
                value="Ces lignes se recalculent : elles tirent leurs volumes des feuilles de "
                      "chaque ouvrage, qui tirent les leurs de « Bassins versants » et "
                      "« Ouvrages ».").font = Font(italic=True, size=9, color="475569")
        ligne += 1
        _entete(ws, ligne, ["Bassin d'orage", "Scénario retenu", "V minimal [m³]",
                            "Apport amont [m³]", "V à mettre en œuvre [m³]",
                            "V encodé [m³]", "Suffisant ?"])
        ligne += 1
        colonne_scenario = {s: get_column_letter(2 + j) for j, s in enumerate(ORDRE_SCENARIOS)}
        for ancrage, fiche in zip(ancrages, dossier.fiches):
            col = colonne_scenario[fiche.ouvrage.scenario]
            feuille = _ref(ancrage.feuille_scenarios)
            ws.cell(row=ligne, column=1, value=ancrage.nom).font = Font(bold=True)
            ws.cell(row=ligne, column=2, value=LIBELLES_SCENARIOS[fiche.ouvrage.scenario])
            ws.cell(row=ligne, column=3, value=f"={feuille}!{col}5").number_format = "0.0"
            ws.cell(row=ligne, column=4, value=f"={ancrage.apport_amont}").number_format = "0.00"
            ws.cell(row=ligne, column=5,
                    value=f"=C{ligne}+D{ligne}").number_format = "0.0"
            ws.cell(row=ligne, column=6, value=f"={ancrage.v_bassin}").number_format = "0.0"
            ws.cell(row=ligne, column=7,
                    value=f'=IF(F{ligne}>=E{ligne},"OUI","NON")')
            for col_i in range(1, 8):
                ws.cell(row=ligne, column=col_i).border = _BORDURE
            ligne += 1
        c = ws.cell(row=ligne, column=1, value="Total")
        c.font = Font(bold=True)
        for col_i, lettre in ((5, "E"), (6, "F")):
            c = ws.cell(row=ligne, column=col_i,
                        value=f"=SUM({lettre}{ligne - len(ancrages)}:{lettre}{ligne - 1})")
            c.font = Font(bold=True)
            c.number_format = "0.0"
            c.fill = PatternFill("solid", fgColor=BLEU_PALE)
        ligne += 2

    sim = dossier.simulation_systeme
    if sim is not None:
        _titre(ws, f"A{ligne}", "5. Simulation du système complet", 12)
        ligne += 1
        ws.cell(row=ligne, column=1,
                value=fr(f"Averse la plus défavorable : {sim.hauteur_mm:.1f} mm en "
                         f"{sim.duree_min:.0f} min, T = {sim.periode_retour} ans"))
        ligne += 1
        couleurs = {"OK": VERT_PALE, "LIMITE": ORANGE_PALE, "DEBORDEMENT": ROUGE_PALE,
                    "NON CONFORME": ROUGE_PALE, "NON ENCODE": GRIS_PALE}
        fonds = {i: couleurs[res.statut] for i, (_o, res) in enumerate(sim.resultats, start=1)}
        l_entete = ligne
        ligne = _tableau(ws, ligne, synthese_simulation_systeme(dossier), fonds,
                         colonnes_teintees=(1, 8))
        # La pointe et le débordement sortent d'une intégration pas à pas ; la
        # capacité et le taux de remplissage, eux, se déduisent — et doivent
        # suivre si l'on retouche le volume de l'ouvrage dans le classeur.
        par_id = {a.ouvrage_id: a for a in ancrages}
        premiere = l_entete + 1
        for i, (ouvrage, _res) in enumerate(sim.resultats):
            r = premiere + i
            ancrage = par_id.get(ouvrage.id)
            if ancrage is not None and ancrage.v_bassin:
                ws.cell(row=r, column=3, value=f"={ancrage.v_bassin}").number_format = "0.0"
            ws.cell(row=r, column=4,
                    value=f"=IF(C{r}<=0,0,B{r}/C{r}*100)").number_format = "0"
            # L'apport amont de CETTE colonne n'est pas celui de la feuille
            # « Ouvrages » : ici c'est le volume restitué sous l'averse la plus
            # défavorable du système entier, là celui de la pluie critique du
            # seul ouvrage (49,7 contre 50,8 m³ sur le réseau de démonstration).
            # Les confondre ferait dire au classeur une chose pour une autre.
            # La vidange s'écrit comme une DURÉE et non comme du texte : même
            # affichage (« 16 h 13 »), mais la ligne du bas peut en prendre le
            # maximum au lieu de recopier un nombre.
            c_vid = ws.cell(row=r, column=7, value=_res.temps_vidange_h / 24.0)
            c_vid.number_format = '[h]" h "mm'
            c_vid.border = _BORDURE
        derniere = premiere + len(sim.resultats) - 1
        _label(ws, ligne, "Volume stocké par le réseau",
               f"=SUM(B{premiere}:B{derniere})", "m³", "0.0")
        _label(ws, ligne + 1, "Débordement total", f"=SUM(E{premiere}:E{derniere})", "m³", "0.00",
               fond=ROUGE_PALE if sim.ouvrages_en_debordement else VERT_PALE)
        c_max = _label(ws, ligne + 2, "Vidange la plus longue",
                       f"=MAX(G{premiere}:G{derniere})", "h")
        c_max.number_format = '[h]" h "mm'

        ws.cell(row=ligne + 3, column=1, value=(
            "Pointe, débordement, apport amont et vidange viennent de l'application : la "
            "simulation du réseau intègre les hydrogrammes pas à pas, ce qu'une formule de "
            "cellule ne sait pas faire. Capacité, remplissage et totaux, eux, se recalculent."
        )).font = Font(italic=True, size=9, color="B45309")
        ligne += 5

    anomalies = systeme.anomalies()
    if anomalies:
        _titre(ws, f"A{ligne}", "Anomalies du réseau", 12)
        for i, anomalie in enumerate(anomalies, start=1):
            c = ws.cell(row=ligne + i, column=1, value=anomalie)
            c.font = Font(color="B45309")


def ecrire(dossier: Dossier, chemin: str) -> str:
    """Génère le rapport Excel et renvoie le chemin du fichier."""
    wb = construire_classeur(dossier)
    wb.save(chemin)
    return chemin
