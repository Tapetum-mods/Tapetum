package dev.tapetum.shaders.pipeline;

/**
 * The extension point that a real shaderpack-driven rendering pipeline will implement: compiling
 * gbuffers/composite/deferred/shadow programs from a {@link dev.tapetum.shaders.shaderpack.ShaderPack}
 * and swapping the GPU state at the right points during level rendering.
 *
 * <p>This is intentionally a small subset of what such a pipeline needs (Iris' equivalent
 * interface has several dozen methods covering shadow distance, cloud settings, particle
 * rendering, uniforms, texture overrides, etc.). Right now the only implementation is
 * {@link VanillaRenderingPipeline}, a no-op used whenever shaders are disabled or no pack is
 * selected. Building an implementation that actually compiles and runs GLSL programs is the next
 * milestone for this project, not something this skeleton attempts.</p>
 */
public interface RenderingPipeline {
	/**
	 * Called from {@link dev.tapetum.shaders.mixin.MixinLevelRenderer} right before level rendering
	 * begins for this frame.
	 */
	void beginLevelRendering();

	/**
	 * Called once level rendering has finished for this frame.
	 */
	void finalizeLevelRendering();

	/**
	 * Whether this pipeline is actually overriding vanilla rendering with shaderpack-driven GLSL
	 * programs, as opposed to being a pass-through.
	 */
	boolean isShaderPackActive();

	/**
	 * Releases any GPU resources (programs, render targets) held by this pipeline.
	 */
	void destroy();
}
