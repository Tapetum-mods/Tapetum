plugins { id("java") }

fun artifactVersion(mod: String, game: String, release: Boolean, buildId: String?): String {
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(mod)) {
        "modVersion must be a semantic version without build metadata"
    }
    require(Regex("[0-9]+(?:\\.[0-9]+)+").matches(game)) { "minecraftVersion must be an exact release" }
    if (release) return "$mod+mc$game"
    require(buildId == null || Regex("[0-9]+").matches(buildId)) { "GITHUB_RUN_NUMBER must be numeric" }
    val suffix = buildId?.let { "build.$it" } ?: "local"
    return "${mod.substringBefore('-')}-snapshot+mc$game-$suffix"
}

group = providers.gradleProperty("mavenGroup").get()
version = artifactVersion(
    providers.gradleProperty("modVersion").get(),
    providers.gradleProperty("minecraftVersion").get(),
    providers.gradleProperty("build.release").orElse("false").get().toBooleanStrict(),
    providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
)

// Only fabric/ produces the installable mod. common/ stays independently testable.
tasks.jar { enabled = false }

val verifyVersionPolicy = tasks.register("verifyVersionPolicy") {
    doLast {
        check(artifactVersion("0.1.0", "26.1.2", false, null) == "0.1.0-snapshot+mc26.1.2-local")
        check(artifactVersion("0.1.0", "26.2", false, "42") == "0.1.0-snapshot+mc26.2-build.42")
        check(artifactVersion("0.1.0", "26.3", true, "42") == "0.1.0+mc26.3")
        check(artifactVersion("0.2.0-beta.1", "26.3", true, null) == "0.2.0-beta.1+mc26.3")
        check(artifactVersion("0.2.0-beta.1", "26.3", false, null) == "0.2.0-snapshot+mc26.3-local")
        check(runCatching { artifactVersion("../bad", "26.3", false, null) }.isFailure)
        check(runCatching { artifactVersion("0.1.0", "*", false, null) }.isFailure)
        check(runCatching { artifactVersion("0.1.0", "26.3", false, "../bad") }.isFailure)
        println("Version policy: 8 checks passed")
    }
}
tasks.check { dependsOn(verifyVersionPolicy) }
