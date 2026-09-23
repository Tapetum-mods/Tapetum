package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.gui.ShaderPackScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Share the footer with the native Done action without replacing its cleanup callback. */
@Mixin(VideoSettingsScreen.class)
public abstract class MixinVideoSettingsScreen extends Screen {
    protected MixinVideoSettingsScreen(Component title) { super(title); }

    @ModifyArgs(method = "init", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/gui/components/Button$Builder;bounds(IIII)Lnet/minecraft/client/gui/components/Button$Builder;"))
    private void tapetum$makeFooterSpace(Args args) {
        args.set(0, width / 2 + 5);
        args.set(2, Math.max(1, Math.min(150, width / 2 - 15)));
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void tapetum$addShaderpacks(CallbackInfo ci) {
        int buttonWidth = Math.max(1, Math.min(150, width / 2 - 15));
        addRenderableWidget(Button.builder(Component.translatable("options.tapetumshaders.shaderpacks"),
            button -> minecraft.setScreen(new ShaderPackScreen(this)))
            .bounds(width / 2 - 5 - buttonWidth, height - 27, buttonWidth, 20).build());
    }
}
