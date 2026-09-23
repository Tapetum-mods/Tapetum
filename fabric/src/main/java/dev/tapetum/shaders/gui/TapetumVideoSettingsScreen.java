package dev.tapetum.shaders.gui;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

/** Uses vanilla options, GPU warnings, reloads and persistence with a transparent Tapetum layout. */
public final class TapetumVideoSettingsScreen extends VideoSettingsScreen {
    private enum Category {
        GENERAL("general"), QUALITY("quality"), PERFORMANCE("performance"), ADVANCED("advanced");
        final String key;
        Category(String key) { this.key = "tapetumshaders.gui.category." + key; }
        Component label() { return Component.translatable(key); }
    }

    private Category category = Category.GENERAL;
    private final Map<OptionInstance<?>, Category> categories = new IdentityHashMap<>();
    private final Map<Category, Button> categoryButtons = new EnumMap<>(Category.class);
    private SettingsLayout geometry;
    private EditBox search;
    private String query = "";

    public TapetumVideoSettingsScreen(Screen parent, Minecraft client) {
        super(parent, client, client.options);
        group(Category.GENERAL, options.renderDistance(), options.simulationDistance(), options.gamma(),
            options.guiScale(), options.fullscreen(), options.enableVsync(), options.framerateLimit(),
            options.attackIndicator(), options.showAutosaveIndicator());
        group(Category.QUALITY, options.graphicsPreset(), options.improvedTransparency(), options.cloudStatus(),
            options.cloudRange(), options.weatherRadius(), options.cutoutLeaves(), options.particles(),
            options.ambientOcclusion(), options.biomeBlendRadius(), options.entityDistanceScaling(),
            options.entityShadows(), options.vignette(), options.mipmapLevels(), options.textureFiltering(),
            options.maxAnisotropyBit());
        group(Category.PERFORMANCE, options.prioritizeChunkUpdates(), options.inactivityFpsLimit(),
            options.chunkSectionFadeInTime());
        group(Category.ADVANCED, options.exclusiveFullscreen(), options.menuBackgroundBlurriness());
    }

    private void group(Category group, OptionInstance<?>... entries) {
        for (var option : entries) categories.put(option, group);
    }

    @Override
    protected void init() {
        // Flush delayed vanilla sliders before replacing their widgets on resize.
        if (list != null) list.applyUnsavedChanges();
        geometry = SettingsLayout.fit(width, height);
        categoryButtons.clear();
        search = new EditBox(font, geometry.left() + 5, 12,
            geometry.right() - geometry.left() - 10, 18, Component.translatable("tapetumshaders.gui.search"));
        search.setBordered(false);
        search.setHint(Component.translatable("tapetumshaders.gui.search"));
        search.setValue(query);
        search.setResponder(value -> { query = value; refreshOptions(); });
        addRenderableWidget(search);

        int y = geometry.top() + 26;
        for (Category item : Category.values()) {
            var button = addRenderableWidget(TransparentWidgets.style(Button.builder(item.label(), ignored -> {
                category = item;
                search.setValue("");
                refreshOptions();
            }).bounds(geometry.left(), y, geometry.sidebarWidth(), 22).build()));
            categoryButtons.put(item, button);
            y += 23;
        }
        addRenderableWidget(TransparentWidgets.style(Button.builder(
            Component.translatable("tapetumshaders.gui.title"), ignored -> {
                list.applyUnsavedChanges();
                minecraft.setScreenAndShow(new ShaderPackScreen(this));
            }).bounds(geometry.left(), y + 8, geometry.sidebarWidth(), 22).build()));

        list = addRenderableWidget(new TransparentOptionsList());
        refreshOptions();
        int buttonWidth = Math.min(96, (geometry.contentWidth() - 6) / 2);
        addRenderableWidget(TransparentWidgets.style(Button.builder(Component.translatable("tapetumshaders.gui.apply"),
            ignored -> { list.applyUnsavedChanges(); options.save(); })
            .bounds(geometry.right() - buttonWidth * 2 - 6, geometry.footerY(), buttonWidth, 20).build()));
        addRenderableWidget(TransparentWidgets.style(Button.builder(Component.translatable("tapetumshaders.gui.done"),
            ignored -> onClose()).bounds(geometry.right() - buttonWidth, geometry.footerY(), buttonWidth, 20).build()));
    }

    private void refreshOptions() {
        if (!(list instanceof TransparentOptionsList rows)) return;
        rows.applyUnsavedChanges();
        rows.clear();
        // Includes the real fullscreen resolution option and all options introduced by this game version.
        super.addOptions();
        categoryButtons.forEach((item, button) -> TransparentWidgets.selected(button, query.isBlank() && category == item));
    }

    @Override protected void repositionElements() { rebuildWidgets(); }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // In a world, leave the scene untouched: no full-screen dark overlay and no second blur.
        if (minecraft.level == null) extractPanorama(graphics, delta);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(geometry.left(), 6, geometry.right(), 32, TransparentWidgets.PANEL);
        graphics.fill(geometry.left(), geometry.top(), geometry.left() + geometry.sidebarWidth(),
            geometry.footerY() - 6, TransparentWidgets.PANEL);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("tapetumshaders", "logo"),
            geometry.left() + 4, geometry.top() + 2, 22, 22);
        graphics.text(font, TransparentWidgets.fit("Tapetum", geometry.sidebarWidth() - 34),
            geometry.left() + 30, geometry.top() + 8, TransparentWidgets.ACCENT);
        graphics.fill(geometry.contentLeft(), geometry.top(), geometry.right(), geometry.top() + 22, TransparentWidgets.PANEL);
        graphics.text(font, query.isBlank() ? category.label() : Component.translatable("tapetumshaders.gui.search_results"),
            geometry.contentLeft() + 6, geometry.top() + 7, TransparentWidgets.ACCENT);
        if (list.children().isEmpty()) graphics.text(font, Component.translatable("tapetumshaders.gui.no_results"),
            geometry.contentLeft() + 6, geometry.top() + 32, 0xFFFFFFFF);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private final class TransparentOptionsList extends OptionsList {
        TransparentOptionsList() {
            super(TapetumVideoSettingsScreen.this.minecraft, geometry.contentWidth(), TapetumVideoSettingsScreen.this);
            updateSizeAndPosition(geometry.contentWidth(), Math.max(20, geometry.contentHeight() - 26),
                geometry.contentLeft(), geometry.top() + 26);
        }

        void clear() { clearEntries(); setScrollAmount(0); }

        @Override public void addHeader(Component header) { }
        @Override public void addSmall(OptionInstance<?>... options) { for (var option : options) addBig(option); }

        @Override
        public void addBig(OptionInstance<?> option) {
            boolean matches = query.isBlank() ? categories.getOrDefault(option, Category.GENERAL) == category
                : option.toString().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
            if (!matches) return;
            super.addBig(option);
            AbstractWidget widget = findOption(option);
            if (widget != null) TransparentWidgets.option(widget, option);
        }

        @Override public int getRowWidth() { return Math.max(1, getWidth() - 8); }
        @Override protected void extractListBackground(GuiGraphicsExtractor graphics) { }
        @Override protected void extractListSeparators(GuiGraphicsExtractor graphics) { }

        @Override
        protected void extractItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, AbstractEntry entry) {
            // Vanilla's Entry centers its widgets on the whole screen; use this list's actual bounds instead.
            entry.visitWidgets(widget -> {
                widget.setPosition(getRowLeft(), entry.getContentY());
                widget.setWidth(getRowWidth());
                widget.extractRenderState(graphics, mouseX, mouseY, delta);
            });
        }
    }
}
