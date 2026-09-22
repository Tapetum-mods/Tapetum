package dev.tapetum.shaders.pipeline;

import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.pipeline.backend.GpuBackendType;
import dev.tapetum.shaders.pipeline.backend.gl.GlShaderCompileException;
import dev.tapetum.shaders.shaderpack.ShaderDimension;
import dev.tapetum.shaders.shaderpack.ShaderPack;
import dev.tapetum.shaders.shaderpack.ShaderProperties;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import dev.tapetum.shaders.shaderpack.glsl.GlslStageLinkage;
import dev.tapetum.shaders.shaderpack.glsl.ColorTextureFormat;
import dev.tapetum.shaders.shaderpack.uniform.CustomUniforms;
import dev.tapetum.shaders.shaderpack.glsl.FullScreenVertexAdapter;
import dev.tapetum.shaders.shaderpack.glsl.DrawBuffers;
import dev.tapetum.shaders.shaderpack.ShaderProgramChain;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.tapetum.shaders.shaderpack.glsl.GlslCompatPatcher;
import dev.tapetum.shaders.shaderpack.glsl.GlslIncludeException;
import dev.tapetum.shaders.shaderpack.glsl.ShaderMacros;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import dev.tapetum.shaders.uniform.FrameState;

/**
 * Owns the currently active {@link RenderingPipeline} and swaps it whenever the selected
 * shaderpack or the shaders-enabled flag changes.
 *
 * <p>{@link #reload()} builds a {@link CompositeChainPipeline} from every screen-space program the
 * pack ships — the whole {@code deferred} chain, then {@code composite}, then {@code final} — and
 * falls back to {@link VanillaRenderingPipeline} for anything else (no such programs, a compile
 * error, a non-OpenGL backend), logging why instead of silently pretending shaders are active.</p>
 */
public class PipelineManager implements ShaderEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger("Tapetum Shaders");

	private RenderingPipeline current = VanillaRenderingPipeline.INSTANCE;
	private Throwable lastFailure;
	private ClientLevel renderingLevel;

	@Override
	public String name() {
		return "native Tapetum screen-space engine";
	}

	public RenderingPipeline getPipeline() {
		return current;
	}

	@Override
	public void beginLevelRendering() {
		// A new world instance also invalidates history when its dimension key is unchanged.
		if (Minecraft.getInstance().level != renderingLevel) {
			reload();
		}
		current.beginLevelRendering();
	}

	@Override
	public boolean isShaderPackActive() {
		return current.isShaderPackActive();
	}

	/** A failed pass must not be retried every frame or leave a half-rendered GL state behind. */
	public void finalizeLevelRendering() {
		try {
			current.finalizeLevelRendering();
		} catch (RuntimeException e) {
			lastFailure = e;
			RenderingPipeline failed = current;
			current = VanillaRenderingPipeline.INSTANCE;
			LOGGER.error("Shader rendering failed; shaders disabled until the next reload", e);
			try {
				failed.destroy();
			} catch (RuntimeException cleanupFailure) {
				LOGGER.error("Failed to release the stopped shader pipeline", cleanupFailure);
			}
		}
	}

	@Override
	public void destroy() {
		RenderingPipeline pipeline = current;
		current = VanillaRenderingPipeline.INSTANCE;
		renderingLevel = null;
		FrameState.resetHistory();
		if (pipeline != null && pipeline != VanillaRenderingPipeline.INSTANCE) {
			pipeline.destroy();
		}
	}

	/**
	 * The GPU backend the future rendering pipeline would need to target. See
	 * {@code dev.tapetum.shaders.pipeline.backend} for why this isn't assumed to be OpenGL.
	 */
	public GpuBackendType getActiveBackend() {
		return GpuBackendType.detectActive().orElse(GpuBackendType.UNKNOWN);
	}

	/**
	 * Re-evaluates which pipeline should be active based on the current config and shaderpack
	 * selection. Call this after the selection changes in the GUI, or on startup.
	 */
	@Override
	public void reload() {
		lastFailure = null;
		renderingLevel = Minecraft.getInstance().level;
		FrameState.resetHistory();
		reloadNativeScreenSpacePipeline();
	}

	@Override
	public void syncSelection() {
		// No render callback runs after disconnect, so release world-owned targets from the tick.
		if (renderingLevel != null && Minecraft.getInstance().level == null) {
			destroy();
		}
	}

	@Override
	public Optional<Throwable> lastFailure() {
		return Optional.ofNullable(lastFailure);
	}

	@Override
	public Optional<Screen> openPackOptions(Screen parent) {
		return Optional.empty();
	}

	/** Native Tapetum pipeline. Geometry and shadow stages are added behind this boundary. */
	private void reloadNativeScreenSpacePipeline() {
		RenderingPipeline previous = current;
		current = VanillaRenderingPipeline.INSTANCE;
		try {
			if (previous != null) {
				previous.destroy();
			}
		} catch (RuntimeException error) {
			LOGGER.error("Failed to destroy the previous experimental pipeline; falling back to vanilla rendering",
				error);
		}

		if (!TapetumShaders.getConfig().areShadersEnabled()) {
			return;
		}

		TapetumShaders.getConfig().getShaderPackName().ifPresent(packName -> {
			var loaded = TapetumShaders.getShaderpackManager().load(packName);

			if (loaded.isEmpty()) {
				lastFailure = new IOException("Selected shaderpack could not be loaded: " + packName);
				LOGGER.warn("Selected shaderpack '{}' could not be loaded", packName);
				return;
			}

			ShaderPack pack = loaded.get();
			try {
				current = buildPipeline(pack);
			} catch (RuntimeException e) {
				lastFailure = e;
				// Distinguish this from a close() failure: attributing a pipeline-construction crash
				// to "failed to close the handle" sends anyone reading the log the wrong way entirely.
				LOGGER.error("Failed to build a rendering pipeline for '{}' - falling back to vanilla rendering",
					packName, e);
				current = VanillaRenderingPipeline.INSTANCE;
			} finally {
				try {
					pack.close();
				} catch (IOException e) {
					LOGGER.error("Failed to close shaderpack handle for '{}'", packName, e);
				}
			}
		});
	}

	/**
	 * Gathers the values behind the OptiFine macro set — Minecraft's version, the OS, and the GL
	 * vendor — from the live game. See {@link ShaderMacros} for why packs need these to compile at
	 * all on some drivers, not merely to pick nicer code paths.
	 */
	private static ShaderMacros buildMacros() {
		// Read the version through the loader rather than Minecraft's own SharedConstants: where that
		// version lives has moved between the releases this mod targets, and the loader's view of it
		// has not.
		String minecraftVersion = FabricLoader.getInstance()
			.getModContainer("minecraft")
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("");

		// GL_VENDOR is only readable with a context current, which holds here: pipelines are only
		// ever built on the render thread.
		String glVendor;
		try {
			glVendor = GlStateManager._getString(GL11.GL_VENDOR);
		} catch (RuntimeException e) {
			LOGGER.warn("Could not read GL_VENDOR; shaderpacks will see MC_GL_VENDOR_OTHER", e);
			glVendor = null;
		}

		return new ShaderMacros(
			ShaderMacros.encodeMinecraftVersion(minecraftVersion),
			ShaderMacros.OperatingSystem.detect(System.getProperty("os.name")),
			ShaderMacros.GlVendor.detect(glVendor));
	}

	private RenderingPipeline buildPipeline(ShaderPack pack) {
		GpuBackendType backend = getActiveBackend();
		if (backend != GpuBackendType.OPENGL) {
			lastFailure = new UnsupportedOperationException("Tapetum currently requires OpenGL; active backend: " + backend);
			LOGGER.warn("'{}' selected, but the active GPU backend ({}) isn't OpenGL - this build only knows how "
				+ "to compile GLSL through the OpenGL-specific path - vanilla rendering will be used",
				pack.getName(), backend);
			return VanillaRenderingPipeline.INSTANCE;
		}

		ShaderDimension dimension = ShaderDimension.fromDimensionId(renderingLevel == null ? null
			: renderingLevel.dimension().location().toString());
		List<ShaderProgramChain.Pass> chain = ShaderProgramChain.discover(pack, dimension);

		if (chain.isEmpty()) {
			LOGGER.info("'{}' selected ({} properties parsed) but ships no screen-space programs in '{}/' or at "
				+ "the shaders root - vanilla rendering will be used", pack.getName(),
				pack.getProperties().size(), dimension.folderName());
			return VanillaRenderingPipeline.INSTANCE;
		}

		ShaderMacros macros = buildMacros();
		List<CompositeChainPipeline.PassSource> prepared = new ArrayList<>();
		int bufferCount = 1;
		// Gathered across every pass, because a pack declares its formats once in a shared include and
		// that include reaches each pass independently.
		Map<Integer, ColorTextureFormat> formats = new HashMap<>();

		for (ShaderProgramChain.Pass pass : chain) {
			Optional<String> fragment;
			try {
				fragment = pack.readCompilableProgramSource(
					pass.fragment(), GlslCompatPatcher.Stage.FRAGMENT, dimension, macros);
			} catch (IOException e) {
				LOGGER.error("Failed to read '{}' from '{}'", pass.fragment(), pack.getName(), e);
				return VanillaRenderingPipeline.INSTANCE;
			} catch (GlslIncludeException e) {
				LOGGER.error("Failed to expand #include directives in '{}' from '{}': {}",
					pass.fragment(), pack.getName(), e.getMessage());
				return VanillaRenderingPipeline.INSTANCE;
			}

			if (fragment.isEmpty()) {
				// discover() found the file, so this only happens if it vanished mid-load.
				LOGGER.warn("'{}' from '{}' disappeared while the chain was being built - skipping it",
					pass.fragment(), pack.getName());
				continue;
			}

			String fragmentSource = fragment.get();
			String vertexSource = packVertexShader(pack, pass, macros, dimension)
				.orElseGet(() -> GlslStageLinkage.buildFullScreenVertexShader(
					fragmentSource, GlslStageLinkage.declaredVersion(fragmentSource)));
			vertexSource = alignVersions(vertexSource, fragmentSource, pass.name());

			// Sized from every branch, not just the one that would run: a pack writing from a
			// different #ifdef arm would otherwise target a buffer that was never allocated.
			bufferCount = Math.max(bufferCount, DrawBuffers.requiredBufferCount(fragmentSource));
			formats.putAll(ColorTextureFormat.parse(fragmentSource));

			prepared.add(new CompositeChainPipeline.PassSource(
				pass.name(), vertexSource, fragmentSource, DrawBuffers.parse(fragmentSource)));
		}

		if (prepared.isEmpty()) {
			return VanillaRenderingPipeline.INSTANCE;
		}

		// Compile-only, and deliberately before the chain is built: if a gbuffers program is what
		// finally takes the driver down, the log says so above the line that says the chain is ready.
		GbufferCompileAudit.run(pack, macros, dimension);

		try {
			RenderingPipeline pipeline =
				new CompositeChainPipeline(pack.getName(), prepared, bufferCount, formats,
					sunPathRotation(pack.getProperties()),
					floatProperty(pack.getProperties(), "shadowDistance", 160.0f),
					readCustomUniforms(pack));
			LOGGER.info("'{}': compiled {} of {} chain passes ({}) - {} colortex buffers",
				pack.getName(), prepared.size(), chain.size(),
				prepared.stream().map(CompositeChainPipeline.PassSource::name).toList(), bufferCount);
			return pipeline;
		} catch (GlShaderCompileException e) {
			lastFailure = e;
			LOGGER.error("Failed to compile the program chain from '{}'", pack.getName(), e);
			return VanillaRenderingPipeline.INSTANCE;
		}
	}

	/**
	 * The pack's {@code sunPathRotation}, in degrees.
	 *
	 * <p>Parsed leniently: a malformed value falls back to zero rather than failing pack loading over
	 * one cosmetic property. All five packs surveyed here leave it unset.</p>
	 */
	private static float sunPathRotation(ShaderProperties properties) {
		return floatProperty(properties, "sunPathRotation", 0.0f);
	}

	/** Reads a numeric property leniently: a malformed value falls back rather than failing the pack. */
	private static float floatProperty(ShaderProperties properties, String key, float fallback) {
		String declared = properties.getOrDefault(key, Float.toString(fallback)).trim();
		try {
			return Float.parseFloat(declared);
		} catch (NumberFormatException e) {
			LOGGER.warn("Ignoring unparseable {} '{}'", key, declared);
			return fallback;
		}
	}

	/**
	 * The pack's own vertex shader for this pass, adapted for a core-profile full-screen triangle.
	 *
	 * <p>Every pass across the five packs surveyed here ships one, and they do real work: BSL's
	 * {@code deferred1.vsh} computes {@code sunVec}, {@code upVec} and {@code eastVec}, the vectors
	 * its whole lighting model reads. Generating a replacement instead — which zeroes any varying it
	 * cannot fill — zeroed the sun direction and took the image to black. Falling back to the
	 * generator stays right for a pass that genuinely ships no vertex half.</p>
	 */
	private static Optional<String> packVertexShader(ShaderPack pack, ShaderProgramChain.Pass pass,
			ShaderMacros macros, ShaderDimension dimension) {
		try {
			return pack.readCompilableProgramSource(
					pass.vertex(), GlslCompatPatcher.Stage.VERTEX, dimension, macros)
				.map(FullScreenVertexAdapter::adapt);
		} catch (IOException | GlslIncludeException e) {
			LOGGER.warn("Could not use '{}' from '{}' ({}); generating a vertex stage instead",
				pass.vertex(), pack.getName(), e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Raises the vertex stage to the fragment stage's {@code #version} when they disagree.
	 *
	 * <p>GLSL refuses to link two stages compiled at different versions, and the two halves can drift
	 * apart legitimately: each is patched independently, and a library included by only one of them
	 * can request a higher version. Raising rather than lowering is the safe direction — a shader
	 * compiled at a newer version still works, one compiled at an older one may not.</p>
	 */
	static String alignVersions(String vertexSource, String fragmentSource, String passName) {
		String aligned = GlslStageLinkage.alignVersions(vertexSource, fragmentSource, passName);
		if (!aligned.equals(vertexSource)) {
			int vertexVersion = GlslStageLinkage.declaredVersion(vertexSource);
			int fragmentVersion = GlslStageLinkage.declaredVersion(fragmentSource);
			LOGGER.debug("'{}': raising vertex stage from #version {} to {} to match the fragment stage",
				passName, vertexVersion, fragmentVersion);
		}
		return aligned;
	}

	/**
	 * The uniforms the pack computes for itself in {@code shaders.properties}.
	 *
	 * <p>BSL declares forty-one, MakeUp thirty, Mellow thirty-two; both Complementary packs declare
	 * none. The GPU audit found eight of BSL's arriving as zero, {@code timeBrightness} among them —
	 * a daylight multiplier, so zero meant the pack's sun never rose.</p>
	 *
	 * <p>Read from the raw file text: {@link java.util.Properties} loses declaration order, which is
	 * how these build on one another, and collapses the duplicate keys BSL relies on.</p>
	 */
	private static CustomUniforms readCustomUniforms(ShaderPack pack) {
		List<String> unparseable = new ArrayList<>();
		CustomUniforms uniforms = CustomUniforms.parse(pack.getPropertiesText(), unparseable);

		if (!unparseable.isEmpty()) {
			// Named rather than counted: one unreadable definition is usually a newer OptiFine
			// feature, and knowing which one is the difference between a lead and a shrug.
			LOGGER.warn("'{}': {} custom uniform(s) could not be parsed and will read as zero: {}",
				pack.getName(), unparseable.size(), unparseable);
		}
		if (!uniforms.isEmpty()) {
			LOGGER.info("'{}': evaluating {} custom uniform definition(s), {} uploaded",
				pack.getName(), uniforms.definitions().size(), uniforms.uploaded().size());
		}
		return uniforms;
	}
}
