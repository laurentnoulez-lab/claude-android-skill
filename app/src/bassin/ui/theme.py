"""Theme graphique et composants d'interface reutilisables."""

from __future__ import annotations

from typing import Callable, List, Optional, Sequence

import flet as ft

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
}

LIBELLES_STATUT = {"OK": "OK", "LIMITE": "Limite", "DEBORDEMENT": "Débordement"}


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
    colonne = [ft.Text(texte, size=18, weight=ft.FontWeight.W_700)]
    if sous_titre:
        colonne.append(ft.Text(sous_titre, size=12, color=GRIS))
    ligne.append(ft.Column(colonne, spacing=1))
    return ft.Row(ligne, spacing=10, vertical_alignment=ft.CrossAxisAlignment.CENTER)


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
                ft.Text(libelle.upper(), size=11, weight=ft.FontWeight.W_600, color=GRIS),
            ],
            spacing=6,
        ),
        ft.Row(
            [
                ft.Text(valeur, size=26, weight=ft.FontWeight.W_800, color=couleur),
                ft.Text(unite, size=13, color=GRIS, weight=ft.FontWeight.W_600),
            ],
            spacing=5,
            alignment=ft.MainAxisAlignment.START,
            vertical_alignment=ft.CrossAxisAlignment.END,
        ),
    ]
    if aide:
        contenu.append(ft.Text(aide, size=11, color=GRIS))
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
    contenu.append(ft.Text(texte, size=12, weight=ft.FontWeight.W_700, color=couleur))
    return ft.Container(
        content=ft.Row(contenu, spacing=5, tight=True),
        padding=ft.padding.symmetric(5, 10),
        border_radius=20,
        bgcolor=fond,
    )


def etiquette_statut(statut: str) -> ft.Control:
    couleur, fond = COULEURS_STATUT.get(statut, (GRIS, GRIS_CLAIR))
    icone = {"OK": ft.Icons.CHECK_CIRCLE, "LIMITE": ft.Icons.WARNING_AMBER,
             "DEBORDEMENT": ft.Icons.ERROR}.get(statut, ft.Icons.INFO)
    return etiquette(LIBELLES_STATUT.get(statut, statut), couleur, fond, icone)


def champ_nombre(libelle: str, valeur: float, on_change: Callable[[float], None],
                 unite: str = "", aide: str = "", decimales: int = 3,
                 col: Optional[dict] = None, on_valide: Optional[Callable[[], None]] = None,
                 compact: bool = False) -> ft.Control:
    """Champ numérique tolérant (virgule ou point, champ vide = 0).

    ``on_change`` ne met à jour que la donnée : le texte saisi n'est jamais
    reformaté et l'interface n'est pas reconstruite, sans quoi le champ perdrait
    le focus à chaque frappe. ``on_valide`` est appelé quand l'utilisateur quitte
    le champ ou valide : c'est là que les résultats sont recalculés.
    """

    def formater(v: float) -> str:
        if v == 0:
            return "0"
        if abs(v) < 1e-3 or abs(v) >= 1e7:
            texte = f"{v:.10f}".rstrip("0").rstrip(".")
            return texte if texte else "0"
        if float(v) == int(v):
            return str(int(v))
        return f"{round(v, decimales):g}"

    def lire(texte: str) -> Optional[float]:
        brut = (texte or "").replace(",", ".").replace(" ", "").strip()
        if brut in ("", "-", ".", "-."):
            return 0.0
        try:
            return float(brut)
        except ValueError:
            return None

    def _change(e: ft.ControlEvent) -> None:
        valeur_num = lire(e.control.value)
        erreur = "Nombre invalide" if valeur_num is None else None
        if e.control.error_text != erreur:
            e.control.error_text = erreur
            e.control.update()
        if valeur_num is not None:
            on_change(valeur_num)

    def _valide(e: ft.ControlEvent) -> None:
        valeur_num = lire(e.control.value)
        if valeur_num is None:
            return
        on_change(valeur_num)
        texte = formater(valeur_num)
        if e.control.value != texte:
            e.control.value = texte
            e.control.update()
        if on_valide:
            on_valide()

    champ = ft.TextField(
        label=libelle,
        value=formater(valeur),
        suffix_text=unite or None,
        helper_text=aide or None,
        on_change=_change,
        on_blur=_valide,
        on_submit=_valide,
        keyboard_type=ft.KeyboardType.NUMBER,
        dense=True,
        border_radius=10,
        text_size=13 if compact else 14,
        content_padding=ft.padding.symmetric(8, 12) if compact else None,
    )
    return ft.Container(champ, col=col) if col else champ


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
    return ft.Container(
        content=ft.Row(
            [ft.Icon(icone, color=couleur, size=18),
             ft.Text(texte, size=12.5, color=ARDOISE, expand=True, selectable=True)],
            spacing=10,
            vertical_alignment=ft.CrossAxisAlignment.START,
        ),
        padding=ft.padding.symmetric(10, 14),
        border_radius=10,
        bgcolor=fond,
    )


def entete_tableau(textes: Sequence[str]) -> List[ft.DataColumn]:
    return [ft.DataColumn(ft.Text(t, size=12, weight=ft.FontWeight.W_700)) for t in textes]
