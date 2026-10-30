plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

val verifyIrohAndroidNative by tasks.registering {
    group = "verification"
    description = "Prevents packaging an Android client without its iroh native transport."
    doLast {
        val library = layout.projectDirectory.file("src/androidMain/jniLibs/arm64-v8a/libiroh_ffi.so").asFile
        check(library.isFile && library.length() > 0) {
            "Missing iroh Android native library. Run scripts/build-iroh-android.sh before assembling the APK."
        }
    }
}
tasks.matching { it.name == "mergeAndroidMainNativeLibs" }.configureEach {
    dependsOn(verifyIrohAndroidNative)
}

kotlin {
    android {
        namespace = "io.dsh.mobile.transport"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:protocol"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies { implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar") }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.serialization.json)
            implementation(kotlin("test"))
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
            implementation(libs.coroutines.test)
        }
    }
}
