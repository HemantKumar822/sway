// Single Room database module (AD-8): entities, DAOs, exported schemas and explicit
// migrations. Imported only by :core:data (AD-5). Entities land with migration 1 in 7.3.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sway.core.database"
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


// Exported schemas live in version control; explicit migrations are tested against
// them from migration 1 onward (AD-8).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
