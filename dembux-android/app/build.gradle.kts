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
        versionCode = 5
        versionName = "1.4"
        buildConfigField("String", "SERVEUR", "\"https://quinte-flux-production.up.railway.app\"")
    }

    buildFeatures { buildConfig = true }

    // Sur GitHub, la clé Dembux (gardée dans le cache privé) signe toujours l'APK :
    // chaque nouvelle version s'installe par-dessus l'ancienne. En local, clé debug habituelle.
    val cle = System.getenv("DEMBUX_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    if (cle != null) {
        signingConfigs {
            create("dembux") {
                storeFile = cle
                storePassword = "android"
                keyAlias = "dembux"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (cle != null) signingConfig = signingConfigs.getByName("dembux")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
