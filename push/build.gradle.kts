import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Single source of truth for the published coordinate's version. Keep in step with
// AppsOnAirDeviceInfo.SDK_VERSION, which is the value reported to the backend as
// "sdk_version" — that one is a runtime constant and cannot be read from here.
val sdkVersion = "0.0.1-alpha"

group = "com.appsonair"
version = sdkVersion

// Wire-level configuration lives in this SDK repo's own gradle.properties, same as the
// AppLink, AppSync, and Core SDKs. The value is unquoted there; buildConfigField adds the
// string literal quotes.
//
// Loaded from the file by path rather than via project.property(), because :push is also
// consumed cross-repo — PushNotification-QA maps it in with
// project(":push").projectDir = file("../appsonair-push-notification-android/push"), making
// it a subproject of *that* build. Project properties would then resolve against the QA
// repo's gradle.properties, which would put the SDK's backend URL under the demo app's
// control. projectDir is always <this repo>/push in either build, so ../gradle.properties is
// always this repo's file.
//
// A -PBASE_URL=... command-line override still wins, for pointing a build at production.
val sdkProperties = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}

// Explicitly typed: getProperty() returns a platform type, so without the String? annotation
// the elvis below is dead code and a missing key reaches buildConfigField as the literal
// string "null" — which would silently produce request URLs like "nullsubscriptions".
val baseUrlOrNull: String? = findProperty("BASE_URL") as String?
    ?: sdkProperties.getProperty("BASE_URL")
val baseUrl = (baseUrlOrNull
    ?: error("BASE_URL missing from ${file("../gradle.properties")}")).trim('"')

android {
    namespace = "com.appsonair.push"
    compileSdk = 36

    defaultConfig {
        // Matches AppsOnAir Core (minSdk 24) — Core is a required dependency,
        // so the Push SDK cannot go below it. Drops Android 6.0.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to build its Application.
            isIncludeAndroidResources = true
        }
    }

    // Publish only the release variant. Without this the maven-publish plugin finds no
    // "release" software component and the publication below fails to resolve.
    publishing {
        singleVariant("release")
    }
}

dependencies {

    //Used AppsOnAir Core
    // JitPack publishes this release as "1.2.2"; the older tags carried a "v" prefix.
    implementation("com.github.apps-on-air:AppsOnAir-Android-Core:1.2.2")

    // HTTP client for the AppsOnAir Push backend. Same version the AppLink and AppSync
    // SDKs pin, so the SDK family shares one OkHttp on a host app's classpath.
    implementation("com.squareup.okhttp3:okhttp:4.9.1")

    // Keep Firebase in Push SDK, not AppsOnAir Core.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-installations")

    // ProcessLifecycleOwner — used by AppsOnAirSessionManager to detect app foreground/background.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // Unit tests. Robolectric runs them on the JVM against a real Android framework
    // implementation, which is what lets NotificationPermissionChangeTest drive the
    // Activity lifecycle callbacks and toggle the notification permission the SDK reads.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// The release component only exists after the Android plugin has evaluated its variants,
// so the publication has to be declared inside afterEvaluate. JitPack runs
// publishToMavenLocal and serves whatever this produces.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.appsonair"
                artifactId = "push"
                version = sdkVersion
            }
        }
    }
}
