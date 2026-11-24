plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.dx8.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.dx8.monitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Testversion mit Ablaufdatum (Trial.kt) – ohne: gradle -Ptrial=false assembleDebug
        buildConfigField("boolean", "TRIAL", (project.findProperty("trial") ?: "true").toString())
    }
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // JaCoCo-Bericht: gradle :app:createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
}
