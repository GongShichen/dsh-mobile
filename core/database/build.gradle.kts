plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "io.dsh.mobile.database"
        compileSdk = 37
        minSdk = 26
        withHostTestBuilder {}
        withDeviceTestBuilder {}.configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
        }
        getByName("androidHostTest").dependencies { implementation("app.cash.sqldelight:sqlite-driver:2.4.1") }
        androidMain.dependencies { implementation(libs.sqldelight.android) }
        iosMain.dependencies { implementation(libs.sqldelight.native) }
    }
}

sqldelight {
    databases {
        create("HLDatabase") {
            packageName.set("io.dsh.mobile.database.generated")
        }
    }
}
