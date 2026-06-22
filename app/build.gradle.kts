plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bike.trainer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bike.trainer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Strava OAuth credentials. Override in local.properties / CI secrets for a
        // real build; the placeholders keep the project compiling without them.
        val stravaClientId = (project.findProperty("STRAVA_CLIENT_ID") as String?) ?: ""
        val stravaClientSecret = (project.findProperty("STRAVA_CLIENT_SECRET") as String?) ?: ""
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"$stravaClientId\"")
        buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"$stravaClientSecret\"")
        // MapTiler key powers the real 3D map (terrain + satellite + OSM buildings).
        // Without it the ride falls back to MapLibre's free demo tiles (flat, no 3D).
        val mapTilesKey = (project.findProperty("MAPTILES_API_KEY") as String?) ?: ""
        buildConfigField("String", "MAPTILES_API_KEY", "\"$mapTilesKey\"")
        // Google Maps key (build-time) powers the interactive Street View panorama.
        // The Maps SDK reads it from the manifest, so it can't be entered in-app.
        // An Android Maps key is shipped inside every APK (extractable), so it's
        // protected by app/API restrictions in the Google console, not by secrecy.
        // A MAPS_API_KEY gradle property / CI secret overrides this default.
        val mapsApiKey = (project.findProperty("MAPS_API_KEY") as String?)?.ifBlank { null }
            ?: "AIzaSyDvo9I5f-bq7Rd6WRA_Il5C-Mbi5Nk7ID0"
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
        buildConfigField("boolean", "HAS_MAPS_KEY", "${mapsApiKey.isNotBlank()}")
        // Same key, exposed to code for the Street View *Static* API (route prefetch).
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        // Redirect scheme used by the OAuth callback (bike://strava-auth).
        manifestPlaceholders["stravaRedirectScheme"] = "bike"
        manifestPlaceholders["stravaRedirectHost"] = "strava-auth"
    }

    signingConfigs {
        // A fixed, committed debug keystore (password "android") so every build —
        // including each CI run — is signed with the SAME key. Without this each
        // ephemeral runner generates a random debug key, so new APKs can't update
        // an installed one in place (signature mismatch) and require a reinstall.
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
        buildConfig = true
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
    implementation(libs.androidx.browser)
    implementation(libs.maplibre.android)
    implementation(libs.play.services.maps)
    // OpenCV for the optional optical-flow "Flow" Street View morph. Large (native
    // libs for all ABIs) but only pulled in for that one feature.
    implementation(libs.opencv)
}
