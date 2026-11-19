package com.neugoga.spellblock.client;

import static com.neugoga.spellblock.client.ui.Ui.any;
import static com.neugoga.spellblock.client.ui.Ui.colorRank;
import static com.neugoga.spellblock.client.ui.Ui.inside;
import static com.neugoga.spellblock.client.ui.Ui.rarityColor;
import static com.neugoga.spellblock.client.ui.Ui.rarityLabel;

import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.network.TradeActionC2SPayload;
import com.neugoga.spellblock.network.TradeSyncS2CPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen 3-column player trade UI:
 * Left = your binder (click +1, Shift+click stack),
 * Middle = your offer + Lock / Cancel countdown button,
 * Right = partner's offer + read-only Lock status label.
 */
public final class TradeScreen extends Screen {
	private static final int BINDER_ROWS = 3;
	private static final int OFFER_COLS = 2;
	private static final int OFFER_ROWS = 3;
	private static final int OFFER_PER_PAGE = OFFER_COLS * OFFER_ROWS;

	private static final String[] COLOR_KEYS = {"W", "U", "B", "R", "G", "C"};
	private static final String[] COLOR_NAMES = {"White", "Blue", "Black", "Red", "Green", "Colorless"};
	private static final int[] COLOR_INK = {0xFF2A2618, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFF1C1C1C};
	private static final int[] COLOR_FILL = {0xFFF3E8B8, 0xFF3B7FBF, 0xFF4E4458, 0xFFC4483A, 0xFF3E8A45, 0xFFB7B7B7};
	private static final String[] RARITY_KEYS = {"C", "U", "R", "M"};
	private static final String[] RARITY_NAMES = {"Common", "Uncommon", "Rare", "Mythic"};
	private static final String[] RARITY_IDS = {"common", "uncommon", "rare", "mythic"};

	private static final int GOLD = 0xFFE6D3A3;
	private static final int GOLD_BRIGHT = 0xFFFFD36A;
	private static final int PAPER = 0xFFF4EFE4;
	private static final int MUTED = 0xFFD9CBB2;
	private static final int SUB_MUTED = 0xFF9E8D79;

	private final boolean[] colorOn = new boolean[COLOR_KEYS.length];
	private final boolean[] rarityOn = new boolean[RARITY_KEYS.length];
	private final Map<String, Integer> offeredByKey = new HashMap<>();

	private String myName = "";
	private String partnerName = "";
	private List<BinderCard> binderAll = List.of();
	private List<BinderCard> binderVisible = List.of();
	private List<BinderCard> myOffer = List.of();
	private List<BinderCard> theirOffer = List.of();
	private boolean myLocked;
	private boolean theirLocked;
	private int countdownSeconds = -1;
	private String status = "";
	private boolean closedByServer;

	private String query = "";
	private int binderPage;
	private int myOfferPage;
	private int theirOfferPage;

	private EditBox search;
	private Layout layout = Layout.EMPTY;

	public TradeScreen(TradeSyncS2CPayload payload) {
		super(Component.literal("Trade"));
		apply(payload);
	}

	public void replace(TradeSyncS2CPayload payload) {
		if (payload.closed()) {
			closedByServer = true;
			if (this.minecraft != null) {
				this.minecraft.setScreen(null);
			}
			return;
		}
		apply(payload);
		rebuild();
	}

	private void apply(TradeSyncS2CPayload payload) {
		myName = payload.myName();
		partnerName = payload.partnerName();
		if (payload.hasBinder()) {
			binderAll = payload.myBinder();
		}
		myOffer = payload.myOffer();
		theirOffer = payload.theirOffer();
		myLocked = payload.myLocked();
		theirLocked = payload.theirLocked();
		countdownSeconds = payload.countdownSeconds();
		if (!payload.status().isEmpty()) {
			status = payload.status();
		} else if (myLocked && theirLocked && countdownSeconds >= 0) {
			status = "";
		}
		offeredByKey.clear();
		for (BinderCard card : myOffer) {
			offeredByKey.put(cardKey(card), card.count());
		}
	}

	@Override
	protected void init() {
		search = new EditBox(this.font, 0, 0, 120, 14, Component.literal("Search"));
		search.setMaxLength(48);
		search.setHint(Component.literal("Search name or t:type"));
		search.setValue(query);
		search.setResponder(text -> {
			String next = text == null ? "" : text;
			if (!next.equals(query)) {
				query = next;
				binderPage = 0;
				rebuild();
			}
		});
		addRenderableWidget(search);
		rebuild();
	}

	@Override
	public void onClose() {
		if (!closedByServer && ClientPlayNetworking.canSend(TradeActionC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new TradeActionC2SPayload("close", "", "", "", 0));
		}
		super.onClose();
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	protected void setInitialFocus() {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void rebuild() {
		List<BinderCard> next = new ArrayList<>();
		for (BinderCard card : binderAll) {
			if (accepts(card)) {
				next.add(card);
			}
		}
		next.sort(Comparator
			.comparingInt((BinderCard c) -> colorRank(c.colorIdentity()))
			.thenComparing(BinderCard::colorIdentity)
			.thenComparingDouble(BinderCard::manaValue)
			.thenComparing(c -> c.name().toLowerCase(Locale.ROOT))
			.thenComparing(BinderCard::finish)
			.thenComparing(BinderCard::treatment)
			.thenComparing(BinderCard::printingId));
		binderVisible = next;
		binderPage = Mth.clamp(binderPage, 0, binderPageCount() - 1);
		myOfferPage = Mth.clamp(myOfferPage, 0, offerPageCount(myOffer) - 1);
		theirOfferPage = Mth.clamp(theirOfferPage, 0, offerPageCount(theirOffer) - 1);
	}

	private boolean accepts(BinderCard card) {
		if (!query.isBlank()) {
			String raw = query.trim();
			if (raw.toLowerCase(Locale.ROOT).startsWith("t:")) {
				String term = OracleIndex.fold(raw.substring(2).trim());
				if (!term.isEmpty() && !OracleIndex.fold(card.typeLine()).contains(term)) {
					return false;
				}
			} else if (!OracleIndex.fold(card.name()).contains(OracleIndex.fold(raw))) {
				return false;
			}
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

	private int binderPerPage() {
		return Math.max(1, layout.binderCols * BINDER_ROWS);
	}

	private int binderPageCount() {
		return Math.max(1, (binderVisible.size() + binderPerPage() - 1) / binderPerPage());
	}

	private static int offerPageCount(List<BinderCard> offer) {
		return Math.max(1, (offer.size() + OFFER_PER_PAGE - 1) / OFFER_PER_PAGE);
	}

	private int remainingCount(BinderCard card) {
		return Math.max(0, card.count() - offeredByKey.getOrDefault(cardKey(card), 0));
	}

	private static int totalCopies(List<BinderCard> cards) {
		int sum = 0;
		for (BinderCard c : cards) {
			sum += c.count();
		}
		return sum;
	}

	private static String cardKey(BinderCard card) {
		return card.oracleId() + "\0" + card.printingId() + "\0" + card.finish();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		layout = Layout.compute(this.width, this.height);
		if (search != null) {
			search.setX(layout.searchX);
			search.setY(layout.searchY);
			search.setWidth(Math.max(80, layout.leftX + layout.leftW - 12 - layout.searchX));
		}

		prepareArt();

		graphics.fill(0, 0, this.width, this.height, 0xC0100C0A);

		// Left Pane: Your Binder
		graphics.fill(layout.leftX, layout.leftY, layout.leftX + layout.leftW, layout.leftY + layout.leftH, 0xFF4A3424);
		graphics.fill(layout.leftX, layout.leftY, layout.leftX + 8, layout.leftY + layout.leftH, 0xFF2C1E14);
		graphics.fill(layout.pageX, layout.pageY, layout.pageX + layout.pageW, layout.pageY + layout.pageH, 0xFF1B1714);

		// Right Pane: Exchange Area (Your Offer + Their Offer)
		graphics.fill(layout.rightX, layout.rightY, layout.rightX + layout.rightW, layout.rightY + layout.rightH, 0xFF4A3424);
		graphics.fill(layout.rightX + layout.rightW - 8, layout.rightY, layout.rightX + layout.rightW, layout.rightY + layout.rightH, 0xFF2C1E14);

		// Left header
		graphics.drawString(this.font, "Your Binder", layout.leftX + 14, layout.leftY + 6, GOLD, false);
		graphics.drawString(this.font, fit(binderSubtitle(), layout.leftW - 26), layout.leftX + 14, layout.leftY + 18, MUTED, false);

		drawFilters(graphics);
		drawBinderGrid(graphics, mouseX, mouseY);
		drawBinderFooter(graphics);

		drawExchangeArea(graphics, mouseX, mouseY);

		super.render(graphics, mouseX, mouseY, partialTick);
		drawHover(graphics, mouseX, mouseY);
	}

	private String binderSubtitle() {
		int copies = totalCopies(binderAll);
		int offered = totalCopies(myOffer);
		String line = binderAll.size() + " unique · " + copies + " copies";
		if (offered > 0) {
			line += " · " + offered + " offered";
		}
		return line;
	}

	private void prepareArt() {
		CardArt.beginFrame();
		pinBinderPage(binderPage);
		if (binderPage > 0) {
			pinBinderPage(binderPage - 1);
		}
		if (binderPage + 1 < binderPageCount()) {
			pinBinderPage(binderPage + 1);
		}
		preloadBinderPage(binderPage);
		if (binderPage + 1 < binderPageCount()) {
			preloadBinderPage(binderPage + 1);
		}
		pinAndPreloadOffer(myOffer, myOfferPage);
		pinAndPreloadOffer(theirOffer, theirOfferPage);
	}

	private void pinBinderPage(int pageIndex) {
		int per = binderPerPage();
		int start = pageIndex * per;
		for (int i = 0; i < per && start + i < binderVisible.size(); i++) {
			CardArt.pin(binderVisible.get(start + i).printingId());
		}
	}

	private void preloadBinderPage(int pageIndex) {
		int per = binderPerPage();
		int start = pageIndex * per;
		for (int i = 0; i < per && start + i < binderVisible.size(); i++) {
			CardArt.preload(binderVisible.get(start + i).printingId());
		}
	}

	private void pinAndPreloadOffer(List<BinderCard> offer, int pageIndex) {
		int start = pageIndex * OFFER_PER_PAGE;
		for (int i = 0; i < OFFER_PER_PAGE && start + i < offer.size(); i++) {
			String id = offer.get(start + i).printingId();
			CardArt.pin(id);
			CardArt.preload(id);
		}
	}

	private void drawFilters(GuiGraphics graphics) {
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			drawChip(graphics, layout.colorX[i], layout.chipsY, COLOR_KEYS[i], COLOR_FILL[i], COLOR_INK[i], colorOn[i]);
		}
		for (int i = 0; i < RARITY_KEYS.length; i++) {
			drawChip(graphics, layout.rarityX[i], layout.chipsY, RARITY_KEYS[i], rarityColor(RARITY_IDS[i]), 0xFF1C1C1C, rarityOn[i]);
		}
	}

	private void drawChip(GuiGraphics graphics, int x, int y, String letter, int fill, int ink, boolean on) {
		graphics.fill(x - 1, y - 1, x + 15, y + 15, on ? GOLD_BRIGHT : 0xFF2A241E);
		graphics.fill(x, y, x + 14, y + 14, fill);
		graphics.drawString(this.font, letter, x + 4, y + 3, ink, false);
	}

	private void drawBinderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
		int pages = binderPageCount();
		drawArrow(graphics, layout.prevX, layout.arrowY, true, binderPage > 0);
		drawArrow(graphics, layout.nextX, layout.arrowY, false, binderPage + 1 < pages);

		int per = binderPerPage();
		int start = binderPage * per;
		for (int slot = 0; slot < per; slot++) {
			int col = slot % layout.binderCols;
			int row = slot / layout.binderCols;
			int x = layout.gridX + col * (layout.cardW + layout.gap);
			int y = layout.gridY + row * (layout.cardH + layout.gap);
			int idx = start + slot;
			BinderCard card = idx < binderVisible.size() ? binderVisible.get(idx) : null;
			boolean hot = card != null && inside(mouseX, mouseY, x, y, layout.cardW, layout.cardH);
			drawBinderSleeve(graphics, card, x, y, layout.cardW, layout.cardH, hot);
		}
		if (binderVisible.isEmpty()) {
			String empty = binderAll.isEmpty() ? "Your binder is empty." : "No cards match current filters.";
			graphics.drawCenteredString(this.font, empty, layout.pageX + layout.pageW / 2, layout.pageY + layout.pageH / 2 - 4, SUB_MUTED);
		}
	}

	private void drawBinderSleeve(GuiGraphics graphics, BinderCard card, int x, int y, int w, int h, boolean hovered) {
		if (card == null) {
			graphics.fill(x, y, x + w, y + h, 0xFF3A322C);
			graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF120F0D);
			return;
		}
		int offered = offeredByKey.getOrDefault(cardKey(card), 0);
		int avail = Math.max(0, card.count() - offered);
		int rim = offered > 0 ? GOLD_BRIGHT : (hovered && avail > 0 ? 0xFF8A7560 : rarityColor(card.rarity()));
		graphics.fill(x, y, x + w, y + h, rim);
		drawCardFace(graphics, card, x + 1, y + 1, w - 2, h - 2);
		if (avail <= 0) {
			graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x88100C0A);
		}

		if (offered > 0) {
			String offTxt = "+" + offered;
			int ow = this.font.width(offTxt);
			graphics.fill(x + 2, y + 2, x + ow + 6, y + 12, 0xDD000000);
			graphics.drawString(this.font, offTxt, x + 4, y + 3, GOLD_BRIGHT, false);
		}

		String count = "x" + avail;
		int cw = this.font.width(count);
		graphics.fill(x + w - cw - 6, y + h - 13, x + w - 2, y + h - 2, 0xCC000000);
		graphics.drawString(this.font, count, x + w - cw - 4, y + h - 12, avail > 0 ? 0xFFFFFFFF : SUB_MUTED, false);
	}

	private void drawBinderFooter(GuiGraphics graphics) {
		int footerY = layout.leftY + layout.leftH - 12;
		int pages = binderPageCount();
		String pageLabel = (binderPage + 1) + " / " + pages;
		int pageW = this.font.width(pageLabel);
		graphics.drawString(this.font, pageLabel, layout.leftX + layout.leftW - 14 - pageW, footerY, GOLD, false);
		graphics.drawString(this.font, fit("Click: +1 to offer · Shift+Click: stack", layout.leftW - pageW - 34), layout.leftX + 14, footerY, MUTED, false);
	}

	private void drawExchangeArea(GuiGraphics graphics, int mouseX, int mouseY) {
		boolean counting = myLocked && theirLocked && countdownSeconds >= 0;
		String titleTxt = counting
			? ("Confirming trade in " + countdownSeconds + "s...")
			: "Trade";
		int titleColor = counting ? GOLD_BRIGHT : GOLD;
		graphics.drawString(this.font, titleTxt, layout.rightX + 10, layout.rightY + 7, titleColor, false);

		drawOfferColumn(
			graphics,
			layout.myColX,
			layout.colY,
			layout.colW,
			layout.colH,
			myName.isEmpty() ? "You" : myName,
			myOffer,
			myOfferPage,
			true,
			myLocked,
			counting,
			mouseX,
			mouseY
		);

		drawOfferColumn(
			graphics,
			layout.theirColX,
			layout.colY,
			layout.colW,
			layout.colH,
			partnerName.isEmpty() ? "Partner" : partnerName,
			theirOffer,
			theirOfferPage,
			false,
			theirLocked,
			counting,
			mouseX,
			mouseY
		);

		// Bottom status + close button
		int closeW = 46;
		int closeX = layout.rightX + layout.rightW - 12 - closeW;
		int closeY = layout.rightY + layout.rightH - 17;
		drawButton(graphics, closeX, closeY, closeW, 13, "Cancel", false, mouseX, mouseY, true);

		if (!status.isEmpty()) {
			graphics.drawString(
				this.font,
				fit(status, closeX - layout.rightX - 16),
				layout.rightX + 10,
				layout.rightY + layout.rightH - 14,
				GOLD,
				false
			);
		}
	}

	private void drawOfferColumn(
		GuiGraphics graphics,
		int colX,
		int colY,
		int colW,
		int colH,
		String ownerName,
		List<BinderCard> offer,
		int offerPage,
		boolean mine,
		boolean locked,
		boolean counting,
		int mouseX,
		int mouseY
	) {
		graphics.fill(colX, colY, colX + colW, colY + colH, 0xFF1B1714);
		int lockStrip = locked ? (counting ? 0xFFE6A23C : 0xFF6BBF59) : 0xFF3A2C22;
		graphics.fill(colX, colY, colX + colW, colY + 2, lockStrip);

		int pad = 5;
		int innerW = colW - pad * 2;
		graphics.drawString(this.font, fit(ownerName, innerW), colX + pad, colY + 6, locked ? GOLD_BRIGHT : PAPER, false);
		int copies = totalCopies(offer);
		String sub = copies == 1 ? "1 card" : (copies + " cards");
		graphics.drawString(this.font, sub, colX + pad, colY + 17, MUTED, false);

		int pages = offerPageCount(offer);
		if (pages > 1) {
			String pTxt = (offerPage + 1) + "/" + pages;
			int pw = this.font.width(pTxt);
			int ax = colX + colW - pad - 30 - pw;
			drawSmallBtn(graphics, ax, colY + 15, 12, 11, "<", offerPage > 0, mouseX, mouseY);
			graphics.drawString(this.font, pTxt, ax + 14, colY + 17, GOLD, false);
			drawSmallBtn(graphics, colX + colW - pad - 12, colY + 15, 12, 11, ">", offerPage + 1 < pages, mouseX, mouseY);
		}

		// Offer card grid (2 cols x 3 rows)
		int gridTop = colY + 30;
		int gridBot = colY + colH - 26;
		int availH = Math.max(40, gridBot - gridTop);
		int gap = 4;
		int cw = (innerW - gap) / OFFER_COLS;
		int ch = cw * 7 / 5;
		if (ch * OFFER_ROWS + gap * (OFFER_ROWS - 1) > availH) {
			ch = (availH - gap * (OFFER_ROWS - 1)) / OFFER_ROWS;
			cw = Math.max(24, ch * 5 / 7);
		}
		int gridW = cw * OFFER_COLS + gap;
		int startX = colX + (colW - gridW) / 2;
		int startY = gridTop + Math.max(0, (availH - (ch * OFFER_ROWS + gap * (OFFER_ROWS - 1))) / 2);

		int startIdx = offerPage * OFFER_PER_PAGE;
		for (int slot = 0; slot < OFFER_PER_PAGE; slot++) {
			int col = slot % OFFER_COLS;
			int row = slot / OFFER_COLS;
			int sx = startX + col * (cw + gap);
			int sy = startY + row * (ch + gap);
			int idx = startIdx + slot;
			BinderCard card = idx < offer.size() ? offer.get(idx) : null;
			if (card == null) {
				graphics.fill(sx, sy, sx + cw, sy + ch, 0xFF2B231E);
				graphics.fill(sx + 1, sy + 1, sx + cw - 1, sy + ch - 1, 0xFF14110F);
				continue;
			}
			boolean hot = inside(mouseX, mouseY, sx, sy, cw, ch);
			int rim = (mine && hot) ? GOLD_BRIGHT : rarityColor(card.rarity());
			graphics.fill(sx, sy, sx + cw, sy + ch, rim);
			drawCardFace(graphics, card, sx + 1, sy + 1, cw - 2, ch - 2);
			String cnt = "x" + card.count();
			int cntW = this.font.width(cnt);
			graphics.fill(sx + cw - cntW - 5, sy + ch - 12, sx + cw - 2, sy + ch - 2, 0xCC000000);
			graphics.drawString(this.font, cnt, sx + cw - cntW - 3, sy + ch - 11, 0xFFFFFFFF, false);
		}
		if (offer.isEmpty()) {
			String empty = mine ? "Click binder to add" : "No cards offered";
			graphics.drawCenteredString(this.font, fit(empty, innerW), colX + colW / 2, gridTop + availH / 2 - 4, SUB_MUTED);
		}

		// Bottom control / label
		int ctrlY = colY + colH - 22;
		int ctrlH = 17;
		if (mine) {
			if (counting) {
				drawDangerButton(graphics, colX + pad, ctrlY, innerW, ctrlH, "Cancel (" + countdownSeconds + "s)", mouseX, mouseY);
			} else if (myLocked) {
				drawButton(graphics, colX + pad, ctrlY, innerW, ctrlH, "Locked (Unlock)", true, mouseX, mouseY, true);
			} else {
				boolean canLock = !myOffer.isEmpty() || !theirOffer.isEmpty();
				drawActionButton(graphics, colX + pad, ctrlY, innerW, ctrlH, "Lock Offer", canLock, mouseX, mouseY);
			}
		} else {
			// Partner's lock is strictly a read-only label, NOT a button
			int bg = locked ? 0xFF243B1F : 0xFF201A16;
			int border = locked ? 0xFF6BBF59 : 0xFF3E3229;
			int ink = locked ? 0xFFB8F2A6 : SUB_MUTED;
			String label = locked ? "LOCKED" : "Not Locked";
			graphics.fill(colX + pad, ctrlY, colX + pad + innerW, ctrlY + ctrlH, border);
			graphics.fill(colX + pad + 1, ctrlY + 1, colX + pad + innerW - 1, ctrlY + ctrlH - 1, bg);
			graphics.drawCenteredString(this.font, label, colX + colW / 2, ctrlY + 4, ink);
		}
	}

	private void drawCardFace(GuiGraphics graphics, BinderCard card, int x, int y, int w, int h) {
		if (!CardArt.draw(graphics, card.printingId(), card.finish(), x, y, w, h)) {
			graphics.fill(x, y, x + w, y + h, 0xFF1A1714);
			graphics.fill(x + 2, y + 2, x + 5, y + h - 2, rarityColor(card.rarity()));
			int textW = Math.max(8, w - 10);
			String[] lines = wrap(card.name(), textW);
			graphics.drawString(this.font, lines[0], x + 7, y + 4, PAPER, false);
			if (!lines[1].isEmpty() && h >= 28) {
				graphics.drawString(this.font, lines[1], x + 7, y + 14, PAPER, false);
			}
		}
	}

	private void drawArrow(GuiGraphics graphics, int x, int y, boolean left, boolean enabled) {
		int color = enabled ? GOLD : 0xFF6A5A48;
		graphics.fill(x, y, x + 14, y + 14, 0xFF2C1E14);
		graphics.drawString(this.font, left ? "<" : ">", x + 4, y + 3, color, false);
	}

	private void drawSmallBtn(GuiGraphics graphics, int x, int y, int w, int h, String label, boolean enabled, int mx, int my) {
		boolean hover = enabled && inside(mx, my, x, y, w, h);
		graphics.fill(x, y, x + w, y + h, hover ? 0xFF3E2B1E : 0xFF2C1E14);
		graphics.drawCenteredString(this.font, label, x + w / 2, y + 2, enabled ? GOLD : 0xFF6A5A48);
	}

	private void drawButton(GuiGraphics graphics, int x, int y, int w, int h, String label, boolean active, int mx, int my, boolean enabled) {
		boolean hover = enabled && inside(mx, my, x, y, w, h);
		int fill = !enabled ? 0xFF1A1411 : (active ? 0xFF4A301A : (hover ? 0xFF3E2B1E : 0xFF2C1E14));
		graphics.fill(x, y, x + w, y + h, fill);
		if (active && enabled) {
			graphics.fill(x, y, x + w, y + 1, GOLD_BRIGHT);
		}
		int ink = !enabled ? 0xFF6A5A48 : (active ? GOLD_BRIGHT : PAPER);
		graphics.drawCenteredString(this.font, fit(label, Math.max(8, w - 4)), x + w / 2, y + (h - 8) / 2, ink);
	}

	private void drawActionButton(GuiGraphics graphics, int x, int y, int w, int h, String label, boolean enabled, int mx, int my) {
		boolean hover = enabled && inside(mx, my, x, y, w, h);
		int fill = !enabled ? 0xFF1E1713 : (hover ? 0xFF563822 : 0xFF3B2618);
		graphics.fill(x, y, x + w, y + h, fill);
		graphics.fill(x, y, x + w, y + 1, enabled ? GOLD_BRIGHT : 0xFF5A4A38);
		graphics.fill(x, y + h - 1, x + w, y + h, enabled ? GOLD : 0xFF3A2E24);
		graphics.drawCenteredString(this.font, fit(label, Math.max(10, w - 6)), x + w / 2, y + (h - 8) / 2, enabled ? PAPER : SUB_MUTED);
	}

	private void drawDangerButton(GuiGraphics graphics, int x, int y, int w, int h, String label, int mx, int my) {
		boolean hover = inside(mx, my, x, y, w, h);
		graphics.fill(x, y, x + w, y + h, hover ? 0xFF7A2822 : 0xFF5C1E19);
		graphics.fill(x, y, x + w, y + 1, 0xFFFF8A7A);
		graphics.drawCenteredString(this.font, fit(label, Math.max(10, w - 6)), x + w / 2, y + (h - 8) / 2, PAPER);
	}

	private void drawHover(GuiGraphics graphics, int mouseX, int mouseY) {
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			if (inside(mouseX, mouseY, layout.colorX[i], layout.chipsY, 14, 14)) {
				graphics.renderTooltip(this.font, Component.literal(COLOR_NAMES[i]), mouseX, mouseY);
				return;
			}
		}
		for (int i = 0; i < RARITY_KEYS.length; i++) {
			if (inside(mouseX, mouseY, layout.rarityX[i], layout.chipsY, 14, 14)) {
				graphics.renderTooltip(this.font, Component.literal(RARITY_NAMES[i]), mouseX, mouseY);
				return;
			}
		}
		int slot = binderSlotAt(mouseX, mouseY);
		if (slot >= 0) {
			int idx = binderPage * binderPerPage() + slot;
			if (idx < binderVisible.size()) {
				BinderCard card = binderVisible.get(idx);
				int avail = remainingCount(card);
				renderCardTooltip(graphics, card, avail > 0 ? "Click: +1 · Shift+Click: stack (" + avail + ")" : "All copies offered", mouseX, mouseY);
				return;
			}
		}
		BinderCard myCard = offerCardAt(layout.myColX, layout.colY, layout.colW, layout.colH, myOffer, myOfferPage, mouseX, mouseY);
		if (myCard != null) {
			renderCardTooltip(graphics, myCard, "Click: -1 · Shift+Click: remove stack", mouseX, mouseY);
			return;
		}
		BinderCard theirCard = offerCardAt(layout.theirColX, layout.colY, layout.colW, layout.colH, theirOffer, theirOfferPage, mouseX, mouseY);
		if (theirCard != null) {
			renderCardTooltip(graphics, theirCard, partnerName + "'s offer", mouseX, mouseY);
		}
	}

	private void renderCardTooltip(GuiGraphics graphics, BinderCard card, String hint, int mouseX, int mouseY) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(card.name()));
		if (!card.typeLine().isEmpty()) {
			lines.add(Component.literal(card.typeLine()));
		}
		String meta = rarityLabel(card.rarity());
		if (!card.setCode().isEmpty()) {
			meta += " · " + card.setCode().toUpperCase(Locale.ROOT);
		}
		String extra = CardArt.caption(card.treatment(), card.finish());
		if (!extra.isEmpty()) {
			meta += " · " + extra;
		}
		meta += " · x" + card.count();
		lines.add(Component.literal(meta));
		if (hint != null && !hint.isEmpty()) {
			lines.add(Component.literal(hint));
		}
		graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (search != null) {
			search.setFocused(false);
		}
		this.setFocused(null);

		if (button != 0) {
			return true;
		}

		// Filter chips
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			if (inside(mouseX, mouseY, layout.colorX[i], layout.chipsY, 14, 14)) {
				colorOn[i] = !colorOn[i];
				binderPage = 0;
				rebuild();
				return true;
			}
		}
		for (int i = 0; i < RARITY_KEYS.length; i++) {
			if (inside(mouseX, mouseY, layout.rarityX[i], layout.chipsY, 14, 14)) {
				rarityOn[i] = !rarityOn[i];
				binderPage = 0;
				rebuild();
				return true;
			}
		}

		// Binder pagination
		if (inside(mouseX, mouseY, layout.prevX, layout.arrowY, 14, 14) && binderPage > 0) {
			binderPage--;
			return true;
		}
		if (inside(mouseX, mouseY, layout.nextX, layout.arrowY, 14, 14) && binderPage + 1 < binderPageCount()) {
			binderPage++;
			return true;
		}

		// Binder card click -> add 1 (or remaining stack on Shift)
		int slot = binderSlotAt(mouseX, mouseY);
		if (slot >= 0) {
			int idx = binderPage * binderPerPage() + slot;
			if (idx < binderVisible.size()) {
				BinderCard card = binderVisible.get(idx);
				int avail = remainingCount(card);
				if (avail > 0) {
					int amt = Screen.hasShiftDown() ? avail : 1;
					sendAction("add", card.oracleId(), card.printingId(), card.finish(), amt);
				}
			}
			return true;
		}

		// Close trade button
		int closeW = 46;
		int closeX = layout.rightX + layout.rightW - 12 - closeW;
		int closeY = layout.rightY + layout.rightH - 17;
		if (inside(mouseX, mouseY, closeX, closeY, closeW, 13)) {
			onClose();
			return true;
		}

		// Your offer pagination
		int myPages = offerPageCount(myOffer);
		if (myPages > 1) {
			int pad = 5;
			String pTxt = (myOfferPage + 1) + "/" + myPages;
			int pw = this.font.width(pTxt);
			int ax = layout.myColX + layout.colW - pad - 30 - pw;
			if (inside(mouseX, mouseY, ax, layout.colY + 15, 12, 11) && myOfferPage > 0) {
				myOfferPage--;
				return true;
			}
			if (inside(mouseX, mouseY, layout.myColX + layout.colW - pad - 12, layout.colY + 15, 12, 11) && myOfferPage + 1 < myPages) {
				myOfferPage++;
				return true;
			}
		}

		// Their offer pagination
		int theirPages = offerPageCount(theirOffer);
		if (theirPages > 1) {
			int pad = 5;
			String pTxt = (theirOfferPage + 1) + "/" + theirPages;
			int pw = this.font.width(pTxt);
			int ax = layout.theirColX + layout.colW - pad - 30 - pw;
			if (inside(mouseX, mouseY, ax, layout.colY + 15, 12, 11) && theirOfferPage > 0) {
				theirOfferPage--;
				return true;
			}
			if (inside(mouseX, mouseY, layout.theirColX + layout.colW - pad - 12, layout.colY + 15, 12, 11) && theirOfferPage + 1 < theirPages) {
				theirOfferPage++;
				return true;
			}
		}

		// Click on card in Your Offer -> remove 1 (or stack on Shift)
		BinderCard clickedMine = offerCardAt(layout.myColX, layout.colY, layout.colW, layout.colH, myOffer, myOfferPage, mouseX, mouseY);
		if (clickedMine != null) {
			int amt = Screen.hasShiftDown() ? clickedMine.count() : 1;
			sendAction("remove", clickedMine.oracleId(), clickedMine.printingId(), clickedMine.finish(), amt);
			return true;
		}

		// Lock / Cancel Countdown button on Your Offer side
		int pad = 5;
		int ctrlY = layout.colY + layout.colH - 22;
		int innerW = layout.colW - pad * 2;
		if (inside(mouseX, mouseY, layout.myColX + pad, ctrlY, innerW, 17)) {
			boolean counting = myLocked && theirLocked && countdownSeconds >= 0;
			if (counting) {
				sendAction("cancel_countdown", "", "", "", 0);
			} else if (!myOffer.isEmpty() || !theirOffer.isEmpty()) {
				sendAction("lock", "", "", "", 0);
			}
			return true;
		}

		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (inside(mouseX, mouseY, layout.myColX, layout.colY, layout.colW, layout.colH)) {
			int max = offerPageCount(myOffer) - 1;
			if (scrollY < 0 && myOfferPage < max) {
				myOfferPage++;
			} else if (scrollY > 0 && myOfferPage > 0) {
				myOfferPage--;
			}
			return true;
		}
		if (inside(mouseX, mouseY, layout.theirColX, layout.colY, layout.colW, layout.colH)) {
			int max = offerPageCount(theirOffer) - 1;
			if (scrollY < 0 && theirOfferPage < max) {
				theirOfferPage++;
			} else if (scrollY > 0 && theirOfferPage > 0) {
				theirOfferPage--;
			}
			return true;
		}
		if (scrollY > 0 && binderPage > 0) {
			binderPage--;
			return true;
		}
		if (scrollY < 0 && binderPage + 1 < binderPageCount()) {
			binderPage++;
			return true;
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (search != null && search.isFocused()) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
				search.setFocused(false);
				this.setFocused(null);
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT && binderPage > 0) {
			binderPage--;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT && binderPage + 1 < binderPageCount()) {
			binderPage++;
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private void sendAction(String action, String oracleId, String printingId, String finish, int amount) {
		if (!ClientPlayNetworking.canSend(TradeActionC2SPayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new TradeActionC2SPayload(action, oracleId, printingId, finish, amount));
	}

	private int binderSlotAt(double mouseX, double mouseY) {
		int per = binderPerPage();
		for (int slot = 0; slot < per; slot++) {
			int col = slot % layout.binderCols;
			int row = slot / layout.binderCols;
			int x = layout.gridX + col * (layout.cardW + layout.gap);
			int y = layout.gridY + row * (layout.cardH + layout.gap);
			if (inside(mouseX, mouseY, x, y, layout.cardW, layout.cardH)) {
				return slot;
			}
		}
		return -1;
	}

	private BinderCard offerCardAt(int colX, int colY, int colW, int colH, List<BinderCard> offer, int offerPage, double mouseX, double mouseY) {
		int pad = 5;
		int innerW = colW - pad * 2;
		int gridTop = colY + 30;
		int gridBot = colY + colH - 26;
		int availH = Math.max(40, gridBot - gridTop);
		int gap = 4;
		int cw = (innerW - gap) / OFFER_COLS;
		int ch = cw * 7 / 5;
		if (ch * OFFER_ROWS + gap * (OFFER_ROWS - 1) > availH) {
			ch = (availH - gap * (OFFER_ROWS - 1)) / OFFER_ROWS;
			cw = Math.max(24, ch * 5 / 7);
		}
		int gridW = cw * OFFER_COLS + gap;
		int startX = colX + (colW - gridW) / 2;
		int startY = gridTop + Math.max(0, (availH - (ch * OFFER_ROWS + gap * (OFFER_ROWS - 1))) / 2);

		int startIdx = offerPage * OFFER_PER_PAGE;
		for (int slot = 0; slot < OFFER_PER_PAGE; slot++) {
			int idx = startIdx + slot;
			if (idx >= offer.size()) {
				break;
			}
			int col = slot % OFFER_COLS;
			int row = slot / OFFER_COLS;
			int sx = startX + col * (cw + gap);
			int sy = startY + row * (ch + gap);
			if (inside(mouseX, mouseY, sx, sy, cw, ch)) {
				return offer.get(idx);
			}
		}
		return null;
	}

	private String fit(String text, int maxWidth) {
		if (text == null || maxWidth <= 0) {
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
		if (text == null || maxWidth <= 0) {
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
		int space = text.lastIndexOf(' ', split);
		if (space > 0) {
			return new String[] {text.substring(0, space), fit(text.substring(space + 1), maxWidth)};
		}
		return new String[] {text.substring(0, split), fit(text.substring(split), maxWidth)};
	}

	private record Layout(
		int leftX, int leftY, int leftW, int leftH,
		int rightX, int rightY, int rightW, int rightH,
		int searchX, int searchY,
		int[] colorX, int[] rarityX, int chipsY,
		int pageX, int pageY, int pageW, int pageH,
		int binderCols, int cardW, int cardH, int gap, int gridX, int gridY,
		int prevX, int nextX, int arrowY,
		int myColX, int theirColX, int colY, int colW, int colH
	) {
		private static final Layout EMPTY = new Layout(
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, new int[6], new int[4], 0,
			0, 0, 0, 0,
			3, 40, 56, 6, 0, 0,
			0, 0, 0,
			0, 0, 0, 100, 100
		);

		private static Layout compute(int screenW, int screenH) {
			int margin = 8;
			int usableW = Math.max(380, screenW - margin * 2);
			int usableH = Math.max(220, screenH - margin * 2);
			int gapBetweenPanes = 6;

			int rightW = Mth.clamp((int) (usableW * 0.48f), 196, 360);
			int leftW = usableW - rightW - gapBetweenPanes;
			if (leftW < 180) {
				leftW = 180;
				rightW = usableW - leftW - gapBetweenPanes;
			}

			int leftX = margin;
			int leftY = margin;
			int leftH = usableH;
			int rightX = leftX + leftW + gapBetweenPanes;
			int rightY = margin;
			int rightH = usableH;

			int searchX = leftX + 14;
			int searchY = leftY + 30;
			int chipsY = searchY + 19;

			int[] colorX = new int[COLOR_KEYS.length];
			for (int i = 0; i < colorX.length; i++) {
				colorX[i] = searchX + i * 17;
			}
			int[] rarityX = new int[RARITY_KEYS.length];
			int rarityStart = leftX + leftW - 12 - RARITY_KEYS.length * 17;
			int minRarity = searchX + COLOR_KEYS.length * 17 + 8;
			if (rarityStart < minRarity) {
				rarityStart = minRarity;
			}
			for (int i = 0; i < rarityX.length; i++) {
				rarityX[i] = rarityStart + i * 17;
			}

			int pageX = leftX + 10;
			int pageY = chipsY + 19;
			int pageW = leftW - 18;
			int pageH = leftH - (pageY - leftY) - 18;

			int gap = 6;
			int arrow = 18;
			int innerW = Math.max(60, pageW - arrow * 2 - 8);
			int innerH = Math.max(60, pageH - 8);
			int cardH = (innerH - gap * (BINDER_ROWS - 1)) / BINDER_ROWS;
			int cardW = cardH * 5 / 7;
			if (cardW < 34) {
				cardW = 34;
				cardH = 48;
			}
			int binderCols = Math.max(3, Math.min(4, (innerW + gap) / (cardW + gap)));
			while (binderCols > 3 && binderCols * cardW + (binderCols - 1) * gap > innerW) {
				binderCols--;
			}
			if (binderCols * cardW + (binderCols - 1) * gap > innerW) {
				cardW = (innerW - (binderCols - 1) * gap) / binderCols;
				cardH = cardW * 7 / 5;
			}
			int gridW = binderCols * cardW + (binderCols - 1) * gap;
			int gridH = BINDER_ROWS * cardH + (BINDER_ROWS - 1) * gap;
			int gridX = pageX + arrow + Math.max(0, (innerW - gridW) / 2) + 4;
			int gridY = pageY + Math.max(0, (pageH - gridH) / 2);

			int prevX = pageX + 2;
			int nextX = pageX + pageW - 16;
			int arrowY = pageY + pageH / 2 - 7;

			// Right pane columns: Your Offer | Their Offer
			int colGap = 6;
			int innerRightW = rightW - 20;
			int colW = (innerRightW - colGap) / 2;
			int myColX = rightX + 8;
			int theirColX = myColX + colW + colGap;
			int colY = rightY + 22;
			int colH = rightH - 42;

			return new Layout(
				leftX, leftY, leftW, leftH,
				rightX, rightY, rightW, rightH,
				searchX, searchY,
				colorX, rarityX, chipsY,
				pageX, pageY, pageW, pageH,
				binderCols, cardW, cardH, gap, gridX, gridY,
				prevX, nextX, arrowY,
				myColX, theirColX, colY, colW, colH
			);
		}
	}
}
