// Gemeinsamer Kern fuer Android und Windows: Protokoll (Modbus-HID), Logik, Programmspeicher, Systemwerte.
// Reines Kotlin/JVM ohne Android – laeuft unveraendert in der Android-App und in der Desktop-App.
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    jacoco
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Testdaten (echte Programme und Systemwerte vom DX8) liegen bei den Assets der Android-App
    systemProperty("dx8.assets", rootProject.file("app/src/main/assets").absolutePath)
    finalizedBy(tasks.jacocoTestReport)
}
