package dev.tapetum.shaders.uniform;

import dev.tapetum.shaders.shaderpack.uniform.EvaluationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the names a pack's custom-uniform expressions read, against the live game.
 *
 * <p>Three kinds of name arrive here: the loader's own uniforms, which are snapshotted once a frame;
 * biome identity, which needs a mapping of its own; and {@code smooth()}, which needs memory between
 * frames.</p>
 */
public final class ShaderExpressionContext implements EvaluationContext {

	/** This frame's loader-supplied values, refreshed by {@link #beginFrame}. */
	private final Map<String, Float> builtins = new HashMap<>();

	/**
	 * Biome names interned to sequential ids.
	 *
	 * <p>Packs compare {@code in(biome, BIOME_DESERT, BIOME_BADLANDS)}, so only <em>agreement</em>
	 * between the two sides matters, never the absolute number. Interning both through this one table
	 * gives that exactly, and sidesteps numeric biome ids entirely — they stopped being stable
	 * identifiers years ago, and a mapping built on them would silently drift between versions.</p>
	 */
	private final Map<String, Integer> biomeIds = new HashMap<>();

	/** One remembered value per {@code smooth()} call site, keyed by the id the pack chose. */
	private final Map<Integer, Float> smoothed = new HashMap<>();

	private long lastFrameNanos;
	private float deltaSeconds;

	/** Starts a frame: takes the loader's values and measures how long the last frame took. */
	public void beginFrame(Map<String, Float> frameValues) {
		builtins.clear();
		builtins.putAll(frameValues);

		long now = System.nanoTime();
		deltaSeconds = lastFrameNanos == 0L ? 0.0f
			: Math.clamp((now - lastFrameNanos) / 1.0e9f, 0.0f, MAX_FRAME_SECONDS);
		lastFrameNanos = now;
	}

	/**
	 * A long stall must not jump a smoothed value straight to its target.
	 *
	 * <p>Loading a world or opening a menu can pause rendering for seconds; without this the first
	 * frame back would apply all of it at once and the fade the pack asked for would be a jump.</p>
	 */
	private static final float MAX_FRAME_SECONDS = 0.1f;

	@Override
	public float value(String name) {
		if (name.startsWith("BIOME_")) {
			return biomeId("minecraft:" + name.substring("BIOME_".length()).toLowerCase(Locale.ROOT));
		}
		if ("biome".equals(name)) {
			return currentBiomeId();
		}

		Float supplied = builtins.get(name);
		// An unknown name is zero, matching how a pack behaves under OptiFine when a feature it asks
		// about is unavailable.
		return supplied != null ? supplied : 0.0f;
	}

	private float currentBiomeId() {
		Minecraft minecraft = Minecraft.getInstance();
		var level = minecraft.level;
		var camera = minecraft.getCameraEntity();
		if (level == null || camera == null) {
			return -1.0f;
		}
		return biomeId(level.getBiome(BlockPos.containing(camera.position())).getRegisteredName());
	}

	private float biomeId(String registeredName) {
		return biomeIds.computeIfAbsent(registeredName, unused -> biomeIds.size());
	}

	@Override
	public float smooth(int id, float target, float fadeUpSeconds, float fadeDownSeconds) {
		Float previous = smoothed.get(id);
		if (previous == null || deltaSeconds <= 0.0f) {
			smoothed.put(id, target);
			return target;
		}

		float fade = target > previous ? fadeUpSeconds : fadeDownSeconds;
		if (fade <= 0.0f) {
			smoothed.put(id, target);
			return target;
		}

		// Exponential easing rather than a linear ramp: it reaches the target smoothly from either
		// direction and cannot overshoot, whatever the frame time.
		float blended = previous + (target - previous) * Math.min(1.0f, deltaSeconds / fade);
		smoothed.put(id, blended);
		return blended;
	}

	/** Forgets the smoothing state, for when a pack is reloaded and its call-site ids change. */
	public void reset() {
		smoothed.clear();
		lastFrameNanos = 0L;
	}
}
