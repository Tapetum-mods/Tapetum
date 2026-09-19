package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL11;

/**
 * A single triangle, sized to cover the whole clip-space area, with no vertex buffer at all — its
 * three positions are computed in the vertex shader from {@code gl_VertexID}. Core-profile OpenGL
 * still requires a vertex array object to be bound for any draw call even when nothing is being
 * pulled from a buffer, so this just holds that otherwise-empty VAO.
 */
public final class FullScreenTriangle implements AutoCloseable {
	private int vertexArrayId;

	public FullScreenTriangle() {
		this.vertexArrayId = GlStateManager._glGenVertexArrays();
	}

	public void draw() {
		if (vertexArrayId == 0) throw new IllegalStateException("Triangle is closed");
		int previous = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		GlStateManager._glBindVertexArray(vertexArrayId);
		try {
			GlStateManager._drawArrays(GL30.GL_TRIANGLES, 0, 3);
		} finally {
			GlStateManager._glBindVertexArray(previous);
		}
	}

	@Override
	public void close() {
		if (vertexArrayId != 0) {
			if (GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) == vertexArrayId) {
				GlStateManager._glBindVertexArray(0);
			}
			GL30.glDeleteVertexArrays(vertexArrayId);
			vertexArrayId = 0;
		}
	}
}
