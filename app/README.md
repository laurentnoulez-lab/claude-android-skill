# HydroBassin — dimensionnement de bassins d'orage

Application de dimensionnement et de vérification de bassins d'orage par la **méthode
rationnelle**, à partir des **pluies statistiques du GTI** (Guide Technique d'Infiltration,
Région wallonne) embarquées dans l'application.

Livrables : **APK Android** et **exécutable Windows (.exe)**, à partir d'un code unique
(Python + [Flet](https://flet.dev)).

![Icône](src/assets/icon.png)

## Ce que fait l'application

| Onglet | Contenu |
|---|---|
| **Projet** | Commune (574 communes, dont les 262 communes wallonnes), période de retour (2 → 200 ans), surfaces incidentes et coefficients de ruissellement du GTI |
| **Dimensionnement** | Vitesse d'infiltration K, débit d'ajutage, temps de vidange maximum ; comparaison des **4 scénarios** ; volume, durée critique, surface d'infiltration minimale, débit d'ajutage minimal |
| **Bassin** | Encodage de l'ouvrage (volume tampon, volume sous l'ajutage, surface de dispersion, débit d'ajutage) et **simulation complète** du remplissage / vidange |
| **Table QDF** | Tableau récurrences × durées : quelles pluies l'ouvrage encaisse sans déborder |
| **Ajutage** | Dimensionnement de l'orifice (Torricelli), abaque des diamètres commerciaux |
| **Pluies GTI** | Tables QDF en mm et en l/s/ha, coefficients de Montana, courbes IDF |
| **Rapport** | Génération du dossier en **Excel (avec formules vivantes)**, **Word** et **PDF** |

### Les quatre scénarios étudiés

1. **Temporisation seule** (sans dispersion) — seul l'orifice calibré évacue.
2. **Dispersion seule** (sans exutoire ajuté) — toute l'eau s'infiltre.
3. **Temporisation et dispersion** — infiltration par le fond **+** orifice calibré.
4. **Dispersion seule avec temporisation au-delà d'un seuil** — orifice surélevé : sous
   l'axe de l'orifice, seule l'infiltration évacue ; au-dessus, l'ajutage s'y ajoute.

## Méthode de calcul

Voir [docs/methode.md](docs/methode.md) pour le détail (formules, hypothèses, règles du GTI).
En résumé, pour chaque durée de pluie *t* :

```
V_ruisselé(t) = h(t) [mm] × S_pondérée [m²] / 1000            [m³]
V_évacué(t)   = Q_sortie [l/s] × t [min] × 60 / 1000          [m³]
V_à_maîtriser = max( V_ruisselé(t) − V_évacué(t) , 0 )        [m³]
```

Le volume de dimensionnement est le maximum sur l'ensemble des durées (10 min → 60 jours,
pas de 5 min), ce qui donne la **durée de pluie critique**.

## Utilisation en développement

```bash
pip install -r requirements.txt
python src/main.py            # application de bureau
flet run --web src/main.py    # dans le navigateur
python -m unittest discover -s tests -v
python tools/exemple.py --sortie ../rapports_demo   # dossier de démonstration
```

## Construction des livrables

Les binaires sont produits par GitHub Actions (`Actions` → workflow → *Run workflow*) :

| Workflow | Livrable |
|---|---|
| `Build APK Android` | `HydroBassin-1.0.0.apk` |
| `Build Windows` | `HydroBassin.exe` (portable, un seul fichier) et `HydroBassin-windows.zip` (application Flutter) |

En local (Flutter 3.29.x requis, installé automatiquement par flet si absent) :

```bash
pip install "flet[all]==0.28.3"
flet build apk        # Android
flet build windows    # Windows (à lancer sur Windows)
flet pack src/main.py --name HydroBassin --icon src/assets/icon.png --onefile \
  --add-data "src/bassin/data/gti_rainfall.json.gz;bassin/data"
```

## Organisation du code

```
app/
├── src/
│   ├── main.py                  point d'entrée Flet (navigation, thème, persistance)
│   ├── assets/                  icône et écran de démarrage
│   └── bassin/
│       ├── core/
│       │   ├── rainfall.py      pluies GTI : Montana + tables QDF, 574 communes
│       │   ├── model.py         projet, surfaces, bassin, constantes du GTI
│       │   ├── hydro.py         méthode rationnelle, scénarios, minima (dichotomie)
│       │   ├── simulation.py    remplissage / vidange, table QDF d'acceptation
│       │   └── orifice.py       Torricelli, abaque des diamètres
│       ├── data/gti_rainfall.json.gz   données extraites du classeur GTI (193 Ko)
│       ├── reports/
│       │   ├── dossier.py       assemblage du dossier de calcul
│       │   ├── charts.py        graphiques + rasteriseur PNG en Python pur
│       │   ├── xlsx_report.py   classeur Excel avec formules vivantes
│       │   ├── docx_writer.py   générateur DOCX (OOXML) sans dépendance native
│       │   ├── docx_report.py   rapport Word
│       │   ├── pdf_writer.py    générateur PDF sans dépendance native
│       │   └── pdf_report.py    rapport PDF (graphiques vectoriels)
│       └── ui/                  thème, état, graphiques Flet et 7 vues
├── tests/                       66 tests unitaires
└── tools/                       génération de l'icône et du dossier de démonstration
```

Aucune dépendance native n'est utilisée (ni matplotlib, ni Pillow, ni lxml, ni reportlab) :
les graphiques, le DOCX et le PDF sont produits en Python pur, ce qui garantit le
fonctionnement identique sur Windows et sur Android.

## Données

Les pluies proviennent du classeur GTI fourni (feuilles `Montana`, `QDF` et `Listes`) :

* **Montana** — 563 communes belges × 12 périodes de retour × 3 jeux de coefficients
  (`i [mm/h] = a × t[min]^(−b)`, plages `t < 25 min`, `25 → 6000 min`, `t > 6000 min`) ;
* **QDF** — 262 communes wallonnes × 19 durées normalisées (10 min → 30 jours) × 12 périodes
  de retour ;
* 11 communes wallonnes sans coefficients de Montana basculent automatiquement sur les
  tables QDF (interpolation logarithmique).
