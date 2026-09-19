package dev.tapetum.shaders.pipeline;

/**
 * The pipeline used whenever no shaderpack should affect rendering: shaders disabled in config,
 * no pack selected, or a pack is selected but this build has no real GLSL pipeline to run it yet.
 * Every hook is a no-op, leaving Minecraft's own rendering completely untouched.
 */
public final class VanillaRenderingPipeline implements RenderingPipeline {
	public static final VanillaRenderingPipeline INSTANCE = new VanillaRenderingPipeline();

	private VanillaRenderingPipeline() {
	}

	@Override
	public void beginLevelRendering() {
	}

	@Override
	public void finalizeLevelRendering() {
	}

	@Override
	public boolean isShaderPackActive() {
		return false;
	}

	@Override
	public void destroy() {
	}
}
