package dev.tapetum.shaders.pipeline;

import java.nio.IntBuffer;

/** Triangle indices for Minecraft's four consecutive vertices per terrain quad. */
public final class QuadIndices {
    public static final int MAX_VERTICES = 1_048_576;

    private QuadIndices() { }

    public static int indexCount(int vertices) {
        if (vertices < 0 || vertices > MAX_VERTICES || vertices % 4 != 0) {
            throw new IllegalArgumentException("Invalid terrain quad vertex count: " + vertices);
        }
        return vertices / 4 * 6;
    }

    public static void write(IntBuffer target, int vertices) {
        int count = indexCount(vertices);
        if (target.remaining() < count) throw new IllegalArgumentException("Index buffer is too small");
        for (int first = 0; first < vertices; first += 4) {
            target.put(first).put(first + 1).put(first + 2);
            target.put(first + 2).put(first + 3).put(first);
        }
    }
}
