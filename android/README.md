# Bassin Dispersion — Application Android

Application Android pour le **Simulateur de Bassin de Dispersion / Rétention**
(données IRM, formule de Montana), porté depuis l'application HTML
`V6_bassin_dispersion.html`.

L'application embarque l'interface web complète (calculs, simulation,
matrice de protection, génération de rapport) dans un hôte natif `WebView`,
avec une interface **adaptée aux smartphones**.

## Adaptations mobiles

L'interface d'origine était conçue pour un écran large (panneau latéral fixe de
360 px + zone de contenu). La version mobile (`app/src/main/assets/index.html`)
ajoute une couche responsive :

- **Panneau de paramètres en tiroir** : ouvert via un bouton flottant
  « ☰ Paramètres », fermé par un appui sur l'arrière-plan, la croix, ou le
  bouton retour. Il se referme automatiquement après un calcul pour révéler
  les résultats.
- **Onglets défilables horizontalement** avec des cibles tactiles agrandies.
- **Formulaires en pleine largeur**, libellés au-dessus des champs, champs de
  saisie agrandis (15 px) pour le tactile.
- **Cartes de résultats sur une seule colonne** et **tableaux à défilement
  horizontal**.
- **Onglet Simulation empilé verticalement** (contrôles puis graphique).
- Prise en compte des encoches/zones sûres (`env(safe-area-inset-*)`).

Le pont natif (`MainActivity.kt`) fournit ce qui manque à un `WebView` nu :

| Fonction web | Pont natif |
|---|---|
| Export JSON / Word (`blob:`) | Interception + enregistrement dans **Téléchargements** (MediaStore) |
| Génération PDF (`window.open` + `window.print`) | Multi-fenêtres + **PrintManager** Android |
| Import d'étude / téléversement de logo (`<input type="file">`) | Sélecteur de fichiers natif |
| Bouton retour | Ferme le tiroir → historique WebView → quitte |

`Chart.js` est **embarqué localement** (`assets/chart.umd.min.js`) : les
graphiques fonctionnent hors-ligne.

## Construction (build)

### Sur GitHub (recommandé)

Le workflow [`.github/workflows/android-build.yml`](../.github/workflows/android-build.yml)
construit l'APK de debug à chaque push et le publie en **artefact**
(`bassin-dispersion-debug-apk`). Lancement manuel possible via
*Actions → Build Android APK → Run workflow*.

Pour récupérer l'APK : onglet **Actions**, ouvrir le dernier run réussi,
section **Artifacts**, télécharger `bassin-dispersion-debug-apk`.

### En local

Prérequis : JDK 17 et le SDK Android (platform 34).

```bash
cd android
./gradlew :app:assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Installer sur un appareil branché :

```bash
./gradlew :app:installDebug
```

## Caractéristiques techniques

- `applicationId` : `com.sml.bassindispersion`
- `minSdk` 26 (Android 8.0), `targetSdk` / `compileSdk` 34
- Kotlin, AndroidX, `androidx.webkit`, Material 3
