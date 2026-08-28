plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin: the slideshow engine has no Android dependency at all, which is what makes the
// composition, movement and transition logic testable on the JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
