package dev.tapetum.shaders.mixin;

import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ShaderPackScreen.class, remap = false)
public interface IrisShaderPackScreenAccess {
    @Accessor("optionMenuOpen")
    void tapetum$openOptions(boolean value);
}
