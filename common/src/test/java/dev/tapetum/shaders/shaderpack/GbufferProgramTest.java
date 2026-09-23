package dev.tapetum.shaders.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OptiFine's gbuffers inheritance. Packs ship only what they treat specially and inherit the rest —
 * measured across the five surveyed packs, only fourteen of the twenty-two programs that appear are
 * shipped by all five, so resolving the chain wrongly is not an edge case.
 */
class GbufferProgramTest {

	private static GbufferProgram resolve(GbufferProgram wanted, GbufferProgram... shipped) {
		Set<GbufferProgram> available = shipped.length == 0
			? EnumSet.noneOf(GbufferProgram.class) : EnumSet.of(shipped[0], shipped);
		return wanted.resolve(available::contains).orElse(null);
	}

	@Test
	void usesTheProgramItselfWhenThePackShipsIt() {
		assertEquals(GbufferProgram.TERRAIN,
			resolve(GbufferProgram.TERRAIN, GbufferProgram.TERRAIN, GbufferProgram.BASIC));
	}

	@Test
	void walksTerrainAllTheWayDownToBasic() {
		// terrain -> textured_lit -> textured -> basic. Every pack ships basic, which is why the
		// chain can end there.
		assertEquals(GbufferProgram.BASIC, resolve(GbufferProgram.TERRAIN, GbufferProgram.BASIC));
	}

	@Test
	void inheritsWaterFromTerrainRatherThanFromTextured() {
		// Water is a terrain variant. Falling through to textured would draw it without the terrain
		// program's lighting, which looks wrong rather than failing.
		assertEquals(GbufferProgram.TERRAIN,
			resolve(GbufferProgram.WATER, GbufferProgram.TERRAIN, GbufferProgram.TEXTURED));
	}

	@Test
	void inheritsGlowingEntitiesFromEntities() {
		// Only four of five packs ship entities_glowing; the fifth must get entities, not textured.
		assertEquals(GbufferProgram.ENTITIES,
			resolve(GbufferProgram.ENTITIES_GLOWING, GbufferProgram.ENTITIES, GbufferProgram.TEXTURED));
	}

	@Test
	void inheritsHandWaterFromHand() {
		// Exactly one of the five packs ships hand_water.
		assertEquals(GbufferProgram.HAND,
			resolve(GbufferProgram.HAND_WATER, GbufferProgram.HAND, GbufferProgram.TEXTURED_LIT));
	}

	@Test
	void leavesTheShadowProgramOutOfTheChain() {
		// It draws from the sun's point of view, so nothing it could inherit would be correct.
		assertTrue(GbufferProgram.SHADOW.fallback().isEmpty());
		assertEquals(null, resolve(GbufferProgram.SHADOW, GbufferProgram.BASIC));
	}

	@Test
	void reportsNothingWhenNoProgramInTheChainExists() {
		assertEquals(null, resolve(GbufferProgram.TERRAIN));
	}

	@Test
	void namesBothStageFiles() {
		assertEquals("gbuffers_terrain.fsh", GbufferProgram.TERRAIN.fragmentFile());
		assertEquals("gbuffers_terrain.vsh", GbufferProgram.TERRAIN.vertexFile());
		assertEquals("shadow.fsh", GbufferProgram.SHADOW.fragmentFile());
	}

	@Test
	void cutoutUsesItsOwnProgramBeforeTerrainFallback() {
		assertEquals(GbufferProgram.TERRAIN_CUTOUT, resolve(GbufferProgram.TERRAIN_CUTOUT,
			GbufferProgram.TERRAIN_CUTOUT, GbufferProgram.TERRAIN));
		assertEquals(GbufferProgram.TERRAIN, resolve(GbufferProgram.TERRAIN_CUTOUT, GbufferProgram.TERRAIN));
	}

	@Test
	void everyChainTerminates() {
		// A cycle here would hang resolution rather than fail it.
		for (GbufferProgram program : GbufferProgram.values()) {
			int steps = 0;
			for (var current = java.util.Optional.of(program); current.isPresent();
					current = current.get().fallback()) {
				assertTrue(++steps <= GbufferProgram.values().length,
					"fallback chain does not terminate from " + program);
			}
		}
	}
}
