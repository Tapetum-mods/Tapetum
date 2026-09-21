// Historical integration, excluded from all production and test source sets.
package dev.tapetum.shaders.compat.sodium;

import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.gui.ShaderPackScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds a "Tapetum Shaders" section to Sodium's options screen, the same way Iris does — a sidebar
 * entry with a page that opens {@link ShaderPackScreen} and a settings page carrying the
 * shaders on/off toggle.
 *
 * <p>This class is referenced only from the {@code sodium:config_api_user} entrypoint in
 * {@code fabric.mod.json}, which nothing but Sodium itself ever invokes, so its
 * {@code net.caffeinemc} types resolve only when Sodium is present.</p>
 *
 * <p>Sodium is a required dependency, so that isolation is no longer what prevents a
 * {@code NoClassDefFoundError} — Fabric Loader refuses to launch without Sodium long before this
 * class could load. The isolation is kept regardless: it costs nothing, and it keeps the boundary
 * explicit about which package may name Sodium types. Nothing outside this package may reference
 * it.</p>
 */
public class SodiumConfigIntegration implements ConfigEntryPoint {
	/** Teal, for the "tapetum lucidum" eyeshine the mod is named after. */
	private static final int THEME_COLOR = 0xFF4ECDC4;

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		builder.registerOwnModOptions()
			.setName("Tapetum Shaders")
			.setVersion(modVersion())
			.setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME_COLOR))
			.addPage(builder.createExternalPage()
				.setName(Component.translatable("tapetumshaders.gui.title"))
				.setScreenConsumer(parent -> Minecraft.getInstance().setScreenAndShow(new ShaderPackScreen(parent))))
			.addPage(builder.createOptionPage()
				.setName(Component.translatable("options.tapetumshaders.settings"))
				.addOptionGroup(builder.createOptionGroup()
					.addOption(builder.createBooleanOption(
							Identifier.fromNamespaceAndPath(TapetumShaders.MOD_ID, "enable_shaders"))
						.setName(Component.translatable("options.tapetumshaders.enable_shaders"))
						.setTooltip(Component.translatable("options.tapetumshaders.enable_shaders.tooltip"))
						.setDefaultValue(Boolean.TRUE)
						.setBinding(
							enabled -> TapetumShaders.getConfig().setShadersEnabled(enabled),
							() -> TapetumShaders.getConfig().areShadersEnabled())
						// Sodium applies the binding first and only then fires this, so persisting
						// and rebuilding the pipeline here picks up the value just written.
						.setStorageHandler(TapetumShaders::saveConfigAndReload))));
	}

	private static String modVersion() {
		return FabricLoader.getInstance()
			.getModContainer(TapetumShaders.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("");
	}
}
