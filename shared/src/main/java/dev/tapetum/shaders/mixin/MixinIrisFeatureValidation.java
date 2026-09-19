// SPDX-License-Identifier: LGPL-3.0-only
package dev.tapetum.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.tapetum.shaders.compat.iris.IrisRenderingBridge;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

/** Reject unsupported features before Iris recursively reloads and closes the ZIP being parsed. */
@Mixin(value = ShaderPack.class, remap = false)
public class MixinIrisFeatureValidation {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/shaderpack/properties/ShaderProperties;getRequiredFeatureFlags()Ljava/util/List;"))
    private List<String> tapetum$validateFeatures(List<String> required) {
        List<String> missing = required.stream().filter(FeatureFlags::isInvalid).toList();
        if (!missing.isEmpty()) {
            var error = new IllegalStateException("Unsupported shader features on this engine/GPU: "
                + String.join(", ", missing));
            IrisRenderingBridge.reportFailure(error);
            throw error;
        }
        return required;
    }
}
