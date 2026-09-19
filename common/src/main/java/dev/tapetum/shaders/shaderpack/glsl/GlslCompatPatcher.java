package dev.tapetum.shaders.shaderpack.glsl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the compatibility-profile GLSL that OptiFine-format shaderpacks are written in into the
 * core-profile GLSL modern Minecraft's OpenGL context will actually accept.
 *
 * <p>Packs target OptiFine, which compiles them in a compatibility profile where the removed-in-core
 * builtins still exist. Minecraft's own context is core, so the same source fails to compile
 * verbatim — this is the reason a pack can be "found and selected" and still render nothing. Iris
 * solves this with glsl-transformer, a real GLSL parser; this is a deliberately smaller
 * transformation, driven by what real packs actually contain (checked against Complementary
 * Shaders' {@code final.fsh} chain, whose 6-line entry file needs every rule below).</p>
 *
 * <p>What is rewritten:</p>
 * <ul>
 *   <li>{@code varying} → {@code in} (fragment) or {@code out} (vertex); {@code attribute} → {@code in}.</li>
 *   <li>{@code texture2D}/{@code texture3D}/{@code textureCube} and their {@code *Lod}/{@code *Grad}
 *       variants → plain {@code texture}/{@code textureLod}/{@code textureGrad}, which are overloaded
 *       on sampler type in core.</li>
 *   <li>{@code gl_FragColor} → a declared {@code out vec4}, since core has no implicit fragment
 *       output. {@code gl_FragData[N]} is handled the same way, with one output per index used.</li>
 *   <li>A {@code #version} directive is added if absent, and a compatibility-era one
 *       ({@code #version 120}/{@code 130}) is raised, since the rewritten source needs at least 150.</li>
 * </ul>
 *
 * <p>What this deliberately does <b>not</b> do, and why it is not full pack compatibility: it is a
 * line/regex transformation, not a parse, so it does not understand preprocessor branches, macro
 * expansion, or scoping. A pack whose {@code #ifdef} arms disagree about types, or that builds
 * identifiers through macros, can still defeat it. It also does not supply the OptiFine uniform set
 * ({@code colortex0..N}, {@code frameTimeCounter}, matrices) — declaring those and binding real
 * values is separate work, and a pass that samples {@code colortex0} still needs the rendered scene
 * bound to it before it produces anything meaningful.</p>
 */
public final class GlslCompatPatcher {
	/** The lowest core-profile version that supports everything this patcher emits. */
	private static final int MINIMUM_VERSION = 150;

	/**
	 * {@code layout(location = ...)} on a fragment output only became legal here — emitting one at
	 * 150 is rejected outright ("'location' : not supported for this version"), so a shader needing
	 * explicit output locations has to be raised this far.
	 */
	private static final int LAYOUT_QUALIFIER_VERSION = 330;

	private static final String FRAG_COLOR_OUT = "tapetum_FragColor";
	private static final String FRAG_DATA_OUT_PREFIX = "tapetum_FragData";

	private static final Pattern VERSION_DIRECTIVE =
		Pattern.compile("^\\s*#\\s*version\\s+(\\d+)(\\s+\\w+)?\\s*$");

	// \b so a pack's own identifier that merely ends in "varying" is left alone.
	private static final Pattern VARYING_DECL = Pattern.compile("\\bvarying\\b");
	private static final Pattern ATTRIBUTE_DECL = Pattern.compile("\\battribute\\b");

	/**
	 * Legacy sampling builtins. Ordered longest-suffix-first so {@code texture2DLod} is not first
	 * matched as {@code texture2D} followed by a stray "Lod".
	 *
	 * <p>The optional {@code ARB} suffix is the {@code GL_ARB_shader_texture_lod} spelling of the
	 * same function, and is simply dropped: {@code texture2DGradARB} is {@code textureGrad}. This is
	 * not cosmetic. Apple's OpenGL 4.1 does not expose that extension, so BSL's parallax code —
	 * 188 calls across twenty of its programs — reached the driver as an undeclared identifier and
	 * took {@code gbuffers_water} down with it. {@code glslangValidator} implements the extension and
	 * accepted every one of them, which is the whole reason the compile audit exists.</p>
	 */
	private static final Pattern LEGACY_TEXTURE_CALL =
		Pattern.compile("\\btexture(?:1D|2D|3D|Cube|2DRect)(Lod|Proj|Grad|ProjLod|ProjGrad)?(?:ARB)?\\b");

	/**
	 * Extensions whose whole function surface this patcher rewrites to core equivalents, so the pack
	 * no longer calls anything they provide.
	 */
	private static final Set<String> REPLACED_EXTENSIONS = Set.of("GL_ARB_shader_texture_lod");

	/**
	 * The same suffix on the shadow-sampling builtins. Only the suffix is dropped; the name that
	 * remains is served by the stand-in functions the preamble declares.
	 */
	private static final Pattern LEGACY_SHADOW_ARB_CALL =
		Pattern.compile("\\b(shadow(?:1D|2D|2DRect)(?:Proj)?(?:Lod|Grad)?)ARB\\b");

	/**
	 * The fixed-function fog block, removed in core profile. Packs read {@code gl_Fog.start} and
	 * {@code gl_Fog.scale} to reproduce vanilla's fog curve; Complementary does so in both its fog
	 * library and its deferred pass. It cannot simply be declared under its own name — GLSL reserves
	 * every {@code gl_} identifier — so uses are renamed onto a struct this patcher declares.
	 */
	private static final Pattern GL_FOG = Pattern.compile("\\bgl_Fog\\b");

	private static final String FOG_REPLACEMENT = "tapetum_Fog";

	/** The replacement varying for {@code gl_FogFragCoord}. */
	private static final String FOG_FRAG_COORD_REPLACEMENT = "tapetum_FogFragCoord";

	/**
	 * A use of the fog <em>parameter</em> struct, matched whole: a plain {@code contains} on
	 * {@code tapetum_Fog} is also true of {@code tapetum_FogFragCoord}, and declared the struct and
	 * its uniform in every program that only ever wanted the varying.
	 */
	private static final Pattern FOG_PARAMETERS_USE = Pattern.compile("\\btapetum_Fog\\b");

	private static final Pattern GL_FRAG_COLOR = Pattern.compile("\\bgl_FragColor\\b");
	private static final Pattern GL_FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");

	/** A {@code #define}, used to know which names the source already gives a value. */
	private static final Pattern DEFINE_DIRECTIVE =
		Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_][A-Za-z0-9_]*)");

	/** {@code #if}/{@code #elif} - the only directives whose operands are evaluated arithmetically. */
	private static final Pattern ARITHMETIC_CONDITION =
		Pattern.compile("^\\s*#\\s*(?:if|elif)\\s+(.*)$");

	/** Names whose mere existence is tested: {@code #ifdef X}, {@code #ifndef X}, {@code defined X}. */
	private static final Pattern EXISTENCE_TEST =
		Pattern.compile("#\\s*(?:ifdef|ifndef)\\s+([A-Za-z_][A-Za-z0-9_]*)|defined\\s*\\(?\\s*([A-Za-z_][A-Za-z0-9_]*)");

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	/**
	 * GLSL keywords, never defaulted even if one appears in a condition.
	 *
	 * <p>Defence in depth behind comment stripping. Defining {@code in} as 0 rewrites every varying
	 * declaration in the file into nonsense ("noperspective 0 vec2 texCoord"), which is exactly what
	 * happened when trailing comments on {@code #if} lines were mistaken for operands.</p>
	 */
	private static final Set<String> GLSL_KEYWORDS = Set.of(
		"in", "out", "inout", "uniform", "attribute", "varying", "const", "flat", "smooth",
		"noperspective", "centroid", "sample", "layout", "struct", "void", "bool", "int", "uint",
		"float", "double", "if", "else", "for", "while", "do", "break", "continue", "return",
		"discard", "switch", "case", "default", "true", "false", "defined", "precision",
		"lowp", "mediump", "highp");

	/** The removed shadow-sampling builtins, matched to decide whether to declare stand-ins. */
	private static final Pattern SHADOW_SAMPLE_CALL =
		Pattern.compile("\\bshadow2D(Lod|Grad)?(?:ARB)?\\s*\\(");

	/**
	 * OptiFine's render-target format declarations — {@code const int colortex0Format = R11F_G11F_B10F;}
	 * and the {@code gauxNFormat}/{@code shadowcolorNFormat}/{@code depthtexNFormat} spellings. These
	 * look like GLSL but are configuration OptiFine parses out of the source; the right-hand side is a
	 * format enum name, not anything the compiler can resolve. Real packs ship them uncommented
	 * (AstralCore's {@code lib/pipelineSettings.glsl} declares all eight), so they must be stripped.
	 */
	private static final Pattern OPTIFINE_FORMAT_CONSTANT = Pattern.compile(
		"^const\\s+int\\s+\\w*(?:colortex|gaux|shadowcolor|depthtex|shadowtex)\\w*Format\\s*=\\s*\\w+\\s*;.*$");

	public enum Stage { VERTEX, FRAGMENT }

	private GlslCompatPatcher() {
	}

	/**
	 * Returns {@code source} rewritten for core profile. Expects includes to already be expanded
	 * (see {@link GlslIncludeResolver}) — an unexpanded {@code #include} is left untouched here and
	 * would fail to compile.
	 */
	public static String patch(String source, Stage stage) {
		return patch(source, stage, null);
	}

	/**
	 * @param macros the OptiFine macro set to hand the pack ({@code MC_VERSION} and friends), or
	 *               null to define none — see {@link ShaderMacros} for why omitting them breaks real
	 *               packs on strict drivers rather than merely limiting them
	 */
	public static String patch(String source, Stage stage, ShaderMacros macros) {
		String normalized = normalizeLinesAndSpliceContinuations(source);
		String withoutLegacyCalls = rewriteBody(normalized, stage);
		return applyVersionAndOutputs(withoutLegacyCalls, stage, macros);
	}

	/**
	 * Normalises line endings and joins backslash-continued lines, the way a C preprocessor's line
	 * splicing phase does, so everything downstream sees one logical line at a time.
	 *
	 * <p>Both halves are load-bearing on real packs:</p>
	 * <ul>
	 *   <li><b>CRLF.</b> Packs are routinely authored on Windows (Complementary Unbound ships CRLF
	 *       throughout). Splitting on {@code \n} otherwise leaves a stray {@code \r} at the end of
	 *       every line, and a backslash followed by {@code \r\n} is ambiguously a continuation —
	 *       compilers disagree about it, which is exactly the sort of thing that works on one driver
	 *       and fails on another.</li>
	 *   <li><b>Continuations.</b> This class rewrites line by line, so a construct spanning a
	 *       continuation would otherwise be seen in fragments: Complementary's multi-line
	 *       {@code #define printString(string)} has its first line treated as a directive and its
	 *       continuation lines as ordinary code. Splicing first makes that one {@code #define}, which
	 *       is what it actually is. It also removes continuations the pack never meant to write —
	 *       Complementary's ASCII-art banner has a line ending in {@code \} inside a block comment,
	 *       which GLSL 150 rejects outright as an unsupported line continuation.</li>
	 * </ul>
	 *
	 * <p>Splicing shifts line numbers relative to the pack's own files, so compiler diagnostics point
	 * at the spliced source rather than the original. Include expansion already shifts them, so this
	 * changes degree rather than kind.</p>
	 */
	private static String normalizeLinesAndSpliceContinuations(String source) {
		String normalized = source.replace("\r\n", "\n").replace('\r', '\n');

		// A backslash immediately before a newline continues the logical line; the pair disappears.
		// Deliberately not a regex over the whole string: sources are large and this stays linear.
		StringBuilder out = new StringBuilder(normalized.length());
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			if (c == '\\' && i + 1 < normalized.length() && normalized.charAt(i + 1) == '\n') {
				i++;
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	private static String rewriteBody(String source, Stage stage) {
		StringBuilder out = new StringBuilder(source.length() + 256);

		for (String line : source.split("\n", -1)) {
			// Leave preprocessor lines alone apart from #version (handled separately): rewriting
			// inside an #ifdef condition or a #define's replacement text is how a regex pass breaks
			// source it doesn't actually understand.
			String trimmed = line.trim();
			if (trimmed.startsWith("#") && !trimmed.startsWith("#define")) {
				out.append(line).append('\n');
				continue;
			}

			String patched = line;

			// A #define's replacement text is real code and does get rewritten, but its name must not:
			// splitting it off keeps "#define texture2D(...)" style macros from being mangled.
			if (trimmed.startsWith("#define")) {
				out.append(patchDefineBody(patched)).append('\n');
				continue;
			}

			// OptiFine reads render-target formats out of the source as configuration and strips them;
			// left in, they are not compilable GLSL at all (R11F_G11F_B10F and friends are not
			// identifiers the compiler knows). Blank the line rather than dropping it, so every later
			// line keeps its original number and compiler diagnostics still point at the right place.
			if (OPTIFINE_FORMAT_CONSTANT.matcher(trimmed).matches()) {
				out.append("// [tapetum] stripped OptiFine format constant: ").append(trimmed).append('\n');
				continue;
			}

			patched = GL_FOG.matcher(patched).replaceAll(FOG_REPLACEMENT);
			patched = VARYING_DECL.matcher(patched)
				.replaceAll(stage == Stage.FRAGMENT ? "in" : "out");
			patched = ATTRIBUTE_DECL.matcher(patched).replaceAll("in");
			patched = rewriteLegacyTextureCalls(patched);

			if (stage == Stage.FRAGMENT) {
				patched = GL_FRAG_COLOR.matcher(patched).replaceAll(FRAG_COLOR_OUT);
				patched = GL_FRAG_DATA.matcher(patched).replaceAll(FRAG_DATA_OUT_PREFIX + "$1");
			}

			out.append(patched).append('\n');
		}

		// split("\n", -1) yields a trailing empty element for a source ending in a newline, which the
		// loop turns back into that newline; strip the one extra we then added.
		if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
			out.setLength(out.length() - 1);
		}
		return out.toString();
	}

	/** Rewrites only the replacement text of a {@code #define}, never the macro's own name. */
	private static String patchDefineBody(String line) {
		String withoutHash = line.stripLeading();
		int leadingWhitespace = line.length() - withoutHash.length();
		String indent = line.substring(0, leadingWhitespace);

		// "#define" + name (possibly with a parameter list) + replacement text.
		Matcher head = Pattern.compile("^#\\s*define\\s+(\\w+(?:\\s*\\([^)]*\\))?)").matcher(withoutHash);
		if (!head.find()) {
			return line;
		}

		String namePart = withoutHash.substring(0, head.end());
		String body = withoutHash.substring(head.end());

		// Drop a trailing comment from the macro's value. OptiFine annotates every option this way
		// ("#define WORLD_SPACE_REF_MODE 2 //[1 2]" declares the values its GUI offers), and a strict
		// preprocessor carries the comment into the expansion: "#if WORLD_SPACE_REF_MODE == 1" then
		// becomes "#if 2 //[1 2] == 1" and fails with "unexpected tokens following #if". The
		// annotation is read from the pack's own files, never from this rewritten copy, so removing it
		// here costs nothing.
		body = body.replaceAll("//.*$", "").replaceAll("/\\*.*?\\*/", " ");

        return indent + namePart + rewriteLegacyTextureCalls(body);
	}

	/**
	 * A sampler the pack named {@code texture}, which core profile turned into a function name.
	 *
	 * <p>OptiFine's terrain sampler has always been called {@code texture}, and in the compatibility
	 * profile that was fine: the sampling function was {@code texture2D}. Core profile renamed the
	 * function to {@code texture}, so the pack's uniform now shadows it and every call becomes
	 * <i>"can't use function syntax on variable"</i>. BSL hits this in sixteen files, which cascade
	 * through includes into fifty-one of the hundred and twenty-five gbuffers programs.</p>
	 */
	private static final Pattern PACK_TEXTURE_SAMPLER = Pattern.compile(
		"^\\s*uniform\\s+sampler\\w*\\s+texture\\s*;", Pattern.MULTILINE);

	/**
	 * Every {@code texture} that is <em>not</em> followed by an opening parenthesis — i.e. the
	 * variable, never the function call. This is what lets {@code texture(texture, uv)} survive:
	 * the first stays the builtin, the second becomes the renamed uniform.
	 */
	private static final Pattern TEXTURE_AS_VARIABLE = Pattern.compile("\\btexture\\b(?!\\s*\\()");

	/**
	 * Renames a pack-declared {@code texture} sampler out of the way of the builtin.
	 *
	 * <p>Only applied when the source actually declares one: renaming the identifier unconditionally
	 * would rewrite unrelated uses in packs that never had the clash.</p>
	 */
	/** The fixed-function fog varying: written by the vertex stage, read by the fragment stage. */
	private static final Pattern FOG_FRAG_COORD = Pattern.compile("\\bgl_FogFragCoord\\b");

	/**
	 * Replaces {@code gl_FogFragCoord} with a varying of our own, declared for the right stage.
	 *
	 * <p>Core profile removed it, but packs still write it in the vertex stage and read it in the
	 * fragment stage — twenty-one and seventeen of the generated gbuffers programs respectively. It
	 * therefore cannot live in the vertex adapter: the two stages have to agree on the name, and only
	 * the patcher knows which one it is looking at.</p>
	 *
	 * <p>Only the rename happens here. The declaration is left to {@link #compatibilityPreamble},
	 * which is the one place that knows where an injected declaration may legally go — after the
	 * {@code #version} and after every top-level {@code #extension}. Prepending it here instead put a
	 * non-preprocessor token ahead of the pack's own {@code #version}, which disqualified that
	 * directive from keeping its position and sent the whole file down the fallback emission path.
	 * That path skips {@link #undefBeforeRedefinition}, so MakeUp's {@code gbuffers_water} lost the
	 * {@code #undef} pairing its {@code REFLECTION} option needed and failed to compile. Every
	 * program using fog took that path — thirty-eight of them — so the damage was not specific to
	 * that one pack.</p>
	 */
	/**
	 * A vertex output declared at file scope, with its interpolation qualifiers.
	 */
	private static final Pattern VERTEX_OUTPUT_DECL = Pattern.compile(
		"^\\s*(?:flat\\s+|noperspective\\s+|smooth\\s+|centroid\\s+)*out\\s+(\\w+)\\s+(\\w+)\\s*;");

	/** The body of {@code main}, matched so an initialiser can be placed at its top. */
	private static final Pattern MAIN_BODY =
		Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void)?\\s*\\)\\s*\\{");

	/**
	 * Types a zero initialiser can be written for. A struct or an array is left alone: the
	 * constructor spelling differs, and no surveyed pack declares one as a vertex output.
	 */
	private static final Set<String> ZEROABLE_TYPES = Set.of(
		"float", "double", "int", "uint", "bool",
		"vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
		"bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4",
		"mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4",
		"mat4x2", "mat4x3", "mat4x4");

	/**
	 * Gives a zero value to any vertex output the shader might leave unwritten.
	 *
	 * <p>GLSL allows it — an unwritten output simply has an undefined value — but Apple's linker does
	 * not, and rejects the whole program with "Input of fragment shader 'x' not written by vertex
	 * shader". Mellow's {@code gbuffers_clouds} is exactly this shape: when its cloud style is not
	 * the vanilla one it collapses the geometry with {@code gl_Position = vec4(-1)} and returns
	 * without touching {@code texcoord}. The shader is correct; the driver is stricter than the
	 * specification.</p>
	 *
	 * <p>Only outputs that <em>every</em> assignment leaves conditional are initialised, so a shader
	 * that plainly writes all of its outputs is untouched. And only declarations at file scope: one
	 * inside an {@code #ifdef} does not exist when that branch is not taken, and initialising it
	 * would name something undeclared — the same trap that put the output declarations inside a
	 * conditional and broke twenty-three passes.</p>
	 */
	private static String initialiseConditionalVertexOutputs(String source) {
		Matcher main = MAIN_BODY.matcher(source);
		if (!main.find()) {
			return source;
		}

		String[] lines = source.split("\n", -1);
		Map<String, String> declared = new LinkedHashMap<>();
		int depth = 0;
		for (String line : lines) {
			Matcher declaration = VERTEX_OUTPUT_DECL.matcher(line);
			if (depth == 0 && declaration.find() && ZEROABLE_TYPES.contains(declaration.group(1))) {
				declared.put(declaration.group(2), declaration.group(1));
			}

			String trimmed = line.trim();
			if (trimmed.startsWith("#")) {
				String directive = trimmed.substring(1).trim();
				if (directive.startsWith("if")) {
					depth++;
				} else if (directive.startsWith("endif")) {
					depth = Math.max(0, depth - 1);
				}
			}
		}
		if (declared.isEmpty()) {
			return source;
		}

		for (String name : assignedUnconditionally(lines, declared.keySet())) {
			declared.remove(name);
		}
		if (declared.isEmpty()) {
			return source;
		}

		StringBuilder initialisers = new StringBuilder("\n\t// [tapetum] outputs this shader may leave "
			+ "unwritten; Apple's linker rejects those\n");
		declared.forEach((name, type) ->
			initialisers.append('\t').append(name).append(" = ").append(type).append("(0);\n"));

		return source.substring(0, main.end()) + initialisers + source.substring(main.end());
	}

	/** Which of {@code names} the shader assigns outside every conditional. */
	private static Set<String> assignedUnconditionally(String[] lines, Set<String> names) {
		Map<String, Pattern> assignments = new LinkedHashMap<>();
		for (String name : names) {
			assignments.put(name, Pattern.compile(
				"\\b" + Pattern.quote(name) + "\\b\\s*(?:\\.[xyzwrgbastpq]+)?\\s*=(?!=)"));
		}

		Set<String> unconditional = new LinkedHashSet<>();
		int depth = 0;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#")) {
				String directive = trimmed.substring(1).trim();
				if (directive.startsWith("if")) {
					depth++;
				} else if (directive.startsWith("endif")) {
					depth = Math.max(0, depth - 1);
				}
				continue;
			}
			if (depth != 0 || VERTEX_OUTPUT_DECL.matcher(line).find()) {
				continue;
			}
			assignments.forEach((name, pattern) -> {
				if (pattern.matcher(line).find()) {
					unconditional.add(name);
				}
			});
		}
		return unconditional;
	}

	private static String renameFogFragCoord(String source) {
		if (!FOG_FRAG_COORD.matcher(source).find()) {
			return source;
		}
		return FOG_FRAG_COORD.matcher(source).replaceAll(FOG_FRAG_COORD_REPLACEMENT);
	}

	private static String renameShadowedTextureSampler(String source) {
		if (!PACK_TEXTURE_SAMPLER.matcher(source).find()) {
			return source;
		}
		return TEXTURE_AS_VARIABLE.matcher(source).replaceAll("tapetum_texture");
	}

	private static String rewriteLegacyTextureCalls(String line) {
		line = LEGACY_SHADOW_ARB_CALL.matcher(line).replaceAll("$1");
		Matcher matcher = LEGACY_TEXTURE_CALL.matcher(line);
		StringBuilder result = new StringBuilder();

		while (matcher.find()) {
			String suffix = matcher.group(1) == null ? "" : matcher.group(1);
			matcher.appendReplacement(result, Matcher.quoteReplacement("texture" + suffix));
		}
		matcher.appendTail(result);

		return result.toString();
	}

	/**
	 * Ensures a high-enough {@code #version} (it must be the first non-comment line) and declares the
	 * fragment outputs that replaced {@code gl_FragColor}/{@code gl_FragData}.
	 *
	 * <p>Every {@code #version} after the first is commented out. Include expansion routinely
	 * produces them — a library file that carries its own {@code #version} is legal on its own, but
	 * once inlined it lands mid-file, where GLSL rejects it outright ("'#version' : must occur
	 * first in shader") and takes the whole program down with it. The highest version seen anywhere
	 * still wins, so a library asking for more than the entry file does is honoured rather than
	 * silently downgraded.</p>
	 */
	private static String applyVersionAndOutputs(String source, Stage stage, ShaderMacros macros) {
		source = renameShadowedTextureSampler(source);
		source = renameFogFragCoord(source);
		if (stage == Stage.VERTEX) {
			source = initialiseConditionalVertexOutputs(source);
		}
		HoistedExtensions hoisted = hoistExtensions(source.split("\n", -1));
		String[] lines = hoisted.lines();

		int versionLineIndex = -1;
		int declaredVersion = -1;
		int highestVersionAnywhere = -1;

		for (int i = 0; i < lines.length; i++) {
			Matcher matcher = VERSION_DIRECTIVE.matcher(lines[i]);
			if (matcher.matches()) {
				int version = Integer.parseInt(matcher.group(1));
				highestVersionAnywhere = Math.max(highestVersionAnywhere, version);

				if (versionLineIndex < 0 && declaredVersion < 0) {
					// Only the first directive keeps its position; the rest are neutralised below.
					versionLineIndex = i;
					declaredVersion = version;
				}
			}
		}

		// A #version is only honoured in place if nothing but blank lines and comments precedes it.
		if (versionLineIndex > 0 && !onlyCommentsAndBlanksBefore(lines, versionLineIndex)) {
			versionLineIndex = -1;
		}

		FragmentOutputs outputs = buildFragmentOutputDeclarations(source, stage);
		int effectiveVersion = Math.max(
			Math.max(highestVersionAnywhere, MINIMUM_VERSION), outputs.requiredVersion());

		StringBuilder out = new StringBuilder(source.length() + 256);

		// The pack's own conditionals are defaulted after the loader's macros, so a name the loader
		// supplies (MC_VERSION and friends) is already defined and will not be defaulted to 0 here.
		DEFAULTED_CONDITIONALS.get().clear();
		String defineBlock = (macros == null ? "" : macros.toDefineBlock())
			+ defaultsForUndefinedConditionals((macros == null ? "" : macros.toDefineBlock()) + source);

		if (versionLineIndex < 0) {
			out.append("#version ").append(effectiveVersion).append('\n');
			out.append(defineBlock);
			out.append(hoisted.block());

			// Even when a fresh #version is prepended, the declarations cannot simply follow it: a
			// pack may carry its own top-level #extension, and GLSL requires every one of those to
			// precede any non-preprocessor token. BSL's deferred pass is exactly this shape, and
			// emitting the output declaration here produced "#extension must always be before any
			// non-preprocessor tokens". When the source declares no extension at all there is nothing
			// to order against, so the declarations stay up front - which also keeps them out of a
			// leading block comment.
			int extensionIndex = lastTopLevelExtensionOrNone(lines, -1);
			if (extensionIndex < 0) {
				out.append(compatibilityPreamble(source, stage));
				out.append(outputs.declarations());
				out.append(neutraliseStrayVersionDirectives(lines, -1));
				return out.toString();
			}

			boolean emitted = false;
			for (int i = 0; i < lines.length; i++) {
				if (VERSION_DIRECTIVE.matcher(lines[i]).matches()) {
					out.append("// [tapetum] superseded #version from an include: ")
						.append(lines[i].trim()).append('\n');
				} else {
					out.append(undefBeforeRedefinition(lines[i])).append('\n');
				}
				if (!emitted && i >= extensionIndex) {
					out.append(compatibilityPreamble(source, stage));
					out.append(outputs.declarations());
					emitted = true;
				}
			}
			if (!emitted) {
				out.append(compatibilityPreamble(source, stage));
				out.append(outputs.declarations());
			}
			if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
				out.setLength(out.length() - 1);
			}
			return out.toString();
		}

		// Where the injected declarations may legally go. Two constraints pull in opposite
		// directions: GLSL requires #extension to precede any non-preprocessor token, so the
		// declarations must come after the last one; but they must also stay at file scope, because a
		// declaration emitted inside an #ifdef simply does not exist when that branch is not taken -
		// which is exactly what happened when this inserted at "the first line of real code" and
		// produced "tapetum_FragData0 : undeclared identifier" across 23 passes.
		int declarationIndex = lastTopLevelExtension(lines, versionLineIndex);
		boolean outputsEmitted = outputs.declarations().isEmpty() && compatibilityPreamble(source, stage).isEmpty();

		for (int i = 0; i < lines.length; i++) {
			if (i == versionLineIndex) {
				out.append("#version ").append(effectiveVersion).append('\n');
				// The macro block must precede everything the pack wrote: its very first lines are
				// often #if MC_VERSION guards, which need MC_VERSION already defined. Being
				// preprocessor directives themselves, they may sit ahead of #extension safely.
				out.append(defineBlock);
				out.append(hoisted.block());
			} else if (VERSION_DIRECTIVE.matcher(lines[i]).matches()) {
				out.append("// [tapetum] superseded #version from an include: ").append(lines[i].trim()).append('\n');
			} else {
				out.append(undefBeforeRedefinition(lines[i])).append('\n');
			}

			if (!outputsEmitted && i >= declarationIndex) {
				out.append(compatibilityPreamble(source, stage));
				out.append(outputs.declarations());
				outputsEmitted = true;
			}
		}

		if (!outputsEmitted) {
			// A source with no code at all after its directives still needs its outputs declared.
			out.append(compatibilityPreamble(source, stage));
			out.append(outputs.declarations());
		}

		if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
			out.setLength(out.length() - 1);
		}
		return out.toString();
	}

	/**
	 * Gives a value of 0 to names the source evaluates arithmetically but never defines.
	 *
	 * <p>The C preprocessor treats an undefined name in {@code #if} as 0, and packs rely on it:
	 * Complementary writes {@code #if WORLD_SPACE_REF_MODE == 1} for an option its settings only
	 * define when enabled. Apple's OpenGL does not follow that rule — it rejects the directive outright
	 * with "syntax error: incorrect preprocessor directive" — so the value has to be supplied
	 * explicitly. This is the same failure that {@code MC_VERSION} hit, generalised: enumerating names
	 * one at a time fixes one pack, whereas this covers every option any pack gates on.</p>
	 *
	 * <p>Two exclusions keep it from changing meaning. A name the source itself {@code #define}s is
	 * left alone, so nothing is shadowed. And a name whose <em>existence</em> is tested — through
	 * {@code #ifdef}, {@code #ifndef} or {@code defined} — is never given a value, because defining it
	 * would flip that test from false to true and silently enable code the pack meant to skip.</p>
	 */
	private static String defaultsForUndefinedConditionals(String source) {
		Set<String> unconditionallyDefined = new TreeSet<>();
		Set<String> conditionallyDefined = new TreeSet<>();
		Set<String> existenceTested = new TreeSet<>();
		Set<String> arithmetic = new TreeSet<>();

		int depth = 0;
		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();

			Matcher define = DEFINE_DIRECTIVE.matcher(line);
			if (define.find()) {
				// Depth matters. A #define nested inside a conditional only happens if that branch is
				// taken, so it cannot be assumed. Complementary defines WORLD_SPACE_REF_MODE two levels
				// deep, inside a block guarded by "!defined MC_OS_MAC" - on a Mac the define never
				// runs, and "#if WORLD_SPACE_REF_MODE == 1" later hits an undefined name. Treating any
				// textual #define as proof of definition is exactly what missed that.
				if (depth == 0) {
					unconditionallyDefined.add(define.group(1));
				} else {
					conditionallyDefined.add(define.group(1));
				}
			}

			Matcher existence = EXISTENCE_TEST.matcher(line);
			while (existence.find()) {
				existenceTested.add(existence.group(1) != null ? existence.group(1) : existence.group(2));
			}

			Matcher condition = ARITHMETIC_CONDITION.matcher(line);
			if (condition.find()) {
				// Strip the trailing annotation first. Packs write "#if QUALITY == 2 // Low Medium High",
				// and reading those words as operands defines them - including the keyword "in", which
				// rewrites every varying declaration in the file into "noperspective 0 vec2 texCoord".
				String expression = condition.group(1)
					.replaceAll("//.*$", "")
					.replaceAll("/\\*.*?\\*/", " ");
				Matcher identifier = IDENTIFIER.matcher(expression);
				while (identifier.find()) {
					arithmetic.add(identifier.group());
				}
			}

			if (trimmed.startsWith("#")) {
				String directive = trimmed.substring(1).trim();
				if (directive.startsWith("if")) {
					depth++;
				} else if (directive.startsWith("endif")) {
					depth = Math.max(0, depth - 1);
				}
			}
		}

		arithmetic.remove("defined");

		StringBuilder defaults = new StringBuilder();
		for (String name : arithmetic) {
			// A keyword is never an option name; defining one rewrites real declarations into nonsense.
			if (GLSL_KEYWORDS.contains(name)
					|| unconditionallyDefined.contains(name)
					|| existenceTested.contains(name)) {
				continue;
			}
			defaults.append("#define ").append(name).append(" 0\n");
			if (conditionallyDefined.contains(name)) {
				// The pack may still define this in a branch that is taken, and that value must win.
				// Redefining a macro is a diagnostic in its own right, so the later #define is paired
				// with an #undef by undefConditionalRedefinitions().
				DEFAULTED_CONDITIONALS.get().add(name);
			}
		}
		return defaults.toString();
	}

	/**
	 * Names given a default above that the pack also defines conditionally, collected so the pack's
	 * own {@code #define} can be preceded by an {@code #undef}.
	 *
	 * <p>Held per call rather than as shared state: patching is not synchronised, and a leaked name
	 * would make one pack's defaults alter another's source.</p>
	 */
	private static final ThreadLocal<Set<String>> DEFAULTED_CONDITIONALS =
		ThreadLocal.withInitial(TreeSet::new);

	/**
	 * Precedes a conditional {@code #define} of a defaulted name with {@code #undef}, so the pack's
	 * value replaces the default cleanly instead of being a redefinition.
	 */
	private static String undefBeforeRedefinition(String line) {
		Matcher define = DEFINE_DIRECTIVE.matcher(line);
		if (!define.find() || !DEFAULTED_CONDITIONALS.get().contains(define.group(1))) {
			return line;
		}
		String indent = line.substring(0, line.length() - line.stripLeading().length());
		return indent + "#undef " + define.group(1) + "\n" + line;
	}

	/**
	 * Declarations that stand in for fixed-function features core profile removed, emitted only when
	 * the source actually uses them.
	 *
	 * <p>{@code shadow2D} is provided as a real function rather than rewritten textually: the legacy
	 * builtin returned a {@code vec4} whose components all held the comparison result, and packs
	 * exploit that by swizzling ({@code shadow2D(...).z}, {@code .x}). A regex would have to balance
	 * the call's parentheses to wrap it; an overload lets the compiler do that work and keeps the
	 * swizzle valid.</p>
	 *
	 * <p>{@code gl_Fog} gets a struct instead, since its uses are field accesses rather than calls.
	 * The values are left at zero for now — nothing binds them yet — which is well-defined rather than
	 * correct: a pack reading {@code gl_Fog.start} sees 0 and renders as though fog began at the
	 * camera. Wrong, but deterministic, and it compiles.</p>
	 */
	private static String compatibilityPreamble(String source, Stage stage) {
		StringBuilder preamble = new StringBuilder();

		if (source.contains(FOG_FRAG_COORD_REPLACEMENT)) {
			preamble.append(stage == Stage.VERTEX ? "out float " : "in float ")
				.append(FOG_FRAG_COORD_REPLACEMENT).append(";\n");
		}

		if (FOG_PARAMETERS_USE.matcher(source).find()) {
			preamble.append("struct tapetum_FogParameters { vec4 color; float density; float start; ")
				.append("float end; float scale; };\n")
				.append("uniform tapetum_FogParameters ").append(FOG_REPLACEMENT).append(";\n");
		}

		if (SHADOW_SAMPLE_CALL.matcher(source).find()) {
			preamble.append("vec4 shadow2D(sampler2DShadow tapetum_s, vec3 tapetum_c) ")
				.append("{ return vec4(texture(tapetum_s, tapetum_c)); }\n")
				.append("vec4 shadow2DLod(sampler2DShadow tapetum_s, vec3 tapetum_c, float tapetum_l) ")
				.append("{ return vec4(textureLod(tapetum_s, tapetum_c, tapetum_l)); }\n")
				.append("vec4 shadow2DGrad(sampler2DShadow tapetum_s, vec3 tapetum_c, vec2 tapetum_x, ")
				.append("vec2 tapetum_y) { return vec4(textureGrad(tapetum_s, tapetum_c, tapetum_x, ")
				.append("tapetum_y)); }\n");
		}

		return preamble.toString();
	}

	/**
	 * The line after which injected declarations may safely go: the last {@code #extension} that sits
	 * at file scope, or the {@code #version} line when there is none.
	 *
	 * <p>Nesting is tracked so an {@code #extension} inside a conditional is not chosen — placing the
	 * declarations there would put them inside that conditional too, and they would vanish whenever
	 * the branch was not taken.</p>
	 */
	/**
	 * Whether only blank lines and comments precede {@code index}, tracking {@code /*} ... {@code *}{@code /}
	 * across lines.
	 *
	 * <p>An earlier version accepted a line only if it started with {@code //}, {@code /*} or {@code *},
	 * which quietly failed on the commonest header shape there is - a block comment whose inner lines
	 * start with plain prose. BSL opens exactly that way, so its {@code #version} was judged not to
	 * lead the file and the whole generated header was prepended ahead of the pack's
	 * {@code #extension}, which GLSL rejects.</p>
	 */
	private static final Pattern EXTENSION_DIRECTIVE = Pattern.compile(
		"^#\\s*extension\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(require|enable|warn|disable)\\s*$");

	/** The lines with every {@code #extension} commented out, plus the block to emit in the header. */
	private record HoistedExtensions(String block, String[] lines) {
	}

	/** Orders extension behaviours so the strongest request for a given extension wins. */
	private static int behaviourRank(String behaviour) {
		return switch (behaviour) {
			case "require" -> 3;
			case "enable" -> 2;
			case "warn" -> 1;
			default -> 0;
		};
	}

	/**
	 * Lifts every {@code #extension} to the top of the file, where GLSL requires it.
	 *
	 * <p>Packs put these in library files: Mellow declares
	 * {@code GL_ARB_shader_image_load_store} inside {@code /lib/all_the_uniforms.glsl}, Complementary
	 * declares {@code GL_ARB_shader_storage_buffer_object} inside its voxelization SSBO headers. Once
	 * {@code #include} is expanded those land thousands of lines into the source, long past the first
	 * real declaration, and the driver rejects the file outright. Nineteen of the forty-nine passes
	 * across the five packs surveyed here are in that shape, so placing the loader's own declarations
	 * carefully is not enough — the pack's directives have to move.</p>
	 *
	 * <p>A directive nested inside a conditional has {@code require} softened to {@code enable}.
	 * Hoisting makes it unconditional, and a hard {@code require} for an extension the driver lacks
	 * fails the compile even when the guarded code that needed it was never included — Mellow's
	 * {@code GL_ARB_shader_image_load_store : require} sits behind {@code #ifdef COLORED_LIGHTS} and
	 * would otherwise break every build on a 4.1 context. {@code enable} degrades to a warning
	 * instead, and the feature is unreferenced anyway when its branch is off. A top-level
	 * {@code require} is left alone: the pack means it for every configuration.</p>
	 */
	private static HoistedExtensions hoistExtensions(String[] lines) {
		Map<String, String> requested = new LinkedHashMap<>();
		String[] rewritten = lines.clone();
		int depth = 0;

		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (!trimmed.startsWith("#")) {
				continue;
			}

			String directive = trimmed.substring(1).trim();
			if (directive.startsWith("if")) {
				depth++;
				continue;
			}
			if (directive.startsWith("endif")) {
				depth = Math.max(0, depth - 1);
				continue;
			}

			Matcher matcher = EXTENSION_DIRECTIVE.matcher(trimmed);
			if (!matcher.matches()) {
				continue;
			}

			String name = matcher.group(1);
			String written = matcher.group(2);
			// An unguarded "require" is the pack saying it needs the extension in every configuration,
			// and softening one would hide a real incompatibility behind a warning. The exception is
			// an extension this patcher has already replaced: every function GL_ARB_shader_texture_lod
			// provides is rewritten to its core equivalent, so requiring it on a driver that lacks it
			// - Apple's OpenGL 4.1 - would fail over functions the shader no longer calls.
			boolean softenable = depth > 0 || REPLACED_EXTENSIONS.contains(name);
			String behaviour = softenable && written.equals("require") ? "enable" : written;
			requested.merge(name, behaviour,
				(a, b) -> behaviourRank(a) >= behaviourRank(b) ? a : b);
			rewritten[i] = "// [tapetum] hoisted #extension " + name + " : " + written;
		}

		StringBuilder block = new StringBuilder();
		for (Map.Entry<String, String> entry : requested.entrySet()) {
			block.append("#extension ").append(entry.getKey()).append(" : ")
				.append(entry.getValue()).append('\n');
		}
		return new HoistedExtensions(block.toString(), rewritten);
	}

	private static boolean onlyCommentsAndBlanksBefore(String[] lines, int index) {
		boolean inBlockComment = false;

		for (int i = 0; i < index; i++) {
			String line = lines[i];
			int pos = 0;
			while (pos < line.length()) {
				if (inBlockComment) {
					int end = line.indexOf("*/", pos);
					if (end < 0) {
						pos = line.length();
					} else {
						inBlockComment = false;
						pos = end + 2;
					}
					continue;
				}
				if (Character.isWhitespace(line.charAt(pos))) {
					pos++;
				} else if (line.startsWith("//", pos)) {
					pos = line.length();
				} else if (line.startsWith("/*", pos)) {
					inBlockComment = true;
					pos += 2;
				} else {
					return false;
				}
			}
		}

		// Still inside a comment means the directive itself was commented out, so it cannot lead.
		return !inBlockComment;
	}

	/** The last top-level {@code #extension} after {@code from}, or -1 when the source has none. */
	private static int lastTopLevelExtensionOrNone(String[] lines, int from) {
		int chosen = -1;
		int depth = 0;

		for (int i = Math.max(from, -1) + 1; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (!trimmed.startsWith("#")) {
				continue;
			}
			String directive = trimmed.substring(1).trim();
			if (directive.startsWith("if")) {
				depth++;
			} else if (directive.startsWith("endif")) {
				depth = Math.max(0, depth - 1);
			} else if (depth == 0 && directive.startsWith("extension")) {
				chosen = i;
			}
		}

		return chosen;
	}

	private static int lastTopLevelExtension(String[] lines, int versionLineIndex) {
		int chosen = Math.max(versionLineIndex, 0);
		int depth = 0;

		for (int i = Math.max(versionLineIndex, 0) + 1; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (!trimmed.startsWith("#")) {
				continue;
			}

			String directive = trimmed.substring(1).trim();
			if (directive.startsWith("if")) {
				depth++;
			} else if (directive.startsWith("endif")) {
				depth = Math.max(0, depth - 1);
			} else if (depth == 0 && directive.startsWith("extension")) {
				chosen = i;
			}
		}

		return chosen;
	}

	/**
	 * Reassembles {@code lines}, commenting out every {@code #version} except the one at
	 * {@code keepIndex} (pass -1 to comment out all of them, when a fresh directive was prepended).
	 */
	private static String neutraliseStrayVersionDirectives(String[] lines, int keepIndex) {
		StringBuilder out = new StringBuilder();

		for (int i = 0; i < lines.length; i++) {
			if (i != keepIndex && VERSION_DIRECTIVE.matcher(lines[i]).matches()) {
				out.append("// [tapetum] superseded #version from an include: ").append(lines[i].trim());
			} else {
				// Pairing the #undef here too, not only on the main path: a defaulted option the pack
				// redefines in a taken branch is a redefinition with a different substitution, which
				// is a hard error, and which emission path the source happened to take is no reason
				// for it to appear or not.
				out.append(undefBeforeRedefinition(lines[i]));
			}
			if (i < lines.length - 1) {
				out.append('\n');
			}
		}

		return out.toString();
	}

	/**
	 * The fragment output declarations to inject, and the {@code #version} they need — an explicit
	 * {@code layout(location = ...)} on a fragment output is only legal from GLSL 330 onwards, so a
	 * shader needing one has to be raised past {@link #MINIMUM_VERSION}.
	 */
	private record FragmentOutputs(String declarations, int requiredVersion) {
	}

	/**
	 * Declares an {@code out vec4} for each legacy fragment output the (already-rewritten) source
	 * actually uses, so a shader that never wrote {@code gl_FragColor} gains no stray output.
	 *
	 * <p>The single-output {@code gl_FragColor} case is emitted without a {@code layout} qualifier:
	 * a lone fragment output defaults to location 0, which is exactly what {@code gl_FragColor} meant,
	 * and omitting it keeps such shaders compiling at 150 instead of forcing them to 330 for no
	 * gain. Multi-target {@code gl_FragData[N]} genuinely needs explicit locations, so that case does
	 * raise the version.</p>
	 */
	private static FragmentOutputs buildFragmentOutputDeclarations(String rewrittenSource, Stage stage) {
		if (stage != Stage.FRAGMENT) {
			return new FragmentOutputs("", MINIMUM_VERSION);
		}

		StringBuilder declarations = new StringBuilder();

		Matcher fragData = Pattern.compile("\\b" + FRAG_DATA_OUT_PREFIX + "(\\d+)\\b").matcher(rewrittenSource);
		Set<Integer> indices = new TreeSet<>();
		while (fragData.find()) {
			indices.add(Integer.parseInt(fragData.group(1)));
		}

		boolean usesFragColor = Pattern.compile("\\b" + FRAG_COLOR_OUT + "\\b").matcher(rewrittenSource).find();

		if (usesFragColor && indices.isEmpty()) {
			declarations.append("out vec4 ").append(FRAG_COLOR_OUT).append(";\n");
			return new FragmentOutputs(declarations.toString(), MINIMUM_VERSION);
		}

		// Mixed or multi-target: every output needs an explicit, non-colliding location.
		if (usesFragColor) {
			declarations.append("layout(location = 0) out vec4 ").append(FRAG_COLOR_OUT).append(";\n");
		}
		for (int index : indices) {
			if (usesFragColor && index == 0) {
				// gl_FragColor and gl_FragData[0] are the same output; declaring both would collide.
				continue;
			}
			declarations.append("layout(location = ").append(index).append(") out vec4 ")
				.append(FRAG_DATA_OUT_PREFIX).append(index).append(";\n");
		}

		int requiredVersion = declarations.indexOf("layout(") >= 0 ? LAYOUT_QUALIFIER_VERSION : MINIMUM_VERSION;
		return new FragmentOutputs(declarations.toString(), requiredVersion);
	}
}
