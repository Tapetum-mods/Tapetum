package dev.tapetum.shaders.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers per-dimension program lookup. This is not a corner case: current Complementary Unbound
 * (r5.9) ships {@code world0/final.fsh}, {@code world-1/final.fsh} and {@code world1/final.fsh} with
 * <em>no</em> {@code final.fsh} at the shaders root, so a root-only search finds nothing and falls
 * silently back to vanilla rendering — which is what it did before this existed.
 */
class ShaderPackDimensionTest {

	private static ShaderPack packWith(Path root, String... relativeFiles) throws IOException {
		Path shaders = root.resolve("shaders");
		Files.createDirectories(shaders);
		for (String relative : relativeFiles) {
			Path file = shaders.resolve(relative);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "// " + relative + "\nvoid main() {}\n");
		}
		return new ShaderPack("test-pack", root, shaders, null);
	}

	@Test
	void findsProgramInDimensionFolderWhenRootHasNone() throws Exception {
		Path root = Files.createTempDirectory("pack");
		try (ShaderPack pack = packWith(root, "world0/final.fsh")) {
			Optional<Path> located = pack.locateProgram("final.fsh", ShaderDimension.OVERWORLD);

			assertTrue(located.isPresent(), "world0/final.fsh should have been found");
			assertEquals("final.fsh", located.get().getFileName().toString());
			assertEquals("world0", located.get().getParent().getFileName().toString());
		}
	}

	@Test
	void prefersDimensionFolderOverRoot(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "final.fsh", "world-1/final.fsh")) {
			Optional<Path> located = pack.locateProgram("final.fsh", ShaderDimension.NETHER);

			assertTrue(located.isPresent());
			assertEquals("world-1", located.get().getParent().getFileName().toString());
		}
	}

	@Test
	void fallsBackToRootWhenDimensionFolderHasNoCopy(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "final.fsh")) {
			Optional<Path> located = pack.locateProgram("final.fsh", ShaderDimension.END);

			assertTrue(located.isPresent());
			assertEquals("shaders", located.get().getParent().getFileName().toString());
		}
	}

	@Test
	void reportsAbsentWhenNeitherLocationHasIt(@TempDir Path root) throws Exception {
		try (ShaderPack pack = packWith(root, "world0/composite.fsh")) {
			assertTrue(pack.locateProgram("final.fsh", ShaderDimension.OVERWORLD).isEmpty());
		}
	}

	@Test
	void rootRelativeIncludeFromDimensionFolderResolvesAgainstShadersRoot(@TempDir Path root) throws Exception {
		// The trap: "/program/final.glsl" inside world0/final.fsh means shaders/program/final.glsl,
		// NOT shaders/world0/program/final.glsl. Complementary's entry files are exactly this shape.
		Path shaders = root.resolve("shaders");
		Files.createDirectories(shaders.resolve("world0"));
		Files.createDirectories(shaders.resolve("program"));
		Files.writeString(shaders.resolve("program/final.glsl"), "float fromSharedProgram = 1.0;");
		Files.writeString(shaders.resolve("world0/final.fsh"),
			"#version 130\n#include \"/program/final.glsl\"\nvoid main() { gl_FragColor = vec4(fromSharedProgram); }");

		try (ShaderPack pack = new ShaderPack("test-pack", root, shaders, null)) {
			Optional<String> source = pack.readCompilableProgramSource(
				"final.fsh", dev.tapetum.shaders.shaderpack.glsl.GlslCompatPatcher.Stage.FRAGMENT,
				ShaderDimension.OVERWORLD, null);

			assertTrue(source.isPresent());
			assertTrue(source.get().contains("float fromSharedProgram = 1.0;"), source.get());
		}
	}

	@Test
	void everyDimensionMapsToItsOptifineFolderName() {
		// The folder names come from the vanilla dimension ids the format predates; getting one wrong
		// means silently reading the wrong dimension's programs.
		assertEquals("world0", ShaderDimension.OVERWORLD.folderName());
		assertEquals("world-1", ShaderDimension.NETHER.folderName());
		assertEquals("world1", ShaderDimension.END.folderName());
	}
}
