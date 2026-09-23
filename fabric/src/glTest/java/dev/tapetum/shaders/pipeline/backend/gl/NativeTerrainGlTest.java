package dev.tapetum.shaders.pipeline.backend.gl;

import dev.tapetum.shaders.compat.GlStateManager;
import dev.tapetum.shaders.pipeline.NativeTerrainPipeline;
import dev.tapetum.shaders.shaderpack.glsl.GbufferVertexAdapter;
import dev.tapetum.shaders.shaderpack.glsl.NativeTerrainVertexAdapter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Exercises the production VAO and inherited inputs, not a substitute fullscreen shader. */
final class NativeTerrainGlTest {
    private static final String PACK_VERTEX = """
        #version 330 core
        out vec4 tint;
        out vec2 uv;
        out vec2 light;
        void main() {
            gl_Position = ftransform();
            tint = gl_Color;
            uv = gl_MultiTexCoord0.xy;
            light = gl_MultiTexCoord1.xy / 256.0;
        }
        """;
    private static final String PACK_FRAGMENT = """
        #version 330 core
        in vec4 tint;
        in vec2 uv;
        in vec2 light;
        uniform sampler2D gtexture, lightmap;
        out vec4 color;
        void main() {
            color = texture(gtexture, uv) * texture(lightmap, light) * tint;
            if (color.a < 0.1) discard;
        }
        """;
    static void run() throws Exception {
        int oldDepthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean oldDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try (GlRenderState state = GlRenderState.capture(); MemoryStack stack = MemoryStack.stackPush();
                NativeTerrainMesh mesh = new NativeTerrainMesh(); RenderTargets targets = new RenderTargets();
                GlProgram game = GlProgram.link("native block fixture", gameVertex(), """
                    #version 330 core
                    uniform sampler2D Sampler0, Sampler2;
                    out vec4 color;
                    void main() { color = texture(Sampler0, vec2(0.5)) * texture(Sampler2, vec2(0.5)); }
                    """);
                GlProgram pack = GlProgram.link("native terrain fixture",
                    NativeTerrainVertexAdapter.adapt(GbufferVertexAdapter.adapt(PACK_VERTEX)),
                    PACK_FRAGMENT, NativeTerrainMesh.ATTRIBUTES)) {
            state.prepareForFullscreen();
            NativeTerrainPipeline.validateInputs(pack);
            game.use();
            int nativeProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int chunkBlock = GL31.glGetUniformBlockIndex(nativeProgram, "ChunkSection");
            int globalsBlock = GL31.glGetUniformBlockIndex(nativeProgram, "Globals");
            GL31.glUniformBlockBinding(nativeProgram, chunkBlock, 5);
            GL31.glUniformBlockBinding(nativeProgram, globalsBlock, 6);
            int chunk = GL15.glGenBuffers();
            int globals = GL15.glGenBuffers();
            int vbo = GL15.glGenBuffers();
            int ebo = GL15.glGenBuffers();
            int depth = GL11.glGenTextures();
            int atlas = texture(255, 128, 64, 255);
            int light = texture(128, 255, 255, 255);
                int previousVao = GlStateManager._glGenVertexArrays();
            int oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int oldUniform = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
            try {
                ByteBuffer section = stack.calloc(96);
                new Matrix4f().get(0, section);
                section.putInt(80, 30_000_001);
                section.putInt(84, 0);
                section.putInt(88, 0);
                int sectionOffset = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
                ByteBuffer sectionBuffer = stack.calloc(sectionOffset + section.capacity());
                sectionBuffer.position(sectionOffset).put(section).flip();
                uploadUbo(chunk, 5, sectionBuffer);
                GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, 5, chunk, sectionOffset, 96);
                ByteBuffer globalData = stack.calloc(64);
                globalData.putInt(0, 30_000_000);
                globalData.putFloat(16, -0.5f);
                uploadUbo(globals, 6, globalData);
                GlStateManager._glBindVertexArray(previousVao);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, new short[] {0, 0, 0, 0, 1, 2, 2, 3, 0}, GL15.GL_STATIC_DRAW);
                targets.resize(8, 8);
                GlProgram.bindTexture(7, depth);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, 8, 8, 0,
                    GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                game.use();
                game.bindSampler("Sampler0", 0, atlas);
                game.bindSampler("Sampler2", 3, light);
                pack.use();
                NativeTerrainPipeline.inheritInputs(pack, nativeProgram);
                pack.setUniform("tapetum_ProjectionMatrix", new Matrix4f());
                try (FramebufferBindings _ = targets.bindForWriting(List.of(0))) {
                    GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL11.GL_TEXTURE_2D, depth, 0);
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthFunc(GL11.GL_LESS);
                    GL11.glDepthMask(true);
                    GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {0, 0, 0, 1});
                    GL30.glClearBufferfv(GL11.GL_DEPTH, 0, new float[] {1});
                    draw(mesh, vbo, ebo, stack, 0.0f, 255, 255, 255, 255);
                    // A farther red surface must not overwrite the first surface.
                    draw(mesh, vbo, ebo, stack, 0.5f, 255, 0, 0, 255);
                    // A nearer transparent texel must not write color OR depth.
                    draw(mesh, vbo, ebo, stack, -0.5f, 0, 255, 0, 0);
                    equal(previousVao, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), "VAO restored");
                    equal(vbo, GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), "VBO restored");
                    equal(ebo, GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING), "EBO restored");
                    float[] sampledDepth = new float[1];
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                    GL11.glReadPixels(4, 4, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, sampledDepth);
                    if (Math.abs(sampledDepth[0] - 0.5f) > 0.0001f) throw new AssertionError("Incorrect terrain depth " + sampledDepth[0]);
                    ByteBuffer sampledColor = stack.malloc(4);
                    GL11.glReadPixels(4, 4, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, sampledColor);
                    pixel(new int[] {Byte.toUnsignedInt(sampledColor.get(0)), Byte.toUnsignedInt(sampledColor.get(1)),
                        Byte.toUnsignedInt(sampledColor.get(2)), Byte.toUnsignedInt(sampledColor.get(3))}, 128, 128, 64);
                    // Large meshes use 32-bit indices. Preserve firstIndex/baseVertex on this path too.
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, new int[] {0, 0, 0, 0, 1, 2, 2, 3, 0}, GL15.GL_STATIC_DRAW);
                    GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {0, 0, 0, 1});
                    GL30.glClearBufferfv(GL11.GL_DEPTH, 0, new float[] {1});
                    uploadVertices(vbo, stack, 0, 255, 255, 255, 255);
                    mesh.draw(vbo, ebo, GL11.GL_UNSIGNED_INT, 3, 6, 4);
                    GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
                }
                targets.flip(0);
                pixel(targets.samplePixel(targets.readTexture(0), 4, 4), 128, 128, 64);
                pixel(targets.samplePixel(targets.readTexture(0), 0, 4), 0, 0, 0);
                equal(chunk, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, 5), "chunk UBO unchanged");
                equal(sectionOffset, (int) GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_START, 5), "section UBO offset unchanged");
                equal(globals, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, 6), "globals UBO unchanged");
            } finally {
                GlStateManager._glBindVertexArray(0);
                GL30.glDeleteVertexArrays(previousVao);
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray);
                GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 5, 0);
                GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 6, 0);
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, oldUniform);
                GL15.glDeleteBuffers(chunk);
                GL15.glDeleteBuffers(globals);
                GL15.glDeleteBuffers(vbo);
                GL15.glDeleteBuffers(ebo);
                GlStateManager._deleteTexture(depth);
                GlStateManager._deleteTexture(atlas);
                GlStateManager._deleteTexture(light);
            }
        } finally {
            GL11.glDepthFunc(oldDepthFunction);
            GL11.glDepthMask(oldDepthMask);
        }
        rejectsMissingInputs();
    }

    private static String gameVertex() throws IOException {
        StringBuilder source = new StringBuilder("#version 330 core\n");
        for (String name : List.of("chunksection", "globals")) {
            String path = "assets/minecraft/shaders/include/" + name + ".glsl";
            try (var stream = NativeTerrainGlTest.class.getClassLoader().getResourceAsStream(path)) {
                if (stream == null) throw new IOException("Missing native GLSL resource " + path);
                new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.stripLeading().startsWith("#version"))
                    .forEach(line -> source.append(line).append('\n'));
            }
        }
        return source + "void main() { gl_Position = ModelViewMat * "
            + "vec4(vec3(ChunkPosition - CameraBlockPos) + CameraOffset, 1.0); }\n";
    }

    private static void draw(NativeTerrainMesh mesh, int vbo, int ebo, MemoryStack stack, float z,
            int r, int g, int b, int a) {
        uploadVertices(vbo, stack, z, r, g, b, a);
        mesh.draw(vbo, ebo, GL11.GL_UNSIGNED_SHORT, 3, 6, 4);
    }

    private static void uploadVertices(int vbo, MemoryStack stack, float z, int r, int g, int b, int a) {
        ByteBuffer data = stack.calloc(8 * 28);
        for (int i = 0; i < 4; i++) {
            int offset = (i + 4) * 28;
            data.putFloat(offset, i == 0 || i == 3 ? -1f : 0f);
            data.putFloat(offset + 4, i < 2 ? -0.5f : 0.5f);
            data.putFloat(offset + 8, z);
            data.put(offset + 12, (byte) r).put(offset + 13, (byte) g).put(offset + 14, (byte) b).put(offset + 15, (byte) a);
            data.putFloat(offset + 16, 0.5f).putFloat(offset + 20, 0.5f);
            data.putShort(offset + 24, (short) 128).putShort(offset + 26, (short) 128);
        }
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
    }

    private static void uploadUbo(int id, int binding, ByteBuffer data) {
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, id);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, data, GL15.GL_STATIC_DRAW);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, id);
    }

    private static int texture(int r, int g, int b, int a) {
        int texture = GlStateManager._genTexture();
        GlProgram.bindTexture(7, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pixel = stack.bytes((byte) r, (byte) g, (byte) b, (byte) a);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        }
        return texture;
    }

    private static void rejectsMissingInputs() throws Exception {
        for (String missing : new String[] {"in vec3 tapetum_Normal;", "in vec4 mc_Entity;"}) {
            String name = missing.contains("Normal") ? "tapetum_Normal" : "mc_Entity";
            try (GlProgram invalid = GlProgram.link("missing " + name, "#version 330 core\n" + missing
                    + "\nvoid main() { gl_Position = vec4(" + name + ".xyz, 1.0); }",
                    "#version 330 core\nout vec4 color; void main() { color = vec4(1.0); }")) {
                try {
                    NativeTerrainPipeline.validateInputs(invalid);
                    throw new AssertionError("Accepted missing input " + name);
                } catch (IOException expected) {
                    if (!expected.getMessage().contains(name)) throw expected;
                }
            }
        }
    }

    private static void equal(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + actual + " != " + expected);
    }

    private static void pixel(int[] actual, int r, int g, int b) {
        if (actual == null || Math.abs(actual[0] - r) > 1 || Math.abs(actual[1] - g) > 1
                || Math.abs(actual[2] - b) > 1 || actual[3] != 255) {
            throw new AssertionError("Native terrain pixel: " + java.util.Arrays.toString(actual));
        }
    }
}
