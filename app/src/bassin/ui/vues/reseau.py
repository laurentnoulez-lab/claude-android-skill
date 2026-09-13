"""Vue « Réseau » : les bassins d'orage, leurs raccordements et leur dimensionnement.

C'est ici que se déclare la topologie — quel bassin se déverse dans quel autre,
où part la surverse — et que chaque ouvrage est confronté à son volume minimal,
à sa surface d'infiltration minimale et à son ajutage minimal.
"""

from __future__ import annotations

from typing import List

import flet as ft

from ...core.model import LIBELLES_SCENARIOS
from ...reports.dossier import ORDRE_SCENARIOS
from .. import theme
from .base import Vue


#: Une liste déroulante Flet dont la valeur est la chaîne vide n'affiche rien :
#: l'exutoire a donc besoin d'une valeur à lui, distincte de « non renseigné ».
EXUTOIRE_LISTE = "__exutoire__"


class VueReseau(Vue):
    titre = "Réseau"
    icone = ft.Icons.ACCOUNT_TREE
    sous_titre = "Bassins d'orage et raccordements"

    def __init__(self, page, etat):
        super().__init__(page, etat)
        self._ouvert = None

    # ---------------------------------------------------------- un ouvrage
    def _carte_ouvrage(self, ouvrage) -> ft.Control:
        etat = self.etat
        systeme = etat.systeme
        # Sans choix explicite, c'est la carte de l'ouvrage étudié qui est
        # dépliée : l'écran ne s'ouvre jamais sur une liste entièrement repliée.
        ouvert = ((self._ouvert or systeme.ouvrage_courant) == ouvrage.id
                  or len(systeme.ouvrages) == 1)
        fiche = etat.fiche(ouvrage.id)

        def maj_nom(e: ft.ControlEvent) -> None:
            ouvrage.nom = e.control.value

        def maj_note(e: ft.ControlEvent) -> None:
            ouvrage.note = e.control.value

        def maj_aval(e: ft.ControlEvent) -> None:
            ouvrage.aval_id = "" if e.control.value == EXUTOIRE_LISTE else (e.control.value or "")
            etat.invalider()
            self.rafraichir()

        def maj_scenario(e: ft.ControlEvent) -> None:
            ouvrage.scenario = e.control.value
            etat.invalider()
            self.rafraichir()

        def basculer_surverse(e: ft.ControlEvent) -> None:
            ouvrage.surverse_vers_milieu_naturel = bool(e.control.value)
            etat.invalider()
            self.rafraichir()

        def basculer_bv_amont(e: ft.ControlEvent) -> None:
            ouvrage.compter_bv_amont_dans_ajutage = bool(e.control.value)
            etat.invalider()
            self.rafraichir()

        def basculer(_=None) -> None:
            self._ouvert = None if ouvert else ouvrage.id
            self.rafraichir()

        def etudier(_=None) -> None:
            etat.choisir_ouvrage(ouvrage.id)
            self.rafraichir()
            self.notifier(f"« {ouvrage.nom} » est l'ouvrage étudié dans les autres onglets.",
                          "succes")

        def supprimer(_=None) -> None:
            nom = ouvrage.nom
            if not etat.supprimer_ouvrage(ouvrage.id):
                self.notifier("Un projet garde au moins un bassin d'orage.", "alerte")
                return
            self.rafraichir()
            orphelins = etat.systeme.versants_orphelins()
            if orphelins:
                # Supprimer un ouvrage terminal laisse ses bassins versants sans
                # destination : le dire, sinon leur ruissellement disparaît des
                # calculs sans que personne ne s'en aperçoive.
                self.notifier(
                    f"« {nom} » supprimé. À raccorder ailleurs : "
                    + ", ".join(f"« {bv.nom} »" for bv in orphelins) + ".", "alerte")
            else:
                self.notifier(f"« {nom} » supprimé.", "succes")

        def reprendre(_=None) -> None:
            if fiche is None:
                return
            ouvrage.etude.bassin.volume_total_m3 = round(fiche.volume_minimal_m3 * 1.05, 1)
            etat.invalider()
            self.rafraichir()

        # Un ouvrage ne peut pas se déverser dans lui-même ni dans ce qu'il alimente :
        # les choix qui créeraient une boucle ne sont tout simplement pas proposés.
        interdits = {ouvrage.id} | {a.id for a in systeme.amonts_transitifs(ouvrage.id)}
        options = [ft.dropdown.Option(EXUTOIRE_LISTE, "Exutoire (milieu naturel)")]
        options += [ft.dropdown.Option(o.id, o.nom) for o in systeme.ouvrages
                    if o.id not in interdits]

        raccordement = ft.ResponsiveRow(
            [
                ft.Container(
                    ft.TextField(label="Nom du bassin d'orage", value=ouvrage.nom, dense=True,
                                 border_radius=10, on_change=maj_nom),
                    col={"xs": 12, "md": 4},
                ),
                ft.Container(
                    ft.Dropdown(label="Se déverse vers",
                                value=ouvrage.aval_id or EXUTOIRE_LISTE,
                                options=options, on_change=maj_aval, dense=True,
                                border_radius=10),
                    col={"xs": 12, "md": 4},
                ),
                theme.selecteur("Scénario de dimensionnement", ouvrage.scenario,
                                [(s, LIBELLES_SCENARIOS[s]) for s in ORDRE_SCENARIOS],
                                maj_scenario, col={"xs": 12, "md": 4}),
            ],
            spacing=12, run_spacing=12,
        )

        cases: List[ft.Control] = []
        if ouvrage.aval_id:
            cases.append(ft.Row(
                [
                    ft.Checkbox(value=ouvrage.surverse_vers_milieu_naturel,
                                on_change=basculer_surverse),
                    ft.Text("La surverse (trop-plein) part vers le milieu naturel plutôt que "
                            "vers le bassin aval", size=13, expand=True, no_wrap=False),
                ],
                spacing=8, vertical_alignment=ft.CrossAxisAlignment.CENTER))
        else:
            cases.append(ft.Text(
                "Cet ouvrage rejette à l'exutoire : son ajutage comme sa surverse partent "
                "au milieu naturel.", size=11.5, color=theme.GRIS, no_wrap=False))
        if systeme.amonts_directs(ouvrage.id):
            cases.append(ft.Row(
                [
                    ft.Checkbox(value=ouvrage.compter_bv_amont_dans_ajutage,
                                on_change=basculer_bv_amont),
                    ft.Text("Compter les bassins versants situés en amont dans la surface "
                            "raccordée (débit de fuite admissible et ajutage en l/(s·ha))",
                            size=13, expand=True, no_wrap=False),
                ],
                spacing=8, vertical_alignment=ft.CrossAxisAlignment.CENTER))
            cases.append(ft.Text(
                theme.fr(f"Surface raccordée retenue : {ouvrage.etude.aire_raccordee_m2:.0f} m² "
                         f"· débit de fuite admissible "
                         f"{ouvrage.etude.debit_fuite_admissible_ls:.3f} l/s"),
                size=11.5, color=theme.GRIS, no_wrap=False))

        chiffres: List[ft.Control] = []
        if fiche is not None:
            res = fiche.resultat
            chiffres = [
                ft.ResponsiveRow(
                    [
                        ft.Container(theme.tuile(
                            theme.nombre(fiche.volume_minimal_m3, 1),
                            "Volume minimal sans surverse", "m³", theme.BLEU, ft.Icons.WATER,
                            f"encodé : {theme.nombre(fiche.volume_encode_m3, 1)} m³"),
                            col={"xs": 12, "sm": 6, "md": 3}),
                        ft.Container(theme.tuile(
                            "—" if res.surface_infiltration_min_m2 is None
                            else theme.nombre(res.surface_infiltration_min_m2, 1),
                            "Surface d'infiltration minimale", "m²", theme.VERT, ft.Icons.GRASS,
                            f"pour vidanger en {systeme.temps_vidange_max_h:.0f} h"
                            if etat.minima_disponibles else "à calculer ci-dessus"),
                            col={"xs": 12, "sm": 6, "md": 3}),
                        ft.Container(theme.tuile(
                            "—" if res.debit_ajutage_min_ls is None
                            else theme.nombre(res.debit_ajutage_min_ls, 3),
                            "Débit d'ajutage minimal", "l/s", theme.ORANGE, ft.Icons.TUNE,
                            f"pour vidanger en {systeme.temps_vidange_max_h:.0f} h"
                            if etat.minima_disponibles else "à calculer ci-dessus"),
                            col={"xs": 12, "sm": 6, "md": 3}),
                        ft.Container(theme.tuile(
                            res.temps_vidange_hm, "Vidange après la pluie", "",
                            theme.ARDOISE, ft.Icons.TIMELAPSE,
                            f"pluie critique {res.duree_critique_hm}"),
                            col={"xs": 12, "sm": 6, "md": 3}),
                    ],
                    spacing=12, run_spacing=12,
                ),
            ]
            if fiche.apport_amont_m3 > 0:
                chiffres.append(theme.message(
                    theme.fr(
                        f"Le volume minimal comprend l'apport des ouvrages amont : "
                        f"{fiche.apport_amont_m3:.1f} m³ restitués, pointe "
                        f"{fiche.q_amont_max_ls:.2f} l/s. Cet apport se poursuit après "
                        f"l'averse : il est intégré pas à pas, sans formule fermée."), "info"))
            if not fiche.suffisant and fiche.volume_encode_m3 > 0:
                chiffres.append(theme.message(
                    theme.fr(f"Ce bassin surverse : {fiche.volume_encode_m3:.1f} m³ encodés "
                             f"pour {fiche.volume_minimal_m3:.1f} m³ nécessaires."), "erreur"))

        couleur, fond = theme.COULEURS_STATUT.get(
            fiche.statut if fiche else "OK", (theme.GRIS, theme.GRIS_CLAIR))
        courant = ouvrage.id == etat.systeme.ouvrage_courant
        # Sur téléphone, quatre commandes à droite du titre ne laissaient au nom
        # qu'une colonne de quelques caractères : ils passent sous le titre.
        contenu: List[ft.Control] = [
            ft.ResponsiveRow(
                [
                    ft.Container(
                        ft.Row(
                            [
                                ft.Icon(ft.Icons.WATER_DAMAGE, color=couleur, size=20),
                                ft.Column(
                                    [
                                        ft.Text(ouvrage.nom or "Bassin d'orage sans nom",
                                                size=15, weight=ft.FontWeight.W_700,
                                                no_wrap=False),
                                        ft.Text(self._resume(ouvrage), size=11.5,
                                                color=theme.GRIS, no_wrap=False),
                                    ],
                                    spacing=0, expand=True, tight=True,
                                ),
                            ],
                            spacing=8, vertical_alignment=ft.CrossAxisAlignment.CENTER,
                        ),
                        col={"xs": 12, "md": 7},
                    ),
                    ft.Container(
                        ft.Row(
                            [
                                theme.etiquette("étudié" if courant else "étudier",
                                                theme.BLEU if courant else theme.GRIS,
                                                theme.BLEU_CLAIR if courant else theme.GRIS_CLAIR,
                                                ft.Icons.VISIBILITY),
                                ft.IconButton(ft.Icons.OPEN_IN_NEW,
                                              tooltip="Étudier cet ouvrage", on_click=etudier),
                                ft.IconButton(
                                    ft.Icons.EXPAND_LESS if ouvert else ft.Icons.EXPAND_MORE,
                                    tooltip="Replier" if ouvert else "Déplier",
                                    on_click=basculer),
                                ft.IconButton(ft.Icons.DELETE_OUTLINE,
                                              tooltip="Supprimer cet ouvrage",
                                              on_click=supprimer),
                            ],
                            spacing=4, alignment=ft.MainAxisAlignment.END, wrap=True,
                            run_spacing=4, vertical_alignment=ft.CrossAxisAlignment.CENTER,
                        ),
                        col={"xs": 12, "md": 5},
                    ),
                ],
                spacing=8, run_spacing=8,
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
            ),
        ]
        if ouvert:
            contenu += [ft.Divider(height=14), raccordement] + cases
            contenu += [
                ft.TextField(label="Remarque", value=ouvrage.note, dense=True, border_radius=10,
                             on_change=maj_note),
                ft.Divider(height=14),
            ] + chiffres
            if fiche is not None:
                contenu.append(ft.Row(
                    [
                        theme.bouton_secondaire(
                            f"Retenir {theme.nombre(fiche.volume_minimal_m3 * 1.05, 1)} m³ "
                            "(minimum + 5 %)", ft.Icons.AUTO_FIX_HIGH, reprendre),
                    ],
                    wrap=True, spacing=10))
        return ft.Container(
            content=ft.Column(contenu, spacing=8),
            padding=16,
            border_radius=theme.RAYON,
            bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
            border=ft.border.all(2 if courant else 1,
                                 theme.BLEU if courant else ft.Colors.OUTLINE_VARIANT),
        )

    def _resume(self, ouvrage) -> str:
        systeme = self.etat.systeme
        aval = systeme.aval(ouvrage.id)
        versants = systeme.versants_de(ouvrage.id)
        amonts = systeme.amonts_directs(ouvrage.id)
        entrees = []
        if versants:
            entrees.append(f"{len(versants)} bassin(s) versant(s)")
        if amonts:
            entrees.append(f"{len(amonts)} bassin(s) amont")
        return theme.fr(
            f"{ouvrage.etude.bassin.volume_total_m3:.1f} m³ · "
            f"ajutage {ouvrage.etude.bassin.debit_ajutage_ls:.2f} l/s · "
            + (", ".join(entrees) if entrees else "aucun apport")
            + " → " + (aval.nom if aval is not None else "exutoire"))

    # ------------------------------------------------------------ tableau
    def _tableau(self) -> ft.Control:
        systeme = self.etat.systeme
        lignes = []
        for fiche in self.etat.fiches:
            o = fiche.ouvrage
            aval = systeme.aval(o.id)
            couleur, fond = theme.COULEURS_STATUT.get(fiche.statut, (theme.GRIS, theme.GRIS_CLAIR))
            res = fiche.resultat
            lignes.append(ft.DataRow(cells=[
                ft.DataCell(ft.Text(o.nom, size=12, weight=ft.FontWeight.W_600)),
                ft.DataCell(ft.Text(aval.nom if aval is not None else "exutoire", size=12)),
                ft.DataCell(ft.Text(theme.nombre(fiche.aire_ponderee_propre_m2, 0), size=12)),
                ft.DataCell(ft.Text(theme.nombre(fiche.aire_ponderee_amont_m2, 0), size=12)),
                ft.DataCell(ft.Text(theme.nombre(fiche.volume_minimal_m3, 1), size=12)),
                ft.DataCell(ft.Text(theme.nombre(fiche.volume_encode_m3, 1), size=12)),
                ft.DataCell(ft.Text(res.duree_critique_hm, size=12)),
                ft.DataCell(ft.Text(res.temps_vidange_hm, size=12,
                                    color=theme.ROUGE
                                    if res.temps_vidange_h > systeme.temps_vidange_max_h else None)),
                ft.DataCell(ft.Text("—" if res.surface_infiltration_min_m2 is None
                                    else theme.nombre(res.surface_infiltration_min_m2, 1), size=12)),
                ft.DataCell(ft.Text("—" if res.debit_ajutage_min_ls is None
                                    else theme.nombre(res.debit_ajutage_min_ls, 3), size=12)),
                ft.DataCell(ft.Container(
                    ft.Text(theme.LIBELLES_STATUT.get(fiche.statut, fiche.statut), size=11,
                            color=couleur, weight=ft.FontWeight.W_700),
                    bgcolor=fond, padding=ft.padding.symmetric(3, 8), border_radius=8)),
            ]))
        return theme.tableau_defilant(
            ft.DataTable(
                columns=theme.entete_tableau(
                    ["Bassin d'orage", "Vers", "S active propre [m²]", "S active amont [m²]",
                     "V minimal [m³]", "V encodé [m³]", "Pluie critique", "Vidange",
                     "S infiltration min [m²]", "Q ajutage min [l/s]", "Statut"]),
                rows=lignes, column_spacing=16, heading_row_height=38,
                data_row_max_height=44, divider_thickness=0.4,
            )
        )

    # ------------------------------------------------------------- actions
    def _ajouter(self, _=None) -> None:
        ouvrage = self.etat.ajouter_ouvrage()
        self._ouvert = ouvrage.id
        self.rafraichir()
        self.notifier(f"« {ouvrage.nom} » ajouté. Raccordez-lui un bassin versant.", "succes")

    def _basculer_minima(self, e: ft.ControlEvent) -> None:
        """Les minima par ouvrage coûtent cher : ils se demandent explicitement."""
        self.etat.minima_demandes = bool(e.control.value)
        self.rafraichir()

    def _dimensionner(self, _=None) -> None:
        retenus = self.etat.dimensionner_le_reseau()
        self.rafraichir()
        detail = " · ".join(theme.fr(f"{nom} {volume:.1f} m³") for nom, volume in retenus)
        self.notifier(f"Dimensionnement en cascade : {detail}", "succes")

    # --------------------------------------------------------------- rendu
    def resultats(self) -> List[ft.Control]:
        systeme = self.etat.systeme
        fiches = self.etat.fiches
        total_min = sum(f.volume_minimal_m3 for f in fiches)
        insuffisants = [f for f in fiches if not f.suffisant and f.volume_encode_m3 > 0]
        blocs: List[ft.Control] = [
            ft.Row(
                [
                    theme.etiquette(f"{len(systeme.ouvrages)} bassin(s) d'orage", theme.BLEU,
                                    theme.BLEU_CLAIR, ft.Icons.WATER_DAMAGE),
                    theme.etiquette(
                        f"Volume encodé {theme.nombre(systeme.volume_total_m3, 1)} m³",
                        theme.ARDOISE, theme.GRIS_CLAIR, ft.Icons.STACKED_LINE_CHART),
                    theme.etiquette(f"Volume minimal cumulé {theme.nombre(total_min, 1)} m³",
                                    theme.VERT if systeme.volume_total_m3 >= total_min
                                    else theme.ROUGE,
                                    theme.VERT_CLAIR if systeme.volume_total_m3 >= total_min
                                    else theme.ROUGE_CLAIR, ft.Icons.RULE),
                ],
                wrap=True, spacing=8, run_spacing=8,
            ),
        ]
        for anomalie in systeme.anomalies():
            blocs.append(theme.message(anomalie, "alerte"))
        if insuffisants:
            blocs.append(theme.message(
                "Ces ouvrages surversent pour la pluie de projet : "
                + ", ".join(f"« {f.nom} »" for f in insuffisants)
                + ". Le dimensionnement en cascade propose un volume pour chacun.", "erreur"))
        if not self.etat.minima_disponibles:
            blocs.append(ft.Row(
                [
                    ft.Switch(value=self.etat.minima_demandes,
                              on_change=self._basculer_minima),
                    ft.Text("Calculer aussi la surface d'infiltration minimale et l'ajutage "
                            "minimal de chaque ouvrage — deux dichotomies par bassin, le "
                            "recalcul devient nettement plus long sur un réseau de cette "
                            f"taille ({len(systeme.ouvrages)} ouvrages).",
                            size=12.5, expand=True, no_wrap=False),
                ],
                spacing=8, vertical_alignment=ft.CrossAxisAlignment.CENTER))
        blocs.append(self._tableau())
        return blocs

    def construire(self) -> List[ft.Control]:
        self.zone.controls = self.resultats()
        return [
            theme.section(
                "Dimensionnement du réseau",
                ft.Column(
                    [
                        ft.Text("Un bassin versant se raccorde à un seul bassin d'orage ; un "
                                "bassin d'orage se déverse dans un autre bassin ou à "
                                "l'exutoire. Les collecteurs sont supposés véhiculer tout le "
                                "débit et les temps de parcours sont négligés.",
                                size=12, color=theme.GRIS),
                        ft.Row(
                            [
                                theme.bouton_principal("Ajouter un bassin d'orage", ft.Icons.ADD,
                                                       self._ajouter),
                                theme.bouton_secondaire("Dimensionner en cascade",
                                                        ft.Icons.AUTO_FIX_HIGH,
                                                        self._dimensionner),
                                theme.bouton_secondaire("Recalculer", ft.Icons.REFRESH,
                                                        lambda _: self.maj_resultats()),
                            ],
                            wrap=True, spacing=10,
                        ),
                        ft.Text("« Dimensionner en cascade » calcule de l'amont vers l'aval : "
                                "un ouvrage amont correctement dimensionné ne surverse plus, "
                                "et l'ouvrage aval s'en trouve allégé. Les exutoires encodés "
                                "de chaque ouvrage sont accordés à son scénario, comme le fait "
                                "« Reprendre le dimensionnement ».",
                                size=11.5, color=theme.GRIS),
                        self.zone,
                    ],
                    spacing=14,
                ),
                ft.Icons.ACCOUNT_TREE,
            ),
            ft.Column([self._carte_ouvrage(o) for o in self.etat.systeme.ordre_amont_aval()],
                      spacing=12),
        ]
