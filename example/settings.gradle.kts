// Standalone Gradle build — deliberately NOT included in the SDK's root settings.gradle.kts.
//
// JitPack builds the repository root, and this example applies the google-services plugin,
// which fails without a google-services.json. That file is gitignored (it belongs to whoever
// runs the example), so keeping the example out of the root build is what stops a missing
// config file from breaking the SDK's publish.
//
// Run it from this directory: cd example && ./gradlew :app:installDebug

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AppsOnAir Core is published on JitPack and is a dependency of :push.
        maven("https://jitpack.io")
    }
}

rootProject.name = "AppsOnAirPushExample"

include(":app")

// The SDK is consumed as a local module rather than a published artifact, so the example
// always runs against the working tree. push/build.gradle.kts reads BASE_URL from
// ../gradle.properties relative to its own projectDir, which resolves to the SDK repo's
// own file from here too.
include(":push")
project(":push").projectDir = file("../push")
