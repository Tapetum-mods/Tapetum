package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the OptiFine macro set. The version encoding here is not a convention picked for
 * convenience — it is pinned by comparisons real packs ship, so an off-by-one-digit mistake silently
 * flips which code paths a pack takes.
 */
class ShaderMacrosTest {

	private static ShaderMacros macros(int version) {
		return new ShaderMacros(version, ShaderMacros.OperatingSystem.MAC, ShaderMacros.GlVendor.APPLE);
	}

	@Test
	void encodesVersionsTheWayRealPacksCompareThem() {
		// Each of these is a comparison Complementary Unbound actually makes; the encoding only lines
		// up with all three under major * 10000 + minor * 100 + patch.
		assertEquals(11605, ShaderMacros.encodeMinecraftVersion("1.16.5"));
		assertEquals(12109, ShaderMacros.encodeMinecraftVersion("1.21.9"));
		assertEquals(260200, ShaderMacros.encodeMinecraftVersion("26.2"));
		assertEquals(260102, ShaderMacros.encodeMinecraftVersion("26.1.2"));
	}

	@Test
	void ignoresSuffixesOnPrereleaseVersions() {
		assertEquals(260200, ShaderMacros.encodeMinecraftVersion("26.2-rc1"));
		assertEquals(12111, ShaderMacros.encodeMinecraftVersion("1.21.11-pre2"));
	}

	@Test
	void treatsUnparseableVersionAsOlderThanAnything() {
		// Zero reads to a pack as "older than every gate", which keeps version-gated features off
		// rather than switching them on against a version that may not support them.
		assertEquals(0, ShaderMacros.encodeMinecraftVersion(""));
		assertEquals(0, ShaderMacros.encodeMinecraftVersion(null));
		assertEquals(0, ShaderMacros.encodeMinecraftVersion("snapshot"));
	}

	@Test
	void definesMinecraftVersionAsANumber() {
		assertTrue(macros(260200).toDefineBlock().contains("#define MC_VERSION 260200"),
			macros(260200).toDefineBlock());
	}

	@Test
	void definesOnlyTheCurrentOperatingSystem() {
		String block = macros(260200).toDefineBlock();

		assertTrue(block.contains("#define MC_OS_MAC"), block);
		assertFalse(block.contains("MC_OS_WINDOWS"), block);
		assertFalse(block.contains("MC_OS_LINUX"), block);
	}

	@Test
	void definesIrisVersionButNotIsIris() {
		// The pack must take its non-Iris path (none of Iris' extensions exist here), yet strict
		// drivers still need a value for the numeric IRIS_VERSION comparisons packs make.
		String block = macros(260200).toDefineBlock();

		assertTrue(block.contains("#define IRIS_VERSION 0"), block);
		assertFalse(block.contains("IS_IRIS"), block);
	}

	@Test
	void detectsOperatingSystemsFromRealSystemPropertyValues() {
		assertEquals(ShaderMacros.OperatingSystem.MAC, ShaderMacros.OperatingSystem.detect("Mac OS X"));
		assertEquals(ShaderMacros.OperatingSystem.WINDOWS, ShaderMacros.OperatingSystem.detect("Windows 11"));
		assertEquals(ShaderMacros.OperatingSystem.LINUX, ShaderMacros.OperatingSystem.detect("Linux"));
		assertEquals(ShaderMacros.OperatingSystem.OTHER, ShaderMacros.OperatingSystem.detect("Haiku"));
		assertEquals(ShaderMacros.OperatingSystem.OTHER, ShaderMacros.OperatingSystem.detect(null));
	}

	@Test
	void detectsVendorsFromRealGlVendorStrings() {
		// "Apple" is the string this project observed on an M1; the rest are the usual spellings.
		assertEquals(ShaderMacros.GlVendor.APPLE, ShaderMacros.GlVendor.detect("Apple"));
		assertEquals(ShaderMacros.GlVendor.NVIDIA, ShaderMacros.GlVendor.detect("NVIDIA Corporation"));
		assertEquals(ShaderMacros.GlVendor.INTEL, ShaderMacros.GlVendor.detect("Intel Inc."));
		assertEquals(ShaderMacros.GlVendor.ATI, ShaderMacros.GlVendor.detect("ATI Technologies Inc."));
		assertEquals(ShaderMacros.GlVendor.AMD, ShaderMacros.GlVendor.detect("AMD"));
		assertEquals(ShaderMacros.GlVendor.OTHER, ShaderMacros.GlVendor.detect(null));
	}

	@Test
	void macroBlockLandsDirectlyAfterTheVersionDirective() {
		// A pack's very first lines are often #if MC_VERSION guards, so the defines have to precede
		// everything the pack wrote - but still follow #version, which must come first of all.
		String patched = GlslCompatPatcher.patch(
			"#version 150\n#if MC_VERSION >= 260200\nfloat x = 1.0;\n#endif\n",
			GlslCompatPatcher.Stage.FRAGMENT,
			macros(260200));

		String[] lines = patched.split("\n");
		assertTrue(lines[0].startsWith("#version"), patched);
		assertTrue(lines[1].contains("MC_VERSION"), patched);
		assertTrue(patched.indexOf("#define MC_VERSION") < patched.indexOf("#if MC_VERSION"), patched);
	}

	@Test
	void definesNothingWhenNoMacrosSupplied() {
		String patched = GlslCompatPatcher.patch("#version 150\nvoid main() {}\n",
			GlslCompatPatcher.Stage.FRAGMENT, null);

		assertFalse(patched.contains("MC_VERSION"), patched);
	}
}
