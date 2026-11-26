plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.medialibrary.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.medialibrary.manager"
        minSdk = 26
        targetSdk = 35
        // Every CI build used to ship versionCode 1 — with an identical version, some installers
        // treat "install" as a no-op (or balk) instead of actually replacing the running code, so
        // a build could look installed without it taking effect. CI passes -PversionCode=<run
        // number>, strictly increasing per build; local/dev builds fall back to 1.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.2.0"
    }

    signingConfigs {
        // A fixed, committed debug keystore (password "android") so every build —
        // including each CI run — is signed with the SAME key. Without this each
        // ephemeral runner generates a random debug key, so new APKs can't update
        // an installed one in place (signature mismatch) and require a reinstall.
        // Same keystore the Bike app already uses — debug keys aren't app-specific
        // or secret, and the repo's README/release notes already say this app's
        // APK is debug-signed, not released under any particular identity.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    packaging {
        jniLibs {
            // Compress the torrent library's native code in the APK (~16 MB per CPU type
            // uncompressed); it's unpacked once at install instead.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)

    // SMB2/3 client (pure Java/Kotlin, no native deps) — share browsing + the raw
    // random-access reads that back SmbDataSource's real streaming support.
    implementation(libs.jcifs.ng)
    // Generic HTML result-page scraping for user-configured search sites.
    implementation(libs.jsoup)
    // In-app network playback.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    // SAF folder-tree access for user-picked local folders.
    implementation(libs.androidx.documentfile)
    // Artwork loading (fetched cover/poster URLs) in Compose.
    implementation(libs.coil.compose)
    // In-app torrent downloads: libtorrent's Java API plus its native library for ARM phones/TV
    // boxes (64- and 32-bit; many Google TV devices run 32-bit) and x86_64 (emulators, Chromebooks).
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)
    implementation(libs.libtorrent4j.android.arm)
    implementation(libs.libtorrent4j.android.x64)
}
