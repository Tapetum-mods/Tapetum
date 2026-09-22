rootProject.name = "tapetum-shaders"

pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

// The Git branch selects the Minecraft version. No other version is configured or packaged.
include("common", "fabric")
