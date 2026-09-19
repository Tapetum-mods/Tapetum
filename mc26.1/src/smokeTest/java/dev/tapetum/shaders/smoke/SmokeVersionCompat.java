package dev.tapetum.shaders.smoke;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
final class SmokeVersionCompat {
    private SmokeVersionCompat() {}
    static Screen screen(Minecraft client) { return client.screen; }
    static boolean loading(Minecraft client) { return client.getOverlay() != null; }
}
