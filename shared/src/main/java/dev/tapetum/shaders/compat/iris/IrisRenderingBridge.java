package dev.tapetum.shaders.compat.iris;

import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.pipeline.RenderingPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import java.io.IOException;
import java.util.Objects;

/** The embedded engine owns all world/shadow passes; Tapetum only controls selection and UI. */
public final class IrisRenderingBridge implements RenderingPipeline {
    public static final IrisRenderingBridge INSTANCE = new IrisRenderingBridge();
    private static boolean started;
    private static Throwable lastFailure;

    private IrisRenderingBridge() {}

    public static void applySelection() throws IOException {
        lastFailure = null;
        var engine = Iris.getIrisConfig();
        if (engine == null) throw new IllegalStateException("The embedded Iris engine has not initialized");
        var config = TapetumShaders.getConfig();
        engine.setShaderPackName(config.getShaderPackName().orElse(null));
        engine.setShadersEnabled(config.areShadersEnabled());
        engine.save();
        Iris.reload();
        // Iris normally prepares this when entering a world. Apply must also report compilation
        // failures from the title screen, not just whether the ZIP was readable.
        if (config.areShadersEnabled() && Iris.getCurrentPack().isPresent()) {
            Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());
        }
        started = true;
        Iris.getStoredError().ifPresent(IrisRenderingBridge::reportFailure);
    }

    public static void reportFailure(Throwable error) {
        lastFailure = error;
        TapetumShaders.LOGGER.error("Embedded shader engine failed", error);
    }

    public static java.util.Optional<Throwable> getLastFailure() {
        return java.util.Optional.ofNullable(lastFailure);
    }

    /** Also persist selections/toggles made through Iris's options or its native keybindings. */
    public static void syncSelectionFromEngine() {
        if (!started || Iris.getIrisConfig() == null) return;
        var engine = Iris.getIrisConfig();
        var config = TapetumShaders.getConfig();
        if (config.areShadersEnabled() == engine.areShadersEnabled()
                && Objects.equals(config.getShaderPackName(), engine.getShaderPackName())) return;
        config.setShadersEnabled(engine.areShadersEnabled());
        config.setShaderPackName(engine.getShaderPackName().orElse(null));
        try {
            config.save();
        } catch (IOException error) {
            TapetumShaders.LOGGER.error("Could not persist the embedded engine's shader selection", error);
        }
    }

    @Override public void beginLevelRendering() {}
    @Override public void finalizeLevelRendering() {}
    @Override public void destroy() {}

    @Override
    public boolean isShaderPackActive() {
        return IrisApi.getInstance().isShaderPackInUse() && !Iris.isFallback()
            && TapetumShaders.getConfig().getShaderPackName().filter(Iris.getCurrentPackName()::equals).isPresent();
    }
}
