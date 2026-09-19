package dev.tapetum.shaders.pipeline.backend.gl;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.joml.Matrix4fc;

/**
 * A linked GLSL program, compiled directly through {@code GlStateManager} rather than Blaze3D's
 * {@code GpuDevice.precompilePipeline} path. That higher-level API is built for Minecraft's own
 * statically-known shaders; it has no way to accept arbitrary GLSL source discovered from a
 * shaderpack at runtime, so this — like Iris' own shader compilation — talks to the driver
 * directly. Going through {@code GlStateManager} (instead of raw LWJGL calls) matters here: vanilla
 * caches bound-program/bound-buffer state internally for its own draw calls, and bypassing that
 * cache would leave it out of sync with reality.
 */
public final class GlProgram implements AutoCloseable {
	/**
	 * How many texture units may be used.
	 *
	 * <p>Not the GL limit — OpenGL guarantees more — but the size of the cache
	 * {@code GlStateManager} keeps of which texture is bound where. It indexes that array with the
	 * unit number and does not range-check, so asking for unit 16 throws
	 * {@code ArrayIndexOutOfBoundsException} on the render thread and takes the game down. Staying
	 * inside vanilla's cache is what keeps the two views of GL state consistent as well.</p>
	 */
	public static final int VANILLA_CACHED_UNITS = 12;

	/** @deprecated read {@link #maxTextureUnits()} instead; this is only vanilla's cache depth. */
	@Deprecated
	public static final int MAX_TEXTURE_UNITS = VANILLA_CACHED_UNITS;

	private final int programId;

	/**
	 * Every uniform name this loader tried to set, and the subset the linked program actually had.
	 *
	 * <p>Recorded only until {@link #stopAuditing()}, so the cost is one frame. The two sets answer
	 * the question that guessing could not: a name the program declares but never receives is a gap
	 * in this loader, whereas a name offered and ignored is effort spent on something no pack reads.
	 * A typo on this side shows up only here — the source-level survey can see what a pack declares,
	 * but not whether the name I send matches it.</p>
	 */
	private final Set<String> offeredNames = new LinkedHashSet<>();
	private final Set<String> acceptedNames = new LinkedHashSet<>();
	private boolean auditing = true;

	private GlProgram(int programId) {
		this.programId = programId;
	}

	public static GlProgram link(String debugName, String vertexSource, String fragmentSource) throws GlShaderCompileException {
		return link(debugName, vertexSource, fragmentSource, Map.of());
	}

	/** Attribute locations must agree with the mesh VAO, including on GLSL versions before 330. */
	public static GlProgram link(String debugName, String vertexSource, String fragmentSource,
			Map<String, Integer> attributeBindings) throws GlShaderCompileException {
		int limit = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
		for (var binding : attributeBindings.entrySet()) {
			if (binding.getValue() < 0 || binding.getValue() >= limit) {
				throw new IllegalArgumentException("Invalid attribute location: " + binding);
			}
		}
		int vertexShader = compileStage(debugName, GL20.GL_VERTEX_SHADER, "vertex", vertexSource);
		int fragmentShader;
		try {
			fragmentShader = compileStage(debugName, GL20.GL_FRAGMENT_SHADER, "fragment", fragmentSource);
		} catch (GlShaderCompileException e) {
			GlStateManager.glDeleteShader(vertexShader);
			throw e;
		}

		int program = GlStateManager.glCreateProgram();
		GlStateManager.glAttachShader(program, vertexShader);
		GlStateManager.glAttachShader(program, fragmentShader);
		attributeBindings.forEach((name, location) -> GL20.glBindAttribLocation(program, location, name));
		GlStateManager.glLinkProgram(program);

		// The shader objects are no longer needed once linked into the program; only the link
		// result matters from here on.
		GlStateManager.glDeleteShader(vertexShader);
		GlStateManager.glDeleteShader(fragmentShader);

		if (GlStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
			String log = GlStateManager.glGetProgramInfoLog(program, 8192);
			GlStateManager.glDeleteProgram(program);
			throw new GlShaderCompileException("Failed to link '" + debugName + "':\n" + log);
		}

		return new GlProgram(program);
	}

	private static int compileStage(String debugName, int stageType, String stageLabel, String source) throws GlShaderCompileException {
		int shader = GlStateManager.glCreateShader(stageType);
		GlStateManager.glShaderSource(shader, source);
		GlStateManager.glCompileShader(shader);

		if (GlStateManager.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL20.GL_FALSE) {
			String log = GlStateManager.glGetShaderInfoLog(shader, 8192);
			GlStateManager.glDeleteShader(shader);
			throw new GlShaderCompileException("Failed to compile " + stageLabel + " shader for '" + debugName + "':\n" + log);
		}

		return shader;
	}

	public void use() {
		GlStateManager._glUseProgram(programId);
	}

	/**
	 * Points a {@code sampler2D} uniform at a texture unit, and binds {@code textureId} there.
	 *
	 * <p>Silently does nothing when the shader has no such uniform. That is the normal case, not an
	 * error: packs declare only the samplers they use, and the loader offers every name a pack
	 * <em>might</em> ask for. A driver also strips uniforms the shader never reads, so even a
	 * declared name can be absent from the linked program.</p>
	 */
	public boolean bindSampler(String uniformName, int textureUnit, int textureId) {
		if (textureUnit < 0 || textureUnit >= maxTextureUnits()) {
			// Offered but not accepted: the pass wanted this sampler and the units ran out, which the
			// audit reports differently from a name the program never declared.
			record(uniformName, false);
			return false;
		}

		int location = GlStateManager._glGetUniformLocation(programId, uniformName);
		record(uniformName, location >= 0);
		if (location < 0) {
			return false;
		}

		bindTexture(textureUnit, textureId);
		// A sampler left by Blaze3D overrides the texture's filtering/comparison settings.
		// The enclosing GlRenderState restores Minecraft's sampler bindings after the chain.
		GL33.glBindSampler(textureUnit, 0);

		GlStateManager._glUniform1i(location, textureUnit);
		return true;
	}

	static void bindTexture(int unit, int texture) {
		// _activeTexture is valid above unit 11; only _bindTexture indexes the short cache.
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
		if (unit < VANILLA_CACHED_UNITS) {
			GlStateManager._bindTexture(texture);
		} else {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		}
	}

	private static int cachedMaxTextureUnits;

	/**
	 * How many texture units a pass may use.
	 *
	 * <p>Vanilla's cache holds twelve and packs want more: Complementary's {@code deferred1} declares
	 * eighteen samplers, and adding the shadow map to BSL's eleven overruns it too. Units beyond the
	 * cache are now bound directly rather than refused — OpenGL guarantees at least sixteen, and the
	 * real ceiling is asked for rather than assumed.</p>
	 */
	public static int maxTextureUnits() {
		if (cachedMaxTextureUnits == 0) {
			cachedMaxTextureUnits =
				Math.max(VANILLA_CACHED_UNITS, GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS));
		}
		return cachedMaxTextureUnits;
	}

	/**
	 * Sets a {@code float} uniform, ignoring names the linked program does not have.
	 *
	 * <p>Goes straight to LWJGL rather than through {@code GlStateManager}, which has no float
	 * overload here. That is safe for uniforms specifically: they live in the program object, not in
	 * the context state vanilla caches, so there is no cache to get out of step with.</p>
	 */
	public void setUniform(String uniformName, float value) {
		int location = GlStateManager._glGetUniformLocation(programId, uniformName);
		record(uniformName, location >= 0);
		if (location >= 0) {
			GL20.glUniform1f(location, value);
		}
	}

	/** Sets a {@code vec3} uniform, ignoring names the linked program does not have. */
	public void setUniform(String uniformName, float x, float y, float z) {
		int location = GlStateManager._glGetUniformLocation(programId, uniformName);
		record(uniformName, location >= 0);
		if (location >= 0) {
			GL20.glUniform3f(location, x, y, z);
		}
	}

	/** Sets a {@code vec4} uniform, ignoring names the linked program does not have. */
	public void setUniform(String uniformName, float x, float y, float z, float w) {
		int location = GlStateManager._glGetUniformLocation(programId, uniformName);
		record(uniformName, location >= 0);
		if (location >= 0) {
			GL20.glUniform4f(location, x, y, z, w);
		}
	}

	/** Sets an {@code ivec2} uniform, ignoring names the linked program does not have. */
	public void setUniform(String uniformName, int x, int y) {
		int location = GlStateManager._glGetUniformLocation(programId, uniformName);
		record(uniformName, location >= 0);
		if (location >= 0) {
			GL20.glUniform2i(location, x, y);
		}
	}

	/** Sets an {@code int} uniform, ignoring names the linked program does not have. */
	public void setUniform(String uniformName, int value) {
		int location = GlStateManager._glGetUniformLocation(programId, uniformName);
		record(uniformName, location >= 0);
		if (location >= 0) {
			GlStateManager._glUniform1i(location, value);
		}
	}

	/**
	 * Sets a {@code mat4} uniform, ignoring names the linked program does not have.
	 *
	 * <p>These matter more than any other uniform this loader supplies. A pack reconstructs view and
	 * world position from the depth buffer by multiplying through
	 * {@code gbufferProjectionInverse}; GLSL zero-initialises a uniform that is never uploaded, and
	 * multiplying by a zero matrix collapses every reconstructed position onto the origin. Fog,
	 * lighting, ambient occlusion and reflections then all evaluate at one point and the frame comes
	 * out black — from a scene that was captured perfectly.</p>
	 */
	public void setUniform(String uniformName, Matrix4fc value) {
		int location = GlStateManager._glGetUniformLocation(programId, uniformName);
		record(uniformName, location >= 0);
		if (location < 0) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.mallocFloat(16);
			value.get(buffer);
			GL20.glUniformMatrix4fv(location, false, buffer);
		}
	}

	private void record(String uniformName, boolean accepted) {
		if (!auditing) {
			return;
		}
		offeredNames.add(uniformName);
		if (accepted) {
			acceptedNames.add(uniformName);
		}
	}

	/** Stops recording once the audit has been reported; after this the sets never grow again. */
	public void stopAuditing() {
		auditing = false;
	}

	/**
	 * Every uniform and sampler the linked program actually uses, straight from the driver.
	 *
	 * <p>Array uniforms come back as {@code "name[0]"}; the subscript is dropped so the names line up
	 * with what this loader sets.</p>
	 */
	public List<String> activeUniforms() {
		int count = GlStateManager.glGetProgrami(programId, GL20.GL_ACTIVE_UNIFORMS);
		List<String> names = new ArrayList<>(Math.max(count, 0));

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer size = stack.mallocInt(1);
			IntBuffer type = stack.mallocInt(1);
			for (int index = 0; index < count; index++) {
				String name = GL20.glGetActiveUniform(programId, index, size, type);
				int subscript = name.indexOf('[');
				names.add(subscript < 0 ? name : name.substring(0, subscript));
			}
		}

		return names;
	}

	/**
	 * What this program asked for versus what it was given.
	 *
	 * <p>Deliberately reports both directions. Names in "not supplied" are the work still to do;
	 * names in "ignored" are effort this loader spends on something the pack never reads.</p>
	 */
	public String auditReport() {
		Set<String> active = new LinkedHashSet<>(activeUniforms());
		Set<String> missing = new LinkedHashSet<>(active);
		missing.removeAll(acceptedNames);
		Set<String> ignored = new LinkedHashSet<>(offeredNames);
		ignored.removeAll(active);

		return active.size() + " active, " + acceptedNames.size() + " supplied"
			+ (missing.isEmpty() ? "" : "\n      not supplied: " + String.join(", ", missing))
			+ (ignored.isEmpty() ? "" : "\n      ignored: " + String.join(", ", ignored));
	}

	/**
	 * Unbinds whatever program is current. Call this once done drawing: leaving our program bound
	 * would leave the context in a state the next renderer to draw — vanilla, or Sodium with its
	 * own program management — never established.
	 */
	public static void unbind() {
		GlStateManager._glUseProgram(0);
	}

	@Override
	public void close() {
		GlStateManager.glDeleteProgram(programId);
	}
}
