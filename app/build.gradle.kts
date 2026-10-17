plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (GitHub Actions secrets).
// Without them only the debug (test) build can be produced.
val keystorePath: String? = System.getenv("DAROYAR_KEYSTORE")?.takeIf { it.isNotBlank() }
val keystorePass: String? = System.getenv("DAROYAR_KS_PASS")?.takeIf { it.isNotBlank() }
val hasReleaseKey = keystorePath != null && keystorePass != null && java.io.File(keystorePath).exists()
val repoSlug: String = System.getenv("GITHUB_REPOSITORY") ?: "jinzin1350/daroyar"

android {
    namespace = "com.daroyar.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.daroyar.app"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        manifestPlaceholders["appLabel"] = "دارویار"
        buildConfigField("String", "REPO", "\"$repoSlug\"")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = System.getenv("DAROYAR_KEY_ALIAS") ?: "daroyar"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Test builds install next to the real app and never clash with it.
            applicationIdSuffix = ".test"
            manifestPlaceholders["appLabel"] = "دارویار تست"
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
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // MQTT client: talks to free public brokers, no server of our own needed
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
