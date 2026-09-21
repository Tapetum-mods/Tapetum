package dev.tapetum.shaders.pipeline.backend.gl;

import dev.tapetum.shaders.compat.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * The shadow map a pack samples, currently filled with "nothing casts a shadow".
 *
 * <p>Thirty-five of the forty-nine passes surveyed here declare {@code sampler2DShadow shadowtex0}.
 * An unbound one reads 0, and for a comparison sampler 0 means <em>the depth test failed</em> —
 * every fragment fully shadowed. Packs multiply their sunlight term by that result, so leaving these
 * unbound clamps direct light to zero across most of the chain. That is a black floor entirely
 * separate from the missing G-buffers, and lifting it costs one texture.</p>
 *
 * <p>The depth is filled with 1.0, the far plane. With {@code GL_COMPARE_REF_TO_TEXTURE} and
 * {@code GL_LEQUAL} any reference depth compares as nearer than the occluder, so the lookup returns
 * 1.0 — fully lit. Rendering a real shadow map later replaces the contents of these same textures
 * without changing anything that samples them.</p>
 */
public final class ShadowTargets implements AutoCloseable {
	/**
	 * Small deliberately. Nothing samples detail out of a map with no occluders in it, and packs read
	 * {@code shadowMapResolution} for their own filtering rather than measuring the texture.
	 */
	public static final int RESOLUTION = 512;

	private int depthTexture;
	private int colorTexture;

	/** The always-lit depth map, for {@code shadowtex0} and {@code shadowtex1}. */
	public int depth() {
		ensureAllocated();
		return depthTexture;
	}

	/** Opaque white, for {@code shadowcolor0} and {@code shadowcolor1}. */
	public int color() {
		ensureAllocated();
		return colorTexture;
	}

	private void ensureAllocated() {
		if (depthTexture != 0) {
			return;
		}
		depthTexture = allocateDepth();
		colorTexture = allocateColor();
	}

	/**
	 * Allocates the depth map already full of "far plane".
	 *
	 * <p>Filled at upload rather than with {@code glClear}. Blaze3D 26.x dropped
	 * {@code GlStateManager._clearDepth} and {@code _clearColor} — clears go through the
	 * {@code RenderPass} API now — and reaching past it to raw LWJGL would be worse than inconvenient:
	 * {@code glClear} is gated by the clear colour, the depth-write mask and the scissor test, none of
	 * which Minecraft leaves in a known state at this point in the frame, and all of which are shared
	 * with Sodium. Handing the contents to {@code glTexImage2D} touches no GL state at all.</p>
	 */
	private static int allocateDepth() {
		int id = GlStateManager._genTexture();
		GlStateManager._bindTexture(id);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		// What makes a sampler2DShadow lookup return a comparison result rather than a raw depth read.
		// Without it the pack's shadow2D() call on this texture is undefined.
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE,
			GL30.GL_COMPARE_REF_TO_TEXTURE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);

		ByteBuffer pixels = MemoryUtil.memAlloc(RESOLUTION * RESOLUTION * Float.BYTES);
		try {
			FloatBuffer depths = pixels.asFloatBuffer();
			for (int i = 0; i < RESOLUTION * RESOLUTION; i++) {
				depths.put(i, 1.0f);
			}
			GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24,
				RESOLUTION, RESOLUTION, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, pixels);
		} finally {
			// Off-heap, so freed explicitly and in a finally: a driver error must not leak a megabyte.
			MemoryUtil.memFree(pixels);
		}
		return id;
	}

	/** Allocates the shadow colour map as opaque white, i.e. nothing tints the light. */
	private static int allocateColor() {
		int id = GlStateManager._genTexture();
		GlStateManager._bindTexture(id);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

		int bytes = RESOLUTION * RESOLUTION * 4;
		ByteBuffer pixels = MemoryUtil.memAlloc(bytes);
		try {
			for (int i = 0; i < bytes; i++) {
				pixels.put(i, (byte) 0xFF);
			}
			GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, RESOLUTION, RESOLUTION, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
		} finally {
			MemoryUtil.memFree(pixels);
		}
		return id;
	}

	@Override
	public void close() {
		if (depthTexture != 0) {
			GlStateManager._deleteTexture(depthTexture);
			depthTexture = 0;
		}
		if (colorTexture != 0) {
			GlStateManager._deleteTexture(colorTexture);
			colorTexture = 0;
		}
	}
}
