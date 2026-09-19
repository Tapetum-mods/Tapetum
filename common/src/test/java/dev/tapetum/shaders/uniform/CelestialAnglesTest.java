package dev.tapetum.shaders.uniform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quarter-day offset between Minecraft's sky angle and a pack's sunAngle. Getting this wrong
 * does not crash or log anything — it lights every scene six hours out, which is exactly the kind of
 * error that survives to release.
 */
class CelestialAnglesTest {

	@Test
	void mapsMinecraftsNoonToAQuarterDay() {
		// Minecraft's sky angle reads 0 at noon; the pack-facing sunAngle reads 0.25 there.
		assertEquals(0.25f, CelestialAngles.sunAngle(0.0f), 1.0e-6f);
	}

	@Test
	void mapsSunriseToZero() {
		// Sky angle 0.75 is sunrise, which is where a pack's sunAngle starts.
		assertEquals(0.0f, CelestialAngles.sunAngle(0.75f), 1.0e-6f);
	}

	@Test
	void mapsSunsetToAHalfDay() {
		assertEquals(0.5f, CelestialAngles.sunAngle(0.25f), 1.0e-6f);
	}

	@Test
	void staysWithinZeroToOneAcrossAWholeDay() {
		for (int step = 0; step <= 100; step++) {
			float sunAngle = CelestialAngles.sunAngle(step / 100.0f);
			assertTrue(sunAngle >= 0.0f && sunAngle < 1.0f, "sunAngle out of range: " + sunAngle);
		}
	}

	@Test
	void treatsTheSunAsTheLightSourceBetweenSunriseAndSunset() {
		assertTrue(CelestialAngles.isDay(0.75f), "sunrise");
		assertTrue(CelestialAngles.isDay(0.0f), "noon");
		assertTrue(CelestialAngles.isDay(0.25f), "sunset");
		assertFalse(CelestialAngles.isDay(0.5f), "midnight");
	}

	@Test
	void shiftsTheShadowAngleByAHalfDayAtNight() {
		// At night the moon casts, and packs expect the angle to follow it rather than the sun.
		assertEquals(CelestialAngles.sunAngle(0.0f), CelestialAngles.shadowAngle(0.0f), 1.0e-6f);
		assertEquals(CelestialAngles.sunAngle(0.5f) - 0.5f, CelestialAngles.shadowAngle(0.5f), 1.0e-6f);
	}

	@Test
	void turnsTheSkyAngleIntoAFullRotation() {
		assertEquals(0.0f, CelestialAngles.celestialRotationDegrees(0.0f), 1.0e-6f);
		assertEquals(180.0f, CelestialAngles.celestialRotationDegrees(0.5f), 1.0e-6f);
	}

	@Test
	void foldsAnAngleThatHasRunPastAFullDay() {
		// A drifting or accumulated angle must not produce a sunAngle outside 0-1.
		assertEquals(CelestialAngles.sunAngle(0.1f), CelestialAngles.sunAngle(1.1f), 1.0e-6f);
		assertEquals(CelestialAngles.sunAngle(0.9f), CelestialAngles.sunAngle(-0.1f), 1.0e-5f);
	}
}
