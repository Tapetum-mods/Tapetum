package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.tapetum.shaders.TapetumShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public abstract class MixinTerrainVertexBuffer {
    @Shadow private int vertexBufferId;
    @Shadow private int indexCount;
    @Shadow private VertexFormat.IndexType indexType;
    @Shadow private com.mojang.blaze3d.systems.RenderSystem.AutoStorageIndexBuffer sequentialIndices;
    @Shadow private VertexFormat.Mode mode;
    @Shadow private VertexFormat format;

    @Inject(method = "draw", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;drawElements(III)V"), cancellable = true)
    private void tapetum$drawTerrain(CallbackInfo ci) {
        int indices = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        var actualType = sequentialIndices == null ? indexType : sequentialIndices.type();
        if (TapetumShaders.getPipelineManager().drawTerrain(vertexBufferId, indices, indexCount,
                actualType.asGLType, format, mode)) {
            ci.cancel();
        }
    }
}
