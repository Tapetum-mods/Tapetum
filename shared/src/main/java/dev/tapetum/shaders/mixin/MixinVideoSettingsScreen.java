package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.gui.ShaderPackScreen;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/**
 * Adds a "Shaderpacks..." entry to the vanilla Video Settings screen, the same entry point both
 * Iris and Sodium use, so shader controls are discoverable from where players already look for
 * graphics settings instead of only from a keybind.
 *
 * <p>{@code addOptions()} builds its whole options array in one shot and hands it straight to
 * {@code OptionsList.addSmall(OptionInstance...)}, so the only way to add an entry without
 * duplicating that method is to intercept the array on its way in. {@code OptionInstance} itself
 * has no "just a button" constructor — every option is a value with a caption function — so this
 * fakes one: a boolean value nothing ever reads, whose caption function returns its own label
 * unchanged regardless of the (irrelevant) value, and whose change callback opens the screen.</p>
 */
@Mixin(VideoSettingsScreen.class)
public abstract class MixinVideoSettingsScreen extends Screen {
	protected MixinVideoSettingsScreen(Component title) {
		super(title);
	}

	@ModifyArg(
		method = "addOptions",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/OptionInstance;)V"),
		index = 0
	)
	private OptionInstance<?>[] tapetum$appendShaderpacksEntry(OptionInstance<?>[] vanillaOptions) {
		OptionInstance<Boolean> openShaderpacks = new OptionInstance<>(
			"options.tapetumshaders.shaderpacks",
			OptionInstance.noTooltip(),
			(label, ignoredValue) -> label,
			OptionInstance.BOOLEAN_VALUES,
			Boolean.TRUE,
			ignoredValue -> this.minecraft.setScreenAndShow(new ShaderPackScreen(this))
		);

		OptionInstance<?>[] withShaderpacksEntry = Arrays.copyOf(vanillaOptions, vanillaOptions.length + 1);
		withShaderpacksEntry[vanillaOptions.length] = openShaderpacks;
		return withShaderpacksEntry;
	}
}
