package dev.tapetum.shaders;

import dev.tapetum.shaders.config.TapetumConfig;
import dev.tapetum.shaders.pipeline.PipelineManager;
import dev.tapetum.shaders.shaderpack.ShaderpackManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entrypoint shared between the client and (eventually) server side. Owns the long-lived
 * singletons that the rest of the mod reaches through static accessors, mirroring how Iris
 * exposes its {@code Iris} class.
 */
public class TapetumShaders implements ModInitializer {
	public static final String MOD_ID = "tapetumshaders";
	public static final Logger LOGGER = LoggerFactory.getLogger("Tapetum Shaders");

	private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();
	private static final Path SHADERPACKS_DIR = GAME_DIR.resolve("shaderpacks");
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("tapetumshaders.properties");

	private static TapetumConfig config;
	private static ShaderpackManager shaderpackManager;
	private static PipelineManager pipelineManager;

	@Override
	public void onInitialize() {
		config = new TapetumConfig(CONFIG_PATH);

		try {
			config.load();
		} catch (IOException e) {
			LOGGER.error("Failed to load configuration from {}, falling back to defaults", CONFIG_PATH, e);
		}

		shaderpackManager = new ShaderpackManager(SHADERPACKS_DIR);
		shaderpackManager.refresh();

		pipelineManager = new PipelineManager();

		LOGGER.info("Tapetum Shaders initialized. Found {} shaderpack(s) in {}",
			shaderpackManager.getAvailablePacks().size(), SHADERPACKS_DIR);
	}

	public static Path getShaderpacksDirectory() {
		return SHADERPACKS_DIR;
	}

	public static TapetumConfig getConfig() {
		return config;
	}

	public static ShaderpackManager getShaderpackManager() {
		return shaderpackManager;
	}

	public static PipelineManager getPipelineManager() {
		return pipelineManager;
	}

	/**
	 * Persists whatever was just changed on {@link #getConfig()} and re-evaluates the active
	 * pipeline to match. Shared by the shaderpack screen's buttons and the in-game keybinds so both
	 * apply config changes the same way.
	 */
	public static boolean saveConfigAndReload() {
		try {
			config.save();
		} catch (IOException e) {
			LOGGER.error("Failed to save Tapetum Shaders config", e);
			return false;
		}

		pipelineManager.reload();
		return !config.areShadersEnabled() || pipelineManager.getPipeline().isShaderPackActive();
	}
}
