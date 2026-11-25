// ChargeDeck fuer Windows (Compose for Desktop). Nutzt den gemeinsamen Kern :shared unveraendert.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

// Demo-Daten (echte Programme und Systemwerte vom DX8) aus den Assets der Android-App mitnehmen
sourceSets["main"].resources.srcDir(rootProject.file("app/src/main/assets"))

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.hid4java:hid4java:0.8.0")
    implementation("net.java.dev.jna:jna:5.19.1")   // hid4java verlangt nur ">= 5.8": Version festhalten
}

// Testversion mit Ablaufdatum (Trial.kt) – Vollversion: gradle -Ptrial=false :desktop:packageMsi
val trial = (project.findProperty("trial") ?: "true").toString()

compose.desktop {
    application {
        mainClass = "de.dx8.desktop.MainKt"
        jvmArgs += listOf("-Dchargedeck.trial=$trial")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            // Java-Module der mitgelieferten Laufzeit (Einstellungen, Firmware-Pruefung ueber HTTP)
            modules("java.prefs", "java.net.http", "jdk.crypto.ec", "java.instrument", "jdk.unsupported")
            packageName = "ChargeDeck"
            packageVersion = "1.0.0"
            description = "ChargeDeck für den Junsi iCharger DX8"
            vendor = "ChargeDeck"
            windows {
                menuGroup = "ChargeDeck"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f1c2b9e-4d7a-4c1e-9a52-3b8e0d6c7f21"
            }
        }
    }
}
