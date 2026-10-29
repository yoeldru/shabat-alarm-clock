// Written: 2026-10-29 21:30 (Asia/Jerusalem)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "il.yoel.shabatalarm"
    compileSdk = 34

    defaultConfig {
        applicationId = "il.yoel.shabatalarm"
        minSdk = 28
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            // Fixed key so every new build installs as an update over the old one
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "shabatalarm123"
            keyAlias = "shabatalarm"
            keyPassword = "shabatalarm123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
