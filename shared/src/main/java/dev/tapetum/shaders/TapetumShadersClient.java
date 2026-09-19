package dev.tapetum.shaders;

import dev.tapetum.shaders.compat.iris.IrisRenderingBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Iris owns rendering and O/K/R; registering a second set would toggle shaders twice. */
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
