package dev.tapetum.shaders.shaderpack.glsl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link GlslIncludeResolver} against the include conventions real packs use — absolute,
 * shaders-root-relative paths ({@code #include "/lib/common.glsl"}), which every pack surveyed
 * (Complementary, Bliss, AstralCore) uses exclusively, plus the relative form and the failure modes
 * that would otherwise hang or mislead.
 */
class GlslIncludeResolverTest {

	private static Path write(Path root, String relative, String content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}

	@Test
	void expandsRootRelativeInclude(@TempDir Path root) throws Exception {
		write(root, "lib/common.glsl", "float shared = 1.0;");
		Path entry = write(root, "final.fsh", "#include \"/lib/common.glsl\"\nvoid main() {}");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertTrue(resolved.contains("float shared = 1.0;"), resolved);
		assertFalse(resolved.contains("#include"), resolved);
	}

	@Test
	void expandsIncludeRelativeToIncludingFile(@TempDir Path root) throws Exception {
		write(root, "program/helper.glsl", "float helper = 2.0;");
		Path entry = write(root, "program/final.glsl", "#include \"helper.glsl\"");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertTrue(resolved.contains("float helper = 2.0;"), resolved);
	}

	@Test
	void expandsNestedIncludesRecursively(@TempDir Path root) throws Exception {
		// Bliss' real chain is final.fsh -> /dimensions/final.fsh -> 7 further /lib includes, so a
		// single non-recursive substitution pass is not enough.
		write(root, "lib/inner.glsl", "float inner = 3.0;");
		write(root, "lib/outer.glsl", "#include \"/lib/inner.glsl\"\nfloat outer = 4.0;");
		Path entry = write(root, "final.fsh", "#include \"/lib/outer.glsl\"");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertTrue(resolved.contains("float inner = 3.0;"), resolved);
		assertTrue(resolved.contains("float outer = 4.0;"), resolved);
	}

	@Test
	void expandsSameFileTwiceWhenIncludedTwice(@TempDir Path root) throws Exception {
		// A textual preprocessor inlines every occurrence; packs rely on that for shared library code
		// pulled into several programs. This must not be mistaken for a cycle.
		write(root, "lib/util.glsl", "float util = 5.0;");
		Path entry = write(root, "final.fsh",
			"#include \"/lib/util.glsl\"\n#include \"/lib/util.glsl\"");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertEquals(2, resolved.lines().filter(line -> line.contains("float util = 5.0;")).count(), resolved);
	}

	@Test
	void expandsDiamondIncludeWithoutFalseCycle(@TempDir Path root) throws Exception {
		write(root, "lib/base.glsl", "float base = 6.0;");
		write(root, "lib/left.glsl", "#include \"/lib/base.glsl\"");
		write(root, "lib/right.glsl", "#include \"/lib/base.glsl\"");
		Path entry = write(root, "final.fsh",
			"#include \"/lib/left.glsl\"\n#include \"/lib/right.glsl\"");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertEquals(2, resolved.lines().filter(line -> line.contains("float base = 6.0;")).count(), resolved);
	}

	@Test
	void throwsOnDirectCycleRatherThanHanging(@TempDir Path root) throws Exception {
		Path entry = write(root, "loop.glsl", "#include \"/loop.glsl\"");

		GlslIncludeException thrown = assertThrows(GlslIncludeException.class,
			() -> new GlslIncludeResolver(root).resolve(Files.readString(entry), entry));

		assertTrue(thrown.getMessage().contains("cycle"), thrown.getMessage());
	}

	@Test
	void throwsOnMutualCycle(@TempDir Path root) throws Exception {
		write(root, "b.glsl", "#include \"/a.glsl\"");
		Path entry = write(root, "a.glsl", "#include \"/b.glsl\"");

		assertThrows(GlslIncludeException.class,
			() -> new GlslIncludeResolver(root).resolve(Files.readString(entry), entry));
	}

	@Test
	void throwsWithReadableMessageOnMissingInclude(@TempDir Path root) throws Exception {
		Path entry = write(root, "final.fsh", "#include \"/lib/absent.glsl\"");

		GlslIncludeException thrown = assertThrows(GlslIncludeException.class,
			() -> new GlslIncludeResolver(root).resolve(Files.readString(entry), entry));

		// The pack author needs to know which file was missing and where it was referenced from.
		assertTrue(thrown.getMessage().contains("absent.glsl"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("final.fsh"), thrown.getMessage());
	}

	@Test
	void toleratesWhitespaceBetweenHashAndInclude(@TempDir Path root) throws Exception {
		write(root, "lib/spaced.glsl", "float spaced = 7.0;");
		Path entry = write(root, "final.fsh", "#  include \"/lib/spaced.glsl\"");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertTrue(resolved.contains("float spaced = 7.0;"), resolved);
	}

	@Test
	void refusesRootRelativeIncludeEscapingThePack(@TempDir Path sandbox) throws Exception {
		// Shaderpacks are downloaded from the internet; without confinement this reads an arbitrary
		// local file and inlines it into the shader source, where it reaches the driver and the log.
		Path root = sandbox.resolve("shaders");
		Files.createDirectories(root);
		Files.writeString(sandbox.resolve("secret.txt"), "SECRET");
		Path entry = write(root, "evil.fsh", "#include \"/../secret.txt\"");

		GlslIncludeException thrown = assertThrows(GlslIncludeException.class,
			() -> new GlslIncludeResolver(root).resolve(Files.readString(entry), entry));

		assertTrue(thrown.getMessage().contains("outside the shaderpack"), thrown.getMessage());
	}

	@Test
	void refusesFileRelativeIncludeEscapingThePack(@TempDir Path sandbox) throws Exception {
		Path root = sandbox.resolve("shaders");
		Files.createDirectories(root);
		Files.writeString(sandbox.resolve("secret.txt"), "SECRET");
		Path entry = write(root, "nested/evil.fsh", "#include \"../../secret.txt\"");

		GlslIncludeException thrown = assertThrows(GlslIncludeException.class,
			() -> new GlslIncludeResolver(root).resolve(Files.readString(entry), entry));

		assertTrue(thrown.getMessage().contains("outside the shaderpack"), thrown.getMessage());
	}

	@Test
	void allowsTraversalThatStaysInsideThePack(@TempDir Path root) throws Exception {
		// Confinement must not break legitimate relative paths that dip through a parent directory.
		write(root, "lib/shared.glsl", "float shared = 1.0;");
		Path entry = write(root, "program/final.glsl", "#include \"../lib/shared.glsl\"");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertTrue(resolved.contains("float shared = 1.0;"), resolved);
	}

	@Test
	void refusesExponentiallyExpandingIncludeGraph(@TempDir Path root) throws Exception {
		// Each level includes the level below twice, so depth alone stays small while the expansion
		// doubles per level - the cycle check never fires and memory is what runs out.
		write(root, "lib/leaf.glsl", "float leaf = 1.0;");
		for (int level = 1; level <= 24; level++) {
			String child = level == 1 ? "/lib/leaf.glsl" : "/lib/bomb" + (level - 1) + ".glsl";
			write(root, "lib/bomb" + level + ".glsl",
				"#include \"" + child + "\"\n#include \"" + child + "\"");
		}
		Path entry = write(root, "final.fsh", "#include \"/lib/bomb24.glsl\"");

		GlslIncludeException thrown = assertThrows(GlslIncludeException.class,
			() -> new GlslIncludeResolver(root).resolve(Files.readString(entry), entry));

		assertTrue(thrown.getMessage().contains("expansion exceeded"), thrown.getMessage());
	}

	@Test
	void leavesNonIncludeDirectivesAlone(@TempDir Path root) throws Exception {
		Path entry = write(root, "final.fsh", "#version 130\n#define OVERWORLD\n#ifdef FSH\n#endif");

		String resolved = new GlslIncludeResolver(root).resolve(Files.readString(entry), entry);

		assertTrue(resolved.contains("#version 130"), resolved);
		assertTrue(resolved.contains("#define OVERWORLD"), resolved);
		assertTrue(resolved.contains("#ifdef FSH"), resolved);
	}
}
