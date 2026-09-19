package dev.tapetum.shaders.smoke;

import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.compat.iris.IrisRenderingBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Separate test-only mod: creates a new QA world and never opens a player's existing saves. */
public final class EmbeddedEngineSmokeTest implements ClientModInitializer {
    private final Path directory = FabricLoader.getInstance().getGameDir();
    private final List<String> results = new ArrayList<>();
    private List<String> packs;
    private int stage;
    private int ticks;
    private int packIndex;
    private boolean capturing;
    private boolean finished;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("tapetum.isolatedSmokeTest")) {
            throw new IllegalStateException("This test mod may only run in an isolated QA profile");
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (finished) return;
        try {
            if (stage == 0 && SmokeVersionCompat.screen(client) instanceof TitleScreen && !SmokeVersionCompat.loading(client)) {
                CreateWorldScreen.openFresh(client, () -> fail(client, new AssertionError("World creation cancelled")));
                stage = 1;
            } else if (stage == 1 && SmokeVersionCompat.screen(client) instanceof CreateWorldScreen create) {
                create.getUiState().setName("Tapetum Isolated QA");
                create.getUiState().setSeed("1337");
                create.getUiState().setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                var start = CreateWorldScreen.class.getDeclaredMethod("onCreate");
                start.setAccessible(true);
                start.invoke(create);
                stage = 2;
            } else if (stage == 2 && client.level != null && client.player != null && SmokeVersionCompat.screen(client) == null) {
                if (++ticks < 200) return;
                client.player.setYRot(135.0f);
                client.player.setXRot(15.0f);
                packs = TapetumShaders.getShaderpackManager().getAvailablePacks();
                if (packs.isEmpty()) throw new AssertionError("No QA packs installed");
                stage = 3;
                ticks = 0;
            } else if (stage == 3 && !capturing) {
                if (ticks == 0) {
                    client.setScreenAndShow(null);
                    var config = TapetumShaders.getConfig();
                    config.setShaderPackName(packs.get(packIndex));
                    config.setShadersEnabled(true);
                    if (!TapetumShaders.saveConfigAndReload()) {
                        var failure = IrisRenderingBridge.getLastFailure();
                        if (packs.get(packIndex).equals("Mellow Shader v3.4.zip")
                                && failure.map(e -> e.getMessage().contains("CUSTOM_IMAGES")).orElse(false)) {
                            results.add("Mellow default: unsupported CUSTOM_IMAGES rejected without recursive reload");
                            // Exercise an option provided by the pack author, only in this QA copy.
                            Iris.getShaderPackOptionQueue().put("COLORED_LIGHTS", "false");
                            if (!TapetumShaders.saveConfigAndReload()) {
                                throw new AssertionError("Mellow with COLORED_LIGHTS=false failed");
                            }
                            results.add("Mellow QA setting: COLORED_LIGHTS=false");
                        } else {
                            throw new AssertionError("Could not activate " + packs.get(packIndex) + ": " + failure);
                        }
                    }
                }
                if (++ticks < 120) return;
                if (!IrisRenderingBridge.INSTANCE.isShaderPackActive()) {
                    throw new AssertionError("Engine stopped rendering " + packs.get(packIndex));
                }
                client.setScreenAndShow(null);
                capturing = true;
                String name = packs.get(packIndex);
                Screenshot.takeScreenshot(dev.tapetum.shaders.compat.VersionCompat.mainRenderTarget(), image -> {
                    try (image) {
                        long lit = 0;
                        java.util.Set<Integer> colors = new java.util.HashSet<>();
                        for (int y = image.getHeight() / 5; y < image.getHeight() * 4 / 5; y += 8) {
                            for (int x = image.getWidth() / 5; x < image.getWidth() * 4 / 5; x += 8) {
                                int rgb = image.getPixel(x, y) & 0xFFFFFF;
                                colors.add(rgb);
                                if ((rgb & 255) + ((rgb >>> 8) & 255) + ((rgb >>> 16) & 255) > 30) lit++;
                            }
                        }
                        if (lit < 100 || colors.size() < 100) throw new AssertionError("Blank world capture: " + name);
                        image.writeToFile(directory.resolve("pack-" + packIndex + ".png"));
                        results.add(name + ": " + Iris.getPipelineManager().getPipelineNullable().getClass().getName());
                        results.add("capture: " + colors.size() + " distinct sampled colors, " + lit + " lit pixels");
                        System.out.println("TAPETUM_SMOKE_CAPTURE " + name);
                        client.execute(() -> {
                            capturing = false;
                            ticks = 0;
                            if (++packIndex == packs.size()) stage = 4;
                        });
                    } catch (Exception error) {
                        client.execute(() -> fail(client, error));
                    }
                });
            } else if (stage == 4) {
                TapetumShaders.getConfig().setShadersEnabled(false);
                if (!TapetumShaders.saveConfigAndReload() || IrisRenderingBridge.INSTANCE.isShaderPackActive()) {
                    throw new AssertionError("Disabling shaders failed");
                }
                results.add("disable: passed");
                TapetumShaders.getConfig().setShadersEnabled(true);
                if (!TapetumShaders.saveConfigAndReload()) throw new AssertionError("Re-enabling failed");
                results.add("re-enable: passed");
                client.setScreenAndShow(new dev.tapetum.shaders.gui.ShaderPackScreen(null));
                stage = 5;
                ticks = 0;
            } else if (stage == 5 && ++ticks > 20) {
                var screen = (dev.tapetum.shaders.gui.ShaderPackScreen) SmokeVersionCompat.screen(client);
                var field = screen.getClass().getDeclaredField("packSettingsButton");
                field.setAccessible(true);
                var button = (net.minecraft.client.gui.components.Button) field.get(screen);
                if (!button.active) throw new AssertionError("Pack settings remain disabled");
                button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                if (!(SmokeVersionCompat.screen(client) instanceof net.irisshaders.iris.gui.screen.ShaderPackScreen)) {
                    throw new AssertionError("Options did not open");
                }
                results.add("Tapetum -> Iris pack options: passed");
                Files.write(directory.resolve("smoke-success.txt"), results);
                finished = true;
                client.stop();
            }
        } catch (Throwable error) {
            fail(client, error);
        }
    }

    private void fail(Minecraft client, Throwable error) {
        error.printStackTrace();
        try { Files.writeString(directory.resolve("smoke-failure.txt"), error.toString()); }
        catch (Exception ignored) { error.addSuppressed(ignored); }
        finished = true;
        client.stop();
    }
}
