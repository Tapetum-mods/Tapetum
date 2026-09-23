package dev.tapetum.shaders.shaderpack;

import java.util.Optional;

/**
 * The geometry programs a shaderpack may ship, and what each falls back to when it does not.
 *
 * <p>These replace the shaders Minecraft draws the world with, which is what finally puts real
 * G-buffer data — normals, specular, block identity — into the {@code colortex} buffers the composite
 * chain reads. Until they run, that chain computes lighting from a copy of the finished frame.</p>
 *
 * <p>The fallback chain is OptiFine's and is not optional: a pack is expected to ship only what it
 * wants to treat specially and to inherit the rest. Measured across the five packs surveyed here,
 * twenty-two distinct programs appear, but only fourteen are shipped by all five — the rest rely on
 * exactly this inheritance. Resolving it wrongly means drawing entities with the terrain program,
 * which does not fail, it just looks wrong.</p>
 */
public enum GbufferProgram {
	/** The root of the chain; everything else reaches here eventually. */
	BASIC("gbuffers_basic", null),
	LINE("gbuffers_line", BASIC),
	TEXTURED("gbuffers_textured", BASIC),
	TEXTURED_LIT("gbuffers_textured_lit", TEXTURED),

	SKY_BASIC("gbuffers_skybasic", BASIC),
	SKY_TEXTURED("gbuffers_skytextured", TEXTURED),
	CLOUDS("gbuffers_clouds", TEXTURED),

	TERRAIN("gbuffers_terrain", TEXTURED_LIT),
	TERRAIN_SOLID("gbuffers_terrain_solid", TERRAIN),
	TERRAIN_CUTOUT("gbuffers_terrain_cutout", TERRAIN),
	DAMAGED_BLOCK("gbuffers_damagedblock", TERRAIN),
	BLOCK("gbuffers_block", TERRAIN),
	BLOCK_TRANSLUCENT("gbuffers_block_translucent", BLOCK),
	WATER("gbuffers_water", TERRAIN),

	ENTITIES("gbuffers_entities", TEXTURED_LIT),
	ENTITIES_GLOWING("gbuffers_entities_glowing", ENTITIES),
	ENTITIES_TRANSLUCENT("gbuffers_entities_translucent", ENTITIES),
	LIGHTNING("gbuffers_lightning", TEXTURED),
	SPIDER_EYES("gbuffers_spidereyes", TEXTURED),
	ARMOR_GLINT("gbuffers_armor_glint", TEXTURED),
	BEACON_BEAM("gbuffers_beaconbeam", TEXTURED),

	ITEM("gbuffers_item", TEXTURED_LIT),
	HAND("gbuffers_hand", TEXTURED_LIT),
	HAND_WATER("gbuffers_hand_water", HAND),
	WEATHER("gbuffers_weather", TEXTURED_LIT),

	/** Rendered from the sun's point of view, not the camera's; it inherits from nothing. */
	SHADOW("shadow", null);

	private final String programName;
	private final GbufferProgram fallback;

	GbufferProgram(String programName, GbufferProgram fallback) {
		this.programName = programName;
		this.fallback = fallback;
	}

	public String programName() {
		return programName;
	}

	public String fragmentFile() {
		return programName + ".fsh";
	}

	public String vertexFile() {
		return programName + ".vsh";
	}

	/** What this program inherits from when the pack does not ship it. */
	public Optional<GbufferProgram> fallback() {
		return Optional.ofNullable(fallback);
	}

	/**
	 * Follows the fallback chain until it finds one the pack ships.
	 *
	 * @param shipped whether the pack ships a given program
	 * @return the program to actually compile, or empty when nothing in the chain exists
	 */
	public Optional<GbufferProgram> resolve(java.util.function.Predicate<GbufferProgram> shipped) {
		for (GbufferProgram candidate = this; candidate != null; candidate = candidate.fallback) {
			if (shipped.test(candidate)) {
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}
}
