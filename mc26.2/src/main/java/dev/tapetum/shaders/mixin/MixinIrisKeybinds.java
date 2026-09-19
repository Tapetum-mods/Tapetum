package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.gui.ShaderPackScreen;
import net.irisshaders.iris.Iris;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Minecraft 26.2 moved setScreen to Gui. */
@Mixin(value = Iris.class, remap = false)
public abstract class MixinIrisKeybinds {
    @ModifyArg(method = "handleKeybinds", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"), index = 0)
    private static Screen tapetum$openSelector(Screen original) {
        return new ShaderPackScreen(null);
    }
}
