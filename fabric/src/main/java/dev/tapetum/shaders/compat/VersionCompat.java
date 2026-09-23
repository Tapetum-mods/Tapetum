package dev.tapetum.shaders.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Minecraft 1.17 platform calls, remapped from Mojang to Fabric names at build time. */
public final class VersionCompat {
    private VersionCompat() { }

    public static void openUri(String uri) { net.minecraft.Util.getPlatform().openUri(uri); }
    public static void openPath(java.nio.file.Path path) { net.minecraft.Util.getPlatform().openFile(path.toFile()); }
    public static void enableBlend() { GlStateManager._enableBlend(); }
    public static void disableBlend() { GlStateManager._disableBlend(); }
    public static String backendName() { return "OpenGL"; }
    public static int colorTextureId(RenderTarget target) { return target.getColorTextureId(); }
    public static int depthTextureId(RenderTarget target) { return target.getDepthTextureId(); }

    public static KeyMapping registerKeyMapping(KeyMapping mapping) {
        return KeyBindingHelper.registerKeyBinding(mapping);
    }

    public static RenderTarget mainRenderTarget() { return Minecraft.getInstance().getMainRenderTarget(); }
    public static Vec3 cameraPosition() { return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(); }

    public static float skyAngle(float partialTick) {
        var level = Minecraft.getInstance().level;
        return level == null ? 0.0f : level.getTimeOfDay(partialTick);
    }

    public static int moonPhase(float partialTick) {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getMoonPhase();
    }
}
