package dev.tapetum.shaders.compat;

/**
 * Compile-time alias for Mojang's version-specific GL state cache. Inherited static calls
 * keep the game's cache coherent; no duplicate state tracker or copied implementation is used.
 */
public final class GlStateManager extends com.mojang.blaze3d.opengl.GlStateManager {
    private GlStateManager() {
    }
}
