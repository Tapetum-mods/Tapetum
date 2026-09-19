package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers vertex/fragment interface matching. The declarations exercised here are the ones real packs
 * ship: Complementary declares {@code noperspective in vec2 texCoord}, BSL a plain
 * {@code in vec2 texCoord}, MakeUp a lowercase {@code texcoord} plus a second {@code float}
 * varying — and a mismatch on any of name, type or qualifier is a hard link failure, not a warning.
 */
class GlslStageLinkageTest {

	@Test
	void carriesInterpolationQualifierThroughToTheVertexStage() {
		// The exact shape that broke Complementary Reimagined: the qualifier must survive, or the
		// program fails to link with "differs in type/qualifiers".
		String fragment = "#version 330\nnoperspective in vec2 texCoord;\nvoid main() {}\n";

		String vertex = GlslStageLinkage.buildFullScreenVertexShader(fragment, 330);

		assertTrue(vertex.contains("noperspective out vec2 texCoord;"), vertex);
	}

	@Test
	void emitsNoQualifierWhenTheFragmentStageDeclaresNone() {
		// BSL's shape. Adding a qualifier here would break linking just as surely as dropping one.
		String fragment = "#version 150\nin vec2 texCoord;\nvoid main() {}\n";

		String vertex = GlslStageLinkage.buildFullScreenVertexShader(fragment, 150);

		assertTrue(vertex.contains("out vec2 texCoord;"), vertex);
		assertFalse(vertex.contains("noperspective"), vertex);
	}

	@Test
	void matchesTheFragmentStagesVersion() {
		// GLSL refuses to link two stages compiled at different versions.
		String fragment = "#version 400\nin vec2 texcoord;\nvoid main() {}\n";

		assertTrue(GlslStageLinkage.buildFullScreenVertexShader(fragment, 400).startsWith("#version 400"),
			"vertex stage must be generated at the fragment stage's version");
		assertEquals(400, GlslStageLinkage.declaredVersion(fragment));
	}

	@Test
	void recognisesLowercaseTextureCoordinateName() {
		// MakeUp and Mellow spell it "texcoord"; it still has to receive the full-screen UV rather
		// than being zeroed like an unknown varying.
		String fragment = "#version 330\nin vec2 texcoord;\nvoid main() {}\n";

		String vertex = GlslStageLinkage.buildFullScreenVertexShader(fragment, 330);

		assertTrue(vertex.contains("texcoord = tapetum_pos;"), vertex);
	}

	@Test
	void declaresAndZeroesVaryingsThisPassCannotSupply() {
		// MakeUp's second varying. It cannot be derived from an attributeless full-screen triangle,
		// but omitting it would fail the link outright - so it is declared and zeroed.
		String fragment = "#version 330\nin vec2 texcoord;\nin float exposure;\nvoid main() {}\n";

		String vertex = GlslStageLinkage.buildFullScreenVertexShader(fragment, 330);

		assertTrue(vertex.contains("out float exposure;"), vertex);
		assertTrue(vertex.contains("exposure = 0.0;"), vertex);
	}

	@Test
	void ignoresOutputDeclarationsFromTheDisabledVertexHalf() {
		// Packs keep both stages in one file behind #ifdefs, so the expanded fragment source also
		// contains the vertex half's "out" declarations. Mistaking one for an input would emit a
		// duplicate.
		String fragment = """
			#version 330
			noperspective in vec2 texCoord;
			noperspective out vec2 texCoord;
			void main() {}
			""";

		List<GlslStageLinkage.FragmentInput> inputs = GlslStageLinkage.fragmentInputs(fragment);

		assertEquals(1, inputs.size(), inputs.toString());
		assertEquals("texCoord", inputs.get(0).name());
	}

	@Test
	void doesNotMistakeInsideFunctionBodiesForDeclarations() {
		String fragment = """
			#version 330
			in vec2 texCoord;
			void main() {
				float in_progress = 1.0;
			}
			""";

		assertEquals(1, GlslStageLinkage.fragmentInputs(fragment).size());
	}

	@Test
	void reportsEachVaryingOnceEvenIfDeclaredTwice() {
		String fragment = "#version 330\nin vec2 texCoord;\nin vec2 texCoord;\nvoid main() {}\n";

		assertEquals(1, GlslStageLinkage.fragmentInputs(fragment).size());
	}

	@Test
	void fallsBackToAUsableVersionWhenNoneIsDeclared() {
		assertEquals(150, GlslStageLinkage.declaredVersion("void main() {}"));
	}

	@Test
	void raisesVertexVersionWhenTheFragmentNeedsItEvenWithoutAVertexDirective() {
		String vertex = "void main() { gl_Position = vec4(0.0); }\n";
		String fragment = "#version 330\nin vec2 texCoord;\nvoid main() {}\n";

		String aligned = GlslStageLinkage.alignVersions(vertex, fragment, "test");

		assertTrue(aligned.startsWith("#version 330"), aligned);
	}

	@Test
	void replacesAnExistingVertexVersionDirectiveInsteadOfPrependingADuplicateOne() {
		String vertex = "/* header */\n#version 150\nvoid main() { gl_Position = vec4(0.0); }\n";
		String fragment = "#version 330\nin vec2 texCoord;\nvoid main() {}\n";

		String aligned = GlslStageLinkage.alignVersions(vertex, fragment, "test");

		assertEquals(1, aligned.lines().filter(line -> line.trim().startsWith("#version")).count(), aligned);
		assertTrue(aligned.contains("#version 330"), aligned);
		assertFalse(aligned.contains("#version 150"), aligned);
	}

	@Test
	void alwaysWritesGlPosition() {
		// Without this the vertex stage compiles and the pass silently draws nothing.
		String vertex = GlslStageLinkage.buildFullScreenVertexShader("#version 150\nvoid main() {}", 150);

		assertTrue(vertex.contains("gl_Position ="), vertex);
	}

	@Test
	void readsEveryNameInACommaSeparatedDeclaration() {
		// Complementary Reimagined writes "flat in vec3 upVec, sunVec, eastVec;". An earlier regex
		// required the name to be followed by a semicolon, so the comma made it match nothing at all
		// and the vertex stage silently omitted all three - the driver then refused to link with
		// "Input of fragment shader 'sunVec' not written by vertex shader".
		String fragment = "#version 330\nflat in vec3 upVec, sunVec, eastVec;\nvoid main() {}";

		List<GlslStageLinkage.FragmentInput> inputs = GlslStageLinkage.fragmentInputs(fragment);

		assertEquals(3, inputs.size(), inputs.toString());
		assertEquals(List.of("upVec", "sunVec", "eastVec"),
			inputs.stream().map(GlslStageLinkage.FragmentInput::name).toList());
		assertTrue(inputs.stream().allMatch(i -> i.qualifiers().equals("flat")), inputs.toString());
	}

	@Test
	void writesEveryNameOfACommaSeparatedDeclarationInTheVertexStage() {
		String fragment = "#version 330\nflat in vec3 upVec, sunVec, eastVec;\nvoid main() {}";

		String vertex = GlslStageLinkage.buildFullScreenVertexShader(fragment, 330);

		for (String name : List.of("upVec", "sunVec", "eastVec")) {
			assertTrue(vertex.contains("flat out vec3 " + name + ";"), name + " missing from:\n" + vertex);
			assertTrue(vertex.contains(name + " = vec3(0.0);"), name + " unwritten in:\n" + vertex);
		}
	}

	@Test
	void toleratesTightAndLooseSpacingInADeclarationList() {
		String fragment = "#version 330\nin vec2 a,b , c;\nvoid main() {}";

		assertEquals(List.of("a", "b", "c"),
			GlslStageLinkage.fragmentInputs(fragment).stream()
				.map(GlslStageLinkage.FragmentInput::name).toList());
	}
}
