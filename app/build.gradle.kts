import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Bump this for each release (release.sh does it for you).
val appVersion = "1.4.0"

android {
    namespace = "com.adfree.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.novaplay.player"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.split(".").let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }
        versionName = appVersion
        // Used by the in-app updater to find releases.
        buildConfigField("String", "GITHUB_REPO", "\"anees-dahot/novaplay\"")
    }

    // Release key lives outside the repo (~/.novaplay/keystore.properties).
    // Every release must use the same key, or phones refuse the update.
    val keyProps = Properties().apply {
        val f = File(System.getProperty("user.home"), ".novaplay/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = File(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Strip unused code/resources (R8) to shrink the APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keyProps.isNotEmpty()) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    // One APK per CPU type keeps each file small (libVLC ships native code).
    splits {
        abi {
            isEnable = true
            reset()
            // Releases target modern 64-bit phones only (keeps the download small).
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    // Store native libs compressed inside the APK: much smaller download.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.7.7")

    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.fragment:fragment:1.8.5") // media pulls an old one; needed for result APIs
}
