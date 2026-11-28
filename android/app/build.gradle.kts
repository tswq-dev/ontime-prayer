plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Google Play: .github/workflows/play.yml puts the upload key here (from the PLAY_UPLOAD_KEY secret).
val playKey: String? = System.getenv("PLAY_KEYSTORE_FILE")

android {
    namespace = "com.tswq.ontimeprayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tswq.ontimeprayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 64
        versionName = "1.0.1"
    }

    signingConfigs {
        // Fixed test key so each trial build installs over the previous one.
        // Google Play builds are signed with the upload key instead (play.yml).
        getByName("debug") {
            storeFile = file("test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (playKey != null) create("play") {
            storeFile = file(playKey)
            storePassword = System.getenv("PLAY_KEYSTORE_PASS")
            keyAlias = "upload"
            keyPassword = System.getenv("PLAY_KEYSTORE_PASS")
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        getByName("release") {
            isMinifyEnabled = false
            if (playKey != null) signingConfig = signingConfigs.getByName("play")
        }
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
