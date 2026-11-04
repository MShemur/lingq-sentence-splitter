plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionCode=<run number> -PversionName=1.<run number>
val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = (project.findProperty("versionName") as String?) ?: "1.0-dev"

android {
    namespace = "com.mshemur.lingqfix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mshemur.lingqfix"
        minSdk = 26
        targetSdk = 35
        versionCode = vCode
        versionName = vName
    }

    // Every build must be signed with the same key, otherwise Android refuses to update the app.
    // The key is not in git: CI writes it from the ANDROID_KEYSTORE_B64 secret to signing/release.p12
    // and passes the password in SIGNING_PASSWORD.
    signingConfigs {
        create("release") {
            storeFile = file("signing/release.p12")
            storeType = "pkcs12"
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "lingqfix"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
