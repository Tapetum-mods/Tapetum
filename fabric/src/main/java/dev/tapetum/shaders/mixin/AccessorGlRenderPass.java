package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlRenderPass")
public interface AccessorGlRenderPass {
    @Accessor("vertexBuffers") GpuBuffer[] tapetum$vertexBuffers();
    @Accessor("indexBuffer") GpuBuffer tapetum$indexBuffer();
}
