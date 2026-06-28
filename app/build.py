#!/usr/bin/env python3
"""Construit le fichier HTML autonome unique à partir des sources.

Inline ExcelJS, le CSS et le JS dans app/src/index.template.html et écrit le
résultat dans gabarit-tranchees-impetrants.html à la racine du dépôt.
"""
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
SRC = HERE / "src"
OUT = ROOT / "gabarit-tranchees-impetrants.html"


def read(p):
    return (SRC / p).read_text(encoding="utf-8")


def main():
    tpl = read("index.template.html")
    exceljs = (HERE / "vendor" / "exceljs.min.js").read_text(encoding="utf-8")
    styles = read("styles.css")
    model = read("model.js")
    ui = read("ui.js")

    out = (
        tpl.replace("/*__STYLES__*/", styles)
        .replace("/*__EXCELJS__*/", exceljs)
        .replace("/*__MODEL__*/", model)
        .replace("/*__UI__*/", ui)
    )
    OUT.write_text(out, encoding="utf-8")
    print(f"Écrit {OUT} ({len(out)/1024:.0f} Ko)")


if __name__ == "__main__":
    main()
