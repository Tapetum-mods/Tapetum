package dev.tapetum.shaders.shaderpack.uniform;

/**
 * Supplies the values an expression reads: the loader's own uniforms, the world state a pack asks
 * about, and the memory that {@code smooth()} needs.
 */
public interface EvaluationContext {
	/**
	 * The value of a name, or 0 when nothing supplies it.
	 *
	 * <p>Names may carry a component: packs write {@code cameraPosition.y} and
	 * {@code shadowLightPosition.x}, and the matrix rows as {@code gbufferModelViewInverse.1}.
	 * Returning 0 for an unknown name rather than failing matches how a pack behaves under OptiFine
	 * when a feature is unavailable.</p>
	 */
	float value(String name);

	/**
	 * OptiFine's {@code smooth(id, value, fadeUpSeconds, fadeDownSeconds)}.
	 *
	 * <p>Stateful by design — it returns a value easing towards {@code target} rather than the target
	 * itself, which is why each call site carries its own {@code id}. Packs use it so that walking
	 * into a desert warms the light over ten seconds instead of switching palette between two frames.
	 * Evaluating it as a pass-through would work but look wrong in exactly the way the pack was
	 * avoiding.</p>
	 */
	float smooth(int id, float target, float fadeUpSeconds, float fadeDownSeconds);
}
