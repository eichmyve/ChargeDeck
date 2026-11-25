pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "DX8Monitor"
include(":shared")   // gemeinsamer Kern (Kotlin/JVM)
include(":app")      // Android-App
include(":desktop")  // Windows-App (Compose for Desktop), Quellen in ../dx8-windows
project(":desktop").projectDir = file("../dx8-windows")
