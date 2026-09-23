package dev.tapetum.shaders.pipeline.backend.gl;

import dev.tapetum.shaders.compat.GlStateManager;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** A private VAO over Minecraft's existing 28-byte BLOCK vertices and sorted index buffers. */
public final class NativeTerrainMesh implements AutoCloseable {
    public static final Map<String, Integer> ATTRIBUTES = Map.of(
        "tapetum_Position", 0, "tapetum_Color", 1, "tapetum_UV0", 2, "tapetum_UV1", 3);
    private int vao;

    public void draw(int vertices, int indices, int type, int firstIndex, int count, int baseVertex) {
        if (vertices <= 0 || indices <= 0 || firstIndex < 0 || count < 0 || baseVertex < 0
                || (type != GL11.GL_UNSIGNED_SHORT && type != GL11.GL_UNSIGNED_INT)) {
            throw new IllegalArgumentException("Invalid native terrain draw");
        }
        if (count == 0) return;
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            if (vao == 0) vao = GlStateManager._glGenVertexArrays();
            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vertices);
            attribute(0, 3, GL11.GL_FLOAT, false, 0);
            attribute(1, 4, GL11.GL_UNSIGNED_BYTE, true, 12);
            attribute(2, 2, GL11.GL_FLOAT, false, 16);
            attribute(3, 2, GL11.GL_SHORT, false, 24);
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices);
            long offset = (long) firstIndex * (type == GL11.GL_UNSIGNED_SHORT ? 2 : 4);
            GL32.glDrawElementsBaseVertex(GL11.GL_TRIANGLES, count, type, offset, baseVertex);
        } finally {
            GlStateManager._glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
        }
    }

    private static void attribute(int index, int size, int type, boolean normalized, long offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, type, normalized, 28, offset);
    }

    @Override public void close() {
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        vao = 0;
    }
}
