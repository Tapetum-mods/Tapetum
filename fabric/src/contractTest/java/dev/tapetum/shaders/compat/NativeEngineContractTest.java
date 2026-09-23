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
            require(registered.equals(Set.of("MixinLevelRenderer", "AccessorOptionsSubScreen", "MixinMinecraftScreen",
                "MixinTransparentButton", "MixinTransparentSlider", "MixinGlCommandEncoder", "AccessorGlBuffer",
                "AccessorGlRenderPass")), "Native rendering and scoped transparent UI hooks registered");
            for (String name : registered) {
                require(mod.getJarEntry("dev/tapetum/shaders/mixin/" + name + ".class") != null, "Packaged mixin " + name);
            }
            verifyGuiHooks(mod, metadata);
            ClassNode initializer = new ClassNode();
            new ClassReader(read(mod, "dev/tapetum/shaders/TapetumShadersClient.class")).accept(initializer, 0);
            long keys = initializer.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode call && call.name.equals("registerKeyMapping")).count();
            require(keys == 2, "Standalone O/K controls registered without another renderer");
            verifyRenderHooks(mod);
            verifyTerrainHooks();
            verifyGlBridge(mod);
        }
        FrameStateContractTest.run();
        System.out.println("Native Tapetum engine contract: " + checks + " checks passed (headless)");
    }

    private static void verifyTerrainHooks() throws IOException {
        String descriptor = "(Lcom/mojang/blaze3d/opengl/GlRenderPass;IIILcom/mojang/blaze3d/vertex/VertexFormat$IndexType;"
            + "Lcom/mojang/blaze3d/opengl/GlRenderPipeline;I)V";
        var encoder = resourceClass("com/mojang/blaze3d/opengl/GlCommandEncoder");
        require(encoder.methods.stream().anyMatch(m -> m.name.equals("drawFromBuffers") && m.desc.equals(descriptor)),
            "Exact native draw hook target exists");
        var hook = resourceClass("dev/tapetum/shaders/mixin/MixinGlCommandEncoder").methods.stream()
            .filter(m -> m.name.equals("tapetum$drawTerrain")).findFirst().orElseThrow();
        require(hook.desc.equals(descriptor.substring(0, descriptor.length() - 2)
            .replace("Lcom/mojang/blaze3d/opengl/GlRenderPass;", "Ljava/lang/Object;")
            + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"), "Hook parameters match with explicit pass coercion");
        var draws = encoder.methods.stream().filter(m -> m.name.equals("executeDrawMultiple")).findFirst().orElseThrow();
        var instructions = java.util.Arrays.asList(draws.instructions.toArray());
        int upload = -1;
        int draw = -1;
        for (int i = 0; i < instructions.size(); i++) {
            if (instructions.get(i) instanceof MethodInsnNode call) {
                if (call.owner.equals("java/util/function/BiConsumer") && call.name.equals("accept")) upload = i;
                if (call.name.equals("drawFromBuffers")) draw = i;
            }
        }
        require(upload >= 0 && draw > upload, "Section uniforms are uploaded before the intercepted draw");
        var pass = resourceClass("com/mojang/blaze3d/opengl/GlRenderPass");
        require(pass.fields.stream().anyMatch(f -> f.name.equals("vertexBuffers")
            && f.desc.equals("[Lcom/mojang/blaze3d/buffers/GpuBuffer;")), "Native vertex buffer accessor target");
        require(pass.fields.stream().anyMatch(f -> f.name.equals("indexBuffer")
            && f.desc.equals("Lcom/mojang/blaze3d/buffers/GpuBuffer;")), "Native index buffer accessor target");
        require(resourceClass("com/mojang/blaze3d/opengl/GlBuffer").fields.stream()
            .anyMatch(f -> f.name.equals("handle") && f.desc.equals("I")), "Native GL handle accessor target");
    }

    private static void verifyGuiHooks(JarFile mod, JsonObject metadata) throws IOException {
        String guiDescriptor = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
        for (String owner : Set.of("AbstractButton", "AbstractSliderButton")) {
            require(resourceClass("net/minecraft/client/gui/components/" + owner).methods.stream().anyMatch(m ->
                m.name.equals("extractWidgetRenderState") && m.desc.equals(guiDescriptor)), "Exact widget paint target: " + owner);
        }
        require(resourceClass("net/minecraft/client/Minecraft").methods.stream().anyMatch(m -> m.name.equals("setScreen")
            && m.desc.equals("(Lnet/minecraft/client/gui/screens/Screen;)V")), "Exact screen replacement hook");
        require(resourceClass("net/minecraft/client/gui/screens/options/OptionsSubScreen").fields.stream().anyMatch(f ->
            f.name.equals("lastScreen") && f.desc.equals("Lnet/minecraft/client/gui/screens/Screen;")), "Parent screen accessor target");
        var video = resourceClass("dev/tapetum/shaders/gui/TapetumVideoSettingsScreen");
        require(video.superName.equals("net/minecraft/client/gui/screens/options/VideoSettingsScreen"),
            "Native video options, GPU warnings and cleanup are retained");
        for (String screen : Set.of("ShaderPackScreen", "TapetumVideoSettingsScreen", "ShaderPackOptionsScreen")) {
            var node = resourceClass("dev/tapetum/shaders/gui/" + screen);
            require(node.methods.stream().anyMatch(m -> m.name.equals("extractBackground") && m.desc.equals(guiDescriptor)),
                "Screen owns its transparent background: " + screen);
            require(node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .noneMatch(i -> i instanceof MethodInsnNode call && Set.of("extractTransparentBackground", "extractBlurredBackground")
                    .contains(call.name)), "No stacked darkness/blur: " + screen);
        }
        String icon = metadata.get("icon").getAsString();
        require(icon.equals("assets/tapetumshaders/textures/gui/sprites/logo.png"), "Supplied logo registered in metadata");
        byte[] png = read(mod, icon);
        require(png.length > 100 && png[0] == (byte) 137 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "Logo PNG packaged");
        var renderer = resourceClass("dev/tapetum/shaders/gui/TransparentWidgets");
        require(renderer.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
            .noneMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/OptionInstance")
                && call.name.equals("set")), "Skin paints values without mutating native options");
        var options = resourceClass("dev/tapetum/shaders/gui/ShaderPackOptionsScreen");
        require(options.fields.stream().anyMatch(field -> field.name.equals("menu")
            && field.desc.equals("Ldev/tapetum/shaders/shaderpack/ShaderPackMenu;")),
            "Pack settings retain author-defined menus after the archive closes");
        require(options.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
            .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("dev/tapetum/shaders/shaderpack/ShaderPackMenu")
                && call.name.equals("profile")), "Profile controls use real validated pack values");
        var packs = resourceClass("dev/tapetum/shaders/gui/ShaderPackListWidget");
        require(packs.methods.stream().filter(m -> m.name.equals("setSelected"))
            .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
            .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals("java/lang/Runnable") && call.name.equals("run")),
            "Keyboard and mouse selection both update the settings action");
        for (var field : renderer.fields) {
            if (Set.of("PANEL", "ROW", "SHEET", "HOVER").contains(field.name)) {
                int alpha = ((Integer) field.value) >>> 24;
                require(alpha >= 0x50 && alpha <= 0x80, "Reference menu opacity is bounded: " + field.name);
            }
        }
        require(options.methods.stream().filter(m -> m.name.equals("apply"))
            .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
            .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("reload")),
            "Applying pack settings reaches the real pipeline reload");
        require(options.methods.stream().filter(m -> m.name.equals("onClose"))
            .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
            .noneMatch(i -> i instanceof MethodInsnNode call && Set.of("save", "reload", "setPackOptions").contains(call.name)),
            "Closing pack settings does not persist pending changes");
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
