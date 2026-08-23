// Pure-Kotlin domain module (AD-5): models, SwayResult/SwayError, ArtworkRef, and the
// CatalogSource / StreamResolver ports live here from epic 2 onward.
// Zero Android dependencies are permitted in this module (CI import-ban enforced).

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
