package dev.tapetum.shaders.compat.iris;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Fail before Minecraft loads if an external Iris jar overrides the tested embedded engine. */
public final class IrisCompatibilityGuard implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        var loader = FabricLoader.getInstance();
        String expected = loader.getModContainer("tapetumshaders").orElseThrow().getMetadata()
            .getCustomValue("tapetum:irisVersion").getAsString();
        String actual = loader.getModContainer("iris").orElseThrow().getMetadata()
            .getVersion().getFriendlyString();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Tapetum bundles Iris " + expected + ", but Fabric selected "
                + actual + ". Remove the separate Iris JAR from this profile; Tapetum already includes it.");
        }
    }
}
