package dev.tapetum.shaders.gui;

import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.shaderpack.ShaderPackOptions;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Edits a snapshot; only Apply/Done persist values and rebuild the active pack. */
public final class ShaderPackOptionsScreen extends Screen {
    private final Screen parent;
    private final String packName;
    private final ShaderPackOptions catalog;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final List<AbstractWidget> rows = new ArrayList<>();
    private List<ShaderPackOptions.Option> visible = List.of();
    private String query = "";
    private String status = "";
    private int page, left, panelWidth, pageSize;
    private EditBox search;
    private Button previous, next;

    public ShaderPackOptionsScreen(Screen parent, String packName, ShaderPackOptions catalog) {
        super(Component.translatable("tapetumshaders.gui.pack_settings"));
        this.parent = parent;
        this.packName = packName;
        this.catalog = catalog;
        pending.putAll(catalog.validate(TapetumShaders.getConfig().getPackOptions(packName)));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(560, width - 16);
        left = (width - panelWidth) / 2;
        pageSize = Math.max(1, (height - 146) / 24);
        search = addRenderableWidget(new EditBox(font, left + 6, 35, panelWidth - 12, 18,
            Component.translatable("tapetumshaders.gui.search")));
        search.setBordered(false);
        search.setMaxLength(100);
        search.setHint(Component.translatable("tapetumshaders.gui.search"));
        search.setValue(query);
        search.setResponder(text -> { query = text; page = 0; refreshRows(); });
        previous = command(left, height - 76, 24, "<", () -> { page--; refreshRows(); });
        previous.setTooltip(Tooltip.create(Component.translatable("tapetumshaders.gui.previous_page")));
        next = command(left + panelWidth - 24, height - 76, 24, ">", () -> { page++; refreshRows(); });
        next.setTooltip(Tooltip.create(Component.translatable("tapetumshaders.gui.next_page")));
        int w = (panelWidth - 18) / 4;
        command(left, height - 26, w, Component.translatable("tapetumshaders.gui.reset").getString(),
            () -> { pending.clear(); status = ""; refreshRows(); });
        command(left + w + 6, height - 26, w, Component.translatable("tapetumshaders.gui.cancel").getString(), this::onClose);
        command(left + (w + 6) * 2, height - 26, w, Component.translatable("tapetumshaders.gui.apply").getString(), this::apply);
        command(left + (w + 6) * 3, height - 26, w, Component.translatable("tapetumshaders.gui.done").getString(),
            () -> { if (apply()) onClose(); });
        rows.clear();
        refreshRows();
    }

    private Button command(int x, int y, int w, String label, Runnable action) {
        return addRenderableWidget(TransparentWidgets.style(Button.builder(Component.literal(label),
            button -> action.run()).bounds(x, y, w, 20).build()));
    }

    private void refreshRows() {
        rows.forEach(this::removeWidget);
        rows.clear();
        var filtered = catalog.entries().stream().filter(option ->
            option.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        int pages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        page = Math.clamp(page, 0, pages - 1);
        visible = filtered.subList(page * pageSize, Math.min(filtered.size(), (page + 1) * pageSize));
        previous.active = page > 0;
        next.active = page + 1 < pages;
        for (int i = 0; i < visible.size(); i++) {
            var option = visible.get(i);
            int x = left + panelWidth / 2, y = 60 + i * 24, w = panelWidth / 2 - 6;
            String value = pending.getOrDefault(option.name(), option.defaultValue());
            AbstractWidget control;
            if (!option.toggle() && option.values().size() > 2
                    && option.values().stream().allMatch(ShaderPackOptionsScreen::numeric)) {
                control = new ValueSlider(x, y, w, option, value);
            } else {
                control = CycleButton.<String>builder(Component::literal, value).withValues(option.values())
                    .displayOnlyValue().create(x, y, w, 20, Component.literal(option.name()),
                        (button, selected) -> change(option, selected));
            }
            control.setTooltip(Tooltip.create(Component.literal(option.name() + "\n" + option.comment())));
            rows.add(addRenderableWidget(TransparentWidgets.style(control)));
            if (option.toggle() && control instanceof CycleButton<?> toggle) TransparentWidgets.toggle(toggle);
        }
    }

    private void change(ShaderPackOptions.Option option, String value) {
        if (value.equals(option.defaultValue())) pending.remove(option.name());
        else pending.put(option.name(), value);
        status = "";
    }

    private boolean apply() {
        var config = TapetumShaders.getConfig();
        var old = config.getPackOptions(packName);
        config.setPackOptions(packName, catalog.validate(pending));
        try {
            config.save();
        } catch (IOException error) {
            config.setPackOptions(packName, old);
            status = Component.translatable("tapetumshaders.gui.options.save_failed").getString();
            TapetumShaders.LOGGER.error("Failed to save options for '{}'", packName, error);
            return false;
        }
        if (config.areShadersEnabled() && config.getShaderPackName().filter(packName::equals).isPresent()) {
            TapetumShaders.getPipelineManager().reload();
            if (!TapetumShaders.getShaderEngine().isShaderPackActive()) {
                status = Component.translatable("tapetumshaders.gui.options.compile_failed").getString();
                return false;
            }
        }
        status = Component.translatable("tapetumshaders.gui.options.saved").getString();
        return true;
    }

    private static boolean numeric(String value) {
        try { return Float.isFinite(Float.parseFloat(value)); }
        catch (NumberFormatException error) { return false; }
    }

    private final class ValueSlider extends AbstractSliderButton {
        private final ShaderPackOptions.Option option;
        ValueSlider(int x, int y, int w, ShaderPackOptions.Option option, String selected) {
            super(x, y, w, 20, Component.literal(selected),
                option.values().indexOf(selected) / (double) (option.values().size() - 1));
            this.option = option;
        }
        private String selected() {
            return option.values().get((int) Math.round(value * (option.values().size() - 1)));
        }
        @Override protected void updateMessage() { setMessage(Component.literal(selected())); }
        @Override protected void applyValue() { change(option, selected()); }
        @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
            return Component.literal(option.name() + ": " + selected());
        }
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        if (minecraft.level == null) extractPanorama(graphics, tick);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        graphics.fill(left, 30, left + panelWidth, height - 32, TransparentWidgets.PANEL);
        graphics.centeredText(font, Component.literal(TransparentWidgets.fit(packName, panelWidth)), width / 2, 12, 0xFFFFFFFF);
        for (int i = 0; i < visible.size(); i++)
            graphics.text(font, TransparentWidgets.fit(visible.get(i).name(), panelWidth / 2 - 16),
                left + 6, 66 + i * 24, 0xFFFFFFFF);
        if (visible.isEmpty()) graphics.centeredText(font,
            Component.translatable("tapetumshaders.gui.no_results"), width / 2, 68, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(Integer.toString(page + 1)), width / 2, height - 70, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(TransparentWidgets.fit(status, panelWidth - 12)),
            width / 2, height - 44, 0xFFFFFFFF);
        super.extractRenderState(graphics, x, y, tick);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
