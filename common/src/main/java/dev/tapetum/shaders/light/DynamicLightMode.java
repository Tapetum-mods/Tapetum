package dev.tapetum.shaders.light;

/**
 * How much the dynamic-light pass is allowed to track, mirroring OptiFine's own three settings so
 * the option reads the way players expect.
 */
public enum DynamicLightMode {
	/** No tracking at all; the collector is never run. */
	OFF,
	/**
	 * Held items only, refreshed every few ticks.
	 *
	 * <p>Held items are what players actually notice — walking a torch down a corridor — and they are
	 * a handful of entities rather than every dropped item in render distance.</p>
	 */
	FAST,
	/** Held items, dropped item entities and self-lit mobs, refreshed every tick. */
	FANCY;

	/** How many client ticks to wait between rebuilds of the source set. */
	public int tickInterval() {
		return this == FAST ? 4 : 1;
	}

	public boolean tracksDroppedItems() {
		return this == FANCY;
	}

	public boolean isEnabled() {
		return this != OFF;
	}

	/** Parses a persisted value, falling back to {@code fallback} rather than throwing on junk. */
	public static DynamicLightMode parse(String value, DynamicLightMode fallback) {
		if (value == null) {
			return fallback;
		}
		for (DynamicLightMode mode : values()) {
			if (mode.name().equalsIgnoreCase(value.trim())) {
				return mode;
			}
		}
		return fallback;
	}
}
