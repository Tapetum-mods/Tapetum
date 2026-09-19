// Minecraft-version module. Its own sources are just the VersionCompat class; everything else is
// compiled from ../shared, so a change there lands in every supported version at once.
plugins {
    id("java")
    id("net.fabricmc.fabric-loom") version("1.15.4")
}

// Plain reads rather than the `by project` / `by extra` delegates: both Kotlin-DSL syntaxes are
// deprecated and are removed in Gradle 10. Nothing here needs these in `extra` - they were only
// ever read by this script.
val minecraftVersion = project.property("mc262_minecraft") as String
val fabricApiVersion = project.property("mc262_fabricApi") as String
val sodiumVersion = project.property("mc262_sodium") as String
val fabricLoaderVersion = project.property("fabricLoaderVersion") as String
val modVersion = project.property("modVersion") as String
val mavenGroup = project.property("mavenGroup") as String
val archivesBaseName = project.property("archivesBaseName") as String

group = mavenGroup
version = "$modVersion+mc$minecraftVersion"

base {
    archivesName.set(archivesBaseName)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    flatDir { dirs(rootProject.file("third_party/iris")) }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    maven {
        name = "CaffeineMC"
        url = uri("https://maven.caffeinemc.net/releases")
    }
    mavenCentral()
}

sourceSets {
    main {
        java.srcDir(rootProject.file("shared/src/main/java"))
        resources.srcDir(rootProject.file("shared/src/main/resources"))
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    // The version-independent core is compiled in rather than shipped as a separate jar, so each
    // version's jar stays a single self-contained file.
    implementation(project(":common"))

    fun embedFabricApiModule(name: String) {
        val module = fabricApi.module(name, fabricApiVersion)
        implementation(module)
        include(module)
    }

    embedFabricApiModule("fabric-api-base")
    embedFabricApiModule("fabric-key-mapping-api-v1")
    embedFabricApiModule("fabric-lifecycle-events-v1")

    // Compiled against but never shipped: Sodium is a hard dependency declared in fabric.mod.json,
    // so the player installs it themselves rather than receiving a bundled copy that would collide
    // with theirs. compileOnly is therefore correct here - required at runtime, absent from the jar.
    compileOnly("net.caffeinemc:sodium-fabric:$sodiumVersion")
    val irisEngine = "dev.tapetum.embedded:iris-fabric:1.11.4+mc$minecraftVersion"
    implementation(irisEngine)
    include(irisEngine)
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
    options.release.set(25)
}

tasks.jar {
    from(project(":common").sourceSets.main.get().output)
    from(rootProject.file("third_party/iris/licenses")) { into("META-INF/licenses/iris") }
    from(rootProject.file("third_party/iris/NOTICE.md")) { into("META-INF/licenses/iris") }
    from(rootProject.file("third_party/iris/iris-sources-$minecraftVersion.zip")) { into("META-INF/sources") }
}

tasks.processResources {
    // Captured here, at configuration time: reading project.* from inside the task action below
    // runs at execution time, which is deprecated and incompatible with the configuration cache.
    val modJsonVersion = project.version.toString()
    val modJsonSodiumVersion = sodiumVersion.substringBefore("+")

    inputs.property("version", modJsonVersion)
    inputs.property("sodiumVersion", sodiumVersion)

    filesMatching("fabric.mod.json") {
        expand(mapOf(
            "version" to modJsonVersion,
            "minecraftVersion" to minecraftVersion,
            "sodiumVersion" to modJsonSodiumVersion
        ))
    }
}

// Headless archive/API checks never start Minecraft or initialize OpenGL.
val contractTest = sourceSets.create("contractTest") {
    java.srcDir(rootProject.file("shared/src/contractTest/java"))
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath
}
dependencies {
    add(contractTest.compileOnlyConfigurationName, "com.google.errorprone:error_prone_annotations:2.41.0")
}
tasks.register<JavaExec>("embeddedEngineContractTest") {
    dependsOn(tasks.jar, tasks.named(contractTest.classesTaskName))
    classpath = contractTest.runtimeClasspath
    mainClass.set("dev.tapetum.shaders.compat.EmbeddedEngineContractTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    args(tasks.jar.get().archiveFile.get().asFile.absolutePath,
        rootProject.file("third_party/iris/iris-fabric-1.11.4+mc$minecraftVersion.jar").absolutePath,
        minecraftVersion)
}
tasks.check { dependsOn("embeddedEngineContractTest") }

// Explicit GPU checks, kept out of build so a display is not required on CI.
val glTest = sourceSets.create("glTest") {
    java.srcDir(rootProject.file("shared/src/glTest/java"))
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}
val smokeTest = sourceSets.create("smokeTest") {
    java.srcDir(rootProject.file("shared/src/smokeTest/java"))
    resources.srcDir(rootProject.file("shared/src/smokeTest/resources"))
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
    providers.gradleProperty("sodiumTestJar262").orNull?.let { installedJar ->
        classpath = files(glTest.runtimeClasspath.filter { !it.name.startsWith("sodium-fabric-") }, installedJar)
    }
    workingDir(layout.buildDirectory.dir("gl-regression").get().asFile)
    doFirst { workingDir.mkdirs() }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Mac")) jvmArgs("-XstartOnFirstThread")
}
