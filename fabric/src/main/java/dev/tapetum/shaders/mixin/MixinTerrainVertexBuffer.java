package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import dev.tapetum.shaders.TapetumShaders;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public abstract class MixinTerrainVertexBuffer {
    @Shadow private int id;
    @Shadow private int vertexCount;
    @Shadow @Final private VertexFormat format;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void tapetum$drawTerrain(Matrix4f modelView, int mode, CallbackInfo ci) {
        if (TapetumShaders.getPipelineManager().drawTerrain(id, vertexCount, format, modelView, mode)) {
            ci.cancel();
        }
    }
}
