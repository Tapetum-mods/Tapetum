package dev.tapetum.shaders.shaderpack.uniform;

import java.util.List;

/**
 * The functions OptiFine's custom-uniform language provides.
 *
 * <p>Exactly the set the five surveyed packs call, counted across their {@code shaders.properties}:
 * {@code if} (74 uses), {@code smooth} (62), {@code in} (42), then the arithmetic helpers. Anything
 * outside it evaluates to zero rather than failing the pack — an unknown function is almost always a
 * feature of a newer OptiFine, and a pack that loses one effect is better than a pack that will not
 * load.</p>
 */
final class ExpressionFunctions {

	private ExpressionFunctions() {
	}

	static Expression build(String name, List<Expression> arguments) {
		return switch (name) {
			case "if" -> ifChain(arguments);
			case "in" -> membership(arguments);
			case "smooth" -> smooth(arguments);
			case "abs" -> unary(arguments, Math::abs);
			case "sin" -> unary(arguments, v -> (float) Math.sin(v));
			case "cos" -> unary(arguments, v -> (float) Math.cos(v));
			case "atan" -> unary(arguments, v -> (float) Math.atan(v));
			case "log" -> unary(arguments, v -> v <= 0.0f ? 0.0f : (float) Math.log(v));
			case "sqrt" -> unary(arguments, v -> v < 0.0f ? 0.0f : (float) Math.sqrt(v));
			case "floor" -> unary(arguments, v -> (float) Math.floor(v));
			case "ceil" -> unary(arguments, v -> (float) Math.ceil(v));
			case "frac" -> unary(arguments, v -> v - (float) Math.floor(v));
			case "min" -> fold(arguments, Math::min);
			case "max" -> fold(arguments, Math::max);
			case "clamp" -> clamp(arguments);
			case "fmod" -> binary(arguments, (a, b) -> b == 0.0f ? 0.0f : a % b);
			case "pow" -> binary(arguments, (a, b) -> (float) Math.pow(a, b));
			// vec2/vec3 build values this loader only ever reads one component of, so the first
			// component is the useful answer and the rest would be discarded anyway.
			case "vec2", "vec3", "vec4", "float" -> arguments.isEmpty() ? zero() : arguments.get(0);
			default -> zero();
		};
	}

	/**
	 * {@code if(cond, a, cond2, b, …, fallback)} — OptiFine allows the chained form, not just the
	 * three-argument one, and packs use it.
	 */
	private static Expression ifChain(List<Expression> arguments) {
		if (arguments.size() < 3) {
			return zero();
		}
		return context -> {
			for (int i = 0; i + 1 < arguments.size(); i += 2) {
				if (arguments.get(i).evaluate(context) != 0.0f) {
					return arguments.get(i + 1).evaluate(context);
				}
			}
			// An odd argument count leaves a trailing else branch; an even one has none.
			return arguments.size() % 2 == 1
				? arguments.get(arguments.size() - 1).evaluate(context) : 0.0f;
		};
	}

	/** {@code in(x, a, b, …)} — 1.0 when {@code x} equals any of the rest. */
	private static Expression membership(List<Expression> arguments) {
		if (arguments.isEmpty()) {
			return zero();
		}
		return context -> {
			float subject = arguments.get(0).evaluate(context);
			for (int i = 1; i < arguments.size(); i++) {
				if (arguments.get(i).evaluate(context) == subject) {
					return 1.0f;
				}
			}
			return 0.0f;
		};
	}

	private static Expression smooth(List<Expression> arguments) {
		if (arguments.isEmpty()) {
			return zero();
		}
		// smooth(value) and smooth(id, value) are both legal; the fade times default to one second.
		if (arguments.size() == 1) {
			return context -> context.smooth(0, arguments.get(0).evaluate(context), 1.0f, 1.0f);
		}
		Expression id = arguments.get(0);
		Expression value = arguments.get(1);
		Expression fadeUp = arguments.size() > 2 ? arguments.get(2) : context -> 1.0f;
		Expression fadeDown = arguments.size() > 3 ? arguments.get(3) : fadeUp;
		return context -> context.smooth((int) id.evaluate(context), value.evaluate(context),
			fadeUp.evaluate(context), fadeDown.evaluate(context));
	}

	private static Expression clamp(List<Expression> arguments) {
		if (arguments.size() < 3) {
			return arguments.isEmpty() ? zero() : arguments.get(0);
		}
		return context -> Math.clamp(arguments.get(0).evaluate(context),
			arguments.get(1).evaluate(context), arguments.get(2).evaluate(context));
	}

	private interface UnaryOperation {
		float apply(float value);
	}

	private interface BinaryOperation {
		float apply(float left, float right);
	}

	private static Expression unary(List<Expression> arguments, UnaryOperation operation) {
		return arguments.isEmpty() ? zero()
			: context -> operation.apply(arguments.get(0).evaluate(context));
	}

	private static Expression binary(List<Expression> arguments, BinaryOperation operation) {
		return arguments.size() < 2 ? zero()
			: context -> operation.apply(arguments.get(0).evaluate(context),
				arguments.get(1).evaluate(context));
	}

	/** {@code min}/{@code max} take any number of arguments in this language. */
	private static Expression fold(List<Expression> arguments, BinaryOperation operation) {
		if (arguments.isEmpty()) {
			return zero();
		}
		return context -> {
			float result = arguments.get(0).evaluate(context);
			for (int i = 1; i < arguments.size(); i++) {
				result = operation.apply(result, arguments.get(i).evaluate(context));
			}
			return result;
		};
	}

	private static Expression zero() {
		return context -> 0.0f;
	}
}
