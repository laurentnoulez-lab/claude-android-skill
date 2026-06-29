# Application Android (APK) — Gabarit tranchées impétrants

Coque **WebView** native qui embarque l'application web autonome
(`gabarit-tranchees-impetrants.html`) : l'app fonctionne **100 % hors-ligne**, et
les exports (Excel, Word, JSON) sont enregistrés dans le dossier
**Téléchargements** du téléphone via un pont natif.

## Récupérer l'APK sans rien installer (recommandé)

Un workflow GitHub Actions compile l'APK à chaque push :
`.github/workflows/android-apk.yml`.

1. Onglet **Actions** du dépôt → workflow **« Android APK »** → dernier run.
2. Section **Artifacts** → télécharger **`gabarit-tranchees-debug-apk`**.
3. Décompresser, transférer `app-debug.apk` sur le téléphone et l'installer
   (autoriser « sources inconnues »).

> APK *debug* signé avec la clé de debug : installable directement, idéal pour
> un usage interne. Pour une diffusion (Play Store / signature de release), voir
> plus bas.

## Compiler en local

Prérequis : **JDK 17+** et le **SDK Android** (API 34, build-tools 34). Le SDK
nécessite un accès réseau à Google (`dl.google.com`).

```bash
# 1) (re)générer le fichier web autonome
python3 app/build.py
# 2) compiler l'APK debug
cd android
./gradlew assembleDebug
# Résultat : android/app/build/outputs/apk/debug/app-debug.apk
```

L'application web est copiée automatiquement dans les assets avant compilation
(tâche `copyWebApp`), à partir de `gabarit-tranchees-impetrants.html` — source
unique.

## Caractéristiques

| Élément | Valeur |
|---|---|
| Package | `com.tranchees.impetrants` |
| Nom | Gabarit Tranchées |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| Dépendances | aucune (framework Android uniquement, sans AndroidX) |
| AGP / Gradle | 8.5.2 / 8.9 |

## Signature de release (optionnel)

Pour produire un APK de release signé, ajouter un keystore et un
`signingConfig` dans `app/build.gradle.kts`, puis `./gradlew assembleRelease`.
Le workflow CI ne produit que l'APK *debug* (aucun secret requis).
