package dev.tapetum.shaders.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.Set;
import java.util.HashSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Headless bytecode and pure camera-state checks, without a Minecraft client or graphics context. */
public final class NativeEngineContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        try (var mod = new JarFile(args[0])) {
            JsonObject metadata = json(mod, "fabric.mod.json");
            require(args.length == 3, "Expected artifact, Minecraft version and mod version");
            require(metadata.getAsJsonObject("depends").get("minecraft").getAsString().equals(args[1]),
                "Minecraft dependency is exactly this branch's target");
            require(metadata.get("version").getAsString().equals(args[2]),
                "Packaged version matches Gradle's artifact version");
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
            verifyRenderHooks(mod);
            verifyGlBridge(mod);
        }
        FrameStateContractTest.run();
        System.out.println("Native Tapetum engine contract: " + checks + " checks passed (headless)");
    }

    private static void verifyRenderHooks(JarFile mod) throws IOException {
        ClassNode renderer = resourceClass("net/minecraft/client/renderer/LevelRenderer");
        ClassNode mixin = new ClassNode();
        new ClassReader(read(mod, "dev/tapetum/shaders/mixin/MixinLevelRenderer.class")).accept(mixin, 0);
        int hooks = 0;
        for (var method : mixin.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var annotation : method.visibleAnnotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                hooks++;
                int key = annotation.values.indexOf("method");
                require(key >= 0, "Render hook declares a target");
                var targets = (java.util.List<?>) annotation.values.get(key + 1);
                Type[] args = Type.getArgumentTypes(method.desc);
                require(args[args.length - 1].getInternalName().endsWith("/CallbackInfo"), "Render hook has callback");
                String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, java.util.Arrays.copyOf(args, args.length - 1));
                require(targets.size() == 1 && renderer.methods.stream().anyMatch(target ->
                    target.name.equals(targets.get(0)) && target.desc.equals(descriptor)),
                    "Render hook exactly matches this Minecraft version: " + descriptor);
            }
            if (method.name.equals("tapetum$beginLevelRender")) {
                var calls = java.util.Arrays.stream(method.instructions.toArray())
                    .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).toList();
                int begin = -1;
                int capture = -1;
                for (int i = 0; i < calls.size(); i++) {
                    if (calls.get(i).owner.equals("dev/tapetum/shaders/pipeline/PipelineManager")
                        && calls.get(i).name.equals("beginLevelRendering")) begin = i;
                    if (calls.get(i).owner.equals("dev/tapetum/shaders/uniform/FrameState")
                        && calls.get(i).name.equals("capture")) capture = i;
                }
                require(begin >= 0 && capture > begin, "World transition is handled before camera capture");
            }
        }
        require(hooks == 2, "Both render hooks were checked");
    }

    private static void verifyGlBridge(JarFile mod) throws IOException {
        var methods = new HashSet<String>();
        String bridge = "dev/tapetum/shaders/compat/GlStateManager";
        String type = bridge;
        while (type != null) {
            var node = resourceClass(type);
            for (var method : node.methods) {
                if ((method.access & Opcodes.ACC_PUBLIC) != 0 && (method.access & Opcodes.ACC_STATIC) != 0)
                    methods.add(method.name + method.desc);
            }
            type = node.superName;
        }
        for (var entry : mod.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
            var node = new ClassNode();
            new ClassReader(read(mod, entry.getName())).accept(node, 0);
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(bridge)) {
                        require(methods.contains(call.name + call.desc), "GL cache bridge resolves " + call.name + call.desc);
                    }
                }
            }
        }
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
