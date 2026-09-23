package dev.tapetum.shaders.gui;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Paint-only styling for explicitly registered widgets; vanilla retains input, narration and callbacks. */
public final class TransparentWidgets {
    public static final int PANEL = 0x70101418;
    public static final int ROW = 0x50101418;
    public static final int SHEET = 0x50000000;
    public static final int HOVER = 0x80303B3D;
    public static final int ACCENT = 0xFF9AE6D2;
    private record Style(OptionInstance<?> option, boolean selected, boolean toggle) { }
    private static final Map<AbstractWidget, Style> STYLES = new WeakHashMap<>();

    private TransparentWidgets() { }

    public static <T extends AbstractWidget> T style(T widget) {
        STYLES.put(widget, new Style(null, false, false));
        return widget;
    }

    public static void option(AbstractWidget widget, OptionInstance<?> option) {
        STYLES.put(widget, new Style(option, false, false));
    }

    public static void selected(AbstractWidget widget, boolean selected) {
        STYLES.put(widget, new Style(null, selected, false));
    }

    public static void toggle(net.minecraft.client.gui.components.CycleButton<?> widget) {
        STYLES.put(widget, new Style(null, false, true));
    }

    public static boolean paint(AbstractWidget widget, GuiGraphicsExtractor graphics, Double slider) {
        Style style = STYLES.get(widget);
        if (style == null) return false;
        var font = Minecraft.getInstance().font;
        int x = widget.getX(), y = widget.getY(), w = widget.getWidth(), h = widget.getHeight();
        int foreground = widget.active ? 0xFFFFFFFF : 0xFFAAAAAA;
        graphics.fill(x, y, x + w, y + h, widget.isHoveredOrFocused() ? HOVER : ROW);
        if (style.option() == null && !style.toggle() && !style.selected())
            graphics.outline(x, y, w, h, widget.active ? 0x809FA8AD : 0x405F686D);
        if (style.selected()) graphics.fill(x, y, x + 2, y + h, ACCENT);
        if (widget.isFocused()) graphics.outline(x, y, w, h, ACCENT);

        if (slider != null) {
            int fill = (int) Math.round(Math.clamp(slider, 0.0, 1.0) * Math.max(0, w - 2));
            graphics.fill(x + 1, y + h - 2, x + 1 + fill, y + h - 1, widget.active ? ACCENT : 0xFF777777);
        }

        if (style.toggle() && widget instanceof net.minecraft.client.gui.components.CycleButton<?> toggle) {
            int boxX = x + (w - 10) / 2, boxY = y + (h - 10) / 2;
            graphics.outline(boxX, boxY, 10, 10, foreground);
            if (Boolean.parseBoolean(String.valueOf(toggle.getValue())))
                graphics.fill(boxX + 2, boxY + 2, boxX + 8, boxY + 8, ACCENT);
        } else if (style.option() != null) {
            String label = style.option().toString();
            int valueWidth;
            if (style.option().get() instanceof Boolean enabled) {
                valueWidth = 12;
                graphics.outline(x + w - 14, y + (h - 8) / 2, 8, 8, foreground);
                if (enabled) graphics.fill(x + w - 12, y + (h - 8) / 2 + 2,
                    x + w - 8, y + (h - 8) / 2 + 6, widget.active ? ACCENT : foreground);
            } else {
                String value = valueText(widget.getMessage(), style.option());
                value = fit(value, Math.max(24, w / 2 - 12));
                valueWidth = font.width(value) + 8;
                graphics.text(font, value, x + w - 6 - font.width(value), y + (h - 9) / 2, foreground);
            }
            graphics.text(font, fit(label, w - valueWidth - 16), x + 6, y + (h - 9) / 2, foreground);
        } else {
            String label = fit(widget.getMessage().getString(), w - 12);
            graphics.text(font, label, x + (w - font.width(label)) / 2, y + (h - 9) / 2,
                style.selected() ? ACCENT : foreground);
        }
        return true;
    }

    private static String valueText(Component message, OptionInstance<?> option) {
        if (message.getContents() instanceof TranslatableContents text && text.getArgs().length == 2) {
            Object value = text.getArgs()[1];
            return value instanceof Component component ? component.getString() : String.valueOf(value);
        }
        return String.valueOf(option.get());
    }

    public static String fit(String text, int width) {
        var font = Minecraft.getInstance().font;
        if (font.width(text) <= width) return text;
        if (width < font.width("...")) return "";
        return font.plainSubstrByWidth(text, width - font.width("...")) + "...";
    }
}
