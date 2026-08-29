"""Générateur DOCX minimaliste en Python pur (aucune dépendance native).

Suffisant pour un rapport technique : titres, paragraphes, tableaux avec
fusion de couleurs, images PNG, saut de page, pied de page.
"""

from __future__ import annotations

import zipfile
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple
from xml.sax.saxutils import escape

from ..formats import fr

EMU_PAR_CM = 360000
TWIP_PAR_CM = 567

W_NS = (
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"'
)


@dataclass
class Cellule:
    texte: str
    gras: bool = False
    fond: Optional[str] = None
    couleur: Optional[str] = None
    alignement: str = "left"


class DocxBuilder:
    """Construit un document Word simple."""

    def __init__(self, titre: str = "", auteur: str = ""):
        self.titre = titre
        self.auteur = auteur
        self._corps: List[str] = []
        self._images: List[Tuple[str, bytes]] = []

    # -- contenu -----------------------------------------------------------
    def titre_principal(self, texte: str, sous_titre: str = "") -> None:
        self._corps.append(self._p(texte, taille=32, gras=True, couleur="1D4ED8", espace_apres=60,
                                   convertir=False))
        if sous_titre:
            self._corps.append(self._p(sous_titre, taille=20, italique=True, couleur="475569", espace_apres=240))

    def titre1(self, texte: str) -> None:
        self._corps.append(self._p(texte, taille=26, gras=True, couleur="1D4ED8",
                                   espace_avant=280, espace_apres=120, bordure_bas=True,
                                   convertir=False))

    def titre2(self, texte: str) -> None:
        self._corps.append(self._p(texte, taille=22, gras=True, couleur="0F172A",
                                   espace_avant=200, espace_apres=80, convertir=False))

    def paragraphe(self, texte: str = "", gras: bool = False, italique: bool = False,
                   taille: int = 20, couleur: str = "0F172A", puce: bool = False) -> None:
        self._corps.append(self._p(("- " if puce else "") + texte, taille=taille, gras=gras,
                                   italique=italique, couleur=couleur, retrait=360 if puce else 0))

    def encadre(self, texte: str, fond: str = "DBEAFE", couleur: str = "0F172A") -> None:
        self._corps.append(self._p(texte, taille=20, gras=True, couleur=couleur, fond=fond,
                                   espace_avant=120, espace_apres=120))

    def saut_de_page(self) -> None:
        self._corps.append('<w:p><w:r><w:br w:type="page"/></w:r></w:p>')

    def tableau(self, lignes: Sequence[Sequence], largeurs: Optional[Sequence[float]] = None,
                entete: bool = True, taille: int = 16) -> None:
        if not lignes:
            return
        n = max(len(l) for l in lignes)
        largeurs = largeurs or [16.0 / n] * n
        grille = "".join(f'<w:gridCol w:w="{int(w * TWIP_PAR_CM)}"/>' for w in largeurs)
        bordure = ('<w:tblBorders>'
                   + "".join(f'<w:{c} w:val="single" w:sz="4" w:space="0" w:color="CBD5E1"/>'
                             for c in ("top", "left", "bottom", "right", "insideH", "insideV"))
                   + '</w:tblBorders>')
        xml = [f'<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/>{bordure}</w:tblPr><w:tblGrid>{grille}</w:tblGrid>']
        for i, ligne in enumerate(lignes):
            xml.append("<w:tr>")
            if entete and i == 0:
                xml.append('<w:trPr><w:tblHeader/></w:trPr>')
            for j in range(n):
                brute = ligne[j] if j < len(ligne) else ""
                cell = brute if isinstance(brute, Cellule) else Cellule(str(brute))
                gras = cell.gras or (entete and i == 0)
                fond = cell.fond or ("1D4ED8" if entete and i == 0 else None)
                couleur = cell.couleur or ("FFFFFF" if entete and i == 0 else "0F172A")
                ombrage = f'<w:shd w:val="clear" w:fill="{fond}"/>' if fond else ""
                xml.append(
                    f'<w:tc><w:tcPr><w:tcW w:w="{int(largeurs[j] * TWIP_PAR_CM)}" w:type="dxa"/>'
                    f'{ombrage}<w:vAlign w:val="center"/></w:tcPr>'
                    + self._p(cell.texte, taille=taille, gras=gras, couleur=couleur,
                              alignement=cell.alignement, espace_avant=20, espace_apres=20)
                    + "</w:tc>"
                )
            xml.append("</w:tr>")
        xml.append("</w:tbl>")
        self._corps.append("".join(xml))
        self._corps.append(self._p("", taille=8))

    def image(self, png: bytes, largeur_cm: float = 16.0, hauteur_cm: Optional[float] = None,
              legende: str = "") -> None:
        largeur_px, hauteur_px = _dimensions_png(png)
        if hauteur_cm is None:
            hauteur_cm = largeur_cm * hauteur_px / max(largeur_px, 1)
        idx = len(self._images) + 1
        nom = f"image{idx}.png"
        self._images.append((nom, png))
        rid = f"rIdImg{idx}"
        cx, cy = int(largeur_cm * EMU_PAR_CM), int(hauteur_cm * EMU_PAR_CM)
        self._corps.append(
            '<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>'
            f'<wp:inline distT="0" distB="0" distL="0" distR="0">'
            f'<wp:extent cx="{cx}" cy="{cy}"/><wp:docPr id="{idx}" name="{nom}"/>'
            '<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
            f'<pic:pic><pic:nvPicPr><pic:cNvPr id="{idx}" name="{nom}"/><pic:cNvPicPr/></pic:nvPicPr>'
            f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
            f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
            '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>'
            '</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>'
        )
        if legende:
            self._corps.append(self._p(legende, taille=16, italique=True, couleur="475569",
                                       alignement="center", espace_apres=160))

    # -- primitives --------------------------------------------------------
    @staticmethod
    def _p(texte: str, taille: int = 20, gras: bool = False, italique: bool = False,
           couleur: str = "0F172A", fond: Optional[str] = None, alignement: str = "left",
           espace_avant: int = 0, espace_apres: int = 60, retrait: int = 0,
           bordure_bas: bool = False, convertir: bool = True) -> str:
        # convertir=False pour les titres : la numérotation « 1.1 » garde son point.
        props = [f'<w:spacing w:before="{espace_avant}" w:after="{espace_apres}"/>']
        if alignement != "left":
            props.append(f'<w:jc w:val="{alignement}"/>')
        if retrait:
            props.append(f'<w:ind w:left="{retrait}"/>')
        if fond:
            props.append(f'<w:shd w:val="clear" w:fill="{fond}"/>')
        if bordure_bas:
            props.append('<w:pBdr><w:bottom w:val="single" w:sz="8" w:space="2" w:color="BFDBFE"/></w:pBdr>')
        rpr = f'<w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="{taille}"/>'\
              f'<w:color w:val="{couleur}"/>' + ("<w:b/>" if gras else "") + ("<w:i/>" if italique else "") + "</w:rPr>"
        contenu = ""
        for i, morceau in enumerate(texte.split("\n")):
            if i:
                contenu += "<w:r><w:br/></w:r>"
            contenu += f'<w:r>{rpr}<w:t xml:space="preserve">{escape(fr(morceau) if convertir else morceau)}</w:t></w:r>'
        return f'<w:p><w:pPr>{"".join(props)}</w:pPr>{contenu}</w:p>'

    # -- ecriture ----------------------------------------------------------
    def enregistrer(self, chemin: str) -> str:
        section = (
            '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" '
            'w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>'
        )
        document = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                    f'<w:document {W_NS}><w:body>{"".join(self._corps)}{section}</w:body></w:document>')
        rels = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">']
        for i, (nom, _) in enumerate(self._images, start=1):
            rels.append(f'<Relationship Id="rIdImg{i}" '
                        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" '
                        f'Target="media/{nom}"/>')
        rels.append('</Relationships>')
        types = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                 '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                 '<Default Extension="xml" ContentType="application/xml"/>'
                 '<Default Extension="png" ContentType="image/png"/>'
                 '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.'
                 'wordprocessingml.document.main+xml"/>'
                 '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.'
                 'core-properties+xml"/></Types>']
        racine_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                       '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                       '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
                       'relationships/officeDocument" Target="word/document.xml"/>'
                       '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/'
                       'relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>')
        core = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
                'xmlns:dc="http://purl.org/dc/elements/1.1/">'
                f'<dc:title>{escape(self.titre)}</dc:title><dc:creator>{escape(self.auteur)}</dc:creator>'
                '</cp:coreProperties>')
        with zipfile.ZipFile(chemin, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("[Content_Types].xml", "".join(types))
            z.writestr("_rels/.rels", racine_rels)
            z.writestr("docProps/core.xml", core)
            z.writestr("word/document.xml", document)
            z.writestr("word/_rels/document.xml.rels", "".join(rels))
            for nom, data in self._images:
                z.writestr(f"word/media/{nom}", data)
        return chemin


def _dimensions_png(png: bytes) -> Tuple[int, int]:
    import struct

    if len(png) > 24 and png[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = struct.unpack(">II", png[16:24])
        return int(w), int(h)
    return 900, 460
