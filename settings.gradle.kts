pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor ships via JitPack; consumed only by :catalog (AD-1).
        maven("https://jitpack.io")
    }
}

plugins {
    // AR-13 note: the JDK line is enforced as a COMPILE TARGET (Java 21 bytecode)
    // rather than a downloaded toolchain — hosts build with whatever modern JDK is
    // present (e.g. Android Studio's JBR), keeping fresh clones dependency-free.
}

rootProject.name = "sway"

// The seven-module graph is fixed by AD-5. No other module may ever appear here.
include(":app")
include(":core:model")
include(":core:database")
include(":core:data")
include(":catalog")
include(":playback")
include(":designui")
