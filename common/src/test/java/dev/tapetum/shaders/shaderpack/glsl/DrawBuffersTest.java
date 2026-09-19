package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers draw-buffer parsing against the directives real packs ship. Every case here was taken from
 * a pack on disk rather than invented, because the failure mode is silent: a pass writing to the
 * wrong buffer corrupts what the next pass reads, and the error only surfaces as a wrong image many
 * passes later.
 */
class DrawBuffersTest {

	@Test
	void readsASingleBufferDirective() {
		// Complementary's composite: /* DRAWBUFFERS:7 */
		assertEquals(List.of(7), DrawBuffers.parse("/* DRAWBUFFERS:7 */\nvoid main() {}"));
	}

	@Test
	void treatsEachCharacterAsItsOwnBuffer() {
		// DRAWBUFFERS:71 means colortex7 and colortex1 - not "buffer seventy-one".
		assertEquals(List.of(7, 1), DrawBuffers.parse("/* DRAWBUFFERS:71 */"));
		assertEquals(List.of(0, 1, 9), DrawBuffers.parse("/* DRAWBUFFERS:019 */"));  // BSL's composite
	}

	@Test
	void preservesDeclarationOrderBecauseItMapsFragmentOutputs() {
		// Output 0 goes to colortex3, output 1 to colortex1. Sorting this silently swaps them.
		assertEquals(List.of(3, 1), DrawBuffers.parse("/* DRAWBUFFERS:31 */"));
		assertEquals(List.of(1, 3), DrawBuffers.parse("/* DRAWBUFFERS:13 */"));
	}

	@Test
	void defaultsToColortexZeroWhenNothingIsDeclared() {
		assertEquals(DrawBuffers.DEFAULT, DrawBuffers.parse("void main() { gl_FragColor = vec4(1.0); }"));
	}

	@Test
	void readsTheNewerCommaSeparatedForm() {
		assertEquals(List.of(0, 3, 11), DrawBuffers.parse("/* RENDERTARGETS: 0,3,11 */"));
		assertEquals(List.of(0, 1), DrawBuffers.parse("/* RENDERTARGETS:0,1 */"));
	}

	@Test
	void addressesBuffersAboveNineInTheOlderForm() {
		// The single-character form runs out of decimal digits at 9, so it continues in hex.
		assertEquals(List.of(10, 15), DrawBuffers.parse("/* DRAWBUFFERS:af */"));
		assertEquals(List.of(10, 15), DrawBuffers.parse("/* DRAWBUFFERS:AF */"));
	}

	@Test
	void takesTheFirstOfSeveralConditionalDirectives() {
		// Complementary's composite.glsl carries both, one per #ifdef branch. Choosing correctly needs
		// preprocessor evaluation this build does not do - the limitation is documented, not hidden.
		String source = "#ifdef X\n/* DRAWBUFFERS:7 */\n#else\n/* DRAWBUFFERS:71 */\n#endif";

		assertEquals(List.of(7), DrawBuffers.parse(source));
	}

	@Test
	void collectsEveryBufferAcrossAllBranchesForAllocation() {
		// Sizing the pool from only the first branch would leave a pack writing to an unallocated
		// buffer as soon as a different branch compiles.
		String source = "#ifdef X\n/* DRAWBUFFERS:7 */\n#else\n/* DRAWBUFFERS:31 */\n#endif";

		assertEquals(Set.of(7, 3, 1), DrawBuffers.allReferencedBuffers(source));
	}

	@Test
	void sizesThePoolFromTheHighestBufferUsed() {
		// BSL reaches colortex9; MakeUp stops at 2. Allocating OptiFine's full sixteen for the latter
		// would be most of a gigabyte of screen-sized textures never sampled.
		assertEquals(10, DrawBuffers.requiredBufferCount("/* DRAWBUFFERS:019 */"));
		assertEquals(3, DrawBuffers.requiredBufferCount("/* DRAWBUFFERS:02 */"));
		assertEquals(1, DrawBuffers.requiredBufferCount("void main() {}"));
	}

	@Test
	void toleratesWhitespaceAroundTheColon() {
		assertEquals(List.of(0, 1), DrawBuffers.parse("/* DRAWBUFFERS : 01 */"));
		assertEquals(List.of(2), DrawBuffers.parse("/*DRAWBUFFERS:2*/"));
	}

	@Test
	void ignoresIndicesBeyondOptifinesCeiling() {
		assertTrue(DrawBuffers.allReferencedBuffers("/* RENDERTARGETS: 99 */").isEmpty());
	}
}
