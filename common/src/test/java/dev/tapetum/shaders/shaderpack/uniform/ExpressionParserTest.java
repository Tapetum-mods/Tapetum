package dev.tapetum.shaders.shaderpack.uniform;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The OptiFine custom-uniform language, exercised on expressions the surveyed packs actually ship.
 * BSL's {@code timeBrightness} and its biome flags appear verbatim: they are the eight uniforms the
 * GPU audit reported as declared but never supplied.
 */
class ExpressionParserTest {

	/** A context backed by a map, with smooth passing its target straight through. */
	private static class TestContext implements EvaluationContext {
		final Map<String, Float> values = new HashMap<>();
		int lastSmoothId = -1;

		@Override
		public float value(String name) {
			return values.getOrDefault(name, 0.0f);
		}

		@Override
		public float smooth(int id, float target, float fadeUp, float fadeDown) {
			lastSmoothId = id;
			return target;
		}
	}

	private static float eval(String source, TestContext context) {
		return ExpressionParser.parse(source).evaluate(context);
	}

	@Test
	void evaluatesBslsTimeBrightnessVerbatim() {
		// uniform.float.timeBrightness = max(sin(timeAngle * 6.28318530718), 0.0)
		TestContext context = new TestContext();
		String source = "max(sin(timeAngle * 6.28318530718), 0.0)";

		context.values.put("timeAngle", 0.25f);
		assertEquals(1.0f, eval(source, context), 1.0e-4f, "noon should be full brightness");

		context.values.put("timeAngle", 0.75f);
		assertEquals(0.0f, eval(source, context), 1.0e-4f, "midnight should clamp to zero");
	}

	@Test
	void evaluatesBslsBiomeFlagVerbatim() {
		// uniform.float.isDesert = smooth(2, if(in(biome, BIOME_DESERT), 1, 0), 10, 10)
		TestContext context = new TestContext();
		context.values.put("BIOME_DESERT", 2.0f);
		String source = "smooth(2, if(in(biome, BIOME_DESERT), 1, 0), 10, 10)";

		context.values.put("biome", 2.0f);
		assertEquals(1.0f, eval(source, context), 1.0e-6f);
		assertEquals(2, context.lastSmoothId, "the smoothing slot must be the one the pack named");

		context.values.put("biome", 7.0f);
		assertEquals(0.0f, eval(source, context), 1.0e-6f);
	}

	@Test
	void honoursPrecedenceRatherThanEvaluatingLeftToRight() {
		TestContext context = new TestContext();
		assertEquals(7.0f, eval("1 + 2 * 3", context), 1.0e-6f);
		assertEquals(9.0f, eval("(1 + 2) * 3", context), 1.0e-6f);
		assertEquals(1.0f, eval("2 > 1 && 3 > 2", context), 1.0e-6f);
	}

	@Test
	void readsAComponentAsPartOfTheName() {
		// Packs write cameraPosition.y and gbufferModelViewInverse.1; the component belongs to the
		// identifier, not to a member-access operator this language does not have.
		TestContext context = new TestContext();
		context.values.put("cameraPosition.y", 113.0f);

		assertEquals(1.0f, eval("if(cameraPosition.y >= 113.0, 1, 0)", context), 1.0e-6f);
	}

	@Test
	void supportsTheChainedIfPacksUse() {
		TestContext context = new TestContext();
		context.values.put("x", 2.0f);

		assertEquals(20.0f, eval("if(x == 1, 10, x == 2, 20, 99)", context), 1.0e-6f);
		assertEquals(99.0f, eval("if(x == 5, 10, x == 6, 20, 99)", context), 1.0e-6f);
	}

	@Test
	void treatsAnUnknownNameAsZeroRatherThanFailing() {
		// Matches how a pack behaves under OptiFine when a feature is unavailable.
		assertEquals(0.0f, eval("somethingNobodySupplies * 5.0", new TestContext()), 1.0e-6f);
	}

	@Test
	void treatsAnUnknownFunctionAsZeroRatherThanFailing() {
		// Almost always a newer OptiFine feature. Losing one effect beats refusing the pack.
		assertEquals(0.0f, eval("futureFunction(1, 2)", new TestContext()), 1.0e-6f);
	}

	@Test
	void returnsZeroOnDivisionByZeroInsteadOfInfinity() {
		// An infinity here becomes a NaN in the shader, spreads through every later expression, and
		// reaches the frame as black with nothing in the log to explain it.
		assertEquals(0.0f, eval("5.0 / 0.0", new TestContext()), 1.0e-6f);
		assertEquals(0.0f, eval("5.0 % 0.0", new TestContext()), 1.0e-6f);
	}

	@Test
	void foldsMinAndMaxOverAnyNumberOfArguments() {
		TestContext context = new TestContext();
		assertEquals(1.0f, eval("min(3, 1, 7, 4)", context), 1.0e-6f);
		assertEquals(7.0f, eval("max(3, 1, 7, 4)", context), 1.0e-6f);
	}

	@Test
	void clampsAndTakesTheFractionalPart() {
		TestContext context = new TestContext();
		assertEquals(0.5f, eval("clamp(1.5, 0.0, 0.5)", context), 1.0e-6f);
		assertEquals(0.25f, eval("frac(2.25)", context), 1.0e-6f);
		assertEquals(0.75f, eval("frac(-2.25)", context), 1.0e-6f, "frac must stay non-negative");
	}

	@Test
	void parsesUnaryMinusAndScientificNotation() {
		TestContext context = new TestContext();
		assertEquals(-40.0f, eval("-40.0", context), 1.0e-6f);
		assertEquals(0.001f, eval("1.0e-3", context), 1.0e-9f);
	}

	@Test
	void rejectsAnExpressionItCannotParse() {
		assertThrows(ExpressionParser.SyntaxException.class, () -> ExpressionParser.parse("1 + "));
		assertThrows(ExpressionParser.SyntaxException.class, () -> ExpressionParser.parse("(1 + 2"));
	}
}
