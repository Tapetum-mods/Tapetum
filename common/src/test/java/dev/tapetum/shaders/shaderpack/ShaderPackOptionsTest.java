package dev.tapetum.shaders.shaderpack;

import dev.tapetum.shaders.config.TapetumConfig;
import dev.tapetum.shaders.shaderpack.glsl.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPackOptionsTest {
    @Test void readsRangesAndSwitchesButNotIncludeGuards() {
        var options = ShaderPackOptions.parse(List.of("""
            #ifndef HEADER
            #define HEADER
            #endif
            #define BLOOM // Bloom
            // #define VIGNETTE // Vignette
            #ifdef BLOOM
            #endif
            #ifndef VIGNETTE
            #endif
            #define QUALITY 2 // Quality [0 1 3]
            #define INTERNAL(x) x
            """));
        assertFalse(options.entries().stream().anyMatch(o -> o.name().equals("HEADER")));
        assertEquals(List.of("false", "true"), options.entries().stream().filter(o -> o.name().equals("BLOOM")).findFirst().orElseThrow().values());
        assertEquals(List.of("0", "1", "3", "2"), options.entries().stream().filter(o -> o.name().equals("QUALITY")).findFirst().orElseThrow().values());
        assertFalse(options.entries().stream().anyMatch(o -> o.name().equals("INTERNAL")));
    }

    @Test void ignoresBlockCommentsAndRejectsConflictingDefaults() {
        var options = ShaderPackOptions.parse(List.of("""
            /*
            #define HIDDEN 1 // [1 2]
            */
            #define QUALITY 1 // [1 2]
            """, "#define QUALITY 2 // [1 2]\n"));
        assertTrue(options.entries().isEmpty());
    }

    @Test void changesDeclarationsNotUsesAndRejectsInjectedValues() {
        String source = """
            #define QUALITY 1 // [1 2 3]
            // #define BLOOM // Bloom
            #ifdef BLOOM
            float brightness = QUALITY;
            #endif
            """;
        var options = ShaderPackOptions.parse(List.of(source));
        var result = options.apply(source, Map.of("QUALITY", "3", "BLOOM", "true"));
        assertTrue(result.contains("#define QUALITY 3\n"));
        assertTrue(result.contains("#define BLOOM\n"));
        assertTrue(result.contains("float brightness = QUALITY;"));
        assertEquals(source.lines().count(), result.lines().count());
        assertEquals(source, options.apply(source, Map.of("QUALITY", "3\n#error injected", "UNKNOWN", "1")));
    }

    @Test void persistsPerPackAndResetsOnlyOnePack(@TempDir Path dir) throws Exception {
        var path = dir.resolve("config.properties");
        var config = new TapetumConfig(path);
        config.setPackOptions("Pack A.zip", Map.of("QUALITY", "3"));
        config.setPackOptions("Pack.A.zip", Map.of("QUALITY", "1"));
        config.save();
        var loaded = new TapetumConfig(path);
        loaded.load();
        assertEquals(Map.of("QUALITY", "3"), loaded.getPackOptions("Pack A.zip"));
        loaded.setPackOptions("Pack A.zip", Map.of());
        loaded.save();
        config.load();
        assertTrue(config.getPackOptions("Pack A.zip").isEmpty());
        assertEquals(Map.of("QUALITY", "1"), config.getPackOptions("Pack.A.zip"));
    }

    @Test void appliesToIncludedShaderBeforeCompatibilityPatching(@TempDir Path dir) throws Exception {
        Path shaders = Files.createDirectories(dir.resolve("Test/shaders"));
        Files.writeString(shaders.resolve("settings.glsl"), "#define EXPOSURE 1.0 // [0.5 1.0 2.0]\n");
        Files.writeString(shaders.resolve("final.fsh"),
            "#version 120\n#include \"settings.glsl\"\nvoid main() { gl_FragColor=vec4(EXPOSURE); }\n");
        var manager = new ShaderpackManager(dir);
        try (var pack = manager.load("Test").orElseThrow()) {
            assertEquals(1, pack.getOptions().entries().size());
            pack.setOptionValues(Map.of("EXPOSURE", "2.0"));
            var compiled = pack.readCompilableProgramSource("final.fsh", GlslCompatPatcher.Stage.FRAGMENT,
                ShaderDimension.OVERWORLD, null).orElseThrow();
            assertTrue(compiled.contains("#define EXPOSURE 2.0"));
            assertTrue(Files.readString(shaders.resolve("settings.glsl")).contains("EXPOSURE 1.0"));
        }
    }
}
