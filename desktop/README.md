# Application Windows (.exe) — Gabarit Tranchées Impétrants

Application de bureau (Electron) embarquant l'application web autonome. Elle
fonctionne **100 % hors-ligne**, conserve **automatiquement le projet en cours**
entre les sessions (profil utilisateur Windows), et permet de créer / modifier /
exporter / importer des projets (fichiers `.json`) ainsi que de générer les
livrables **Excel** et **Word** via la boîte « Enregistrer sous » native.

## Récupérer l'installateur sans rien installer (recommandé)

Le workflow GitHub Actions `.github/workflows/windows-exe.yml` compile
l'installateur à chaque push :

1. Onglet **Actions** du dépôt → workflow **« Windows EXE »** → dernier run.
2. Section **Artifacts** → télécharger **`gabarit-tranchees-windows-setup`**.
3. Décompresser et lancer **`GabaritTranchees-Setup-1.0.0.exe`**.

L'installateur (NSIS, en français) propose le choix du dossier d'installation
et crée des raccourcis Bureau + menu Démarrer. Windows SmartScreen peut
demander confirmation (binaire non signé) : « Informations complémentaires »
→ « Exécuter quand même ».

## Compiler en local

Prérequis : Node.js 18+ (l'idéal est de compiler sous Windows).

```bash
# 1) (re)générer le fichier web autonome
python3 app/build.py
# 2) le copier dans l'app de bureau
mkdir -p desktop/app && cp gabarit-tranchees-impetrants.html desktop/app/index.html
# 3) compiler l'installateur
cd desktop
npm install
npm run dist
# Résultat : desktop/dist/GabaritTranchees-Setup-1.0.0.exe
```

## Caractéristiques

| Élément | Valeur |
|---|---|
| Nom | Gabarit Tranchées Impétrants |
| Installateur | NSIS (assistant, choix du dossier, raccourcis) |
| Mémoire des projets | automatique (localStorage persisté dans `%AppData%`) |
| Projets | créer / modifier / exporter / importer en `.json` |
| Livrables | Excel (.xlsx avec formules + synthèse) et Word (.docx) |
| Dépendances runtime | aucune (tout est embarqué) |
