package dev.tapetum.shaders.shaderpack.glsl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared machinery for replacing removed fixed-function names with declarations of our own.
 *
 * <p>Both vertex adapters — the full-screen one and the geometry one — do the same three things:
 * rewrite names in a fixed order, emit only the declarations the source turned out to need, and place
 * those declarations where GLSL allows them. Each of the three has a way of going subtly wrong, so
 * they live here once rather than twice.</p>
 */
final class FixedFunctionRewriter {

	private final Map<String, String> substitutions = new LinkedHashMap<>();
	private final Map<String, String> declarations = new LinkedHashMap<>();
	private final StringBuilder preamble = new StringBuilder();
	private String source;

	FixedFunctionRewriter(String source) {
		this.source = source;
	}

	/**
	 * Registers a rewrite and the declaration it depends on.
	 *
	 * <p><b>Registration order is load-bearing.</b> {@code gl_ModelViewMatrix} is a prefix of
	 * {@code gl_ModelViewProjectionMatrix}, and {@code gl_Normal} of {@code gl_NormalMatrix}; applying
	 * the shorter name first corrupts the longer one into something nothing declares, and the link
	 * failure that follows points nowhere useful. Register longest first.</p>
	 *
	 * @param declaration emitted only if the name is actually present; null for a rewrite that needs
	 *                    no declaration of its own
	 */
	FixedFunctionRewriter rewrite(String builtin, String replacement, String declaration) {
		substitutions.put(builtin, replacement);
		if (declaration != null) {
			declarations.put(builtin, declaration);
		}
		return this;
	}

	/**
	 * Registers a rewrite, unless the source declares that name itself.
	 *
	 * <p>For the {@code gl_} builtins this cannot arise — the prefix is reserved. It can for the
	 * core-profile spellings: {@code projectionMatrix} is an ordinary identifier, and a pack is free
	 * to declare its own. Rewriting it then would both redirect the pack's own variable and emit a
	 * declaration beside the one already there. Left alone, the name stays as the pack wrote it and
	 * is bound under that name when the program is substituted.</p>
	 */
	FixedFunctionRewriter rewriteUnlessDeclared(String builtin, String replacement, String declaration) {
		return declaresIdentifier(builtin) ? this : rewrite(builtin, replacement, declaration);
	}

	/** Whether the source declares {@code name} as an attribute, uniform or varying of its own. */
	private boolean declaresIdentifier(String name) {
		return Pattern.compile(
				"^\\s*(?:flat\\s+|noperspective\\s+|smooth\\s+|centroid\\s+)*"
					+ "(?:in|out|attribute|varying|uniform)\\s+\\w+\\s+"
					+ Pattern.quote(name) + "\\s*(?:\\[[^\\]]*\\])?\\s*;",
				Pattern.MULTILINE)
			.matcher(source).find();
	}

	/** Registers a declaration emitted whenever {@code trigger} is present, without rewriting it. */
	FixedFunctionRewriter require(String trigger, String declaration) {
		if (contains(trigger)) {
			addDeclaration(declaration);
		}
		return this;
	}

	/** Whether the source uses {@code name} as a whole identifier, not inside a longer one. */
	boolean contains(String name) {
		return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source).find();
	}

	/** Replaces a call with no arguments, parentheses included. */
	FixedFunctionRewriter rewriteCall(String function, String replacement) {
		Pattern call = Pattern.compile("\\b" + Pattern.quote(function) + "\\s*\\(\\s*\\)");
		if (call.matcher(source).find()) {
			source = call.matcher(source).replaceAll(Matcher.quoteReplacement(replacement));
		}
		return this;
	}

	/** Applies every registered rewrite whose name appears, collecting the declarations they need. */
	FixedFunctionRewriter apply() {
		for (Map.Entry<String, String> entry : substitutions.entrySet()) {
			if (!contains(entry.getKey())) {
				continue;
			}
			source = source.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b",
				Matcher.quoteReplacement(entry.getValue()));
			addDeclaration(declarations.get(entry.getKey()));
		}
		return this;
	}

	private void addDeclaration(String declaration) {
		if (declaration != null && preamble.indexOf(declaration) < 0) {
			preamble.append(declaration).append('\n');
		}
	}

	boolean changed() {
		return preamble.length() > 0;
	}

	/** The rewritten source with its preamble in place, or the original when nothing was needed. */
	String finish(String heading) {
		if (preamble.length() == 0) {
			return source;
		}
		return insertAfterDirectives(source, "// [tapetum] " + heading + "\n" + preamble);
	}

	/**
	 * Places the preamble after {@code #version} and any {@code #extension} lines.
	 *
	 * <p>Both orderings are hard requirements: {@code #version} must be the first token in the shader,
	 * and every {@code #extension} must precede any non-preprocessor token — which these declarations
	 * are.</p>
	 *
	 * <p>This finds the {@code #version} directive rather than scanning forward until something stops
	 * looking like a header. That earlier approach broke on the commonest header shape there is: BSL
	 * opens with a block comment whose inner lines are plain prose, so the scan stopped on line two,
	 * the preamble landed above the {@code #version}, and the driver rejected all eleven of its
	 * passes.</p>
	 */
	private static String insertAfterDirectives(String source, String preamble) {
		String[] lines = source.split("\n", -1);

		int insertAt = 0;
		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("#version")) {
				insertAt = i + 1;
			} else if (trimmed.startsWith("#extension") && insertAt > 0) {
				insertAt = i + 1;
			}
		}

		StringBuilder out = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (i == insertAt) {
				out.append(preamble);
			}
			out.append(lines[i]);
			if (i < lines.length - 1) {
				out.append('\n');
			}
		}
		if (insertAt >= lines.length) {
			out.append(preamble);
		}
		return out.toString();
	}
}
