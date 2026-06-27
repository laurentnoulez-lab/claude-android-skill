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

## Périmètre actuel (Phase 1 — Gestion des métrés)

- Création / ouverture / suppression de métrés.
- Édition de la hiérarchie Division → Chapitre → Poste.
- Saisie quantité / prix unitaire, calcul temps réel des totaux
  **HTVA / TVA / TTC**.
- Persistance SQLite.

Les phases suivantes (catalogue normalisé, bordereaux & états, exports,
révision de prix, statistiques, administration, import des données V8) sont
décrites dans [`../docs/03-plan-migration.md`](../docs/03-plan-migration.md).
