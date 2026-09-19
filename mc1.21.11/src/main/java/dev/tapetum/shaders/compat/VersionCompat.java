package dev.tapetum.shaders.compat;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.GpuDevice;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * The Minecraft 1.21.11 implementation of the handful of calls that differ between the Minecraft
 * versions this mod targets.
 *
 * <p>Each version module carries its own copy of this class, in this package and with this exact
 * shape; everything under {@code shared/} compiles against whichever one its module supplies. Keep
 * it small — anything that can be written against an API both versions share belongs in
 * {@code shared/} instead, not here. Nothing else in this module should exist.</p>
 *
 * <p><b>Confirmed</b> against Mojang's own official client mappings for 1.21.11 (fetched directly
 * from the {@code client_mappings} artifact linked in Mojang's version manifest — a plain proguard
 * text file, no Loom/decompile needed): {@code GlStateManager._enableBlend()}/{@code
 * _disableBlend()} take no draw-buffer index at 1.21.11 (that parameter was added by 26.2, per
 * mc26.2's own copy of this class), and {@code GpuDevice.getBackendName()} exists directly (the
 * {@code getDeviceInfo()} indirection is also a 26.2 change). Both match mc26.1's shape, confirming
 * the inference this class was originally built on for these two points — unlike
 * {@link dev.tapetum.shaders.mixin.MixinLevelRenderer}, which the same technique proved wrong on
 * three separate points. See that class's docs for what that means for trusting inferred-but-not-
 * independently-checked assumptions in general.</p>
 */
public final class VersionCompat {
	private VersionCompat() {
	}

	public static void enableBlend() {
		GlStateManager._enableBlend();
	}

	public static void disableBlend() {
		GlStateManager._disableBlend();
	}

	public static String backendName(GpuDevice device) {
		return device.getBackendName();
	}

	/**
	 * Confirmed against the real {@code fabric-key-binding-api-v1-1.1.7+4fc5413f3e.jar} (the module
	 * version bundled in {@code fabric-api-0.141.3+1.21.11}, per its POM) — unlike the rest of this
	 * class, this one class file was actually inspected rather than assumed. 1.21.11 exposes this as
	 * {@code net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper#registerKeyBinding}; the
	 * module was renamed to {@code fabric-key-mapping-api-v1} /
	 * {@code KeyMappingHelper#registerKeyMapping} by 26.1.2.
	 */
	public static KeyMapping registerKeyMapping(KeyMapping mapping) {
		return KeyBindingHelper.registerKeyBinding(mapping);
	}
}
