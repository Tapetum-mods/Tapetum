package dev.tapetum.shaders.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.tapetum.shaders.TapetumShaders;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single hook point this skeleton wires into vanilla rendering: the start and end of
 * {@code LevelRenderer.renderLevel}. Real shaderpack rendering needs many more, much more precisely
 * placed injections than this (see Iris' own {@code MixinLevelRenderer}) — {@code HEAD}/{@code
 * RETURN} is only good enough for a pipeline that doesn't touch the framebuffer yet, which is all
 * {@link dev.tapetum.shaders.pipeline.VanillaRenderingPipeline} does today.
 *
 * <p><b>Confirmed</b> against Mojang's own official client mappings for 1.21.11 (the
 * {@code client_mappings} artifact linked from Mojang's version manifest — no Loom/decompile needed,
 * just the proguard-format mapping file), not assumed. This superseded an earlier version of this
 * class that had ported mc26.1's signature down unchanged and was wrong on three points: 1.21.11
 * takes a plain {@code Camera}, not a {@code CameraRenderState} (that render-state-extraction
 * refactor of the camera parameter hadn't happened yet at 1.21.11 — {@code CameraRenderState} exists
 * elsewhere in 1.21.11's mappings, just not used here yet); it takes <i>three</i> separate
 * {@code Matrix4f} parameters where 26.1.2 has collapsed them into one {@code Matrix4fc}; and it has
 * no trailing {@code ChunkSectionsToRender} parameter at all — that was added later. The three
 * {@code Matrix4f} parameter names below (frustum/projection/modelView) are a reasonable guess at
 * their role, not confirmed — proguard mappings don't carry parameter names, only types and order,
 * which is all a mixin injection actually needs to bind correctly.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void tapetum$beginLevelRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderOutline, Camera camera, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
			Matrix4f modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
			CallbackInfo ci) {
		TapetumShaders.getPipelineManager().getPipeline().beginLevelRendering();
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void tapetum$endLevelRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker,
			boolean renderOutline, Camera camera, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
			Matrix4f modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
			CallbackInfo ci) {
		TapetumShaders.getPipelineManager().getPipeline().finalizeLevelRendering();
	}
}
