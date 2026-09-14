# HydroBassin+ — dimensionnement de réseaux de bassins d'orage

Application de dimensionnement et de vérification de **réseaux de bassins d'orage** par la
**méthode rationnelle**, à partir des **pluies statistiques du GTI** (Guide Technique
d'Infiltration, Région wallonne) embarquées dans l'application.

Un projet décrit autant de **bassins versants** et de **bassins d'orage** que nécessaire, et
leurs raccordements : chaque bassin versant se déverse dans un seul bassin d'orage, chaque
bassin d'orage dans un autre bassin ou à l'exutoire. Un projet à un seul bassin reste ce
qu'il était.

Livrables : **APK Android** et **installateur Windows**, à partir d'un code unique
(Python + [Flet](https://flet.dev)).

![Icône](src/assets/icon.png)

## Ce que fait l'application

| Onglet | Contenu |
|---|---|
| **Projet** | Identification, commune (574 communes, dont les 262 communes wallonnes), période de retour (2 → 200 ans), contraintes du GTI, sauvegarde |
| **Bassins versants** | Autant de bassins versants nommés que nécessaire, chacun avec ses surfaces, ses coefficients de ruissellement du GTI et le bassin d'orage auquel il se raccorde |
| **Réseau** | Les bassins d'orage, leurs raccordements (vers un autre bassin ou l'exutoire), la destination de leur **surverse**, et pour chacun : volume minimal sans surverse, surface d'infiltration minimale, ajutage minimal, temps de vidange. **Dimensionnement en cascade** de l'amont vers l'aval |
| **Dimensionnement** | Pour l'ouvrage choisi : vitesse d'infiltration K (en **m/s**, équivalent mm/h complété tout seul), débit d'ajutage (en **l/s** ou **l/(s·ha)**, au choix), volume sous l'ajutage pour le scénario à orifice surélevé ; comparaison des **4 scénarios** |
| **Bassin** | Encodage de l'ouvrage choisi (volume tampon, volume sous l'ajutage, surface de dispersion, débit d'ajutage) et **simulation** d'une ou plusieurs durées de pluie à la fois |
| **Table QDF** | Tableau récurrences × durées : quelles pluies l'ouvrage encaisse sans déborder |
| **Ajutage** | Dimensionnement de l'orifice (Torricelli), abaque des diamètres commerciaux |
| **Synthèse** | **Schéma du réseau** — bassins versants, bassins d'orage, raccordements, données de chaque ouvrage, pluie dimensionnante, vidange maximale admise — et **simulation du système complet** |
| **Pluies GTI** | Tables QDF en mm et en l/s/ha, coefficients de Montana, courbes IDF |
| **Rapport** | Génération du dossier en **Excel (avec formules vivantes)**, **Word** et **PDF**, synthèse du réseau comprise |

### Les quatre scénarios étudiés

1. **Temporisation seule** (sans dispersion) — seul l'orifice calibré évacue.
2. **Dispersion seule** (sans exutoire ajuté) — toute l'eau s'infiltre.
3. **Temporisation et dispersion** — infiltration par le fond **+** orifice calibré.
4. **Dispersion seule avec temporisation au-delà d'un seuil** — orifice surélevé : sous
   l'axe de l'orifice, seule l'infiltration évacue ; au-dessus, l'ajutage s'y ajoute.

Chaque bassin d'orage du réseau a son propre scénario : rien n'oblige un ouvrage infiltrant
en tête de réseau et un ouvrage étanche à l'exutoire à être traités de la même façon.

### Le réseau

* Un **bassin versant** se raccorde à **un seul** bassin d'orage ; un bassin d'orage en
  reçoit autant qu'on veut.
* Un **bassin d'orage** se déverse dans un autre bassin d'orage ou à l'**exutoire**.
* Sa **surverse** part vers le bassin aval, sauf si l'utilisateur coche qu'elle rejoint le
  **milieu naturel** — auquel cas elle quitte le réseau et n'aggrave plus rien en aval.
* Les collecteurs sont supposés véhiculer tout le débit et les **temps de parcours sont
  négligés**, comme dans les versions précédentes.
* Ce que chaque ouvrage **infiltre** est perdu pour l'aval ; ce qu'il restitue par son
  ajutage, et son trop-plein éventuel, arrivent au bassin suivant.

Le **dimensionnement en cascade** calcule de l'amont vers l'aval : un ouvrage amont
correctement dimensionné ne surverse plus, et l'ouvrage aval s'en trouve allégé.

## Deux fenêtres à la fois (Windows)

Sur le bureau, **Ctrl+N** ou le bouton « Nouvelle fenêtre » ouvre une seconde fenêtre sur le
même projet — comme la commande « Nouvelle fenêtre » d'un tableur. On peut ainsi modifier une
valeur dans un onglet et en voir l'effet dans un autre, côte à côte. La fenêtre
supplémentaire se referme seule, sans arrêter l'application.

Techniquement, Flet fait tourner l'application de bureau derrière un petit serveur local :
la seconde fenêtre est une seconde vue native branchée sur la même session, donc sur le même
projet en mémoire. Sur Android et dans la version web, la fonctionnalité n'a pas de sens et
n'est pas proposée.

## Reprendre le développement ailleurs

[docs/reprise.md](docs/reprise.md) donne le point de départ pour poursuivre ce projet dans
une autre session : commit de référence, carte du code, invariants à ne pas casser, tests à
lancer avant compilation.

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

Deux cas sortent de cette formule fermée et sont traités par intégration exacte, pour que
le tableau des scénarios ne puisse pas contredire la vérification de l'ouvrage : l'ajutage
surélevé (tant que le niveau n'atteint pas son axe, seule la dispersion évacue) et
l'**apport des ouvrages amont**, qui varie dans le temps et se poursuit après l'averse.

### Vérification

Le moteur est confronté à **deux modèles de référence indépendants**, écrits à partir de la
physique seule et sans aucun code partagé avec l'application :

* `app/tests/test_reference.py` — un bassin isolé, simulé à très petits pas. Volume à mettre
  en œuvre, temps de vidange, volume stocké, débordement et courbe entière y sont comparés.
  Campagne aléatoire : `HYDROBASSIN_CAMPAGNE_REFERENCE=120`.
* `app/tests/test_reseau.py` — un **réseau** simulé pas à pas, ouvrage par ouvrage. Il
  vérifie aussi qu'un réseau réduit à un ouvrage rend **au dernier bit** ce que rendait le
  moteur du bassin isolé. Campagne aléatoire : `HYDROBASSIN_CAMPAGNE_RESEAU=40`.

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
| `Build APK Android` | `HydroBassinPlus-3.0.0.apk` |
| `Build Windows` | `HydroBassinPlus-Setup-3.0.0.exe` — installeur Windows (raccourcis menu Démarrer et bureau, désinstallation, installation possible sans droits administrateur) |
| `Captures d'interface` | copies d'écran de chaque onglet en formats téléphone, tablette et bureau (branche `ui-captures`) |

### HydroBassin+ s'installe à côté de la 2.0.0

Les deux versions sont deux programmes distincts, pas deux états du même : elles
s'installent, se lancent et se désinstallent séparément, et chacune garde ses propres
projets enregistrés.

| | HydroBassin 2.0.0 | HydroBassin+ 3.0.0 |
|---|---|---|
| Nom affiché | HydroBassin | **HydroBassin+** |
| Identifiant Android | `be.hydrobassin.hydrobassin` | `be.hydrobassin.hydrobassinplus` |
| `AppId` Inno Setup | `7B5C1E44-…` | `F6730013-…` |
| Dossier Windows | `…\HydroBassin` | `…\HydroBassinPlus` |

C'est l'identifiant, et lui seul, qui décide : à identifiant égal, Android propose une mise
à jour et remplace l'application précédente. Un test (`tests/test_version.py`) refuse que
celui de la 2.0.0 réapparaisse.

Pour passer un projet de l'une à l'autre : « Exporter le projet » dans la 2.0.0, puis
« Importer un projet » dans HydroBassin+, qui relit les enregistrements des versions
antérieures.

En local (Flutter 3.29.x requis, installé automatiquement par flet si absent) :

```bash
pip install "flet[all]==0.28.3"
flet build apk        # Android
flet build windows    # Windows (à lancer sur Windows, Visual Studio 2022 requis)

# puis l'installeur (Inno Setup 6) :
iscc /DSourceDir=..\..\build\windows /DExeName=HydroBassin.exe /DMaVersion=3.0.0 \
     /DOutputDir=..\..\..\livrables packaging\windows\hydrobassin.iss
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
│       │   ├── reseau.py        bassins versants, bassins d'orage, raccordements, routage
│       │   ├── hydro.py         méthode rationnelle, scénarios, minima (dichotomie)
│       │   ├── simulation.py    remplissage / vidange, table QDF d'acceptation
│       │   └── orifice.py       Torricelli, abaque des diamètres
│       ├── data/gti_rainfall.json.gz   données extraites du classeur GTI (193 Ko)
│       ├── reports/
│       │   ├── dossier.py       assemblage du dossier de calcul
│       │   ├── schema.py        géométrie du schéma de réseau (écran et PDF)
│       │   ├── charts.py        graphiques + rasteriseur PNG en Python pur
│       │   ├── xlsx_report.py   classeur Excel avec formules vivantes
│       │   ├── docx_writer.py   générateur DOCX (OOXML) sans dépendance native
│       │   ├── docx_report.py   rapport Word
│       │   ├── pdf_writer.py    générateur PDF sans dépendance native
│       │   └── pdf_report.py    rapport PDF (graphiques vectoriels)
│       ├── formats.py           virgule décimale, partagée écran et rapports
│       └── ui/                  thème, état, graphiques Flet, fenêtres et 10 vues
├── tests/                       tests unitaires, dont la conformité au GTI
└── tools/                       génération de l'icône et du dossier de démonstration
```

## Sauvegarde des projets

L'onglet **Projet** exporte le projet complet dans un fichier `.json` lisible et le réimporte
tel quel : bassins versants, bassins d'orage, raccordements, sol, ouvrages et scénarios. Le
fichier porte une marque `HydroBassin` et un numéro de version ; un fichier étranger ou
illisible est refusé avec un message, sans toucher au projet ouvert.

Un projet enregistré par une version **2.x** se recharge : ses surfaces deviennent un bassin
versant, son ouvrage un bassin d'orage raccordé à l'exutoire, et son bassin d'orage amont —
s'il en avait un — un second ouvrage raccordé au premier, avec son propre bassin versant.

Sur **Android**, le sélecteur du système ne rend pas un chemin de fichier mais un URI du
*Storage Access Framework* (`/document/primary:Documents/…`), que Python ne sait pas ouvrir :
la copie échouait avec un « No such file or directory » alors que le projet était bel et bien
enregistré. L'application n'ouvre donc plus ce sélecteur sur téléphone — elle écrit dans le
dossier **Téléchargements**, visible de n'importe quel gestionnaire de fichiers, et annonce le
chemin. Sur ordinateur, le sélecteur reste proposé ; une destination qui ne serait pas
réellement accessible est signalée sans faire croire à un échec de l'export.

## Navigation

Rail latéral sur ordinateur, tiroir sur téléphone, et **Ctrl+1 à Ctrl+9** puis **Ctrl+0**
pour passer directement à une section. Les onglets de détail (Dimensionnement, Bassin, Table
QDF, Ajutage, Rapport) portent un sélecteur d'ouvrage dès qu'un projet en compte plusieurs.

## Le dossier PDF et Word

Le rapport porte sur **l'étude entière**, pas sur le seul ouvrage affiché à l'écran. Dès
que le projet compte plusieurs bassins d'orage, le dossier s'articule ainsi :

| Section | Contenu |
|---|---|
| 1. Données d'entrée du projet | contraintes communes au réseau, puis tous les bassins versants |
| 2. Synthèse du réseau | raccordements, dimensionnement de chaque ouvrage, simulation d'ensemble |
| 3. Pluie de projet | Montana ou QDF : une seule fois, la pluie est commune au réseau |
| 4, 5, … | **un chapitre par bassin d'orage** : surfaces raccordées, scénarios, vérification, table QDF, ajutage |
| dernière | conclusion du réseau : volume cumulé, puis chaque ouvrage avec son volume, sa pluie critique et sa vidange |

Ce qui vaut pour tout le système — commune, période de retour, sécurité sur K, vidange
maximale admise, pluie de projet — n'est écrit qu'une fois ; le répéter dans chaque
chapitre n'apprendrait rien et laisserait croire que ces valeurs diffèrent d'un ouvrage à
l'autre. Sur un projet à bassin unique, le dossier garde sa forme d'origine (données
d'entrée, pluie, scénarios, vérification, table QDF, ajutage, conclusion).

Les deux formats sont écrits par le même découpage et se lisent donc de la même façon ;
seule la synthèse du réseau diffère : le PDF trace le schéma au vecteur, le Word le rend
en arbre indenté, faute d'une police vectorielle dans le rasteur embarqué.

## Le classeur Excel

Le classeur n'est pas un tirage figé : il **recalcule**. Retoucher une surface, un
coefficient de ruissellement, un K ou un débit d'ajutage change les volumes, feuille par
feuille, jusqu'au récapitulatif du réseau.

| Feuille | Contenu |
|---|---|
| `Projet` | données générales : commune, période de retour, source des pluies, sécurité |
| `Bassins versants` | chaque surface, son coefficient, sa surface active |
| `Ouvrages` | une ligne par bassin d'orage ; les cellules bleues se modifient |
| `Pluie n` / `Scénarios n` | le balayage des durées et les quatre scénarios, **pour chaque ouvrage** |
| `Réseau` | raccordements, synthèse, et un récapitulatif qui tire ses chiffres des feuilles ci-dessus |
| `Bassin - table QDF`, `Ajutage`, `Pluies statistiques` | vérification de l'ouvrage courant, orifice, données sources |

Les feuilles sont numérotées (`Pluie 1`, `Scénarios 2`) parce qu'Excel plafonne les noms à
31 caractères : « Bassin d'orage de la voirie » s'y tronquerait en un intitulé indistinct
de son voisin. Le nom de l'ouvrage se lit en titre de sa feuille et dans la colonne
« Feuilles de calcul » de la feuille `Ouvrages`.

**Les minima aussi se calculent.** La surface d'infiltration minimale et l'ajutage minimal
sortaient d'une dichotomie du moteur et restaient figés : retoucher K ou le temps de
vidange ne les bougeait pas. Or la condition de vidange s'inverse. Elle s'écrit
`V x 1000 / Q / 3600 <= T`, et en y portant `V = h x S / 1000 - Q x t x 60 / 1000` le débit
sort du maximum :

```
Q >= (h x S / 1000) / (3,6 x T + 0,06 x t)
```

Le débit de sortie minimal est donc le **maximum d'une colonne** — une par durée de pluie,
sur la feuille `Pluie n` — dont on retranche l'organe déjà en place pour obtenir l'autre.
Plus de tâtonnement : diviser K par dix multiplie la surface minimale par dix, ramener la
vidange de 48 à 24 h la fait passer de 109,5 à 184,3 m². Vérifié contre le moteur sur 200
projets tirés au sort : écart maximal **0,1 %**, qui est la tolérance de la dichotomie
elle-même.

**Ce qui reste figé**, dans des cellules modifiables et signalées en orange :

* l'**apport des ouvrages amont**, et tous les minima d'un ouvrage qui en reçoit un : cet
  apport varie dans le temps et se poursuit après l'averse, aucune formule de cellule ne
  sait l'intégrer pas à pas ;
* les minima du **scénario à seuil**, où l'ajutage surélevé ne s'ouvre qu'à un instant qui
  dépend lui-même de l'infiltration — l'inversion ci-dessus n'y tient plus ;
* la **simulation** (pointe, débordement, vidange), pour la même raison ; capacité,
  remplissage et totaux, eux, se recalculent.

Recalculé cellule par cellule, le classeur retrouve le moteur à **0,02 %** près — l'écart
que laisse sa grille d'une centaine de durées, contre 17 280 dans l'application.

## Saisie

* Les nombres s'affichent à la française (**virgule décimale**), à l'écran comme dans les
  rapports Word et PDF. Le classeur Excel garde des cellules numériques, mises en forme
  par Excel selon la langue du poste.
* La saisie accepte indifféremment `1e-5`, `0,00001` ou `0.00001`. Une valeur fautive
  n'est signalée qu'une fois le champ quitté : taper `1e-5` passe par `1e`, qui n'est pas
  un nombre sans que l'utilisateur ait commis d'erreur.
* Les résultats se recalculent à la sortie du champ, et à défaut peu après la dernière
  frappe : sous Windows, un clic dans une zone non saisissable ne déclenche pas toujours
  `on_blur`, et l'écran restait alors périmé sans le dire.
* Les champs couplés se complètent dans les deux sens. Pour K (m/s ↔ mm/h) les deux
  cases expriment la même grandeur. Pour l'ajutage, l'unité de saisie décide : encodé
  en **l/(s·ha)** le débit en l/s se calcule seul sur la surface incidente raccordée
  (bassins versants situés en amont compris si la case de l'onglet Réseau est cochée) ;
  encodé en **l/s** il est fixé en valeur absolue et la case l/(s·ha) n'affiche qu'un
  équivalent.
* La conversion reste désactivée tant qu'aucune surface n'est encodée.

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
