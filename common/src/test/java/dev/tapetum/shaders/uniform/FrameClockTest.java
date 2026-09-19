package dev.tapetum.shaders.uniform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameClockTest {
	@Test
	void firstFrameDoesNotIncludePackCompilationTime() {
		FrameClock clock = new FrameClock();
		clock.advance(9_000_000_000L);
		assertEquals(1, clock.frame());
		assertEquals(0.0f, clock.seconds());
	}

	@Test
	void everyPassSeesTheSameFrameDuration() {
		FrameClock clock = new FrameClock();
		clock.advance(0L);
		clock.advance(16_000_000L);
		for (int pass = 0; pass < 15; pass++) {
			assertEquals(2, clock.frame());
			assertEquals(0.016f, clock.seconds(), 0.000001f);
		}
	}

	@Test
	void advancesPastTheDiagnosticThreshold() {
		FrameClock clock = new FrameClock();
		for (int frame = 1; frame <= 31; frame++) clock.advance(frame * 16_000_000L);
		assertEquals(31, clock.frame());
	}

	@Test
	void acceptsNegativeNanoTimeOrigins() {
		FrameClock clock = new FrameClock();
		clock.advance(-1_000_000_000L);
		clock.advance(-980_000_000L);
		assertEquals(0.02f, clock.seconds(), 0.000001f);
	}
}
