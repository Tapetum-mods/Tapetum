package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.gui.ShaderPackScreen;
import net.minecraft.client.Option;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.Arrays;

/** Adds one action to the existing scrollable video options list. */
@Mixin(VideoSettingsScreen.class)
public abstract class MixinVideoSettingsScreen extends Screen {
    protected MixinVideoSettingsScreen(Component title) { super(title); }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/OptionsList;addSmall([Lnet/minecraft/client/Option;)V"), index = 0)
    private Option[] tapetum$appendShaderpacksEntry(Option[] vanillaOptions) {
        Screen parent = this;
        Option entry = new Option("options.tapetumshaders.shaderpacks") {
            @Override public AbstractWidget createButton(Options options, int x, int y, int width) {
                return new Button(x, y, width, 20, new TranslatableComponent("options.tapetumshaders.shaderpacks"),
                    button -> minecraft.setScreen(new ShaderPackScreen(parent)));
            }
        };
        Option[] result = Arrays.copyOf(vanillaOptions, vanillaOptions.length + 1);
        result[vanillaOptions.length] = entry;
        return result;
    }
}
