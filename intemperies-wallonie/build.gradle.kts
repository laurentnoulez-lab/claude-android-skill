plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Génère les deux applications en une commande :
//   ./gradlew buildAll
//   → app/build/outputs/apk/debug/app-debug.apk  (Android)
//   → desktop/build/distributions/IntemperiesWallonie-Windows.zip  (Windows : IntemperiesWallonie.exe + Java intégré)
tasks.register("buildAll") {
    group = "distribution"
    dependsOn(":core:test", ":app:assembleDebug", ":desktop:windowsZip")
}
