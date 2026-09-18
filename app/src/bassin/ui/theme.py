"""Theme graphique et composants d'interface reutilisables."""

from __future__ import annotations

from typing import Callable, List, Optional, Sequence

import flet as ft

from ..core.model import DOMAINES
from ..formats import duree_h, fr, nombre  # noqa: F401  (réexportés pour les vues)

BLEU = "#1D4ED8"
BLEU_CLAIR = "#DBEAFE"
BLEU_FONCE = "#1E3A8A"
VERT = "#059669"
VERT_CLAIR = "#DCFCE7"
ORANGE = "#D97706"
ORANGE_CLAIR = "#FEF3C7"
ROUGE = "#DC2626"
ROUGE_CLAIR = "#FEE2E2"
GRIS = "#64748B"
GRIS_CLAIR = "#F1F5F9"
ARDOISE = "#0F172A"

RAYON = 14

COULEURS_STATUT = {
    "OK": (VERT, VERT_CLAIR),
    "LIMITE": (ORANGE, ORANGE_CLAIR),
    "DEBORDEMENT": (ROUGE, ROUGE_CLAIR),
    "NON CONFORME": (ROUGE, ROUGE_CLAIR),
    "NON ENCODE": (GRIS, GRIS_CLAIR),
}

LIBELLES_STATUT = {"OK": "OK", "LIMITE": "Limite", "DEBORDEMENT": "Débordement",
                   "NON CONFORME": "Non conforme", "NON ENCODE": "Non encodé"}


def appliquer_theme(page: ft.Page, sombre: bool = False) -> None:
    page.theme = ft.Theme(color_scheme_seed=BLEU, use_material3=True)
    page.dark_theme = ft.Theme(color_scheme_seed=BLEU, use_material3=True)
    page.theme_mode = ft.ThemeMode.DARK if sombre else ft.ThemeMode.LIGHT
    page.bgcolor = None
    page.padding = 0


def titre_section(texte: str, icone: Optional[str] = None, sous_titre: str = "") -> ft.Control:
    ligne: List[ft.Control] = []
    if icone:
        ligne.append(ft.Icon(icone, color=BLEU, size=22))
    colonne = [ft.Text(texte, size=17, weight=ft.FontWeight.W_700, no_wrap=False)]
    if sous_titre:
        colonne.append(ft.Text(sous_titre, size=12, color=GRIS, no_wrap=False))
    ligne.append(ft.Column(colonne, spacing=1, expand=True, tight=True))
    return ft.Row(ligne, spacing=10, vertical_alignment=ft.CrossAxisAlignment.START)


def carte(contenu: ft.Control, padding: int = 20) -> ft.Control:
    return ft.Container(
        content=contenu,
        padding=padding,
        border_radius=RAYON,
        bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
        border=ft.border.all(1, ft.Colors.OUTLINE_VARIANT),
    )


def section(titre: str, contenu: ft.Control, icone: Optional[str] = None, sous_titre: str = "") -> ft.Control:
    return carte(ft.Column([titre_section(titre, icone, sous_titre), ft.Divider(height=18, thickness=1), contenu],
                           spacing=2))


def tuile(valeur: str, libelle: str, unite: str = "", couleur: str = BLEU,
          icone: Optional[str] = None, aide: str = "") -> ft.Control:
    """Grande tuile de resultat."""
    contenu = [
        ft.Row(
            [
                ft.Icon(icone, color=couleur, size=18) if icone else ft.Container(width=0),
                # Sur tablette la tuile n'a guère plus de 190 px : un libellé un
                # peu long y était coupé net. Il se replie sur deux lignes.
                ft.Text(libelle.upper(), size=11, weight=ft.FontWeight.W_600, color=GRIS,
                        expand=True, no_wrap=False, max_lines=2,
                        overflow=ft.TextOverflow.ELLIPSIS),
            ],
            spacing=6,
            vertical_alignment=ft.CrossAxisAlignment.START,
        ),
        ft.Row(
            [
                ft.Text(fr(valeur), size=26, weight=ft.FontWeight.W_800, color=couleur),
                ft.Text(unite, size=13, color=GRIS, weight=ft.FontWeight.W_600),
            ],
            spacing=5,
            alignment=ft.MainAxisAlignment.START,
            vertical_alignment=ft.CrossAxisAlignment.END,
        ),
    ]
    if aide:
        contenu.append(ft.Text(fr(aide), size=11, color=GRIS))
    return ft.Container(
        content=ft.Column(contenu, spacing=3),
        padding=ft.padding.symmetric(14, 16),
        border_radius=RAYON,
        bgcolor=ft.Colors.SURFACE_CONTAINER_HIGHEST,
        border=ft.border.all(1, ft.Colors.OUTLINE_VARIANT),
    )


def etiquette(texte: str, couleur: str, fond: str, icone: Optional[str] = None) -> ft.Control:
    contenu: List[ft.Control] = []
    if icone:
        contenu.append(ft.Icon(icone, color=couleur, size=15))
    contenu.append(ft.Text(fr(texte), size=12, weight=ft.FontWeight.W_700, color=couleur))
    return ft.Container(
        content=ft.Row(contenu, spacing=5, tight=True),
        padding=ft.padding.symmetric(5, 10),
        border_radius=20,
        bgcolor=fond,
    )


def etiquette_statut(statut: str) -> ft.Control:
    couleur, fond = COULEURS_STATUT.get(statut, (GRIS, GRIS_CLAIR))
    icone = {"OK": ft.Icons.CHECK_CIRCLE, "LIMITE": ft.Icons.WARNING_AMBER,
             "DEBORDEMENT": ft.Icons.ERROR, "NON CONFORME": ft.Icons.ERROR,
             "NON ENCODE": ft.Icons.EDIT_NOTE}.get(statut, ft.Icons.INFO)
    return etiquette(LIBELLES_STATUT.get(statut, statut), couleur, fond, icone)



def formater_nombre(valeur: float, decimales: int = 3) -> str:
    """Affichage francophone : virgule décimale, pas de notation scientifique."""
    if valeur is None:
        return ""
    valeur = float(valeur)
    if valeur != valeur:
        return "—"
    if valeur in (float("inf"), float("-inf")):
        return "∞" if valeur > 0 else "-∞"
    if valeur == 0:
        return "0"
    if abs(valeur) < 1e-3 or abs(valeur) >= 1e7:
        texte = f"{valeur:.12f}".rstrip("0").rstrip(".")
        return (texte if texte else "0").replace(".", ",")
    if float(valeur) == int(valeur):
        return str(int(valeur))
    return f"{round(valeur, decimales):g}".replace(".", ",")


def lire_nombre(texte: str) -> Optional[float]:
    """Lecture tolérante : virgule ou point, espaces, notation scientifique."""
    brut = (texte or "").replace(",", ".").replace(" ", "").replace("\u202f", "").strip()
    if brut in ("", "-", ".", "-."):
        return 0.0
    try:
        return float(brut)
    except ValueError:
        return None


def _champ_texte(libelle: str, valeur: float, unite: str, aide: str, decimales: int,
                 compact: bool) -> ft.TextField:
    return ft.TextField(
        label=libelle,
        value=formater_nombre(valeur, decimales),
        suffix_text=unite or None,
        helper_text=aide or None,
        # Sans cela le texte d'aide est coupé net à droite sur téléphone.
        helper_max_lines=3,
        keyboard_type=ft.KeyboardType.NUMBER,
        dense=True,
        border_radius=10,
        text_size=13 if compact else 14,
        content_padding=ft.padding.symmetric(8, 12) if compact else None,
    )


def _signaler(champ: ft.Control, erreur: Optional[str]) -> None:
    """Affiche (ou efface) le message d'erreur d'un champ, sans rafraîchir pour rien."""
    if champ.error_text != erreur:
        champ.error_text = erreur
        _rafraichir(champ)


def champ_nombre(libelle: str, valeur: float, on_change: Callable[[float], None],
                 unite: str = "", aide: str = "", decimales: int = 3,
                 col: Optional[dict] = None, on_valide: Optional[Callable[[], None]] = None,
                 compact: bool = False, domaine: Optional[str] = None) -> ft.Control:
    """Champ numérique tolérant (virgule ou point, champ vide = 0).

    ``on_change`` ne met à jour que la donnée : le texte saisi n'est jamais
    reformaté et l'interface n'est pas reconstruite, sans quoi le champ perdrait
    le focus à chaque frappe. ``on_valide`` est appelé quand l'utilisateur quitte
    le champ ou valide : c'est là que les résultats sont recalculés.

    ``domaine`` nomme la grandeur dans :data:`bassin.core.model.DOMAINES` : une
    valeur qui sort de ses bornes physiques n'est pas écrite dans le projet, et
    le champ dit pourquoi. C'est la même table que celle dont le moteur tire ses
    alertes et le classeur ses validations : une seule déclaration, trois
    barrières.
    """
    borne = DOMAINES.get(domaine) if domaine else None

    def _refus(valeur_num: float) -> Optional[str]:
        if borne is None:
            return None
        ecart = borne.ecart(valeur_num)
        return fr(f"{borne.libelle} {ecart}") if ecart else None

    def _change(e: ft.ControlEvent) -> None:
        # Pendant la frappe on reste muet : « 1e-5 » passe par « 1e » et « 1e- »,
        # qui sont invalides sans que l'utilisateur ait fait la moindre faute.
        _signaler(e.control, None)
        valeur_num = lire_nombre(e.control.value)
        if valeur_num is not None and _refus(valeur_num) is None:
            on_change(valeur_num)

    def _valide(e: ft.ControlEvent) -> None:
        valeur_num = lire_nombre(e.control.value)
        if valeur_num is None:
            # La saisie est conservée telle quelle : l'utilisateur doit pouvoir
            # corriger ce qu'il a tapé plutôt que de le voir disparaître.
            _signaler(e.control, "Nombre invalide")
            return
        refus = _refus(valeur_num)
        if refus is not None:
            # Même principe : on montre la raison, on ne touche pas au projet.
            _signaler(e.control, refus)
            return
        _signaler(e.control, None)
        on_change(valeur_num)
        texte = formater_nombre(valeur_num, decimales)
        if e.control.value != texte:
            e.control.value = texte
            _rafraichir(e.control)
        if on_valide:
            on_valide()

    def _focus(e: ft.ControlEvent) -> None:
        """Un champ à zéro se vide quand on y entre.

        Sans cela, cliquer dans un champ qui affiche « 0 » et taper « 3000 »
        donnait « 03000 » : la valeur était juste, l'affichage fautif. Flet
        n'expose pas de sélection programmée ; vider le zéro revient au même,
        et un champ laissé vide vaut zéro de toute façon.
        """
        if lire_nombre(e.control.value) == 0 and e.control.value.strip() != "":
            e.control.value = ""
            _rafraichir(e.control)

    champ = _champ_texte(libelle, valeur, unite, aide, decimales, compact)
    champ.on_change = _change
    champ.on_focus = _focus
    champ.on_blur = _valide
    champ.on_submit = _valide
    return ft.Container(champ, col=col) if col else champ


def _rafraichir(controle: ft.Control) -> None:
    try:
        controle.update()
    except Exception:
        pass  # le contrôle n'est pas encore attaché à la page


def champs_convertis(
    libelle_a: str, unite_a: str, valeur_a: float,
    libelle_b: str, unite_b: str, facteur: Optional[float],
    appliquer: Callable[[float], None],
    on_valide: Optional[Callable[[], None]] = None,
    appliquer_b: Optional[Callable[[float], None]] = None,
    aide_a: str = "", aide_b: str = "", indisponible_b: str = "",
    decimales_a: int = 6, decimales_b: int = 3,
    col_a: Optional[dict] = None, col_b: Optional[dict] = None,
    compact: bool = False, domaine: Optional[str] = None,
) -> List[ft.Control]:
    """Deux champs liés par une conversion : ``valeur_b = valeur_a × facteur``.

    L'utilisateur encode celui qu'il préfère, l'autre se complète tout seul.

    Par défaut seule ``valeur_a`` est enregistrée (via ``appliquer``) : les deux
    champs expriment alors la même grandeur dans deux unités, et il n'importe pas
    de savoir laquelle a été remplie. Quand ``appliquer_b`` est fourni, la case
    remplie fait foi : encoder dans le second champ enregistre sa propre valeur,
    ce qui permet à l'appelant de retenir l'unité de saisie.

    Si ``facteur`` est absent ou nul, le second champ est désactivé et explique
    pourquoi.
    """
    borne = DOMAINES.get(domaine) if domaine else None

    def _refuse(valeur: float) -> Optional[str]:
        """Message de refus si la grandeur sort de son domaine physique.

        Les deux champs expriment la **même** grandeur : la borne se vérifie sur
        sa valeur de référence, quelle que soit l'unité employée pour la saisir.
        """
        if borne is None:
            return None
        ecart = borne.ecart(valeur)
        return fr(f"{borne.libelle} {ecart}") if ecart else None

    champ_a = _champ_texte(libelle_a, valeur_a, unite_a, aide_a, decimales_a, compact)
    valeur_b = valeur_a * facteur if facteur else 0.0
    champ_b = _champ_texte(libelle_b, valeur_b, unite_b,
                           aide_b if facteur else (indisponible_b or aide_b),
                           decimales_b, compact)
    champ_b.disabled = not facteur

    def _ecrire(champ: ft.TextField, valeur: float, decimales: int) -> None:
        texte = formater_nombre(valeur, decimales)
        if champ.value != texte:
            champ.value = texte
            _rafraichir(champ)

    def _sur_a(e: ft.ControlEvent, valider: bool) -> None:
        valeur = lire_nombre(e.control.value)
        if valeur is None:
            # Muet pendant la frappe, explicite une fois le champ quitté.
            _signaler(e.control, "Nombre invalide" if valider else None)
            return
        refus = _refuse(valeur)
        if refus is not None:
            _signaler(e.control, refus)
            return
        _signaler(e.control, None)
        appliquer(valeur)
        if facteur:
            _ecrire(champ_b, valeur * facteur, decimales_b)
        if valider:
            _ecrire(champ_a, valeur, decimales_a)
            if on_valide:
                on_valide()

    def _sur_b(e: ft.ControlEvent, valider: bool) -> None:
        valeur = lire_nombre(e.control.value)
        if valeur is None:
            _signaler(e.control, "Nombre invalide" if valider else None)
            return
        if not facteur:
            _signaler(e.control, None)
            return
        equivalent = valeur / facteur
        refus = _refuse(equivalent if appliquer_b is None else valeur)
        if refus is not None:
            _signaler(e.control, refus)
            return
        _signaler(e.control, None)
        if appliquer_b is not None:
            appliquer_b(valeur)
        else:
            appliquer(equivalent)
        _ecrire(champ_a, equivalent, decimales_a)
        if valider:
            _ecrire(champ_b, valeur, decimales_b)
            if on_valide:
                on_valide()

    champ_a.on_change = lambda e: _sur_a(e, False)
    champ_a.on_blur = lambda e: _sur_a(e, True)
    champ_a.on_submit = lambda e: _sur_a(e, True)
    champ_b.on_change = lambda e: _sur_b(e, False)
    champ_b.on_blur = lambda e: _sur_b(e, True)
    champ_b.on_submit = lambda e: _sur_b(e, True)
    return [
        ft.Container(champ_a, col=col_a) if col_a else champ_a,
        ft.Container(champ_b, col=col_b) if col_b else champ_b,
    ]


def tableau_defilant(tableau: ft.Control) -> ft.Control:
    """Rend un tableau défilable horizontalement (indispensable sur téléphone)."""
    return ft.Row([tableau], scroll=ft.ScrollMode.AUTO, vertical_alignment=ft.CrossAxisAlignment.START)


def selecteur(libelle: str, valeur: str, options, on_change, col: Optional[dict] = None,
              compact: bool = False) -> ft.Control:
    """Liste déroulante compacte (préférée aux boutons segmentés sur mobile)."""
    champ = ft.Dropdown(
        label=libelle,
        value=valeur,
        options=[ft.dropdown.Option(k, t) for k, t in options],
        on_change=on_change,
        dense=True,
        border_radius=10,
        text_size=13 if compact else 14,
    )
    return ft.Container(champ, col=col) if col else champ


def bouton_principal(texte: str, icone: str, on_click) -> ft.Control:
    return ft.FilledButton(texte, icon=icone, on_click=on_click,
                           style=ft.ButtonStyle(shape=ft.RoundedRectangleBorder(radius=10),
                                                padding=ft.padding.symmetric(16, 20)))


def bouton_secondaire(texte: str, icone: str, on_click) -> ft.Control:
    return ft.OutlinedButton(texte, icon=icone, on_click=on_click,
                             style=ft.ButtonStyle(shape=ft.RoundedRectangleBorder(radius=10),
                                                  padding=ft.padding.symmetric(16, 20)))


def message(texte: str, type_: str = "info") -> ft.Control:
    couleurs = {
        "info": (BLEU, BLEU_CLAIR, ft.Icons.INFO_OUTLINE),
        "succes": (VERT, VERT_CLAIR, ft.Icons.CHECK_CIRCLE_OUTLINE),
        "alerte": (ORANGE, ORANGE_CLAIR, ft.Icons.WARNING_AMBER_ROUNDED),
        "erreur": (ROUGE, ROUGE_CLAIR, ft.Icons.ERROR_OUTLINE),
    }
    couleur, fond, icone = couleurs.get(type_, couleurs["info"])
    # Les alertes viennent du moteur, qui formate ses nombres en Python : la
    # virgule décimale se pose ici, une fois, plutôt qu'à chaque message.
    return ft.Container(
        content=ft.Row(
            [ft.Icon(icone, color=couleur, size=18),
             ft.Text(fr(texte), size=12.5, color=ARDOISE, expand=True, selectable=True)],
            spacing=10,
            vertical_alignment=ft.CrossAxisAlignment.START,
        ),
        padding=ft.padding.symmetric(10, 14),
        border_radius=10,
        bgcolor=fond,
    )


def entete_tableau(textes: Sequence[str]) -> List[ft.DataColumn]:
    return [ft.DataColumn(ft.Text(t, size=12, weight=ft.FontWeight.W_700)) for t in textes]
