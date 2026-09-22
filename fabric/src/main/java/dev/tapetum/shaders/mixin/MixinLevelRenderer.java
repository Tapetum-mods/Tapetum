package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.compat.VersionCompat;
import dev.tapetum.shaders.uniform.FrameState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
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
 * Brackets this version's completed world render for Tapetum's screen-space pipeline.
 * Geometry/shadow programs require additional pass-specific integration, not just these hooks.
 * Headless contract checks verify both descriptors against the exact Minecraft version.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void tapetum$beginLevelRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
			ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
		TapetumShaders.getPipelineManager().beginLevelRendering();
		// Captured here rather than at RETURN: the projection matrix and camera live on the render
		// state handed to this method, and the model-view matrix is a parameter. Both are out of reach
		// by the time the chain runs at the end of the same call.
		FrameState.capture(modelViewMatrix, cameraState.projectionMatrix, cameraState.pos,
			NEAR_PLANE, cameraState.depthFar, encodeFogType(cameraState.fogType), fogColor,
			VersionCompat.skyAngle(deltaTracker.getGameTimeDeltaPartialTick(false)),
			VersionCompat.moonPhase(deltaTracker.getGameTimeDeltaPartialTick(false)));

	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void tapetum$endLevelRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
			ChunkSectionsToRender chunkSectionsToRender, CallbackInfo ci) {
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
