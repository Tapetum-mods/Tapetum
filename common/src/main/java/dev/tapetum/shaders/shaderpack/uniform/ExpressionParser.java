package dev.tapetum.shaders.shaderpack.uniform;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses OptiFine's custom-uniform expression language into something evaluable.
 *
 * <p>Packs define their own uniforms in {@code shaders.properties} as expressions over the loader's
 * values: BSL writes {@code uniform.float.timeBrightness = max(sin(timeAngle * 6.28318530718), 0.0)}
 * and gets a day/night curve the loader never has to know about. Without an evaluator those uniforms
 * arrive as zero, which for a brightness multiplier means the pack's daylight never turns on.</p>
 *
 * <p>The grammar implemented here is the one the five surveyed packs actually use — measured, not
 * guessed: seventeen functions, arithmetic, comparisons, {@code &&}/{@code ||}, and component access
 * written as part of the name ({@code cameraPosition.y}, {@code gbufferModelViewInverse.1}).
 * Booleans are floats throughout, as OptiFine treats them.</p>
 */
public final class ExpressionParser {

	/** Thrown when an expression cannot be parsed; the caller falls back rather than failing a pack. */
	public static class SyntaxException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public SyntaxException(String message) {
			super(message);
		}
	}

	private final List<String> tokens;
	private int position;

	private ExpressionParser(List<String> tokens) {
		this.tokens = tokens;
	}

	public static Expression parse(String source) {
		ExpressionParser parser = new ExpressionParser(tokenise(source));
		Expression expression = parser.parseOr();
		if (parser.position < parser.tokens.size()) {
			throw new SyntaxException("unexpected '" + parser.peek() + "' in: " + source);
		}
		return expression;
	}

	// --- tokeniser ---------------------------------------------------------------------------

	private static final String[] TWO_CHARACTER_OPERATORS = { "<=", ">=", "==", "!=", "&&", "||" };

	private static List<String> tokenise(String source) {
		List<String> tokens = new ArrayList<>();
		int i = 0;

		while (i < source.length()) {
			char c = source.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < source.length()
					&& Character.isDigit(source.charAt(i + 1)))) {
				int start = i;
				while (i < source.length() && (Character.isDigit(source.charAt(i))
						|| source.charAt(i) == '.' || source.charAt(i) == 'e' || source.charAt(i) == 'E'
						|| ((source.charAt(i) == '-' || source.charAt(i) == '+') && i > start
							&& (source.charAt(i - 1) == 'e' || source.charAt(i - 1) == 'E')))) {
					i++;
				}
				tokens.add(source.substring(start, i));
			} else if (Character.isLetter(c) || c == '_') {
				int start = i;
				// The component is part of the name: cameraPosition.y and gbufferModelViewInverse.1
				// are single identifiers as far as this language is concerned.
				while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
						|| source.charAt(i) == '_' || source.charAt(i) == '.')) {
					i++;
				}
				tokens.add(source.substring(start, i));
			} else {
				String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
				boolean matched = false;
				for (String operator : TWO_CHARACTER_OPERATORS) {
					if (operator.equals(two)) {
						tokens.add(operator);
						i += 2;
						matched = true;
						break;
					}
				}
				if (!matched) {
					tokens.add(String.valueOf(c));
					i++;
				}
			}
		}

		return tokens;
	}

	// --- recursive descent, loosest binding first ---------------------------------------------

	private Expression parseOr() {
		Expression left = parseAnd();
		while (accept("||")) {
			Expression right = parseAnd();
			Expression captured = left;
			left = ctx -> truth(captured.evaluate(ctx)) || truth(right.evaluate(ctx)) ? 1.0f : 0.0f;
		}
		return left;
	}

	private Expression parseAnd() {
		Expression left = parseComparison();
		while (accept("&&")) {
			Expression right = parseComparison();
			Expression captured = left;
			left = ctx -> truth(captured.evaluate(ctx)) && truth(right.evaluate(ctx)) ? 1.0f : 0.0f;
		}
		return left;
	}

	private Expression parseComparison() {
		Expression left = parseAdditive();
		for (String operator : new String[] { "==", "!=", "<=", ">=", "<", ">" }) {
			if (!accept(operator)) {
				continue;
			}
			Expression right = parseAdditive();
			Expression captured = left;
			return ctx -> compare(operator, captured.evaluate(ctx), right.evaluate(ctx));
		}
		return left;
	}

	private Expression parseAdditive() {
		Expression left = parseMultiplicative();
		while (true) {
			if (accept("+")) {
				Expression right = parseMultiplicative();
				Expression captured = left;
				left = ctx -> captured.evaluate(ctx) + right.evaluate(ctx);
			} else if (accept("-")) {
				Expression right = parseMultiplicative();
				Expression captured = left;
				left = ctx -> captured.evaluate(ctx) - right.evaluate(ctx);
			} else {
				return left;
			}
		}
	}

	private Expression parseMultiplicative() {
		Expression left = parseUnary();
		while (true) {
			if (accept("*")) {
				Expression right = parseUnary();
				Expression captured = left;
				left = ctx -> captured.evaluate(ctx) * right.evaluate(ctx);
			} else if (accept("/")) {
				Expression right = parseUnary();
				Expression captured = left;
				// Division by zero yields zero rather than an infinity that would spread through every
				// later expression and reach the shader as a NaN nothing can trace back.
				left = ctx -> {
					float divisor = right.evaluate(ctx);
					return divisor == 0.0f ? 0.0f : captured.evaluate(ctx) / divisor;
				};
			} else if (accept("%")) {
				Expression right = parseUnary();
				Expression captured = left;
				left = ctx -> {
					float divisor = right.evaluate(ctx);
					return divisor == 0.0f ? 0.0f : captured.evaluate(ctx) % divisor;
				};
			} else {
				return left;
			}
		}
	}

	private Expression parseUnary() {
		if (accept("-")) {
			Expression operand = parseUnary();
			return ctx -> -operand.evaluate(ctx);
		}
		if (accept("+")) {
			return parseUnary();
		}
		if (accept("!")) {
			Expression operand = parseUnary();
			return ctx -> truth(operand.evaluate(ctx)) ? 0.0f : 1.0f;
		}
		return parsePrimary();
	}

	private Expression parsePrimary() {
		if (position >= tokens.size()) {
			throw new SyntaxException("expression ended early");
		}

		if (accept("(")) {
			Expression inner = parseOr();
			expect(")");
			return inner;
		}

		String token = tokens.get(position++);
		if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '.') {
			float literal = Float.parseFloat(token);
			return ctx -> literal;
		}

		if (position < tokens.size() && "(".equals(tokens.get(position))) {
			position++;
			List<Expression> arguments = new ArrayList<>();
			if (!accept(")")) {
				do {
					arguments.add(parseOr());
				} while (accept(","));
				expect(")");
			}
			return ExpressionFunctions.build(token, arguments);
		}

		return ctx -> ctx.value(token);
	}

	// --- helpers -----------------------------------------------------------------------------

	private static boolean truth(float value) {
		return value != 0.0f;
	}

	private static float compare(String operator, float left, float right) {
		boolean result = switch (operator) {
			case "==" -> left == right;
			case "!=" -> left != right;
			case "<=" -> left <= right;
			case ">=" -> left >= right;
			case "<" -> left < right;
			default -> left > right;
		};
		return result ? 1.0f : 0.0f;
	}

	private String peek() {
		return position < tokens.size() ? tokens.get(position) : "<end>";
	}

	private boolean accept(String token) {
		if (position < tokens.size() && tokens.get(position).equals(token)) {
			position++;
			return true;
		}
		return false;
	}

	private void expect(String token) {
		if (!accept(token)) {
			throw new SyntaxException("expected '" + token + "' but found '" + peek() + "'");
		}
	}
}
