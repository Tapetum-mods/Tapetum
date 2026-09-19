package dev.tapetum.shaders.uniform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the uniform derivations. Every one of these is the kind of error that produces a
 * plausible-looking but wrong image rather than a crash, so it is worth pinning precisely.
 */
class ShaderUniformsTest {

	@Test
	void sunriseIsAtMinecraftTickZero() {
		// Minecraft's day tick starts at sunrise (tick 0 = 06:00), which is also OptiFine's zero for
		// sunAngle - so no offset. Adding one puts every pack's day/night cycle six hours out.
		assertEquals(0.0f, ShaderUniforms.sunAngle(0), 1e-6f);
	}

	@Test
	void sunAngleWalksTheDayInOrder() {
		assertEquals(0.0f, ShaderUniforms.sunAngle(0), 1e-6f);        // sunrise, 06:00
		assertEquals(0.25f, ShaderUniforms.sunAngle(6000), 1e-6f);    // noon
		assertEquals(0.5f, ShaderUniforms.sunAngle(12000), 1e-6f);    // sunset, 18:00
		assertEquals(0.75f, ShaderUniforms.sunAngle(18000), 1e-6f);   // midnight
	}

	@Test
	void sunAngleStaysInRangeAcrossManyDays() {
		for (long tick = 0; tick < ShaderUniforms.TICKS_PER_DAY * 5; tick += 137) {
			float angle = ShaderUniforms.sunAngle(tick);
			assertTrue(angle >= 0.0f && angle < 1.0f, "sunAngle out of range at tick " + tick + ": " + angle);
		}
	}

	@Test
	void dayAndNightAreClassifiedFromTheSunNotTheRawTick() {
		assertTrue(ShaderUniforms.isDaytime(0), "tick 0 is sunrise");
		assertTrue(ShaderUniforms.isDaytime(6000), "noon");
		assertFalse(ShaderUniforms.isDaytime(13000), "just after sunset");
		assertFalse(ShaderUniforms.isDaytime(18000), "midnight");
	}

	@Test
	void shadowAngleTracksWhicheverBodyIsCasting() {
		// It must not jump when the caster changes at dusk, which is what a raw sunAngle would do.
		assertEquals(ShaderUniforms.sunAngle(3000), ShaderUniforms.shadowAngle(3000), 1e-6f);
		float night = ShaderUniforms.shadowAngle(18000);
		assertTrue(night >= 0.0f && night < 0.5f, "night shadow angle should fold into the first half: " + night);
	}

	@Test
	void worldTimeWrapsWithinTheDay() {
		assertEquals(0, ShaderUniforms.worldTime(0));
		assertEquals(500, ShaderUniforms.worldTime(500));
		assertEquals(500, ShaderUniforms.worldTime(ShaderUniforms.TICKS_PER_DAY + 500));
	}

	@Test
	void worldTimeStaysPositiveForNegativeGameTime() {
		// Java's % keeps the dividend's sign; a negative result would index backwards through the day.
		assertTrue(ShaderUniforms.worldTime(-500) >= 0);
		assertEquals(ShaderUniforms.TICKS_PER_DAY - 500, ShaderUniforms.worldTime(-500));
	}

	@Test
	void worldDayCountsWholeDays() {
		assertEquals(0, ShaderUniforms.worldDay(0));
		assertEquals(0, ShaderUniforms.worldDay(ShaderUniforms.TICKS_PER_DAY - 1));
		assertEquals(1, ShaderUniforms.worldDay(ShaderUniforms.TICKS_PER_DAY));
		assertEquals(3, ShaderUniforms.worldDay(ShaderUniforms.TICKS_PER_DAY * 3 + 10));
	}

	@Test
	void frameTimeCounterWrapsRatherThanGrowingWithoutBound() {
		// Packs multiply this into trig for waving foliage; an unbounded float loses the sub-second
		// precision they need, and animation visibly stutters then freezes.
		// Built in long arithmetic: computing this as a float loses precision at 3.6e12 and the test
		// would be measuring its own rounding error rather than the wrap.
		long oneHourNanos = 3600L * 1_000_000_000L;
		assertEquals(0.0f, ShaderUniforms.frameTimeCounter(oneHourNanos), 1e-3f);
		assertEquals(1.0f, ShaderUniforms.frameTimeCounter(oneHourNanos + 1_000_000_000L), 1e-3f);
	}

	@Test
	void frameTimeCounterHandlesTheFirstFrame() {
		assertEquals(0.0f, ShaderUniforms.frameTimeCounter(0), 1e-6f);
		assertEquals(0.0f, ShaderUniforms.frameTimeCounter(-1), 1e-6f);
	}

	@Test
	void rainStrengthIsClampedForSafeMixing() {
		assertEquals(0.0f, ShaderUniforms.rainStrength(-0.5f), 1e-6f);
		assertEquals(1.0f, ShaderUniforms.rainStrength(2.0f), 1e-6f);
		assertEquals(0.4f, ShaderUniforms.rainStrength(0.4f), 1e-6f);
		assertEquals(0.0f, ShaderUniforms.rainStrength(Float.NaN), 1e-6f);
	}

	@Test
	void aspectRatioSurvivesAMinimisedWindow() {
		// Height 0 would otherwise hand every pack a NaN that poisons everything downstream.
		assertEquals(1.0f, ShaderUniforms.aspectRatio(1920, 0), 1e-6f);
		assertEquals(1.6f, ShaderUniforms.aspectRatio(1600, 1000), 1e-6f);
	}
}
