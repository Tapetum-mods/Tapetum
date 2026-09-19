package dev.tapetum.shaders.shaderpack.uniform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading a pack's own uniform definitions, on the shapes BSL actually ships. */
class CustomUniformsTest {

	private static EvaluationContext context(Map<String, Float> values) {
		return new EvaluationContext() {
			@Override
			public float value(String name) {
				return values.getOrDefault(name, 0.0f);
			}

			@Override
			public float smooth(int id, float target, float fadeUp, float fadeDown) {
				return target;
			}
		};
	}

	@Test
	void resolvesBslsFiveDeepChainForTimeBrightness() {
		// Verbatim from BSL, lines 145-159. Every intermediate is defined on a line before the one
		// that reads it, so declaration order is the whole mechanism.
		String properties = """
			variable.float.tAmin=frac(sunAngle - 0.033333333)
			variable.float.tAlin=if(tAmin < 0.433333333, tAmin * 1.15384615385, tAmin * 0.882352941176 + 0.117647058824)
			variable.float.hA=if(tAlin > 0.5, 1.0, 0.0)
			variable.float.tAfrc=frac(tAlin * 2.0)
			variable.float.tAfrs=tAfrc * tAfrc * (3.0 - 2.0 * tAfrc)
			variable.float.tAmix=if(hA < 0.5, 0.3, -0.1)
			uniform.float.timeAngle=(tAfrc * (1.0 - tAmix) + tAfrs * tAmix + hA) * 0.5
			uniform.float.timeBrightness=max(sin(timeAngle * 6.28318530718), 0.0)
			""";

		List<String> unparseable = new ArrayList<>();
		CustomUniforms uniforms = CustomUniforms.parse(properties, unparseable);
		assertTrue(unparseable.isEmpty(), unparseable.toString());

		Map<String, Float> uploads = uniforms.evaluate(context(Map.of("sunAngle", 0.28f)));

		// Only the two uniform.* entries reach the GPU; the six variable.* ones are scaffolding.
		assertEquals(2, uploads.size(), uploads.toString());
		assertTrue(uploads.containsKey("timeBrightness"));
		assertTrue(uploads.get("timeBrightness") > 0.0f,
			"a little after sunrise the sun must be up: " + uploads);
	}

	@Test
	void keepsOnlyTheLastOfADuplicatedName() {
		// BSL declares thirteen names twice - once against BIOME_* constants, once against numeric
		// ids. The later definition is the one OptiFine uses.
		String properties = """
			uniform.float.isDesert=1.0
			uniform.float.isDesert=2.0
			""";

		Map<String, Float> uploads = CustomUniforms.parse(properties, new ArrayList<>())
			.evaluate(context(Map.of()));

		assertEquals(1, uploads.size());
		assertEquals(2.0f, uploads.get("isDesert"), 1.0e-6f);
	}

	@Test
	void joinsTheBackslashContinuationsBslUsesForBiomeLists() {
		// Read line by line, each fragment parses separately and all of them are discarded.
		String properties = """
			uniform.float.isCold=if(in(biome, 10, \\
			11, 12), 1, 0)
			""";

		List<String> unparseable = new ArrayList<>();
		Map<String, Float> uploads = CustomUniforms.parse(properties, unparseable)
			.evaluate(context(Map.of("biome", 12.0f)));

		assertTrue(unparseable.isEmpty(), unparseable.toString());
		assertEquals(1.0f, uploads.get("isCold"), 1.0e-6f);
	}

	@Test
	void letsAPackDefinitionShadowALoaderSuppliedValue() {
		// OptiFine's semantics, and BSL depends on it: it redefines timeAngle, shadowFade and
		// blindFactor even though a loader supplies all three.
		String properties = "uniform.float.timeAngle=0.75\n";

		Map<String, Float> uploads = CustomUniforms.parse(properties, new ArrayList<>())
			.evaluate(context(Map.of("timeAngle", 0.1f)));

		assertEquals(0.75f, uploads.get("timeAngle"), 1.0e-6f);
	}

	@Test
	void dropsOneUnparseableLineWithoutLosingTheRest() {
		// A line this evaluator cannot read is almost always a newer OptiFine feature. Losing that
		// one value beats refusing to load the pack.
		String properties = """
			uniform.float.good=1.0
			uniform.float.broken=max(
			uniform.float.alsoGood=2.0
			""";

		List<String> unparseable = new ArrayList<>();
		Map<String, Float> uploads = CustomUniforms.parse(properties, unparseable)
			.evaluate(context(Map.of()));

		assertEquals(1, unparseable.size(), unparseable.toString());
		assertEquals(2, uploads.size(), uploads.toString());
		assertEquals(1.0f, uploads.get("good"), 1.0e-6f);
		assertEquals(2.0f, uploads.get("alsoGood"), 1.0e-6f);
	}

	@Test
	void ignoresEverythingThatIsNotAUniformDefinition() {
		String properties = """
			shadowMapResolution=2048
			screen.SHADOW=SHADOW_ENTITY SHADOW_BLOCK
			sliders=shadowDistance sunPathRotation
			uniform.float.real=3.0
			""";

		CustomUniforms uniforms = CustomUniforms.parse(properties, new ArrayList<>());

		assertEquals(1, uniforms.definitions().size());
		assertEquals("real", uniforms.definitions().get(0).name());
	}

	@Test
	void reportsAnEmptyPackAsEmpty() {
		// Both Complementary packs declare none at all.
		assertTrue(CustomUniforms.parse("shadowMapResolution=1024\n", new ArrayList<>()).isEmpty());
	}

	@Test
	void stripsATrailingCommentFromTheExpression() {
		Map<String, Float> uploads = CustomUniforms.parse(
				"uniform.float.x=1.0 # the value\n", new ArrayList<>())
			.evaluate(context(Map.of()));

		assertEquals(1.0f, uploads.get("x"), 1.0e-6f);
	}

	@Test
	void doesNotUploadVariableEntries() {
		String properties = "variable.float.helper=5.0\nuniform.float.real=helper * 2.0\n";

		Map<String, Float> uploads = CustomUniforms.parse(properties, new ArrayList<>())
			.evaluate(context(Map.of()));

		assertFalse(uploads.containsKey("helper"), "variable.* is scaffolding, not a uniform");
		assertEquals(10.0f, uploads.get("real"), 1.0e-6f);
	}
}
