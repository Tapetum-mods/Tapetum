package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.tapetum.shaders.TapetumShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs after vanilla has bound the target, layer state, samplers and this section's UBO. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class MixinGlCommandEncoder {
    @Inject(method = "drawFromBuffers", at = @At("HEAD"), cancellable = true)
    private void tapetum$drawTerrain(@Coerce Object pass, int baseVertex, int firstIndex, int count,
            VertexFormat.IndexType indexType, GlRenderPipeline pipeline, int instances, CallbackInfo ci) {
        var manager = TapetumShaders.getPipelineManager();
        if (manager != null && manager.drawTerrain((AccessorGlRenderPass) pass, pipeline.info(),
                baseVertex, firstIndex, count, indexType, instances)) ci.cancel();
    }
}
