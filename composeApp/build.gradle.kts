plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "io.dsh.mobile"
        compileSdk = 37
        minSdk = 26
        androidResources.enable = true
        withHostTestBuilder {}
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DshMobile"
            isStatic = true
            export(project(":core:transport"))
            export(project(":core:platform"))
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core:transport"))
            api(project(":core:platform"))
            implementation(project(":core:model"))
            implementation(project(":core:protocol"))
            implementation(project(":core:sync"))
            implementation(project(":core:database"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
