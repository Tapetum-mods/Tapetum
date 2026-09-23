package dev.tapetum.shaders.shaderpack;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SimpleTerrainSourcesTest {
    private static final String VERTEX = "#version 120\nvoid main() { gl_Position = ftransform(); }\n";
    private static final String FRAGMENT = "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n";

    private Path fixture(Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("pack/shaders"));
        Files.writeString(root.resolve("gbuffers_terrain.vsh"), VERTEX);
        Files.writeString(root.resolve("gbuffers_terrain.fsh"), FRAGMENT);
        return root;
    }

    @Test void resolvesAndAdaptsOnePairForAllTerrainLayers(@TempDir Path dir) throws Exception {
        Path root = fixture(dir);
        try (var pack = new ShaderpackManager(dir).load("pack").orElseThrow()) {
            var sources = SimpleTerrainSources.read(pack, null, ShaderDimension.OVERWORLD);
            assertEquals(3, sources.size());
            var solid = sources.get(GbufferProgram.TERRAIN_SOLID);
            assertSame(solid, sources.get(GbufferProgram.TERRAIN_CUTOUT));
            assertSame(solid, sources.get(GbufferProgram.WATER));
            assertTrue(solid.vertex().contains("uniform mat4 tapetum_ModelViewMatrix;"));
            assertTrue(solid.fragment().contains("tapetum_FragColor"));
            assertEquals(VERTEX, Files.readString(root.resolve("gbuffers_terrain.vsh")));
        }
    }

    @Test void usesDimensionSpecificCutoutPair(@TempDir Path dir) throws Exception {
        Path root = fixture(dir);
        Path nether = Files.createDirectories(root.resolve("world-1"));
        Files.writeString(nether.resolve("gbuffers_terrain_cutout.vsh"), VERTEX);
        Files.writeString(nether.resolve("gbuffers_terrain_cutout.fsh"), FRAGMENT.replace("1.0", "0.5"));
        try (var pack = new ShaderpackManager(dir).load("pack").orElseThrow()) {
            var sources = SimpleTerrainSources.read(pack, null, ShaderDimension.NETHER);
            assertEquals(GbufferProgram.TERRAIN_CUTOUT, sources.get(GbufferProgram.TERRAIN_CUTOUT).program());
            assertTrue(sources.get(GbufferProgram.TERRAIN_CUTOUT).fragment().contains("vec4(0.5)"));
            assertEquals(GbufferProgram.TERRAIN, sources.get(GbufferProgram.TERRAIN_SOLID).program());
        }
    }

    @Test void rejectsUnsupportedProgramsBeforeGpuWork(@TempDir Path dir) throws Exception {
        Path root = fixture(dir);
        for (String file : new String[] {"shadow.vsh", "gbuffers_terrain.gsh", "setup.csh", "gbuffers_entities.fsh", "final.fsh"}) {
            Files.writeString(root.resolve(file), VERTEX);
            try (var pack = new ShaderpackManager(dir).load("pack").orElseThrow()) {
                assertThrows(IOException.class, () -> SimpleTerrainSources.read(pack, null, ShaderDimension.OVERWORLD));
            } finally {
                Files.delete(root.resolve(file));
            }
        }
    }

    @Test void rejectsIncompletePairsAndMaterialRequirements(@TempDir Path dir) throws Exception {
        Path root = fixture(dir);
        Files.delete(root.resolve("gbuffers_terrain.vsh"));
        try (var pack = new ShaderpackManager(dir).load("pack").orElseThrow()) {
            assertThrows(IOException.class, () -> SimpleTerrainSources.read(pack, null, ShaderDimension.OVERWORLD));
        }
        Files.writeString(root.resolve("gbuffers_terrain.vsh"), VERTEX);
        Files.writeString(root.resolve("block.properties"), "block.100=minecraft:stone\n");
        try (var pack = new ShaderpackManager(dir).load("pack").orElseThrow()) {
            assertThrows(IOException.class, () -> SimpleTerrainSources.read(pack, null, ShaderDimension.OVERWORLD));
        }
    }

    @Test void rejectsMrtAndFloatingPointTargetRequirements(@TempDir Path dir) throws Exception {
        Path root = fixture(dir);
        for (String directive : new String[] {"/* DRAWBUFFERS:01 */", "/* RENDERTARGETS:2 */",
                "const int colortex0Format = RGBA16F;"}) {
            Files.writeString(root.resolve("gbuffers_terrain.fsh"), FRAGMENT + directive);
            try (var pack = new ShaderpackManager(dir).load("pack").orElseThrow()) {
                assertThrows(IOException.class, () -> SimpleTerrainSources.read(pack, null, ShaderDimension.OVERWORLD));
            }
        }
    }
}
