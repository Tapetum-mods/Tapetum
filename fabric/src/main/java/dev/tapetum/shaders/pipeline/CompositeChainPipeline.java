package dev.tapetum.shaders.pipeline;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.tapetum.shaders.compat.VersionCompat;
import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.pipeline.backend.gl.FullScreenTriangle;
import dev.tapetum.shaders.pipeline.backend.gl.GlProgram;
import dev.tapetum.shaders.pipeline.backend.gl.GlRenderState;
import dev.tapetum.shaders.pipeline.backend.gl.FramebufferBindings;
import dev.tapetum.shaders.pipeline.backend.gl.GlShaderCompileException;
import dev.tapetum.shaders.pipeline.backend.gl.NoiseTexture;
import dev.tapetum.shaders.pipeline.backend.gl.ShadowTargets;
import dev.tapetum.shaders.pipeline.backend.gl.RenderTargets;
import dev.tapetum.shaders.uniform.FrameState;
import dev.tapetum.shaders.uniform.FrameClock;
import dev.tapetum.shaders.uniform.ShaderUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import dev.tapetum.shaders.shaderpack.glsl.ColorTextureFormat;
import dev.tapetum.shaders.shaderpack.uniform.CustomUniforms;
import dev.tapetum.shaders.uniform.ShaderExpressionContext;
import dev.tapetum.shaders.uniform.CelestialAngles;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a shaderpack's screen-space chain — every {@code deferred}, then every {@code composite},
 * then {@code final} — over the frame Minecraft rendered.
 *
 * <p>This is what a single-pass build could never be. A pack's look is not produced by {@code final};
 * {@code final} is a tone-mapping stage that reads what the chain before it computed. Complementary
 * runs nine such passes, BSL eleven, Mellow fifteen, each writing to the {@code colortex} buffers the
 * next one reads. Running only the last of them over a finished vanilla frame applies the last step
 * of a recipe to an empty pan.</p>
 *
 * <p>What is still missing, and why the result will not match the pack: the {@code gbuffers_*}
 * programs. Those replace the shaders Minecraft uses to draw geometry, so they cannot be scheduled as
 * full-screen passes — they need the terrain renderer (and, with Sodium installed, Sodium's) to hand
 * over its vertex pipeline. Without them the {@code colortex} buffers a composite reads hold the
 * vanilla-lit scene rather than the pack's own G-buffer data, so the chain computes real effects from
 * the wrong inputs.</p>
 */
public final class CompositeChainPipeline implements RenderingPipeline {
	private static final Logger LOGGER = LoggerFactory.getLogger("Tapetum Shaders");

	/**
	 * How many {@code colortex} samplers to offer each pass.
	 *
	 * <p>OptiFine allows sixteen; binding every one costs a uniform lookup and a texture bind per
	 * pass per frame, and packs here reach ten at most. Names beyond what the pool holds resolve to
	 * nothing, which {@code GlProgram} skips silently.</p>
	 */
	private static final int SAMPLER_COUNT = 16;

	/**
	 * Depth sampler names packs read. All receive Minecraft's single depth buffer — see
	 * {@link #bindDepthSamplers} for why that is an approximation rather than a substitution.
	 */
	private static final String[] DEPTH_SAMPLERS = { "depthtex0", "depthtex1", "depthtex2" };

	/** One compiled pass and the buffers it declares it writes. */
	private record CompiledPass(String name, GlProgram program, List<Integer> drawBuffers) {
	}

	private final String packName;
	private final List<CompiledPass> passes;
	private final FullScreenTriangle triangle;
	private final RenderTargets targets = new RenderTargets();
	private final NoiseTexture noise = new NoiseTexture();
	private final ShadowTargets shadow = new ShadowTargets();

	/** The uniforms the pack computes for itself, and the state their expressions need. */
	private final CustomUniforms customUniforms;
	private final ShaderExpressionContext expressionContext = new ShaderExpressionContext();

	/** This frame's custom values, evaluated once and uploaded to every pass. */
	private Map<String, Float> customValues = Map.of();

	/**
	 * The pack's {@code sunPathRotation}, in degrees, tilting the sun's arc away from vertical.
	 * Declared in {@code shaders.properties}; zero for every pack surveyed here, but honouring it is
	 * a one-line cost and a pack that sets it looks obviously wrong without it.
	 */
	private final float sunPathRotation;

	/** Half-extent of the shadow frustum, from the pack's {@code shadowDistance}. */
	private final float shadowDistance;

	/** How fast wetness follows rainStrength; one frame's share of a roughly one-second trail. */
	private static final float WETNESS_FOLLOW_RATE = 0.02f;

	private static final int TICKS_PER_SECOND = 20;

	private float wetness;

	/** OptiFine reports light on a 0-240 scale; Minecraft stores 0-15. */
	private static final int LIGHT_SCALE = 16;

	/** How fast the smoothed eye brightness trails the raw value, per frame. */
	private static final float EYE_BRIGHTNESS_FOLLOW_RATE = 0.05f;

	private float smoothedBlockLight;
	private float smoothedSkyLight;
	private final FrameClock clock = new FrameClock();

	private final long createdAtNanos = System.nanoTime();
	private boolean loggedFirstFrame;

	/**
	 * How many frames to let pass before reporting.
	 *
	 * <p>Reporting on the very first frame was worse than useless: at startup the chain runs before
	 * any terrain has been built, so the scene texture is genuinely empty and the depth buffer
	 * genuinely at the far plane. The trace then said "everything is black" about a frame where black
	 * was the correct answer, which is exactly the kind of reading that sends the next hour in the
	 * wrong direction. Half a second in, the world is drawn and the numbers mean something.</p>
	 */
	private static final int TRACE_AFTER_FRAMES = 30;

	/**
	 * @param compiled the chain in run order, already patched for core profile
	 * @param bufferCount how many {@code colortex} buffers the pack references, so the pool is sized
	 *                    to the pack rather than to OptiFine's maximum
	 * @param formats the pixel format each buffer was declared with, so signed and floating-point
	 *                buffers are not silently clamped into RGBA8
	 * @param sunPathRotation the pack's {@code sunPathRotation} in degrees, usually zero
	 * @param customUniforms the values the pack computes for itself from the loader's
	 */
	public CompositeChainPipeline(String packName, List<PassSource> compiled, int bufferCount,
			Map<Integer, ColorTextureFormat> formats, float sunPathRotation, float shadowDistance,
			CustomUniforms customUniforms) throws GlShaderCompileException {
		this.packName = packName;
		this.sunPathRotation = sunPathRotation;
		this.shadowDistance = shadowDistance;
		this.customUniforms = customUniforms;
		this.triangle = new FullScreenTriangle();
		this.targets.setBufferCount(bufferCount);
		this.targets.setFormats(formats);

		List<CompiledPass> built = new java.util.ArrayList<>();
		try {
			for (PassSource source : compiled) {
				GlProgram program = GlProgram.link(
					packName + "/" + source.name(), source.vertexSource(), source.fragmentSource());
				built.add(new CompiledPass(source.name(), program, source.drawBuffers()));
			}
		} catch (GlShaderCompileException e) {
			// Whatever linked before the failure is already live on the GPU; nothing else holds a
			// reference to it, so it would leak on every failed reload.
			built.forEach(pass -> pass.program().close());
			triangle.close();
			throw e;
		}

		this.passes = List.copyOf(built);
	}

	/** A pass's already-prepared source, handed in by whoever compiled the chain. */
	public record PassSource(String name, String vertexSource, String fragmentSource,
			List<Integer> drawBuffers) {
	}

	@Override
	public void beginLevelRendering() {
		// The chain runs once the frame is otherwise complete.
	}

	@Override
	public void finalizeLevelRendering() {
		try (GlRenderState state = GlRenderState.capture()) {
			state.prepareForFullscreen();
			renderChain();
		}
	}

	private void renderChain() {
		RenderTarget mainTarget = VersionCompat.mainRenderTarget();
		if (!targets.resize(mainTarget.width, mainTarget.height)) {
			return;
		}
		clock.advance(System.nanoTime());

		// Lazy allocation binds textures. Do it before binding pass inputs, otherwise allocating
		// noise or shadow maps can overwrite the depth sampler already installed on that unit.
		noise.id();
		shadow.depth();
		shadow.color();

		// Seed the buffers from what Minecraft drew, naming its texture explicitly rather than
		// trusting whatever framebuffer is bound - see captureSceneInto for why that mattered.
		//
		// Every buffer is seeded, not just colortex0. The others would otherwise start black, and a
		// pass reading colortex1 for what it expects to be gbuffer data would compute on nothing. The
		// scene is not what those buffers should hold - that needs the gbuffers programs - but it is
		// real data rather than a void, so passes produce a recognisable image instead of darkness.
		int sceneTexture = VersionCompat.colorTextureId(mainTarget);
		if (sceneTexture != 0) {
			for (int index = 0; index < targets.bufferCount(); index++) {
				targets.captureSceneInto(index, sceneTexture);
			}
		}

		// Read the image back at each step, once per pack activation. Reasoning about where a chain
		// turns black from the source alone is guesswork; this reports what the pixels actually are.
		StringBuilder trace =
			loggedFirstFrame || clock.frame() < TRACE_AFTER_FRAMES || !traceEnabled()
				? null : new StringBuilder();
		if (trace != null) {
			trace.append("\n  main target       ").append(mainTarget.width).append('x').append(mainTarget.height)
				.append(", chain targets ").append(targets.width()).append('x').append(targets.height());
			trace.append("\n  scene texture     ").append(targets.describeTexture(sceneTexture));
			trace.append("\n  colortex0 texture ").append(targets.describeTexture(targets.readTexture(0)));
			trace.append("\n  capture           ").append(targets.describeCapture(sceneTexture));
			trace.append("\n  depthtex0         ").append(
				VersionCompat.depthTextureId(mainTarget) != 0
					? targets.describeDepth(VersionCompat.depthTextureId(mainTarget)) : "absent");
			trace.append("\n  minecraft scene   ").append(describePixel(sceneTexture));
			trace.append("\n  colortex0 seeded  ").append(describePixel(targets.readTexture(0)));
		}

		evaluateCustomUniforms();

		for (CompiledPass pass : passes) {
			runPass(pass);
			if (trace != null) {
				List<Integer> written = pass.drawBuffers().isEmpty() ? List.of(0) : pass.drawBuffers();
				trace.append("\n  after ").append(String.format("%-12s", pass.name()));
				for (int index : written) {
					trace.append(" colortex").append(index).append('[')
						.append(describePixel(targets.readTexture(index))).append(']');
				}
			}
		}

		// Hand the result back to Minecraft. Without this the whole chain is computed and thrown
		// away: it wrote only into private buffers, and Minecraft would blit its own untouched scene
		// over the screen a moment later.
		presentResult(mainTarget);

		if (!loggedFirstFrame && clock.frame() >= TRACE_AFTER_FRAMES) {
			// The uniform audit, always on. Unlike the pixel trace it reads no pixels, so it costs no
			// GPU stall - and it is the only thing that can tell a name this loader never supplies from
			// a name it supplies under the wrong spelling.
			StringBuilder audit = new StringBuilder();
			for (CompiledPass pass : passes) {
				audit.append("\n    ").append(pass.name()).append(": ")
					.append(pass.program().auditReport());
				pass.program().stopAuditing();
			}
			LOGGER.info("'{}': uniform audit{}", packName, audit);
		}

		if (!loggedFirstFrame && clock.frame() >= TRACE_AFTER_FRAMES && trace == null) {
			// Still report once that the chain ran, just without the expensive read-backs.
			loggedFirstFrame = true;
			LOGGER.info("'{}': ran {} chain passes into {} colortex buffers at {}x{}"
					+ " (set diagnosticTrace=true in config/tapetumshaders.properties for a pixel trace)",
				packName, passes.size(), targets.bufferCount(), targets.width(), targets.height());
		}

		if (trace != null) {
			loggedFirstFrame = true;
			trace.append("\n  presented scene   ").append(describePixel(sceneTexture));
			LOGGER.info("'{}': ran {} chain passes into {} colortex buffers at {}x{}."
					+ " Centre pixel through the chain:{}",
				packName, passes.size(), targets.bufferCount(), targets.width(), targets.height(),
				trace);
		}
	}

	/**
	 * How many pixels the one-shot trace may read back.
	 *
	 * <p>Each read stalls until the GPU has drained every queued command. Thirteen of them already
	 * cost four seconds of server lag on the frame the pack is activated; a fifteen-pass pack writing
	 * four buffers each would ask for sixty. This is a debugging aid, not a feature — it must be
	 * removed or gated before a stable release rather than merely bounded.</p>
	 */
	private static final int MAX_TRACE_SAMPLES = 24;

	/**
	 * Whether to run the one-shot chain trace at all, off unless {@code -Dtapetum.trace=true}.
	 *
	 * <p>Each sample stalls until the GPU has drained every queued command, and thirteen of them cost
	 * four seconds of server lag on the frame a pack is activated. That is an acceptable price for a
	 * developer chasing a black screen and an unacceptable one for a player who just picked a
	 * shaderpack, so it is opt-in rather than merely bounded.</p>
	 */
	private static boolean traceEnabled() {
		return Boolean.getBoolean("tapetum.trace")
			|| TapetumShaders.getConfig().isDiagnosticTrace();
	}

	private int traceSamples;

	/** The centre pixel of {@code texture} as readable text, for the one-shot chain trace. */
	private String describePixel(int texture) {
		if (++traceSamples > MAX_TRACE_SAMPLES) {
			return "(not sampled)";
		}
		int[] pixel = targets.samplePixel(texture, targets.width() / 2, targets.height() / 2);
		if (pixel == null) {
			return "(unreadable)";
		}
		return String.format("r=%3d g=%3d b=%3d a=%3d%s",
			pixel[0], pixel[1], pixel[2], pixel[3],
			pixel[0] == 0 && pixel[1] == 0 && pixel[2] == 0 ? "   <- BLACK" : "");
	}

	/**
	 * Copies the last pass's output into Minecraft's scene texture, which is what finally reaches the
	 * screen.
	 *
	 * <p>The chain's result is taken from {@code colortex0}: that is where OptiFine's {@code final}
	 * pass writes, and it is what every pack surveyed here declares
	 * ({@code final} → {@code DRAWBUFFERS:0} in all five).</p>
	 */
	private void presentResult(RenderTarget mainTarget) {
		int destination = VersionCompat.colorTextureId(mainTarget);
		if (destination == 0) {
			// A non-OpenGL backend; PipelineManager refuses to build this pipeline for one, so this
			// only guards that check being loosened later.
			return;
		}
		targets.presentTo(0, destination);
	}

	/** Runs one pass into every buffer it declares, and publishes them for the next one. */
	private void runPass(CompiledPass pass) {
		List<Integer> written = pass.drawBuffers().isEmpty() ? List.of(0) : pass.drawBuffers();

		try (FramebufferBindings framebufferScope = targets.bindForWriting(written)) {
			pass.program().use();
			bindInputs(pass.program());
			triangle.draw();
		}

		// Publish before the next pass reads: the writes went to the back textures, and only flipping
		// makes them the ones readTexture() hands out. Every buffer the pass declared has to flip -
		// flipping only the first left the rest showing the previous frame's contents.
		for (int index : written) {
			targets.flip(index);
		}

	}

	/** Offers every pass the buffers the previous ones wrote, plus the standard uniform set. */
	private void bindInputs(GlProgram program) {
		// Depth first, and deliberately so. Texture units are capped at twelve by vanilla's cache, and
		// an earlier version handed them out to colortex0..15 before ever reaching the depth samplers.
		// Complementary's deferred1 declares ten colortex plus two depth textures, so depthtex1 was
		// refused for want of a unit - and depth is the one input that must never lose that race.
		//
		// It gates everything downstream: a pack reads z to decide sky from terrain and to rebuild
		// world position from the depth buffer. An unbound sampler reads 0, which makes z == 1.0 false
		// for every pixel, so the sky branch is never taken, the terrain branch runs everywhere, and
		// every position reconstructs onto the near plane. That is terrain lighting evaluated five
		// centimetres from the camera with no normals - which is black.
		int unit = bindDepthSamplers(program, 0);
		unit = bindShadowSamplers(program, unit);

		for (int index = 0; index < SAMPLER_COUNT && unit < GlProgram.maxTextureUnits(); index++) {
			int texture = targets.readTexture(index);
			if (texture != 0 && program.bindSampler("colortex" + index, unit, texture)) {
				unit++;
			}
		}

		// The pre-1.17 spellings. Complementary's deferred1 declares gaux2 and gaux4 and, until these
		// were bound, received nothing for either: OptiFine's gaux1 through gaux4 are colortex4
		// through colortex7, not colortex1 through colortex4.
		for (int alias = 0; alias < LEGACY_BUFFER_ALIASES.length; alias++) {
			if (unit >= GlProgram.maxTextureUnits()) {
				break;
			}
			int texture = targets.readTexture(alias);
			if (texture != 0 && program.bindSampler(LEGACY_BUFFER_ALIASES[alias], unit, texture)) {
				unit++;
			}
		}

		// Thirty-eight of the forty-nine surveyed passes declare noisetex. Left unbound it resolves to
		// texture unit 0, so a pack sampling "noise" was handed whatever sat there instead.
		if (unit < GlProgram.maxTextureUnits() && program.bindSampler("noisetex", unit, noise.id())) {
			unit++;
		}
		program.setUniform("noiseTextureResolution", (float) NoiseTexture.RESOLUTION);

		bindStandardUniforms(program, targets.width(), targets.height());
	}

	/**
	 * OptiFine's pre-1.17 buffer names, indexed by the {@code colortex} they alias.
	 *
	 * <p>{@code gaux1} is {@code colortex4}, not {@code colortex1} — the four {@code gaux} buffers
	 * follow the four named ones. Getting that offset wrong hands a pack its lighting buffer where it
	 * asked for its normals.</p>
	 */
	private static final String[] LEGACY_BUFFER_ALIASES = {
		"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"
	};

	/**
	 * Binds the shadow map, which thirty-five of the forty-nine surveyed passes sample.
	 *
	 * <p>Bound early, just after depth, and for the same reason: an unbound {@code sampler2DShadow}
	 * reads 0, and for a comparison sampler 0 means the test <em>failed</em> — fully shadowed. A pack
	 * multiplies its sunlight by that, so losing this race costs the direct light across most of the
	 * chain.</p>
	 *
	 * @return the next free texture unit
	 */
	private int bindShadowSamplers(GlProgram program, int firstUnit) {
		int unit = firstUnit;
		for (String name : SHADOW_DEPTH_SAMPLERS) {
			if (unit < GlProgram.maxTextureUnits() && program.bindSampler(name, unit, shadow.depth())) {
				unit++;
			}
		}
		for (String name : SHADOW_COLOR_SAMPLERS) {
			if (unit < GlProgram.maxTextureUnits() && program.bindSampler(name, unit, shadow.color())) {
				unit++;
			}
		}
		return unit;
	}

	/** Both depth names get the same map: nothing here distinguishes opaque from translucent yet. */
	private static final String[] SHADOW_DEPTH_SAMPLERS = { "shadowtex0", "shadowtex1", "shadow" };

	private static final String[] SHADOW_COLOR_SAMPLERS = { "shadowcolor0", "shadowcolor1", "shadowcolor" };

	/**
	 * Binds Minecraft's depth buffer to every depth sampler name a pack might read.
	 *
	 * @return the next free texture unit
	 */
	private int bindDepthSamplers(GlProgram program, int firstUnit) {
		int depth = VersionCompat.depthTextureId(VersionCompat.mainRenderTarget());
		if (depth == 0) {
			return firstUnit;
		}

		int unit = firstUnit;
		for (String name : DEPTH_SAMPLERS) {
			if (unit >= GlProgram.maxTextureUnits()) {
				return unit;
			}
			if (program.bindSampler(name, unit, depth)) {
				unit++;
			}
		}
		return unit;
	}

	private void bindStandardUniforms(GlProgram program, int width, int height) {
		program.setUniform("viewWidth", (float) width);
		program.setUniform("viewHeight", (float) height);
		program.setUniform("aspectRatio", ShaderUniforms.aspectRatio(width, height));
		program.setUniform("frameTimeCounter",
			ShaderUniforms.frameTimeCounter(System.nanoTime() - createdAtNanos));
		program.setUniform("frameCounter", clock.frame());

		Minecraft minecraft = Minecraft.getInstance();
		var level = minecraft.level;
		if (level != null) {
			long gameTime = level.getGameTime();
			program.setUniform("worldTime", ShaderUniforms.worldTime(gameTime));
			program.setUniform("worldDay", ShaderUniforms.worldDay(gameTime));
			program.setUniform("rainStrength", ShaderUniforms.rainStrength(level.getRainLevel(1.0f)));
			// Mellow's deferred1 reads this and got nothing: the first name the audit has ever
			// reported as "not supplied" since the custom-uniform work landed. Clamped like
			// rainStrength, for the same reason - packs feed it straight into mix().
			program.setUniform("thunderStrength",
				ShaderUniforms.rainStrength(level.getThunderLevel(1.0f)));
		}

		bindFogParameters(program);

		// Iris built-ins the packs declare with a GLSL default. Complementary reads all three, and
		// the audit listed them as not supplied on all nine of its passes.
		program.setUniform("isElytraFlying",
			minecraft.player != null && minecraft.player.isFallFlying() ? 1 : 0);
		// The pack reads this as "isnan(cloudHeight) ? 192.0 : cloudHeight", its own comment saying
		// Iris returns NaN when there are no clouds. 192 is where vanilla puts them.
		program.setUniform("cloudHeight", 192.0f);
		// A bool the pack only uses as "isEnderDragonDead = !heavyFog". The dimension is hardcoded to
		// the Overworld, so false is the honest value rather than a guess.
		program.setUniform("heavyFog", 0);

		// Packs declare "uniform vec3 cameraPosition". Three scalars named cameraPositionX/Y/Z, as an
		// earlier version supplied, match no declaration in any pack and were silently discarded.
		Vec3 eye = FrameState.cameraPosition();
		program.setUniform("cameraPosition", (float) eye.x, (float) eye.y, (float) eye.z);
		program.setUniform("eyeAltitude", (float) eye.y);

		Vec3 previousEye = FrameState.previousCameraPosition();
		program.setUniform("previousCameraPosition",
			(float) previousEye.x, (float) previousEye.y, (float) previousEye.z);

		// The matrices, which is what a screen-space effect cannot work without. Every one of these
		// was previously absent, so the pack received a zero matrix and reconstructed every pixel's
		// position as the origin.
		program.setUniform("gbufferProjection", FrameState.projection());
		program.setUniform("gbufferProjectionInverse", FrameState.projectionInverse());
		program.setUniform("gbufferModelView", FrameState.modelView());
		program.setUniform("gbufferModelViewInverse", FrameState.modelViewInverse());
		program.setUniform("gbufferPreviousProjection", FrameState.previousProjection());
		program.setUniform("gbufferPreviousModelView", FrameState.previousModelView());

		program.setUniform("near", FrameState.near());
		program.setUniform("far", FrameState.far());
		program.setUniform("isEyeInWater", FrameState.eyeInWater());

		// Minecraft hands renderLevel the exact colour it fogs with, so this is read rather than
		// approximated. An unset fogColor is black, which turns a pack's distance fog into a
		// darkening instead of a blend towards the sky.
		Vector3f fog = FrameState.fogColor();
		program.setUniform("fogColor", fog.x, fog.y, fog.z);
		program.setUniform("skyColor", fog.x, fog.y, fog.z);

		bindCelestialPositions(program);
		bindShadowMatrices(program);
		bindPlayerUniforms(program);

		// Last, deliberately: a pack that defines a name for itself overrides whatever this loader
		// supplied under it. BSL redefines timeAngle, shadowFade and blindFactor that way, and under
		// OptiFine its own value is the one that counts.
		for (Map.Entry<String, Float> custom : customValues.entrySet()) {
			program.setUniform(custom.getKey(), custom.getValue());
		}
	}

	/**
	 * Works out the pack's own uniforms for this frame.
	 *
	 * <p>Once per frame rather than once per pass: BSL declares forty-one definitions over a chain
	 * five deep, and every pass would otherwise recompute the identical result.</p>
	 */
	private void evaluateCustomUniforms() {
		if (customUniforms.isEmpty()) {
			return;
		}

		var level = Minecraft.getInstance().level;
		Vec3 eye = FrameState.cameraPosition();

		Map<String, Float> frameValues = new HashMap<>();
		frameValues.put("sunAngle", CelestialAngles.sunAngle(FrameState.skyAngle()));
		frameValues.put("shadowAngle", CelestialAngles.shadowAngle(FrameState.skyAngle()));
		frameValues.put("frameCounter", (float) clock.frame());
		frameValues.put("frameTime", clock.seconds());
		frameValues.put("frameTimeCounter",
			ShaderUniforms.frameTimeCounter(System.nanoTime() - createdAtNanos));
		frameValues.put("worldTime", level == null ? 0.0f : ShaderUniforms.worldTime(level.getGameTime()));
		frameValues.put("worldDay", level == null ? 0.0f : ShaderUniforms.worldDay(level.getGameTime()));
		frameValues.put("rainStrength",
			level == null ? 0.0f : ShaderUniforms.rainStrength(level.getRainLevel(1.0f)));
		frameValues.put("thunderStrength",
			level == null ? 0.0f : ShaderUniforms.rainStrength(level.getThunderLevel(1.0f)));
		frameValues.put("wetness", wetness);
		frameValues.put("cameraPosition.x", (float) eye.x);
		frameValues.put("cameraPosition.y", (float) eye.y);
		frameValues.put("cameraPosition.z", (float) eye.z);
		frameValues.put("eyeAltitude", (float) eye.y);
		frameValues.put("eyeBrightness.x", smoothedBlockLight);
		frameValues.put("eyeBrightness.y", smoothedSkyLight);
		frameValues.put("isEyeInWater", (float) FrameState.eyeInWater());
		frameValues.put("blindness", effectStrength(MobEffects.BLINDNESS));
		frameValues.put("darknessFactor", 0.0f); // Darkness does not exist in 1.16.5.
		frameValues.put("nightVision", effectStrength(MobEffects.NIGHT_VISION));
		frameValues.put("viewWidth", (float) targets.width());
		frameValues.put("viewHeight", (float) targets.height());
		frameValues.put("aspectRatio", ShaderUniforms.aspectRatio(targets.width(), targets.height()));
		frameValues.put("pi", (float) Math.PI);
		frameValues.put("e", (float) Math.E);

		expressionContext.beginFrame(frameValues);
		customValues = customUniforms.evaluate(expressionContext);
	}

	/**
	 * The player-state uniforms packs gate effects on.
	 *
	 * <p>None of these is decorative. An unsupplied uniform is zero, so a pack testing
	 * {@code if (blindness > 0.0)} never sees blindness, and one dividing by {@code screenBrightness}
	 * divides by zero. Supplying them is cheap; leaving them out fails quietly.</p>
	 */
	private void bindPlayerUniforms(GlProgram program) {
		Minecraft minecraft = Minecraft.getInstance();

		program.setUniform("blindness", effectStrength(MobEffects.BLINDNESS));
		program.setUniform("nightVision", effectStrength(MobEffects.NIGHT_VISION));
		program.setUniform("darknessFactor", 0.0f);

		program.setUniform("screenBrightness", minecraft.options.gamma().get().floatValue());
		program.setUniform("isRightHanded",
			minecraft.options.mainHand().get() == HumanoidArm.RIGHT ? 1 : 0);

		// Wetness trails rainStrength rather than tracking it: packs use it to dry surfaces off
		// gradually once rain stops, and a value that snaps looks worse than none at all.
		var level = minecraft.level;
		float rain = level == null ? 0.0f : ShaderUniforms.rainStrength(level.getRainLevel(1.0f));
		wetness += (rain - wetness) * WETNESS_FOLLOW_RATE;
		program.setUniform("wetness", wetness);

		bindLightAndTimeUniforms(program, minecraft);
	}

	/**
	 * Light level at the eye, and the time-of-day values packs recompute from.
	 *
	 * <p>{@code eyeBrightness} is OptiFine's 0-240 scale, not Minecraft's 0-15: packs divide by 240
	 * and would otherwise read every torch-lit room as sixteen times brighter than the sun. The
	 * smoothed variant trails the raw one so stepping through a doorway fades rather than snaps,
	 * which is what packs use it for.</p>
	 */
	private void bindLightAndTimeUniforms(GlProgram program, Minecraft minecraft) {
		var level = minecraft.level;
		var camera = minecraft.getCameraEntity();

		int blockLight = 0;
		int skyLight = 0;
		if (level != null && camera != null) {
			BlockPos eye = BlockPos.containing(camera.getEyePosition(1.0f));
			var lighting = level.getLightEngine();
			blockLight = lighting.getLayerListener(LightLayer.BLOCK).getLightValue(eye) * LIGHT_SCALE;
			skyLight = lighting.getLayerListener(LightLayer.SKY).getLightValue(eye) * LIGHT_SCALE;
		}
		program.setUniform("eyeBrightness", blockLight, skyLight);

		smoothedBlockLight += (blockLight - smoothedBlockLight) * EYE_BRIGHTNESS_FOLLOW_RATE;
		smoothedSkyLight += (skyLight - smoothedSkyLight) * EYE_BRIGHTNESS_FOLLOW_RATE;
		program.setUniform("eyeBrightnessSmooth",
			Math.round(smoothedBlockLight), Math.round(smoothedSkyLight));

		// Full strength: shadows do not fade until there is a real shadow map to fade out. Zero would
		// read as "shadows fully faded", which several packs use to cancel their sunlight term.
		program.setUniform("shadowFade", 1.0f);
		program.setUniform("blindFactor", effectStrength(MobEffects.BLINDNESS));
		program.setUniform("darknessLightFactor", 0.0f);

		// The raw celestial angle, as distinct from the pack-facing sunAngle - packs use this one to
		// redo their own sun maths, so it must not be the shifted value.
		program.setUniform("timeAngle", FrameState.skyAngle());
		program.setUniform("moonPhase", FrameState.moonPhase());
		// Seconds the previous frame took, which packs use to make animation frame-rate independent.
		program.setUniform("frameTime", clock.seconds());

		// The fractional part of the camera position, which packs add back to keep precision at long
		// distances from the origin.
		Vec3 eyePosition = FrameState.cameraPosition();
		program.setUniform("cameraPositionFract",
			(float) (eyePosition.x - Math.floor(eyePosition.x)),
			(float) (eyePosition.y - Math.floor(eyePosition.y)),
			(float) (eyePosition.z - Math.floor(eyePosition.z)));
	}

	/**
	 * How strongly an effect applies, 0 to 1, ramping down over its final second the way Iris does so
	 * the screen does not snap back when the effect expires.
	 */
	private static float effectStrength(MobEffect effect) {
		if (!(Minecraft.getInstance().getCameraEntity() instanceof LivingEntity living)) {
			return 0.0f;
		}
		MobEffectInstance active = living.getEffect(effect);
		if (active == null) {
			return 0.0f;
		}
		return Math.clamp(active.getDuration() / (float) TICKS_PER_SECOND, 0.0f, 1.0f);
	}

	/**
	 * Supplies the eye-space directions packs light from.
	 *
	 * <p>The transform sequence follows Iris' {@code CelestialUniforms} (github.com/IrisShaders/Iris,
	 * LGPL-3.0, the same licence as this project), which mirrors what {@code renderSky} does to place
	 * the sun: rotate the gbuffer model-view by -90 degrees about Y, then by the pack's
	 * {@code sunPathRotation} about Z, then by the sky angle about X, and transform a vector pointing
	 * straight up. Deriving a direction vector by hand instead — as an earlier version did — put the
	 * sun in the wrong place for most of the day and omitted the -90 degree rotation from
	 * {@code upPosition} entirely.</p>
	 */
	private void bindCelestialPositions(GlProgram program) {
		float skyAngle = FrameState.skyAngle();

		// upPosition takes the same -90 degree Y rotation as the celestial bodies but skips the sky
		// angle, so it stays fixed while they travel.
		Vector4f up = new Vector4f(0.0f, CELESTIAL_DISTANCE, 0.0f, 0.0f);
		new Matrix4f(FrameState.modelView())
			.rotateY((float) Math.toRadians(-90.0f))
			.transform(up);
		program.setUniform("upPosition", up.x, up.y, up.z);

		Vector4f sun = celestialPosition(skyAngle, CELESTIAL_DISTANCE);
		program.setUniform("sunPosition", sun.x, sun.y, sun.z);

		Vector4f moon = celestialPosition(skyAngle, -CELESTIAL_DISTANCE);
		program.setUniform("moonPosition", moon.x, moon.y, moon.z);

		// The shadow-casting body: the sun by day, the moon by night. Packs read this rather than
		// branching on the time themselves.
		Vector4f shadowLight = CelestialAngles.isDay(skyAngle) ? sun : moon;
		program.setUniform("shadowLightPosition", shadowLight.x, shadowLight.y, shadowLight.z);

		program.setUniform("sunAngle", CelestialAngles.sunAngle(skyAngle));
		program.setUniform("shadowAngle", CelestialAngles.shadowAngle(skyAngle));
	}

	/**
	 * The matrices a pack uses to project a world position into shadow space.
	 *
	 * <p>The sequence is Iris' {@code ShadowMatrices} (LGPL-3.0), fetched rather than derived. Note
	 * the second angle conversion: it starts from {@code shadowAngle} — which already differs from
	 * Minecraft's sky angle by a quarter day — and shifts it a further quarter. Feeding the raw
	 * celestial angle in would leave the shadow frustum ninety degrees out, and nothing in the image
	 * would say so directly.</p>
	 */
	private void bindShadowMatrices(GlProgram program) {
		float shadowAngle = CelestialAngles.shadowAngle(FrameState.skyAngle());
		float skyAngle = shadowAngle < 0.25f ? shadowAngle + 0.75f : shadowAngle - 0.25f;

		Matrix4f modelView = new Matrix4f()
			.rotateX((float) Math.toRadians(90.0f))
			.rotateZ((float) Math.toRadians(skyAngle * -360.0f))
			.rotateX((float) Math.toRadians(sunPathRotation));

		Matrix4f projection = new Matrix4f()
			.setOrthoSymmetric(shadowDistance * 2.0f, shadowDistance * 2.0f, SHADOW_NEAR, SHADOW_FAR);

		program.setUniform("shadowModelView", modelView);
		program.setUniform("shadowProjection", projection);
		program.setUniform("shadowModelViewInverse", new Matrix4f(modelView).invert());
		program.setUniform("shadowProjectionInverse", new Matrix4f(projection).invert());

		program.setUniform("shadowMapResolution", (float) ShadowTargets.RESOLUTION);
		program.setUniform("shadowDistance", shadowDistance);
	}

	/** Iris' shadow depth range; the near plane is deliberately negative. */
	private static final float SHADOW_NEAR = -100.05f;
	private static final float SHADOW_FAR = 156.0f;

	/** A celestial body's eye-space position; negative {@code distance} gives the moon. */
	private Vector4f celestialPosition(float skyAngle, float distance) {
		Vector4f position = new Vector4f(0.0f, distance, 0.0f, 0.0f);
		new Matrix4f(FrameState.modelView())
			.rotateY((float) Math.toRadians(-90.0f))
			.rotateZ((float) Math.toRadians(sunPathRotation))
			.rotateX((float) Math.toRadians(CelestialAngles.celestialRotationDegrees(skyAngle)))
			.transform(position);
		return position;
	}

	/** OptiFine puts the celestial bodies at a fixed distance; packs normalise anyway. */
	private static final float CELESTIAL_DISTANCE = 100.0f;

	@Override
	public boolean isShaderPackActive() {
		return true;
	}

	@Override
	public void destroy() {
		targets.close();
		noise.close();
		shadow.close();
		expressionContext.reset();
		passes.forEach(pass -> pass.program().close());
		triangle.close();
	}

	/**
	 * Fills the fog block that stands in for {@code gl_Fog}.
	 *
	 * <p>{@link dev.tapetum.shaders.shaderpack.glsl.GlslCompatPatcher} declares this struct whenever a
	 * pack reads the fixed-function fog that core profile removed — and nothing ever wrote to it, so
	 * every field read as zero. A zero fog colour is black, and a pack that mixes towards it darkens
	 * the whole frame. The audit named it: Complementary's {@code deferred1} listed all five fields
	 * under "not supplied", which is how a struct of my own making turned up as missing work.</p>
	 *
	 * <p>{@code start} and {@code end} are the view frustum's bounds. 26.x computes fog inside a
	 * uniform buffer rather than as the four fixed-function scalars, so there is nothing to read the
	 * real distances from; the frustum is what the fixed-function pipeline would have been set to at
	 * default settings, and it is bounded and monotonic, which is what the packs actually rely on.</p>
	 */
	private static void bindFogParameters(GlProgram program) {
		Vector3f fog = FrameState.fogColor();
		float near = FrameState.near();
		float far = FrameState.far();
		float span = far - near;

		program.setUniform("tapetum_Fog.color", fog.x, fog.y, fog.z, 1.0f);
		program.setUniform("tapetum_Fog.start", near);
		program.setUniform("tapetum_Fog.end", far);
		program.setUniform("tapetum_Fog.density", 1.0f);
		// Guarded: a degenerate frustum would hand the shader an infinity, and an infinity becomes a
		// NaN that spreads through everything it touches and comes out black with nothing in the log.
		program.setUniform("tapetum_Fog.scale", span > 0.0f ? 1.0f / span : 0.0f);
	}

}
