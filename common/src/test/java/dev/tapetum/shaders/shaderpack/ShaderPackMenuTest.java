package dev.tapetum.shaders.shaderpack;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ShaderPackMenuTest {
    private static final ShaderPackOptions OPTIONS = ShaderPackOptions.parse(List.of("""
        #define BLOOM
        #ifdef BLOOM
        #endif
        #define QUALITY 2 // [1 2 3]
        const float shadowDistance = 128.0; // [64.0 128.0 192.0]
        """));

    @Test void readsPagesAndProfileInheritanceAndTranslations() {
        var menu = new ShaderPackMenu(OPTIONS, """
            screen=<profile> [LIGHTING] UNKNOWN <empty>
            screen.LIGHTING=BLOOM shadowDistance
            profile.Low=QUALITY:1 !BLOOM shadowDistance=64.0
            profile.High=profile.Low QUALITY:3 BLOOM
            """, "screen.LIGHTING=Lighting\noption.BLOOM=Bloom strength\n");
        assertEquals(2, menu.entries("").size());
        assertEquals("LIGHTING", menu.entries("").get(1).name());
        assertEquals(List.of("BLOOM", "shadowDistance"), menu.entries("LIGHTING").stream().map(ShaderPackMenu.Entry::name).toList());
        assertEquals(Map.of("QUALITY", "3", "BLOOM", "true", "shadowDistance", "64.0"), menu.profile("High"));
        assertEquals("Lighting", menu.label("screen.LIGHTING", "fallback"));
    }

    @Test void rejectsIncompleteCyclicOrInvalidProfiles() {
        var menu = new ShaderPackMenu(OPTIONS, """
            profile.A=profile.B
            profile.B=profile.A
            profile.Partial=QUALITY:1 !program.shadow
            profile.Invalid=QUALITY:999
            profile.Valid=QUALITY:1
            """, "");
        assertEquals(List.of("Valid"), menu.profiles());
        assertEquals(3, menu.entries("").size());
    }

    @Test void ignoresConditionalMenusInsteadOfChoosingTheLastBranch() {
        var menu = new ShaderPackMenu(OPTIONS, """
            screen=QUALITY
            #ifdef MC_OS_MAC
            screen=BLOOM
            #else
            screen=shadowDistance
            #endif
            """, "");
        assertEquals(List.of(new ShaderPackMenu.Entry(ShaderPackMenu.Kind.OPTION, "QUALITY")), menu.entries(""));
    }

    @Test void loadsPackLanguageWithEnglishFallback(@TempDir Path dir) throws Exception {
        Path shaders = Files.createDirectories(dir.resolve("Pack/shaders/lang"));
        Files.writeString(shaders.resolve("en_US.lang"), "screen.LIGHTING=Lighting\noption.QUALITY=Quality\n");
        Files.writeString(shaders.resolve("fr_FR.lang"), "screen.LIGHTING=Eclairage\n");
        Files.writeString(shaders.getParent().resolve("settings.glsl"), "#define QUALITY 2 // [1 2 3]\n");
        Files.writeString(shaders.getParent().resolve("shaders.properties"), "screen=[LIGHTING]\nscreen.LIGHTING=QUALITY\n");
        try (var pack = new ShaderpackManager(dir).load("Pack").orElseThrow()) {
            var menu = pack.getMenu("fr_fr");
            assertEquals("Eclairage", menu.label("screen.LIGHTING", "missing"));
            assertEquals("Quality", menu.label("option.QUALITY", "missing"));
            assertEquals("Lighting", pack.getMenu("../../bad").label("screen.LIGHTING", "missing"));
        }
    }
}
