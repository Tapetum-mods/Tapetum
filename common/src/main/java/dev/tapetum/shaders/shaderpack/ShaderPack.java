package dev.tapetum.shaders.shaderpack;

import dev.tapetum.shaders.shaderpack.glsl.GlslCompatPatcher;
import dev.tapetum.shaders.shaderpack.glsl.GlslIncludeException;
import dev.tapetum.shaders.shaderpack.glsl.GlslIncludeResolver;
import dev.tapetum.shaders.shaderpack.glsl.ShaderMacros;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A shaderpack that has been located on disk (as a directory) or inside a zip file, with its
 * {@code shaders/} directory resolved and its {@code shaders.properties} parsed. Individual program
 * source files (e.g. {@code final.fsh}) are read on demand via {@link #readProgramSource}.
 */
public class ShaderPack implements AutoCloseable {
	private final String name;
	private final Path root;
	private final Path shaderRoot;
	private final ShaderProperties properties;
	private final String propertiesText;
	private ShaderPackOptions options;
	private java.util.Map<String, String> optionValues = java.util.Map.of();
	/** Null for a directory-backed pack, which has no filesystem handle to release. */
	private final Closeable closeHandle;

	ShaderPack(String name, Path root, Path shaderRoot, Closeable closeHandle) throws IOException {
		this.name = name;
		this.root = root;
		this.shaderRoot = shaderRoot;
		this.closeHandle = closeHandle;

		Path propertiesFile = shaderRoot.resolve("shaders.properties");
		boolean hasProperties = Files.exists(propertiesFile);
		this.properties = hasProperties ? ShaderProperties.parse(propertiesFile) : ShaderProperties.empty();
		// Kept as text as well as parsed. The custom-uniform definitions need declaration order and
		// duplicate keys, and java.util.Properties discards both - see CustomUniforms.
		this.propertiesText = hasProperties ? Files.readString(propertiesFile) : "";
	}

	public String getName() {
		return name;
	}

	public Path getRoot() {
		return root;
	}

	public Path getShaderRoot() {
		return shaderRoot;
	}

	/**
	 * The raw {@code shaders.properties} text.
	 *
	 * <p>Needed alongside the parsed form because the custom-uniform definitions depend on
	 * declaration order and on later duplicates overriding earlier ones, neither of which survives
	 * {@link java.util.Properties}.</p>
	 */
	public String getPropertiesText() {
		return propertiesText;
	}

	public ShaderProperties getProperties() {
		return properties;
	}

	public ShaderPackOptions getOptions() throws IOException {
		if (options != null) return options;
		var sources = new java.util.ArrayList<String>();
		long total = 0;
		Path realRoot = shaderRoot.toRealPath();
		try (var files = Files.walk(shaderRoot, 32)) {
			var iterator = files.filter(path -> Files.isRegularFile(path)
				&& path.getFileName().toString().matches(".*\\.(vsh|fsh|gsh|csh|glsl)")).iterator();
			while (iterator.hasNext()) {
				Path file = iterator.next();
				if (!file.toRealPath().startsWith(realRoot)) throw new IOException("Shader source escapes pack root");
				if (sources.size() >= 4096) throw new IOException("Too many shader sources");
				try (var input = Files.newInputStream(file)) {
					byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
					total += bytes.length;
					if (bytes.length > 2 * 1024 * 1024 || total > 64 * 1024 * 1024)
						throw new IOException("Shader option source limit exceeded");
					sources.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
				}
			}
		}
		options = ShaderPackOptions.parse(sources);
		return options;
	}

	public void setOptionValues(java.util.Map<String, String> values) throws IOException {
		optionValues = values.isEmpty() ? java.util.Map.of() : getOptions().validate(values);
	}

	/**
	 * Reads a program source file (e.g. {@code "final.fsh"}) relative to {@link #getShaderRoot()},
	 * or empty if the pack doesn't have one.
	 *
	 * <p>This is the raw file as shipped: {@code #include} directives are still unexpanded and the
	 * source is still compatibility-profile GLSL. Use {@link #readCompilableProgramSource} for
	 * something a driver will actually accept.</p>
	 */
	public Optional<String> readProgramSource(String fileName) throws IOException {
		Path file = shaderRoot.resolve(fileName);
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		return Optional.of(Files.readString(file));
	}

	/**
	 * Reads a program source file and prepares it for the driver: expands its {@code #include}
	 * chain, then rewrites the compatibility-profile constructs core OpenGL removed.
	 *
	 * <p>Both steps are mandatory for real packs and neither is optional polish. A pack's entry file
	 * is typically a stub — Complementary's {@code final.fsh} is six lines whose body is one
	 * {@code #include}, and Bliss' is two — so without include expansion there is essentially no
	 * shader to compile; and every pack surveyed writes {@code varying}/{@code texture2D}/
	 * {@code gl_FragColor}, none of which exist in core profile.</p>
	 *
	 * @param fileName  program file name, e.g. {@code "final.fsh"} — located via
	 *                  {@link #locateProgram}, so a per-dimension copy wins over a root one
	 * @param stage     which shader stage this source is for — decides whether {@code varying}
	 *                  becomes {@code in} or {@code out}
	 * @param dimension which dimension's programs to prefer
	 * @param macros    the OptiFine macro set to define for the pack ({@code MC_VERSION} and
	 *                  friends); may be null, but see {@link ShaderMacros} — real packs fail to
	 *                  compile on strict drivers without them
	 */
	public Optional<String> readCompilableProgramSource(String fileName, GlslCompatPatcher.Stage stage,
			ShaderDimension dimension, ShaderMacros macros) throws IOException, GlslIncludeException {
		Optional<Path> located = locateProgram(fileName, dimension);
		if (located.isEmpty()) {
			return Optional.empty();
		}

		Path file = located.get();
		// The resolver stays rooted at the pack's shaders/ directory even when the program itself
		// lives in a world<id>/ subfolder: a pack's "/program/final.glsl" means shaders/program/...,
		// not shaders/world0/program/..., and resolving it relative to the program would break it.
		String expanded = new GlslIncludeResolver(shaderRoot).resolve(Files.readString(file), file);
		if (!optionValues.isEmpty()) expanded = getOptions().apply(expanded, optionValues);
		return Optional.of(GlslCompatPatcher.patch(expanded, stage, macros));
	}

	/**
	 * Finds a program file, honouring the per-dimension folders OptiFine-format packs use.
	 *
	 * <p>Packs may ship a program either at the shaders root or inside a {@code world<id>} folder,
	 * with the dimension-specific copy taking priority. This is not an edge case: current
	 * Complementary Unbound ships <em>only</em> {@code world0/final.fsh}, {@code world-1/final.fsh}
	 * and {@code world1/final.fsh}, with no {@code final.fsh} at the root at all — looking only at
	 * the root finds nothing and silently falls back to vanilla rendering, which is exactly what it
	 * used to do.</p>
	 */
	public Optional<Path> locateProgram(String fileName, ShaderDimension dimension) {
		Path dimensionSpecific = shaderRoot.resolve(dimension.folderName()).resolve(fileName);
		if (Files.isRegularFile(dimensionSpecific)) {
			return Optional.of(dimensionSpecific);
		}

		Path atRoot = shaderRoot.resolve(fileName);
		return Files.isRegularFile(atRoot) ? Optional.of(atRoot) : Optional.empty();
	}

	/**
	 * Releases the backing zip filesystem, if this pack was loaded from a zip file. Directory-based
	 * packs have nothing to release.
	 *
	 * <p>Declared to throw {@link IOException} rather than the {@code Exception} that
	 * {@link AutoCloseable} permits: the only thing ever closed here is a {@link java.nio.file.FileSystem},
	 * which throws nothing wider. The wider signature would drag {@code InterruptedException} into
	 * every try-with-resources over a pack, where it is liable to be swallowed along with the
	 * thread's interrupted status.</p>
	 */
	@Override
	public void close() throws IOException {
		if (closeHandle != null) {
			closeHandle.close();
		}
	}
}
