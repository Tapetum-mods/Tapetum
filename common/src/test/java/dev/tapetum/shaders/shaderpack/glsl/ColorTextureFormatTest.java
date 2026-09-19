package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render-target formats, read from the declarations real packs ship. Every value asserted here was
 * taken from the five packs surveyed locally, not invented.
 */
class ColorTextureFormatTest {

	@Test
	void readsTheFormatsTheSurveyedPacksActuallyDeclare() {
		// Verbatim from BSL's pipeline settings.
		String source = """
			const int colortex0Format = R11F_G11F_B10F;
			const int colortex1Format = RGB8_SNORM;
			const int colortex2Format = RGB16F;
			const int colortex4Format = RGBA8_SNORM;
			const int colortex7Format = RGBA16F;
			""";

		Map<Integer, ColorTextureFormat> formats = ColorTextureFormat.parse(source);

		assertEquals(ColorTextureFormat.R11F_G11F_B10F, formats.get(0));
		assertEquals(ColorTextureFormat.RGB8_SNORM, formats.get(1));
		assertEquals(ColorTextureFormat.RGB16F, formats.get(2));
		assertEquals(ColorTextureFormat.RGBA8_SNORM, formats.get(4));
		assertEquals(ColorTextureFormat.RGBA16F, formats.get(7));
	}

	@Test
	void mapsThePre117GauxSpellingOntoColortexFourThroughSeven() {
		// gaux1 is colortex4. Packs derived from older sources still ship this spelling, and reading
		// it as colortex1 would give the normals buffer the wrong format.
		Map<Integer, ColorTextureFormat> formats =
			ColorTextureFormat.parse("const int gaux1Format = RGBA16F;\nconst int gaux4Format = RGB8;");

		assertEquals(ColorTextureFormat.RGBA16F, formats.get(4));
		assertEquals(ColorTextureFormat.RGB8, formats.get(7));
	}

	@Test
	void recognisesSignedFormats() {
		// The reason this class exists: a signed format held in an unsigned buffer clamps every
		// negative component to zero, so half of every normal vector is lost.
		assertTrue(ColorTextureFormat.RGB8_SNORM.isSigned());
		assertTrue(ColorTextureFormat.RGBA8_SNORM.isSigned());
		assertFalse(ColorTextureFormat.RGBA8.isSigned());
		assertFalse(ColorTextureFormat.RGBA16F.isSigned());
	}

	@Test
	void recognisesHighDynamicRangeFormats() {
		assertTrue(ColorTextureFormat.R11F_G11F_B10F.isHighDynamicRange());
		assertTrue(ColorTextureFormat.RGBA16F.isHighDynamicRange());
		assertTrue(ColorTextureFormat.RGB9_E5.isHighDynamicRange());
		assertFalse(ColorTextureFormat.RGBA8.isHighDynamicRange());
		assertFalse(ColorTextureFormat.RGB8_SNORM.isHighDynamicRange());
	}

	@Test
	void doesNotMistakeAnUnsignedIntegerFormatForASignedOne() {
		// RGBA8UI and RGBA8I differ by one letter and by their whole range.
		assertTrue(ColorTextureFormat.RGBA8I.isSigned());
		assertFalse(ColorTextureFormat.RGBA8UI.isSigned());
	}

	@Test
	void ignoresAFormatNameItCannotRender() {
		// An unknown name is a reason to fall back, not to refuse the pack.
		assertTrue(ColorTextureFormat.parse("const int colortex0Format = SOMETHING_NEW;").isEmpty());
		assertTrue(ColorTextureFormat.parseName("SOMETHING_NEW").isEmpty());
	}

	@Test
	void readsDeclarationsFromInsideDisabledBranches() {
		// Allocating wider than the active branch needs costs memory; allocating narrower costs data
		// the pack cannot get back, so both arms are honoured.
		String source = """
			#ifdef NEVER_DEFINED
			const int colortex3Format = RGBA16F;
			#endif
			""";

		assertEquals(ColorTextureFormat.RGBA16F, ColorTextureFormat.parse(source).get(3));
	}

	@Test
	void toleratesLeadingWhitespaceAndTrailingComments() {
		String source = "    const int colortex5Format = RG16; // packed motion vectors\n";

		assertEquals(ColorTextureFormat.RG16, ColorTextureFormat.parse(source).get(5));
	}

	@Test
	void returnsNothingForASourceThatDeclaresNoFormats() {
		assertTrue(ColorTextureFormat.parse("void main() {}").isEmpty());
	}

	@Test
	void readsTheFormatBackOutOfThePatchedSource() {
		// The pipeline only ever holds the patched source, where the patcher has commented these lines
		// out to make the file compilable. Parsing only the raw form would report no formats for every
		// pack, and every buffer would quietly fall back to RGBA8.
		String patched = GlslCompatPatcher.patch(
			"#version 330\nconst int colortex1Format = RGB8_SNORM;\nvoid main() {}",
			GlslCompatPatcher.Stage.FRAGMENT);

		assertEquals(ColorTextureFormat.RGB8_SNORM, ColorTextureFormat.parse(patched).get(1));
	}
}
