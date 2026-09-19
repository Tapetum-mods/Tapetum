package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * The {@code noisetex} sampler every OptiFine-format pack expects.
 *
 * <p>Thirty-eight of the forty-nine passes across the five packs surveyed here declare
 * {@code uniform sampler2D noisetex}. A sampler that is never bound reads texture unit 0, which in
 * this pipeline holds {@code colortex0} — so a pack sampling "noise" was handed the scene itself,
 * and every effect built on it (clouds, dithering, water surface, godrays) computed from an image
 * rather than from noise.</p>
 *
 * <p>Deterministic by design: seeded from a constant so the same frame renders identically across
 * runs and machines. Noise that changes between launches would make any visual regression
 * impossible to reproduce.</p>
 */
public final class NoiseTexture implements AutoCloseable {
	/**
	 * OptiFine's default, and what a pack assumes when {@code noiseTextureResolution} is absent from
	 * {@code shaders.properties}. Packs index this texture with {@code 1.0 / noiseTextureResolution}
	 * steps, so the value reported to the shader and the size allocated here must agree.
	 */
	public static final int RESOLUTION = 256;

	private static final long SEED = 0x7A9E_1B3D_4C5F_6081L;

	private int textureId;

	public int id() {
		if (textureId == 0) {
			textureId = allocate();
		}
		return textureId;
	}

	private static int allocate() {
		int id = GlStateManager._genTexture();
		GlStateManager._bindTexture(id);
		// Linear and repeating: packs sample it at arbitrary scaled coordinates and rely on it tiling
		// seamlessly. Clamping would leave a visible seam across the sky.
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

		int bytes = RESOLUTION * RESOLUTION * 4;
		// Allocated off-heap because OpenGL reads it after the call returns; a heap array would be
		// free to move. Freed in the finally block whatever happens, so a driver error cannot leak it.
		ByteBuffer pixels = MemoryUtil.memAlloc(bytes);
		try {
			Random random = new Random(SEED);
			byte[] block = new byte[bytes];
			random.nextBytes(block);
			// Alpha is forced opaque: a pack multiplying by the sampled alpha would otherwise cancel
			// its own noise out roughly half the time.
			for (int i = 3; i < bytes; i += 4) {
				block[i] = (byte) 0xFF;
			}
			pixels.put(block).flip();
			GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, RESOLUTION, RESOLUTION, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
		} finally {
			MemoryUtil.memFree(pixels);
		}

		return id;
	}

	@Override
	public void close() {
		if (textureId != 0) {
			GlStateManager._deleteTexture(textureId);
			textureId = 0;
		}
	}
}
