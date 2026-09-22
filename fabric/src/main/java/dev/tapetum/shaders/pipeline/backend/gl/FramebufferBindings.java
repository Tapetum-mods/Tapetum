package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** Read and draw bindings are independent; GL_FRAMEBUFFER is not a valid getFrameBuffer query. */
public record FramebufferBindings(int read, int draw) implements AutoCloseable {
    public static FramebufferBindings capture() {
        return new FramebufferBindings(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
    }

    @Override
    public void close() {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }
}
