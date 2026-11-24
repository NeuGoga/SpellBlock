package com.neugoga.spellblock.client;

import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.network.BinderSyncS2CPayload;
import com.neugoga.spellblock.network.FavouriteC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.neugoga.spellblock.client.ui.Ui.any;
import static com.neugoga.spellblock.client.ui.Ui.colorRank;
import static com.neugoga.spellblock.client.ui.Ui.inside;
import static com.neugoga.spellblock.client.ui.Ui.rarityColor;
import static com.neugoga.spellblock.client.ui.Ui.rarityLabel;

/** One binder page: nine portrait sleeves, a count in the corner, and filters along the top. */
public final class BinderScreen extends Screen {
	private static final int PER_PAGE = 9;
	private static final int COLS = 3;
	private static final int SLIDE_MS = 280;
	private static final String[] COLOR_KEYS = {"W", "U", "B", "R", "G", "C"};
	private static final String[] COLOR_NAMES = {"White", "Blue", "Black", "Red", "Green", "Colorless"};
	private static final int[] COLOR_INK = {0xFF2A2618, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFF1C1C1C};
	private static final int[] COLOR_FILL = {0xFFF3E8B8, 0xFF3B7FBF, 0xFF4E4458, 0xFFC4483A, 0xFF3E8A45, 0xFFB7B7B7};
	private static final String[] RARITY_KEYS = {"C", "U", "R", "M"};
	private static final String[] RARITY_NAMES = {"Common", "Uncommon", "Rare", "Mythic"};
	private static final String[] RARITY_IDS = {"common", "uncommon", "rare", "mythic"};

	private final boolean[] colorOn = new boolean[COLOR_KEYS.length];
	private final boolean[] rarityOn = new boolean[RARITY_KEYS.length];

	private List<BinderCard> all = List.of();
	private List<BinderCard> visible = List.of();
	private final Set<String> favourites = new HashSet<>();
	private boolean favOnly;
	private String status = "";
	private int dust;
	private String query = "";
	private int page;
	private int slideFrom = -1;
	private int slideDir;
	private long slideStart;
	private String selectedOracle = "";
	private String selectedPrinting = "";
	private String selectedFinish = "";
	private EditBox search;
	private Frame frame = Frame.EMPTY;

	public BinderScreen(BinderSyncS2CPayload payload) {
		super(Component.translatable("spellblock.binder.title"));
		apply(payload);
	}

	public void replace(BinderSyncS2CPayload payload) {
		apply(payload);
		rebuild();
	}

	@Override
	protected void init() {
		int searchW = Math.min(220, this.width - 80);
		search = new EditBox(this.font, (this.width - searchW) / 2, 0, searchW, 16, Component.translatable("spellblock.binder.search"));
		search.setMaxLength(48);
		search.setHint(Component.translatable("spellblock.binder.search"));
		search.setValue(query);
		search.setResponder(text -> {
			if (!text.equals(query)) {
				query = text;
				page = 0;
				slideFrom = -1;
				rebuild();
			}
		});
		addRenderableWidget(search);
		rebuild();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		frame = Frame.layout(this.width, this.height);
		search.setX(frame.searchX);
		search.setY(frame.searchY);
		if (slideFrom >= 0 && UtilMillis.now() - slideStart >= SLIDE_MS) {
			slideFrom = -1;
		}
		prepareArt();
		graphics.fill(0, 0, this.width, this.height, 0xC0100C0A);
		graphics.fill(frame.panelX, frame.panelY, frame.panelX + frame.panelW, frame.panelY + frame.panelH, 0xFF4A3424);
		graphics.fill(frame.panelX, frame.panelY, frame.panelX + 10, frame.panelY + frame.panelH, 0xFF2C1E14);
		graphics.fill(frame.pageX, frame.pageY, frame.pageX + frame.pageW, frame.pageY + frame.pageH, 0xFF1B1714);
		graphics.drawString(this.font, this.title, frame.panelX + 16, frame.panelY + 6, 0xFFE6D3A3, false);
		graphics.drawString(this.font, totals(), frame.panelX + 16, frame.panelY + 18, 0xFFD9CBB2, false);
		drawChips(graphics, mouseX, mouseY);
		drawArrow(graphics, frame.prevX, frame.prevY, true, page > 0);
		drawArrow(graphics, frame.nextX, frame.nextY, false, page + 1 < pageCount());
		String label = (page + 1) + " / " + pageCount();
		graphics.drawString(this.font, label, frame.panelX + frame.panelW - 16 - this.font.width(label), frame.panelY + frame.panelH - 14, 0xFFE6D3A3, false);

		graphics.enableScissor(frame.pageX, frame.pageY, frame.pageX + frame.pageW, frame.pageY + frame.pageH);
		if (slideFrom >= 0) {
			float t = Mth.clamp((UtilMillis.now() - slideStart) / (float) SLIDE_MS, 0, 1);
			t = t * t * (3 - 2 * t);
			float travel = frame.pageW;
			float moved = t * travel;
			float oldShift = slideDir > 0 ? -moved : moved;
			float newShift = slideDir > 0 ? travel - moved : -travel + moved;
			drawShifted(graphics, slideFrom, oldShift, mouseX, mouseY);
			drawShifted(graphics, page, newShift, mouseX, mouseY);
		} else {
			drawPage(graphics, page, frame.pageX, mouseX, mouseY, true);
		}
		graphics.disableScissor();
		super.render(graphics, mouseX, mouseY, partialTick);
		drawHover(graphics, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && !sliding()) {
			int color = chipAt(frame.colorX, frame.colorY, COLOR_KEYS.length, mouseX, mouseY);
			if (color >= 0) {
				colorOn[color] = !colorOn[color];
				page = 0;
				slideFrom = -1;
				rebuild();
				return true;
			}
			int rarity = chipAt(frame.rarityX, frame.rarityY, RARITY_KEYS.length, mouseX, mouseY);
			if (rarity >= 0) {
				rarityOn[rarity] = !rarityOn[rarity];
				page = 0;
				slideFrom = -1;
				rebuild();
				return true;
			}
			if (inside(mouseX, mouseY, frame.prevX, frame.prevY, 14, 14) && page > 0) {
				turn(-1);
				return true;
			}
			if (inside(mouseX, mouseY, frame.nextX, frame.nextY, 14, 14) && page + 1 < pageCount()) {
				turn(1);
				return true;
			}
			int slot = slotAt(mouseX, mouseY);
			if (slot >= 0) {
				int index = page * PER_PAGE + slot;
				if (index < visible.size()) {
					BinderCard card = visible.get(index);
					selectedOracle = card.oracleId();
					selectedPrinting = card.printingId();
					selectedFinish = card.finish();
				}
				return true;
			}
		}
		if (button == 1) {
			int slot = slotAt(mouseX, mouseY);
			if (slot >= 0) {
				int index = page * PER_PAGE + slot;
				if (index < visible.size()) {
					toggleFav(visible.get(index));
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY < 0) {
			turn(1);
		} else if (scrollY > 0) {
			turn(-1);
		}
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (search.isFocused()) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (keyCode == GLFW.GLFW_KEY_LEFT) {
			turn(-1);
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_RIGHT) {
			turn(1);
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_F) {
			favOnly = !favOnly;
			page = 0;
			slideFrom = -1;
			rebuild();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void apply(BinderSyncS2CPayload payload) {
		all = payload.cards();
		status = payload.status();
		dust = payload.dust();
		favourites.clear();
		favourites.addAll(payload.favourites());
	}

	private void rebuild() {
		List<BinderCard> next = new ArrayList<>();
		for (BinderCard card : all) {
			if (accepts(card)) {
				next.add(card);
			}
		}
		next.sort(Comparator
			.comparingInt((BinderCard card) -> colorRank(card.colorIdentity()))
			.thenComparing(BinderCard::colorIdentity)
			.thenComparingDouble(BinderCard::manaValue)
			.thenComparing(card -> card.name().toLowerCase(Locale.ROOT))
			.thenComparing(BinderCard::finish)
			.thenComparing(BinderCard::treatment)
			.thenComparing(BinderCard::printingId));
		visible = next;
		if (page >= pageCount()) {
			page = pageCount() - 1;
		}
		if (page < 0) {
			page = 0;
		}
	}

	private boolean accepts(BinderCard card) {
		if (favOnly && !isFav(card)) {
			return false;
		}
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

	private void turn(int dir) {
		if (sliding()) {
			return;
		}
		int next = page + dir;
		if (next < 0 || next >= pageCount()) {
			return;
		}
		slideFrom = page;
		slideDir = dir;
		slideStart = UtilMillis.now();
		page = next;
	}

	private boolean sliding() {
		return slideFrom >= 0 && UtilMillis.now() - slideStart < SLIDE_MS;
	}

	private int pageCount() {
		return Math.max(1, (visible.size() + PER_PAGE - 1) / PER_PAGE);
	}

	private String totals() {
		int copies = 0;
		for (BinderCard card : all) {
			copies += card.count();
		}
		String line = all.size() + " unique · " + copies + " copies · " + dust + " dust";
		if (visible.size() != all.size()) {
			line = line + " · " + visible.size() + " shown";
		}
		if (!status.isEmpty()) {
			return all.isEmpty() ? status : line + " · " + status;
		}
		return line;
	}

	private void prepareArt() {
		CardArt.beginFrame();
		pinPage(page);
		if (slideFrom >= 0) {
			pinPage(slideFrom);
		} else {
			if (page > 0) {
				pinPage(page - 1);
			}
			if (page + 1 < pageCount()) {
				pinPage(page + 1);
			}
		}
		preloadPage(page);
		if (slideFrom >= 0) {
			preloadPage(slideFrom);
		} else if (page + 1 < pageCount()) {
			preloadPage(page + 1);
		}
	}

	private void pinPage(int pageIndex) {
		int start = pageIndex * PER_PAGE;
		for (int i = 0; i < PER_PAGE && start + i < visible.size(); i++) {
			CardArt.pin(visible.get(start + i).printingId());
		}
	}

	private void preloadPage(int pageIndex) {
		int start = pageIndex * PER_PAGE;
		for (int i = 0; i < PER_PAGE && start + i < visible.size(); i++) {
			CardArt.preload(visible.get(start + i).printingId());
		}
	}

	private void drawShifted(GuiGraphics graphics, int pageIndex, float dx, int mouseX, int mouseY) {
		graphics.pose().pushPose();
		graphics.pose().translate(dx, 0.0f, 0.0f);
		drawPage(graphics, pageIndex, frame.pageX, mouseX, mouseY, false);
		graphics.pose().popPose();
	}

	private void drawPage(GuiGraphics graphics, int pageIndex, int originX, int mouseX, int mouseY, boolean live) {
		for (int slot = 0; slot < PER_PAGE; slot++) {
			int col = slot % COLS;
			int row = slot / COLS;
			int x = originX + frame.gridX + col * (frame.cardW + frame.gap);
			int y = frame.gridY + row * (frame.cardH + frame.gap);
			int index = pageIndex * PER_PAGE + slot;
			BinderCard card = index >= 0 && index < visible.size() ? visible.get(index) : null;
			boolean hot = live && inside(mouseX, mouseY, x, y, frame.cardW, frame.cardH);
			drawSleeve(graphics, card, x, y, hot);
		}
	}

	private void drawSleeve(GuiGraphics graphics, BinderCard card, int x, int y, boolean hovered) {
		int w = frame.cardW;
		int h = frame.cardH;
		if (card == null) {
			graphics.fill(x, y, x + w, y + h, 0xFF3A322C);
			graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF120F0D);
			return;
		}
		boolean selected = card.oracleId().equals(selectedOracle)
			&& card.printingId().equals(selectedPrinting)
			&& card.finish().equals(selectedFinish);
		int rim = selected ? 0xFFFFD36A : (hovered ? 0xFF8A7560 : rarityColor(card.rarity()));
		graphics.fill(x, y, x + w, y + h, rim);
		if (!CardArt.draw(graphics, card.printingId(), card.finish(), x + 1, y + 1, w - 2, h - 2)) {
			graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF1A1714);
			graphics.fill(x + 3, y + 3, x + 6, y + h - 3, rarityColor(card.rarity()));
			int textW = w - 14;
			String[] lines = wrap(card.name(), textW);
			graphics.drawString(this.font, lines[0], x + 9, y + 5, 0xFFF4EFE4, false);
			int manaY = y + 16;
			if (!lines[1].isEmpty()) {
				graphics.drawString(this.font, lines[1], x + 9, y + 15, 0xFFF4EFE4, false);
				manaY = y + 26;
			}
			if (!card.manaCost().isEmpty() && manaY < y + h - 14) {
				graphics.drawString(this.font, fit(card.manaCost(), textW), x + 9, manaY, 0xFFB7C4CE, false);
			}
			String tag = CardArt.caption(card.treatment(), card.finish());
			if (!tag.isEmpty()) {
				int tagColor = Finish.ETCHED.name().equals(card.finish()) ? 0xFF8FD0C8 : 0xFFE6C56A;
				graphics.drawString(this.font, fit(tag, textW), x + 9, y + h - 12, tagColor, false);
			}
		}
		String count = "x" + card.count();
		int countW = this.font.width(count);
		int badgeX = x + w - countW - 5;
		graphics.fill(badgeX - 2, y + h - 13, x + w - 2, y + h - 2, 0xCC000000);
		graphics.drawString(this.font, count, badgeX, y + h - 12, 0xFFFFFFFF, false);
		if (isFav(card)) {
			graphics.drawString(this.font, "*", x + 3, y + 2, 0xFFFFD36A, false);
		}
	}

	private void drawChips(GuiGraphics graphics, int mouseX, int mouseY) {
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			drawChip(graphics, frame.colorX[i], frame.colorY, COLOR_KEYS[i], COLOR_FILL[i], COLOR_INK[i], colorOn[i]);
		}
		for (int i = 0; i < RARITY_KEYS.length; i++) {
			drawChip(graphics, frame.rarityX[i], frame.rarityY, RARITY_KEYS[i], rarityColor(RARITY_IDS[i]), 0xFF1C1C1C, rarityOn[i]);
		}
	}

	private void drawChip(GuiGraphics graphics, int x, int y, String letter, int fill, int ink, boolean on) {
		graphics.fill(x - 1, y - 1, x + 15, y + 15, on ? 0xFFFFD36A : 0xFF2A241E);
		graphics.fill(x, y, x + 14, y + 14, fill);
		graphics.drawString(this.font, letter, x + 4, y + 3, ink, false);
	}

	private void drawArrow(GuiGraphics graphics, int x, int y, boolean left, boolean enabled) {
		int color = enabled ? 0xFFE6D3A3 : 0xFF6A5A48;
		graphics.fill(x, y, x + 14, y + 14, 0xFF2C1E14);
		graphics.drawString(this.font, left ? "<" : ">", x + 4, y + 3, color, false);
	}

	private void drawHover(GuiGraphics graphics, int mouseX, int mouseY) {
		if (sliding()) {
			return;
		}
		int color = chipAt(frame.colorX, frame.colorY, COLOR_KEYS.length, mouseX, mouseY);
		if (color >= 0) {
			graphics.renderTooltip(this.font, Component.literal(COLOR_NAMES[color]), mouseX, mouseY);
			return;
		}
		int rarity = chipAt(frame.rarityX, frame.rarityY, RARITY_KEYS.length, mouseX, mouseY);
		if (rarity >= 0) {
			graphics.renderTooltip(this.font, Component.literal(RARITY_NAMES[rarity]), mouseX, mouseY);
			return;
		}
		int slot = slotAt(mouseX, mouseY);
		if (slot < 0) {
			return;
		}
		int index = page * PER_PAGE + slot;
		if (index >= visible.size()) {
			return;
		}
		BinderCard card = visible.get(index);
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(card.name()));
		if (!card.typeLine().isEmpty()) {
			lines.add(Component.literal(card.typeLine()));
		}
		String meta = rarityLabel(card.rarity());
		if (!card.setCode().isEmpty()) {
			meta = meta + " · " + card.setCode().toUpperCase(Locale.ROOT);
		}
		String extra = CardArt.caption(card.treatment(), card.finish());
		if (!extra.isEmpty()) {
			meta = meta + " · " + extra;
		}
		meta = meta + " · x" + card.count();
		lines.add(Component.literal(meta));
		graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
	}

	private int slotAt(double mouseX, double mouseY) {
		for (int slot = 0; slot < PER_PAGE; slot++) {
			int col = slot % COLS;
			int row = slot / COLS;
			int x = frame.pageX + frame.gridX + col * (frame.cardW + frame.gap);
			int y = frame.gridY + row * (frame.cardH + frame.gap);
			if (inside(mouseX, mouseY, x, y, frame.cardW, frame.cardH)) {
				return slot;
			}
		}
		return -1;
	}

	private static int chipAt(int[] xs, int y, int count, double mouseX, double mouseY) {
		for (int i = 0; i < count; i++) {
			if (inside(mouseX, mouseY, xs[i], y, 14, 14)) {
				return i;
			}
		}
		return -1;
	}

	private String fit(String text, int maxWidth) {
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

	private static final class UtilMillis {
		private UtilMillis() {
		}

		private static long now() {
			return System.currentTimeMillis();
		}
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// empty on purpose: removes the vanilla blur
	}

	@Override
	protected void setInitialFocus() {
		// empty on purpose: stops the search box from auto-focusing
	}

	private record Frame(
		int panelX,
		int panelY,
		int panelW,
		int panelH,
		int searchX,
		int searchY,
		int pageX,
		int pageY,
		int pageW,
		int pageH,
		int gridX,
		int gridY,
		int cardW,
		int cardH,
		int gap,
		int[] colorX,
		int colorY,
		int[] rarityX,
		int rarityY,
		int prevX,
		int prevY,
		int nextX,
		int nextY
	) {
		private static final Frame EMPTY = new Frame(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 4, new int[6], 0, new int[4], 0, 0, 0, 0, 0);

		private static Frame layout(int screenW, int screenH) {
			int margin = 8;
			int panelW = Math.max(320, screenW - margin * 2);
			int panelH = Math.max(220, screenH - margin * 2);
			int panelX = (screenW - panelW) / 2;
			int panelY = (screenH - panelH) / 2;
			int searchX = panelX + 16;
			int searchY = panelY + 30;
			int chipsY = searchY + 20;
			int[] colorX = new int[COLOR_KEYS.length];
			for (int i = 0; i < colorX.length; i++) {
				colorX[i] = searchX + i * 18;
			}
			int[] rarityX = new int[RARITY_KEYS.length];
			int rarityStart = panelX + panelW - 16 - RARITY_KEYS.length * 18;
			int minStart = panelX + 16 + COLOR_KEYS.length * 18 + 8;
			if (rarityStart < minStart) {
				rarityStart = minStart;
			}
			for (int i = 0; i < rarityX.length; i++) {
				rarityX[i] = rarityStart + i * 18;
			}
			int pageX = panelX + 8;
			int pageY = chipsY + 18;
			int pageW = panelW - 16;
			int pageH = panelH - (pageY - panelY) - 16;
			int gap = 8;
			int arrow = 18;
			int innerW = pageW - arrow * 2 - 8;
			int innerH = Math.max(1, pageH - 6);
			int cardH = (innerH - gap * 2) / 3;
			int cardW = cardH * 5 / 7;
			if (cardW * 3 + gap * 2 > innerW) {
				cardW = (innerW - gap * 2) / 3;
				cardH = cardW * 7 / 5;
			}
			if (cardW < 48) {
				cardW = 48;
				cardH = 67;
			}
			int gridW = cardW * 3 + gap * 2;
			int gridX = arrow + Math.max(0, (innerW - gridW) / 2);
			int gridY = pageY + Math.max(0, (pageH - (cardH * 3 + gap * 2)) / 2);
			int prevX = pageX + 2;
			int nextX = pageX + pageW - 16;
			int prevY = pageY + pageH / 2 - 7;
			return new Frame(panelX, panelY, panelW, panelH, searchX, searchY, pageX, pageY, pageW, pageH, gridX, gridY, cardW, cardH, gap, colorX, chipsY, rarityX, chipsY, prevX, prevY, nextX, prevY);
		}
	}
	private boolean isFav(BinderCard card) {
		return favourites.contains(card.oracleId() + "\0" + card.printingId() + "\0" + card.finish());
	}

	private void toggleFav(BinderCard card) {
		if (!ClientPlayNetworking.canSend(FavouriteC2SPayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new FavouriteC2SPayload(card.oracleId(), card.printingId(), card.finish(), "binder"));
	}


}
