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
        versionCode = 3
        versionName = "0.1.2"
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

// In-app "Licenses" screen: copies the project LICENSE, the Apache-2.0 text and the
// generated third-party license report into app assets at build time, so the screen
// always matches the sources and the dependencies actually bundled.
abstract class LicenseAssetsTask : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licenses")
        dir.deleteRecursively()
        dir.mkdirs()
        sources.files.forEach { file ->
            val name = if (file.name == "LICENSE") "LICENSE.txt" else file.name
            file.copyTo(dir.resolve(name), overwrite = true)
        }
    }
}

val licenseAssets = tasks.register<LicenseAssetsTask>("licenseAssets") {
    sources.from(
        rootProject.file("LICENSE"),
        file("licenses/Apache-2.0.txt"),
        layout.buildDirectory.file("reports/third-party-licenses/third-party-licenses.md")
    )
    dependsOn("checkThirdPartyLicenses")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(licenseAssets, LicenseAssetsTask::outputDir)
    }
}

// Fails preBuild/check when a runtime dependency has a forbidden or unknown license.
apply(from = rootProject.file("gradle/license-check.gradle.kts"))
