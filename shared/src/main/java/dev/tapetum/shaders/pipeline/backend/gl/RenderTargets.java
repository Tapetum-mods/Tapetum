package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.tapetum.shaders.shaderpack.glsl.ColorTextureFormat;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL41;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;

/**
 * The {@code colortex} buffers a shaderpack's composite chain reads and writes, each double-buffered
 * so a pass can read the previous pass's output while writing its own.
 *
 * <p>Double-buffering is not an optimisation, it is what makes the chain expressible at all. A
 * composite pass almost always samples the same buffer it writes to ({@code colortex0} in, tone-mapped
 * {@code colortex0} out), and sampling a texture you are rendering into is undefined in OpenGL. So
 * each buffer holds two textures: reads go to the front, writes to the back, and {@link #flip} swaps
 * them once the pass is done. This is the same ping-pong Iris and OptiFine use.</p>
 *
 */
public final class RenderTargets implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("Tapetum Shaders");

	/** OptiFine's ceiling, and the most this will ever allocate. */
	public static final int MAX_BUFFER_COUNT = 16;

	/**
	 * How many buffers to allocate, set from the pack rather than fixed.
	 *
	 * <p>Sizing this to the pack matters: each buffer costs two full-screen RGBA textures, so at
	 * 2048x1280 the difference between a pack needing six (Mellow) and allocating OptiFine's sixteen
	 * is roughly 200 MB of textures that are never sampled. BSL genuinely reaches ten, so a fixed
	 * small number is equally wrong - it has to follow what the pack declares.</p>
	 */
	private int bufferCount = 1;

	private final int[] frontTexture = new int[MAX_BUFFER_COUNT];
	private final int[] backTexture = new int[MAX_BUFFER_COUNT];
	private int framebuffer;
	/** How many colour attachments the last pass bound, so leftovers can be detached. */
	private int attachedCount;
	/** A second FBO so a blit can have distinct read and draw attachments. */
	private int presentFramebuffer;
	private int width;
	private int height;

	/** What each buffer's pixel format should be, as the pack declared it. */
	private Map<Integer, ColorTextureFormat> formats = Map.of();

	/**
	 * Sets how many buffers to allocate. Takes effect on the next {@link #resize}; changing it
	 * discards the current allocation, since the pool is no longer the right shape.
	 */
	public void setBufferCount(int bufferCount) {
		int clamped = Math.clamp(bufferCount, 1, MAX_BUFFER_COUNT);
		if (clamped != this.bufferCount) {
			this.bufferCount = clamped;
			close();
		}
	}

	public int bufferCount() {
		return bufferCount;
	}

	/**
	 * Sets the pixel format of each buffer from the pack's own declarations. Takes effect on the next
	 * {@link #resize}; changing it discards the current allocation, since the textures are the wrong
	 * type rather than merely the wrong size.
	 */
	public void setFormats(Map<Integer, ColorTextureFormat> formats) {
		Map<Integer, ColorTextureFormat> copy = Map.copyOf(formats);
		if (!copy.equals(this.formats)) {
			this.formats = copy;
			close();
		}
	}

	/**
	 * Ensures every buffer exists at {@code width} x {@code height}, reallocating on a resize.
	 *
	 * @return true if the targets are usable; false when the window has no area yet
	 */
	public boolean resize(int width, int height) {
		if (width <= 0 || height <= 0) {
			return false;
		}
		if (this.width == width && this.height == height && framebuffer != 0) {
			return true;
		}

		close();

		framebuffer = GlStateManager.glGenFramebuffers();
		presentFramebuffer = GlStateManager.glGenFramebuffers();
		for (int i = 0; i < bufferCount; i++) {
			ColorTextureFormat format = formats.getOrDefault(i, ColorTextureFormat.DEFAULT);
			frontTexture[i] = allocateTexture(width, height, format);
			backTexture[i] = allocateTexture(width, height, format);
			if (format != ColorTextureFormat.DEFAULT) {
				LOGGER.debug("colortex{}: allocated {}", i, format);
			}
		}

		this.width = width;
		this.height = height;
		return true;
	}

	private static int allocateTexture(int width, int height, ColorTextureFormat format) {
		int id = GlStateManager._genTexture();
		GlStateManager._bindTexture(id);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		// Clamped rather than repeating: a composite sampling just past the edge should see the edge
		// pixel, not wrap to the opposite side of the screen and smear it across the border.
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		// Integer textures cannot be filtered, and asking for LINEAR on one makes the whole framebuffer
		// incomplete rather than merely unfiltered.
		if (isInteger(format)) {
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		}

		// With a null pixel pointer the transfer format and type only have to be a legal pair for the
		// internal format; nothing is read through them. GL_RGBA + GL_FLOAT covers every normalised and
		// floating-point format, and integer formats need the _INTEGER transfer format.
		GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, glInternalFormat(format), width, height, 0,
			isInteger(format) ? GL30.GL_RGBA_INTEGER : GL11.GL_RGBA,
			isInteger(format) ? GL11.GL_UNSIGNED_INT : GL11.GL_FLOAT, null);
		return id;
	}

	private static boolean isInteger(ColorTextureFormat format) {
		return format.name().endsWith("I") || format.name().endsWith("UI");
	}

	/**
	 * The OpenGL sized internal format for a pack's declared format.
	 *
	 * <p>Signed and floating-point formats are the point of this mapping. Packs store normals in
	 * {@code RGB8_SNORM} and the HDR scene in {@code R11F_G11F_B10F}; allocating either as
	 * {@code RGBA8} silently clamps the data, which shows up as flat lighting and a sky that goes
	 * white at sunset rather than as any kind of error.</p>
	 */
	private static int glInternalFormat(ColorTextureFormat format) {
		return switch (format) {
			case R8 -> GL30.GL_R8;
			case RG8 -> GL30.GL_RG8;
			case RGB8 -> GL11.GL_RGB8;
			case RGBA8 -> GL11.GL_RGBA8;
			case R8_SNORM -> GL31.GL_R8_SNORM;
			case RG8_SNORM -> GL31.GL_RG8_SNORM;
			case RGB8_SNORM -> GL31.GL_RGB8_SNORM;
			case RGBA8_SNORM -> GL31.GL_RGBA8_SNORM;
			case R16 -> GL30.GL_R16;
			case RG16 -> GL30.GL_RG16;
			case RGB16 -> GL11.GL_RGB16;
			case RGBA16 -> GL11.GL_RGBA16;
			case R16_SNORM -> GL31.GL_R16_SNORM;
			case RG16_SNORM -> GL31.GL_RG16_SNORM;
			case RGB16_SNORM -> GL31.GL_RGB16_SNORM;
			case RGBA16_SNORM -> GL31.GL_RGBA16_SNORM;
			case R16F -> GL30.GL_R16F;
			case RG16F -> GL30.GL_RG16F;
			case RGB16F -> GL30.GL_RGB16F;
			case RGBA16F -> GL30.GL_RGBA16F;
			case R32F -> GL30.GL_R32F;
			case RG32F -> GL30.GL_RG32F;
			case RGB32F -> GL30.GL_RGB32F;
			case RGBA32F -> GL30.GL_RGBA32F;
			case R11F_G11F_B10F -> GL30.GL_R11F_G11F_B10F;
			case RGB9_E5 -> GL30.GL_RGB9_E5;
			case RGB565 -> GL41.GL_RGB565;
			case RGB5_A1 -> GL11.GL_RGB5_A1;
			case RGBA4 -> GL11.GL_RGBA4;
			case RGB10_A2 -> GL11.GL_RGB10_A2;
			case SRGB8 -> GL21.GL_SRGB8;
			case SRGB8_ALPHA8 -> GL21.GL_SRGB8_ALPHA8;
			case R8I -> GL30.GL_R8I;
			case R8UI -> GL30.GL_R8UI;
			case R16I -> GL30.GL_R16I;
			case R16UI -> GL30.GL_R16UI;
			case R32I -> GL30.GL_R32I;
			case R32UI -> GL30.GL_R32UI;
			case RG8I -> GL30.GL_RG8I;
			case RG8UI -> GL30.GL_RG8UI;
			case RG16I -> GL30.GL_RG16I;
			case RG16UI -> GL30.GL_RG16UI;
			case RG32I -> GL30.GL_RG32I;
			case RG32UI -> GL30.GL_RG32UI;
			case RGBA8I -> GL30.GL_RGBA8I;
			case RGBA8UI -> GL30.GL_RGBA8UI;
			case RGBA16I -> GL30.GL_RGBA16I;
			case RGBA16UI -> GL30.GL_RGBA16UI;
			case RGBA32I -> GL30.GL_RGBA32I;
			case RGBA32UI -> GL30.GL_RGBA32UI;
		};
	}

	/** The texture a pass should sample for {@code colortexIndex}, i.e. the previous pass's output. */
	public int readTexture(int colortexIndex) {
		return colortexIndex >= 0 && colortexIndex < bufferCount ? frontTexture[colortexIndex] : 0;
	}

	/**
	 * Binds every buffer this pass declares it writes, one colour attachment each.
	 *
	 * <p>This is what {@code DRAWBUFFERS:} actually means. A pass writing
	 * {@code /* DRAWBUFFERS:0462 *}{@code /} emits four fragment outputs, and location <em>i</em> goes
	 * to the <em>i</em>th name in that list — which is why declaration order is preserved all the way
	 * from parsing to here. Binding only the first, as an earlier version did, let a gbuffer-style
	 * pass write one buffer and silently drop the rest; the next pass then read whatever stale
	 * content those buffers happened to hold, which is how BSL's {@code deferred1} came to compute a
	 * black image from a perfectly good scene.</p>
	 *
	 * @return the previous bindings, which the caller must close after drawing
	 */
	public FramebufferBindings bindForWriting(List<Integer> colortexIndices) {
		List<Integer> outputs = colortexIndices.isEmpty() ? List.of(0) : colortexIndices;
		if (outputs.size() > maxDrawBuffers()) {
			throw new IllegalArgumentException("Pass needs " + outputs.size()
				+ " draw buffers, driver supports " + maxDrawBuffers());
		}
		if (outputs.stream().anyMatch(index -> index < 0 || index >= bufferCount)
				|| outputs.stream().distinct().count() != outputs.size()) {
			throw new IllegalArgumentException("Invalid draw buffers: " + outputs);
		}
		FramebufferBindings previous = FramebufferBindings.capture();
		try {
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
			int count = 0;
			for (int index : outputs) {
				GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
					GL30.GL_COLOR_ATTACHMENT0 + count, GL11.GL_TEXTURE_2D, backTexture[index], 0);
				count++;
			}
			// Detach outputs left by a previous, wider pass.
			for (int i = count; i < attachedCount; i++) {
				GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
					GL30.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, 0, 0);
			}
			attachedCount = count;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer draw = stack.mallocInt(count);
				for (int i = 0; i < count; i++) {
					draw.put(i, GL30.GL_COLOR_ATTACHMENT0 + i);
				}
				GL20.glDrawBuffers(draw);
			}
			GL11.glViewport(0, 0, width, height);
			requireComplete(GL30.GL_DRAW_FRAMEBUFFER);
			return previous;
		} catch (RuntimeException e) {
			previous.close();
			throw e;
		}
	}

	private int cachedMaxDrawBuffers;

	/** The driver's colour-attachment limit, queried once. OpenGL 3.0 guarantees at least eight. */
	private int maxDrawBuffers() {
		if (cachedMaxDrawBuffers == 0) {
			cachedMaxDrawBuffers = Math.min(GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS),
				GL11.glGetInteger(GL30.GL_MAX_COLOR_ATTACHMENTS));
		}
		return cachedMaxDrawBuffers;
	}

	/**
	 * Restricts the bound draw framebuffer to its first attachment.
	 *
	 * <p>Draw-buffer state belongs to the framebuffer object, not to the binding, so it survives from
	 * one use to the next. A pass writing four targets leaves this framebuffer expecting four; the
	 * blits that share it attach one texture and copy one, and the three attachments still listed as
	 * draw buffers have no image behind them. Resetting first keeps the blit describing exactly what
	 * it does. {@link #bindForWriting} sets the full list again on the next pass, so nothing is lost.</p>
	 *
	 * <p>Only the framebuffer the passes write through needs this. {@code presentFramebuffer} is used
	 * for single-attachment blits and nothing else, so its draw-buffer state never drifts.</p>
	 */
	private void useSingleDrawBuffer() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer draw = stack.mallocInt(1);
			draw.put(0, GL30.GL_COLOR_ATTACHMENT0);
			GL20.glDrawBuffers(draw);
		}
	}

	private static void requireComplete(int target) {
		int status = GL30.glCheckFramebufferStatus(target);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("Shader framebuffer: " + framebufferStatus(status));
		}
	}

	/**
	 * Publishes what the pass just wrote, so the next pass reads it.
	 *
	 * <p>Called once per pass, per buffer written. Flipping a buffer nothing wrote would hand the next
	 * pass a stale frame's contents instead of this one's.</p>
	 */
	public void flip(int colortexIndex) {
		if (colortexIndex < 0 || colortexIndex >= bufferCount) {
			return;
		}
		int swap = frontTexture[colortexIndex];
		frontTexture[colortexIndex] = backTexture[colortexIndex];
		backTexture[colortexIndex] = swap;
	}

	/**
	 * Seeds a buffer from Minecraft's scene texture, so the chain starts from the rendered frame
	 * rather than an uninitialised texture.
	 *
	 * <p>The source is named explicitly rather than taken from whatever framebuffer happens to be
	 * bound. An earlier version copied from the current read target, and at this point in the frame
	 * that is sometimes the default framebuffer — which holds nothing yet. The chain was then seeded
	 * with black, every pass computed on black, and the result written back over the scene was black:
	 * a completely dark world from a pipeline that was otherwise working.</p>
	 */
	public void captureSceneInto(int colortexIndex, int sceneTexture) {
		if (colortexIndex < 0 || colortexIndex >= bufferCount || width <= 0 || sceneTexture == 0) {
			return;
		}

		try (FramebufferBindings _ = FramebufferBindings.capture()) {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, presentFramebuffer);
			GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, sceneTexture, 0);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
			GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, frontTexture[colortexIndex], 0);
			useSingleDrawBuffer();
			requireComplete(GL30.GL_READ_FRAMEBUFFER);
			requireComplete(GL30.GL_DRAW_FRAMEBUFFER);

			GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
				GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		}
	}

	/**
	 * Copies the chain's result into {@code destinationTexture} — Minecraft's own scene texture.
	 *
	 * <p>This is what makes the chain visible. Every pass so far has written into private buffers, so
	 * without this step the work is complete and discarded: Minecraft blits its own untouched scene
	 * over the screen and nothing the pack computed is ever seen.</p>
	 *
	 * <p>Writing into Minecraft's texture rather than onto the screen is deliberate. Drawing straight
	 * to the default framebuffer here would be overwritten moments later by
	 * {@code Minecraft.renderFrame}'s own blit; handing the result back through the texture that blit
	 * reads means it carries the pack's output instead of fighting it.</p>
	 */
	public void presentTo(int colortexIndex, int destinationTexture) {
		if (colortexIndex < 0 || colortexIndex >= bufferCount || width <= 0 || destinationTexture == 0) {
			return;
		}

		try (FramebufferBindings _ = FramebufferBindings.capture()) {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
			GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, frontTexture[colortexIndex], 0);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, presentFramebuffer);
			GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, destinationTexture, 0);
			GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
			requireComplete(GL30.GL_READ_FRAMEBUFFER);
			requireComplete(GL30.GL_DRAW_FRAMEBUFFER);

			GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
				GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		}
	}

	/**
	 * Reads one pixel back from {@code texture}, to find out what an image actually contains rather
	 * than inferring it from what the code looks like it should produce.
	 *
	 * <p>Deliberately expensive: {@code glReadPixels} stalls until the GPU has caught up with every
	 * queued command. This must never run per frame — it exists to be called a fixed handful of times
	 * and then stop.</p>
	 *
	 * @return red, green, blue and alpha as 0-255, or {@code null} if there is nothing to read
	 */
	public int[] samplePixel(int texture, int x, int y) {
		if (texture == 0 || width <= 0) {
			return null;
		}

		FramebufferBindings previous = FramebufferBindings.capture();

		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, presentFramebuffer);
		GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
			GL11.GL_TEXTURE_2D, texture, 0);
		GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

		int[] pixel;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer buffer = stack.malloc(4);
			GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
			pixel = new int[] {
				buffer.get(0) & 0xFF, buffer.get(1) & 0xFF, buffer.get(2) & 0xFF, buffer.get(3) & 0xFF
			};
		}

		previous.close();
		return pixel;
	}

	/**
	 * Performs the capture blit while reporting what OpenGL made of it.
	 *
	 * <p>A silently failing blit and a genuinely black scene produce the same pixels — all zeroes —
	 * because a freshly allocated texture reads as transparent black too. Only the framebuffer
	 * completeness status and the error flag tell the two apart, so this reports both rather than
	 * leaving the question open.</p>
	 */
	public String describeCapture(int sceneTexture) {
		if (width <= 0) {
			return "targets not allocated";
		}
		if (sceneTexture == 0) {
			return "minecraft scene texture id is 0 (not a GlTexture?)";
		}

		FramebufferBindings previous = FramebufferBindings.capture();
		while (GL11.glGetError() != GL11.GL_NO_ERROR) {
			// Drain errors raised before this point so the one reported below is ours.
		}

		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, presentFramebuffer);
		GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
			GL11.GL_TEXTURE_2D, sceneTexture, 0);
		int readStatus = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);

		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
		GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
			GL11.GL_TEXTURE_2D, frontTexture[0], 0);
		useSingleDrawBuffer();
		int drawStatus = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);

		GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height,
			GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
		int error = GL11.glGetError();

		previous.close();
		return "sceneTex=" + sceneTexture + " colortex0=" + frontTexture[0]
			+ " readFBO=" + framebufferStatus(readStatus)
			+ " drawFBO=" + framebufferStatus(drawStatus)
			+ " blitError=" + glErrorName(error);
	}

	private static String framebufferStatus(int status) {
		return switch (status) {
			case GL30.GL_FRAMEBUFFER_COMPLETE -> "COMPLETE";
			case GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "INCOMPLETE_ATTACHMENT";
			case GL30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "INCOMPLETE_MISSING_ATTACHMENT";
			case GL30.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER -> "INCOMPLETE_DRAW_BUFFER";
			case GL30.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER -> "INCOMPLETE_READ_BUFFER";
			case GL30.GL_FRAMEBUFFER_UNSUPPORTED -> "UNSUPPORTED";
			case GL30.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE -> "INCOMPLETE_MULTISAMPLE";
			default -> "0x" + Integer.toHexString(status);
		};
	}

	private static String glErrorName(int error) {
		return switch (error) {
			case GL11.GL_NO_ERROR -> "none";
			case GL11.GL_INVALID_ENUM -> "INVALID_ENUM";
			case GL11.GL_INVALID_VALUE -> "INVALID_VALUE";
			case GL11.GL_INVALID_OPERATION -> "INVALID_OPERATION";
			case GL30.GL_INVALID_FRAMEBUFFER_OPERATION -> "INVALID_FRAMEBUFFER_OPERATION";
			case GL11.GL_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
			default -> "0x" + Integer.toHexString(error);
		};
	}

	/**
	 * Reports a texture's actual dimensions and internal format.
	 *
	 * <p>A blit whose source rectangle exceeds the source texture is legal, raises no error and
	 * leaves the destination unwritten — indistinguishable from a genuinely black scene by pixels
	 * alone. Asking the texture how big it really is settles that.</p>
	 */
	public String describeTexture(int texture) {
		if (texture == 0) {
			return "id=0";
		}
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(texture);
		int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
		int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
		int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
		return "id=" + texture + " " + w + "x" + h + " format=0x" + Integer.toHexString(format);
	}

	/**
	 * Reports what Minecraft's depth texture really is, and what it really contains.
	 *
	 * <p>This is the input a pack leans on hardest: it reads {@code z} to tell sky from terrain and to
	 * rebuild world position from the depth buffer. If {@code z} comes back 0 everywhere then
	 * {@code z == 1.0} is false for every pixel, the sky branch is never taken, the terrain branch
	 * runs everywhere, and every position reconstructs onto the near plane — terrain lighting
	 * evaluated at the camera, which is black. Three things can cause that and none of them raises a
	 * GL error, so all three are reported: the size, the comparison mode, and an actual sample.</p>
	 */
	public String describeDepth(int depthTexture) {
		if (depthTexture == 0) {
			return "no depth texture (not a GlTexture?)";
		}

		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(depthTexture);
		int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
		int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
		int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
		int compareMode = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE);
		int minFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);

		String sample = sampleDepth(depthTexture);

		return "id=" + depthTexture + " " + w + "x" + h
			+ " format=0x" + Integer.toHexString(format)
			+ " compareMode=" + (compareMode == GL11.GL_NONE ? "NONE (sampler2D ok)"
				: "0x" + Integer.toHexString(compareMode) + " (sampler2D UNDEFINED)")
			+ " minFilter=0x" + Integer.toHexString(minFilter)
			+ " depth=" + sample;
	}

	/** Reads the centre depth value, which is what settles whether the buffer holds a real frame. */
	private String sampleDepth(int depthTexture) {
		if (width <= 0) {
			return "(targets not allocated)";
		}

		FramebufferBindings previous = FramebufferBindings.capture();
		while (GL11.glGetError() != GL11.GL_NO_ERROR) {
			// Drain earlier errors so the one reported below is ours.
		}

		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, presentFramebuffer);
		// Detach any colour left from a previous use, then attach depth alone. A depth-only
		// framebuffer is incomplete unless both buffers are explicitly set to none.
		GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
			GL11.GL_TEXTURE_2D, 0, 0);
		GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
			GL11.GL_TEXTURE_2D, depthTexture, 0);
		GL11.glReadBuffer(GL11.GL_NONE);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);

		String result;
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			result = "unreadable (" + framebufferStatus(status) + ")";
		} else {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				// Five points, not one. The centre alone cannot tell "this buffer is empty" from
				// "the player is looking at the sky", and those call for completely different work:
				// one is a capture that never happened, the other is a correct frame.
				int[][] points = {
					{ width / 2, height / 2 }, { width / 4, height / 4 }, { 3 * width / 4, height / 4 },
					{ width / 4, 3 * height / 4 }, { 3 * width / 4, 3 * height / 4 },
				};
				FloatBuffer buffer = stack.mallocFloat(1);
				StringBuilder samples = new StringBuilder();
				int far = 0;
				for (int[] point : points) {
					GL11.glReadPixels(point[0], point[1], 1, 1,
						GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
					float z = buffer.get(0);
					if (z == 1.0f) {
						far++;
					}
					samples.append(samples.length() == 0 ? "" : " ").append(String.format("%.4f", z));
				}
				result = samples + (far == points.length
					? "  <- FIVE SAMPLES AT FAR PLANE: sky or cleared depth; capture not yet established"
					: "  (real depth)");
			}
		}

		int error = GL11.glGetError();
		GlStateManager._glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
			GL11.GL_TEXTURE_2D, 0, 0);
		GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
		previous.close();

		return error == GL11.GL_NO_ERROR ? result : result + " glError=" + glErrorName(error);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	@Override
	public void close() {
		if (framebuffer != 0) {
			GlStateManager._glDeleteFramebuffers(framebuffer);
			framebuffer = 0;
		}
		if (presentFramebuffer != 0) {
			GlStateManager._glDeleteFramebuffers(presentFramebuffer);
			presentFramebuffer = 0;
		}
		for (int i = 0; i < MAX_BUFFER_COUNT; i++) {
			if (frontTexture[i] != 0) {
				GlStateManager._deleteTexture(frontTexture[i]);
				frontTexture[i] = 0;
			}
			if (backTexture[i] != 0) {
				GlStateManager._deleteTexture(backTexture[i]);
				backTexture[i] = 0;
			}
		}
		width = 0;
		height = 0;
	}
}
