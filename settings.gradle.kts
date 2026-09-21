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

// One module per supported Minecraft version, each producing its own jar, plus the
// version-independent core they all build on. See README for why this cannot be a single jar.
//
// mc1.21.11 is deliberately left out of this list for now: its Minecraft jar setup currently fails
// with a Fabric Loom bug ("Expected official namespace for access widener entry, found:
// intermediary in mod: fabric-lifecycle-events-v1") that survives Loom 1.15.4 through 1.17.20 and a
// Gradle 9.4.0->9.7.1 bump alike - see README's "Roadmap towards 1.16.5" for status. Because Gradle
// configures every included subproject up front, leaving it included would break `./gradlew build`
// (and any single-module build) for mc26.1/mc26.2 too. Its source is untouched on disk; re-add
// "mc1.21.11" here once that's fixed.
include("common", "mc26.1", "mc26.2", "mc26.3")
