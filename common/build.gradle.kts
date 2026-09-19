// The version-independent core: shaderpack discovery, configuration, and the pipeline contract.
// This module must never depend on Minecraft or on a mod loader - that is what lets every
// supported Minecraft version share it unchanged, and what lets it be tested without a game.
plugins {
    id("java")
}

// Plain reads rather than the `by project` delegate: that Kotlin-DSL syntax is deprecated and is
// removed in Gradle 10.
val modVersion = project.property("modVersion") as String
val mavenGroup = project.property("mavenGroup") as String

group = mavenGroup
version = modVersion

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.17")

    // This module has no Minecraft on its classpath, which is exactly what makes the GLSL
    // preprocessing/patching layer here unit-testable without a game.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}
