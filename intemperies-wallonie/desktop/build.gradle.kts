import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.Certificate
import java.net.URI

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Application Windows (Compose Desktop) : même cœur (core) et mêmes écrans (shared-ui) que l'application Android.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../shared-ui/src/main/kotlin")
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val composeVersion = "1.9.3"
val skikoVersion = "0.9.22.2"
val windowsRuntime: Configuration by configurations.creating

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.compose.desktop:desktop:$composeVersion")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.kotlinxCoroutines.get()}")
    implementation("org.apache.pdfbox:pdfbox:3.0.6")
    // Moteur graphique natif : Linux pour exécuter/tester ici, Windows pour le paquet .exe.
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:$skikoVersion")
    windowsRuntime("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:$composeVersion")
}

tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) exclude("**/*Screenshot*")
    providers.environmentVariable("SCREENSHOT_OUT").orNull?.let { environment("SCREENSHOT_OUT", it) }
}

// Archive exécutable Windows (application + dépendances + moteur graphique Windows).
tasks.register<Jar>("windowsJar") {
    group = "distribution"
    archiveFileName.set("IntemperiesWallonie.jar")
    manifest { attributes["Main-Class"] = "be.wallonie.intemperies.desktop.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    val cp = configurations.runtimeClasspath.get().filterNot { it.name.contains("skiko-awt-runtime-linux") } + windowsRuntime
    dependsOn(configurations.runtimeClasspath, windowsRuntime)
    from({ cp.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    }
}

// ------------------------------------------------------------------------------------------------
// Paquet Windows : IntemperiesWallonie.exe (Launch4j) + environnement Java intégré + magasin de certificats.
//   ./gradlew :desktop:windowsZip   →   desktop/build/distributions/IntemperiesWallonie-Windows.zip
// Sources épinglées par empreinte SHA-256 (intégrité vérifiée au téléchargement).
// ------------------------------------------------------------------------------------------------
val runtimeWheelUrl = "https://files.pythonhosted.org/packages/ca/14/4baef08289d39b90bf31229b194b517c46e4b50dc5037ce54fa23db5fbb6/jdk4py-25.0.5.1-py3-none-win_amd64.whl"
val runtimeWheelSha = "faeb2e188e003a67c0b91a17a00f95ae0cf0e2f0803892d1e44c410eca0a08db"
val certifiWheelUrl = "https://files.pythonhosted.org/packages/6d/39/5eec3296860b94ac20bb8ed807725b88f6adfab3f343df592afd975f5f86/certifi-2026.11.20-py3-none-any.whl"
val certifiWheelSha = "7422df25243767b9877031a2248cb14d09ddac9beee846f4cef3d5c9191d488f"

val launch4j: Configuration by configurations.creating { isTransitive = false }
dependencies {
    launch4j("net.sf.launch4j:launch4j:3.50:core")
    launch4j("net.sf.launch4j:launch4j:3.50:workdir-linux")
    launch4j("com.thoughtworks.xstream:xstream:1.4.20")
    launch4j("io.github.x-stream:mxparser:1.2.2")
    launch4j("xmlpull:xmlpull:1.1.3.1")
    launch4j("commons-beanutils:commons-beanutils:1.9.4")
    launch4j("commons-logging:commons-logging:1.2")
    launch4j("commons-collections:commons-collections:3.2.2")
}

fun downloadVerified(url: String, sha: String, dest: File) {
    if (!dest.exists()) {
        dest.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).joinToString("") { "%02x".format(it) }
    if (digest != sha) { dest.delete(); throw GradleException("Empreinte SHA-256 inattendue pour $url : $digest") }
}

val winDir = layout.buildDirectory.dir("windows/IntemperiesWallonie")

val windowsRuntimeImage by tasks.registering {
    group = "distribution"
    val out = winDir.map { it.dir("runtime") }
    outputs.dir(out)
    doLast {
        val wheel = layout.buildDirectory.file("cache/jdk4py-win.whl").get().asFile
        downloadVerified(runtimeWheelUrl, runtimeWheelSha, wheel)
        val target = out.get().asFile
        target.deleteRecursively()
        project.copy {
            from(project.zipTree(wheel)) { include("jdk4py/java-runtime/**") }
            into(target)
            eachFile { path = path.removePrefix("jdk4py/java-runtime/") }
            includeEmptyDirs = false
        }
        // Magasin de certificats (autorités Mozilla via certifi) : l'environnement Java fourni n'en contient pas.
        val certifi = layout.buildDirectory.file("cache/certifi.whl").get().asFile
        downloadVerified(certifiWheelUrl, certifiWheelSha, certifi)
        val pem = project.zipTree(certifi).matching { include("certifi/cacert.pem") }.singleFile
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val certs = CertificateFactory.getInstance("X.509").generateCertificates(pem.inputStream())
        certs.forEachIndexed { i: Int, c: Certificate -> ks.setCertificateEntry("mozilla-$i", c) }
        File(target, "lib/security").mkdirs()
        File(target, "lib/security/cacerts").outputStream().use { ks.store(it, "changeit".toCharArray()) }
        logger.lifecycle("Environnement Java Windows prêt (${certs.size} autorités de certification).")
    }
}

val windowsExe by tasks.registering(JavaExec::class) {
    group = "distribution"
    val jar = tasks.named<Jar>("windowsJar")
    dependsOn(jar)
    val l4jDir = layout.buildDirectory.dir("launch4j")
    val exe = winDir.map { it.file("IntemperiesWallonie.exe") }
    outputs.file(exe)
    doFirst {
        val dir = l4jDir.get().asFile
        if (!File(dir, "bin").exists()) {
            launch4j.files.forEach { f ->
                when {
                    f.name.contains("workdir") -> project.copy {
                        from(project.zipTree(f)) { include("launch4j-3.50-workdir-linux/**") }
                        into(dir)
                        eachFile { path = path.removePrefix("launch4j-3.50-workdir-linux/") }
                        includeEmptyDirs = false
                    }
                    // Launch4j cherche bin/, head/ et w32api/ à côté de son propre jar.
                    f.name.startsWith("launch4j") -> f.copyTo(File(dir, "launch4j.jar"), true)
                    else -> f.copyTo(File(dir, "lib/${f.name}"), true)
                }
            }
            // Les outils Linux de Launch4j sont en 32 bits : on utilise binutils-mingw-w64-i686 s'il est installé
            // (apt install binutils-mingw-w64-i686), sinon les outils fournis.
            for (tool in listOf("windres", "ld")) {
                val sys = File("/usr/bin/i686-w64-mingw32-$tool")
                val target = File(dir, "bin/$tool")
                if (sys.exists()) { target.delete(); Files.createSymbolicLink(target.toPath(), sys.toPath()) }
            }
            File(dir, "bin").listFiles()?.forEach { it.setExecutable(true) }
        }
        val cfg = File(dir, "config.xml")
        cfg.writeText(
            """
            <launch4jConfig>
              <dontWrapJar>false</dontWrapJar>
              <headerType>gui</headerType>
              <jar>${jar.get().archiveFile.get().asFile.absolutePath}</jar>
              <outfile>${exe.get().asFile.absolutePath}</outfile>
              <errTitle>Intemperies Wallonie</errTitle>
              <chdir>.</chdir>
              <priority>normal</priority>
              <stayAlive>false</stayAlive>
              <restartOnCrash>false</restartOnCrash>
              <icon>${project.file("packaging/icon.ico").absolutePath}</icon>
              <singleInstance><mutexName>IntemperiesWallonie</mutexName><windowTitle>Intempéries Wallonie</windowTitle></singleInstance>
              <jre>
                <path>runtime</path>
                <requiresJdk>false</requiresJdk>
                <requires64Bit>true</requires64Bit>
                <minVersion>17</minVersion>
                <maxHeapPercent>50</maxHeapPercent>
                <opt>-Dfile.encoding=UTF-8</opt>
              </jre>
              <versionInfo>
                <fileVersion>1.0.0.0</fileVersion>
                <txtFileVersion>1.0.0</txtFileVersion>
                <fileDescription>Intemperies Wallonie</fileDescription>
                <copyright>Sources des donnees : SPW</copyright>
                <productVersion>1.0.0.0</productVersion>
                <txtProductVersion>1.0.0</txtProductVersion>
                <productName>Intemperies Wallonie</productName>
                <internalName>IntemperiesWallonie</internalName>
                <originalFilename>IntemperiesWallonie.exe</originalFilename>
              </versionInfo>
            </launch4jConfig>
            """.trimIndent(),
        )
        classpath = files(File(dir, "launch4j.jar")) + fileTree(File(dir, "lib"))
        args(cfg.absolutePath)
        exe.get().asFile.parentFile.mkdirs()
    }
    mainClass.set("net.sf.launch4j.Main")
}

tasks.register<Zip>("windowsZip") {
    group = "distribution"
    dependsOn(windowsRuntimeImage, windowsExe)
    archiveFileName.set("IntemperiesWallonie-Windows.zip")
    from(winDir) { into("IntemperiesWallonie") }
    from(project.file("packaging/LISEZMOI.txt")) { into("IntemperiesWallonie") }
    from(project.file("src/main/resources/fonts/DejaVu-LICENSE.txt")) { into("IntemperiesWallonie/licences") }
}
