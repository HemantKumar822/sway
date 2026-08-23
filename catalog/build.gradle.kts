// Catalog adapter (AD-1): the ONLY module permitted to import NewPipeExtractor types.
// Implements the CatalogSource / StreamResolver ports from :core:model, speaking
// exclusively in core:model types returning SwayResult.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sway.catalog"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}


dependencies {
    implementation(project(":core:model"))

    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
