package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fixed-function substitutions a pack's own composite vertex shader needs.
 *
 * <p>The shapes exercised here are taken from BSL's {@code deferred1.vsh}, which is the reason this
 * class exists: it computes {@code sunVec}, {@code upVec} and {@code eastVec}, and discarding it in
 * favour of a generated stub zeroed the sun direction for the whole lighting model.</p>
 */
class FullScreenVertexAdapterTest {

	@Test
	void rewritesTheShapeBslActuallyShips() {
		String source = """
			#version 150
			out vec2 texCoord;
			void main() {
				texCoord = gl_MultiTexCoord0.xy;
				gl_Position = ftransform();
			}
			""";

		String adapted = FullScreenVertexAdapter.adapt(source);

		assertTrue(adapted.contains("tapetum_MultiTexCoord0().xy"), adapted);
		assertTrue(adapted.contains("gl_Position = tapetum_Vertex();"), adapted);
		assertFalse(adapted.contains("ftransform()"), adapted);
	}

	@Test
	void leavesGlPositionAlone() {
		// Still a core-profile builtin. Rewriting it would break the one output that must be written.
		String adapted = FullScreenVertexAdapter.adapt(
			"#version 150\nvoid main() { gl_Position = ftransform(); }");

		assertTrue(adapted.contains("gl_Position ="), adapted);
	}

	@Test
	void doesNotCorruptTheLongerNameWhenAShorterOneIsAPrefix() {
		// gl_ModelViewMatrix is a prefix of gl_ModelViewProjectionMatrix, and gl_Normal of
		// gl_NormalMatrix. Substituting the short name first would produce
		// tapetum_ModelViewMatrixProjectionMatrix - a name nothing declares, and a link failure whose
		// message points nowhere useful.
		String source = """
			#version 150
			void main() {
				gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				vec3 n = gl_NormalMatrix * gl_Normal;
			}
			""";

		String adapted = FullScreenVertexAdapter.adapt(source);

		assertTrue(adapted.contains("tapetum_ModelViewProjectionMatrix"), adapted);
		assertTrue(adapted.contains("tapetum_NormalMatrix"), adapted);
		assertFalse(adapted.contains("tapetum_ModelViewMatrixProjection"), adapted);
		assertFalse(adapted.contains("tapetum_NormalMatrixMatrix"), adapted);
	}

	@Test
	void declaresOnlyWhatTheSourceUses() {
		String adapted = FullScreenVertexAdapter.adapt(
			"#version 150\nvoid main() { gl_Position = gl_ModelViewMatrix * gl_Vertex; }");

		assertTrue(adapted.contains("const mat4 tapetum_ModelViewMatrix"), adapted);
		assertFalse(adapted.contains("tapetum_NormalMatrix ="), adapted);
		assertFalse(adapted.contains("const vec4 tapetum_Color"), adapted);
	}

	@Test
	void indexesTheTextureMatrixTheWayPacksDo() {
		// Measured across the five packs: only [0] and [1] are ever indexed.
		String adapted = FullScreenVertexAdapter.adapt(
			"#version 150\nvoid main() { vec4 t = gl_TextureMatrix[1] * gl_MultiTexCoord0; }");

		assertTrue(adapted.contains("tapetum_TextureMatrix[1]"), adapted);
		assertTrue(adapted.contains("const mat4 tapetum_TextureMatrix[2]"), adapted);
	}

	@Test
	void placesTheAdapterAfterVersionAndExtensionDirectives() {
		// #version must come first and every #extension must precede any non-preprocessor token.
		// These declarations are exactly such tokens, so inserting them any earlier fails the compile.
		String source = """
			#version 330
			#extension GL_ARB_shader_texture_lod : enable
			void main() { gl_Position = ftransform(); }
			""";

		String adapted = FullScreenVertexAdapter.adapt(source);
		String[] lines = adapted.split("\n", -1);

		int extension = -1;
		int firstDeclaration = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith("#extension")) {
				extension = i;
			}
			if (firstDeclaration < 0 && lines[i].contains("tapetum_ScreenUv")) {
				firstDeclaration = i;
			}
		}

		assertTrue(extension >= 0 && firstDeclaration > extension,
			"adapter must follow the #extension block:\n" + adapted);
	}

	@Test
	void emitsTheScreenGeometryOnlyOnceEvenWithSeveralUsers() {
		String adapted = FullScreenVertexAdapter.adapt(
			"#version 150\nvoid main() { gl_Position = ftransform(); vec4 v = gl_Vertex; "
				+ "vec4 t = gl_MultiTexCoord0; }");

		assertTrue(adapted.contains("vec2 tapetum_ScreenUv()"), adapted);
		assertFalse(adapted.replaceFirst("vec2 tapetum_ScreenUv\\(\\)", "")
			.contains("vec2 tapetum_ScreenUv()"), "declared twice:\n" + adapted);
	}

	@Test
	void leavesASourceThatNeedsNothingUntouched() {
		// Complementary's vertex halves are already core-profile; adapting them would be noise.
		String source = "#version 330\nin vec2 pos;\nvoid main() { gl_Position = vec4(pos, 0.0, 1.0); }";

		assertFalse(FullScreenVertexAdapter.isNeeded(source));
		assertTrue(FullScreenVertexAdapter.adapt(source).equals(source));
	}

	@Test
	void doesNotRewriteANameThatMerelyContainsABuiltin() {
		String source = "#version 150\nvoid main() { float my_gl_Vertexish = 1.0; gl_Position = vec4(0); }";

		assertTrue(FullScreenVertexAdapter.adapt(source).contains("my_gl_Vertexish"),
			"an identifier containing a builtin must not be rewritten");
	}

	@Test
	void reportsWhenAdaptationIsNeeded() {
		assertTrue(FullScreenVertexAdapter.isNeeded("gl_Position = ftransform();"));
		assertTrue(FullScreenVertexAdapter.isNeeded("vec4 v = gl_Vertex;"));
		assertFalse(FullScreenVertexAdapter.isNeeded("gl_Position = vec4(0.0);"));
	}

	@Test
	void placesTheAdapterAfterAVersionPrecededByABlockComment() {
		// BSL's exact shape, and the one that broke this. The header is a block comment whose inner
		// lines are plain prose, so scanning forward for "still a header?" stops on line two and the
		// preamble lands above the #version - which the driver rejects outright. Eleven passes failed
		// this way before the adapter searched for the directive instead.
		String source = """
			/*
			BSL Shaders v10 Series by Capt Tatsu
			https://capttatsu.com
			*/

			#version 330
			#define MC_VERSION 260102
			void main() { gl_Position = ftransform(); }
			""";

		String adapted = FullScreenVertexAdapter.adapt(source);
		String[] lines = adapted.split("\n", -1);

		int version = -1;
		int adapterAt = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith("#version")) {
				version = i;
			}
			if (adapterAt < 0 && lines[i].contains("tapetum_ScreenUv")) {
				adapterAt = i;
			}
		}

		assertTrue(version >= 0, adapted);
		assertTrue(adapterAt > version,
			"the adapter must follow #version, not precede it:\n" + adapted);
	}
}
