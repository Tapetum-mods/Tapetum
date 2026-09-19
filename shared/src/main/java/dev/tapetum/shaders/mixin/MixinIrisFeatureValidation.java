// SPDX-License-Identifier: LGPL-3.0-only
package dev.tapetum.shaders.mixin;

import dev.tapetum.shaders.compat.iris.IrisRenderingBridge;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents Iris from recursively reloading while a ZIP-backed shaderpack is being constructed. */
@Mixin(targets = "net.irisshaders.iris.shaderpack.ShaderPack", remap = false)
public final class MixinIrisFeatureValidation {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/api/v0/IrisApiConfig;setShadersEnabledAndApply(Z)V"))
    private void tapetum$rejectUnsupportedFeatures(IrisApiConfig config, boolean enabled) {
        if (!enabled) {
            var error = new IllegalStateException(
                "Iris rejected this shaderpack because it requires an unavailable feature");
            IrisRenderingBridge.reportFailure(error);
            throw error;
        }
        config.setShadersEnabledAndApply(true);
    }
}