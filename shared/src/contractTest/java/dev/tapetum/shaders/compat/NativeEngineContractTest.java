package dev.tapetum.shaders.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.Set;
import java.util.HashSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Reads archives and bytecode only, without Minecraft initialization or a graphics context. */
public final class NativeEngineContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        try (var mod = new JarFile(args[0])) {
            JsonObject metadata = json(mod, "fabric.mod.json");
            String metadataText = new String(read(mod, "fabric.mod.json"), StandardCharsets.UTF_8);
            require(!metadataText.toLowerCase().contains("iris"), "No Iris metadata in fabric.mod.json");
            require(!metadataText.toLowerCase().contains("sodium"), "No Sodium metadata in fabric.mod.json");
            require(metadata.getAsJsonObject("depends").keySet().equals(Set.of("fabricloader", "minecraft", "java")),
                "Only platform dependencies are required");
            require(mod.getJarEntry("dev/tapetum/shaders/pipeline/ShaderEngine.class") != null,
                "Tapetum ShaderEngine is packaged");
            require(mod.getJarEntry("dev/tapetum/shaders/pipeline/PipelineManager.class") != null,
                "Tapetum PipelineManager is packaged");
            require(mod.stream().noneMatch(entry -> {
                String name = entry.getName().toLowerCase();
                return name.contains("iris") || name.contains("sodium") || name.contains("/smoke/");
            }), "No external renderer or smoke-test files in production");
            require(read(mod, "META-INF/licenses/tapetum/LICENSE").length > 100, "Open-source license packaged");
            for (String path : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                String name = new java.io.File(path).getName().toLowerCase();
                require(!name.startsWith("sodium-") && !name.startsWith("iris-"), "No renderer on build/test classpath: " + name);
            }
            JsonObject mixins = json(mod, "tapetumshaders.mixins.json");
            Set<String> registered = new HashSet<>();
            mixins.getAsJsonArray("client").forEach(value -> registered.add(value.getAsString()));
            require(registered.equals(Set.of("MixinLevelRenderer", "MixinVideoSettingsScreen")),
                "Native rendering and vanilla settings hooks registered");
            for (String name : registered) {
                require(mod.getJarEntry("dev/tapetum/shaders/mixin/" + name + ".class") != null, "Packaged mixin " + name);
            }
            ClassNode video = resourceClass("net/minecraft/client/gui/screens/options/VideoSettingsScreen");
            long calls = video.methods.stream().filter(m -> m.name.equals("addOptions"))
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/gui/components/OptionsList")
                    && call.name.equals("addSmall") && call.desc.equals("([Lnet/minecraft/client/OptionInstance;)V"))
                .count();
            require(calls == 3, "Video settings hook targets the third array, not all three sections");
            ClassNode initializer = new ClassNode();
            new ClassReader(read(mod, "dev/tapetum/shaders/TapetumShadersClient.class")).accept(initializer, 0);
            long keys = initializer.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode call && call.name.equals("registerKeyMapping")).count();
            require(keys == 2, "Standalone O/K controls registered without another renderer");
        }
        System.out.println("Native Tapetum engine contract: " + checks + " checks passed (headless)");
    }

    private static ClassNode resourceClass(String name) throws IOException {
        try (var stream = NativeEngineContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) throw new IOException("Missing Minecraft class " + name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static JsonObject json(JarFile jar, String name) throws IOException {
        return JsonParser.parseString(new String(read(jar, name), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null) throw new IOException("Missing archive entry: " + name);
        try (var stream = jar.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
