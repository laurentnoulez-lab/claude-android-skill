# Gabarit Tranchées 2.0

Version **2.0.0** (13/11/2026) — numéro et date dans `app/version.json`, affichés
dans l'en-tête de l'application, la fenêtre « À propos », le classeur Excel et la
notice Word.

Ouvrir **`gabarit-tranchees-v2.html`** (racine du dépôt) dans un navigateur :
aucune installation, aucune connexion. Les applications Windows (`desktop/`) et
Android (`android/`) embarquent ce même fichier.

## Une application distincte de la version 1

La 2.0 est un **nouveau programme**, pas une mise à jour : elle s'installe à côté
de la 1.x sans la remplacer ni toucher à ses données.

| | Version 1 | Version 2.0 |
|---|---|---|
| Fichier autonome | `gabarit-tranchees-impetrants.html` (conservé, figé) | `gabarit-tranchees-v2.html` |
| Windows (appId / nom) | `com.tranchees.impetrants` / Gabarit Tranchées Impétrants | `com.tranchees.impetrants.v2` / Gabarit Tranchées 2.0 |
| Android (applicationId) | `com.tranchees.impetrants` | `com.tranchees.impetrants.v2` |
| Stockage navigateur | `gabarit-tranchees-impetrants:project` | `gabarit-tranchees-v2:*` |

Les projets `.json` de la version 1 s'importent tels quels (la couche de surface
devient un coffre, comme avant). Au premier lancement dans un navigateur où la
1.x a servi, son projet est repris **par copie**.

## Nouveautés de la 2.0

- **Couche de surface au choix** par tronçon : *coffre* ou *terre végétale* (cases
  à cocher), épaisseur toujours libre, au-dessus du remblai en terres ou en
  empierrement. La terre végétale est dessinée en brun sombre avec la pelouse en
  surface. La mention « SPI » a disparu. La nature de la couche ne modifie aucun
  calcul de tranchée ; son **volume** (épaisseur × largeur × longueur) est ajouté
  à l'application, au classeur Excel (2 colonnes après BT) et à sa synthèse.
- **Interface** : navigation par onglets (Impétrants · Paramètres · Tronçons ·
  Synthèse), saisie d'un tronçon en deux colonnes avec **coupe en direct**, blocs
  Câbles / Conduites nettement distincts et **barre de séparation** entre les deux,
  totaux permanents, nouveau logo.
- **Onglet Synthèse** : métré, volumes par type, clé de répartition cumulée.
- **Alertes de cohérence** en direct (remblai négatif, largeur max dépassée,
  valeur aberrante du type « 20 m » au lieu de « 0,20 m »…).
- **Annuler / rétablir** (Ctrl+Z / Ctrl+Y), **plusieurs projets** enregistrés,
  réordonnancement des tronçons, confirmation avant suppression.
- **Livrables** : coupes insérées dans la notice Word, coupe exportable en PNG,
  impression / PDF (synthèse + une fiche par tronçon).

## Non-régression

`npm test` (dans `app/`) exécute quatre suites, dont `test/v2.test.js` qui compare
la 2.0 au moteur de la version 1 conservé dans `test/fixtures/model-v1.js` : sur
60 projets aléatoires, valeurs, répartitions, totaux et cellules Excel héritées
sont strictement identiques. L'équivalence avec le classeur de référence reste à
1031/1031 valeurs.

---

*La suite de ce document décrit le fonctionnement commun aux deux versions.*

## Gabarit tranchées impétrants — fonctionnement

Application HTML autonome qui automatise l'onglet **« Gabarits tranchées
communes »** du classeur de récapitulatif des tranchées impétrants : saisie des
tronçons, calcul des largeurs et volumes, **répartition des volumes par
impétrant**, et **génération d'un classeur Excel avec les mêmes formules** que
l'original.

## Utilisation

Ouvrir le fichier **`gabarit-tranchees-v2.html`** (à la racine du dépôt)
dans un navigateur. Aucune installation, aucun serveur, aucune connexion
internet : tout est embarqué dans un seul fichier (y compris la bibliothèque
ExcelJS).

### Saisie des nombres

Tous les champs numériques acceptent **indifféremment la virgule et le point**
comme séparateur décimal : `0,48` et `0.48` donnent la même valeur. Les espaces
de milliers sont tolérés (`1 234,5`). À la sortie du champ, la valeur est
réaffichée sous forme normalisée (`0,5`). Une saisie illisible passe le champ en
rouge et **n'écrase pas** la valeur en place ; un champ vidé vaut zéro.

> Auparavant ces champs étaient des `<input type="number">`, qui suppriment la
> virgule sans le signaler : une largeur saisie `0,48` était enregistrée `048`,
> soit **48 m au lieu de 0,48 m**, et l'erreur se propageait jusqu'au classeur
> Excel. **Si vous avez saisi des valeurs avec la virgule avant ce correctif,
> vérifiez-les : elles peuvent être 10 ou 100 fois trop grandes.**

### Fonctions

- **Impétrants (concessionnaires)** — ajouter / renommer / supprimer des
  impétrants et leurs sous-réseaux (DP, DD, E, Éclairage…), et choisir leur
  catégorie *Câbles* ou *Conduites*. La structure des colonnes et les formules
  du gabarit s'adaptent automatiquement. Un interstice précède chaque
  sous-réseau (plus un interstice de fin de catégorie) ; une catégorie sans
  sous-réseau ne comporte aucun interstice.
- **Coupe de tranchée à l'échelle** — pour chaque tronçon, un schéma vertical
  (coffre, remblais, sable, câbles en rectangles, conduites en cercles) avec
  cotes de largeur et de profondeur, mis à jour en direct.
- **Parts de volume par sous-réseau** — tableau par tronçon : largeur, part dans
  la tranchée de la catégorie, part dans la tranchée totale et volume attribué,
  avec une note expliquant la répartition des interstices.
- **Générer Word** — produit un document `.docx` (notice méthodologique +
  données du projet + explication du traitement des interstices).
- **Options par tronçon fidèles à l'original** : remblai terres décaissées /
  empierrement de sous-fondation (AP/AQ/BJ/BK automatiques), **câbles sous
  gaines** (le volume des fourreaux Ø paramétrable est déduit du sable, et la
  longueur de gaines AU devient automatique), et hauteur moyenne des conduites
  forcée (BB). Équivalence vérifiée à 100 % sur les 43 tronçons du classeur de
  référence (1031/1031 valeurs identiques).
- **Paramètres par défaut** — valeurs géométriques pré-remplies pour chaque
  nouveau tronçon (lit de pose, recouvrements, hauteur de coffre, etc.), avec un
  bouton pour les réappliquer à tous les tronçons existants.
- **Tronçons** — un tableau de tronçons avec aperçu en direct de la largeur
  théorique, du volume total et du contrôle OK/NOK (largeur max dépassée). Chaque
  tronçon s'édite dans un panneau détaillé : identité, largeurs par canal et
  interstices (a–h), paramètres câbles et conduites.
- **Exporter / Importer** — sauvegarder un projet au format `.json` et le
  recharger plus tard. Le projet en cours est aussi conservé automatiquement
  dans le navigateur (localStorage).
- **Générer Excel** — produit un `.xlsx` à deux onglets :
  - *Gabarits tranchées communes* : le gabarit complet avec en-têtes teintés
    (câbles en bleu, conduites en vert), cellules de saisie (jaunes), **toutes
    les formules** (largeurs, volumes, répartition en %), et une ligne TOTAL ;
  - *Synthèse* : quantités globales pour le métré, longueurs/volumes par type
    de tranchée (COUNTIF/SUMIFS), et **clé de répartition par sous-réseau**
    (SUMPRODUCT pondéré par les volumes) avec contrôle « somme = 100 % » —
    le tout en formules vivantes qui se recalculent dans Excel.

## Périmètre

Couvre le gabarit, les volumes calculés et la répartition des volumes par
impétrant. Le bordereau de prix (onglet « Métré avec répartition ») n'est
volontairement pas inclus.

## Développement

Les sources sont dans `app/src/` et le fichier autonome est généré par un script
de build qui inline le CSS, le JS et ExcelJS dans le template HTML.

```
app/
├── src/
│   ├── model.js            # modèle, moteur de calcul, génération Excel (sans DOM)
│   ├── coupe.js            # coupe verticale à l'échelle (SVG)
│   ├── docx-export.js      # génération du document Word
│   ├── ui.js               # interface (navigateur)
│   ├── styles.css
│   └── index.template.html
├── vendor/
│   ├── exceljs.min.js      # ExcelJS 4.4.0 (embarqué)
│   └── docx.iife.js        # docx 8.5.0 (embarqué)
├── version.json            # numéro et date de version (source unique)
└── build.py                # produit gabarit-tranchees-v2.html à la racine
```

Reconstruire le fichier autonome après modification des sources :

```bash
python3 app/build.py
```

### Tests

```bash
cd app && npm install && npm test
```

Le cœur métier (`model.js`) fonctionne aussi sous Node. Le moteur de calcul a
été validé contre les valeurs réelles du classeur de référence (lignes 5 et 30)
et les formules générées contre les positions de colonnes de l'original.
