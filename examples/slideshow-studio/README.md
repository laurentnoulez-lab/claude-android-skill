# Diaporama Studio

Application Android qui transforme des photos importées en **vidéo diaporama animée** :
1920 × 1080, 30 fps, MP4 / H.264, avec mouvements Ken Burns, compositions variées et transitions
dynamiques.

Cette application sert aussi d'exemple complet pour la skill `android-development` de ce dépôt :
architecture en couches, module de logique métier pur Kotlin entièrement testé, UI Jetpack Compose
en MVVM avec flux de données unidirectionnel.

## Fonctionnalités

| Besoin | Où c'est implémenté |
|---|---|
| Import de photos (portrait / paysage / carré, résolutions mixtes) | `data/AndroidPhotoRepository.kt` (photo picker Android) |
| Durée d'affichage réglable de 2 à 7 s | `SlideshowSettings.sceneDurationSeconds`, curseur dans `ui/editor` |
| Modes 1 / 1-2 / 1-3 / 1-4 images par scène | `ImagesPerSceneMode`, `StoryboardBuilder.chooseCount` |
| Variation automatique du nombre d'images | `StoryboardBuilder` : deux scènes consécutives n'ont jamais le même nombre d'images quand c'est possible |
| Jamais deux fois la même photo dans une scène | `StoryboardBuilder` : distribution par paquet mélangé, sans répétition |
| Compositions 1 / 2 / 3 / 4 photos, dont asymétriques et décalées | `LayoutCatalog` (21 compositions) |
| Mouvement permanent (zoom, panoramique, diagonale, rotation légère) | `MotionKind`, `MotionFactory` |
| Transitions variées de 0,5 à 1 s | `TransitionKind` (15 transitions, 6 familles), `TransitionFactory` |
| Entrées / sorties de photos intégrées à la transition | transitions échelonnées (`stagger`) dans `TransitionSpec` |
| Recadrage intelligent, jamais de déformation | `SmartCrop` + détection de visages (`FaceFocusDetector`) |
| Aperçu avec lecture / pause / retour au début | `ui/preview/PreviewScreen.kt` |
| Export 1080p MP4 H.264 | `export/VideoExporter.kt` (MediaCodec + MediaMuxer + OpenGL ES) |

## Architecture

```
core/engine/          Kotlin pur, aucune dépendance Android — testable sur la JVM
  Model.kt            Photo, zone d'intérêt, réglages
  Layouts.kt          Catalogue des compositions (1 à 4 photos)
  Motion.kt           Mouvements Ken Burns
  Transitions.kt      Transitions entre scènes
  SmartCrop.kt        Recadrage « cover » sans déformation
  StoryboardBuilder   Plan complet de la vidéo (scènes, photos, compositions, mouvements)
  FrameComposer       Image par image : liste de quadrilatères à dessiner
  SourceResolution    Résolution de décodage nécessaire pour chaque photo

app/                  Application Android
  data/               Import, décodage, orientation EXIF, détection de visages
  render/             Rendu Compose (aperçu) et OpenGL ES 2.0 (export)
  export/             Encodage MediaCodec → MediaMuxer, publication dans la galerie
  ui/                 Écrans Compose, ViewModel, état d'UI
```

Le point clé : **l'aperçu et l'export consomment les mêmes `Frame` produites par le moteur.**
Le moteur ne manipule aucun pixel, seulement des rectangles normalisés ; ce que l'utilisateur voit
dans l'aperçu est donc exactement ce qui est encodé, à la résolution près.

### Pourquoi le moteur est pur Kotlin

Tout ce qui décide du rendu — nombre d'images, composition, mouvement, transition, recadrage — est
déterministe et sans dépendance Android. Cela permet de vérifier par des tests JVM que :

* aucune photo n'est déformée (le rapport source/destination est identique à 0,2 % près) ;
* aucun recadrage ne sort de la photo ;
* aucune photo n'apparaît ni ne disparaît brutalement (contrôle image par image) ;
* aucune photo n'est figée ;
* les règles de variété (compositions, transitions, mouvements, nombres d'images) sont respectées.

```bash
./gradlew :core:engine:test     # 48 tests
```

## Compilation

```bash
./gradlew :app:assembleDebug
```

Prérequis : JDK 17, Android SDK 35, accès aux dépôts `google()` et `mavenCentral()`.

## Choix techniques notables

**Fondu enchaîné sans creux de luminosité.** Pendant une transition, la scène sortante reste
totalement opaque et la scène entrante est composée par-dessus. Un fondu classique
(sortante à `1-t`, entrante à `t`) fait chuter la luminosité de 25 % à mi-parcours ; ici la somme
reste à 1. La scène sortante ne s'efface que sur la fin de la transition, une fois la nouvelle
scène quasi opaque.

**Courbes séparées pour le mouvement et l'opacité.** Les déplacements utilisent une sinusoïde
(vitesse de pointe 1,6 × la moyenne) ; les fondus utilisent une courbe plate aux deux extrémités,
ce qui évite l'apparition brutale d'une photo sur la première image d'une transition rapide.

**Mouvement continu pendant les transitions.** Le mouvement Ken Burns d'une scène est extrapolé
au-delà de sa durée nominale pendant que la transition suivante se joue : aucune photo ne se fige
sous la scène entrante.

**Rotation sans bord vide.** Une photo légèrement inclinée est agrandie du strict nécessaire
(`rotationCoverScale`) puis découpée à son emplacement, de sorte qu'aucun coin de fond n'apparaît.

**Résolution de décodage calculée.** `SourceResolution` détermine, pour chaque photo, la largeur
réellement nécessaire d'après la taille de son emplacement et son zoom maximal. Une photo qui
occupe un quart de l'écran n'est pas décodée en pleine résolution : moins de mémoire, et surtout
moins de scintillement dû à une réduction trop brutale.

**Attribution des photos aux emplacements.** Pour chaque scène, les permutations possibles
(4 photos au maximum) sont évaluées et celle qui minimise l'écart entre le format de la photo et
celui de l'emplacement est retenue : les portraits vont dans les emplacements hauts, les paysages
dans les larges, ce qui réduit le recadrage.

**Détection de visages sans dépendance.** `android.media.FaceDetector`, présent dans la plateforme,
tourne sur une vignette et fournit la zone à préserver au recadrage. Le remplacement par ML Kit ne
demanderait que de renvoyer une autre `FocusArea`.

## Écarts assumés par rapport à la skill

* **Pas de Hilt** : l'application n'a ni base de données, ni réseau, ni graphe de dépendances
  profond. `AppContainer` (injection manuelle) suffit et évite du code généré inutile.
* **Pas de navigation multi-modules** : deux écrans seulement, permutés par `AnimatedContent`.

Le reste suit la skill : séparation UI / données, `ViewModel` + `UiState` + actions, flux
unidirectionnel, logique métier isolée et testée.
