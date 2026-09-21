package dev.tapetum.shaders.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers chain discovery against the shapes real packs actually ship — in particular the gapped
 * numbering that a naive "stop at the first missing index" would silently truncate.
 */
class ShaderProgramChainTest {

	private static ShaderPack packWith(Path root, String... programFiles) throws IOException {
		Path shaders = root.resolve("shaders");
		Files.createDirectories(shaders);
		for (String relative : programFiles) {
			Path file = shaders.resolve(relative);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "void main() {}\n");
		}
		return new ShaderPack("test-pack", root, shaders, null);
	}

	private static List<String> names(List<ShaderProgramChain.Pass> passes) {
		return passes.stream().map(ShaderProgramChain.Pass::name).toList();
	}

	@Test
	void discoversComplementaryReimaginedsRealGappedChain(@TempDir Path root) throws Exception {
		// The exact set Complementary Reimagined ships in world0: no composite2, and deferred1
		// without a deferred. Stopping at the first gap would drop composite3-7 entirely.
		try (ShaderPack pack = packWith(root,
				"world0/deferred1.fsh",
				"world0/composite.fsh", "world0/composite1.fsh",
				"world0/composite3.fsh", "world0/composite4.fsh",
				"world0/composite5.fsh", "world0/composite6.fsh", "world0/composite7.fsh",
				"world0/final.fsh")) {

			List<String> discovered = names(ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD));

			assertEquals(List.of(
				"deferred1",
				"composite", "composite1", "composite3", "composite4",
				"composite5", "composite6", "composite7",
				"final"), discovered);
		}
	}

	@Test
	void runsDeferredBeforeCompositeBeforeFinal(@TempDir Path root) throws Exception {
		// Order is the contract: a composite reads what deferred wrote, and final reads what
		// composite wrote. Running them out of order produces a plausible but wrong image.
		try (ShaderPack pack = packWith(root,
				"world0/final.fsh", "world0/composite.fsh", "world0/deferred.fsh")) {

			assertEquals(List.of("deferred", "composite", "final"),
				names(ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD)));
		}
	}

	@Test
	void numbersSortNumericallyNotAlphabetically(@TempDir Path root) throws Exception {
		// Sorted as text, composite10 would come before composite2 and the chain would run backwards
		// through five passes.
		try (ShaderPack pack = packWith(root,
				"world0/composite1.fsh", "world0/composite2.fsh", "world0/composite10.fsh")) {

			assertEquals(List.of("composite1", "composite2", "composite10"),
				names(ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD)));
		}
	}

	@Test
	void unnumberedEntryComesBeforeNumberOne(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "world0/composite1.fsh", "world0/composite.fsh")) {
			assertEquals(List.of("composite", "composite1"),
				names(ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD)));
		}
	}

	@Test
	void findsTheChainAtTheShadersRootWhenThereIsNoDimensionFolder(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "composite.fsh", "final.fsh")) {
			assertEquals(List.of("composite", "final"),
				names(ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD)));
		}
	}

	@Test
	void prefersTheDimensionCopyOverTheRootOne(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "composite.fsh", "world-1/composite.fsh")) {
			List<ShaderProgramChain.Pass> passes =
				ShaderProgramChain.discover(pack, ShaderDimension.NETHER);

			assertEquals(1, passes.size());
			assertTrue(pack.locateProgram("composite.fsh", ShaderDimension.NETHER)
				.orElseThrow().getParent().getFileName().toString().equals("world-1"));
		}
	}

	@Test
	void discoversDistinctChainsForAllVanillaDimensions(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "world0/composite.fsh", "world-1/deferred2.fsh", "world1/final.fsh")) {
			assertEquals(List.of("composite"), names(ShaderProgramChain.discover(pack,
				ShaderDimension.fromDimensionId("minecraft:overworld"))));
			assertEquals(List.of("deferred2"), names(ShaderProgramChain.discover(pack,
				ShaderDimension.fromDimensionId("minecraft:the_nether"))));
			assertEquals(List.of("final"), names(ShaderProgramChain.discover(pack,
				ShaderDimension.fromDimensionId("minecraft:the_end"))));
		}
	}

	@Test
	void reportsNothingForAPackWithNoScreenSpacePrograms(@TempDir Path root) throws Exception {
		// gbuffers alone is not a chain this class can schedule - it must not invent a final pass.
		try (ShaderPack pack = packWith(root, "world0/gbuffers_terrain.fsh")) {
			assertTrue(ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD).isEmpty());
		}
	}

	@Test
	void probesTheWholeRangeUpToTheOptifineCeiling(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "world0/composite15.fsh")) {
			assertEquals(List.of("composite15"),
				names(ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD)));
		}
	}

	@Test
	void mapsEachPassToItsOwnFragmentFile(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "world0/composite3.fsh")) {
			ShaderProgramChain.Pass pass =
				ShaderProgramChain.discover(pack, ShaderDimension.OVERWORLD).get(0);

			assertEquals("composite3", pass.name());
			assertEquals("composite3.fsh", pass.fragment());
		}
	}
}
