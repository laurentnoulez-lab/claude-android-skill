#!/usr/bin/env python3
"""Construit le fichier HTML autonome unique à partir des sources.

Inline ExcelJS, docx, le CSS, le JS et le logo dans app/src/index.template.html
et écrit le résultat dans gabarit-tranchees-v2.html à la racine du dépôt.
Le numéro et la date de version viennent de app/version.json (source unique).

Le fichier gabarit-tranchees-impetrants.html (version 1) n'est plus régénéré :
il reste dans le dépôt tel quel, pour ceux qui utilisent encore la 1.x.
"""
import json
import pathlib
import urllib.parse

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
SRC = HERE / "src"
OUT = ROOT / "gabarit-tranchees-v2.html"


def read(p):
    return (SRC / p).read_text(encoding="utf-8")


def main():
    info = json.loads((HERE / "version.json").read_text(encoding="utf-8"))
    y, m, d = info["date"].split("-")
    date_fr = f"{d}/{m}/{y}"

    logo = read("logo.svg").strip()
    favicon = "data:image/svg+xml," + urllib.parse.quote(logo, safe="/:=' ")

    tpl = read("index.template.html")
    # 1) métadonnées (sur le gabarit seul, avant d'injecter les bibliothèques)
    tpl = (
        tpl.replace("__APP_NAME__", info["name"])
        .replace("__APP_VERSION__", info["version"])
        .replace("__APP_DATE_FR__", date_fr)
        .replace("__APP_DATE__", info["date"])
        .replace("__FAVICON__", favicon)
        .replace("<!--__LOGO__-->", logo)
    )
    # 2) contenus
    parts = {
        "/*__STYLES__*/": read("styles.css"),
        "/*__EXCELJS__*/": (HERE / "vendor" / "exceljs.min.js").read_text(encoding="utf-8"),
        "/*__DOCX__*/": (HERE / "vendor" / "docx.iife.js").read_text(encoding="utf-8"),
        "/*__MODEL__*/": read("model.js"),
        "/*__COUPE__*/": read("coupe.js"),
        "/*__DOCXGEN__*/": read("docx-export.js"),
        "/*__UI__*/": read("ui.js"),
    }
    # Découpage plutôt que remplacements successifs : un marqueur présent par
    # hasard dans une bibliothèque injectée ne peut pas être remplacé à son tour.
    out = tpl
    chunks = []
    for marker in parts:
        assert out.count(marker) == 1, f"marqueur {marker} absent ou dupliqué"
    order = sorted(parts, key=lambda k: out.index(k))
    pos = 0
    for marker in order:
        i = out.index(marker, pos)
        chunks.append(out[pos:i])
        chunks.append(parts[marker])
        pos = i + len(marker)
    chunks.append(out[pos:])
    result = "".join(chunks)
    OUT.write_text(result, encoding="utf-8")
    print(f"Écrit {OUT} — {info['name']} v{info['version']} du {date_fr} ({len(result)/1024:.0f} Ko)")


if __name__ == "__main__":
    main()
