/**
 * Backend detection for the native Tapetum renderer.
 *
 * <p>Minecraft 26.3 moved its GPU types into RenderPearl and supports both OpenGL and Vulkan.
 * Tapetum currently implements only the OpenGL path. Version-specific device and texture access
 * lives in VersionCompat; the GL state-manager alias preserves Mojang's state cache across the
 * package relocation. It does not emulate OpenGL on a Vulkan device.
 *
 * <p>Use {@link dev.tapetum.shaders.pipeline.backend.GpuBackendType} to check the active device
 * before allocating targets or compiling GLSL. A backend without an implementation must yield
 * an explicit activation failure, not a claim that a shaderpack rendered successfully.
 */
package dev.tapetum.shaders.pipeline.backend;
