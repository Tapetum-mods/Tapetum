package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.compat.LegacyMatrices;
import dev.tapetum.shaders.compat.VersionCompat;
import dev.tapetum.shaders.uniform.FrameState;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.tags.FluidTags;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tapetum.shaders.pipeline.LegacyTerrainPipeline;
import net.minecraft.client.renderer.RenderType;

/** Brackets the 1.16.5 world draw. Geometry and shadow passes remain separate development work. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
    @WrapMethod(method = "renderChunkLayer")
    private void tapetum$terrainLayer(RenderType type, PoseStack pose, double x, double y, double z,
            Operation<Void> original) {
        var pipeline = TapetumShaders.getPipelineManager().getPipeline();
        if (!(pipeline instanceof LegacyTerrainPipeline terrain)) {
            original.call(type, pose, x, y, z);
            return;
        }
        terrain.enterLayer(type);
        try {
            original.call(type, pose, x, y, z);
        } finally {
            terrain.leaveLayer();
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void tapetum$beginLevelRender(PoseStack pose, float partialTick, long finishTimeNano,
            boolean renderOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projection, CallbackInfo ci) {
        TapetumShaders.getPipelineManager().beginLevelRendering();
        var fluid = camera.getFluidInCamera();
        int eyeInWater = fluid.is(FluidTags.WATER) ? 1 : fluid.is(FluidTags.LAVA) ? 2 : 0;
        FrameState.capture(LegacyMatrices.convert(pose.last().pose()), LegacyMatrices.convert(projection),
            camera.getPosition(), 0.05f, gameRenderer.getRenderDistance() * 4.0f, eyeInWater, null,
            VersionCompat.skyAngle(partialTick), VersionCompat.moonPhase(partialTick));
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void tapetum$endLevelRender(PoseStack pose, float partialTick, long finishTimeNano,
            boolean renderOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projection, CallbackInfo ci) {
        // FogRenderer establishes this frame's fixed-function fog inside renderLevel, after HEAD.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var fog = stack.mallocFloat(4);
            GL11.glGetFloatv(GL11.GL_FOG_COLOR, fog);
            FrameState.fogColor().set(fog.get(0), fog.get(1), fog.get(2));
        }
        TapetumShaders.getPipelineManager().finalizeLevelRendering();
    }
}
