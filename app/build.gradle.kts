plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dallas.cabletv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dallas.cabletv"
        minSdk = 26
        targetSdk = 34
        // CI passes -PversionCode=<run number> so every release is newer than the last.
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    // Release signing reads its values from environment variables (or Gradle properties with the
    // same names), so the keystore and its passwords never live in the repository.
    //   CABLETV_KEYSTORE_PATH, CABLETV_KEYSTORE_PASSWORD, CABLETV_KEY_ALIAS, CABLETV_KEY_PASSWORD
    // If any are missing, the release build is produced unsigned, and debug builds are unaffected.
    signingConfigs {
        create("release") {
            fun setting(name: String): String? =
                (System.getenv(name) ?: providers.gradleProperty(name).orNull)?.takeIf { it.isNotBlank() }

            val path = setting("CABLETV_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = setting("CABLETV_KEYSTORE_PASSWORD")
                keyAlias = setting("CABLETV_KEY_ALIAS")
                keyPassword = setting("CABLETV_KEY_PASSWORD")
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

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only attach the signing config when a keystore was supplied.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Draws the sign-in QR code on the TV.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
