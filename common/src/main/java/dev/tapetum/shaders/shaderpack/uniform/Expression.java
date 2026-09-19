package dev.tapetum.shaders.shaderpack.uniform;

/**
 * One parsed OptiFine custom-uniform expression, ready to evaluate once per frame.
 *
 * <p>Parsing is separated from evaluation because these run every frame for every pass: BSL declares
 * forty-one of them. Re-parsing the text each time would be work repeated sixty times a second for a
 * result that only depends on the values handed in.</p>
 */
@FunctionalInterface
public interface Expression {
	/** Everything is a float, including booleans — 1.0 for true, 0.0 for false, as OptiFine does. */
	float evaluate(EvaluationContext context);
}
