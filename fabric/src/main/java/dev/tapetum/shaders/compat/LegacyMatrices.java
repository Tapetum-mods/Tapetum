package dev.tapetum.shaders.compat;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;

/** Both matrix libraries serialize in OpenGL column-major order; do not transpose here. */
public final class LegacyMatrices {
    private LegacyMatrices() { }

    public static Matrix4f convert(com.mojang.math.Matrix4f source) {
        FloatBuffer elements = FloatBuffer.allocate(16);
        source.store(elements);
        return new Matrix4f(elements);
    }
}
