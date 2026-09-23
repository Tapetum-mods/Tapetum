package dev.tapetum.shaders.compat;

import org.joml.Matrix4f;

/** Snapshot the native JOML matrix so later game mutations cannot change the captured state. */
public final class LegacyMatrices {
    private LegacyMatrices() { }

    public static Matrix4f convert(org.joml.Matrix4f source) {
        return new Matrix4f(source);
    }
}
