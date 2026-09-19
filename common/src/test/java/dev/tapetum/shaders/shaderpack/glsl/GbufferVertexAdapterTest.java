package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geometry vertex adapter. Unlike the composite passes, these shaders run on real geometry, so
 * every removed builtin needs a real attribute or uniform behind it rather than an identity.
 */
class GbufferVertexAdapterTest {

	/** How many times {@code name} is *declared*, as opposed to merely mentioned. */
	private static long declarationsOf(String source, String name) {
		return source.lines()
			.filter(line -> line.trim().matches("^(in|attribute)\\s+\\w+\\s+" + name + "\\s*;.*"))
			.count();
	}

	@Test
	void declaresTheMatricesWhenTheyAreOnlyReachedThroughFtransform() {
		// The trap this caught: ftransform() expands to a projection and a model-view reference, so a
		// shader that never names either directly still needs both declared. Expanding to the
		// replacement names instead of the gl_ ones leaves it referring to uniforms nothing declares.
		String adapted = GbufferVertexAdapter.adapt(
			"#version 150\nvoid main() { gl_Position = ftransform(); }");

		assertTrue(adapted.contains("uniform mat4 tapetum_ProjectionMatrix;"), adapted);
		assertTrue(adapted.contains("uniform mat4 tapetum_ModelViewMatrix;"), adapted);
		assertTrue(adapted.contains("in vec3 tapetum_Position;"), adapted);
		assertFalse(adapted.contains("ftransform"), adapted);
	}

	@Test
	void bindsVertexAndColourToRealAttributes() {
		// Not identities: this geometry is actually transformed.
		String adapted = GbufferVertexAdapter.adapt(
			"#version 150\nvoid main() { vec4 p = gl_Vertex; vec4 c = gl_Color; }");

		assertTrue(adapted.contains("in vec3 tapetum_Position;"), adapted);
		assertTrue(adapted.contains("in vec4 tapetum_Color;"), adapted);
		assertTrue(adapted.contains("vec4(tapetum_Position, 1.0)"), adapted);
	}

	@Test
	void leavesThePacksOwnAttributesAlone() {
		// Packs declare mc_Entity, mc_midTexCoord, at_tangent and at_midBlock themselves; the patcher
		// has already turned `attribute` into `in`. Declaring them again is a duplicate and a compile
		// error.
		String source = """
			#version 150
			in vec4 mc_Entity;
			in vec4 at_tangent;
			in vec4 at_midBlock;
			void main() { gl_Position = ftransform(); float id = mc_Entity.x; }
			""";

		String adapted = GbufferVertexAdapter.adapt(source);

		for (String attribute : new String[] { "mc_Entity", "at_tangent", "at_midBlock" }) {
			assertEquals(1, declarationsOf(adapted, attribute),
				attribute + " must keep exactly the pack's own declaration:\n" + adapted);
		}
		assertFalse(adapted.contains("tapetum_Entity"), adapted);
	}

	@Test
	void doesNotCorruptTheLongerNameWhenAShorterOneIsAPrefix() {
		String adapted = GbufferVertexAdapter.adapt("""
			#version 150
			void main() {
				gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				vec3 n = gl_NormalMatrix * gl_Normal;
			}
			""");

		assertTrue(adapted.contains("tapetum_ModelViewProjectionMatrix"), adapted);
		assertTrue(adapted.contains("tapetum_NormalMatrix"), adapted);
		assertFalse(adapted.contains("tapetum_ModelViewMatrixProjection"), adapted);
		assertFalse(adapted.contains("tapetum_NormalMatrixMatrix"), adapted);
	}

	@Test
	void declaresOnlyWhatIsUsed() {
		String adapted = GbufferVertexAdapter.adapt(
			"#version 150\nvoid main() { vec4 c = gl_Color; }");

		assertTrue(adapted.contains("in vec4 tapetum_Color;"), adapted);
		assertFalse(adapted.contains("tapetum_NormalMatrix"), adapted);
		assertFalse(adapted.contains("tapetum_TextureMatrix"), adapted);
	}

	@Test
	void keepsTheTextureMatrixIndexable() {
		String adapted = GbufferVertexAdapter.adapt(
			"#version 150\nvoid main() { vec4 t = gl_TextureMatrix[1] * gl_MultiTexCoord0; }");

		assertTrue(adapted.contains("uniform mat4 tapetum_TextureMatrix[2];"), adapted);
		assertTrue(adapted.contains("tapetum_TextureMatrix[1]"), adapted);
	}

	@Test
	void placesTheAdapterAfterAVersionPrecededByABlockComment() {
		// BSL's header shape, which broke the full-screen adapter the same way.
		String adapted = GbufferVertexAdapter.adapt("""
			/*
			BSL Shaders v10 Series by Capt Tatsu
			*/

			#version 330
			void main() { gl_Position = ftransform(); }
			""");

		String[] lines = adapted.split("\n", -1);
		int version = -1;
		int declaration = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith("#version")) {
				version = i;
			}
			if (declaration < 0 && lines[i].contains("tapetum_Position")) {
				declaration = i;
			}
		}

		assertTrue(version >= 0 && declaration > version,
			"declarations must follow #version:\n" + adapted);
	}

	@Test
	void leavesGlPositionAlone() {
		assertTrue(GbufferVertexAdapter.adapt("#version 150\nvoid main() { gl_Position = ftransform(); }")
			.contains("gl_Position ="));
	}

	@Test
	void leavesACoreProfileSourceUntouched() {
		String source = "#version 330\nin vec3 pos;\nvoid main() { gl_Position = vec4(pos, 1.0); }";

		assertFalse(GbufferVertexAdapter.isNeeded(source));
		assertEquals(source, GbufferVertexAdapter.adapt(source));
	}

	@Test
	void rewritesTheCoreProfileSpellingsIrisIntroduced() {
		// Complementary's gbuffers_basic widens lines itself, which needs the untransformed position
		// and gl_VertexID, so its GBUFFERS_LINE branch is written entirely in these names.
		String adapted = GbufferVertexAdapter.adapt("""
			#version 330
			void main() {
			    vec4 start = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
			    vec4 end = projectionMatrix * modelViewMatrix * vec4(vaPosition + vaNormal, 1.0);
			    gl_Position = start + end;
			}
			""");

		assertTrue(adapted.contains("uniform mat4 tapetum_ProjectionMatrix;"), adapted);
		assertTrue(adapted.contains("uniform mat4 tapetum_ModelViewMatrix;"), adapted);
		assertTrue(adapted.contains("in vec3 tapetum_Position;"), adapted);
		assertTrue(adapted.contains("in vec3 tapetum_Normal;"), adapted);
		assertFalse(adapted.contains("vaPosition"), adapted);
		assertFalse(adapted.contains(" projectionMatrix"), adapted);
	}

	@Test
	void declaresEachUniformOnceWhenBothSpellingsAppear() {
		String adapted = GbufferVertexAdapter.adapt("""
			#version 330
			void main() {
			    gl_Position = gl_ProjectionMatrix * vec4(vaPosition, 1.0);
			    vec4 other = projectionMatrix * gl_Vertex;
			}
			""");

		assertEquals(1, occurrences(adapted, "uniform mat4 tapetum_ProjectionMatrix;"), adapted);
		assertEquals(1, occurrences(adapted, "in vec3 tapetum_Position;"), adapted);
	}

	@Test
	void leavesACoreProfileNameThePackDeclaresItself() {
		// An ordinary identifier, unlike the reserved gl_ prefix: a pack may declare its own, and
		// rewriting it would both redirect that variable and duplicate its declaration.
		String source = """
			#version 330
			uniform mat4 projectionMatrix;
			in vec3 vaPosition;
			void main() { gl_Position = projectionMatrix * vec4(vaPosition, 1.0); }
			""";

		String adapted = GbufferVertexAdapter.adapt(source);

		assertEquals(source, adapted);
		assertFalse(adapted.contains("tapetum_ProjectionMatrix"), adapted);
	}

	private static int occurrences(String haystack, String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
			count++;
		}
		return count;
	}
}
