package dev.tapetum.shaders;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import dev.tapetum.shaders.gui.ShaderPackScreen;

/** Tapetum owns rendering and shaderpack selection. */
public class TapetumShadersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        var category = "key.categories.tapetumshaders";
        var open = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.tapetumshaders.open_shaderpack_screen", org.lwjgl.glfw.GLFW.GLFW_KEY_O, category));
        var toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.tapetumshaders.toggle_shaders", org.lwjgl.glfw.GLFW.GLFW_KEY_K, category));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            TapetumShaders.LOGGER.info("Rendering through {}", TapetumShaders.getShaderEngine().name());
            TapetumShaders.getPipelineManager().reload();
        });
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TapetumShaders.getShaderEngine().syncSelection();
            while (open.consumeClick()) {
                if (client.player != null) client.setScreen(new ShaderPackScreen(null));
            }
            while (toggle.consumeClick()) {
                if (client.player == null) continue;
                var config = TapetumShaders.getConfig();
                config.setShadersEnabled(!config.areShadersEnabled());
                boolean applied = TapetumShaders.saveConfigAndReload();
                String message = !applied ? "tapetumshaders.chat.shaders_failed"
                    : config.areShadersEnabled() ? "tapetumshaders.chat.shaders_on" : "tapetumshaders.chat.shaders_off";
                client.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(message), false);
            }
        });
    }
}
