package dev.tapetum.shaders.gui;

import dev.tapetum.shaders.TapetumShaders;
import dev.tapetum.shaders.shaderpack.ShaderPackOptions;
import dev.tapetum.shaders.shaderpack.ShaderPackMenu;
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
    private final ShaderPackMenu menu;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final List<AbstractWidget> rows = new ArrayList<>();
    private List<ShaderPackMenu.Entry> visible = List.of();
    private final Deque<String> history = new ArrayDeque<>();
    private String section = "";
    private boolean allOptions;
    private String query = "";
    private String status = "";
    private int page, left, panelWidth, pageSize;
    private EditBox search;
    private Button previous, next, back;

    public ShaderPackOptionsScreen(Screen parent, String packName, ShaderPackOptions catalog, ShaderPackMenu menu) {
        super(Component.translatable("tapetumshaders.gui.pack_settings"));
        this.parent = parent;
        this.packName = packName;
        this.catalog = catalog;
        this.menu = menu;
        pending.putAll(catalog.validate(TapetumShaders.getConfig().getPackOptions(packName)));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(560, width - 16);
        left = (width - panelWidth) / 2;
        pageSize = Math.max(1, (height - 170) / 24);
        search = addRenderableWidget(new EditBox(font, left + 6, 35, panelWidth - 12, 18,
            Component.translatable("tapetumshaders.gui.search")));
        search.setBordered(false);
        search.setMaxLength(100);
        search.setHint(Component.translatable("tapetumshaders.gui.search"));
        search.setValue(query);
        search.setResponder(text -> { query = text; page = 0; refreshRows(); });
        back = command(left + 6, 58, 24, "<", () -> {
            if (!history.isEmpty()) section = history.pop();
            else section = "";
            allOptions = false;
            page = 0;
            refreshRows();
        });
        back.setTooltip(Tooltip.create(Component.translatable("gui.back")));
        command(left + panelWidth - 126, 58, 120, Component.translatable("tapetumshaders.gui.options.all").getString(), () -> {
            allOptions = !allOptions;
            page = 0;
            refreshRows();
        });
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
        var filtered = allOptions || !query.isBlank()
            ? catalog.entries().stream().filter(option -> (option.name() + " " + optionLabel(option.name()))
                .toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .map(option -> new ShaderPackMenu.Entry(ShaderPackMenu.Kind.OPTION, option.name())).toList()
            : menu.entries(section);
        int pages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        page = Math.clamp(page, 0, pages - 1);
        visible = filtered.subList(page * pageSize, Math.min(filtered.size(), (page + 1) * pageSize));
        previous.active = page > 0;
        next.active = page + 1 < pages;
        back.active = allOptions || !history.isEmpty() || !section.isEmpty();
        for (int i = 0; i < visible.size(); i++) {
            var entry = visible.get(i);
            int x = left + panelWidth / 2, y = 84 + i * 24, w = panelWidth / 2 - 6;
            if (entry.kind() == ShaderPackMenu.Kind.PAGE) {
                rows.add(command(left + 6, y, panelWidth - 12, menu.label("screen." + entry.name(), entry.name()), () -> {
                    history.push(section);
                    section = entry.name();
                    page = 0;
                    refreshRows();
                }));
                continue;
            }
            if (entry.kind() == ShaderPackMenu.Kind.PROFILE) {
                String custom = Component.translatable("tapetumshaders.gui.options.custom").getString();
                var values = new ArrayList<>(menu.profiles());
                String customId = "<custom>";
                values.add(customId);
                String selected = menu.profiles().stream().filter(name -> menu.profile(name).entrySet().stream()
                    .allMatch(value -> pending.getOrDefault(value.getKey(), option(value.getKey()).defaultValue())
                        .equals(value.getValue()))).findFirst().orElse(customId);
                var control = CycleButton.<String>builder(value -> Component.literal(value.equals(customId)
                    ? custom : menu.label("profile." + value, value)), selected).withValues(values)
                    .displayOnlyValue().create(x, y, w, 20, Component.translatable("tapetumshaders.gui.options.profile"),
                        (button, value) -> {
                            if (value.equals(customId)) return;
                            menu.profile(value).forEach((key, setting) -> change(option(key), setting));
                            refreshRows();
                        });
                control.active = !menu.profiles().isEmpty();
                rows.add(addRenderableWidget(TransparentWidgets.style(control)));
                continue;
            }
            var option = option(entry.name());
            String value = pending.getOrDefault(option.name(), option.defaultValue());
            AbstractWidget control;
            if (!option.toggle() && option.values().size() > 2
                    && option.values().stream().allMatch(ShaderPackOptionsScreen::numeric)) {
                control = new ValueSlider(x, y, w, option, value);
            } else {
                control = CycleButton.<String>builder(v -> Component.literal(menu.label("value." + option.name() + "." + v, v)), value)
                    .withValues(option.values()).displayOnlyValue().create(x, y, w, 20, Component.literal(optionLabel(option.name())),
                        (button, selected) -> change(option, selected));
            }
            control.setTooltip(Tooltip.create(Component.literal(option.name() + "\n"
                + menu.label("option." + option.name() + ".comment", option.comment()))));
            rows.add(addRenderableWidget(TransparentWidgets.style(control)));
            if (option.toggle() && control instanceof CycleButton<?> toggle) TransparentWidgets.toggle(toggle);
        }
    }

    private ShaderPackOptions.Option option(String name) {
        return catalog.entries().stream().filter(option -> option.name().equals(name)).findFirst().orElseThrow();
    }

    private String optionLabel(String name) { return menu.label("option." + name, name); }

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
        graphics.fill(left, 30, left + panelWidth, height - 32, TransparentWidgets.SHEET);
        graphics.centeredText(font, Component.literal(TransparentWidgets.fit(packName, panelWidth)), width / 2, 12, 0xFFFFFFFF);
        String heading = allOptions ? Component.translatable("tapetumshaders.gui.options.all").getString()
            : section.isEmpty() ? title.getString() : menu.label("screen." + section, section);
        graphics.text(font, TransparentWidgets.fit(heading, panelWidth - 172), left + 38, 64, TransparentWidgets.ACCENT);
        for (int i = 0; i < visible.size(); i++) {
            var entry = visible.get(i);
            if (entry.kind() == ShaderPackMenu.Kind.PAGE) continue;
            String label = entry.kind() == ShaderPackMenu.Kind.PROFILE
                ? Component.translatable("tapetumshaders.gui.options.profile").getString() : optionLabel(entry.name());
            graphics.text(font, TransparentWidgets.fit(label, panelWidth / 2 - 16), left + 6, 90 + i * 24, 0xFFFFFFFF);
        }
        if (visible.isEmpty()) graphics.centeredText(font,
            Component.translatable("tapetumshaders.gui.no_results"), width / 2, 92, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(Integer.toString(page + 1)), width / 2, height - 70, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(TransparentWidgets.fit(status, panelWidth - 12)),
            width / 2, height - 44, 0xFFFFFFFF);
        super.extractRenderState(graphics, x, y, tick);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
