package dev.tapetum.shaders.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * The scrollable list of installed shaderpacks on {@link ShaderPackScreen}.
 *
 * <p>Using a real list widget rather than a column of buttons is what makes the screen behave with
 * more than a couple of packs: rows scroll instead of running off the bottom of the screen, and
 * names too long for a row are ellipsized instead of overflowing it.</p>
 */
public class ShaderPackListWidget extends ObjectSelectionList<ShaderPackListWidget.PackEntry> {
	/**
	 * Run whenever the player picks a different row. Without it the screen has no way to know the
	 * selection moved: {@code AbstractSelectionList} keeps that state to itself, so anything on the
	 * screen derived from it silently goes stale the moment a row is clicked.
	 */
	private Runnable selectionListener = () -> {
	};
	private static final int ROW_WIDTH = 300;
	/** Teal, matching the mod's accent elsewhere, for the pack that is actually loaded right now. */
	private static final int APPLIED_COLOR = 0xFF4ECDC4;
	private static final int NORMAL_COLOR = 0xFFFFFFFF;

	public ShaderPackListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
		super(minecraft, width, height, y, itemHeight);
	}

	public void setSelectionListener(Runnable selectionListener) {
		this.selectionListener = selectionListener;
	}

	@Override
	public int getRowWidth() {
		return ROW_WIDTH;
	}

	/**
	 * Rebuilds the rows: a "none" entry followed by every installed pack.
	 *
	 * @param packNames    installed packs, as returned by the shaderpack manager
	 * @param appliedPack  the pack currently loaded (null for none), highlighted as active
	 * @param selectedPack the pack to leave selected (null for none), i.e. the pending choice
	 */
	public void refill(List<String> packNames, String appliedPack, String selectedPack) {
		clearEntries();

		PackEntry noneEntry = new PackEntry(null, Component.translatable("tapetumshaders.gui.none_selected"),
			appliedPack == null);
		addEntry(noneEntry);
		PackEntry toSelect = selectedPack == null ? noneEntry : null;

		for (String packName : packNames) {
			PackEntry entry = new PackEntry(packName, Component.literal(packName),
				Objects.equals(packName, appliedPack));
			addEntry(entry);

			if (Objects.equals(packName, selectedPack)) {
				toSelect = entry;
			}
		}

		// A pending selection can name a pack that has since been deleted from disk; fall back to
		// "none" rather than leaving the list with nothing selected.
		setSelected(toSelect != null ? toSelect : noneEntry);
	}

	/** The pack the user has picked, or null for "none". */
	public String getSelectedPackName() {
		PackEntry selected = getSelected();
		return selected == null ? null : selected.packName;
	}

	public class PackEntry extends ObjectSelectionList.Entry<PackEntry> {
		/** Null for the "none" row, which stands for vanilla rendering rather than a pack. */
		private final String packName;
		private final Component label;
		private final boolean applied;

		PackEntry(String packName, Component label, boolean applied) {
			this.packName = packName;
			this.label = label;
			this.applied = applied;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			var font = ShaderPackListWidget.this.minecraft.font;
			int textY = getContentY() + (getContentHeight() - font.lineHeight) / 2;

			// Ellipsize rather than letting a long pack name spill past the row.
			String text = label.getString();
			int available = getContentWidth();
			if (font.width(text) > available) {
				text = font.plainSubstrByWidth(text, Math.max(0, available - font.width("..."))) + "...";
			}

			guiGraphics.text(font, text, getContentX(), textY, applied ? APPLIED_COLOR : NORMAL_COLOR);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
			// AbstractSelectionList doesn't select on click by itself - it only routes the event to
			// the entry under the cursor - so selection has to happen here.
			ShaderPackListWidget.this.setSelected(this);
			ShaderPackListWidget.this.selectionListener.run();
			return true;
		}

		@Override
		public Component getNarration() {
			return label;
		}
	}
}
