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
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
