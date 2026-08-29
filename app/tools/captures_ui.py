"""Capture d'écrans de l'application (build web + navigateur sans interface).

    python3 tools/captures_ui.py --racine build/web --sortie captures

Utilisé par le workflow « Captures d'interface » : les images permettent de
vérifier la mise en page réelle sur téléphone et sur ordinateur.
"""

from __future__ import annotations

import argparse
import functools
import http.server
import os
import socketserver
import threading

VUES = ["Projet", "Dimensionnement", "Bassin", "Table QDF", "Ajutage", "Pluies GTI", "Rapport"]
FORMATS = {"telephone": (390, 844), "tablette": (820, 1180), "bureau": (1440, 960)}


class _GestionnaireSPA(http.server.SimpleHTTPRequestHandler):
    """Renvoie index.html pour toute route inconnue (routage côté application)."""

    def do_GET(self):  # noqa: N802 - nom imposé par http.server
        chemin = self.translate_path(self.path)
        if not os.path.exists(chemin) or os.path.isdir(chemin) and not os.path.exists(
                os.path.join(chemin, "index.html")):
            self.path = "/index.html"
        return super().do_GET()


def servir(racine: str, port: int) -> socketserver.TCPServer:
    gestionnaire = functools.partial(_GestionnaireSPA, directory=racine)
    socketserver.TCPServer.allow_reuse_address = True
    serveur = socketserver.TCPServer(("127.0.0.1", port), gestionnaire)
    threading.Thread(target=serveur.serve_forever, daemon=True).start()
    return serveur


def defiler_et_capturer(page, sortie: str, nom_format: str, index: int, nom: str) -> None:
    """Capture aussi le bas de la page : les débordements y sont fréquents."""
    try:
        page.mouse.move(200, 400)
        for _ in range(3):
            page.mouse.wheel(0, 900)
            page.wait_for_timeout(600)
        page.screenshot(path=os.path.join(sortie, f"{nom_format}_{index}_{nom}_bas.png"))
        for _ in range(4):
            page.mouse.wheel(0, -900)
        page.wait_for_timeout(600)
    except Exception as exc:  # pragma: no cover
        print(f"  ! défilement impossible ({nom_format}/{nom}) : {str(exc)[:80]}")


def etiquettes(page) -> list:
    """Libellés exposés par l'arbre d'accessibilité de Flutter (diagnostic)."""
    try:
        return page.evaluate(
            """() => Array.from(document.querySelectorAll('flt-semantics[aria-label]'))
                      .map(e => e.getAttribute('aria-label')).filter(Boolean).slice(0, 60)"""
        )
    except Exception as exc:  # pragma: no cover
        return [f"illisible : {str(exc)[:80]}"]


def cliquer_etiquette(page, libelle: str, delai: int = 4000) -> bool:
    """Clique un élément de l'arbre d'accessibilité par son libellé exact."""
    try:
        page.locator(f'flt-semantics[aria-label="{libelle}"]').first.click(timeout=delai)
        return True
    except Exception:
        return False


def ouvrir_section(page, vue: str, compact: bool) -> bool:
    """Ouvre une section comme le ferait l'utilisateur : tiroir ou rail lateral."""
    if compact and not cliquer_etiquette(page, "Sections"):
        return False
    if compact:
        page.wait_for_timeout(1200)
    if cliquer_etiquette(page, vue):
        return True
    if compact:  # le tiroir reste ouvert : on le referme pour ne pas fausser la suite
        page.keyboard.press("Escape")
    return False


def capturer(url: str, sortie: str, chemin_navigateur: str | None) -> None:
    from playwright.sync_api import sync_playwright

    os.makedirs(sortie, exist_ok=True)
    journal: list[str] = []
    with sync_playwright() as pw:
        options = {"args": ["--no-sandbox"]}
        if chemin_navigateur:
            options["executable_path"] = chemin_navigateur
        navigateur = pw.chromium.launch(**options)
        for nom_format, (largeur, hauteur) in FORMATS.items():
            page = navigateur.new_page(viewport={"width": largeur, "height": hauteur})
            erreurs: list[str] = []
            page.on("pageerror", lambda e: erreurs.append(f"[{nom_format}] pageerror : {str(e)[:400]}"))
            # Tous les messages sont conservés : les jalons de démarrage écrits par
            # l'application (print côté Pyodide) arrivent en type « log » et sont la
            # seule fenêtre sur ce qui se passe dans le navigateur.
            page.on("console", lambda m: erreurs.append(f"[{nom_format}] {m.type} : {m.text[:400]}"))
            page.goto(url, wait_until="load", timeout=90000)
            page.wait_for_timeout(15000)
            # Flutter dessine dans un canvas : sans l'arbre d'accessibilité, aucun
            # texte n'est sélectionnable par Playwright.
            try:
                page.click("flt-semantics-placeholder", force=True, timeout=5000)
                page.wait_for_timeout(2000)
            except Exception as exc:
                print(f"  ! accessibilité non activée ({nom_format}) : {str(exc)[:80]}")
            erreurs.append(f"[{nom_format}] étiquettes accessibles : {etiquettes(page)}")
            page.screenshot(path=os.path.join(sortie, f"{nom_format}_0_Projet.png"))
            defiler_et_capturer(page, sortie, nom_format, 0, "Projet")
            # La navigation se fait dans l'application elle-même, par l'arbre
            # d'accessibilité : c'est ce que fait l'utilisateur, et cela ne dépend
            # ni de la stratégie d'URL de Flet ni du serveur de fichiers.
            compact = largeur < 840
            for i, vue in enumerate(VUES[1:], start=1):
                nom = vue.replace(" ", "_")
                try:
                    if not ouvrir_section(page, vue, compact):
                        erreurs.append(f"[{nom_format}] section « {vue} » inatteignable")
                        continue
                    page.wait_for_timeout(2500)
                    page.screenshot(path=os.path.join(sortie, f"{nom_format}_{i}_{nom}.png"))
                    defiler_et_capturer(page, sortie, nom_format, i, nom)
                except Exception as exc:  # pragma: no cover - dépend du rendu
                    print(f"  ! {nom_format} / {vue} : {str(exc)[:120]}")
                    erreurs.append(f"[{nom_format}] {vue} : {str(exc)[:200]}")
            journal.extend(erreurs)
            if erreurs:
                print(f"  ! {len(erreurs)} message(s) console ({nom_format})")
            page.close()
        navigateur.close()
    # Le journal est publié avec les captures : indispensable pour diagnostiquer
    # un écran vide sans avoir accès au navigateur.
    with open(os.path.join(sortie, "journal.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(journal) if journal else "aucun message de console")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--racine", default="build/web")
    parser.add_argument("--sortie", default="captures")
    parser.add_argument("--port", type=int, default=8770)
    parser.add_argument("--navigateur", default=os.environ.get("CHEMIN_CHROMIUM"))
    args = parser.parse_args()

    serveur = servir(args.racine, args.port)
    try:
        capturer(f"http://127.0.0.1:{args.port}/", args.sortie, args.navigateur)
    finally:
        serveur.shutdown()
    fichiers = sorted(os.listdir(args.sortie))
    print(f"{len(fichiers)} captures dans {args.sortie}")
    for f in fichiers:
        print(" -", f)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
