plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.util.Properties

val appVersionName = "1.09"
val devVersionName = "1.18"
val devVersionCode = 14

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val releaseStoreFile = localProperties.getProperty("release.storeFile")
val releaseStorePassword = localProperties.getProperty("release.storePassword")
val releaseKeyAlias = localProperties.getProperty("release.keyAlias")
val releaseKeyPassword = localProperties.getProperty("release.keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.sungyoon.helper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sungyoon.helper"
        minSdk = 24
        targetSdk = 36
        // App version is user-facing and only changes on explicit request.
        versionName = appVersionName
        // Development version is internal and can track git/release progress.
        versionCode = devVersionCode
        buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("String", "DEV_VERSION_NAME", "\"$devVersionName\"")
        buildConfigField("int", "DEV_VERSION_CODE", devVersionCode.toString())
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose (Dec 2025 stable BOM)
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Activity / Lifecycle (Dec 2025~)
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.17.0")

    // DataStore + Serialization (포인트 저장)
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
