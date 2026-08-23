// Owned data layer (AD-5): repositories over Room/DataStore, Offline Fallback Cache,
// settings and session restore. The canonical QueueSnapshot serializer lives here (AD-8).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sway.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}


dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    // Story 7.3: canonical QueueSnapshot JSON serializer (AD-8 single representation).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
