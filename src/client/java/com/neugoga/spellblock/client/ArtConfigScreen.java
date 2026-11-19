package com.neugoga.spellblock.client;

import com.neugoga.spellblock.config.ArtQuality;
import com.neugoga.spellblock.config.SpellBlockConfig;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client settings page for SpellBlock card images. Opened from the SpellBlock keybind. */
public final class ArtConfigScreen extends Screen {
	private static final int GOLD = 0xFFC4A574;
	private static final int TEXT = 0xFFE6D3A3;
	private static final int MUTED = 0xFFA89880;
	private static final int PANEL = 0xFF2C1E14;
	private static final int PANEL_EDGE = 0xFF4A3424;

	private final Screen parent;
	private Button qualityButton;
	private Button cacheButton;

	public ArtConfigScreen(Screen parent) {
		super(Component.literal("SpellBlock"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height / 2 - 36;

		qualityButton = addRenderableWidget(Button.builder(qualityLabel(), b -> {
			ArtQuality next = SpellBlockConfig.get().artQuality().next();
			SpellBlockConfig.get().setArtQuality(next);
			CardArt.onConfigChanged();
			b.setMessage(qualityLabel());
			if (cacheButton != null) {
				cacheButton.setMessage(Component.literal("Reload downloaded art"));
			}
		}).bounds(cx - 140, y, 280, 20).build());

		y += 28;
		cacheButton = addRenderableWidget(Button.builder(Component.literal("Reload downloaded art"), b -> {
			int removed = CardArt.clearDiskCache();
			CardArt.onConfigChanged();
			b.setMessage(Component.literal("Cleared " + removed + " file(s)"));
		}).bounds(cx - 140, y, 280, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(cx - 50, y + 36, 100, 20).build());
	}

	private static Component qualityLabel() {
		return Component.literal("Art: " + SpellBlockConfig.get().artQuality().label());
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0xC0100C0A);
		int x = width / 2 - 160;
		int y = height / 2 - 78;
		int w = 320;
		int h = 168;
		graphics.fill(x, y, x + w, y + h, PANEL);
		graphics.fill(x, y, x + 6, y + h, PANEL_EDGE);
		graphics.fill(x + w - 6, y, x + w, y + h, PANEL_EDGE);
		graphics.fill(x, y, x + w, y + 1, GOLD);
		graphics.fill(x, y + h - 1, x + w, y + h, GOLD);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		int y = height / 2 - 78;
		graphics.drawCenteredString(font, title, width / 2, y + 10, GOLD);
		graphics.drawCenteredString(font, "Normal · Large · High", width / 2, y + 24, TEXT);
		graphics.drawCenteredString(font, "Cached under SpellBlock/art", width / 2, y + 152, MUTED);
	}

	@Override
	public void onClose() {
		if (minecraft != null) {
			minecraft.setScreen(parent);
		}
	}
}
