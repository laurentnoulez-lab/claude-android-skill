# Simulateur de Bassin de Dispersion / Rétention — Guide pour Claude Code

> Document de référence pour comprendre et faire évoluer cette application.
> Tout est en français (interface, commentaires, ce guide).

## 1. Vue d'ensemble

Application **Android pour smartphone** qui dimensionne des bassins de
dispersion / rétention d'eaux pluviales (données IRM belges, formule de
Montana). C'est le portage d'une application web mono-page (`index.html`)
vers une application Android native qui héberge cette page web dans une
`WebView`, avec une interface adaptée au tactile et des ponts natifs pour
les fonctions qu'un WebView ne sait pas faire seul (impression PDF,
enregistrement de fichiers, sélecteur de fichiers).

**Toute la logique métier (calculs, scénarios, simulation, rapport) vit dans
`app/src/main/assets/index.html`** (HTML + CSS + JavaScript, un seul fichier).
Le code Kotlin (`MainActivity.kt`) ne fait QUE héberger et ponter ce WebView.

➡️ **Pour modifier le comportement / les calculs / l'interface : éditer
`index.html`.** Pour modifier le comportement natif (impression, fichiers) :
éditer `MainActivity.kt`.

## 2. Structure du projet

```
.
├── CLAUDE.md                       ← ce fichier
├── README.md                       ← présentation courte
├── settings.gradle.kts             ← module unique :app
├── build.gradle.kts                ← plugins AGP/Kotlin (racine)
├── gradle.properties
├── gradlew / gradlew.bat           ← wrapper Gradle (8.7)
├── gradle/wrapper/                 ← gradle-wrapper.jar + .properties
├── .github/workflows/
│   └── android-build.yml           ← CI : build l'APK debug, artefact
└── app/
    ├── build.gradle.kts            ← config app (minSdk 26, target 34)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/sml/bassindispersion/
        │   └── MainActivity.kt     ← hôte WebView + ponts natifs
        ├── assets/
        │   ├── index.html          ← ★ APPLICATION COMPLÈTE (calculs + UI)
        │   ├── montana.js          ← coefficients Montana GTI (562 communes)
        │   └── chart.umd.min.js    ← Chart.js 4.4.0 (embarqué, hors-ligne)
        └── res/
            ├── values/ (strings, colors, themes)  + values-night/
            ├── drawable/ (icône adaptative : fond + premier plan)
            └── mipmap-anydpi-v26/ (ic_launcher / ic_launcher_round)
```

- `applicationId` : `com.sml.bassindispersion`
- `minSdk` 26 (Android 8.0), `compileSdk`/`targetSdk` 34, Kotlin + AndroidX + Material 3.

## 3. Construire l'application

### En local
Prérequis : **JDK 17** et le **SDK Android** (platform 34, build-tools).
Créer `local.properties` avec `sdk.dir=/chemin/vers/Android/Sdk` (ou définir
`ANDROID_HOME`).

```bash
./gradlew :app:assembleDebug      # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug       # installer sur un appareil branché
```

### Sur GitHub
Le workflow `.github/workflows/android-build.yml` compile l'APK debug à chaque
push et le publie comme artefact `bassin-dispersion-debug-apk`
(Actions → dernier run → Artifacts). Déclenchable aussi manuellement.

### Tester rapidement les calculs sans Android
`index.html` s'ouvre directement dans un navigateur de bureau (le code JS y
fonctionne ; seuls les ponts natifs Android sont inactifs, avec repli
navigateur). Pratique pour itérer vite sur la logique.

⚠️ Le JavaScript d'`index.html` peut être vérifié syntaxiquement en extrayant
le bloc `<script>` principal et en lançant `node --check`.

## 4. L'application web (`index.html`) — organisation

Un seul fichier. Ordre : `<style>` (thème + couche responsive mobile) →
`<body>` (sidebar paramètres + onglets) → `<script>` principal (données +
logique) → petit `<script>` du tiroir mobile.

### Mise en page
- **Sidebar (panneau gauche)** : commune, période de retour, tableau des
  surfaces drainées (Cr × m²), perméabilité (k, coefficient de sécurité,
  vidange max), case **« Pluies à sonder comme tableur GTI »**, bouton
  « Calculer ».
- **Onglets** : `s0` Temporisation, `s1` Dispersion pure, `s2` Disp.+Ajutage,
  `s3` Surprofondeur, `pluies` (tableau des pluies), `sim` Simulation,
  `prot` Matrice de protection, `report` Reporting.
- **Adaptation mobile** : un bloc `<style id="mobile-adapt">` (media query
  `max-width:860px`) transforme la sidebar en **tiroir coulissant** (bouton
  flottant « ☰ Paramètres »), rend les onglets défilables, met les formulaires
  en pleine largeur, etc. Le tiroir est câblé par l'IIFE `setupMobileDrawer`.
  ⚠️ `showTab(id)` repose sur un tableau `ids` dont l'ORDRE doit correspondre
  exactement à l'ordre des `.tab` dans le DOM.

### Données
- `COMMUNES_DATA` : table IRM (hauteurs de pluie en mm) par commune × 19 durées
  × 12 périodes de retour. `DUR` = 19 durées (min), `DUR_LBL` = libellés,
  `RP` = [2,5,10,15,20,25,30,40,50,75,100,200].
- `MONTANA_DATA` (fichier `montana.js`) : coefficients de Montana du **tableur
  GTI** pour 562 communes × 12 périodes : `[a1,b1,a2,b2,a3,b3]`. Cohérents à
  ~1-2 % avec `COMMUNES_DATA`.
- `surfaceRows` : surfaces saisies. `state` : résultats `{s0,s1,s2,s3,simRuns}`.

### Accès à la pluie (point central)
- `rainMm(dur_min, rp)` : hauteur de pluie (mm). En **mode GTI** → Montana
  (`montanaDepthMm`) ; sinon → table IRM (avec repli interpolation log-log si
  durée non tabulée).
- `rainIntensity_Lsha`, `rainFlow_Ls` en dépendent → tout devient
  automatiquement « GTI » quand la case est cochée.

## 5. Le mode GTI (sondage continu des pluies)

Reproduit la méthode du tableur Excel GTI fourni par le client.

- **Formule de Montana** : `intensité[mm/h] = a · durée[min]^(−b)`, par
  segments : `<25 min` (a1,b1), `25–6000 min` (a2,b2), `>6000 min` (a3,b3).
  Hauteur `Q[mm] = i · durée/60`. → fonction `montanaDepthMm(coeffs, dur)`.
- **Balayage** : `getProbeDurations()` renvoie, en mode GTI, **10 → 86400 min
  par pas de 5 min** (≈ 17 280 points, identique au tableur), sinon les 19
  durées IRM.
- `gtiEnabled()` = case cochée ET commune présente dans `MONTANA_DATA`
  (sinon repli sur la table IRM, avec note). `montanaCoeffs` gère quelques
  alias d'apostrophe (`MONTANA_ALIAS`).
- **Affichage** : comme le balayage produit des milliers de lignes,
  `gtiDisplaySubset(rows, critIdx)` n'affiche qu'un sous-ensemble lisible
  (~30 lignes), **incluant toujours la durée critique**. Le dimensionnement
  (max) utilise lui le balayage complet.
- La case est persistée dans l'export/import d'étude (`gti`).

Vérifié : intensité, hauteur, Vin/Vout/Vol et la grille correspondent
exactement au tableur GTI.

## 6. Les scénarios de dimensionnement

Chaque `calcSx()` renvoie `{rows, critIdx, ...}` ; chaque `renderSx()` produit
le HTML. `calculateAll()` lance les 4 et leurs rendus. Les boucles parcourent
`getProbeDurations()` (donc fines en mode GTI) ; libellé via `durLabel(d)`.

- **Sc. 0 — `calcS0` / `renderS0`** : temporisation pure (bassin étanche, pas
  d'infiltration, vidange par ajutage seul). = Sc. 2 avec surface de
  dispersion nulle. Entrée : débit d'ajutage `s0-qaj` [L/(s·ha)].
- **Sc. 1 — `calcS1`** : dispersion 100 % (infiltration seule, sans ajutage).
  Calcule `S_min`, volume tampon.
- **Sc. 2 — `calcS2`** : dispersion + ajutage. Modes `dispersion_only`,
  `orifice_only`, `normal`.
- **Sc. 3 — `calcS3`** : dispersion + ajutage + **surprofondeur** (volume sous
  le niveau de l'ajutage). Phases : remplissage surprofondeur → tampon →
  vidange tampon (infil+ajutage) → vidange surprofondeur (infil seule).

Conventions des débits : `Q_infil = k · S_disp` ; `Q_ajutage = taux[L/(s·ha)]
× surface TOTALE [ha]` (non pondérée). Volume d'eau `V = Q · durée`.

## 7. Statut OK / !OK / NOK (sc. 3 et simulation)

**Source unique** : `eventStatus(cfg, d1)` — utilisée À LA FOIS par le
scénario 3 et la simulation, pour qu'ils donnent toujours la même conclusion
à paramètres égaux. `cfg = {scen, Vsurp, Vtotal, Qaj_Ls, Qinfil_Ls, rp}`.

- **OK** : vidange complète en ≤ t_max (décision **analytique**, `analyticDrain`,
  indépendante du pas de temps, cohérente avec les colonnes de temps de
  vidange affichées).
- **!OK** (jaune) : non vidangé dans t_max, MAIS le volume encore disponible
  (`Vtotal − résiduel à t_max`, via `analyticResidual`) permet d'absorber un
  **nouvel épisode** de même période de retour, quelle que soit sa durée, sans
  déborder. C'est une **re-vérification dynamique** : on resimule un épisode
  frais (`simulateEventGeneric`, toutes durées) en partant du résiduel.
- **NOK** : le 1ᵉʳ épisode déborde déjà, OU le nouvel épisode déborderait.

Conséquence attendue : pour un bassin dimensionné au plus juste, !OK est rare
(on passe vite de OK à NOK) ; !OK apparaît surtout quand il existe une marge.

⚠️ Piège historique résolu : la simulation utilisait une période de retour
indépendante de celle du dimensionnement (sidebar) → divergence de conclusion.
Désormais `sim-rp` **suit** la période de retour de la sidebar (à l'ouverture,
au changement, et au préremplissage). Voir `populateSimRP`, l'écouteur sur
`inp-rp`, et `prefillFromScenario`.

## 8. Onglet Simulation

Modèle dynamique pas-à-pas. `getSimParamsBase()` lit les contrôles
(`sim-scen` 0/1/2/3, surface, volumes, ajutage, période). `computeSimData(p)`
intègre le volume `V(t)` (entrée pluie, sorties infiltration + ajutage si
`V > Vsurp`, débordement si `V > Vtotal`). `runSimulation()` trace les courbes
(Chart.js) et `showSimStats()` affiche le tableau (V max, %, temps de vidange
tampon / surprofondeur / total, statut via `eventStatus`).

- **Débit d'ajutage en deux unités** : champs `sim-qaj-ha` (L/(s·ha)) et
  `sim-qaj` (L/s) synchronisés (`syncSimQajFromHa` / `syncSimQajFromLs`,
  facteur = surface totale en ha). `sim-qaj` (L/s) reste la source de vérité.
- `getSimDt(dur)` : pas de temps adaptatif.

## 9. Onglet Pluies, Matrice, Reporting

- **Pluies** : `buildRainTable()` — tableau hauteurs (mm) / débits spécifiques
  L/(s·ha) par durée × période, basculé par `setPluiesMode('mm'|'q')`.
- **Matrice de protection** : `buildProtectionMatrix()` — débordement (oui/non)
  pour chaque durée × période, pour un bassin choisi (`prot-scen`).
- **Reporting** : `buildReportHTML()` assemble le rapport (page de garde,
  synthèse, hypothèses, pluies, surfaces, perméabilité, sc. 0-3, simulation,
  matrice, conclusions, signature ; cases `sec-*`). `renderSimulationForReport`
  réexécute la simulation pour des données fraîches. Titre par défaut :
  « Étude de bassin de dispersion et/ou de temporisation ».
  - **PDF** : `generatePDF()` → en Android, appelle `AndroidReport.printHtml`
    (impression native → « Enregistrer au format PDF ») ; sinon `window.open`.
  - **Word `.docx` RÉEL** : `generateWord()` construit un OOXML valide
    (mini-ZIP `_zipStore` + CRC32 + WordprocessingML via `_walkDocx` à partir
    du DOM du rapport). Enregistré via `AndroidDownloader.saveDataUrl` (Android)
    ou blob (navigateur). NE PAS revenir au `.doc` HTML (corrompu sur Android).
  - **Import/Export JSON** : `captureStudyState` / `applyStudyState` (toutes les
    entrées, y compris la case GTI). Logo personnalisable (`localStorage`).

## 10. Le pont natif Android (`MainActivity.kt`)

`WebView` plein écran chargeant `file:///android_asset/index.html` (JS, DOM
storage, accès fichiers activés). Interfaces JS injectées :

- `AndroidDownloader.saveDataUrl(dataUrl, nom)` : décode un data URL base64 et
  enregistre dans **Téléchargements** (MediaStore API 29+, sinon dossier
  public). Sert aux exports JSON / Word.
- `AndroidReport.printHtml(html)` : charge le HTML du rapport dans un WebView
  caché puis lance le `PrintManager` (PDF).
- `onShowFileChooser` : sélecteur de fichiers natif (import d'étude, logo).
- `onPageFinished` injecte un pont JS qui intercepte les téléchargements
  `blob:`/`data:` (clic d'ancre) → `AndroidDownloader`.
- Bouton retour : ferme d'abord le tiroir, puis l'historique WebView, puis
  quitte.

`Chart.js` est embarqué localement (`chart.umd.min.js`) → graphiques
hors-ligne. Permission `INTERNET` présente (non requise pour le cœur).

## 11. Conventions et points d'attention pour les évolutions

1. **Modifier les calculs/UI = éditer `index.html`** (pas de build nécessaire
   pour tester dans un navigateur).
2. **Statuts OK/!OK/NOK : passer par `eventStatus`** (ne pas dupliquer la
   logique) pour garder sc. 3 et simulation cohérents.
3. **Accès pluie : passer par `rainMm` / `rainFlow_Ls`** (gèrent IRM vs GTI).
4. **Boucles de scénario : `getProbeDurations()` + `durLabel(d)`**, jamais
   `DUR`/`DUR_LBL` en dur (sinon le mode GTI ne s'applique pas). Exceptions
   légitimes restantes : interpolation IRM dans `rainMm`, et la boucle « second
   épisode » d'`eventStatus` qui utilise volontairement les 19 durées standard.
5. **`showTab` : garder l'ordre du tableau `ids` synchronisé avec le DOM** si
   on ajoute/retire un onglet.
6. **Mode GTI = beaucoup de durées** : ne calculer les statuts coûteux
   (`eventStatus`, qui resimule) que sur le sous-ensemble affiché
   (`gtiDisplaySubset`), comme le fait déjà `calcS3`.
7. **Word** : conserver la génération `.docx` OOXML (testée valide). Les images
   (logo, graphique) sont volontairement omises du `.docx` (présentes dans le
   PDF).
8. Après modification, vérifier la syntaxe JS (`node --check` sur le bloc
   script) puis, si possible, builder l'APK (local ou CI).

## 12. Régénérer les coefficients Montana (`montana.js`)

Extraits du classeur GTI (feuille cachée « Montana ») : colonnes
`Name`, `Return period`, puis `a1,b1,a2,b2,a3,b3`. Structure du fichier :
`const MONTANA_DATA = { "Commune": { "25": [a1,b1,a2,b2,a3,b3], ... }, ... };`.
Si une nouvelle version du classeur arrive, ré-extraire la feuille et
régénérer ce fichier (562 communes × 12 périodes).
