/**
 * Backend detection, and the seam future shaderpack-rendering code in this project must respect.
 *
 * <p>Minecraft's Blaze3D layer ({@code com.mojang.blaze3d.systems}) exposes GPU access through
 * backend-agnostic types — {@code GpuDevice}, {@code CommandEncoder}, {@code RenderPass},
 * {@code GpuTexture}, {@code GpuBuffer} — with exactly one implementation shipped as of Minecraft
 * 26.1.2: {@code GlBackend} (OpenGL). There is no Vulkan backend in vanilla Minecraft yet, and Iris
 * itself doesn't attempt fully backend-neutral shaderpack rendering either: OptiFine-format packs
 * need low-level control (arbitrary multi-target framebuffers, image load/store, SSBOs, custom
 * per-buffer blend equations) that Blaze3D's generic API doesn't expose, so Iris maintains its own
 * OpenGL-specific {@code iris.gl} layer underneath the abstract one.
 *
 * <p>Tapetum Shaders' real rendering pipeline (not built yet — see {@code pipeline.RenderingPipeline})
 * should follow the same split: use Blaze3D's abstract types for anything they cover, and put
 * everything they don't behind a small internal backend interface in this package — one
 * implementation for OpenGL now, with room for a second implementation if and when Mojang ships a
 * real {@code GpuBackend} for Vulkan. Use {@link dev.tapetum.shaders.pipeline.backend.GpuBackendType}
 * to find out which backend is actually active instead of assuming OpenGL.
 */
package dev.tapetum.shaders.pipeline.backend;
