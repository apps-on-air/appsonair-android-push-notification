plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Reads google-services.json. The build fails with a clear message if it is missing —
    // see example/README.md.
    id("com.google.gms.google-services")
}

android {
    namespace = "com.appsonair.pushexample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appsonair.pushexample"
        // Matches the SDK's minSdk.
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.3-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":push"))
}
