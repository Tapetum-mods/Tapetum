package dev.tapetum.shaders.shaderpack.glsl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Makes a shaderpack's own vertex shader compile as a full-screen pass in a core profile.
 *
 * <p>Every one of the forty-nine composite passes across the five packs surveyed here ships a vertex
 * shader, and they carry real work. BSL's {@code deferred1.vsh} computes {@code sunVec},
 * {@code upVec} and {@code eastVec} from {@code timeAngle} — the vectors its entire lighting model is
 * built on. Generating a replacement that zeroes every varying it cannot fill, as
 * {@link GlslStageLinkage} does, therefore zeroes the sun direction: {@code dot(normal, sunVec)}
 * evaluates to zero everywhere and the frame comes out black.</p>
 *
 * <p>These shaders are written against the fixed-function pipeline, all of which core profile
 * removed. The names are rewritten rather than {@code #define}d, both to match how
 * {@link GlslCompatPatcher} already handles {@code gl_FragColor} and because GLSL reserves the
 * {@code gl_} prefix — a macro over one of those names is not portable.</p>
 *
 * <p>The substitutions are exact for a full-screen pass rather than approximations. The geometry is
 * a single attributeless triangle covering the screen in clip space, so the model-view and
 * projection matrices genuinely are identity and {@code ftransform()} genuinely is the vertex
 * position: there is no transform to apply.</p>
 */
public final class FullScreenVertexAdapter {

	/**
	 * Fixed-function names mapped to their stand-ins, longest first.
	 *
	 * <p>Order is load-bearing. {@code gl_ModelViewMatrix} is a prefix of
	 * {@code gl_ModelViewProjectionMatrix}, and {@code gl_Normal} of {@code gl_NormalMatrix};
	 * rewriting the shorter name first would corrupt the longer one into
	 * {@code tapetum_NormalMatrix} → {@code tapetum_ModelViewMatrixProjectionMatrix} and similar.
	 * A {@link LinkedHashMap} keeps the declaration order below.</p>
	 */
	private static final Map<String, String> SUBSTITUTIONS = new LinkedHashMap<>();

	static {
		SUBSTITUTIONS.put("gl_ModelViewProjectionMatrix", "tapetum_ModelViewProjectionMatrix");
		SUBSTITUTIONS.put("gl_ModelViewMatrix", "tapetum_ModelViewMatrix");
		SUBSTITUTIONS.put("gl_ProjectionMatrix", "tapetum_ProjectionMatrix");
		SUBSTITUTIONS.put("gl_NormalMatrix", "tapetum_NormalMatrix");
		SUBSTITUTIONS.put("gl_TextureMatrix", "tapetum_TextureMatrix");
		SUBSTITUTIONS.put("gl_MultiTexCoord0", "tapetum_MultiTexCoord0()");
		SUBSTITUTIONS.put("gl_MultiTexCoord1", "tapetum_MultiTexCoord1()");
		SUBSTITUTIONS.put("gl_MultiTexCoord2", "tapetum_MultiTexCoord1()");
		SUBSTITUTIONS.put("gl_Vertex", "tapetum_Vertex()");
		SUBSTITUTIONS.put("gl_Normal", "tapetum_Normal");
		SUBSTITUTIONS.put("gl_Color", "tapetum_Color");
	}

	/** {@code ftransform()} takes no arguments, so the call parentheses are matched and replaced. */
	private static final Pattern FTRANSFORM = Pattern.compile("\\bftransform\\s*\\(\\s*\\)");

	/** Declarations emitted only when the source actually uses the name they support. */
	private static final Map<String, String> DECLARATIONS = new LinkedHashMap<>();

	static {
		DECLARATIONS.put("tapetum_ModelViewProjectionMatrix",
			"const mat4 tapetum_ModelViewProjectionMatrix = mat4(1.0);");
		DECLARATIONS.put("tapetum_ModelViewMatrix", "const mat4 tapetum_ModelViewMatrix = mat4(1.0);");
		DECLARATIONS.put("tapetum_ProjectionMatrix", "const mat4 tapetum_ProjectionMatrix = mat4(1.0);");
		DECLARATIONS.put("tapetum_NormalMatrix", "const mat3 tapetum_NormalMatrix = mat3(1.0);");
		DECLARATIONS.put("tapetum_TextureMatrix",
			"const mat4 tapetum_TextureMatrix[2] = mat4[2](mat4(1.0), mat4(1.0));");
		DECLARATIONS.put("tapetum_Normal", "const vec3 tapetum_Normal = vec3(0.0, 0.0, 1.0);");
		DECLARATIONS.put("tapetum_Color", "const vec4 tapetum_Color = vec4(1.0);");
	}

	/**
	 * The screen-covering triangle, derived from {@code gl_VertexID}.
	 *
	 * <p>Written as functions rather than globals because a global initialiser has to be a constant
	 * expression in GLSL and {@code gl_VertexID} is not one.</p>
	 */
	private static final String GEOMETRY = """
		vec2 tapetum_ScreenUv() {
		\treturn vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
		}
		vec4 tapetum_Vertex() {
		\treturn vec4(tapetum_ScreenUv() * 2.0 - 1.0, 0.0, 1.0);
		}
		vec4 tapetum_MultiTexCoord0() {
		\treturn vec4(tapetum_ScreenUv(), 0.0, 1.0);
		}
		vec4 tapetum_MultiTexCoord1() {
		\treturn vec4(tapetum_ScreenUv(), 0.0, 1.0);
		}
		""";

	private FullScreenVertexAdapter() {
	}

	/** Whether {@code source} uses any fixed-function name this adapter would have to stand in for. */
	public static boolean isNeeded(String source) {
		if (FTRANSFORM.matcher(source).find()) {
			return true;
		}
		return SUBSTITUTIONS.keySet().stream().anyMatch(name -> containsIdentifier(source, name));
	}

	/**
	 * Rewrites the fixed-function names in {@code source} and returns the declarations its
	 * replacements need, ready to be emitted after the {@code #version} directive.
	 *
	 * @return the rewritten source, with the preamble already prepended after any leading directives
	 */
	public static String adapt(String source) {
		String rewritten = FTRANSFORM.matcher(source).replaceAll("tapetum_Vertex()");

		StringBuilder preamble = new StringBuilder();
		boolean needsGeometry = !rewritten.equals(source);

		for (Map.Entry<String, String> entry : SUBSTITUTIONS.entrySet()) {
			if (!containsIdentifier(rewritten, entry.getKey())) {
				continue;
			}
			rewritten = rewritten.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b",
				java.util.regex.Matcher.quoteReplacement(entry.getValue()));

			String replacement = entry.getValue();
			if (replacement.endsWith("()")) {
				needsGeometry = true;
			} else {
				String declaration = DECLARATIONS.get(replacement);
				if (declaration != null && preamble.indexOf(declaration) < 0) {
					preamble.append(declaration).append('\n');
				}
			}
		}

		if (needsGeometry) {
			preamble.insert(0, GEOMETRY);
		}
		if (preamble.length() == 0) {
			return rewritten;
		}

		preamble.insert(0, "// [tapetum] full-screen vertex adapter\n");
		return insertAfterDirectives(rewritten, preamble.toString());
	}

	/** Whether {@code name} appears as a whole identifier rather than inside a longer one. */
	private static boolean containsIdentifier(String source, String name) {
		return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source).find();
	}

	/**
	 * Places the preamble after the {@code #version} directive and any {@code #extension} lines.
	 *
	 * <p>Both orderings are hard requirements: {@code #version} must be the first token in the
	 * shader, and every {@code #extension} must precede any non-preprocessor token — which these
	 * declarations are.</p>
	 *
	 * <p>This locates the {@code #version} line directly rather than scanning forward until something
	 * stops looking like a header. That earlier approach broke on the commonest header shape there
	 * is: BSL opens with a block comment whose inner lines are plain prose, which no "is this still a
	 * comment?" test recognises line by line, so the preamble landed above the {@code #version} and
	 * the driver rejected all eleven of its passes. Searching for the directive sidesteps the question
	 * entirely.</p>
	 */
	private static String insertAfterDirectives(String source, String preamble) {
		String[] lines = source.split("\n", -1);

		int insertAt = 0;
		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("#version")) {
				insertAt = i + 1;
			} else if (trimmed.startsWith("#extension") && insertAt > 0) {
				// Extensions were hoisted to just below #version, so keep stepping past them.
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
