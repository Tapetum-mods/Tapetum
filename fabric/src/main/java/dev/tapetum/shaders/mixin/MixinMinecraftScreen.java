package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.gui.TapetumVideoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class MixinMinecraftScreen {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen tapetum$videoSettings(Screen screen) {
        // Leave screens provided by other mods alone, including our own subclass.
        if (screen != null && screen.getClass() == VideoSettingsScreen.class) {
            Minecraft client = Minecraft.getInstance();
            return new TapetumVideoSettingsScreen(((AccessorOptionsSubScreen) screen).tapetum$parent(), client);
        }
        return screen;
    }
}
