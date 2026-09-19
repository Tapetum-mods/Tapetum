package dev.tapetum.shaders.uniform;

/**
 * Converts Minecraft's sky angle into the celestial values an OptiFine-format pack expects.
 *
 * <p>The conversions here follow Iris' {@code CelestialUniforms} (github.com/IrisShaders/Iris,
 * LGPL-3.0 — the same licence as this project), which is the reference implementation for what these
 * uniforms are supposed to mean. Iris reads Minecraft's sky angle from {@code Level.getTimeOfDay};
 * that method no longer exists in 26.x, where the value comes from the camera's environment-attribute
 * probe instead, so the source differs while the arithmetic below does not.</p>
 *
 * <p>Kept free of Minecraft types so the arithmetic can be tested without a game — the offsets are
 * exactly the sort of thing that is off by a quarter-day and looks merely "wrong" rather than
 * broken.</p>
 */
public final class CelestialAngles {

	private CelestialAngles() {
	}

	/**
	 * The pack-facing {@code sunAngle}: 0 at sunrise, 0.25 at noon, 0.5 at sunset.
	 *
	 * <p>Minecraft's own sky angle is a quarter-day ahead of this — it reads 0 at noon — so the two
	 * are not interchangeable. Passing the raw sky angle through would light every scene as though it
	 * were six hours from the truth.</p>
	 *
	 * @param skyAngle Minecraft's sky angle, 0-1
	 */
	public static float sunAngle(float skyAngle) {
		float wrapped = wrap(skyAngle);
		return wrapped < 0.75f ? wrapped + 0.25f : wrapped - 0.75f;
	}

	/** Whether the sun is the light source, which is what decides where shadows come from. */
	public static boolean isDay(float skyAngle) {
		return sunAngle(skyAngle) <= 0.5f;
	}

	/**
	 * The angle of whichever body is currently casting shadows: the sun by day, the moon by night.
	 * Packs read this instead of branching on the time themselves.
	 */
	public static float shadowAngle(float skyAngle) {
		float sun = sunAngle(skyAngle);
		return isDay(skyAngle) ? sun : sun - 0.5f;
	}

	/** The rotation, in degrees, applied about the X axis to place the celestial bodies. */
	public static float celestialRotationDegrees(float skyAngle) {
		return wrap(skyAngle) * 360.0f;
	}

	/** Folds any angle into 0-1, so a value drifting past a full day stays meaningful. */
	private static float wrap(float angle) {
		float wrapped = angle % 1.0f;
		return wrapped < 0.0f ? wrapped + 1.0f : wrapped;
	}
}
