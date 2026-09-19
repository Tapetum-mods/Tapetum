package dev.tapetum.shaders.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;

/** Verifies that the production archive contains Tapetum's engine and no Iris runtime. */
public final class NativeEngineContractTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        try (var mod = new JarFile(args[0])) {
            JsonObject metadata = json(mod, "fabric.mod.json");
            String metadataText = new String(read(mod, "fabric.mod.json"), StandardCharsets.UTF_8);
            require(!metadataText.toLowerCase().contains("iris"), "No Iris metadata in fabric.mod.json");
            require(metadata.getAsJsonObject("depends").get("sodium") != null, "Sodium remains the rendering dependency");
            require(mod.getJarEntry("dev/tapetum/shaders/pipeline/ShaderEngine.class") != null,
                "Tapetum ShaderEngine is packaged");
            require(mod.getJarEntry("dev/tapetum/shaders/pipeline/PipelineManager.class") != null,
                "Tapetum PipelineManager is packaged");
            require(mod.stream().noneMatch(entry -> entry.getName().toLowerCase().contains("iris")),
                "Production archive contains no Iris files");
            JsonObject mixins = json(mod, "tapetumshaders.mixins.json");
            require(mixins.getAsJsonArray("client").size() == 1
                    && "MixinLevelRenderer".equals(mixins.getAsJsonArray("client").get(0).getAsString()),
                "Only the native Tapetum renderer hook is registered");
        }
        System.out.println("Native Tapetum engine contract: " + checks + " checks passed (headless)");
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
