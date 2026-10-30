plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.gongshichen.dshmobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.gongshichen.dshmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":core:platform"))
    implementation(project(":core:database"))
    implementation(project(":core:sync"))
    implementation(project(":core:transport"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:${libs.versions.coroutines.get()}")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-base:18.11.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
