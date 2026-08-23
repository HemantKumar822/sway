// Playback engine module (AD-6): SwayPlaybackService owns the player; UI talks only
// through the PlayerConnection facade. PendingUri placeholder scheme is defined
// in exactly one place inside this module (PendingUri.PREFIX).

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sway.playback"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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


dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented FR-8 timing harness placeholder (story 4.4) — mirrors the
    // :catalog LiveSmoke precedent: tag-gated @Ignore, never runs without a device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}
