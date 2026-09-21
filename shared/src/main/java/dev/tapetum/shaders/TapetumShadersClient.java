package dev.tapetum.shaders;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import dev.tapetum.shaders.gui.ShaderPackScreen;

/** Tapetum owns rendering and shaderpack selection. */
public class TapetumShadersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(TapetumShaders.MOD_ID, "main"));
        var open = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.tapetumshaders.open_shaderpack_screen", InputConstants.KEY_O, category));
        var toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.tapetumshaders.toggle_shaders", InputConstants.KEY_K, category));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            TapetumShaders.LOGGER.info("Rendering through {}", TapetumShaders.getShaderEngine().name());
            TapetumShaders.getPipelineManager().reload();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.consumeClick()) {
                if (client.player != null) client.setScreenAndShow(new ShaderPackScreen(null));
            }
            while (toggle.consumeClick()) {
                if (client.player == null) continue;
                var config = TapetumShaders.getConfig();
                config.setShadersEnabled(!config.areShadersEnabled());
                boolean applied = TapetumShaders.saveConfigAndReload();
                String message = !applied ? "tapetumshaders.chat.shaders_failed"
                    : config.areShadersEnabled() ? "tapetumshaders.chat.shaders_on" : "tapetumshaders.chat.shaders_off";
                client.player.sendSystemMessage(Component.translatable(message));
            }
        });
    }
}
