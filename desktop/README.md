# Application Windows (.exe) — Gabarit Tranchées Impétrants

Application de bureau (Electron) embarquant l'application web autonome. Elle
fonctionne **100 % hors-ligne**, conserve **automatiquement le projet en cours**
entre les sessions (profil utilisateur Windows), et permet de créer / modifier /
exporter / importer des projets (fichiers `.json`) ainsi que de générer les
livrables **Excel** et **Word** via la boîte « Enregistrer sous » native.

## Récupérer l'application

Le workflow GitHub Actions `.github/workflows/windows-exe.yml` compile à chaque
push. Onglet **Actions** du dépôt → workflow **« Windows EXE »** → dernier run →
section **Artifacts** → télécharger **`gabarit-tranchees-windows-setup`**.

L'archive contient **trois façons de lancer l'application**, de la plus simple
à la plus intégrée :

| Fichier | Ce qu'il faut faire | Installation |
|---|---|---|
| `gabarit-tranchees-impetrants.html` | double-clic (s'ouvre dans Edge) | aucune |
| `GabaritTranchees-portable-1.0.0.zip` | décompresser, lancer l'`.exe` du dossier | aucune |
| `GabaritTranchees-Setup-1.0.0.exe` | installateur classique | oui |

Plus `SHA256SUMS.txt`, pour vérifier qu'un téléchargement n'a pas été tronqué.

Le fichier HTML offre **exactement les mêmes fonctions** que l'application de
bureau (calculs, coupe, exports Excel et Word, mémoire du projet en cours) : la
version Electron n'est qu'une coque autour de ce même fichier. C'est la voie à
privilégier sur un poste où l'installation de logiciels est restreinte.

L'installateur (NSIS, en français) propose le choix du dossier d'installation
et crée des raccourcis Bureau + menu Démarrer. Windows SmartScreen peut
demander confirmation (binaire non signé) : « Informations complémentaires »
→ « Exécuter quand même ».

## Dépannage : « Windows ne parvient pas à accéder au périphérique, au chemin d'accès ou au fichier spécifié »

Ce message apparaît au lancement de l'installateur, avant toute installation.
Il ne vient pas du programme : Windows a refusé de l'exécuter. Les causes, dans
l'ordre de fréquence :

1. **L'antivirus a bloqué ou mis en quarantaine le fichier.** C'est de loin la
   cause la plus courante : un installateur non signé numériquement qui embarque
   Electron déclenche fréquemment Microsoft Defender ou un antivirus d'entreprise.
   Vérifier dans **Sécurité Windows → Protection contre les virus et menaces →
   Historique de la protection**. Le fichier peut rester visible dans
   l'Explorateur tout en étant interdit d'exécution.

2. **Le fichier est « bloqué » (marque de provenance Internet).**
   Clic droit sur l'`.exe` → **Propriétés** → en bas de l'onglet *Général*,
   cocher **Débloquer** → *Appliquer*. À faire sur le fichier `.exe` lui-même,
   après décompression.

3. **Décompression incomplète.** L'Explorateur Windows tronque parfois les
   archives volumineuses (~80 Mo ici). Comparer l'empreinte du fichier avec
   `SHA256SUMS.txt` :

   ```powershell
   Get-FileHash .\GabaritTranchees-Setup-1.0.0.exe -Algorithm SHA256
   ```

   Si elle diffère, re-télécharger et décompresser avec 7-Zip plutôt qu'avec
   l'Explorateur.

4. **Stratégie d'entreprise (AppLocker, SmartScreen d'entreprise)** interdisant
   l'exécution depuis le Bureau ou les Téléchargements. Essayer depuis
   `C:\Users\<vous>\AppData\Local\Temp`, ou demander à l'informatique.

5. **Raccourci pointant vers un fichier déplacé ou supprimé** — si vous lancez
   depuis un raccourci et non depuis l'`.exe`, vérifier sa cible.

**Dans tous les cas, la solution immédiate est le fichier
`gabarit-tranchees-impetrants.html`** : c'est un simple document, aucun
antivirus ni aucune stratégie ne le bloque, et il fait tout ce que fait
l'application installée.

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
| Version portable | zip, à décompresser et lancer sans installation |
| Mémoire des projets | automatique (localStorage persisté dans `%AppData%`) |
| Projets | créer / modifier / exporter / importer en `.json` |
| Livrables | Excel (.xlsx avec formules + synthèse) et Word (.docx) |
| Dépendances runtime | aucune (tout est embarqué) |
