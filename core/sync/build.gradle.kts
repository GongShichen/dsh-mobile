plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "io.dsh.mobile.sync"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:protocol"))
            api(project(":core:transport"))
            api(project(":core:database"))
            api(project(":core:platform"))
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
