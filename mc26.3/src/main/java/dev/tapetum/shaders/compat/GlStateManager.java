package dev.tapetum.shaders.compat;

/**
 * Compile-time alias for Mojang's version-specific GL state cache. Inherited static calls
 * keep the game's cache coherent; no duplicate state tracker or copied implementation is used.
 */
public final class GlStateManager extends com.mojang.renderpearl.backend.opengl.GlStateManager {
    private GlStateManager() {
    }

    /** Clear depth is no longer exposed by RenderPearl and has no cached state there. */
    public static void _clearDepth(double depth) {
        org.lwjgl.opengl.GL11.glClearDepth(depth);
    }
}
