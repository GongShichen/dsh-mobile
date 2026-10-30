pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-mobile"
include(":androidApp", ":composeApp")
include(":core:model", ":core:protocol", ":core:transport", ":core:sync", ":core:database", ":core:platform")
