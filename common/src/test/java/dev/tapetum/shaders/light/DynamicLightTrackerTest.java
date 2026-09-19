package dev.tapetum.shaders.light;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dynamic-light bookkeeping, tested without a game. What matters here is not that a torch is
 * bright but that the renderer is told to rebuild exactly the right sections: too few and the light
 * smears behind a walking player, too many and every step costs a chunk rebuild.
 */
class DynamicLightTrackerTest {

	private static DynamicLightTracker.Source torchAt(int id, int x, int y, int z) {
		return new DynamicLightTracker.Source(id, x, y, z, 14);
	}

	@Test
	void reportsTheLitSectionsWhenASourceAppears() {
		DynamicLightTracker tracker = new DynamicLightTracker();

		Set<DynamicLightTracker.Section> dirty = tracker.update(List.of(torchAt(1, 8, 8, 8)));

		assertTrue(dirty.contains(new DynamicLightTracker.Section(0, 0, 0)), dirty.toString());
		assertFalse(dirty.isEmpty());
	}

	@Test
	void dirtiesBothTheOldAndNewSectionWhenASourceMovesBetweenThem() {
		// The case that gives dynamic lights away when it is wrong: walk a torch across a chunk
		// boundary and the light stays burnt into the section you left.
		DynamicLightTracker tracker = new DynamicLightTracker();
		tracker.update(List.of(torchAt(1, 8, 8, 8)));

		Set<DynamicLightTracker.Section> dirty = tracker.update(List.of(torchAt(1, 40, 8, 8)));

		assertTrue(dirty.contains(new DynamicLightTracker.Section(0, 0, 0)), dirty.toString());
		assertTrue(dirty.contains(new DynamicLightTracker.Section(2, 0, 0)), dirty.toString());
	}

	@Test
	void reportsNothingWhenASourceHasNotMoved() {
		// Called every tick, so a stationary player holding a torch must not rebuild chunks forever.
		DynamicLightTracker tracker = new DynamicLightTracker();
		tracker.update(List.of(torchAt(1, 8, 8, 8)));

		assertEquals(Set.of(), tracker.update(List.of(torchAt(1, 8, 8, 8))));
	}

	@Test
	void dirtiesTheSectionWhenASourceStopsEmitting() {
		DynamicLightTracker tracker = new DynamicLightTracker();
		tracker.update(List.of(torchAt(1, 8, 8, 8)));

		Set<DynamicLightTracker.Section> dirty = tracker.update(List.of());

		assertTrue(dirty.contains(new DynamicLightTracker.Section(0, 0, 0)), dirty.toString());
		assertEquals(0, tracker.luminanceAt(8, 8, 8));
	}

	@Test
	void dirtiesTheSectionWhenOnlyTheBrightnessChanges() {
		// Swapping a torch for a lantern in the same hand, without moving.
		DynamicLightTracker tracker = new DynamicLightTracker();
		tracker.update(List.of(new DynamicLightTracker.Source(1, 8, 8, 8, 14)));

		Set<DynamicLightTracker.Section> dirty =
			tracker.update(List.of(new DynamicLightTracker.Source(1, 8, 8, 8, 15)));

		assertTrue(dirty.contains(new DynamicLightTracker.Section(0, 0, 0)), dirty.toString());
	}

	@Test
	void fallsOffOneLevelPerBlockLikeVanillaBlockLight() {
		DynamicLightTracker tracker = new DynamicLightTracker();
		tracker.update(List.of(torchAt(1, 0, 0, 0)));

		assertEquals(14, tracker.luminanceAt(0, 0, 0));
		assertEquals(13, tracker.luminanceAt(1, 0, 0));
		assertEquals(4, tracker.luminanceAt(10, 0, 0));
		assertEquals(0, tracker.luminanceAt(14, 0, 0));
		assertEquals(0, tracker.luminanceAt(100, 0, 0));
	}

	@Test
	void takesTheBrightestSourceRatherThanAddingThemUp() {
		// Vanilla block light does not accumulate; two torches side by side are as bright as one.
		DynamicLightTracker tracker = new DynamicLightTracker();
		tracker.update(List.of(torchAt(1, 0, 0, 0), torchAt(2, 1, 0, 0)));

		assertEquals(14, tracker.luminanceAt(0, 0, 0));
		assertTrue(tracker.luminanceAt(0, 0, 0) <= DynamicLightTracker.MAX_LUMINANCE);
	}

	@Test
	void ignoresSourcesThatEmitNothing() {
		// An empty hand is reported like any other entity; it must not occupy the tracker or dirty
		// sections every tick.
		DynamicLightTracker tracker = new DynamicLightTracker();

		Set<DynamicLightTracker.Section> dirty =
			tracker.update(List.of(new DynamicLightTracker.Source(1, 8, 8, 8, 0)));

		assertEquals(Set.of(), dirty);
		assertEquals(List.of(), tracker.activeSources());
	}

	@Test
	void clampsLuminanceToVanillasMaximum() {
		assertEquals(15, new DynamicLightTracker.Source(1, 0, 0, 0, 99).luminance());
		assertEquals(0, new DynamicLightTracker.Source(1, 0, 0, 0, -5).luminance());
	}

	@Test
	void handlesNegativeCoordinatesWhenChoosingSections() {
		// Integer division truncates towards zero, so -1 / 16 is 0 rather than -1; getting this wrong
		// leaves a one-section band unlit on the negative side of the origin.
		DynamicLightTracker tracker = new DynamicLightTracker();

		Set<DynamicLightTracker.Section> dirty = tracker.update(List.of(torchAt(1, -1, -1, -1)));

		assertTrue(dirty.contains(new DynamicLightTracker.Section(-1, -1, -1)), dirty.toString());
	}

	@Test
	void clearForgetsEverythingAndReportsWhatNeedsRelighting() {
		DynamicLightTracker tracker = new DynamicLightTracker();
		tracker.update(List.of(torchAt(1, 8, 8, 8)));

		assertFalse(tracker.clear().isEmpty());
		assertEquals(0, tracker.luminanceAt(8, 8, 8));
		assertEquals(List.of(), tracker.activeSources());
	}

	@Test
	void parsesAPersistedModeAndFallsBackOnJunk() {
		assertEquals(DynamicLightMode.FANCY, DynamicLightMode.parse("FANCY", DynamicLightMode.OFF));
		assertEquals(DynamicLightMode.FAST, DynamicLightMode.parse("  fast ", DynamicLightMode.OFF));
		assertEquals(DynamicLightMode.OFF, DynamicLightMode.parse("nonsense", DynamicLightMode.OFF));
		assertEquals(DynamicLightMode.OFF, DynamicLightMode.parse(null, DynamicLightMode.OFF));
	}
}
