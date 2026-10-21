plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pockettranspose"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pockettranspose"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    // Release signing comes from the environment (GitHub Actions secrets in
    // .github/workflows/release.yml); the keystore is never committed. Without it,
    // assembleRelease produces an unsigned APK, which the release workflow rejects.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build sit next to the signed release build on one phone.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "PocketTranspose (debug)")
        }
        release {
            // R8 stays off for the first release: the app is small and the WebView
            // integration has only been verified unminified on a device.
            isMinifyEnabled = false
            resValue("string", "app_name", "PocketTranspose")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}

// Fails preBuild/check when a runtime dependency has a forbidden or unknown license.
apply(from = rootProject.file("gradle/license-check.gradle.kts"))
