package dev.tapetum.shaders.shaderpack.glsl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the inputs a fragment shader declares, so a vertex shader can be generated that actually
 * links against it.
 *
 * <p>GLSL requires the two stages to agree on every varying's name, type <em>and</em> interpolation
 * qualifier. A mismatch is not a warning — the program fails to link with "Input of fragment shader
 * 'x' differs in type/qualifiers to that written by vertex shader", which is exactly how
 * Complementary Reimagined failed: it declares {@code noperspective in vec2 texCoord}, against a
 * built-in vertex shader that wrote a plain (implicitly {@code smooth}) {@code out vec2 texCoord}.</p>
 *
 * <p>Generating the vertex stage from the fragment stage is the tractable half of the problem here.
 * The other option — compiling the pack's own {@code final.vsh} — does not work for this pipeline:
 * those are written against the fixed-function pipeline ({@code ftransform()},
 * {@code gl_TextureMatrix[0]}, {@code gl_MultiTexCoord0}), all removed in core profile, and they
 * expect real vertex attributes that an attributeless full-screen triangle does not supply.</p>
 */
public final class GlslStageLinkage {
	/**
	 * A varying the fragment shader expects, exactly as it must be re-declared on the vertex side.
	 *
	 * @param qualifiers interpolation qualifiers ({@code noperspective}, {@code flat}, ...), or
	 *                   empty for the default
	 */
	public record FragmentInput(String qualifiers, String type, String name) {
		/** The matching vertex-stage declaration, qualifiers included. */
		public String asVertexOutput() {
			return (qualifiers.isEmpty() ? "" : qualifiers + " ") + "out " + type + " " + name + ";";
		}
	}

	/**
	 * A declaration at statement level: optional interpolation qualifiers, {@code in}, a type, a
	 * name. Deliberately anchored to the start of a line so that {@code in} appearing inside a
	 * function body or a parameter list is not mistaken for a declaration.
	 */
	private static final Pattern INPUT_DECLARATION = Pattern.compile(
		"^\\s*((?:(?:centroid|sample|flat|noperspective|smooth)\\s+)*)in\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+"
			+ "([A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)\\s*;");

	/** Names packs conventionally use for the full-screen pass's texture coordinate. */
	private static final Set<String> TEXTURE_COORDINATE_NAMES =
		Set.of("texCoord", "texcoord", "TexCoord", "texCoords", "texcoords", "TexCoords");

	private static final Pattern VERSION_DIRECTIVE =
		Pattern.compile("^\\s*#\\s*version\\s+(\\d+)");

	/** The lowest version this generator's own output needs. */
	private static final int FALLBACK_VERSION = 150;

	private GlslStageLinkage() {
	}

	/**
	 * The {@code #version} the given source declares, or {@value #FALLBACK_VERSION} if it declares
	 * none. The vertex stage has to be generated at the same version as the fragment stage: GLSL
	 * refuses to link two different ones.
	 */
	public static int declaredVersion(String source) {
		for (String line : source.split("\n", -1)) {
			Matcher matcher = VERSION_DIRECTIVE.matcher(line);
			if (matcher.find()) {
				return Integer.parseInt(matcher.group(1));
			}
		}
		return FALLBACK_VERSION;
	}

	/**
	 * Raises the vertex stage to the fragment stage's version when they disagree.
	 *
	 * <p>If the vertex file declares no {@code #version}, the directive is injected at the top so the
	 * shader remains linkable with the fragment stage.</p>
	 */
	public static String alignVersions(String vertexSource, String fragmentSource, String passName) {
		int vertexVersion = declaredVersion(vertexSource);
		int fragmentVersion = declaredVersion(fragmentSource);
		if (vertexVersion >= fragmentVersion) {
			return vertexSource;
		}

		String[] lines = vertexSource.split("\n", -1);
		boolean replaced = false;
		for (int i = 0; i < lines.length; i++) {
			if (VERSION_DIRECTIVE.matcher(lines[i]).find()) {
				lines[i] = lines[i].replaceFirst("^\\s*#\\s*version\\s+\\d+", "#version " + fragmentVersion);
				replaced = true;
				break;
			}
		}
		if (replaced) {
			return String.join("\n", lines);
		}
		return "#version " + fragmentVersion + "\n" + vertexSource;
	}

	/**
	 * Returns every varying the given (already include-expanded and core-profile-patched) fragment
	 * source declares as an input, in declaration order and without duplicates.
	 *
	 * <p>Preprocessor branches are <em>not</em> evaluated, so a declaration inside a disabled
	 * {@code #ifdef} is reported too. That errs in the safe direction: a vertex output nothing reads
	 * is at worst a driver warning, whereas a missing one fails the link outright.</p>
	 */
	public static List<FragmentInput> fragmentInputs(String fragmentSource) {
		List<FragmentInput> inputs = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (String line : fragmentSource.split("\n", -1)) {
			String trimmed = line.trim();
			// Directives and comments can look close enough to a declaration to matter.
			if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
				continue;
			}

			Matcher matcher = INPUT_DECLARATION.matcher(line);
			if (!matcher.find()) {
				continue;
			}

			// One declaration may name several varyings: Complementary writes
			// "flat in vec3 upVec, sunVec, eastVec;". Reading only the first - or, as an earlier regex
			// did, failing to match at all because the first name is followed by a comma rather than a
			// semicolon - leaves the vertex stage silently missing them, and the program fails to link
			// with "Input of fragment shader 'sunVec' not written by vertex shader".
			for (String name : matcher.group(3).split(",")) {
				String trimmedName = name.trim();
				if (!trimmedName.isEmpty() && seen.add(trimmedName)) {
					inputs.add(new FragmentInput(matcher.group(1).trim(), matcher.group(2), trimmedName));
				}
			}
		}

		return inputs;
	}

	/**
	 * Builds a vertex shader for an attributeless full-screen triangle whose outputs match
	 * {@code fragmentSource}'s inputs.
	 *
	 * <p>A varying named like a texture coordinate receives the triangle's UV, which is what a
	 * full-screen pass actually wants. Anything else is declared and zeroed: this pipeline has no
	 * geometry to derive it from, and a zeroed varying at least lets the program link so the pass can
	 * run, rather than failing outright over a value the pack may not even use.</p>
	 *
	 * @param glslVersion the {@code #version} to emit — must match what the fragment stage ended up
	 *                    with, since GLSL will not link two different versions
	 */
	public static String buildFullScreenVertexShader(String fragmentSource, int glslVersion) {
		StringBuilder source = new StringBuilder();
		source.append("#version ").append(glslVersion).append('\n');

		List<FragmentInput> inputs = fragmentInputs(fragmentSource);
		for (FragmentInput input : inputs) {
			source.append(input.asVertexOutput()).append('\n');
		}

		source.append("""

			void main() {
			\tvec2 tapetum_pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
			""");

		for (FragmentInput input : inputs) {
			source.append('\t').append(input.name()).append(" = ");
			if (TEXTURE_COORDINATE_NAMES.contains(input.name()) && input.type().equals("vec2")) {
				source.append("tapetum_pos");
			} else {
				source.append(zeroValueOf(input.type()));
			}
			source.append(";\n");
		}

		source.append("\tgl_Position = vec4(tapetum_pos * 2.0 - 1.0, 0.0, 1.0);\n");
		source.append("}\n");

		return source.toString();
	}

	/** A zero-valued initialiser for {@code type}, used for varyings this pass cannot supply. */
	private static String zeroValueOf(String type) {
		return switch (type) {
			case "float" -> "0.0";
			case "int" -> "0";
			case "uint" -> "0u";
			case "bool" -> "false";
			case "vec2" -> "vec2(0.0)";
			case "vec3" -> "vec3(0.0)";
			case "vec4" -> "vec4(0.0)";
			case "ivec2" -> "ivec2(0)";
			case "ivec3" -> "ivec3(0)";
			case "ivec4" -> "ivec4(0)";
			case "mat2" -> "mat2(0.0)";
			case "mat3" -> "mat3(0.0)";
			case "mat4" -> "mat4(0.0)";
			// Unknown types still need *something* assignable; a value-initialised constructor is the
			// closest GLSL equivalent to a default, and covers the struct case.
			default -> type + "(0)";
		};
	}
}
