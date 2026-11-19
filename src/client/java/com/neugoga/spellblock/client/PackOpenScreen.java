package com.neugoga.spellblock.client;

import com.neugoga.spellblock.network.FavouriteC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import static com.neugoga.spellblock.client.ui.Ui.rarityColor;
import static com.neugoga.spellblock.client.ui.Ui.rarityLabel;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.network.PackOpenS2CPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Rips a pack, then peels one card per click. The cards are already in the binder. */
public final class PackOpenScreen extends Screen {
	private static final long RIP_MS = 850;
	private static final long PEEL_MS = 200;
	private static final int PX = 4;
	private static final String[] PACK = {
		"##########",
		"#wwwwwwww#",
		"#w######w#",
		"#w#bbbb#w#",
		"#w#bGGb#w#",
		"#w#beeb#w#",
		"#w#beeb#w#",
		"#w#bGGb#w#",
		"#w#bbbb#w#",
		"#w######w#",
		"#wwwwwwww#",
		"#BBBBBBBB#",
		"#BggggggB#",
		"#BBBBBBBB#",
		"#BBBBBBBB#",
		"#BggggggB#",
		"##########"
	};

	private final String packName;
	private final java.util.List<BinderCard> cards;
	private final Set<String> favourited = new HashSet<>();
	private final long openedAt = System.currentTimeMillis();
	private int index;
	private long peelStart = -1;
	private boolean done;
	private boolean skipRip;

	public PackOpenScreen(PackOpenS2CPayload payload) {
		super(Component.literal(payload.name()));
		this.packName = payload.name();
		this.cards = payload.cards();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		CardArt.beginFrame();
		long now = System.currentTimeMillis();
		advancePeel(now);
		graphics.fill(0, 0, this.width, this.height, 0xC0100C0A);
		graphics.drawCenteredString(this.font, packName, this.width / 2, 28, 0xFFE6D3A3);
		if (!ripped(now)) {
			drawRip(graphics, (now - openedAt) / (float) RIP_MS);
			graphics.drawCenteredString(this.font, "Click to skip", this.width / 2, this.height - 36, 0xFFB7A48A);
		} else if (done || cards.isEmpty()) {
			graphics.drawCenteredString(this.font, "Added to your binder", this.width / 2, this.height / 2 - 8, 0xFFF4EFE4);
			graphics.drawCenteredString(this.font, "Click to close", this.width / 2, this.height / 2 + 8, 0xFFB7A48A);
		} else {
			int[] box = cardBox();
			drawStack(graphics, now, box[0], box[1], box[2], box[3]);
			graphics.drawCenteredString(this.font, "Right-click to favourite", this.width / 2, this.height - 36, 0xFFB7A48A);
		}
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 1 && ripped(System.currentTimeMillis()) && !done && index < cards.size()) {
			BinderCard card = cards.get(index);
			String key = favKey(card);
			if (!favourited.remove(key)) {
				favourited.add(key);
			}
			if (ClientPlayNetworking.canSend(FavouriteC2SPayload.TYPE)) {
				ClientPlayNetworking.send(new FavouriteC2SPayload(card.oracleId(), card.printingId(), card.finish(), "pack"));
			}
			return true;
		}
		if (button == 0) {
			advance();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private static String favKey(BinderCard card) {
		return card.oracleId() + "\0" + card.printingId() + "\0" + card.finish();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER) {
			advance();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	private void advance() {
		long now = System.currentTimeMillis();
		if (!ripped(now)) {
			skipRip = true;
			return;
		}
		if (done || cards.isEmpty()) {
			onClose();
			return;
		}
		if (peelStart < 0) {
			peelStart = now;
		}
	}

	private boolean ripped(long now) {
		return skipRip || now - openedAt >= RIP_MS;
	}

	private void advancePeel(long now) {
		if (peelStart < 0) {
			return;
		}
		if (now - peelStart < PEEL_MS) {
			return;
		}
		peelStart = -1;
		index++;
		if (index >= cards.size()) {
			done = true;
		}
	}

	private void drawRip(GuiGraphics graphics, float raw) {
		float t = Mth.clamp(raw, 0, 1);
		t = t * t * (3 - 2 * t);
		int[] box = cardBox();
		int cardX = box[0];
		int cardY = box[1];
		int cardW = box[2];
		int cardH = box[3];
		int pixH = (cardH + PACK.length - 1) / PACK.length;
		int pixW = (cardW + 16 + PACK[0].length() - 1) / PACK[0].length();
		int pix = Math.max(PX, Math.max(pixH, pixW));
		int packW = PACK[0].length() * pix;
		int packH = PACK.length * pix;
		int packX = cardX + cardW / 2 - packW / 2;
		int packY = cardY + cardH / 2 - packH / 2;
		int split = (int) (t * (packW * 0.7f));
		int mid = PACK[0].length() / 2;
		if (t > 0.05f && !cards.isEmpty()) {
			drawCard(graphics, cards.getFirst(), cardX, cardY, cardW, cardH);
		}
		drawPackHalf(graphics, packX - split, packY, pix, 0, mid);
		drawPackHalf(graphics, packX + mid * pix + split, packY, pix, mid, PACK[0].length());
	}

	private void drawPackHalf(GuiGraphics graphics, int originX, int originY, int pix, int fromCol, int toCol) {
		for (int row = 0; row < PACK.length; row++) {
			String line = PACK[row];
			for (int col = fromCol; col < toCol && col < line.length(); col++) {
				int color = ink(line.charAt(col));
				if (color == 0) {
					continue;
				}
				int x = originX + (col - fromCol) * pix;
				int y = originY + row * pix;
				graphics.fill(x, y, x + pix, y + pix, color);
			}
		}
	}

	private void drawStack(GuiGraphics graphics, long now, int x, int y, int w, int h) {
		int behind = Math.min(3, cards.size() - index - 1);
		for (int i = behind; i >= 1; i--) {
			drawCard(graphics, cards.get(index + i), x + i * 5, y + i * 4, w, h);
		}
		float dy = 0;
		if (peelStart >= 0) {
			float t = Mth.clamp((now - peelStart) / (float) PEEL_MS, 0, 1);
			t = t * t * (3 - 2 * t);
			dy = t * h;
		}
		BinderCard top = cards.get(index);
		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, -dy, 0.0f);
		drawCard(graphics, top, x, y, w, h);
		if (favourited.contains(favKey(top))) {
			String badge = "* Favourited";
			int bw = this.font.width(badge);
			graphics.fill(x + 4, y + 4, x + bw + 10, y + 16, 0xCC000000);
			graphics.drawString(this.font, badge, x + 7, y + 6, 0xFFF2C96B, false);
		}
		graphics.pose().popPose();
		String place = (index + 1) + " / " + cards.size();
		String extra = CardArt.caption(top.treatment(), top.finish());
		if (!extra.isEmpty()) {
			place = place + " · " + extra;
		}
		graphics.drawCenteredString(this.font, place, this.width / 2, y + h + 12, 0xFFE6D3A3);
		graphics.drawCenteredString(this.font, "Click to take the card", this.width / 2, y + h + 24, 0xFFB7A48A);
	}

	/** One card rectangle, portrait, shared by the rip and the stack so the first card does not resize. */
	private int[] cardBox() {
		int maxH = Math.max(112, this.height - 96);
		int maxW = Math.max(80, this.width - 48);
		int h = Math.min(220, maxH);
		int w = h * 5 / 7;
		if (w > maxW) {
			w = maxW;
			h = w * 7 / 5;
		}
		int x = (this.width - w) / 2;
		int y = (this.height - h) / 2 + 6;
		return new int[] {x, y, w, h};
	}

	private void drawCard(GuiGraphics graphics, BinderCard card, int x, int y, int w, int h) {
		int rim = rarityColor(card.rarity());
		graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, rim);
		if (CardArt.draw(graphics, card.printingId(), card.finish(), x, y, w, h)) {
			return;
		}
		graphics.fill(x, y, x + w, y + h, rim);
		graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF1A1714);
		graphics.fill(x + 8, y + 8, x + 14, y + h - 8, rim);
		int textW = w - 28;
		String[] lines = wrap(card.name(), textW);
		graphics.drawString(this.font, lines[0], x + 20, y + 12, 0xFFF4EFE4, false);
		int next = y + 24;
		if (!lines[1].isEmpty()) {
			graphics.drawString(this.font, lines[1], x + 20, next, 0xFFF4EFE4, false);
			next += 12;
		}
		if (!card.manaCost().isEmpty()) {
			graphics.drawString(this.font, fit(card.manaCost(), textW), x + 20, next + 4, 0xFFB7C4CE, false);
		}
		String type = fit(card.typeLine(), textW);
		graphics.drawString(this.font, type, x + 20, y + h - 36, 0xFFD9CBB2, false);
		String rarity = rarityLabel(card.rarity());
		String extra = CardArt.caption(card.treatment(), card.finish());
		if (!extra.isEmpty()) {
			rarity = rarity + " · " + extra;
		}
		graphics.drawString(this.font, fit(rarity, textW), x + 20, y + h - 22, rim, false);
	}

	private String fit(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int budget = maxWidth - this.font.width(ellipsis);
		while (!text.isEmpty() && this.font.width(text) > budget) {
			text = text.substring(0, text.length() - 1);
		}
		return text + ellipsis;
	}

	private String[] wrap(String text, int maxWidth) {
		if (text == null) {
			return new String[] {"", ""};
		}
		if (this.font.width(text) <= maxWidth) {
			return new String[] {text, ""};
		}
		int split = -1;
		for (int i = 1; i <= text.length(); i++) {
			if (this.font.width(text.substring(0, i)) > maxWidth) {
				int space = text.lastIndexOf(' ', i - 1);
				split = space > 0 ? space : i - 1;
				break;
			}
		}
		if (split <= 0) {
			return new String[] {fit(text, maxWidth), ""};
		}
		return new String[] {text.substring(0, split), fit(text.substring(split).trim(), maxWidth)};
	}

	private static int ink(char pixel) {
		return switch (pixel) {
			case '#' -> 0xFF1A1024;
			case 'w' -> 0xFFF4EFE4;
			case 'b' -> 0xFF3C2470;
			case 'B' -> 0xFF241648;
			case 'G' -> 0xFFD6B25E;
			case 'g' -> 0xFF8A6A2E;
			case 'e' -> 0xFFE7D7FF;
			default -> 0;
		};
	}
}
