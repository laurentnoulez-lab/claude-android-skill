# Diaporama — Générateur de vidéo diaporama Android

Application Android qui transforme une série de photos en une **vidéo diaporama
Full HD (1920×1080)** fluide et agréable à regarder, avec **plusieurs photos
affichées en même temps** (collages) et des **transitions en fondu** enchaînées.

## Fonctionnalités

- 🎬 **Export vidéo 1080p** (H.264/MP4, 30 fps, ~16 Mbps) encodé en natif avec
  `MediaCodec` + OpenGL — aucune bibliothèque externe, aucun serveur.
- 🖼️ **Plusieurs images par écran** : mise en page en collage de 1 à 6 photos
  simultanées, avec des dispositions soignées pour chaque nombre.
- ✨ **Rendu fluide** : effet **Ken Burns** (zoom/panoramique lent) sur chaque
  photo et **fondu enchaîné** (crossfade) doux entre les écrans.
- 🎨 Fond flouté généré à partir des photos, coins arrondis, marges élégantes.
- ⚙️ Réglages : nombre de photos par écran, durée par écran, durée de transition.
- 💾 Enregistrement direct dans la **galerie** (`Movies/Diaporama`) + partage.

## Comment obtenir l'APK

Le workflow GitHub Actions [`build-diaporama-apk.yml`](../.github/workflows/build-diaporama-apk.yml)
compile l'APK automatiquement à chaque push.

**Option 1 — Release :** ouvrez l'onglet *Releases* du dépôt, prenez la release
`diaporama-latest` et téléchargez `app-debug.apk`.

**Option 2 — Artefact :** onglet *Actions* → dernier run *Build Diaporama APK* →
section *Artifacts* → `diaporama-debug-apk`.

Installez ensuite l'APK sur le téléphone (autorisez « sources inconnues »).
C'est un APK de **debug**, installable sans compte développeur.

## Construire en local

```bash
cd DiaporamaApp
gradle wrapper --gradle-version 8.6   # génère ./gradlew (une seule fois)
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Prérequis : JDK 17 et le SDK Android (compileSdk 34).

## Architecture technique

| Élément | Rôle |
|--------|------|
| `MainActivity` + Compose | Interface : sélection de photos, réglages, progression |
| `SlideshowViewModel` | État de l'écran, lancement du rendu, sauvegarde |
| `render/Layouts` | Rectangles de collage pour 1 à 6 photos |
| `render/FrameComposer` | Dessine chaque image (Ken Burns, coins arrondis, fond flou) |
| `render/SlideshowBuilder` | Timeline, fondus, boucle d'encodage image par image |
| `video/VideoEncoder` | Encodeur H.264 + `MediaMuxer` (MP4) |
| `video/InputSurface` | Contexte EGL lié à la surface d'entrée du codec |
| `video/BitmapTextureRenderer` | Envoie chaque frame composée vers le codec via OpenGL |
| `MediaStoreSaver` | Publie la vidéo dans la galerie |

Le rendu se fait **hors temps réel** : chaque frame est composée sur un `Bitmap`
(via `Canvas`) puis poussée dans l'encodeur par une texture OpenGL plein écran.
Cela permet des compositions riches tout en gardant une vidéo parfaitement fluide
à la lecture.

- **minSdk** : 29 (Android 10) · **targetSdk / compileSdk** : 34
- Aucune permission d'exécution requise (photo picker + MediaStore scoped storage).
