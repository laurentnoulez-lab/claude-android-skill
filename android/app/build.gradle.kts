plugins {
    id("com.android.application")
}

android {
    namespace = "com.tranchees.impetrants"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tranchees.impetrants"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
}

// Copie le fichier HTML autonome (racine du dépôt) dans les assets avant build,
// pour garder une source unique : gabarit-tranchees-impetrants.html -> assets/index.html
val copyWebApp = tasks.register<Copy>("copyWebApp") {
    from(rootProject.file("../gabarit-tranchees-impetrants.html"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "index.html" }
}
tasks.named("preBuild") { dependsOn(copyWebApp) }

// Les sources Java contiennent des libellés accentués (« Enregistré dans
// Téléchargements »). Sans encodage explicite, javac utiliserait l'encodage
// par défaut de la plateforme de compilation.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
