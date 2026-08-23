// Catalog adapter (AD-1): the ONLY module permitted to import NewPipeExtractor types.
// Implements the CatalogSource / StreamResolver ports from :core:model, speaking
// exclusively in core:model types returning SwayResult.
// Unified OkHttp stack (AD-3): SwayDownloaderImpl runs on the shared client derivation;
// Coil uses coil-network-okhttp (designui) sharing the builder timeouts/proxy.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sway.catalog"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
