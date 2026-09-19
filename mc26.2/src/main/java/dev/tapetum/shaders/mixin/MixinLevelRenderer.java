package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.compat.VersionCompat;
import dev.tapetum.shaders.uniform.FrameState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single hook point this skeleton wires into vanilla rendering: the start and end of
 * {@code LevelRenderer.render} (called {@code renderLevel}, with one more parameter, up to 26.1.2 -
 * which is why this class lives in the version module rather than in {@code shared/}). Real shaderpack rendering needs many more, much more
 * precisely placed injections than this (see Iris' own {@code MixinLevelRenderer}, which injects
 * at specific instructions inside this method to correctly bracket the deferred GPU passes queued
 * through {@link com.mojang.blaze3d.framegraph.FrameGraphBuilder}) — {@code HEAD}/{@code RETURN}
 * is only good enough for a pipeline that doesn't touch the framebuffer yet, which is all
 * {@link dev.tapetum.shaders.pipeline.VanillaRenderingPipeline} does today.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
	@Inject(method = "render", at = @At("HEAD"))
	private void tapetum$beginLevelRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
		// Captured here rather than at RETURN: the projection matrix and camera live on the render
		// state handed to this method, and the model-view matrix is a parameter. Both are out of reach
		// by the time the chain runs at the end of the same call.
		FrameState.capture(modelViewMatrix, cameraState.projectionMatrix, cameraState.pos,
			NEAR_PLANE, cameraState.depthFar, encodeFogType(cameraState.fogType), fogColor,
			VersionCompat.skyAngle(deltaTracker.getGameTimeDeltaPartialTick(false)),
			VersionCompat.moonPhase(deltaTracker.getGameTimeDeltaPartialTick(false)));

		TapetumShaders.getPipelineManager().getPipeline().beginLevelRendering();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void tapetum$endLevelRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
		TapetumShaders.getPipelineManager().finalizeLevelRendering();
	}

	/** Minecraft's near plane, which the projection above is built with. */
	private static final float NEAR_PLANE = 0.05f;

	/** OptiFine's {@code isEyeInWater} encoding: 0 none, 1 water, 2 lava, 3 powder snow. */
	@Unique
	private static int encodeFogType(FogType fogType) {
		if (fogType == FogType.WATER) {
			return 1;
		}
		if (fogType == FogType.LAVA) {
			return 2;
		}
		if (fogType == FogType.POWDER_SNOW) {
			return 3;
		}
		return 0;
	}

}
