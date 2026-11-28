plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dembux.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dembux.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        buildConfigField("String", "SERVEUR", "\"https://quinte-flux-production.up.railway.app\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        // L'APK « debug » est signé automatiquement : il s'installe directement sur le téléphone.
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
