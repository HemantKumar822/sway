// AGP 9 compiles Kotlin via built-in support, which bundles KGP 2.2.10. To pin the
// AR-13 Kotlin line (2.4.10) and its matched KSP, their Gradle plugins are raised on
// the buildscript classpath per developer.android.com/build/migrate-to-built-in-kotlin.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    }
}

// Declares every plugin once at the root so module scripts only apply aliases.
// Versions live exclusively in gradle/libs.versions.toml (AR-13 pins).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
