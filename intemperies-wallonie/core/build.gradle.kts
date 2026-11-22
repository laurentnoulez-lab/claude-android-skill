plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Logique commune Android / Windows : modèle, règles, client SPW, synchronisation, exports.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    providers.environmentVariable("LIVE_SPW").orNull?.let { environment("LIVE_SPW", it) }
    providers.environmentVariable("XLSX_OUT").orNull?.let { environment("XLSX_OUT", it) }
}
