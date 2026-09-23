package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.gui.TransparentWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSliderButton.class)
public abstract class MixinTransparentSlider {
    @Shadow protected double value;

    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void tapetum$paint(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (TransparentWidgets.paint((AbstractWidget) (Object) this, graphics, value)) ci.cancel();
    }
}
