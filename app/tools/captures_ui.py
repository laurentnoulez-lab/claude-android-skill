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


def servir(racine: str, port: int) -> socketserver.TCPServer:
    gestionnaire = functools.partial(http.server.SimpleHTTPRequestHandler, directory=racine)
    socketserver.TCPServer.allow_reuse_address = True
    serveur = socketserver.TCPServer(("127.0.0.1", port), gestionnaire)
    threading.Thread(target=serveur.serve_forever, daemon=True).start()
    return serveur


def capturer(url: str, sortie: str, chemin_navigateur: str | None) -> None:
    from playwright.sync_api import sync_playwright

    os.makedirs(sortie, exist_ok=True)
    with sync_playwright() as pw:
        options = {"args": ["--no-sandbox"]}
        if chemin_navigateur:
            options["executable_path"] = chemin_navigateur
        navigateur = pw.chromium.launch(**options)
        for nom_format, (largeur, hauteur) in FORMATS.items():
            page = navigateur.new_page(viewport={"width": largeur, "height": hauteur})
            erreurs: list[str] = []
            page.on("pageerror", lambda e: erreurs.append(str(e)[:200]))
            page.goto(url, wait_until="load", timeout=90000)
            page.wait_for_timeout(15000)
            page.screenshot(path=os.path.join(sortie, f"{nom_format}_0_accueil.png"))
            for i, vue in enumerate(VUES[1:], start=1):
                try:
                    if largeur < 840:  # navigation par le tiroir
                        page.get_by_role("button").first.click(timeout=8000)
                        page.wait_for_timeout(1200)
                    page.get_by_text(vue, exact=True).first.click(timeout=10000)
                    page.wait_for_timeout(3000)
                    page.screenshot(path=os.path.join(sortie, f"{nom_format}_{i}_{vue.replace(' ', '_')}.png"))
                except Exception as exc:  # pragma: no cover - dépend du rendu
                    print(f"  ! {nom_format} / {vue} : {str(exc)[:120]}")
            if erreurs:
                print(f"  ! erreurs console ({nom_format}) : {erreurs[:3]}")
            page.close()
        navigateur.close()


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
