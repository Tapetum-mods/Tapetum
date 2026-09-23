package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.tapetum.shaders.pipeline.QuadIndices;
import java.nio.IntBuffer;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Draws the game's existing 32-byte block VBO without copying or replacing its mesh. */
public final class LegacyTerrainMesh implements AutoCloseable {
    public static final Map<String, Integer> ATTRIBUTES = Map.of(
        "tapetum_Position", 0, "tapetum_Color", 1, "tapetum_UV0", 2,
        "tapetum_UV1", 3, "tapetum_Normal", 4);
    private int vao;
    private int indices;
    private int capacity;

    public void draw(int buffer, int vertices) {
        int count = QuadIndices.indexCount(vertices);
        if (count == 0) return;
        if (buffer <= 0) throw new IllegalArgumentException("Terrain VBO is not allocated");
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            if (vao == 0) vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            if (indices == 0) indices = GL15.glGenBuffers();
            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indices);
            if (vertices > capacity) {
                IntBuffer data = MemoryUtil.memAllocInt(count);
                try {
                    QuadIndices.write(data, vertices);
                    data.flip();
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
                    capacity = vertices;
                } finally {
                    MemoryUtil.memFree(data);
                }
            }
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
            attribute(0, 3, GL11.GL_FLOAT, false, 0);
            attribute(1, 4, GL11.GL_UNSIGNED_BYTE, true, 12);
            attribute(2, 2, GL11.GL_FLOAT, false, 16);
            attribute(3, 2, GL11.GL_SHORT, false, 24);
            attribute(4, 3, GL11.GL_BYTE, true, 28);
            GL11.glDrawElements(GL11.GL_TRIANGLES, count, GL11.GL_UNSIGNED_INT, 0L);
        } finally {
            GL30.glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
        }
    }

    private static void attribute(int slot, int size, int type, boolean normalized, long offset) {
        GL20.glEnableVertexAttribArray(slot);
        GL20.glVertexAttribPointer(slot, size, type, normalized, 32, offset);
    }

    @Override public void close() {
        if (indices != 0) GL15.glDeleteBuffers(indices);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        indices = vao = capacity = 0;
    }
}
