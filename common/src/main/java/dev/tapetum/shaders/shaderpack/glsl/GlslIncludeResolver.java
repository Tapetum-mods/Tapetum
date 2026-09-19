package dev.tapetum.shaders.shaderpack.glsl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves the {@code #include} directives OptiFine-format shaderpacks rely on, which no GLSL
 * compiler implements — {@code #include} is not part of core GLSL, so a driver handed a pack's
 * source verbatim rejects it outright.
 *
 * <p>Two path forms appear in real packs, and both are supported here: a leading slash means
 * "relative to the pack's {@code shaders/} root" ({@code #include "/lib/common.glsl"} — the form
 * Complementary and BSL use throughout), anything else is relative to the including file's own
 * directory.</p>
 *
 * <p>Includes are expanded depth-first and each file is inlined every time it is named, matching
 * what a textual preprocessor does — packs rely on that, since a single library file is routinely
 * pulled into many programs. What is <em>not</em> allowed is a cycle: a file that (transitively)
 * includes itself would expand forever, so the include stack is tracked and a cycle throws rather
 * than hanging the game. Note this is deliberately narrower than "include each file once": a
 * diamond (two different files both including a common library) is legal and expands twice.</p>
 *
 * <p>This deals only with {@code #include}. The other half of making a real pack compile — legacy
 * compatibility-profile syntax like {@code varying}, {@code gl_FragColor} and {@code texture2D} —
 * is {@link GlslCompatPatcher}'s job, and runs after this.</p>
 */
public final class GlslIncludeResolver {
	/** Guards against a pathological pack (or a bug here) expanding without bound. */
	private static final int MAX_DEPTH = 64;

	/**
	 * Ceiling on the fully expanded source. Real packs land far below this — Complementary's
	 * {@code final.fsh} chain expands to roughly 30 KB — so this only ever trips on an include graph
	 * that is growing exponentially rather than on a legitimately large pack.
	 */
	private static final int MAX_EXPANDED_CHARS = 16 * 1024 * 1024;

	private final Path shaderRoot;
	private final Path confinementRoot;

	public GlslIncludeResolver(Path shaderRoot) {
		this.shaderRoot = shaderRoot;
		this.confinementRoot = shaderRoot.toAbsolutePath().normalize();
	}

	/**
	 * Returns {@code source} with every {@code #include} recursively expanded.
	 *
	 * @param source     the program source, already read from disk
	 * @param sourcePath the file {@code source} came from — relative includes resolve against its
	 *                   directory, and it anchors the cycle check
	 */
	public String resolve(String source, Path sourcePath) throws IOException, GlslIncludeException {
		StringBuilder out = new StringBuilder();
		expand(source, sourcePath, out, new ArrayDeque<>(), new HashSet<>());
		return out.toString();
	}

	/**
	 * Rejects an include that resolves outside the pack's own {@code shaders/} directory.
	 *
	 * <p>Shaderpacks are downloaded from the internet and are not trusted input. Without this,
	 * {@code #include "/../../../../etc/passwd"} reads an arbitrary local file and inlines its
	 * contents into the shader source — where it reaches the driver and, on the compile failure that
	 * follows, the log. That is an arbitrary local file read triggered by opening a pack, so path
	 * confinement is a security boundary here, not tidiness.</p>
	 *
	 * <p>{@code normalize()} alone is not the check: it collapses {@code ..} segments but is
	 * perfectly happy to produce a path above the root. The comparison against the normalized root is
	 * what actually confines it.</p>
	 */
	private void requireInsidePack(Path resolved, String includeTarget, Path sourcePath)
			throws GlslIncludeException {
		Path normalized = resolved.toAbsolutePath().normalize();

		if (!normalized.startsWith(confinementRoot)) {
			throw new GlslIncludeException("#include \"" + includeTarget + "\" in " + describe(sourcePath)
				+ " resolves outside the shaderpack (" + normalized + "); a pack may only include its own files");
		}
	}

	private void expand(String source, Path sourcePath, StringBuilder out, Deque<Path> stack, Set<Path> onStack)
			throws IOException, GlslIncludeException {
		if (stack.size() >= MAX_DEPTH) {
			throw new GlslIncludeException("#include nested more than " + MAX_DEPTH + " deep, giving up at "
				+ describe(sourcePath) + " (include chain: " + describeChain(stack) + ")");
		}

		Path normalizedSource = sourcePath == null ? null : sourcePath.toAbsolutePath().normalize();
		if (normalizedSource != null) {
			if (!onStack.add(normalizedSource)) {
				throw new GlslIncludeException("#include cycle: " + describe(sourcePath)
					+ " includes itself (include chain: " + describeChain(stack) + ")");
			}
			stack.push(normalizedSource);
		}

		try {
			for (String line : source.split("\n", -1)) {
				String includeTarget = parseIncludeTarget(line);

				if (includeTarget == null) {
					out.append(line).append('\n');
					continue;
				}

				Path included = resolveIncludePath(includeTarget, sourcePath);
				requireInsidePack(included, includeTarget, sourcePath);

				if (!Files.isRegularFile(included)) {
					throw new GlslIncludeException("#include \"" + includeTarget + "\" in " + describe(sourcePath)
						+ " points at " + describe(included) + ", which does not exist");
				}

				// A #line directive would be more helpful in compile errors, but GLSL's #line takes a
				// source-string *number*, not a name, so it cannot name the file; a comment is the only
				// way to leave a readable trail in the expanded source.
				out.append("// [tapetum] begin include: ").append(includeTarget).append('\n');
				expand(Files.readString(included), included, out, stack, onStack);
				out.append("// [tapetum] end include: ").append(includeTarget).append('\n');

				// Depth alone does not bound the output: a file that includes the same child twice
				// doubles the expansion per level, so a shallow, small pack can still expand
				// exponentially into an out-of-memory kill. Bound the total instead.
				if (out.length() > MAX_EXPANDED_CHARS) {
					throw new GlslIncludeException("#include expansion exceeded " + MAX_EXPANDED_CHARS
						+ " characters at " + describe(sourcePath)
						+ " - the pack's includes expand without bound (include chain: "
						+ describeChain(stack) + ")");
				}
			}
		} finally {
			if (normalizedSource != null) {
				stack.pop();
				onStack.remove(normalizedSource);
			}
		}
	}

	/**
	 * Returns the quoted path of an {@code #include} line, or null if the line isn't one. Only the
	 * {@code #include "..."} form is recognised — packs use it universally, and the angle-bracket
	 * form has no meaningful "system path" to resolve against here.
	 */
	private static String parseIncludeTarget(String line) {
		String trimmed = line.trim();
		if (!trimmed.startsWith("#")) {
			return null;
		}

		// Tolerate whitespace between '#' and the directive ("#  include"), which is legal in GLSL.
		String directive = trimmed.substring(1).trim();
		if (!directive.startsWith("include")) {
			return null;
		}

		String rest = directive.substring("include".length()).trim();
		if (rest.length() < 2 || rest.charAt(0) != '"') {
			return null;
		}

		int closing = rest.indexOf('"', 1);
		return closing < 0 ? null : rest.substring(1, closing);
	}

	private Path resolveIncludePath(String target, Path sourcePath) {
		if (target.startsWith("/")) {
			return shaderRoot.resolve(target.substring(1)).normalize();
		}

		Path base = sourcePath == null ? shaderRoot : sourcePath.getParent();
		return (base == null ? shaderRoot : base).resolve(target).normalize();
	}

	private String describe(Path path) {
		if (path == null) {
			return "<source>";
		}
		try {
			return shaderRoot.relativize(path).toString();
		} catch (IllegalArgumentException e) {
			// Different filesystem (a zip-backed pack vs a plain path) - fall back to the raw path.
			return path.toString();
		}
	}

	private String describeChain(Deque<Path> stack) {
		if (stack.isEmpty()) {
			return "<none>";
		}
		StringBuilder chain = new StringBuilder();
		// Deque iterates most-recently-pushed first; reverse it so the chain reads outermost-inward.
		Path[] entries = stack.toArray(new Path[0]);
		for (int i = entries.length - 1; i >= 0; i--) {
			chain.append(describe(entries[i]));
			if (i > 0) {
				chain.append(" -> ");
			}
		}
		return chain.toString();
	}
}
