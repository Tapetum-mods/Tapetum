package dev.tapetum.shaders.pipeline.backend;

import com.mojang.blaze3d.systems.GpuDevice;
import dev.tapetum.shaders.compat.VersionCompat;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Locale;
import java.util.Optional;

/**
 * Which GPU backend Blaze3D is actually running on, as reported by the device (through
 * {@link VersionCompat#backendName}, since where that name lives moved between Minecraft versions).
 * Only {@link #OPENGL} is reachable in practice today (see the package docs for why), but reading
 * this instead of hardcoding OpenGL assumptions is what lets the pipeline pick up a future
 * {@link #VULKAN} backend without a rewrite once Mojang ships one.
 */
public enum GpuBackendType {
	OPENGL,
	VULKAN,
	UNKNOWN;

	/**
	 * Returns the active backend, or empty if called before Blaze3D has set up a {@link GpuDevice}
	 * (e.g. during mod init, which runs before the render device exists).
	 */
	public static Optional<GpuBackendType> detectActive() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return Optional.empty();
		}
		return Optional.of(fromBackendName(VersionCompat.backendName(device)));
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
