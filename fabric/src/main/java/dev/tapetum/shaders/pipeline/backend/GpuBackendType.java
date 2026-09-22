package dev.tapetum.shaders.pipeline.backend;

import dev.tapetum.shaders.compat.VersionCompat;

import java.util.Locale;
import java.util.Optional;

/**
 * Which GPU backend Blaze3D is actually running on, as reported by the device (through
 * {@link VersionCompat#backendName}, since where that name lives moved between Minecraft versions).
 * Minecraft 26.3 can use Vulkan, but Tapetum's current pipeline only implements OpenGL.
 * Detecting the active device lets the UI reject an unsupported backend explicitly.
 */
public enum GpuBackendType {
	OPENGL,
	VULKAN,
	UNKNOWN;

	/**
	 * Returns the active backend, or empty if called before Minecraft has set up its device
	 * (e.g. during mod init, which runs before the render device exists).
	 */
	public static Optional<GpuBackendType> detectActive() {
		String backend = VersionCompat.backendName();
		if (backend == null) {
			return Optional.empty();
		}
		return Optional.of(fromBackendName(backend));
	}

	static GpuBackendType fromBackendName(String backendName) {
		if (backendName == null) {
			return UNKNOWN;
		}

		String normalized = backendName.toLowerCase(Locale.ROOT);

		if (normalized.contains("vulkan")) {
			return VULKAN;
		}
		if (normalized.contains("opengl")) {
			return OPENGL;
		}
		return UNKNOWN;
	}
}
