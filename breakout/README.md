# Casse-Brique 🧱

Un jeu de casse-brique complet pour Android, écrit en **Kotlin** avec **Jetpack Compose**.

## Fonctionnalités

- **8 niveaux** aux dispositions variées (mur, damier, pyramide, diamant, forteresse…)
- **Briques multi-résistance** (1 à 3 coups, couleur selon les points de vie restants)
- **Bonus** qui tombent des briques détruites :
  - ↔ **Élargissement** de la raquette (temporaire)
  - **+** **Vie supplémentaire**
  - **3** **Multi-balles** (jusqu'à 6 balles simultanées)
  - **S** **Ralenti** (temporaire)
  - ✳ **Laser** : les balles tirent des salves de lasers tout autour d'elles (temporaire)
  - ◎ **Grosse balle** : les balles grossissent (temporaire)
- **Malus** (signalés par un anneau sombre, à éviter !) :
  - » **Accélération** des balles (temporaire)
  - →← **Rétrécissement** de la raquette (temporaire)
- **Rebond directionnel** : l'angle de renvoi dépend du point d'impact sur la raquette
- **Score, vies, niveaux** avec bonus de fin de niveau
- **Sauvegarde automatique** : fermez l'application et reprenez la partie exactement
  où vous l'avez laissée (« Reprendre la partie » dans le menu)
- **Meilleur score persistant** (SharedPreferences)
- **Pause** (bouton ou passage en arrière-plan)
- **Effets sonores** générés sans assets (ToneGenerator)
- Physique avec **sous-échantillonnage** anti-tunneling (la balle ne traverse jamais une brique)

## Architecture

```
app/src/main/java/com/example/breakout/
├── game/                  # Moteur de jeu en Kotlin pur (aucune dépendance Android)
│   ├── GameEngine.kt      # Physique, collisions, score, bonus/malus, niveaux
│   ├── GameModels.kt      # Ball, Brick, PowerUp, Laser, GameStatus, GameEvents
│   ├── GameSnapshot.kt    # État sérialisable (kotlinx.serialization) pour la reprise
│   └── Levels.kt          # Dispositions des 8 niveaux
├── ui/                    # Interface Jetpack Compose
│   ├── BreakoutApp.kt     # Menu principal + navigation
│   ├── GameScreen.kt      # Canvas, boucle de rendu, HUD, overlays
│   └── Theme.kt           # Couleurs et thème
├── sound/
│   └── SoundEffects.kt    # Sons via ToneGenerator
├── HighScoreStore.kt      # Persistance du meilleur score
├── GameSaveStore.kt       # Persistance de la partie en cours (reprise)
└── MainActivity.kt
```

Le moteur (`game/`) est découplé de l'UI : il est testé par **29 tests unitaires JUnit**
(`app/src/test/`) qui couvrent rebonds, collisions, vies, niveaux, bonus/malus,
pause et sauvegarde/restauration (y compris le changement de taille d'écran).

## Contrôles

- **Glisser** le doigt : déplacer la raquette
- **Toucher** l'écran : lancer la balle
- **❚❚** : pause

## Compiler et lancer

```bash
cd breakout
./gradlew assembleDebug        # APK dans app/build/outputs/apk/debug/
./gradlew test                 # Tests unitaires du moteur
./gradlew installDebug         # Installation sur un appareil connecté
```

Prérequis : JDK 17+, Android SDK (compileSdk 35). Min SDK : Android 8.0 (API 26).
