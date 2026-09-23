package dev.tapetum.shaders.pipeline.backend.gl;

import dev.tapetum.shaders.compat.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import dev.tapetum.shaders.pipeline.PipelineManager;
import dev.tapetum.shaders.pipeline.RenderingPipeline;

import java.util.ArrayList;
import java.util.List;

/** Runs on a real core-profile context; no Minecraft window, account or world is needed. */
public final class GlRegressionTest {
    private static final String VERTEX = """
        #version 330 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;
    private static final String FRAGMENT = """
        #version 330 core
        uniform sampler2D scene;
        out vec4 color;
        void main() { color = texture(scene, vec2(0.5)); }
        """;

    private GlRegressionTest() {}

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();
        try (GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err)) {
            GLFW.glfwSetErrorCallback(errorCallback);
            if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            long window = 0;
            try {
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
                window = GLFW.glfwCreateWindow(32, 32, "Tapetum GL regression", 0, 0);
                if (window == 0) throw new IllegalStateException("No OpenGL 4.1 context available");
                GLFW.glfwMakeContextCurrent(window);
                GL.createCapabilities();
                RenderSystem.initRenderThread();
                System.out.println("Driver: " + GL11.glGetString(GL11.GL_RENDERER)
                    + " / " + GL11.glGetString(GL11.GL_VERSION));
                run("split framebuffer restoration", GlRegressionTest::framebuffers, failures);
                run("high texture units", GlRegressionTest::highTextureUnits, failures);
                run("sampler isolation", GlRegressionTest::samplerIsolation, failures);
                run("VAO restoration", GlRegressionTest::vertexArray, failures);
                run("multi-pass pixels and resize", GlRegressionTest::pixels, failures);
                run("state restored after exception", GlRegressionTest::exceptionState, failures);
                run("invalid MRT rejected", GlRegressionTest::invalidOutputs, failures);
                run("float viewport uniforms", GlRegressionTest::viewportUniforms, failures);
                run("native terrain pixels, depth, cutouts and section transforms", NativeTerrainGlTest::run, failures);
                run("failed pipeline falls back once", GlRegressionTest::pipelineFailure, failures);
            } finally {
                if (window != 0) GLFW.glfwDestroyWindow(window);
                GLFW.glfwTerminate();
                GLFW.glfwSetErrorCallback(null);
            }
        }
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("All GL regressions passed");
    }

    private static void framebuffers() {
        int read = GlStateManager.glGenFramebuffers();
        int draw = GlStateManager.glGenFramebuffers();
        try (RenderTargets targets = new RenderTargets()) {
            targets.setBufferCount(2);
            targets.resize(8, 8);
            int texture = targets.readTexture(1);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            targets.captureSceneInto(0, texture);
            equal(read, GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), "read framebuffer");
            equal(draw, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), "draw framebuffer");
        } finally {
            GlStateManager._glDeleteFramebuffers(read);
            GlStateManager._glDeleteFramebuffers(draw);
        }
    }

    private static void highTextureUnits() throws Exception {
        try (GlProgram program = GlProgram.link("units", VERTEX, FRAGMENT)) {
            program.use();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            program.bindSampler("scene", 12, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            equal(GL13.GL_TEXTURE0, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), "cached active unit");
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlProgram.unbind();
        }
    }

    private static void samplerIsolation() throws Exception {
        int sampler = GL33.glGenSamplers();
        GL33.glBindSampler(0, sampler);
        try (GlProgram program = GlProgram.link("sampler", VERTEX, FRAGMENT)) {
            program.use();
            program.bindSampler("scene", 0, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            equal(0, GL11.glGetInteger(GL33.GL_SAMPLER_BINDING), "inherited sampler");
        } finally {
            GL33.glBindSampler(0, 0);
            GL33.glDeleteSamplers(sampler);
            GlProgram.unbind();
        }
    }

    private static void vertexArray() throws Exception {
        int vao = GlStateManager._glGenVertexArrays();
        try (FullScreenTriangle triangle = new FullScreenTriangle();
                GlProgram program = GlProgram.link("vao", VERTEX,
                    "#version 330 core\nout vec4 color; void main() { color = vec4(1.0); }")) {
            program.use();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GlStateManager._glBindVertexArray(vao);
            triangle.draw();
            equal(vao, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), "VAO after draw");
        } finally {
            GlStateManager._glBindVertexArray(0);
            GL30.glDeleteVertexArrays(vao);
            GlProgram.unbind();
        }
    }

    private static void equal(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static void pixels() throws Exception {
        try (RenderTargets targets = new RenderTargets(); RenderTargets destination = new RenderTargets();
                FullScreenTriangle triangle = new FullScreenTriangle();
                GlProgram seed = GlProgram.link("seed", VERTEX, """
                    #version 330 core
                    layout(location=0) out vec4 first;
                    layout(location=1) out vec4 second;
                    void main() {
                        first = vec4(0.25, 0.5, 0.75, 1.0);
                        second = vec4(0.75, 0.25, 0.5, 1.0);
                    }
                    """);
                GlProgram copy = GlProgram.link("copy", VERTEX, FRAGMENT)) {
            targets.setBufferCount(3);
            for (int size : new int[] {8, 8, 16, 4}) {
                try (GlRenderState state = GlRenderState.capture()) {
                    state.prepareForFullscreen();
                    targets.resize(size, size);
                    destination.resize(size, size);
                    try (FramebufferBindings _ = targets.bindForWriting(List.of(2, 0))) {
                        seed.use();
                        triangle.draw();
                    }
                    targets.flip(2);
                    targets.flip(0);
                    pixel(targets.samplePixel(targets.readTexture(0), 1, 1), 191, 64, 128);
                    pixel(targets.samplePixel(targets.readTexture(2), 1, 1), 64, 128, 191);
                    int sampler = GL33.glGenSamplers();
                    try {
                        // Default mipmap filtering would make these single-level textures incomplete.
                        GL33.glBindSampler(12, sampler);
                        try (FramebufferBindings _ = targets.bindForWriting(List.of(0))) {
                            copy.use();
                            copy.bindSampler("scene", 12, targets.readTexture(2));
                            triangle.draw();
                        }
                        targets.flip(0);
                        targets.presentTo(0, destination.readTexture(0));
                        pixel(destination.samplePixel(destination.readTexture(0), 1, 1), 64, 128, 191);
                        targets.captureSceneInto(1, destination.readTexture(0));
                        pixel(targets.samplePixel(targets.readTexture(1), 1, 1), 64, 128, 191);
                    } finally {
                        GL33.glBindSampler(12, 0);
                        GL33.glDeleteSamplers(sampler);
                    }
                }
            }
        }
    }

    private static void exceptionState() throws Exception {
        int texture = GlStateManager._genTexture();
        int sampler = GL33.glGenSamplers();
        try (GlProgram original = GlProgram.link("original", VERTEX, FRAGMENT)) {
            original.use();
            int expectedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GlProgram.bindTexture(12, texture);
            GL33.glBindSampler(12, sampler);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 3);
            GL11.glViewport(2, 3, 11, 13);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL30.glEnablei(GL11.GL_BLEND, 1);
            GL30.glColorMaski(1, false, true, false, true);
            try {
                try (GlRenderState state = GlRenderState.capture()) {
                    state.prepareForFullscreen();
                    equal(0, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? 1 : 0, "scissor during chain");
                    GlProgram.unbind();
                    GlProgram.bindTexture(12, 0);
                    GL33.glBindSampler(12, 0);
                    GL11.glViewport(0, 0, 1, 1);
                    throw new IllegalStateException("synthetic render failure");
                }
            } catch (IllegalStateException expected) {
                if (!"synthetic render failure".equals(expected.getMessage())) throw expected;
            }
            equal(GL13.GL_TEXTURE0 + 3, GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), "active texture");
            equal(expectedProgram, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), "program");
            equal(1, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? 1 : 0, "scissor restored");
            equal(1, GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0, "cull restored");
            equal(1, GL30.glIsEnabledi(GL11.GL_BLEND, 1) ? 1 : 0, "indexed blend");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var mask = stack.malloc(4);
                GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, 1, mask);
                equal(0, mask.get(0), "indexed red mask");
                equal(1, mask.get(1), "indexed green mask");
                equal(0, mask.get(2), "indexed blue mask");
                equal(1, mask.get(3), "indexed alpha mask");
            }
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            if (!java.util.Arrays.equals(new int[] {2, 3, 11, 13}, viewport)) {
                throw new AssertionError("viewport not restored");
            }
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 12);
            equal(texture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), "texture restored");
            equal(sampler, GL11.glGetInteger(GL33.GL_SAMPLER_BINDING), "sampler restored");
        } finally {
            GlProgram.bindTexture(12, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._deleteTexture(texture);
            GL33.glBindSampler(12, 0);
            GL33.glDeleteSamplers(sampler);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL30.glDisablei(GL11.GL_BLEND, 1);
            GL30.glColorMaski(1, true, true, true, true);
            GlProgram.unbind();
        }
    }

    private static void invalidOutputs() {
        try (RenderTargets targets = new RenderTargets()) {
            targets.resize(8, 8);
            FramebufferBindings before = FramebufferBindings.capture();
            for (List<Integer> invalid : List.of(List.of(1), List.of(-1), List.of(0, 0))) {
                try (FramebufferBindings _ = targets.bindForWriting(invalid)) {
                    throw new AssertionError("Accepted invalid outputs " + invalid);
                } catch (IllegalArgumentException expected) {
                    if (!before.equals(FramebufferBindings.capture())) {
                        throw new AssertionError("Invalid output changed framebuffer bindings");
                    }
                }
            }
        }
    }

    private static void pixel(int[] actual, int r, int g, int b) {
        if (actual == null || Math.abs(actual[0] - r) > 1 || Math.abs(actual[1] - g) > 1
                || Math.abs(actual[2] - b) > 1 || actual[3] != 255) {
            throw new AssertionError("Unexpected pixel " + java.util.Arrays.toString(actual));
        }
    }

    private static void viewportUniforms() throws Exception {
        try (GlProgram program = GlProgram.link("viewport", VERTEX, """
                #version 330 core
                uniform float viewWidth, viewHeight;
                out vec4 color;
                void main() { color = vec4(viewWidth, viewHeight, 0.0, 1.0); }
                """)) {
            program.use();
            int width = 1920;
            int height = 1080;
            program.setUniform("viewWidth", (float) width);
            program.setUniform("viewHeight", (float) height);
            int id = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            equal(width, (int) GL20.glGetUniformf(id, GL20.glGetUniformLocation(id, "viewWidth")), "width");
            equal(height, (int) GL20.glGetUniformf(id, GL20.glGetUniformLocation(id, "viewHeight")), "height");
        } finally {
            GlProgram.unbind();
        }
    }

    private static void pipelineFailure() throws Exception {
        PipelineManager manager = new PipelineManager();
        int[] calls = new int[2];
        RenderingPipeline failing = new RenderingPipeline() {
            public void beginLevelRendering() {}
            public void finalizeLevelRendering() {
                calls[0]++;
                throw new IllegalStateException("Expected regression-test failure");
            }
            public boolean isShaderPackActive() { return true; }
            public void destroy() { calls[1]++; }
        };
        var current = PipelineManager.class.getDeclaredField("current");
        current.setAccessible(true);
        current.set(manager, failing);
        manager.finalizeLevelRendering();
        manager.finalizeLevelRendering();
        equal(1, calls[0], "failed render attempts");
        equal(1, calls[1], "pipeline cleanup");
        equal(0, manager.getPipeline().isShaderPackActive() ? 1 : 0, "vanilla fallback");
    }

    private static void run(String name, CheckedRunnable test, List<String> failures) {
        try {
            test.run();
            equal(GL11.GL_NO_ERROR, GL11.glGetError(), "GL error");
            System.out.println("PASS: " + name);
        } catch (Exception | AssertionError e) {
            failures.add(name + ": " + e);
            System.out.println("FAIL: " + failures.getLast());
        } finally {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) { /* isolate test errors */ }
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }
}
