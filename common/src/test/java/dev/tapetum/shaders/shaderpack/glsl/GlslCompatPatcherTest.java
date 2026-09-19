package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the rewrites {@link GlslCompatPatcher} performs, using the constructs real shaderpacks
 * were actually observed to contain — Complementary's {@code final.fsh} chain (which needs
 * {@code varying}/{@code texture2DLod}/{@code gl_FragColor}), Bliss ({@code #version 120} +
 * {@code gl_FragColor}), and AstralCore ({@code gl_FragData[0]}, uncommented OptiFine format
 * constants) — rather than invented syntax.
 */
class GlslCompatPatcherTest {

	@Test
	void rewritesVaryingToInForFragmentStage() {
		String patched = GlslCompatPatcher.patch("varying vec2 texCoord;", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("in vec2 texCoord;"), patched);
		assertFalse(patched.contains("varying"), patched);
	}

	@Test
	void rewritesVaryingToOutForVertexStage() {
		// The same declaration means the opposite direction in a vertex shader - getting this backwards
		// would compile and then silently pass nothing between stages.
		String patched = GlslCompatPatcher.patch("varying vec2 texCoord;", GlslCompatPatcher.Stage.VERTEX);

		assertTrue(patched.contains("out vec2 texCoord;"), patched);
		assertFalse(patched.contains("varying"), patched);
	}

	@Test
	void rewritesAttributeToIn() {
		String patched = GlslCompatPatcher.patch("attribute vec3 mc_Entity;", GlslCompatPatcher.Stage.VERTEX);

		assertTrue(patched.contains("in vec3 mc_Entity;"), patched);
	}

	@Test
	void rewritesLegacyTextureCallsIncludingSuffixedVariants() {
		String source = """
			vec4 a = texture2D(colortex0, uv);
			vec4 b = texture2DLod(colortex1, uv, 0);
			vec4 c = textureCube(skybox, dir);
			vec4 d = texture3D(volume, p);
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("texture(colortex0, uv)"), patched);
		// The Lod suffix has to survive: rewriting this to plain texture() would change the semantics.
		assertTrue(patched.contains("textureLod(colortex1, uv, 0)"), patched);
		assertTrue(patched.contains("texture(skybox, dir)"), patched);
		assertTrue(patched.contains("texture(volume, p)"), patched);
	}

	@Test
	void replacesGlFragColorWithDeclaredOutputWithoutLayoutQualifier() {
		String patched = GlslCompatPatcher.patch(
			"void main() { gl_FragColor = vec4(1.0); }", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("out vec4 tapetum_FragColor;"), patched);
		assertFalse(patched.contains("gl_FragColor"), patched);
		// A lone output defaults to location 0, so no layout qualifier is needed - and emitting one
		// would force the shader up to #version 330 for nothing (glslang rejects layout at 150).
		assertFalse(patched.contains("layout("), patched);
		assertTrue(patched.contains("#version 150"), patched);
	}

	@Test
	void replacesGlFragDataWithExplicitlyLocatedOutputs() {
		String source = """
			void main() {
				gl_FragData[0] = vec4(1.0);
				gl_FragData[2] = vec4(0.5);
			}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("layout(location = 0) out vec4 tapetum_FragData0;"), patched);
		assertTrue(patched.contains("layout(location = 2) out vec4 tapetum_FragData2;"), patched);
		assertFalse(patched.contains("gl_FragData"), patched);
		// Explicit output locations are only legal from 330 on.
		assertTrue(patched.contains("#version 330"), patched);
	}

	@Test
	void declaresNoOutputWhenShaderWritesNeither() {
		String patched = GlslCompatPatcher.patch(
			"void main() { float unused = 1.0; }", GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("tapetum_Frag"), patched);
	}

	@Test
	void raisesLegacyVersionDirectiveInPlace() {
		// Bliss ships "#version 120"; the rewritten source needs at least 150.
		String patched = GlslCompatPatcher.patch(
			"#version 120\nvarying vec2 uv;\n", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("#version 150"), patched);
		assertFalse(patched.contains("#version 120"), patched);
		assertEquals(1, patched.lines().filter(line -> line.startsWith("#version")).count(), patched);
	}

	@Test
	void normalisesWindowsLineEndings() {
		// Complementary Unbound ships CRLF throughout; a stray \r left on every line makes a
		// backslash-before-newline ambiguously a continuation, which compilers disagree about.
		String patched = GlslCompatPatcher.patch(
			"#version 130\r\nvarying vec2 uv;\r\n", GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("\r"), patched.replace("\r", "<CR>"));
		assertTrue(patched.contains("in vec2 uv;"), patched);
	}

	@Test
	void splicesBackslashContinuedLinesIntoOneLogicalLine() {
		// Complementary's multi-line #define: without splicing, the first line is treated as a
		// directive and the continuation lines as ordinary code - two different rewrite paths for
		// what is actually a single #define.
		String source = """
			#define printString(string) {                 \\
			    uint[] characters = uint[] string;        \\
			    doThing(characters);                      \\
			}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("\\\n"), patched);
		// The whole macro must survive as one line, body intact.
		String defineLine = patched.lines()
			.filter(line -> line.contains("#define printString"))
			.findFirst()
			.orElseThrow();
		assertTrue(defineLine.contains("uint[] characters"), defineLine);
		assertTrue(defineLine.contains("doThing(characters);"), defineLine);
	}

	@Test
	void removesStrayContinuationInsideBlockComment() {
		// Complementary's ASCII-art banner has a line ending in a backslash inside a block comment.
		// GLSL 150 rejects that outright as an unsupported line continuation.
		String source = """
			/*-------------------------------
			| (__| |_| \\__ \\ | || (_) | \\__ \\
			 \\___|\\___/|___/ |_| \\___/
			-------------------------------*/
			void main() { gl_FragColor = vec4(1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("\\\n"), patched);
		// The comment must still close, so the code after it is still code.
		assertTrue(patched.contains("void main()"), patched);
	}

	@Test
	void neutralisesStrayVersionDirectivesFromIncludes() {
		// Include expansion routinely inlines a library that carries its own #version. Legal on its
		// own, fatal once it lands mid-file: "'#version' : must occur first in shader".
		String source = """
			#version 150
			float a = 1.0;
			#version 130
			float b = 2.0;
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertEquals(1, patched.lines().filter(line -> line.trim().startsWith("#version")).count(), patched);
		assertTrue(patched.contains("superseded #version"), patched);
		// Neutralised, not deleted: the surrounding code must keep its meaning.
		assertTrue(patched.contains("float b = 2.0;"), patched);
	}

	@Test
	void honoursHighestVersionRequestedByAnyIncludedFile() {
		// A library needing more than the entry file asked for must not be silently downgraded.
		String source = "#version 150\nfloat a = 1.0;\n#version 430\n";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("#version 430"), patched);
		assertEquals(1, patched.lines().filter(line -> line.trim().startsWith("#version")).count(), patched);
	}

	@Test
	void keepsVersionAlreadyAboveTheMinimum() {
		String patched = GlslCompatPatcher.patch(
			"#version 430\nvoid main() {}\n", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("#version 430"), patched);
	}

	@Test
	void addsVersionDirectiveWhenPackOmitsIt() {
		String patched = GlslCompatPatcher.patch("void main() {}", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.startsWith("#version 150"), patched);
	}

	@Test
	void versionDirectiveStaysFirstWhenPrecededByComments() {
		// GLSL requires #version to be the first non-comment token; a leading licence header is
		// extremely common in real packs.
		String source = """
			// Complementary Shaders by EminGT
			/* block comment */
			#version 130
			void main() { gl_FragColor = vec4(1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);
		String firstDirective = patched.lines()
			.filter(line -> line.trim().startsWith("#version"))
			.findFirst()
			.orElseThrow();

		assertEquals("#version 150", firstDirective.trim(), patched);
		// The output declaration must come after #version, never before it.
		assertTrue(patched.indexOf("#version") < patched.indexOf("out vec4 tapetum_FragColor"), patched);
	}

	@Test
	void stripsOptiFineFormatPseudoConstants() {
		// AstralCore ships these uncommented; R11F_G11F_B10F is not a GLSL identifier, so leaving
		// them in fails compilation outright.
		String source = """
			const int colortex0Format = R11F_G11F_B10F;
			const int gaux2Format = RGBA8;
			void main() { gl_FragColor = vec4(1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		// The stripped text is deliberately kept in a trailing comment for traceability, so what
		// matters is that no *compiled* line still declares them.
		assertTrue(activeCode(patched).isEmpty() || !activeCode(patched).contains("colortex0Format"), patched);
		assertFalse(activeCode(patched).contains("R11F_G11F_B10F"), patched);
		assertFalse(activeCode(patched).contains("RGBA8"), patched);
		assertTrue(patched.contains("[tapetum] stripped OptiFine format constant"), patched);
	}

	/** The patched source with comment-only lines removed, i.e. what the GLSL compiler actually sees. */
	private static String activeCode(String patched) {
		return patched.lines()
			.filter(line -> !line.trim().startsWith("//"))
			.reduce("", (a, b) -> a + "\n" + b);
	}

	@Test
	void keepsUnrelatedConstDeclarations() {
		// Only the *Format configuration constants are OptiFine's; ordinary tunables are real code.
		String source = "const int shadowMapResolution = 2048;\nvoid main() {}\n";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("const int shadowMapResolution = 2048;"), patched);
	}

	@Test
	void leavesPreprocessorConditionsUntouched() {
		// Rewriting inside an #if is how a regex pass corrupts source it does not parse - the macro
		// name here merely contains "varying" and must survive intact.
		String source = "#ifdef USE_varying_PATH\nvarying vec2 uv;\n#endif\n";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("#ifdef USE_varying_PATH"), patched);
		assertTrue(patched.contains("in vec2 uv;"), patched);
	}

	@Test
	void rewritesTextureCallsInsideDefineBodyButNotItsName() {
		String source = "#define SAMPLE_texture2D(s, c) texture2D(s, c)\n";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		// The macro's own name is an identifier, not a call - renaming it would break every use site.
		assertTrue(patched.contains("#define SAMPLE_texture2D(s, c)"), patched);
		assertTrue(patched.contains("texture(s, c)"), patched);
	}

	@Test
	void doesNotRewriteIdentifiersMerelyContainingKeywords() {
		String source = "float myvaryingValue = 1.0;\nvec4 t = mytexture2Dhelper(x);\n";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("myvaryingValue"), patched);
		assertTrue(patched.contains("mytexture2Dhelper"), patched);
	}

	@Test
	void putsDeclarationsAfterExtensionDirectives() {
		// GLSL requires #extension to precede any non-preprocessor token. Emitting the fragment output
		// straight after #version put it ahead of BSL's and MakeUp's
		// "#extension GL_ARB_shader_texture_lod : enable" and failed their whole chain to compile.
		String source = """
			#version 120
			#extension GL_ARB_shader_texture_lod : enable
			void main() { gl_FragColor = vec4(1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.indexOf("#extension") < patched.indexOf("out vec4 tapetum_FragColor"),
			patched);
	}

	@Test
	void keepsDeclarationsAtFileScopeNotInsideAConditional() {
		// The first line of real code is often inside an #ifdef. A declaration emitted there does not
		// exist when that branch is not taken - which produced "tapetum_FragData0 : undeclared
		// identifier" across 23 passes of four different packs.
		String source = """
			#version 330
			#extension GL_ARB_shader_texture_lod : enable
			#ifdef SOME_FEATURE
			float insideBranch = 1.0;
			#endif
			void main() { gl_FragData[0] = vec4(1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.indexOf("out vec4 tapetum_FragData0") < patched.indexOf("#ifdef SOME_FEATURE"),
			"declaration must precede the conditional, not sit inside it:\n" + patched);
	}

	@Test
	void ignoresAnExtensionNestedInsideAConditional() {
		// Anchoring on a nested #extension would drag the declarations into that branch with it.
		String source = """
			#version 330
			#extension GL_ARB_shader_texture_lod : enable
			#ifdef X
			#extension GL_ARB_gpu_shader5 : enable
			#endif
			void main() { gl_FragColor = vec4(1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.indexOf("out vec4 tapetum_FragColor") < patched.indexOf("#ifdef X"), patched);
	}

	@Test
	void declaresAFogStructWhenThePackReadsTheFixedFunctionOne() {
		// Complementary reads gl_Fog.start and gl_Fog.scale. Core removed it, and GLSL reserves every
		// gl_ name, so the uses are renamed onto a struct this patcher declares.
		String patched = GlslCompatPatcher.patch(
			"#version 330\nvoid main() { float f = gl_Fog.start * gl_Fog.scale; }",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("gl_Fog"), patched);
		assertTrue(patched.contains("struct tapetum_FogParameters"), patched);
		assertTrue(patched.contains("tapetum_Fog.start"), patched);
	}

	@Test
	void declaresShadowSamplingStandInsOnlyWhenUsed() {
		// The legacy builtin returned a vec4 whose components all held the comparison result, and packs
		// swizzle it (shadow2D(...).z). An overload keeps that valid; a regex would have to balance the
		// call's parentheses to wrap it.
		String uses = GlslCompatPatcher.patch(
			"#version 330\nuniform sampler2DShadow s;\nvoid main() { float v = shadow2D(s, vec3(0.5)).z; }",
			GlslCompatPatcher.Stage.FRAGMENT);
		assertTrue(uses.contains("vec4 shadow2D(sampler2DShadow"), uses);

		String doesNot = GlslCompatPatcher.patch(
			"#version 330\nvoid main() { gl_FragColor = vec4(1.0); }", GlslCompatPatcher.Stage.FRAGMENT);
		assertFalse(doesNot.contains("shadow2D"), doesNot);
	}

	@Test
	void defaultsUndefinedArithmeticConditionalsToZero() {
		// The C preprocessor treats an undefined name in #if as 0 and packs rely on it, but Apple's
		// OpenGL rejects the directive outright - which is how Complementary's
		// "#if WORLD_SPACE_REF_MODE == 1" failed to compile.
		String patched = GlslCompatPatcher.patch(
			"#version 330\n#if SOME_PACK_OPTION == 1\nfloat a = 1.0;\n#endif\nvoid main() {}",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("#define SOME_PACK_OPTION 0"), patched);
	}

	@Test
	void leavesNamesThePackAlreadyDefinesAlone() {
		String patched = GlslCompatPatcher.patch(
			"#version 330\n#define QUALITY 2\n#if QUALITY == 2\nfloat a = 1.0;\n#endif\nvoid main() {}",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("#define QUALITY 0"), patched);
	}

	@Test
	void neverDefinesANameWhoseExistenceIsTested() {
		// Defining it would flip #ifdef from false to true and silently enable code the pack skips.
		String patched = GlslCompatPatcher.patch(
			"#version 330\n#ifdef FEATURE\nfloat a = 1.0;\n#endif\n#if FEATURE > 0\n#endif\nvoid main() {}",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("#define FEATURE 0"), patched);
	}

	@Test
	void ignoresWordsInTrailingCommentsOnConditionLines() {
		// Packs annotate options inline ("#if QUALITY == 2 // Low Medium High"). Treating those words
		// as operands defined them - including the keyword "in", which rewrote every varying
		// declaration in the file into "noperspective 0 vec2 texCoord" and broke four passes.
		String patched = GlslCompatPatcher.patch(
			"#version 330\n#if QUALITY == 2 // Low Medium High and Ultra\n#endif\nvoid main() {}",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("#define Low 0"), patched);
		assertFalse(patched.contains("#define Ultra 0"), patched);
		assertTrue(patched.contains("#define QUALITY 0"), patched);
	}

	@Test
	void neverDefinesAGlslKeyword() {
		String patched = GlslCompatPatcher.patch(
			"#version 330\n#if in > 0\n#endif\nvoid main() {}", GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("#define in 0"), patched);
	}

	@Test
	void stripsTheOptionAnnotationFromAMacroValue() {
		// OptiFine annotates every option this way - "#define WORLD_SPACE_REF_MODE 2 //[1 2]" declares
		// the values its GUI offers. A strict preprocessor carries the comment into the expansion, so
		// "#if WORLD_SPACE_REF_MODE == 1" becomes "#if 2 //[1 2] == 1" and fails with "unexpected
		// tokens following #if". The annotation is read from the pack's own files, never this copy.
		String patched = GlslCompatPatcher.patch(
			"#version 330\n#define WORLD_SPACE_REF_MODE 2 //[1 2]\nvoid main() {}",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("#define WORLD_SPACE_REF_MODE 2"), patched);
		assertFalse(patched.contains("//[1 2]"), patched);
	}

	@Test
	void keepsTheMacroValueItselfWhenStrippingItsComment() {
		String patched = GlslCompatPatcher.patch(
			"#version 330\n#define QUALITY 3 // some note\nvoid main() {}",
			GlslCompatPatcher.Stage.FRAGMENT);

		String define = patched.lines().filter(l -> l.contains("#define QUALITY")).findFirst().orElseThrow();
		assertTrue(define.contains("3"), define);
		assertFalse(define.contains("some note"), define);
	}

	@Test
	void defaultsANameOnlyDefinedInsideAConditional() {
		// Complementary defines WORLD_SPACE_REF_MODE two levels deep, inside a block guarded by
		// "!defined MC_OS_MAC". On a Mac that branch never runs, so the later
		// "#if WORLD_SPACE_REF_MODE == 1" hits an undefined name and Apple's driver rejects the
		// directive. Treating any textual #define as proof of definition is what missed it.
		String source = """
			#version 330
			#ifdef SOMETHING_FALSE
			#define OPTION_MODE 2
			#endif
			#if OPTION_MODE == 1
			#endif
			void main() {}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("#define OPTION_MODE 0"), patched);
	}

	@Test
	void letsThePacksOwnValueWinOverTheDefault() {
		// The default only applies when the pack's branch does not run. When it does, its value must
		// replace the default - and replacing a defined macro is itself a diagnostic, so the pack's
		// #define is paired with an #undef rather than left as a redefinition.
		String source = """
			#version 330
			#ifdef SOMETHING
			#define OPTION_MODE 2
			#endif
			#if OPTION_MODE == 1
			#endif
			void main() {}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.indexOf("#define OPTION_MODE 0") < patched.indexOf("#undef OPTION_MODE"), patched);
		assertTrue(patched.indexOf("#undef OPTION_MODE") < patched.indexOf("#define OPTION_MODE 2"), patched);
	}

	@Test
	void leavesAnUnconditionalDefineAlone() {
		// A name defined at file scope is genuinely defined; defaulting or undefining it would be wrong.
		String source = "#version 330\n#define OPTION_MODE 2\n#if OPTION_MODE == 1\n#endif\nvoid main() {}";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("#define OPTION_MODE 0"), patched);
		assertFalse(patched.contains("#undef OPTION_MODE"), patched);
	}

	/**
	 * The index of the first token GLSL counts as "non-preprocessor", i.e. the point past which no
	 * {@code #extension} may legally appear. Comments and blank lines do not count.
	 */
	private static int firstNonPreprocessorLine(String source) {
		String[] lines = source.split("\n", -1);
		boolean inBlockComment = false;
		for (int i = 0; i < lines.length; i++) {
			String trimmed = lines[i].trim();
			if (inBlockComment) {
				if (trimmed.contains("*/")) {
					inBlockComment = false;
				}
				continue;
			}
			if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
				continue;
			}
			if (trimmed.startsWith("/*")) {
				if (!trimmed.contains("*/")) {
					inBlockComment = true;
				}
				continue;
			}
			return i;
		}
		return Integer.MAX_VALUE;
	}

	/** Asserts the invariant the driver enforces: every #extension precedes all real code. */
	private static void assertExtensionsPrecedeCode(String patched) {
		int firstCode = firstNonPreprocessorLine(patched);
		String[] lines = patched.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith("#extension")) {
				assertTrue(i < firstCode,
					"#extension on line " + (i + 1) + " follows code on line " + (firstCode + 1)
						+ ":\n" + patched);
			}
		}
	}

	@Test
	void keepsExtensionAheadOfInjectedDeclarationsWhenTheHeaderIsABlockComment() {
		// BSL's exact shape. The header is a block comment whose inner lines start with plain prose,
		// so an earlier guard - which accepted a preceding line only if it began with //, /* or * -
		// decided the #version did not lead the file. The whole generated header was then prepended
		// ahead of the pack's #extension, and the driver rejected it outright with "#extension must
		// always be before any non-preprocessor tokens".
		String source = """
			/*
			BSL Shaders v10 Series by Capt Tatsu
			https://capttatsu.com
			*/

			#version 120

			#extension GL_ARB_shader_texture_lod : enable

			varying vec2 texCoord;
			void main() { gl_FragColor = vec4(texCoord, 0.0, 1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertExtensionsPrecedeCode(patched);
		assertTrue(patched.contains("#extension GL_ARB_shader_texture_lod"), patched);
	}

	@Test
	void placesDeclarationsAfterTheExtensionEvenWhenNoVersionCanLeadTheFile() {
		// Real code before the #version forces the prepend path. Note this source is already invalid
		// GLSL as the pack wrote it - its own declaration precedes its own #extension - so no
		// placement can make the whole file legal. What the patcher can guarantee, and does, is that
		// it never makes the ordering worse by inserting its declarations ahead of the extension.
		String source = """
			varying vec2 leading;
			#version 120
			#extension GL_ARB_shader_texture_lod : enable
			void main() { gl_FragColor = vec4(leading, 0.0, 1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.indexOf("#extension") < patched.indexOf("out vec4 tapetum_FragColor"),
			patched);
	}

	@Test
	void doesNotEmitDeclarationsInsideALeadingBlockCommentWhenThereIsNoExtension() {
		// The mirror hazard: with no #extension to order against, deferring the declarations would
		// drop them inside the header comment, where they would silently not exist.
		String source = """
			/*
			A header whose inner lines are plain prose
			*/
			varying vec2 texCoord;
			void main() { gl_FragColor = vec4(texCoord, 0.0, 1.0); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		int declaration = patched.indexOf("out vec4 tapetum_FragColor");
		assertTrue(declaration >= 0, patched);
		assertTrue(declaration < patched.indexOf("A header whose inner lines"), patched);
	}

	@Test
	void treatsAClosedBlockCommentHeaderAsLeadingWhitespace() {
		// The #version keeps its position, so nothing is prepended ahead of the pack's own directives.
		String source = "/* header\nprose line\n*/\n#version 150\nvoid main() {}\n";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.trim().startsWith("/* header"), patched);
	}

	/**
	 * The real {@code #extension} directives, excluding the {@code // [tapetum] hoisted ...} audit
	 * comments — which deliberately record what the pack originally wrote, {@code require} included.
	 */
	private static java.util.List<String> extensionDirectives(String patched) {
		return java.util.Arrays.stream(patched.split("\n", -1))
			.map(String::trim)
			.filter(line -> line.startsWith("#extension"))
			.toList();
	}

	@Test
	void hoistsAnExtensionDeclaredByAnIncludedLibraryToTheTop() {
		// Complementary and Mellow both declare extensions inside library files. After #include
		// expansion those land thousands of lines in, past the first real declaration, and the driver
		// rejects the whole file. Nineteen of forty-nine passes across the five surveyed packs were
		// in this shape.
		String source = """
			#version 330
			in vec2 texCoord;
			#extension GL_ARB_shader_storage_buffer_object : enable
			void main() {}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertExtensionsPrecedeCode(patched);
		assertEquals(java.util.List.of("#extension GL_ARB_shader_storage_buffer_object : enable"),
			extensionDirectives(patched), patched);
		assertTrue(patched.contains("// [tapetum] hoisted #extension"), patched);
	}

	@Test
	void softensRequireToEnableWhenHoistingOutOfAConditional() {
		// Mellow guards "GL_ARB_shader_image_load_store : require" behind #ifdef COLORED_LIGHTS.
		// Hoisting makes it unconditional, and a hard require for an extension a 4.1 context lacks
		// would fail every compile - including the ones whose guarded code was never included.
		String source = """
			#version 330
			in vec2 texCoord;
			#ifdef COLORED_LIGHTS
			#extension GL_ARB_shader_image_load_store : require
			#endif
			void main() {}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertEquals(java.util.List.of("#extension GL_ARB_shader_image_load_store : enable"),
			extensionDirectives(patched), patched);
	}

	@Test
	void keepsATopLevelRequireAsRequire() {
		// Unguarded, the pack means it for every configuration; softening it would hide a real
		// incompatibility behind a warning.
		String source = "#version 330\n#extension GL_ARB_gpu_shader5 : require\nin vec2 t;\nvoid main() {}";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertEquals(java.util.List.of("#extension GL_ARB_gpu_shader5 : require"),
			extensionDirectives(patched), patched);
	}

	@Test
	void declaresEachHoistedExtensionOnlyOnce() {
		// The same library gets included down several paths, so the same directive arrives repeatedly.
		String source = """
			#version 330
			#extension GL_ARB_shader_texture_lod : enable
			in vec2 texCoord;
			#extension GL_ARB_shader_texture_lod : enable
			void main() {}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertEquals(java.util.List.of("#extension GL_ARB_shader_texture_lod : enable"),
			extensionDirectives(patched), patched);
	}

	@Test
	void renamesAPackSamplerThatShadowsTheTextureBuiltin() {
		// OptiFine's terrain sampler has always been called `texture`, which was fine when the
		// sampling function was texture2D. Core profile renamed the function to `texture`, so the
		// uniform now shadows it: "can't use function syntax on variable". BSL hits this in sixteen
		// files that cascade into fifty-one of its gbuffers programs.
		String source = """
			#version 330
			uniform sampler2D texture;
			in vec2 texCoord;
			void main() { gl_FragColor = texture2D(texture, texCoord); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("uniform sampler2D tapetum_texture;"), patched);
		assertTrue(patched.contains("texture(tapetum_texture, texCoord)"),
			"the call must stay the builtin while its argument is renamed:\n" + patched);
	}

	@Test
	void leavesTheTextureBuiltinAloneWhenNoSamplerShadowsIt() {
		// Renaming unconditionally would rewrite unrelated uses in packs that never had the clash.
		String source = """
			#version 330
			uniform sampler2D colortex0;
			in vec2 texCoord;
			void main() { gl_FragColor = texture2D(colortex0, texCoord); }
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("tapetum_texture"), patched);
		assertTrue(patched.contains("texture(colortex0, texCoord)"), patched);
	}

	@Test
	void doesNotRenameLongerIdentifiersContainingTexture() {
		// gtexture, textureLod and texture2D must all survive untouched.
		String source = """
			#version 330
			uniform sampler2D texture;
			uniform sampler2D gtexture;
			in vec2 texCoord;
			void main() {
				gl_FragColor = texture2D(texture, texCoord) + textureLod(gtexture, texCoord, 0.0);
			}
			""";

		String patched = GlslCompatPatcher.patch(source, GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("uniform sampler2D gtexture;"), patched);
		assertTrue(patched.contains("textureLod(gtexture"), patched);
		assertFalse(patched.contains("tapetum_textureLod"), patched);
	}

	@Test
	void keepsTheVersionFirstWhenTheSourceUsesTheFogVarying() {
		// The declaration standing in for gl_FogFragCoord used to be prepended, which put a
		// non-preprocessor token ahead of the pack's own #version. That disqualified the directive
		// from keeping its place and sent the file down the fallback emission path - which skips the
		// #undef pairing below. Thirty-eight of the generated gbuffers programs took that path.
		String patched = GlslCompatPatcher.patch("""
			#version 120
			#define QUALITY_SLIDER 2
			#if QUALITY_SLIDER == 2
			    #define QUALITY 1
			#endif
			#if QUALITY == 1
			void main() { gl_FragColor = vec4(gl_FogFragCoord); }
			#endif
			""", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.stripLeading().startsWith("#version"),
			"the #version must lead the file:\n" + patched);
		assertTrue(patched.contains("#undef QUALITY"),
			"the pack's own value must replace the default rather than redefine it:\n" + patched);
		assertTrue(patched.contains("in float tapetum_FogFragCoord;"), patched);
		assertFalse(patched.contains("gl_FogFragCoord"), patched);
	}

	@Test
	void declaresTheFogVaryingAsAnOutputInTheVertexStage() {
		String patched = GlslCompatPatcher.patch(
			"#version 120\nvoid main() { gl_FogFragCoord = 1.0; }", GlslCompatPatcher.Stage.VERTEX);

		assertTrue(patched.contains("out float tapetum_FogFragCoord;"), patched);
	}

	@Test
	void doesNotDeclareTheFogStructForTheVaryingAlone() {
		// tapetum_Fog is a prefix of tapetum_FogFragCoord, so a plain containment test declared the
		// fog parameter struct - and an unused uniform with it - in every program that only ever
		// wanted the varying.
		String patched = GlslCompatPatcher.patch(
			"#version 120\nvoid main() { gl_FragColor = vec4(gl_FogFragCoord); }",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("tapetum_FogParameters"), patched);
	}

	@Test
	void stillDeclaresTheFogStructWhenTheSourceReadsFogParameters() {
		String patched = GlslCompatPatcher.patch(
			"#version 120\nvoid main() { gl_FragColor = gl_Fog.color; }",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("tapetum_FogParameters"), patched);
	}

	@Test
	void rewritesTheArbSpellingsOfTheGradientSamplers() {
		// Apple's OpenGL 4.1 does not expose GL_ARB_shader_texture_lod, so BSL's parallax code
		// reached the driver as an undeclared identifier. glslangValidator implements the extension
		// and accepted all 188 calls - the driver is the authority, not the validator.
		String patched = GlslCompatPatcher.patch("""
			#version 120
			uniform sampler2D tex;
			void main() {
			    gl_FragColor = texture2DGradARB(tex, vec2(0.0), vec2(0.0), vec2(0.0));
			}
			""", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("textureGrad(tex"), patched);
		assertFalse(patched.contains("texture2DGradARB"), patched);
	}

	@Test
	void declaresAShadowGradStandInWhenTheArbSpellingIsUsed() {
		String patched = GlslCompatPatcher.patch("""
			#version 120
			uniform sampler2DShadow shadowtex0;
			void main() {
			    gl_FragColor = shadow2DGradARB(shadowtex0, vec3(0.0), vec2(0.0), vec2(0.0));
			}
			""", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.contains("vec4 shadow2DGrad(sampler2DShadow"), patched);
		assertTrue(patched.contains("shadow2DGrad(shadowtex0"), patched);
		assertFalse(patched.contains("shadow2DGradARB"), patched);
	}

	@Test
	void softensRequiredExtensionsToEnable() {
		// A required extension stops the compiler when it is missing - but the functions it provides
		// are rewritten to their core equivalents here, so the pack no longer calls them. Softened,
		// a genuinely missing feature still fails, at the call site and by name.
		String patched = GlslCompatPatcher.patch("""
			#version 120
			#extension GL_ARB_shader_texture_lod : require
			void main() { gl_FragColor = vec4(1.0); }
			""", GlslCompatPatcher.Stage.FRAGMENT);

		assertTrue(patched.lines().anyMatch(line ->
				line.trim().equals("#extension GL_ARB_shader_texture_lod : enable")), patched);
		assertTrue(patched.lines().noneMatch(line ->
				line.trim().startsWith("#extension") && line.contains("require")), patched);
	}

	@Test
	void initialisesVertexOutputsThatMayBeLeftUnwritten() {
		// Mellow's gbuffers_clouds: when its cloud style is not the vanilla one it collapses the
		// geometry and returns without touching texcoord. Legal GLSL - an unwritten output is simply
		// undefined - but Apple's linker rejects the program outright.
		String patched = GlslCompatPatcher.patch("""
			#version 330
			#define CLOUD_STYLE 1
			out vec2 texcoord;
			void main() {
			    #if CLOUD_STYLE != 0
			        gl_Position = vec4(-1.0);
			    #else
			        texcoord = vec2(1.0);
			    #endif
			}
			""", GlslCompatPatcher.Stage.VERTEX);

		assertTrue(patched.contains("texcoord = vec2(0);"), patched);
	}

	@Test
	void leavesVertexOutputsThatAreAlwaysWrittenAlone() {
		String patched = GlslCompatPatcher.patch("""
			#version 330
			out vec2 texcoord;
			void main() {
			    texcoord = vec2(1.0);
			    gl_Position = vec4(0.0);
			}
			""", GlslCompatPatcher.Stage.VERTEX);

		assertFalse(patched.contains("texcoord = vec2(0);"), patched);
	}

	@Test
	void doesNotInitialiseAnOutputDeclaredInsideAConditional() {
		// A declaration inside an #ifdef does not exist when the branch is not taken, so initialising
		// it at the top of main would name something undeclared.
		String patched = GlslCompatPatcher.patch("""
			#version 330
			#ifdef EXTRA
			out vec2 extraCoord;
			#endif
			void main() {
			    #ifdef EXTRA
			        extraCoord = vec2(1.0);
			    #endif
			    gl_Position = vec4(0.0);
			}
			""", GlslCompatPatcher.Stage.VERTEX);

		assertFalse(patched.contains("extraCoord = vec2(0);"), patched);
	}

	@Test
	void doesNotInitialiseFragmentOutputs() {
		String patched = GlslCompatPatcher.patch("""
			#version 330
			out vec4 colour;
			void main() { if (gl_FragCoord.x > 0.0) { colour = vec4(1.0); } }
			""", GlslCompatPatcher.Stage.FRAGMENT);

		assertFalse(patched.contains("colour = vec4(0);"), patched);
	}
}
