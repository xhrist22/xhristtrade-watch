import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Config comes from CI env vars first (GitHub Actions secrets), then local.properties, then a safe default.
// The WATCH_KEY secret therefore never lives in git.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun cfg(key: String, def: String): String =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: def

android {
    namespace = "com.xhristtrade.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xhristtrade.watch"
        minSdk = 30            // Wear OS 3 (Galaxy Watch 4 and up)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "BASE_URL", "\"${cfg("BASE_URL", "https://xhristtrade.com")}\"")
        buildConfigField("String", "WATCH_KEY", "\"${cfg("WATCH_KEY", "")}\"")
        buildConfigField("String", "PHONE_URL", "\"${cfg("PHONE_URL", "https://terminal.xhristtrade.com")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // _QT_WATCH_STABLE_KEY_2026_10_22 — one fixed key (CI decodes xt.keystore from the DEBUG_KEYSTORE_B64 secret) so every
    // build is signed the same way and updates install OVER the old app — no uninstall/re-pair. No keystore ⇒ default debug signing.
    signingConfigs {
        create("stable") {
            val ks = rootProject.file("xt.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "xtwatch2026"
                keyAlias = "xt"
                keyPassword = "xtwatch2026"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            if (rootProject.file("xt.keystore").exists()) signingConfig = signingConfigs.getByName("stable")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.wear:wear-remote-interactions:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.guava:guava:33.2.1-android")
}
