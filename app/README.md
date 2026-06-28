# Gabarit tranchées impétrants

Application HTML autonome qui automatise l'onglet **« Gabarits tranchées
communes »** du classeur de récapitulatif des tranchées impétrants : saisie des
tronçons, calcul des largeurs et volumes, **répartition des volumes par
impétrant**, et **génération d'un classeur Excel avec les mêmes formules** que
l'original.

## Utilisation

Ouvrir le fichier **`gabarit-tranchees-impetrants.html`** (à la racine du dépôt)
dans un navigateur. Aucune installation, aucun serveur, aucune connexion
internet : tout est embarqué dans un seul fichier (y compris la bibliothèque
ExcelJS).

### Fonctions

- **Impétrants (concessionnaires)** — ajouter / renommer / supprimer des
  impétrants et leurs sous-réseaux (DP, DD, E, Éclairage…), et choisir leur
  catégorie *Câbles* ou *Conduites*. La structure des colonnes et les formules
  du gabarit s'adaptent automatiquement.
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
- **Générer Excel** — produit un `.xlsx` reproduisant l'onglet gabarit avec ses
  en-têtes, ses cellules de saisie (jaunes) et **toutes les formules** :
  largeurs (AC, AR, AS, AT…), volumes câbles/conduites (AV…BT), et la
  répartition par impétrant (clé = part du volume total).

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
│   ├── ui.js               # interface (navigateur)
│   ├── styles.css
│   └── index.template.html
├── vendor/exceljs.min.js   # ExcelJS 4.4.0 (embarqué)
└── build.py                # produit gabarit-tranchees-impetrants.html à la racine
```

Reconstruire le fichier autonome après modification des sources :

```bash
python3 app/build.py
```

### Tests

Le cœur métier (`model.js`) fonctionne aussi sous Node. Le moteur de calcul a
été validé contre les valeurs réelles du classeur de référence (lignes 5 et 30)
et les formules générées contre les positions de colonnes de l'original.
