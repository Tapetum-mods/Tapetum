package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.tapetum.shaders.pipeline.GbufferCompileAudit;
import dev.tapetum.shaders.shaderpack.ShaderDimension;
import dev.tapetum.shaders.shaderpack.ShaderpackManager;
import dev.tapetum.shaders.shaderpack.glsl.GbufferVertexAdapter;
import dev.tapetum.shaders.shaderpack.glsl.ShaderMacros;
import dev.tapetum.shaders.shaderpack.glsl.SodiumTerrainInputs;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

/** Tests the adapter against Sodium's real encoder, not a second copy of its packing algorithm. */
final class SodiumTerrainGlTest {
    private SodiumTerrainGlTest() {}

    static void pixels() throws Exception {
        String vertex = GbufferVertexAdapter.adaptForSodiumTerrain("""
            #version 150
            out vec4 color;
            out vec2 uv;
            out vec2 light;
            out vec3 position;
            void main() {
                gl_Position = ftransform();
                color = gl_Color;
                uv = gl_MultiTexCoord0.xy;
                light = gl_MultiTexCoord1.xy / 240.0;
                position = (gl_ModelViewMatrix * gl_Vertex).xyz;
            }
            """);
        String fragment = """
            #version 330 core
            in vec4 color;
            in vec2 uv;
            in vec2 light;
            in vec3 position;
            layout(location=0) out vec4 albedo;
            layout(location=1) out vec4 coordinates;
            layout(location=2) out vec4 geometry;
            void main() {
                albedo = color;
                coordinates = vec4(uv, light);
                geometry = vec4(position, 1.0);
            }
            """;

        int originalVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int vao = GlStateManager._glGenVertexArrays();
        int vbo = GlStateManager._glGenBuffers();
        try (GlRenderState state = GlRenderState.capture(); RenderTargets targets = new RenderTargets();
                GlProgram program = GlProgram.link("Sodium terrain pixels", vertex, fragment,
                    SodiumTerrainInputs.ATTRIBUTE_BINDINGS);
                MemoryStack stack = MemoryStack.stackPush()) {
            state.prepareForFullscreen();
            targets.setBufferCount(3);
            targets.resize(8, 8);
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            // Compact mesh ABI: two uint positions, ABGR bytes, two ushort UVs, four data bytes.
            GL30.glVertexAttribIPointer(0, 2, GL11.GL_UNSIGNED_INT, 20, 0);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, 20, 8);
            GL30.glVertexAttribIPointer(2, 2, GL11.GL_UNSIGNED_SHORT, 20, 12);
            GL30.glVertexAttribIPointer(3, 4, GL11.GL_UNSIGNED_BYTE, 20, 16);
            for (int i = 0; i < 4; i++) GL20.glEnableVertexAttribArray(i);

            ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
            float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < 4; i++) {
                var v = vertices[i];
                v.x = corners[i][0]; v.y = corners[i][1]; v.z = 0.25f;
                v.u = corners[i][0]; v.v = corners[i][1];
                v.color = 0xffc08040;
                v.ao = 1.0f;
                v.light = (240 << 16) | 80;
            }
            ByteBuffer mesh = stack.malloc(4 * 20);
            var encoder = ChunkMeshFormats.COMPACT.getEncoder();
            program.use();
            program.setUniform("tapetum_ProjectionMatrix", new Matrix4f().ortho(0, 1, 0, 1, -1, 1));
            program.setUniform("tapetum_TexCoordShrink", 1.0f / 32768.0f);

            // Every section address in a Sodium region; wrong axis order moves the quad off screen.
            for (int section = 0; section < 256; section++) {
                float x = section % 2 == 0 ? -7.5f : 18.125f;
                float y = section % 3 == 0 ? -4.75f : 20.5f;
                float z = section % 5 == 0 ? -7.25f : 22.75f;
                for (int i = 0; i < 4; i++) {
                    vertices[i].x = corners[i][0] + x;
                    vertices[i].y = corners[i][1] + y;
                    vertices[i].z = z + 0.25f;
                }
                program.setUniform("tapetum_ModelViewMatrix", new Matrix4f().translation(-x, -y, -z));
                long start = MemoryUtil.memAddress(mesh);
                long end = encoder.write(start, 0, vertices, section);
                if (end - start != 80) throw new AssertionError("Unexpected Sodium vertex stride");
                GlStateManager._glBufferData(GL15.GL_ARRAY_BUFFER, mesh, GL15.GL_STREAM_DRAW);
                program.setUniform("tapetum_RegionOffset", (float) -(section / 32 * 16),
                    (float) -(section % 4 * 16), (float) -((section / 4) % 8 * 16));
                try (FramebufferBindings _ = targets.bindForWriting(List.of(0, 1, 2))) {
                    GL11.glClearColor(0, 0, 0, 0);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                    GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, 0, 4);
                }
                for (int i = 0; i < 3; i++) targets.flip(i);
                expect(targets.samplePixel(targets.readTexture(0), 4, 4), 64, 128, 192, 255);
                expect(targets.samplePixel(targets.readTexture(1), 4, 4), 143, 143, 85, 255);
                expect(targets.samplePixel(targets.readTexture(2), 4, 4), 143, 143, 64, 255);
            }
        } finally {
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, originalVbo);
            GlStateManager._glDeleteBuffers(vbo);
            GL30.glDeleteVertexArrays(vao);
        }
    }

    static void privatePack() throws Exception {
        Path root = Files.createTempDirectory(Path.of("."), "private-shaderpack-");
        try {
            Path shaders = Files.createDirectories(root.resolve("test-pack/shaders"));
            Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
                #version 150
                void main() { gl_Position = ftransform(); }
                """);
            Files.writeString(shaders.resolve("gbuffers_basic.fsh"), """
                #version 150
                out vec4 color;
                void main() { color = vec4(0.25, 0.5, 0.75, 1.0); }
                """);
            // An incomplete terrain pair must inherit the complete basic pair, not hide it.
            Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), "deliberately invalid, no vertex pair");
            ShaderpackManager manager = new ShaderpackManager(root);
            try (var pack = manager.load("test-pack").orElseThrow()) {
                var macros = new ShaderMacros(260102, ShaderMacros.OperatingSystem.MAC, ShaderMacros.GlVendor.APPLE);
                var result = GbufferCompileAudit.run(pack, macros, ShaderDimension.OVERWORLD);
                if (result.compiled() != 24 || !result.failed().isEmpty()) {
                    throw new AssertionError("GLSL 150 private pack / incomplete-pair fallback: " + result);
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void installedPacks(Path directory) throws Exception {
        ShaderpackManager manager = new ShaderpackManager(directory);
        manager.refresh();
        if (manager.getAvailablePacks().isEmpty()) throw new AssertionError("No shaderpacks in " + directory);
        ShaderMacros macros = new ShaderMacros(
            ShaderMacros.encodeMinecraftVersion(System.getProperty("tapetum.test.minecraftVersion")),
            ShaderMacros.OperatingSystem.detect(System.getProperty("os.name")),
            ShaderMacros.GlVendor.detect(GL11.glGetString(GL11.GL_VENDOR)));
        for (String name : manager.getAvailablePacks()) {
            try (var pack = manager.load(name).orElseThrow()) {
                for (ShaderDimension dimension : ShaderDimension.values()) {
                    var result = GbufferCompileAudit.run(pack, macros, dimension);
                    if (!result.failed().isEmpty() || result.compiled() == 0) {
                        throw new AssertionError(name + " / " + dimension + ": " + result);
                    }
                }
            }
        }
    }

    private static void expect(int[] pixel, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(pixel[i] - expected[i]) > 1) {
                throw new AssertionError("channel " + i + ": expected " + expected[i] + ", got " + pixel[i]);
            }
        }
    }
}
