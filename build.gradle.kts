plugins {
    id("java")
}

// Sources live in common/ (version-independent), shared/ (Minecraft code common to every
// supported version) and mcXX/ (one module per Minecraft version). Nothing is built from the root.
//
// fabric-loom is deliberately *not* declared here: pinning its version at the root forces every
// version module onto the same Loom release, and Loom releases aren't uniformly compatible with
// every supported Minecraft version (1.15.4 fails to process 1.21.11's access widener - see
// mc1.21.11/build.gradle.kts). Each version module pins its own Loom version instead.
tasks.jar { enabled = false }
