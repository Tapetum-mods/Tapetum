// Fabric adapter and renderer for this branch's single Minecraft version.
plugins {
    id("java")
    id("net.fabricmc.fabric-loom-remap") version("1.15.4")
}

// Plain reads rather than the `by project` / `by extra` delegates: both Kotlin-DSL syntaxes are
// deprecated and are removed in Gradle 10. Nothing here needs these in `extra` - they were only
// ever read by this script.
val minecraftVersion = project.property("minecraftVersion") as String
val fabricApiVersion = project.property("fabricApiVersion") as String
val fabricLoaderVersion = project.property("fabricLoaderVersion") as String
val mavenGroup = project.property("mavenGroup") as String
val archivesBaseName = project.property("archivesBaseName") as String

group = mavenGroup
version = rootProject.version

base {
    archivesName.set(archivesBaseName)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("org.joml:joml:1.10.8")
    include("org.joml:joml:1.10.8")
    implementation("org.slf4j:slf4j-api:2.0.17")
    include("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    include("org.slf4j:slf4j-simple:2.0.17")

    // The version-independent core is compiled in rather than shipped as a separate jar, so each
    // version's jar stays a single self-contained file.
    implementation(project(":common"))

    fun embedFabricApiModule(name: String) {
        val module = fabricApi.module(name, fabricApiVersion)
        modImplementation(module)
        include(module)
    }

    embedFabricApiModule("fabric-api-base")
    embedFabricApiModule("fabric-key-binding-api-v1")
    embedFabricApiModule("fabric-lifecycle-events-v1")

}

loom {
    // No mixin { } block: Loom no longer enables the mixin annotation processor by default and warns
    // if one is configured. Nothing generated a refmap anyway, so tapetumshaders.mixins.json declares
    // none - Loom remaps mixin targets when it builds the jar, which is what production actually uses.
    runs {
        named("client") {
            client()
            configName = "Fabric Client ($minecraftVersion)"
            ideConfigGenerated(true)
            runDir("../run/$minecraftVersion")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    options.release.set(21)
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
    from(rootProject.file("LICENSE")) { into("META-INF/licenses/tapetum") }
}

val standaloneSources = tasks.register<Jar>("standaloneSourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
    from(project(":common").sourceSets.main.get().allSource)
    from(rootProject.file("LICENSE"))
}
tasks.assemble { dependsOn(standaloneSources) }

tasks.processResources {
    // Captured here, at configuration time: reading project.* from inside the task action below
    // runs at execution time, which is deprecated and incompatible with the configuration cache.
    val modJsonVersion = project.version.toString()

    inputs.property("version", modJsonVersion)
    inputs.property("minecraftVersion", minecraftVersion)

    filesMatching("fabric.mod.json") {
        expand(mapOf(
            "version" to modJsonVersion,
            "minecraftVersion" to minecraftVersion
        ))
    }
}

// Headless archive/API checks never start Minecraft or initialize OpenGL.
val contractTest = sourceSets.create("contractTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}
dependencies {
    add(contractTest.compileOnlyConfigurationName, "com.google.errorprone:error_prone_annotations:2.41.0")
}
tasks.register<JavaExec>("nativeEngineContractTest") {
    dependsOn(tasks.jar, tasks.named("remapJar"), tasks.named(contractTest.classesTaskName))
    classpath = contractTest.runtimeClasspath
    mainClass.set("dev.tapetum.shaders.compat.NativeEngineContractTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    args(tasks.jar.get().archiveFile.get().asFile.absolutePath, minecraftVersion, project.version.toString(),
        tasks.named<org.gradle.api.tasks.bundling.AbstractArchiveTask>("remapJar").get().archiveFile.get().asFile.absolutePath)
}
tasks.check { dependsOn("nativeEngineContractTest") }

// Explicit GPU checks, kept out of build so a display is not required on CI.
val glTest = sourceSets.create("glTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}
val smokeTest = sourceSets.create("smokeTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
tasks.register<Jar>("smokeTestJar") {
    archiveBaseName.set("tapetum-smoke-test")
    from(smokeTest.output)
}
tasks.register<JavaExec>("glRegressionTest") {
    dependsOn(tasks.named(glTest.classesTaskName))
    classpath = glTest.runtimeClasspath
    mainClass.set("dev.tapetum.shaders.pipeline.backend.gl.GlRegressionTest")
    systemProperty("tapetum.test.minecraftVersion", minecraftVersion)
    workingDir(layout.buildDirectory.dir("gl-regression").get().asFile)
    doFirst { workingDir.mkdirs() }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Mac")) jvmArgs("-XstartOnFirstThread")
}
