package dev.tapetum.shaders.uniform;

/**
 * The values behind OptiFine's standard uniform set, and the derivations that turn raw game state
 * into what a pack actually reads.
 *
 * <p>Kept in {@code common} and free of any Minecraft type on purpose: the arithmetic below is where
 * the mistakes live — an angle in the wrong quadrant or a counter that fails to wrap is invisible in
 * a screenshot but changes what every pack computes — and here it can be unit-tested without a game.
 * The Minecraft-side code reads the raw values and calls these.</p>
 *
 * <p>Uniform semantics follow the OptiFine shaderpack documentation, which is the contract packs are
 * written against; Iris implements the same one.</p>
 */
public final class ShaderUniforms {
	/** OptiFine wraps {@code frameTimeCounter} here so a long session keeps float precision. */
	public static final float FRAME_TIME_COUNTER_WRAP_SECONDS = 3600.0f;

	/** Minecraft's day is this many ticks long. */
	public static final long TICKS_PER_DAY = 24000L;

	private ShaderUniforms() {
	}

	/**
	 * Seconds since the pack was loaded, wrapped at {@link #FRAME_TIME_COUNTER_WRAP_SECONDS}.
	 *
	 * <p>Wrapping is not cosmetic: packs multiply this into trigonometric functions for waving
	 * foliage and animated water, and an unbounded float loses the sub-second precision those need
	 * within about a day of uptime, which shows up as animation visibly stuttering then freezing.</p>
	 */
	public static float frameTimeCounter(long elapsedNanos) {
		if (elapsedNanos <= 0L) {
			return 0.0f;
		}
		double seconds = elapsedNanos / 1_000_000_000.0;
		return (float) (seconds % FRAME_TIME_COUNTER_WRAP_SECONDS);
	}

	/** The tick within the current Minecraft day, i.e. {@code worldTime} as packs read it. */
	public static int worldTime(long gameTime) {
		long wrapped = gameTime % TICKS_PER_DAY;
		// Java's % keeps the sign of the dividend; a negative time would otherwise index backwards
		// through the day and put the sun below the horizon at noon.
		return (int) (wrapped < 0 ? wrapped + TICKS_PER_DAY : wrapped);
	}

	/** How many whole days have elapsed, which packs use for long-period effects. */
	public static int worldDay(long gameTime) {
		return (int) Math.floorDiv(gameTime, TICKS_PER_DAY);
	}

	/**
	 * The sun's position around the day as a fraction in {@code [0, 1)}, matching OptiFine's
	 * {@code sunAngle}: 0 at sunrise, 0.25 at noon, 0.5 at sunset, 0.75 at midnight.
	 *
	 * <p>Minecraft's day tick already starts at sunrise (tick 0 is 06:00, 6000 is noon, 12000 is
	 * sunset), so this is the plain fraction of the day with no offset. An earlier version shifted by
	 * a quarter day on the mistaken belief that tick 0 was noon, which put the whole cycle six hours
	 * out — a wrong-but-plausible image rather than a crash, which is exactly why it is pinned by
	 * tests here.</p>
	 */
	public static float sunAngle(long gameTime) {
		return worldTime(gameTime) / (float) TICKS_PER_DAY;
	}

	/**
	 * The angle of whichever celestial body is currently casting shadows, as OptiFine's
	 * {@code shadowAngle}: it tracks the sun by day and the moon by night, so shadows never swing
	 * around at dusk.
	 */
	public static float shadowAngle(long gameTime) {
		float sun = sunAngle(gameTime);
		return sun < 0.5f ? sun : sun - 0.5f;
	}

	/** Whether the sun, rather than the moon, is the current shadow-casting light. */
	public static boolean isDaytime(long gameTime) {
		return sunAngle(gameTime) < 0.5f;
	}

	/**
	 * Rain strength clamped to the {@code [0, 1]} range packs assume.
	 *
	 * <p>Clamping matters because packs feed this straight into {@code mix()}; a value outside the
	 * range extrapolates instead of interpolating, which produces colours outside the intended
	 * gamut rather than simply "more rain".</p>
	 */
	public static float rainStrength(float rawRainLevel) {
		if (Float.isNaN(rawRainLevel)) {
			return 0.0f;
		}
		return Math.clamp(rawRainLevel, 0.0f, 1.0f);
	}

	/**
	 * Aspect ratio, guarding the zero-height case.
	 *
	 * <p>A minimised or not-yet-sized window reports height 0, and the resulting division would hand
	 * every pack a NaN that silently poisons every calculation downstream of it.</p>
	 */
	public static float aspectRatio(int width, int height) {
		return height <= 0 ? 1.0f : (float) width / (float) height;
	}
}
