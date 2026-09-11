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
        // AppsOnAir Core is published on JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "AppsOnAir-Android-Push"
include(":push")
