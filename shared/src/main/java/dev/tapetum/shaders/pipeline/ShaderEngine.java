package dev.tapetum.shaders.pipeline;

import java.io.IOException;
import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;

/**
 * Engine boundary for Tapetum's shaderpack runtime.
 *
 * <p>The renderer and UI depend on this contract, never on a third-party engine API. The current
 * Iris adapter is a temporary compatibility backend; the native Tapetum engine will implement the
 * same contract as its geometry, shadow and post-processing stages become production-ready.</p>
 */
public interface ShaderEngine extends RenderingPipeline {
	String name();

	void reload() throws IOException;

	void syncSelection();

	Optional<Throwable> lastFailure();

	Optional<Screen> openPackOptions(Screen parent);
}