package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.gui.ShaderPackScreen;
import net.minecraft.client.CycleOption;
import net.minecraft.client.Option;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.Arrays;

/** Adds one entry to the existing scrollable 1.16.5 video options list. */
@Mixin(VideoSettingsScreen.class)
public abstract class MixinVideoSettingsScreen extends Screen {
    protected MixinVideoSettingsScreen(Component title) { super(title); }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/Option;)V"), index = 0)
    private Option[] tapetum$appendShaderpacksEntry(Option[] vanillaOptions) {
        Option entry = new CycleOption("options.tapetumshaders.shaderpacks",
            (options, ignored) -> this.minecraft.setScreen(new ShaderPackScreen(this)),
            (options, ignored) -> new TranslatableComponent("options.tapetumshaders.shaderpacks"));
        Option[] result = Arrays.copyOf(vanillaOptions, vanillaOptions.length + 1);
        result[vanillaOptions.length] = entry;
        return result;
    }
}
