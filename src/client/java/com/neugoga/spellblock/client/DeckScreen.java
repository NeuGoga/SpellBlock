package com.neugoga.spellblock.client;

import static com.neugoga.spellblock.client.ui.Ui.inside;
import static com.neugoga.spellblock.client.ui.Ui.rarityColor;
import static com.neugoga.spellblock.client.ui.Ui.colorRank;
import static com.neugoga.spellblock.client.ui.Ui.any;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.DeckFormat;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.network.DeckEditC2SPayload;
import com.neugoga.spellblock.network.DeckSyncS2CPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Binder on the right, deck list on the left. Cards move onto the box one click at a time. */
public final class DeckScreen extends Screen {
	private static final int ROWS = 3;
	private static final int ROW = 14;
	private static final String[] COLOR_KEYS = {"W", "U", "B", "R", "G", "C"};
	private static final String[] COLOR_NAMES = {"White", "Blue", "Black", "Red", "Green", "Colorless"};
	private static final int[] COLOR_INK = {0xFF2A2618, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFF1C1C1C};
	private static final int[] COLOR_FILL = {0xFFF3E8B8, 0xFF3B7FBF, 0xFF4E4458, 0xFFC4483A, 0xFF3E8A45, 0xFFB7B7B7};
	private static final String[] RARITY_KEYS = {"C", "U", "R", "M"};
	private static final String[] RARITY_IDS = {"common", "uncommon", "rare", "mythic"};
	private static final String[] BOX_IDS = {"blue", "red", "white", "green", "black", "grey"};
	private static final int[] BOX_FILL = {0xFF3A7FBF, 0xFFC4483A, 0xFFF4F1EA, 0xFF3E8A45, 0xFF2A2430, 0xFF9A9A9A};

	private final boolean[] colorOn = new boolean[COLOR_KEYS.length];
	private final boolean[] rarityOn = new boolean[RARITY_KEYS.length];
	private final List<Hit> hits = new ArrayList<>();

	private int slot;
	private String deckName = "";
	private String formatId = DeckFormat.COMMANDER.id();
	private String boxColor = "blue";
	private boolean locked;
	private boolean yours;
	private BinderCard commanderA = DeckSyncS2CPayload.emptyCard();
	private BinderCard commanderB = DeckSyncS2CPayload.emptyCard();
	private List<BinderCard> main = List.of();
	private List<BinderCard> side = List.of();
	private List<BinderCard> binder = List.of();
	private List<BinderCard> visible = List.of();
	private List<String> issues = List.of();
	private String status = "";
	private String query = "";
	private int page;
	private int scroll;
	private int armed = -1;
	private boolean sideTarget;
	private boolean nameWasFocused;
	private EditBox nameBox;
	private EditBox search;

	private int leftX;
	private int leftY;
	private int leftW;
	private int leftH;
	private int rightX;
	private int rightY;
	private int rightW;
	private int rightH;
	private int listX;
	private int listY;
	private int listW;
	private int listH;
	private int reportY;
	private int cardW = 40;
	private int cardH = 56;
	private int cols = 4;
	private int pageSize = 12;
	private int gridX;
	private int gridY;
	private int gap = 4;

	public DeckScreen(DeckSyncS2CPayload payload) {
		super(Component.literal("Deck Box"));
		apply(payload);
	}

	public void replace(DeckSyncS2CPayload payload) {
		if ("closed".equals(payload.status()) && payload.slot() < 0) {
			if (this.minecraft != null) {
				this.minecraft.setScreen(null);
			}
			return;
		}
		apply(payload);
		if (nameBox != null && !nameBox.isFocused()) {
			applyNameBox(deckName);
			nameBox.setEditable(!locked);
		}
		rebuild();
	}

	@Override
	protected void init() {
		nameBox = new EditBox(this.font, 0, 0, 80, 14, Component.literal("Deck name"));
		nameBox.setMaxLength(32);
		nameBox.setHint(Component.literal("Deck name"));
		applyNameBox(deckName);
		nameBox.setEditable(!locked);
		addRenderableWidget(nameBox);
		search = new EditBox(this.font, 0, 0, 80, 14, Component.translatable("spellblock.binder.search"));
		search.setMaxLength(48);
		search.setHint(Component.translatable("spellblock.binder.search"));
		search.setValue(query);
		search.setResponder(text -> {
			if (!text.equals(query)) {
				query = text;
				page = 0;
				rebuild();
			}
		});
		addRenderableWidget(search);
		rebuild();
	}

	@Override
	public void tick() {
		super.tick();
		boolean focused = nameBox != null && nameBox.isFocused();
		if (nameWasFocused && !focused) {
			sendName();
		}
		nameWasFocused = focused;
	}

	@Override
	public void onClose() {
		sendName();
		super.onClose();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		layout();
		graphics.fill(0, 0, this.width, this.height, 0xC0100C0A);
		graphics.fill(leftX, leftY, leftX + leftW, leftY + leftH, 0xFF4A3424);
		graphics.fill(leftX, leftY, leftX + 8, leftY + leftH, 0xFF2C1E14);
		graphics.fill(rightX, rightY, rightX + rightW, rightY + rightH, 0xFF4A3424);
		graphics.fill(rightX + rightW - 8, rightY, rightX + rightW, rightY + rightH, 0xFF2C1E14);
		graphics.drawString(this.font, "Deck", leftX + 14, leftY + 6, 0xFFE6D3A3, false);
		graphics.drawString(this.font, "Binder", rightX + 10, rightY + 6, 0xFFE6D3A3, false);
		drawBoxColors(graphics);
		drawButton(graphics, formatHit(), DeckFormat.fromId(formatId).label(), true);
		drawButton(graphics, lockHit(), lockLabel(), !locked || yours);
		drawReport(graphics);
		drawCommanders(graphics, mouseX, mouseY);
		drawList(graphics, mouseX, mouseY);
		drawBinder(graphics, mouseX, mouseY);
		String hint = armed >= 0
			? "Click a sleeve to set the commander"
			: "Adding to the " + (sideTarget ? "sideboard" : "mainboard") + ". Right-click a row to move it.";
		graphics.drawString(this.font, fit(hint, leftW - 20), leftX + 14, leftY + leftH - 14, 0xFFD9CBB2, false);
		super.render(graphics, mouseX, mouseY, partialTick);
		drawHover(graphics, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (nameBox != null) {
			nameBox.setFocused(false);
		}
		if (search != null) {
			search.setFocused(false);
		}
		layout();
		for (Hit hit : hits) {
			if (!hit.contains(mouseX, mouseY)) {
				continue;
			}
			if ((hit.kind == Kind.ROW || hit.kind == Kind.HEADER) && !inside(mouseX, mouseY, listX, listY, listW, listH)) {
				continue;
			}
			return press(hit, button);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		layout();
		if (mouseX >= leftX && mouseX < leftX + leftW) {
			scroll = Mth.clamp(scroll + (scrollY < 0 ? ROW : -ROW), 0, maxScroll());
			return true;
		}
		if (scrollY < 0 && page + 1 < pageCount()) {
			page++;
		} else if (scrollY > 0 && page > 0) {
			page--;
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (nameBox != null && nameBox.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				sendName();
				nameBox.setFocused(false);
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (search != null && search.isFocused()) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT && page > 0) {
			page--;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT && page + 1 < pageCount()) {
			page++;
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

	@Override
	protected void setInitialFocus() {
	}

	private boolean press(Hit hit, int button) {
		if (nameBox != null && hit.kind != Kind.FILTER_COLOR && hit.kind != Kind.FILTER_RARITY) {
			nameBox.setFocused(false);
		}
		switch (hit.kind) {
			case COLOR -> send("color", null, hit.text);
			case FORMAT -> {
				if (!locked) {
					send("format", null, DeckFormat.fromId(formatId).next().id());
				}
			}
			case LOCK -> {
				if (!locked) {
					send("lock", null, "");
				} else if (yours) {
					send("unlock", null, "");
				}
			}
			case FILTER_COLOR -> {
				colorOn[hit.slot] = !colorOn[hit.slot];
				page = 0;
				rebuild();
			}
			case FILTER_RARITY -> {
				rarityOn[hit.slot] = !rarityOn[hit.slot];
				page = 0;
				rebuild();
			}
			case PAGE -> {
				if (hit.slot < 0 && page > 0) {
					page--;
				} else if (hit.slot > 0 && page + 1 < pageCount()) {
					page++;
				}
			}
			case ARM -> armed = armed == hit.slot ? -1 : hit.slot;
			case CLEAR -> {
				armed = -1;
				send("commander_clear", null, Integer.toString(hit.slot));
			}
			case HEADER -> sideTarget = hit.slot == 1;
			case ROW -> {
				if (button == 1) {
					send("move", hit.card, hit.slot == 1 ? "side" : "main");
				} else {
					send(hit.slot == 1 ? "side_remove" : "remove", hit.card, "");
				}
			}
			case SLEEVE -> {
				if (hit.card == null || hit.card.oracleId().isEmpty()) {
					break;
				}
				if (armed >= 0 && button == 0 && DeckFormat.fromId(formatId).commander()) {
					send("commander", hit.card, Integer.toString(armed));
					armed = -1;
				} else if (sideTarget || button == 1) {
					send("side_add", hit.card, "");
				} else {
					send("add", hit.card, "");
				}
			}
			default -> {
			}
		}
		return true;
	}

	private void drawBoxColors(GuiGraphics graphics) {
		for (int i = 0; i < BOX_IDS.length; i++) {
			String color = BOX_IDS[i];
			Hit hit = null;
			for (Hit item : hits) {
				if (item.kind == Kind.COLOR && color.equals(item.text)) {
					hit = item;
					break;
				}
			}
			if (hit == null) {
				continue;
			}
			int border = color.equals(boxColor) ? 0xFFFFD36A : 0xFF2A241E;
			graphics.fill(hit.x - 1, hit.y - 1, hit.x + hit.w + 1, hit.y + hit.h + 1, border);
			graphics.fill(hit.x, hit.y, hit.x + hit.w, hit.y + hit.h, BOX_FILL[i]);
		}
	}

	private void drawButton(GuiGraphics graphics, Hit hit, String label, boolean enabled) {
		if (hit == null) {
			return;
		}
		graphics.fill(hit.x, hit.y, hit.x + hit.w, hit.y + hit.h, enabled ? 0xFF2C1E14 : 0xFF3A322C);
		graphics.drawString(this.font, fit(label, hit.w - 6), hit.x + 4, hit.y + 3, enabled ? 0xFFE6D3A3 : 0xFF6A5A48, false);
	}

	private void drawReport(GuiGraphics graphics) {
		boolean legal = issues.isEmpty();
		String headline = legal ? "Legal" : issues.size() + (issues.size() == 1 ? " problem" : " problems");
		int color = legal ? 0xFF7DCEA0 : 0xFFE07A5F;
		int y = reportY;
		graphics.drawString(this.font, headline, leftX + 14, y, color, false);
		int shown = Math.min(2, issues.size());
		for (int i = 0; i < shown; i++) {
			graphics.drawString(this.font, fit(issues.get(i), leftW - 28), leftX + 14, y + 11 + i * 10, 0xFFD9CBB2, false);
		}
		if (!status.isEmpty()) {
			graphics.drawString(this.font, fit(status, leftW - 28), leftX + 14, leftY + 18, 0xFFE6C56A, false);
		}
	}

	private void drawCommanders(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!showCommanders()) {
			return;
		}
		if (commanderFormat() || !commanderA.oracleId().isEmpty()) {
			drawCommander(graphics, commanderA, 0, mouseX, mouseY);
		}
		if (commanderFormat() || !commanderB.oracleId().isEmpty()) {
			drawCommander(graphics, commanderB, 1, mouseX, mouseY);
		}
	}

	private void drawCommander(GuiGraphics graphics, BinderCard card, int index, int mouseX, int mouseY) {
		Hit hit = commanderHit(index);
		if (hit == null) {
			return;
		}
		boolean hot = hit.contains(mouseX, mouseY);
		int rim = armed == index ? 0xFFFFD36A : (hot ? 0xFF8A7560 : 0xFF2C1E14);
		graphics.fill(hit.x, hit.y, hit.x + hit.w, hit.y + hit.h, rim);
		graphics.fill(hit.x + 1, hit.y + 1, hit.x + hit.w - 1, hit.y + hit.h - 1, 0xFF1B1714);
		String text = card.oracleId().isEmpty() ? (index == 0 ? "Commander" : "Second commander") : lineText(card);
		graphics.drawString(this.font, fit(text, hit.w - 16), hit.x + 4, hit.y + 3, card.oracleId().isEmpty() ? 0xFF8A7B6A : 0xFFF4EFE4, false);
		if (!card.oracleId().isEmpty()) {
			graphics.drawString(this.font, "x", hit.x + hit.w - 10, hit.y + 3, 0xFF8A7B6A, false);
		}
		if (!card.oracleId().isEmpty() && Finish.FOIL.name().equals(card.finish())) {
			drawSweep(graphics, hit.x + 1, hit.y + 1, hit.w - 2, hit.h - 2);
		}
	}

	private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.enableScissor(listX, listY, listX + listW, listY + listH);
		for (Hit hit : hits) {
			if (hit.kind != Kind.ROW || !hit.visible) {
				continue;
			}
			boolean hot = hit.contains(mouseX, mouseY);
			graphics.fill(hit.x, hit.y, hit.x + hit.w, hit.y + hit.h, hot ? 0xFF3A2E24 : 0xFF241C16);
			String count = hit.card.count() + "x";
			int countW = this.font.width(count);
			graphics.drawString(this.font, fit(lineLabel(hit.card), hit.w - countW - 12), hit.x + 4, hit.y + 3, 0xFFF4EFE4, false);
			graphics.drawString(this.font, count, hit.x + hit.w - countW - 4, hit.y + 3, 0xFFE6D3A3, false);
			if (Finish.FOIL.name().equals(hit.card.finish())) {
				drawSweep(graphics, hit.x, hit.y, hit.w, hit.h);
			}
		}
		for (Hit hit : hits) {
			if (hit.kind == Kind.HEADER && hit.visible) {
				boolean selected = (hit.slot == 1) == sideTarget;
				if (selected) {
					graphics.fill(hit.x, hit.y, hit.x + hit.w, hit.y + hit.h, 0xFF3A2E24);
				}
				int color = selected ? 0xFFFFD36A : 0xFFE6D3A3;
				graphics.drawString(this.font, hit.text, hit.x, hit.y + 2, color, false);
			}
		}
		graphics.disableScissor();
		int max = maxScroll();
		if (max > 0) {
			int barH = Math.max(8, listH * listH / (listH + max));
			int barY = listY + (listH - barH) * scroll / max;
			graphics.fill(listX + listW - 2, barY, listX + listW, barY + barH, 0xFF8A7560);
		}
	}

	private void drawBinder(GuiGraphics graphics, int mouseX, int mouseY) {
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			Hit hit = chipHit(Kind.FILTER_COLOR, i);
			if (hit != null) {
				drawChip(graphics, hit.x, hit.y, COLOR_KEYS[i], COLOR_FILL[i], COLOR_INK[i], colorOn[i]);
			}
		}
		for (int i = 0; i < RARITY_KEYS.length; i++) {
			Hit hit = chipHit(Kind.FILTER_RARITY, i);
			if (hit != null) {
				drawChip(graphics, hit.x, hit.y, RARITY_KEYS[i], rarityColor(RARITY_IDS[i]), 0xFF1C1C1C, rarityOn[i]);
			}
		}
		prepareArt();
		for (Hit hit : hits) {
			if (hit.kind != Kind.SLEEVE) {
				continue;
			}
			drawSleeve(graphics, hit.card, hit.x, hit.y, hit.contains(mouseX, mouseY));
		}
		String label = (page + 1) + " / " + pageCount();
		graphics.drawString(this.font, label, rightX + rightW - 16 - this.font.width(label), rightY + rightH - 14, 0xFFE6D3A3, false);
		Hit prev = arrowHit(true);
		Hit next = arrowHit(false);
		if (prev != null) {
			graphics.drawString(this.font, "<", prev.x, prev.y, page > 0 ? 0xFFE6D3A3 : 0xFF6A5A48, false);
		}
		if (next != null) {
			graphics.drawString(this.font, ">", next.x, next.y, page + 1 < pageCount() ? 0xFFE6D3A3 : 0xFF6A5A48, false);
		}
	}

	private void drawSleeve(GuiGraphics graphics, BinderCard card, int x, int y, boolean hovered) {
		if (card == null) {
			graphics.fill(x, y, x + cardW, y + cardH, 0xFF3A322C);
			graphics.fill(x + 1, y + 1, x + cardW - 1, y + cardH - 1, 0xFF120F0D);
			return;
		}
		int rim = hovered ? 0xFF8A7560 : rarityColor(card.rarity());
		graphics.fill(x, y, x + cardW, y + cardH, rim);
		if (!CardArt.draw(graphics, card.printingId(), card.finish(), x + 1, y + 1, cardW - 2, cardH - 2)) {
			graphics.fill(x + 1, y + 1, x + cardW - 1, y + cardH - 1, 0xFF1A1714);
			graphics.drawString(this.font, fit(card.name(), cardW - 8), x + 4, y + 4, 0xFFF4EFE4, false);
		}
		String count = "x" + card.count();
		int badgeX = x + cardW - this.font.width(count) - 4;
		graphics.fill(badgeX - 2, y + cardH - 12, x + cardW - 2, y + cardH - 2, 0xCC000000);
		graphics.drawString(this.font, count, badgeX, y + cardH - 11, 0xFFFFFFFF, false);
	}

	private void drawChip(GuiGraphics graphics, int x, int y, String letter, int fill, int ink, boolean on) {
		graphics.fill(x - 1, y - 1, x + 13, y + 13, on ? 0xFFFFD36A : 0xFF2A241E);
		graphics.fill(x, y, x + 12, y + 12, fill);
		graphics.drawString(this.font, letter, x + 3, y + 2, ink, false);
	}

	private void drawSweep(GuiGraphics graphics, int x, int y, int w, int h) {
		float phase = (System.currentTimeMillis() % 1600L) / 1600f;
		int head = x - h + Math.round(phase * (w + h));
		graphics.enableScissor(x, y, x + w, y + h);
		for (int i = 0; i < h; i++) {
			int sx = head + i;
			graphics.fill(sx, y + i, sx + 2, y + i + 1, 0xA0C8C8C8);
		}
		graphics.disableScissor();
	}

	private void drawHover(GuiGraphics graphics, int mouseX, int mouseY) {
		if (inside(mouseX, mouseY, leftX + 14, reportY, leftW - 28, 12 + Math.min(2, issues.size()) * 10) && !issues.isEmpty()) {
			List<Component> lines = new ArrayList<>();
			for (String issue : issues) {
				lines.add(Component.literal(issue));
			}
			graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
			return;
		}
		for (Hit hit : hits) {
			if (!hit.contains(mouseX, mouseY) || !hit.visible) {
				continue;
			}
			if (hit.kind == Kind.COLOR) {
				graphics.renderTooltip(this.font, Component.literal(hit.text.substring(0, 1).toUpperCase(Locale.ROOT) + hit.text.substring(1)), mouseX, mouseY);
				return;
			}
			if ((hit.kind == Kind.ROW || hit.kind == Kind.SLEEVE || hit.kind == Kind.ARM) && hit.card != null && !hit.card.oracleId().isEmpty()) {
				List<Component> lines = new ArrayList<>();
				lines.add(Component.literal(hit.card.name()));
				if (!hit.card.typeLine().isEmpty()) {
					lines.add(Component.literal(hit.card.typeLine()));
				}
				if (!hit.card.manaCost().isEmpty()) {
					lines.add(Component.literal(hit.card.manaCost()));
				}
				String extra = CardArt.caption(hit.card.treatment(), hit.card.finish());
				if (!extra.isEmpty()) {
					lines.add(Component.literal(extra));
				}
				if (hit.kind == Kind.SLEEVE && !hit.card.oracleId().isEmpty()) {
					lines.add(Component.literal(sideTarget ? "Click adds to the sideboard" : "Click adds to the mainboard"));
				}
				graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
				return;
			}
		}
	}

	private void layout() {
		hits.clear();
		int margin = 8;
		leftX = margin;
		leftY = margin;
		leftH = this.height - margin * 2;
		leftW = Math.max(168, (this.width - margin * 3) * 2 / 5);
		rightX = leftX + leftW + margin;
		rightY = margin;
		rightW = this.width - rightX - margin;
		rightH = leftH;
		nameBox.setX(leftX + 14);
		nameBox.setY(leftY + 28);
		nameBox.setWidth(Math.max(40, leftW - 28));
		int y = leftY + 46;
		for (int i = 0; i < BOX_IDS.length; i++) {
			hits.add(new Hit(Kind.COLOR, BOX_IDS[i], null, -1, leftX + 14 + i * 16, y, 12, 12, true));
		}
		y += 16;
		int formatW = Math.min(130, Math.max(70, leftW - 78));
		hits.add(new Hit(Kind.FORMAT, "", null, -1, leftX + 14, y, formatW, 14, true));
		hits.add(new Hit(Kind.LOCK, "", null, -1, leftX + 18 + formatW, y, Math.max(36, leftW - formatW - 36), 14, true));
		reportY = y + 18;
		y = reportY + 12 + Math.min(2, issues.size()) * 10 + 4;
		if (showCommanders()) {
			y = addCommanderRow(commanderA, 0, y);
			y = addCommanderRow(commanderB, 1, y);
			y += 2;
		}
		listX = leftX + 14;
		listY = y;
		listW = leftW - 28;
		listH = Math.max(20, leftY + leftH - 24 - listY);
		int content = contentHeight();
		scroll = Mth.clamp(scroll, 0, Math.max(0, content - listH));
		int rowY = listY - scroll;
		boolean mainHeader = rowY + 12 > listY && rowY < listY + listH;
		hits.add(new Hit(Kind.HEADER, "Mainboard", null, 0, listX, rowY, listW, 12, mainHeader));
		rowY += 12;
		rowY = addRows(main, 0, rowY);
		boolean sideHeader = rowY + 12 > listY && rowY < listY + listH;
		hits.add(new Hit(Kind.HEADER, "Sideboard", null, 1, listX, rowY, listW, 12, sideHeader));
		rowY += 12;
		addRows(side, 1, rowY);
		search.setX(rightX + 8);
		search.setY(rightY + 20);
		search.setWidth(Math.max(40, rightW - 16));
		int chipY = rightY + 38;
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			hits.add(new Hit(Kind.FILTER_COLOR, "", null, i, rightX + 8 + i * 16, chipY, 12, 12, true));
		}
		int rarityStart = rightX + rightW - 8 - RARITY_KEYS.length * 16;
		int rarityFloor = rightX + 8 + COLOR_KEYS.length * 16 + 8;
		if (rarityStart < rarityFloor) {
			rarityStart = rarityFloor;
		}
		for (int i = 0; i < RARITY_KEYS.length; i++) {
			hits.add(new Hit(Kind.FILTER_RARITY, "", null, i, rarityStart + i * 16, chipY, 12, 12, true));
		}
		int innerW = rightW - 16;
		int innerH = rightH - 78;
		gap = 4;
		int maxH = Math.max(36, (innerH - gap * (ROWS - 1)) / ROWS);
		int naturalW = Math.max(28, maxH * 5 / 7);
		int fit = Math.max(1, (innerW + gap) / (naturalW + gap));
		cols = Math.min(5, Math.max(4, fit));
		cardW = Math.max(28, (innerW - gap * (cols - 1)) / cols);
		cardH = cardW * 7 / 5;
		if (cardH > maxH) {
			cardH = maxH;
			cardW = Math.max(28, cardH * 5 / 7);
		}
		pageSize = ROWS * cols;
		if (page >= pageCount()) {
			page = Math.max(0, pageCount() - 1);
		}
		int used = cols * cardW + (cols - 1) * gap;
		gridX = rightX + 8 + Math.max(0, (innerW - used) / 2);
		gridY = chipY + 18;
		for (int slotIndex = 0; slotIndex < pageSize; slotIndex++) {
			int col = slotIndex % cols;
			int row = slotIndex / cols;
			int x = gridX + col * (cardW + gap);
			int sy = gridY + row * (cardH + gap);
			int index = page * pageSize + slotIndex;
			BinderCard card = index >= 0 && index < visible.size() ? visible.get(index) : null;
			hits.add(new Hit(Kind.SLEEVE, "", card, slotIndex, x, sy, cardW, cardH, true));
		}
		hits.add(new Hit(Kind.PAGE, "", null, -1, rightX + 10, rightY + rightH - 14, 10, 10, true));
		hits.add(new Hit(Kind.PAGE, "", null, 1, rightX + 24, rightY + rightH - 14, 10, 10, true));
	}

	private int addRows(List<BinderCard> cards, int zone, int y) {
		for (BinderCard card : cards) {
			boolean visible = y + ROW > listY && y < listY + listH;
			hits.add(new Hit(Kind.ROW, "", card, zone, listX, y, listW - 4, ROW - 1, visible));
			y += ROW;
		}
		return y;
	}

	private int addCommanderRow(BinderCard card, int index, int y) {
		if (!commanderFormat() && card.oracleId().isEmpty()) {
			return y;
		}
		if (commanderFormat()) {
			if (!card.oracleId().isEmpty()) {
				hits.add(new Hit(Kind.CLEAR, "", card, index, leftX + leftW - 26, y, 12, ROW, true));
			}
			hits.add(new Hit(Kind.ARM, "", card, index, leftX + 14, y, leftW - 28, ROW, true));
		} else {
			hits.add(new Hit(Kind.CLEAR, "", card, index, leftX + 14, y, leftW - 28, ROW, true));
		}
		return y + ROW + 2;
	}

	private int contentHeight() {
		return 24 + (main.size() + side.size()) * ROW;
	}

	private boolean commanderFormat() {
		return DeckFormat.fromId(formatId).commander();
	}

	private boolean showCommanders() {
		return commanderFormat() || !commanderA.oracleId().isEmpty() || !commanderB.oracleId().isEmpty();
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - listH);
	}

	private void apply(DeckSyncS2CPayload payload) {
		slot = payload.slot();
		deckName = payload.name();
		formatId = payload.format();
		boxColor = payload.color();
		locked = payload.locked();
		yours = payload.yours();
		commanderA = payload.commanderA();
		commanderB = payload.commanderB();
		main = sorted(payload.main());
		side = sorted(payload.side());
		binder = payload.binder();
		issues = payload.issues();
		status = payload.status();
		if (!DeckFormat.fromId(formatId).commander()) {
			armed = -1;
		}
	}

	private static List<BinderCard> sorted(List<BinderCard> cards) {
		List<BinderCard> next = new ArrayList<>(cards);
		next.sort(Comparator
			.comparingInt((BinderCard card) -> colorRank(card.colorIdentity()))
			.thenComparing(BinderCard::colorIdentity)
			.thenComparingDouble(BinderCard::manaValue)
			.thenComparing(card -> card.name().toLowerCase(Locale.ROOT))
			.thenComparing(BinderCard::finish));
		return next;
	}

	private void rebuild() {
		List<BinderCard> next = new ArrayList<>();
		for (BinderCard card : binder) {
			if (accepts(card)) {
				next.add(card);
			}
		}
		next.sort(Comparator
			.comparingInt((BinderCard card) -> colorRank(card.colorIdentity()))
			.thenComparing(BinderCard::colorIdentity)
			.thenComparingDouble(BinderCard::manaValue)
			.thenComparing(card -> card.name().toLowerCase(Locale.ROOT))
			.thenComparing(BinderCard::finish));
		visible = next;
		if (page >= pageCount()) {
			page = pageCount() - 1;
		}
		if (page < 0) {
			page = 0;
		}
	}

	private boolean accepts(BinderCard card) {
		if (!query.isBlank() && !OracleIndex.fold(card.name()).contains(OracleIndex.fold(query))) {
			return false;
		}
		if (any(colorOn)) {
			String identity = card.colorIdentity();
			boolean matched = false;
			for (int i = 0; i < 5; i++) {
				if (colorOn[i] && identity.indexOf(COLOR_KEYS[i]) >= 0) {
					matched = true;
				}
			}
			if (colorOn[5] && identity.isEmpty()) {
				matched = true;
			}
			if (!matched) {
				return false;
			}
		}
		if (any(rarityOn)) {
			boolean matched = false;
			for (int i = 0; i < RARITY_IDS.length; i++) {
				if (rarityOn[i] && RARITY_IDS[i].equals(card.rarity())) {
					matched = true;
				}
			}
			if (!matched) {
				return false;
			}
		}
		return true;
	}

	private void prepareArt() {
		CardArt.beginFrame();
		int start = page * pageSize;
		for (int i = 0; i < pageSize && start + i < visible.size(); i++) {
			CardArt.pin(visible.get(start + i).printingId());
			CardArt.preload(visible.get(start + i).printingId());
		}
	}

	/** setValue moves the cursor to the end, which can scroll the first letters off-screen in a short box. */
	private void applyNameBox(String value) {
		if (nameBox == null) {
			return;
		}
		String next = value == null ? "" : value;
		if (!nameBox.getValue().equals(next)) {
			nameBox.setValue(next);
		}
		// Show the start of the name (not the end).
		nameBox.moveCursorToStart(false);
		nameBox.setHighlightPos(nameBox.getCursorPosition());
	}

	private void sendName() {
		if (nameBox == null || locked) {
			return;
		}
		String value = nameBox.getValue().trim();
		if (!value.equals(deckName)) {
			send("name", null, value);
		}
	}

	private void send(String action, BinderCard card, String text) {
		if (!allowed(action) || !ClientPlayNetworking.canSend(DeckEditC2SPayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new DeckEditC2SPayload(
			slot,
			action,
			card == null ? "" : card.oracleId(),
			card == null ? "" : card.printingId(),
			card == null ? "" : card.finish(),
			text == null ? "" : text
		));
	}

	private boolean allowed(String action) {
		if ("unlock".equals(action)) {
			return locked && yours;
		}
		return !locked;
	}

	private String lockLabel() {
		if (!locked) {
			return "Lock";
		}
		return yours ? "Unlock" : "Locked";
	}

	private String lineText(BinderCard card) {
		String count = card.count() > 1 ? card.count() + "x " : "";
		return count + lineLabel(card);
	}

	private String lineLabel(BinderCard card) {
		String cost = card.manaCost().isEmpty() ? "" : card.manaCost() + " ";
		return cost + card.name();
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

	private int pageCount() {
		return Math.max(1, (visible.size() + pageSize - 1) / pageSize);
	}

	private Hit formatHit() {
		return first(Kind.FORMAT);
	}

	private Hit lockHit() {
		return first(Kind.LOCK);
	}

	private Hit commanderHit(int index) {
		Hit clear = null;
		for (Hit hit : hits) {
			if (hit.slot != index) {
				continue;
			}
			if (hit.kind == Kind.ARM) {
				return hit;
			}
			if (hit.kind == Kind.CLEAR) {
				clear = hit;
			}
		}
		return clear;
	}

	private Hit chipHit(Kind kind, int index) {
		for (Hit hit : hits) {
			if (hit.kind == kind && hit.slot == index) {
				return hit;
			}
		}
		return null;
	}

	private Hit arrowHit(boolean previous) {
		for (Hit hit : hits) {
			if (hit.kind == Kind.PAGE && (previous ? hit.slot < 0 : hit.slot > 0)) {
				return hit;
			}
		}
		return null;
	}

	private Hit first(Kind kind) {
		for (Hit hit : hits) {
			if (hit.kind == kind) {
				return hit;
			}
		}
		return null;
	}

	private enum Kind {
		COLOR,
		FORMAT,
		LOCK,
		ARM,
		CLEAR,
		ROW,
		HEADER,
		FILTER_COLOR,
		FILTER_RARITY,
		SLEEVE,
		PAGE
	}

	private record Hit(Kind kind, String text, BinderCard card, int slot, int x, int y, int w, int h, boolean visible) {
		private boolean contains(double mouseX, double mouseY) {
			return visible && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		}
	}
}
