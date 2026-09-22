package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/** Isolates the screen-space chain from Minecraft's GL state, including exceptional exits. */
public final class GlRenderState implements AutoCloseable {
    private static final int[] CAPABILITIES = {
        GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST,
        GL11.GL_STENCIL_TEST, GL11.GL_COLOR_LOGIC_OP, GL30.GL_RASTERIZER_DISCARD
    };
    private static final int[] PIXEL_STORE = {
        GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS,
        GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS
    };

    private final FramebufferBindings framebuffers = FramebufferBindings.capture();
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
    private final int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int[] viewport = new int[4];
    private final int[] pixelStore = new int[PIXEL_STORE.length];
    private final boolean[] capabilities = new boolean[CAPABILITIES.length];
    private final int[] textures = new int[GlProgram.maxTextureUnits()];
    private final int[] samplers = new int[textures.length];
    private final boolean[] blend = new boolean[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
    private final boolean[][] masks = new boolean[blend.length][4];
    private boolean closed;

    private GlRenderState() {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        for (int i = 0; i < CAPABILITIES.length; i++) {
            capabilities[i] = GL11.glIsEnabled(CAPABILITIES[i]);
        }
        for (int i = 0; i < PIXEL_STORE.length; i++) pixelStore[i] = GL11.glGetInteger(PIXEL_STORE[i]);
        for (int i = 0; i < textures.length; i++) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + i);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            for (int i = 0; i < blend.length; i++) {
                blend[i] = GL30.glIsEnabledi(GL11.GL_BLEND, i);
                GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, i, mask);
                for (int c = 0; c < 4; c++) masks[i][c] = mask.get(c) != 0;
            }
        }
        GlStateManager._activeTexture(activeTexture);
    }

    public static GlRenderState capture() {
        return new GlRenderState();
    }

    public void prepareForFullscreen() {
        // These temporary toggles bypass the cache and are restored verbatim before returning.
        // No vanilla draws or cached state setters may run inside this screen-space section.
        for (int capability : CAPABILITIES) GL11.glDisable(capability);
        for (int i = 0; i < blend.length; i++) {
            GL30.glDisablei(GL11.GL_BLEND, i);
            GL30.glColorMaski(i, true, true, true, true);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int parameter : PIXEL_STORE) {
            GL11.glPixelStorei(parameter,
                parameter == GL11.GL_PACK_ALIGNMENT || parameter == GL11.GL_UNPACK_ALIGNMENT ? 1 : 0);
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < textures.length; i++) {
            GlProgram.bindTexture(i, textures[i]);
            GL33.glBindSampler(i, samplers[i]);
        }
        GlStateManager._activeTexture(activeTexture);
        GlStateManager._glUseProgram(program);
        org.lwjgl.opengl.GL30.glBindVertexArray(vao);
        framebuffers.close();
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        for (int i = 0; i < CAPABILITIES.length; i++) {
            if (capabilities[i]) GL11.glEnable(CAPABILITIES[i]);
            else GL11.glDisable(CAPABILITIES[i]);
        }
        for (int i = 0; i < blend.length; i++) {
            if (blend[i]) GL30.glEnablei(GL11.GL_BLEND, i);
            else GL30.glDisablei(GL11.GL_BLEND, i);
            GL30.glColorMaski(i, masks[i][0], masks[i][1], masks[i][2], masks[i][3]);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        for (int i = 0; i < PIXEL_STORE.length; i++) GL11.glPixelStorei(PIXEL_STORE[i], pixelStore[i]);
    }
}
