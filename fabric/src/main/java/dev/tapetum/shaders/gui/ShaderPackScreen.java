package dev.tapetum.shaders.gui;

import dev.tapetum.shaders.TapetumShaders;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import dev.tapetum.shaders.compat.VersionCompat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The shaderpack picker, laid out to match Iris' own shaderpack screen: the shaders on/off state and
 * a "download shaders" shortcut sit above the pack list, and the bottom carries a folder/settings
 * row over the Cancel/Apply/Done row.
 *
 * <p>Selecting a row only stages a choice; nothing is written or reloaded until Apply or Done, the
 * same model Iris uses. That matters beyond taste — the earlier build applied instantly and then
 * reopened the screen on every click, which both churned the pipeline needlessly and put two
 * screens' backgrounds in one frame, crashing on the once-per-frame background blur.</p>
 */
public class ShaderPackScreen extends Screen {
	private static final int TITLE_Y = 10;
	private static final int SUBTITLE_Y = 22;

	/** Where the two stacked header buttons (shaders toggle, download) begin. */
	private static final int HEADER_TOP = 36;
	private static final int HEADER_BUTTON_WIDTH = 304;
	private static final int HEADER_BUTTON_HEIGHT = 20;
	private static final int HEADER_BUTTON_GAP = 4;
	/** Room below the header buttons for the drag-and-drop hint before the list starts. */
	private static final int HEADER_HINT_HEIGHT = 18;

	private static final int LIST_ROW_HEIGHT = 20;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 8;

	/** Room below the list for the two footer button rows. */
	private static final int FOOTER_HEIGHT = 60;
	/** Where the footer's shaded band and its separator line start. */
	private static final int FOOTER_BAR_TOP = 56;
	private static final int HINT_COLOR = 0xFFA0A0A0;
	private static final int SUBTITLE_COLOR = 0xFFBFBFBF;
	private static final int WATERMARK_COLOR = 0xFF808080;
	private static final int FOOTER_BAR_COLOR = 0x66000000;
	private static final int SEPARATOR_COLOR = 0xFF4C4C4C;

	/** Where "Download Shaders" sends the player — the same catalogue Iris links to. */
	private static final String SHADER_DOWNLOAD_URL = "https://modrinth.com/shaders";

	/** Bottom-left build stamp, the way Iris labels its own shaderpack screen. */
	private static final String MOD_WATERMARK = "Tapetum Shaders " + FabricLoader.getInstance()
		.getModContainer(TapetumShaders.MOD_ID)
		.map(container -> container.getMetadata().getVersion().getFriendlyString())
		.orElse("");

	/** Null when opened straight from gameplay by keybind, i.e. there is no screen to go back to. */
	private final Screen parent;

	// Assigned in init(), which Minecraft runs before the screen is drawn or interacted with - but
	// not before construction, so every method that can run outside that window guards for null.
	private ShaderPackListWidget packList;
	private Button shadersToggleButton;
	private Button applyButton;
	private Button packSettingsButton;
	private boolean returningFromEngine;
	/** Left edge of the Cancel/Apply/Done row, so the watermark can avoid colliding with it. */
	private int confirmRowLeft;

	/** Staged, not yet written to the config — see the class docs. */
	private boolean pendingShadersEnabled;

	/**
	 * What happened on the last Apply, shown on screen. Without this the only report of a pack
	 * failing to compile is a line in the log: the screen would keep showing the pack as selected
	 * while rendering stayed vanilla, which reads as "applying does nothing".
	 */
	private Component statusMessage = net.minecraft.network.chat.Component.empty();
	private Component failureTooltip;

	public ShaderPackScreen(Screen parent) {
		super(net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.title"));
		this.parent = parent;
		this.pendingShadersEnabled = TapetumShaders.getConfig().areShadersEnabled();
	}

	@Override
	protected void init() {
		super.init();
		if (returningFromEngine) {
			TapetumShaders.getShaderEngine().syncSelection();
			pendingShadersEnabled = TapetumShaders.getConfig().areShadersEnabled();
			packList = null;
			returningFromEngine = false;
		}

		int headerBottom = HEADER_TOP
			+ HEADER_BUTTON_HEIGHT * 2 + HEADER_BUTTON_GAP
			+ HEADER_HINT_HEIGHT;
		int listBottom = this.height - FOOTER_HEIGHT;
		int listHeight = Math.max(LIST_ROW_HEIGHT, listBottom - headerBottom);

		// init() runs again on every resize (resize -> repositionElements -> rebuildWidgets -> init),
		// and rebuildWidgets only clears the screen's widget lists - the previous list object, and
		// with it the staged selection, is still on this field. Carry that across, or a resize would
		// silently snap the selection back to whatever is currently applied.
		String selectedPack = packList != null
			? packList.getSelectedPackName()
			: TapetumShaders.getConfig().getShaderPackName().orElse(null);

		int headerX = this.width / 2 - HEADER_BUTTON_WIDTH / 2;

		shadersToggleButton = this.addRenderableWidget(Button.builder(shadersToggleLabel(), button -> {
				pendingShadersEnabled = !pendingShadersEnabled;
				button.setMessage(shadersToggleLabel());
				refreshApplyState();
			}).bounds(headerX, HEADER_TOP, HEADER_BUTTON_WIDTH, HEADER_BUTTON_HEIGHT).build());

		this.addRenderableWidget(Button.builder(
			net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.download_shaders"),
			button -> VersionCompat.openUri(SHADER_DOWNLOAD_URL))
			.bounds(headerX, HEADER_TOP + HEADER_BUTTON_HEIGHT + HEADER_BUTTON_GAP, HEADER_BUTTON_WIDTH, HEADER_BUTTON_HEIGHT)
			.tooltip(Tooltip.create(Component.translatable("tapetumshaders.gui.download_shaders.tooltip"))).build());

		packList = this.addWidget(
			new ShaderPackListWidget(this.minecraft, this.width, listHeight, headerBottom, LIST_ROW_HEIGHT));
		packList.setSelectionListener(this::onSelectionChanged);
		refreshPackList(selectedPack);

		int actionRowY = this.height - 52;
		int confirmRowY = this.height - 26;

		// Folder and per-pack settings share the upper footer row, as they do in Iris.
		int actionWidth = 152;
		int actionLeftX = this.width / 2 - actionWidth - BUTTON_GAP / 2;
		int actionRightX = this.width / 2 + BUTTON_GAP / 2;

		this.addRenderableWidget(Button.builder(
			net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.open_folder"),
			button -> VersionCompat.openPath(TapetumShaders.getShaderpacksDirectory()))
			.bounds(actionLeftX, actionRowY, actionWidth, BUTTON_HEIGHT).build());

		packSettingsButton = this.addRenderableWidget(
			Button.builder(
				net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.pack_settings"), button -> {
					if (hasPendingChanges() && !applyChanges()) return;
					TapetumShaders.getShaderEngine().openPackOptions(this).ifPresent(options -> {
						returningFromEngine = true;
						this.minecraft.setScreen(options);
					});
				}).bounds(actionRightX, actionRowY, actionWidth, BUTTON_HEIGHT).build());

		// Cancel / Apply / Done share the bottom row, so they are narrower than the row above.
		int confirmWidth = (actionWidth * 2 + BUTTON_GAP - BUTTON_GAP * 2) / 3;
		int confirmX = this.width / 2 - (confirmWidth * 3 + BUTTON_GAP * 2) / 2;
		confirmRowLeft = confirmX;

		this.addRenderableWidget(Button.builder(
			net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.cancel"), button -> this.onClose())
			.bounds(confirmX, confirmRowY, confirmWidth, BUTTON_HEIGHT).build());

		applyButton = this.addRenderableWidget(Button.builder(Component.translatable("tapetumshaders.gui.apply"),
			button -> applyChanges()).bounds(confirmX + confirmWidth + BUTTON_GAP, confirmRowY,
			confirmWidth, BUTTON_HEIGHT).build());

		this.addRenderableWidget(Button.builder(net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.done"), button -> {
				if (hasPendingChanges()) {
					if (!applyChanges()) return;
				}
				this.onClose();
			}).bounds(confirmX + (confirmWidth + BUTTON_GAP) * 2, confirmRowY, confirmWidth, BUTTON_HEIGHT).build());

		refreshApplyState();
	}

	/**
	 * The header button's label. With no packs installed there is nothing for the toggle to act on,
	 * so it says so rather than offering an on/off state that would change nothing — the same thing
	 * Iris' "Shaders: No Packs Present" does.
	 */
	private Component shadersToggleLabel() {
		if (TapetumShaders.getShaderpackManager().getAvailablePacks().isEmpty()) {
			return net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.shaders_enabled",
				net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.no_packs"));
		}

		return net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.shaders_enabled",
			net.minecraft.network.chat.Component.translatable(pendingShadersEnabled ? "tapetumshaders.gui.on" : "tapetumshaders.gui.off"));
	}

	/** Rescans the shaderpacks folder and rebuilds the rows, keeping {@code selectedPack} picked. */
	private void refreshPackList(String selectedPack) {
		TapetumShaders.getShaderpackManager().refresh();

		if (packList == null) {
			return;
		}

		packList.refill(
			TapetumShaders.getShaderpackManager().getAvailablePacks(),
			TapetumShaders.getConfig().getShaderPackName().orElse(null),
			selectedPack);

		// The toggle's label depends on whether any packs exist, which a rescan can change.
		if (shadersToggleButton != null) {
			shadersToggleButton.setMessage(shadersToggleLabel());
		}
	}

	private boolean hasPendingChanges() {
		if (packList == null) {
			// Before init(): the shaders toggle is the only thing that could differ, and the pack
			// selection cannot have been touched yet.
			return pendingShadersEnabled != TapetumShaders.getConfig().areShadersEnabled();
		}

		return pendingShadersEnabled != TapetumShaders.getConfig().areShadersEnabled()
			|| !Objects.equals(packList.getSelectedPackName(),
				TapetumShaders.getConfig().getShaderPackName().orElse(null));
	}

	private boolean applyChanges() {
		if (packList == null) {
			return false;
		}

		TapetumShaders.getConfig().setShadersEnabled(pendingShadersEnabled);
		TapetumShaders.getConfig().setShaderPackName(packList.getSelectedPackName());
		boolean applied = TapetumShaders.saveConfigAndReload();

		// Rebuild in place - the applied-pack highlight moved, and reopening the screen to show
		// that is what used to crash on the once-per-frame background blur.
		refreshPackList(packList.getSelectedPackName());

		statusMessage = applied ? describeResult(packList.getSelectedPackName())
			: net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.status.failed", packList.getSelectedPackName())
				.withStyle(ChatFormatting.RED);
		failureTooltip = TapetumShaders.getShaderEngine().lastFailure()
			.map(error -> net.minecraft.network.chat.Component.literal(String.valueOf(error.getMessage()))).orElse(null);
		return applied;
	}

	/**
	 * Reports what the pipeline actually ended up doing, rather than what was requested — the two
	 * differ whenever a pack fails to compile, and that difference is the whole point of showing it.
	 */
	private Component describeResult(String appliedPack) {
		if (appliedPack == null) {
			return net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.status.none").withStyle(ChatFormatting.GRAY);
		}

		if (!TapetumShaders.getConfig().areShadersEnabled()) {
			return net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.status.disabled").withStyle(ChatFormatting.GRAY);
		}

		if (TapetumShaders.getPipelineManager().getPipeline().isShaderPackActive()) {
			return net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.status.active", appliedPack)
				.withStyle(ChatFormatting.YELLOW);
		}

		return net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.status.failed", appliedPack)
			.withStyle(ChatFormatting.RED);
	}

	/**
	 * Picking a different pack invalidates whatever the last Apply reported, so the old result is
	 * cleared rather than left sitting under a selection it no longer describes.
	 */
	private void onSelectionChanged() {
		statusMessage = net.minecraft.network.chat.Component.empty();
		refreshApplyState();
	}

	/**
	 * Apply stays clickable at all times, as it does in Iris.
	 *
	 * <p>Gating it on "is the selection different from what is saved" looked tidy and was wrong on
	 * two counts. Applying is not only how a <em>new</em> choice is stored, it is also the only way
	 * to rebuild the pipeline for the choice already stored — so a pack that failed to compile left
	 * the button greyed out at exactly the moment the player wanted to retry. Worse, nothing told
	 * the screen when the list selection moved, so with no pack saved the button stayed disabled
	 * even after picking one: the state it was gated on was never recomputed.</p>
	 */
	private void refreshApplyState() {
		if (packSettingsButton != null) {
			packSettingsButton.active = packList != null && packList.getSelectedPackName() != null;
		}
		if (applyButton != null) {
			applyButton.setTooltip(failureTooltip == null ? null : Tooltip.create(failureTooltip));
			applyButton.active = true;
		}
	}

	/**
	 * Installs shaderpacks dragged onto the window, so a pack can be added without hunting for the
	 * folder first.
	 */
	@Override
	public void onFilesDrop(List<Path> files) {
		String lastInstalled = null;

		for (Path source : files) {
			if (!isShaderpackCandidate(source)) {
				TapetumShaders.LOGGER.info("Ignoring dropped file '{}': not a shaderpack folder or .zip", source);
				continue;
			}

			try {
				Path destination = TapetumShaders.getShaderpacksDirectory().resolve(source.getFileName().toString());
				Files.createDirectories(TapetumShaders.getShaderpacksDirectory());

				if (Files.isDirectory(source)) {
					copyDirectory(source, destination);
				} else {
					Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
				}

				lastInstalled = destination.getFileName().toString();
				TapetumShaders.LOGGER.info("Installed dropped shaderpack '{}'", lastInstalled);
			} catch (IOException e) {
				TapetumShaders.LOGGER.error("Failed to install dropped shaderpack '{}'", source, e);
			}
		}

		// Select what was just dropped: that is almost always why it was dropped.
		refreshPackList(lastInstalled != null || packList == null ? lastInstalled : packList.getSelectedPackName());
		refreshApplyState();
	}

	/** Shaderpacks are a folder or a zip of one; anything else dropped here is not for us. */
	private static boolean isShaderpackCandidate(Path source) {
		return Files.isDirectory(source)
			|| source.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
	}

	private static void copyDirectory(Path source, Path destination) throws IOException {
		try (Stream<Path> entries = Files.walk(source)) {
			for (Path entry : entries.toList()) {
				Path target = destination.resolve(source.relativize(entry).toString());

				if (Files.isDirectory(entry)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	@Override
	public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
		if (minecraft.level == null) renderBackground(pose);
		packList.render(pose, mouseX, mouseY, partialTick);

		drawCenteredString(pose, this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);
		drawCenteredString(pose, this.font, net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.subtitle"),
			this.width / 2, SUBTITLE_Y, SUBTITLE_COLOR);

		// The drop hint belongs with the header buttons it sits under, above the list.
		int hintY = HEADER_TOP + HEADER_BUTTON_HEIGHT * 2 + HEADER_BUTTON_GAP + 5;
		drawCenteredString(pose, this.font,
			net.minecraft.network.chat.Component.translatable("tapetumshaders.gui.drop_hint").withStyle(ChatFormatting.ITALIC),
			this.width / 2, hintY, HINT_COLOR);

		// Sits in the footer band so it cannot be pushed off-screen by a long pack list.
		if (!statusMessage.getString().isEmpty()) {
			drawCenteredString(pose, this.font, this.font.plainSubstrByWidth(statusMessage.getString(), width - 16),
				this.width / 2, this.height - FOOTER_BAR_TOP - 12, 0xFFFFFFFF);
		}

		// Shaded band behind the action buttons, so they read as a footer rather than as widgets
		// floating over the world.
		int barTop = this.height - FOOTER_BAR_TOP;
		fill(pose, 0, barTop, this.width, this.height, FOOTER_BAR_COLOR);
		fill(pose, 0, barTop, this.width, barTop + 1, SEPARATOR_COLOR);

		// Bottom-left, level with the confirm row - so only draw it when the window is wide enough
		// that it cannot run into the Cancel button.
		if (4 + this.font.width(MOD_WATERMARK) + BUTTON_GAP < confirmRowLeft) {
			drawString(pose, this.font, MOD_WATERMARK, 4, this.height - this.font.lineHeight - 3, WATERMARK_COLOR);
		}

		super.render(pose, mouseX, mouseY, partialTick);
	}

	/**
	 * Returns to whatever opened this screen — or to the game itself, when nothing did.
	 *
	 * <p>Passing a null screen is the supported way to say "close this and go back to playing", not
	 * an oversight: {@code Gui.setScreen} branches on the argument being null and picks the in-game
	 * GUI (or the title screen when there is no level). Verified in 26.2's own bytecode rather than
	 * assumed, because the parameter carries no nullability annotation, which makes a null-analysis
	 * flag this call even though it is correct.</p>
	 */
	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
