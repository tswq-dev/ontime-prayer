plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tswq.ontimeprayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tswq.ontimeprayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.18.2"
    }

    signingConfigs {
        // Fixed test key so each trial build installs over the previous one.
        // Replace with a private release key before publishing to Google Play.
        getByName("debug") {
            storeFile = file("test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // the widget pictures test draws with the real fonts in assets
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
