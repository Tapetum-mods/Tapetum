package dev.tapetum.shaders.shaderpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Discovers shaderpacks (zip files or directories) inside the {@code shaderpacks} folder and loads
 * them on demand. A pack is only considered valid if it (or exactly one of its immediate
 * subdirectories, to tolerate a wrapping folder from a zip export) contains a {@code shaders}
 * directory, matching the OptiFine/Iris shaderpack layout.
 */
public class ShaderpackManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("Tapetum Shaders");

	private final Path shaderpacksDirectory;
	private final List<String> availablePacks = new ArrayList<>();

	public ShaderpackManager(Path shaderpacksDirectory) {
		this.shaderpacksDirectory = shaderpacksDirectory;
	}

	/**
	 * Rescans the shaderpacks directory. Should be called on startup and whenever the shaderpack
	 * selection screen is opened, since packs may have been dropped in externally.
	 */
	public void refresh() {
		try {
			Files.createDirectories(shaderpacksDirectory);
		} catch (IOException e) {
			LOGGER.error("Failed to create shaderpacks directory at {}", shaderpacksDirectory, e);
			return;
		}

		List<String> found = new ArrayList<>();

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(shaderpacksDirectory)) {
			for (Path entry : stream) {
				String fileName = entry.getFileName().toString();

				if (Files.isDirectory(entry)) {
					if (locateShaderRoot(entry).isPresent()) {
						found.add(fileName);
					}
				} else if (fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
					// Validated lazily on load() rather than opening a filesystem for every zip here.
					found.add(fileName);
				}
			}
		} catch (IOException e) {
			LOGGER.error("Failed to scan shaderpacks directory at {}", shaderpacksDirectory, e);
		}

		found.sort(String.CASE_INSENSITIVE_ORDER);

		availablePacks.clear();
		availablePacks.addAll(found);
	}

	public List<String> getAvailablePacks() {
		return Collections.unmodifiableList(availablePacks);
	}

	/**
	 * Loads a pack by its file name (as returned by {@link #getAvailablePacks()}). The caller is
	 * responsible for closing the returned pack once done with it, since zip-backed packs hold a
	 * filesystem handle open.
	 */
	public Optional<ShaderPack> load(String packName) {
		if (!isSafePackName(packName)) {
			LOGGER.warn("Refusing to load shaderpack '{}': the name is not a plain entry inside the "
				+ "shaderpacks folder", packName);
			return Optional.empty();
		}

		Path entry = shaderpacksDirectory.resolve(packName);

		if (!Files.exists(entry)) {
			LOGGER.warn("Requested shaderpack '{}' no longer exists", packName);
			return Optional.empty();
		}

		try {
			if (Files.isDirectory(entry)) {
				Optional<Path> shaderRoot = locateShaderRoot(entry);
				if (shaderRoot.isEmpty()) {
					LOGGER.warn("'{}' does not contain a shaders directory", packName);
					return Optional.empty();
				}
				return Optional.of(new ShaderPack(packName, entry, shaderRoot.get(), null));
			}

			// A zip filesystem is keyed by path, so a second open of the same pack throws - and
			// FileSystemAlreadyExistsException is a RuntimeException, so it slips straight past the
			// IOException handler below and out of the reload as an unhandled crash. It happens for
			// real: any earlier load that failed after opening the filesystem leaves it registered.
			FileSystem zipFs;
			boolean ownsFileSystem;
			try {
				zipFs = FileSystems.newFileSystem(entry, (ClassLoader) null);
				ownsFileSystem = true;
			} catch (FileSystemAlreadyExistsException e) {
				zipFs = FileSystems.getFileSystem(entry.toUri());
				// Someone else registered it, so closing it here would pull the rug from under them.
				ownsFileSystem = false;
				LOGGER.debug("Reusing the already-open filesystem for '{}'", packName);
			}

			boolean opened = false;
			try {
				Optional<Path> shaderRoot = locateShaderRoot(zipFs.getPath("/"));
				if (shaderRoot.isEmpty()) {
					LOGGER.warn("'{}' does not contain a shaders directory", packName);
					return Optional.empty();
				}
				ShaderPack pack = new ShaderPack(packName, entry, shaderRoot.get(),
					ownsFileSystem ? zipFs : null);
				opened = true;
				return Optional.of(pack);
			} finally {
				// Only close what we opened, and only when the pack that would have taken ownership
				// was never built.
				if (!opened && ownsFileSystem) {
					zipFs.close();
				}
			}
		} catch (IOException e) {
			LOGGER.error("Failed to load shaderpack '{}'", packName, e);
			return Optional.empty();
		}
	}

	/**
	 * Whether {@code packName} names a plain entry directly inside the shaderpacks folder.
	 *
	 * <p>The name reaches here from the persisted config as well as from the picker, so it is not
	 * guaranteed to be one of the names {@link #refresh()} discovered — a hand-edited (or
	 * mod-written) {@code shaderPack=../../..} entry would otherwise make {@code resolve} walk out of
	 * the shaderpacks folder and open something else entirely.</p>
	 */
	private static boolean isSafePackName(String packName) {
		if (packName == null || packName.isBlank()) {
			return false;
		}

		// Reject both separators outright rather than only the platform's: a Windows-style name
		// reaching a Unix JVM (from a synced config, say) must not slip through as a plain name.
		if (packName.indexOf('/') >= 0 || packName.indexOf('\\') >= 0) {
			return false;
		}

		return !packName.equals(".") && !packName.equals("..");
	}

	private static Optional<Path> locateShaderRoot(Path packRoot) throws IOException {
		Path direct = packRoot.resolve("shaders");
		if (Files.isDirectory(direct)) {
			return Optional.of(direct);
		}

		Path onlyCandidate = null;

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(packRoot, Files::isDirectory)) {
			for (Path candidate : stream) {
				Path nested = candidate.resolve("shaders");
				if (Files.isDirectory(nested)) {
					if (onlyCandidate != null) {
						// Ambiguous: more than one wrapping folder contains a shaders directory.
						return Optional.empty();
					}
					onlyCandidate = nested;
				}
			}
		}

		return Optional.ofNullable(onlyCandidate);
	}
}
