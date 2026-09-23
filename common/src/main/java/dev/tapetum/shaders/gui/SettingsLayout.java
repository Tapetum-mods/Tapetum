package dev.tapetum.shaders.gui;

/** Coordinates in Minecraft GUI pixels, independent of display scale and rendering APIs. */
public record SettingsLayout(int left, int top, int sidebarWidth, int contentLeft,
                             int contentWidth, int contentHeight, int footerY) {
    public static SettingsLayout fit(int width, int height) {
        int total = Math.min(560, Math.max(1, width - 16));
        int sidebar = Math.min(122, Math.max(72, total / 4));
        int left = (width - total) / 2;
        int top = 38;
        int footer = Math.max(top + 24, height - 28);
        return new SettingsLayout(left, top, sidebar, left + sidebar + 6,
            Math.max(1, total - sidebar - 6), Math.max(20, footer - top - 6), footer);
    }

    public int right() { return contentLeft + contentWidth; }
}
