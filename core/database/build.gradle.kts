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
        androidMain.dependencies { implementation(libs.sqldelight.android) }
        iosMain.dependencies { implementation(libs.sqldelight.native) }
    }
}

sqldelight {
    databases {
        create("MobileDatabase") {
            packageName.set("io.dsh.mobile.database.generated")
        }
    }
}
