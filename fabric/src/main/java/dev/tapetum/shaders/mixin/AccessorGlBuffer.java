package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlBuffer.class)
public interface AccessorGlBuffer {
    @Accessor("handle") int tapetum$handle();
}
