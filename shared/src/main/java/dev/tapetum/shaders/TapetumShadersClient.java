package dev.tapetum.shaders;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Tapetum owns rendering and shaderpack selection. */
public class TapetumShadersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            TapetumShaders.LOGGER.info("Rendering through {}", TapetumShaders.getShaderEngine().name());
            TapetumShaders.getPipelineManager().reload();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> TapetumShaders.getShaderEngine().syncSelection());
    }
}
