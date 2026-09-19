package dev.tapetum.shaders.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;

/** Reads classfiles only: no game entrypoint, class initialization or graphics context. */
public final class EmbeddedEngineContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        String version = args[2];
        try (var mod = new JarFile(args[0]); var iris = new JarFile(args[1])) {
            JsonObject metadata = json(mod, "fabric.mod.json");
            String nestedPath = "META-INF/jars/iris-fabric-1.11.4+mc" + version + ".jar";
            require(Arrays.equals(read(mod, nestedPath), Files.readAllBytes(Path.of(args[1]))),
                "Nested Iris must exactly match the pinned official binary");
            require(metadata.getAsJsonArray("jars").asList().stream()
                .anyMatch(e -> nestedPath.equals(e.getAsJsonObject().get("file").getAsString())),
                "Fabric must discover embedded Iris");
            require(json(iris, "fabric.mod.json").get("version").getAsString().equals(
                metadata.getAsJsonObject("custom").get("tapetum:irisVersion").getAsString()),
                "Engine compatibility guard version");
            VersionPredicate sodium = VersionPredicate.parse(metadata.getAsJsonObject("depends").get("sodium").getAsString());
            require(sodium.test(Version.parse("0.9.2+mc" + version)), "Accept Sodium 0.9.2");
            require(!sodium.test(Version.parse("0.9.1")), "Reject older Sodium");
            require(!sodium.test(Version.parse("0.10.0")), "Reject unverified Sodium major API change");
            for (String resource : new String[]{"META-INF/licenses/iris/NOTICE.md",
                    "META-INF/licenses/iris/Iris-LGPL-3.0.txt", "META-INF/licenses/iris/GPL-3.0.txt",
                    "META-INF/sources/iris-sources-" + version + ".zip"}) {
                require(read(mod, resource).length > 100, "Bundled license/source: " + resource);
            }
            require(mod.stream().noneMatch(e -> e.getName().contains("/smoke/")
                || e.getName().contains("/contractTest/") || e.getName().startsWith("META-INF/jars/sodium")),
                "Do not ship test harnesses or a duplicate Sodium");
            Set<String> mixins = new HashSet<>();
            json(mod, "tapetumshaders.mixins.json").getAsJsonArray("client")
                .forEach(e -> mixins.add(e.getAsString()));
            require(mixins.equals(Set.of("MixinIrisKeybinds", "IrisShaderPackScreenAccess", "MixinIrisFeatureValidation")),
                "Only embedded-engine mixins, never the old full-screen renderer");
            for (String mixin : mixins) {
                require(mod.getJarEntry("dev/tapetum/shaders/mixin/" + mixin + ".class") != null,
                    "Mixin class packaged: " + mixin);
            }
            ClassNode engine = node(iris, "net/irisshaders/iris/Iris");
            String screenOwner = version.equals("26.2") ? "net/minecraft/client/gui/Gui" : "net/minecraft/client/Minecraft";
            require(calls(engine, "handleKeybinds", screenOwner, "setScreen", "(Lnet/minecraft/client/gui/screens/Screen;)V"),
                "Version-specific O key injection target");
            require(node(iris, "net/irisshaders/iris/gui/screen/ShaderPackScreen").fields.stream()
                .anyMatch(f -> f.name.equals("optionMenuOpen") && f.desc.equals("Z")), "Native shader options accessor");
            require(calls(node(iris, "net/irisshaders/iris/shaderpack/ShaderPack"), "<init>",
                "net/irisshaders/iris/shaderpack/properties/ShaderProperties", "getRequiredFeatureFlags", "()Ljava/util/List;"),
                "Unsupported-feature protection injection target");
            // Every Iris method/field referenced by the bridge must exist in the exact shipped engine.
            ClassNode bridge = node(mod, "dev/tapetum/shaders/compat/iris/IrisRenderingBridge");
            for (var method : bridge.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.startsWith("net/irisshaders/iris/")) {
                        require(node(iris, call.owner).methods.stream().anyMatch(m -> m.name.equals(call.name)
                            && m.desc.equals(call.desc) && (m.access & Opcodes.ACC_PUBLIC) != 0),
                            "Public engine API: " + call.owner + "." + call.name);
                    }
                }
            }
        }
        System.out.println("Embedded engine contract " + version + ": " + checks + " checks passed (headless)");
    }

    private static boolean calls(ClassNode type, String method, String owner, String name, String descriptor) {
        for (var candidate : type.methods) {
            if (!candidate.name.equals(method)) continue;
            for (var instruction : candidate.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                        && call.name.equals(name) && call.desc.equals(descriptor)) return true;
            }
        }
        return false;
    }

    private static ClassNode node(JarFile jar, String name) throws IOException {
        var result = new ClassNode();
        new ClassReader(read(jar, name + ".class")).accept(result, 0);
        return result;
    }

    private static JsonObject json(JarFile jar, String name) throws IOException {
        return JsonParser.parseString(new String(read(jar, name), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null) throw new AssertionError("Missing archive entry: " + name);
        try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
    }

    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
}
