pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GalaxyFit3CustomFace"
include(":app")
include(":core:format")
include(":core:image")
include(":core:delivery")
