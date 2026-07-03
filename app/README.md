# MAO Moderne

Reconstruction moderne et installable de **MAO V8** (Métré Assisté par
Ordinateur, Qualiroutes/RW99), à l'origine en PowerBuilder 7 + Sybase SQL
Anywhere 6. Objectif : **aucune régression fonctionnelle**, plus pratique et
plus flexible. Voir l'analyse complète dans [`../docs`](../docs).

## Pile technique

- **.NET 8** / C#
- **Avalonia UI 11** (interface desktop multiplateforme, MVVM)
- **EF Core 8 + SQLite** (base embarquée mono-fichier, remplace `MAO.db`)
- **xUnit** (tests de non-régression sur les calculs)

## Structure

```
app/
  MaoModerne.sln
  src/
    Mao.Domain/   # entités + règles de calcul (pur, sans dépendance)
    Mao.Data/     # EF Core (DbContext, seed, services)
    Mao.App/      # Avalonia : Views (XAML) + ViewModels
  tests/
    Mao.Tests/    # tests unitaires & d'intégration
```

## Prérequis

[.NET SDK 8.0](https://dotnet.microsoft.com/download).

## Lancer en développement

```bash
cd app
dotnet run --project src/Mao.App/Mao.App.csproj
```

La base SQLite est créée automatiquement au premier lancement dans le dossier
de données utilisateur (`%AppData%/MaoModerne/mao.db` sous Windows).

## Tester

```bash
cd app
dotnet test
```

## Produire un exécutable installable (Windows)

```bash
cd app
dotnet publish src/Mao.App/Mao.App.csproj -c Release -r win-x64 \
  --self-contained true -p:PublishSingleFile=true
```

L'exécutable autonome est généré sous
`src/Mao.App/bin/Release/net8.0/win-x64/publish/MaoModerne.exe`
(aucune installation de runtime requise sur le poste cible). Un installeur
`.exe` peut ensuite être produit avec Inno Setup.

## Périmètre actuel (Phases 1 à 3)

**Phase 1 — Gestion des métrés**
- Création / ouverture / suppression de métrés.
- Édition de la hiérarchie Division → Chapitre → Poste.
- Saisie quantité / prix unitaire, calcul temps réel des totaux
  **HTVA / TVA / TTC**.
- Persistance SQLite.

**Phase 2 — Catalogue normalisé (RW99/Qualiroutes)**
- Recherche par mot-clé / code dans le catalogue des postes standardisés.
- Insertion d'un poste normalisé dans le métré (« + Poste normalisé »).
- Importeur du catalogue complet via JSON (`CatalogueImporter`), pour charger
  les données réelles issues de `MAO.db` sans `.exe` de mise à jour.
- Le catalogue est pré-chargé avec un échantillon RW99 représentatif, à
  remplacer par l'import des données réelles.

**Phase 3 — Bordereaux & états**
- Génération du **bordereau**, du **métré estimatif** et du
  **métré récapitulatif**.
- Export **PDF** (QuestPDF), **Excel .xlsx** (ClosedXML) et **CSV** (Excel FR).

**Données réelles préchargées**
- Le **catalogue Qualiroutes QR21** (14 092 postes, repris de la base
  `MAO.db` de l'utilisateur) et les **701 métrés** (78 742 postes) sont
  **embarqués** et chargés au premier lancement, avec : taux de TVA réels,
  **types et codes de déchets**, affectations déchets par poste (4 210),
  **formules de révision par métré** (704), prix des postes déchets (1 269),
  **indices salaires et matériaux réels** (2 337 valeurs jusqu'à fin 2023) et
  formules de référence TP (109). Le premier démarrage prend ~25 secondes
  (insertion initiale), puis l'application démarre instantanément.
- Fidélité de la reprise : 99,3 % des quantités concordent avec les formules
  saisies ; les écarts résiduels (dossiers 108 « ROCHEFORT/JAMBLINNE » ×1000 et
  partiellement 259 « N62-MALMEDY ») existent tels quels dans la base
  d'origine — MAO V8 affichait les mêmes montants.

**Génération des postes déchets (D9000)** — bouton « Générer postes déchets »
- Reproduit la génération de MAO V8 : quantité de déchet = quantité du poste ×
  coefficient de conversion (affectation TYPE_DECHET_POSTE), répartie vers les
  codes de destination D9xxx selon les pourcentages de la table CODE_DECHET,
  prix repris de PRIX_POSTE_DECHET. Les postes générés sont marqués et
  remplacés à chaque exécution (équivalent O_GEN_AUTO).

**Formules de révision** — bouton « Formules de révision »
- Les formules propres à chaque métré (reprises de FORMULE_REVISION) sont
  affichées avec leurs coefficients (p = p0 × (A·s/S + B·i/I + C), type 3 =
  sans révision) et un calcul de coefficient/prix révisé pré-rempli avec les
  indices réels.

**Échange .mao** — menu Données
- Export du métré ouvert / import d'un métré au format d'échange `.mao`
  (texte tabulé sectionné, décimales « . » ou « , », encodage Windows-1252
  comme le programme d'origine). ⚠️ Le format exact des fichiers produits par
  MAO V8 doit encore être validé sur un fichier témoin exporté du programme
  d'origine.

## Installateur Windows

Le dossier [`installer/`](installer/) contient le script NSIS. Compilation :

```bash
dotnet publish src/Mao.App/Mao.App.csproj -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true \
  -p:EnableCompressionInSingleFile=true -o publish
makensis -DVERSION=1.1 -DPUBLISH_DIR=publish installer/MaoModerne.nsi
```

Produit `MaoModerne-Setup-<version>.exe` : installation dans Program Files,
raccourcis menu Démarrer (groupe « Qualiroutes ») et bureau, désinstalleur,
association du type de fichier `.mao`. La base de données utilisateur
(`%AppData%\MaoModerne`) est conservée à la désinstallation.

**Phase 5 — Révision de prix** (menu Outils → Révision de prix)
- Gestion des indices salaire et matériaux (CRUD, par période mensuelle).
- Formules de révision : `p = p0 × (part fixe + Σ coef × indice_courant / indice_base)`.
- Calcul du coefficient de révision et du prix révisé entre deux périodes.

**Phase 7 — Administration** (menu Outils → Administration)
- Utilisateurs, entités, agents, taux de TVA.
- Paramètres applicatifs centralisés (remplacent les fichiers `.ini` de MAO V8).

**Phase 6 — Adjudications & statistiques** (menu Outils → Adjudications & statistiques)
- Import du **fichier statistiques natif MAO** (version/portée/adjudications/
  lignes de prix), parsé fidèlement.
- Statistiques de prix **min/max par poste**, résolues vers le catalogue via
  (ChapitreStdId, PosteStdId), avec intitulé.
- Export CSV.

**Catalogue réel intégré** : le **Catalogue des Postes Normalisés** Qualiroutes
(CPN, liste QR17 — **9 691 postes**) est embarqué (JSON gzippé) et chargé au
premier lancement. Le `CatalogueImporter` permet de réimporter une version mise
à jour.

**Menu Données — import / export**
- **Importer un fichier MAO (.db Sybase)** : connexion ODBC directe à un
  `MAO.db` (nécessite le pilote *SQL Anywhere* installé — présent sur le poste
  où MAO V8 tourne). Importe le catalogue `POSTE_STD` et les métrés.
- **Importer le catalogue (JSON)** : met à jour le catalogue normalisé.
- **Exporter / Importer une sauvegarde (JSON)** : sauvegarde portable complète
  (métrés, catalogue, indices, formules, TVA, statistiques, administration) et
  restauration sur n'importe quel poste.

> Si le pilote Sybase n'est pas disponible, l'app le signale et invite à fournir
> un export `dbunload` (.sql) ou une sauvegarde JSON. La conversion des **métrés**
> depuis `MAO.db` est en *best-effort* (lecture défensive des colonnes) et sera
> ajustée sur des données réelles.

Les exports Excel/JSON additionnels restent décrits dans
[`../docs/03-plan-migration.md`](../docs/03-plan-migration.md).

## Importer le catalogue réel depuis `MAO.db`

`MAO.db` est une base **Sybase SQL Anywhere 6** : sa lecture nécessite les
outils Sybase fournis dans le package d'origine (Windows). Procédure :

1. Sur un PC Windows où MAO V8 est installé, exporter la table `POSTE_STD` au
   format texte avec `dbunload` (fourni dans le package), ou via une requête
   ODBC vers du CSV/JSON.
2. Convertir au format JSON attendu par `CatalogueImporter` (liste d'objets
   `PosteStd` ; voir `docs/02-modele-donnees.md`).
3. Importer dans l'application (le même mécanisme servira pour la reprise
   complète des métrés en phase 8).
