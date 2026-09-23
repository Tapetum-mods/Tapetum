package dev.tapetum.shaders.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsLayoutTest {
    @Test
    void fitsSupportedGuiSizesWithoutFooterOrSidebarOverlap() {
        for (int width : new int[]{320, 427, 512, 640, 854, 1024, 1920}) {
            for (int height : new int[]{240, 360, 480, 624, 1080}) {
                var layout = SettingsLayout.fit(width, height);
                assertTrue(layout.left() >= 0);
                assertTrue(layout.right() <= width);
                assertTrue(layout.contentLeft() > layout.left() + layout.sidebarWidth());
                assertTrue(layout.contentWidth() >= 220);
                assertTrue(layout.top() + layout.contentHeight() < layout.footerY());
                assertTrue(layout.footerY() + 20 <= height);
                assertTrue(layout.top() + 26 + 4 * 23 + 8 + 22 <= layout.footerY() - 6);
            }
        }
    }

    @Test
    void largeWindowsDoNotStretchTheControlsIndefinitely() {
        var layout = SettingsLayout.fit(1920, 1080);
        assertEquals(560, layout.right() - layout.left());
        assertEquals(122, layout.sidebarWidth());
    }
}
