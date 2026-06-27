# MAO Moderne — Plan de migration & feuille de route

Reconstruction de MAO V8 (PowerBuilder 7 + Sybase SQL Anywhere 6) en
application **desktop installable**, **sans régression fonctionnelle**.

## Pile technique cible

| Couche | Choix | Raison |
|---|---|---|
| Langage / runtime | **.NET 8** (C#) | mature, outillage desktop riche, installeurs |
| UI | **Avalonia UI 11** (MVVM) | multiplateforme (Win/Mac/Linux), proche de l'ergonomie « formulaire + grille » de PowerBuilder |
| Données | **EF Core 8 + SQLite** | base embarquée mono-fichier (comme `MAO.db`), zéro serveur à installer |
| Tests | **xUnit** | logique de calcul testée (non-régression) |
| Packaging | `dotnet publish` self-contained + Inno Setup (Win) | installeur `.exe`, sans dépendance runtime |

## Architecture

```
app/
  MaoModerne.sln
  src/
    Mao.Domain/   # entités + règles de calcul (pur, testable, sans dépendance UI/DB)
    Mao.Data/     # EF Core DbContext, configuration, seed, accès données
    Mao.App/      # Avalonia : Views (XAML) + ViewModels (MVVM)
  tests/
    Mao.Tests/    # tests unitaires (calculs, ViewModels)
```

Principe : la logique métier vit dans `Mao.Domain` (sans dépendance), exactement
ce qui permet de **garantir les calculs** indépendamment de l'UI.

## Améliorations « plus pratique et flexible » (sans retrait de fonctionnalité)

Réponses ciblées aux limites du §6 de `01-analyse-mao-v8.md` :

1. **Multiplateforme** : Avalonia → Windows, mais aussi macOS/Linux possibles.
2. **Installation simple** : base SQLite auto-créée au 1er lancement, plus de
   DSN ODBC ni de `ServerName=TOBEDEFINED` à configurer.
3. **Configuration centralisée** : un `appsettings.json` lisible remplace les
   `.ini` éparpillés (compat. import des `[PARAM]`/`[OPTION_RECAP]`).
4. **États paramétrables** : un seul moteur de récapitulatif avec options
   (au lieu des ~16 DataWindows quasi dupliquées).
5. **Exports modernes** : CSV/JSON/PDF natifs en plus d'Excel.
6. **Catalogue versionné** : import des mises à jour `POSTE_STD` par fichier
   de données (JSON/SQL) appliqué depuis l'appli, plus de `.exe` par révision.

## Feuille de route par phases (= non-régression incrémentale)

| Phase | Périmètre (réf. §4 de l'analyse) | État |
|---|---|---|
| **1. Socle + Gestion des métrés** | structure projet, modèle données cœur (Metre/Division/Chapitre/Poste/PosteStd/Tva), CRUD métrés, éditeur hiérarchique, calculs HTVA/TVA/TTC, tests | **fait** |
| **2. Catalogue normalisé** | recherche par mot-clé/code, sélection et insertion d'un poste std dans le métré, importeur JSON (catalogue complet), seed RW99 (§4.2) | **fait** |
| **3. Bordereaux & états** | bordereau, métré estimatif, métré récapitulatif, export **PDF** (QuestPDF) et **CSV** (§4.4) | **fait** |
| 4. Exports | Excel/JSON en plus du PDF/CSV déjà livrés (§4.5) | partiel (CSV+PDF faits) |
| **5. Révision de prix** | indices salaire/matériaux (CRUD), formules de révision, calcul du coefficient et du prix révisé (§4.3) | **fait** |
| 6. Adjudications & statistiques | import/export, SIGMA (§4.6) | à venir |
| **7. Administration** | utilisateurs, entités, agents, taux de TVA, paramètres applicatifs centralisés (remplace les .ini) (§4.7) | **fait** |
| 8. Import des données V8 | reprise du contenu de `MAO.db` (§4.8) | à venir |

Aucune phase n'est « terminée » tant que les éléments correspondants de la
check-list §4 ne sont pas couverts par l'application **et** par des tests.

## Import des données d'origine (phase 8)

`MAO.db` est une base Sybase SQL Anywhere 6. La reprise se fera via :
1. `dbunload` (outil Sybase fourni dans le package) → fichiers `.dat`/SQL ;
2. script de transformation vers le schéma SQLite (mapping du §
   `02-modele-donnees.md`) ;
3. validation par rapprochement des totaux métré par métré (contrôle de
   non-régression chiffré).
