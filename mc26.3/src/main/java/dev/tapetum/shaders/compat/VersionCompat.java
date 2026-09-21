package dev.tapetum.shaders.compat;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * The Minecraft 26.3 implementation of the handful of calls that differ between the Minecraft
 * versions this mod targets.
 *
 * <p>Each version module carries its own copy of this class, in this package and with this exact
 * shape; everything under {@code shared/} compiles against whichever one its module supplies. Keep
 * it small — anything that can be written against an API both versions share belongs in
 * {@code shared/} instead, not here. Nothing else in this module should exist.</p>
 */
public final class VersionCompat {
	private VersionCompat() {
	}

	public static void openUri(String uri) {
		com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(uri));
	}

	public static void openPath(java.nio.file.Path path) {
		com.mojang.blaze3d.Blaze3D.openPath(path);
	}

	/** The index selects the draw buffer; 26.1.2 had no such parameter. */
	public static void enableBlend() {
		GlStateManager._enableBlend(0);
	}

	public static void disableBlend() {
		GlStateManager._disableBlend(0);
	}

	/** 26.1.2 had this directly on GpuDevice. */
	public static String backendName() {
		var device = RenderSystem.tryGetDevice();
		return device == null ? null : device.getDeviceInfo().backendName();
	}

	public static int colorTextureId(RenderTarget target) {
		return target.getColorTexture() instanceof GlTexture texture ? texture.glId() : 0;
	}

	public static int depthTextureId(RenderTarget target) {
		return target.getDepthTexture() instanceof GlTexture texture ? texture.glId() : 0;
	}

	/**
	 * The Fabric API module backing this was {@code fabric-key-binding-api-v1}
	 * ({@code KeyBindingHelper.registerKeyBinding}) as recently as 1.21.11; renamed to
	 * {@code fabric-key-mapping-api-v1} by the time of this version.
	 */
	public static KeyMapping registerKeyMapping(KeyMapping mapping) {
		return KeyMappingHelper.registerKeyMapping(mapping);
	}

	/**
	 * The framebuffer Minecraft renders the world into, and whose colour texture a final pass reads.
	 *
	 * <p>26.2 moved the main render target onto GameRenderer; 26.1.2 had it on Minecraft.</p>
	 */
	public static RenderTarget mainRenderTarget() {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}

	/**
	 * Where the view is rendered from, which packs read as {@code cameraPosition}.
	 *
	 * <p>Approximated from the player's eye: 26.2 made GameRenderer.mainCamera private with no accessor, so third-person and spectator views will not match the real camera.</p>
	 */
	public static Vec3 cameraPosition() {
		// 26.2 made GameRenderer.mainCamera private with no accessor, so the player's eye is used
		// instead. It matches the camera in first person and drifts from it in third person or
		// spectator - an approximation, not a silent equivalence. An accessor mixin would fix it.
		var player = Minecraft.getInstance().player;
		return player == null ? Vec3.ZERO : player.getEyePosition();
	}

	/**
	 * Minecraft's sky angle for this frame, 0-1, read from the camera's environment probe.
	 *
	 * <p>26.x removed {@code Level.getTimeOfDay()}, where OptiFine and Iris read this, and moved the
	 * value behind {@code EnvironmentAttributes.SUN_ANGLE}. This version spells the camera accessor
	 * {@code mainCamera()}, which is why the lookup lives here rather than in shared code.</p>
	 */
	public static float skyAngle(float partialTick) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		return camera == null ? 0.0f
			: camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTick);
	}

	/**
	 * The moon phase as an ordinal, 0-7. Like the sky angle, 26.x moved this behind the camera's
	 * environment probe — {@code Level.getMoonPhase()} no longer exists.
	 */
	public static int moonPhase(float partialTick) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		if (camera == null) {
			return 0;
		}
		return camera.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, partialTick).ordinal();
	}
}
