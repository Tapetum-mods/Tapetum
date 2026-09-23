package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.compat.LegacyMatrices;
import dev.tapetum.shaders.compat.VersionCompat;
import dev.tapetum.shaders.uniform.FrameState;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.material.FogType;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tapetum.shaders.pipeline.LegacyTerrainPipeline;
import net.minecraft.client.renderer.RenderType;

/** Brackets world rendering and captures the matrices used by each terrain layer. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
    @WrapMethod(method = "renderSectionLayer")
    private void tapetum$terrainLayer(RenderType type, PoseStack pose, double x, double y, double z,
            Matrix4f projection, Operation<Void> original) {
        var pipeline = TapetumShaders.getPipelineManager().getPipeline();
        if (!(pipeline instanceof LegacyTerrainPipeline terrain)) {
            original.call(type, pose, x, y, z, projection);
            return;
        }
        terrain.enterLayer(type, pose.last().pose(), projection);
        try {
            original.call(type, pose, x, y, z, projection);
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
        int eyeInWater = fluid == FogType.WATER ? 1 : fluid == FogType.LAVA ? 2 : 0;
        FrameState.capture(LegacyMatrices.convert(pose.last().pose()), LegacyMatrices.convert(projection),
            camera.getPosition(), 0.05f, gameRenderer.getRenderDistance() * 4.0f, eyeInWater, null,
            VersionCompat.skyAngle(partialTick), VersionCompat.moonPhase(partialTick));
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void tapetum$endLevelRender(PoseStack pose, float partialTick, long finishTimeNano,
            boolean renderOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projection, CallbackInfo ci) {
        var fog = RenderSystem.getShaderFogColor();
        FrameState.fogColor().set(fog[0], fog[1], fog[2]);
        TapetumShaders.getPipelineManager().finalizeLevelRendering();
    }
}
