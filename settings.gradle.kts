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
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") }
        }
    }
}

rootProject.name = "HiLightStudio"
include(":app")
