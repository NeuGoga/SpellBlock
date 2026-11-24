package com.neugoga.spellblock.client;

import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.datapack.CardRestrictions;
import com.neugoga.spellblock.datapack.EconomyRules;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.DustValues;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.PrintingOption;
import com.neugoga.spellblock.network.DusteryActionC2SPayload;
import com.neugoga.spellblock.network.DusteryPrintingsC2SPayload;
import com.neugoga.spellblock.network.DusteryPrintingsS2CPayload;
import com.neugoga.spellblock.network.DusterySearchC2SPayload;
import com.neugoga.spellblock.network.DusterySearchS2CPayload;
import com.neugoga.spellblock.network.DusterySyncS2CPayload;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.neugoga.spellblock.client.ui.Ui.any;
import static com.neugoga.spellblock.client.ui.Ui.colorRank;
import static com.neugoga.spellblock.client.ui.Ui.inside;
import static com.neugoga.spellblock.client.ui.Ui.rarityColor;
import static com.neugoga.spellblock.client.ui.Ui.rarityLabel;

/**
 * Full-screen binder-styled Dustery workshop:
 * disenchant single/stack/all non-favourites, search all MTG cards, browse unique Scryfall printings,
 * choose finishes (Normal/Foil/Etched), and craft cards.
 */
public final class DusteryScreen extends Screen {
	private static final int ROWS = 3;
	private static final String[] COLOR_KEYS = {"W", "U", "B", "R", "G", "C"};
	private static final String[] COLOR_NAMES = {"White", "Blue", "Black", "Red", "Green", "Colorless"};
	private static final int[] COLOR_INK = {0xFF2A2618, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFFF4F7FB, 0xFF1C1C1C};
	private static final int[] COLOR_FILL = {0xFFF3E8B8, 0xFF3B7FBF, 0xFF4E4458, 0xFFC4483A, 0xFF3E8A45, 0xFFB7B7B7};
	private static final String[] RARITY_KEYS = {"C", "U", "R", "M"};
	private static final String[] RARITY_NAMES = {"Common", "Uncommon", "Rare", "Mythic"};
	private static final String[] RARITY_IDS = {"common", "uncommon", "rare", "mythic"};
	private static final String[] PRINT_FILTER_LABELS = {"All", "No-Norm", "Retro", "Border", "SPG"};
	private static final String[] PRINT_FILTER_TIPS = {
		"Show all printings",
		"Special printings only (exclude normal frames)",
		"Retro / old-frame printings",
		"Borderless, full-art, showcase & extended-art",
		"Special Guests (SPG)"
	};

	private static final int GOLD = 0xFFE6D3A3;
	private static final int GOLD_BRIGHT = 0xFFFFD36A;
	private static final int PAPER = 0xFFF4EFE4;
	private static final int MUTED = 0xFFD9CBB2;
	private static final int SUB_MUTED = 0xFF9E8D79;

	private final boolean[] colorOn = new boolean[COLOR_KEYS.length];
	private final boolean[] rarityOn = new boolean[RARITY_KEYS.length];
	private final Set<String> favourites = new HashSet<>();
	private final Set<String> favOracles = new HashSet<>();
	private final Map<String, Integer> ownedByKey = new HashMap<>();
	private final Map<String, Integer> ownedByOracle = new HashMap<>();

	private List<BinderCard> all = List.of();
	private List<BinderCard> catalogResults = List.of();
	private List<BinderCard> visible = List.of();
	private int catalogTotalMatches;
	private int indexSize;
	private int dust;
	private boolean dustEnabled = true;
	private boolean craftEnabled = true;
	private EconomyRules economy = EconomyRules.DEFAULTS;
	private CardRestrictions restrictions = CardRestrictions.EMPTY;
	private String status = "";
	private String query = "";
	private int tab; // 0 = Disenchant, 1 = Craft
	private boolean craftOwnedOnly;
	private boolean favOnly;
	private int page;
	private int dustAmount = 1;
	private int craftAmount = 1;
	private boolean confirmDustAll;
	private boolean showBackFace;

	private String selectedOracle = "";
	private String selectedPrinting = "";
	private String selectedFinish = Finish.NONFOIL.name();
	private BinderCard selectedSnapshot;

	private String printingsOracle = "";
	private List<PrintingOption> printings = List.of();
	private boolean printingsLoading;
	private int printingFilter; // 0 = All, 1 = Non-normal, 2 = Retro, 3 = Borderless/Alt, 4 = SPG
	private int printingScroll;

	private EditBox search;
	private Layout layout = Layout.EMPTY;

	public DusteryScreen(DusterySyncS2CPayload payload) {
		super(Component.literal("Dustery"));
		apply(payload);
	}

	public void replace(DusterySyncS2CPayload payload) {
		if (payload.closed()) {
			if (this.minecraft != null) {
				this.minecraft.setScreen(null);
			}
			return;
		}
		apply(payload);
		confirmDustAll = false;
		rebuild();
	}

	public void onSearchResults(DusterySearchS2CPayload payload) {
		catalogResults = payload.cards();
		catalogTotalMatches = payload.totalMatches();
		if (!payload.status().isEmpty()) {
			status = payload.status();
		}
		if (tab == 1 && !craftOwnedOnly) {
			rebuild();
		}
	}

	public void onPrintingsLoaded(DusteryPrintingsS2CPayload payload) {
		if (!payload.oracleId().equals(selectedOracle)) {
			return;
		}
		printingsOracle = payload.oracleId();
		printings = payload.printings();
		printingsLoading = payload.loading();
		if (!payload.status().isEmpty()) {
			status = payload.status();
		}
		List<PrintingOption> filtered = filteredPrintings();
		if (!filtered.isEmpty()) {
			boolean matched = false;
			for (int i = 0; i < filtered.size(); i++) {
				if (filtered.get(i).printingId().equals(selectedPrinting)) {
					matched = true;
					ensurePrintingVisible(i);
					break;
				}
			}
			if (!matched) {
				selectPrintingOption(filtered.getFirst(), 0);
			}
		} else {
			printingScroll = 0;
		}
	}

	private void apply(DusterySyncS2CPayload payload) {
		all = payload.cards();
		dust = payload.dust();
		indexSize = payload.indexSize();
		dustEnabled = payload.dustEnabled();
		craftEnabled = payload.craftEnabled();
		economy = payload.economy() == null ? EconomyRules.DEFAULTS : payload.economy();
		restrictions = payload.restrictions() == null ? CardRestrictions.EMPTY : payload.restrictions();
		if (tab == 0 && !dustEnabled && craftEnabled) {
			tab = 1;
		} else if (tab == 1 && !craftEnabled && dustEnabled) {
			tab = 0;
		}
		if (economy.craft().ownedOnly()) {
			craftOwnedOnly = true;
		}
		if (!economy.craft().isFinishAllowed(Finish.fromName(selectedFinish))) {
			selectedFinish = Finish.NONFOIL.name();
		}
		craftAmount = Mth.clamp(craftAmount, 1, economy.craft().maxBatch());
		status = payload.status() == null ? "" : payload.status();
		favourites.clear();
		favourites.addAll(payload.favourites());
		favOracles.clear();
		for (String fav : favourites) {
			int nul = fav.indexOf('\0');
			if (nul > 0) {
				favOracles.add(fav.substring(0, nul));
			}
		}
		ownedByKey.clear();
		ownedByOracle.clear();
		for (BinderCard card : all) {
			ownedByKey.put(cardKey(card.oracleId(), card.printingId(), card.finish()), card.count());
			if (card.count() > 0) {
				ownedByOracle.merge(card.oracleId(), card.count(), Integer::sum);
			}
		}
		if (!catalogResults.isEmpty()) {
			List<BinderCard> refreshed = new ArrayList<>(catalogResults.size());
			for (BinderCard c : catalogResults) {
				int owned = ownedByOracle.getOrDefault(c.oracleId(), 0);
				refreshed.add(owned == c.count() ? c : new BinderCard(
					c.oracleId(), c.printingId(), c.finish(), owned,
					c.name(), c.manaCost(), c.manaValue(), c.typeLine(),
					c.colorIdentity(), c.rarity(), c.setCode(), c.treatment()
				));
			}
			catalogResults = refreshed;
		}
	}

	@Override
	protected void init() {
		search = new EditBox(this.font, 0, 0, 120, 14, Component.literal("Search"));
		search.setMaxLength(64);
		search.setHint(Component.literal("Search (press Enter) or t:type"));
		search.setValue(query);
		search.setResponder(text -> {
			String next = text == null ? "" : text;
			if (!next.equals(query)) {
				query = next;
				page = 0;
				confirmDustAll = false;
				if (tab == 0 || craftOwnedOnly) {
					rebuild();
				}
			}
		});
		addRenderableWidget(search);
		rebuild();
		if (tab == 1 && craftEnabled && !craftOwnedOnly && catalogResults.isEmpty()) {
			sendSearchNow();
		}
	}

	@Override
	public void onClose() {
		if (ClientPlayNetworking.canSend(DusteryActionC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new DusteryActionC2SPayload("close", "", "", "", 0));
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

	private void sendSearchNow() {
		if (!ClientPlayNetworking.canSend(DusterySearchC2SPayload.TYPE)) {
			return;
		}
		page = 0;
		ClientPlayNetworking.send(new DusterySearchC2SPayload(query, encodeColors(), encodeRarities()));
	}

	private void requestPrintings(String oracleId) {
		if (oracleId == null || oracleId.isEmpty() || !ClientPlayNetworking.canSend(DusteryPrintingsC2SPayload.TYPE)) {
			return;
		}
		printingsOracle = oracleId;
		printings = List.of();
		printingsLoading = true;
		printingScroll = 0;
		ClientPlayNetworking.send(new DusteryPrintingsC2SPayload(oracleId));
	}

	private String encodeColors() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			if (colorOn[i]) {
				sb.append(COLOR_KEYS[i]);
			}
		}
		return sb.toString();
	}

	private String encodeRarities() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < RARITY_IDS.length; i++) {
			if (rarityOn[i]) {
				if (!sb.isEmpty()) {
					sb.append(',');
				}
				sb.append(RARITY_IDS[i]);
			}
		}
		return sb.toString();
	}

	private void rebuild() {
		List<BinderCard> next = new ArrayList<>();
		if (tab == 1 && !craftOwnedOnly) {
			for (BinderCard card : catalogResults) {
				if (favOnly && !favOracles.contains(card.oracleId())) {
					continue;
				}
				next.add(card);
			}
		} else {
			for (BinderCard card : all) {
				if (acceptsOwned(card)) {
					next.add(card);
				}
			}
			next.sort(Comparator
				.comparing((BinderCard c) -> isFav(c) ? 0 : 1)
				.thenComparingInt(c -> colorRank(c.colorIdentity()))
				.thenComparing(BinderCard::colorIdentity)
				.thenComparingDouble(BinderCard::manaValue)
				.thenComparing(c -> c.name().toLowerCase(Locale.ROOT))
				.thenComparing(BinderCard::finish)
				.thenComparing(BinderCard::printingId));
		}
		visible = next;
		int pages = pageCount();
		if (page >= pages) {
			page = pages - 1;
		}
		if (page < 0) {
			page = 0;
		}
		if (selectedSnapshot != null && tab == 0) {
			int owned = ownedCount(selectedOracle, selectedPrinting, selectedFinish);
			if (owned <= 0) {
				selectedSnapshot = null;
			} else {
				dustAmount = Mth.clamp(dustAmount, 1, owned);
			}
		}
	}

	private boolean acceptsOwned(BinderCard card) {
		if (favOnly && !isFav(card)) {
			return false;
		}
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

	private int perPage() {
		return Math.max(1, layout.cols * ROWS);
	}

	private int pageCount() {
		return Math.max(1, (visible.size() + perPage() - 1) / perPage());
	}

	private BinderCard resolveSelectedCard() {
		if (selectedOracle.isEmpty()) {
			return null;
		}
		if (tab == 1 && !printings.isEmpty()) {
			PrintingOption opt = activePrintingOption();
			if (opt != null) {
				int owned = ownedCount(opt.oracleId(), opt.printingId(), selectedFinish);
				return new BinderCard(
					opt.oracleId(),
					opt.printingId(),
					selectedFinish,
					owned,
					opt.name(),
					opt.manaCost(),
					opt.manaValue(),
					opt.typeLine(),
					opt.colorIdentity(),
					opt.rarity(),
					opt.setCode(),
					opt.treatment()
				);
			}
		}
		for (BinderCard card : all) {
			if (card.oracleId().equals(selectedOracle)
				&& card.printingId().equals(selectedPrinting)
				&& card.finish().equals(selectedFinish)) {
				return card;
			}
		}
		for (BinderCard card : visible) {
			if (card.oracleId().equals(selectedOracle) && card.printingId().equals(selectedPrinting)) {
				return card;
			}
		}
		return selectedSnapshot;
	}

	private PrintingOption activePrintingOption() {
		for (PrintingOption opt : printings) {
			if (opt.printingId().equals(selectedPrinting)) {
				return opt;
			}
		}
		return printings.isEmpty() ? null : printings.getFirst();
	}

	private void selectCard(BinderCard card) {
		if (card == null) {
			return;
		}
		boolean oracleChanged = !card.oracleId().equals(selectedOracle);
		selectedOracle = card.oracleId();
		selectedPrinting = card.printingId();
		selectedFinish = card.finish().isEmpty() ? Finish.NONFOIL.name() : card.finish();
		selectedSnapshot = card;
		showBackFace = false;
		confirmDustAll = false;
		dustAmount = Math.max(1, Math.min(dustAmount, Math.max(1, card.count())));
		if (oracleChanged || !card.oracleId().equals(printingsOracle)) {
			requestPrintings(card.oracleId());
		}
	}

	private List<PrintingOption> filteredPrintings() {
		if (printingFilter == 0 || printings.isEmpty()) {
			return printings;
		}
		List<PrintingOption> out = new ArrayList<>();
		for (PrintingOption opt : printings) {
			if (matchesPrintingFilter(opt)) {
				out.add(opt);
			}
		}
		return out;
	}

	private boolean matchesPrintingFilter(PrintingOption opt) {
		String t = opt.treatment() == null ? "normal" : opt.treatment();
		return switch (printingFilter) {
			case 1 -> !"normal".equals(t);
			case 2 -> "retro".equals(t);
			case 3 -> "borderless".equals(t);
			case 4 -> "special_guest".equals(t);
			default -> true;
		};
	}

	private void selectPrintingOption(PrintingOption opt, int idx) {
		selectedPrinting = opt.printingId();
		showBackFace = false;
		selectedSnapshot = new BinderCard(
			opt.oracleId(),
			opt.printingId(),
			selectedFinish,
			ownedCount(opt.oracleId(), opt.printingId(), selectedFinish),
			opt.name(),
			opt.manaCost(),
			opt.manaValue(),
			opt.typeLine(),
			opt.colorIdentity(),
			opt.rarity(),
			opt.setCode(),
			opt.treatment()
		);
		ensurePrintingVisible(idx);
	}

	private void ensurePrintingVisible(int idx) {
		int rows = Math.max(1, layout.printListRows);
		int count = filteredPrintings().size();
		if (idx < printingScroll) {
			printingScroll = idx;
		} else if (idx >= printingScroll + rows) {
			printingScroll = idx - rows + 1;
		}
		printingScroll = Mth.clamp(printingScroll, 0, Math.max(0, count - rows));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		layout = Layout.compute(this.width, this.height, this.font.width("All Cards"));
		if (search != null) {
			search.setX(layout.searchX);
			search.setY(layout.searchY);
			int sw = tab == 1
				? (craftOwnedOnly ? layout.searchW_OwnedCraft : layout.searchW)
				: (layout.leftX + layout.leftW - 12 - layout.searchX);
			search.setWidth(Math.max(70, sw));
			search.setHint(Component.literal(tab == 1 && !craftOwnedOnly ? "Search (press Enter) or t:type" : "Search collection"));
		}

		prepareArt();

		graphics.fill(0, 0, this.width, this.height, 0xC0100C0A);

		// Left Pane (Card Grid)
		graphics.fill(layout.leftX, layout.leftY, layout.leftX + layout.leftW, layout.leftY + layout.leftH, 0xFF4A3424);
		graphics.fill(layout.leftX, layout.leftY, layout.leftX + 8, layout.leftY + layout.leftH, 0xFF2C1E14);
		graphics.fill(layout.pageX, layout.pageY, layout.pageX + layout.pageW, layout.pageY + layout.pageH, 0xFF1B1714);

		// Right Pane (Workshop / Inspector)
		graphics.fill(layout.rightX, layout.rightY, layout.rightX + layout.rightW, layout.rightY + layout.rightH, 0xFF4A3424);
		graphics.fill(layout.rightX + layout.rightW - 8, layout.rightY, layout.rightX + layout.rightW, layout.rightY + layout.rightH, 0xFF2C1E14);
		graphics.fill(layout.inspX, layout.inspY, layout.inspX + layout.inspW, layout.inspY + layout.inspH, 0xFF1B1714);

		// Left header
		graphics.drawString(this.font, this.title, layout.leftX + 14, layout.leftY + 6, GOLD, false);
		int tabLeftBound = dustEnabled ? layout.tabDisX : layout.tabCraftX;
		graphics.drawString(this.font, fit(subtitle(), tabLeftBound - layout.leftX - 20), layout.leftX + 14, layout.leftY + 18, MUTED, false);

		if (dustEnabled) {
			int disX = craftEnabled ? layout.tabDisX : (layout.tabCraftX + layout.tabCraftW - layout.tabDisW);
			drawTabButton(graphics, disX, layout.tabY, layout.tabDisW, 16, "Disenchant", tab == 0, mouseX, mouseY);
		}
		if (craftEnabled) {
			drawTabButton(graphics, layout.tabCraftX, layout.tabY, layout.tabCraftW, 16, "Craft", tab == 1, mouseX, mouseY);
		}

		if (tab == 1) {
			if (!craftOwnedOnly) {
				drawButton(graphics, layout.goBtnX, layout.searchY, layout.goBtnW, 14, "Go", false, mouseX, mouseY);
			}
			String scopeLabel = economy.craft().ownedOnly() ? "Owned Only" : (craftOwnedOnly ? "Owned" : "All Cards");
			if (economy.craft().ownedOnly()) {
				drawButton(graphics, layout.scopeBtnX, layout.searchY, layout.scopeBtnW, 14, scopeLabel, false, mouseX, mouseY, false);
			} else {
				drawTabButton(graphics, layout.scopeBtnX, layout.searchY, layout.scopeBtnW, 14, scopeLabel, !craftOwnedOnly, mouseX, mouseY);
			}
		}

		drawFilters(graphics, mouseX, mouseY);
		drawArrowsAndFooter(graphics);
		drawGrid(graphics, mouseX, mouseY);
		drawInspector(graphics, mouseX, mouseY);

		super.render(graphics, mouseX, mouseY, partialTick);
		drawHover(graphics, mouseX, mouseY);
	}

	private String subtitle() {
		if (tab == 1 && !craftOwnedOnly) {
			if (visible.size() < catalogTotalMatches) {
				return "Showing " + visible.size() + " of " + catalogTotalMatches + " matches";
			}
			if (catalogTotalMatches > 0) {
				return catalogTotalMatches + " matches" + (indexSize > 0 ? " · " + indexSize + " indexed" : "");
			}
			return indexSize > 0 ? indexSize + " cards indexed" : "Searching catalog...";
		}
		int copies = 0;
		for (BinderCard c : all) {
			copies += c.count();
		}
		String line = all.size() + " unique · " + copies + " copies";
		if (visible.size() != all.size()) {
			line += " · " + visible.size() + " shown";
		}
		return line;
	}

	private void prepareArt() {
		CardArt.beginFrame();
		pinPage(page);
		if (page > 0) {
			pinPage(page - 1);
		}
		if (page + 1 < pageCount()) {
			pinPage(page + 1);
		}
		preloadPage(page);
		if (page + 1 < pageCount()) {
			preloadPage(page + 1);
		}
		BinderCard sel = resolveSelectedCard();
		if (sel != null) {
			CardArt.pin(sel.printingId());
			CardArt.preload(sel.printingId());
		}
		if (tab == 1) {
			List<PrintingOption> filtered = filteredPrintings();
			int max = Math.min(filtered.size(), printingScroll + layout.printListRows + 2);
			for (int i = printingScroll; i < max; i++) {
				CardArt.preload(filtered.get(i).printingId());
			}
		}
	}

	private void pinPage(int pageIndex) {
		int per = perPage();
		int start = pageIndex * per;
		for (int i = 0; i < per && start + i < visible.size(); i++) {
			CardArt.pin(visible.get(start + i).printingId());
		}
	}

	private void preloadPage(int pageIndex) {
		int per = perPage();
		int start = pageIndex * per;
		for (int i = 0; i < per && start + i < visible.size(); i++) {
			CardArt.preload(visible.get(start + i).printingId());
		}
	}

	private void drawFilters(GuiGraphics graphics, int mouseX, int mouseY) {
		for (int i = 0; i < COLOR_KEYS.length; i++) {
			drawChip(graphics, layout.colorX[i], layout.chipsY, COLOR_KEYS[i], COLOR_FILL[i], COLOR_INK[i], colorOn[i]);
		}
		// Fav toggle chip
		boolean favHot = inside(mouseX, mouseY, layout.favX, layout.chipsY, layout.favW, 14);
		graphics.fill(layout.favX - 1, layout.chipsY - 1, layout.favX + layout.favW + 1, layout.chipsY + 15, favOnly ? GOLD_BRIGHT : 0xFF2A241E);
		graphics.fill(layout.favX, layout.chipsY, layout.favX + layout.favW, layout.chipsY + 14, favOnly ? 0xFF4A301A : (favHot ? 0xFF3A281D : 0xFF241A14));
		graphics.drawString(this.font, "* Fav", layout.favX + 5, layout.chipsY + 3, favOnly ? GOLD_BRIGHT : MUTED, false);

		for (int i = 0; i < RARITY_KEYS.length; i++) {
			drawChip(graphics, layout.rarityX[i], layout.chipsY, RARITY_KEYS[i], rarityColor(RARITY_IDS[i]), 0xFF1C1C1C, rarityOn[i]);
		}
	}

	private void drawChip(GuiGraphics graphics, int x, int y, String letter, int fill, int ink, boolean on) {
		graphics.fill(x - 1, y - 1, x + 15, y + 15, on ? GOLD_BRIGHT : 0xFF2A241E);
		graphics.fill(x, y, x + 14, y + 14, fill);
		graphics.drawString(this.font, letter, x + 4, y + 3, ink, false);
	}

	private void drawArrowsAndFooter(GuiGraphics graphics) {
		int pages = pageCount();
		drawArrow(graphics, layout.prevX, layout.arrowY, true, page > 0);
		drawArrow(graphics, layout.nextX, layout.arrowY, false, page + 1 < pages);

		int footerY = layout.leftY + layout.leftH - 12;
		String pageLabel = (page + 1) + " / " + pages;
		int pageW = this.font.width(pageLabel);
		graphics.drawString(this.font, pageLabel, layout.leftX + layout.leftW - 14 - pageW, footerY, GOLD, false);
		String hint = tab == 1 && !craftOwnedOnly
			? "Click a card to choose a printing · R-click fav · F filter"
			: "Click card · right-click to favourite · F fav filter";
		graphics.drawString(this.font, fit(hint, layout.leftW - pageW - 34), layout.leftX + 14, footerY, MUTED, false);
	}

	private void drawArrow(GuiGraphics graphics, int x, int y, boolean left, boolean enabled) {
		int color = enabled ? GOLD : 0xFF6A5A48;
		graphics.fill(x, y, x + 14, y + 14, 0xFF2C1E14);
		graphics.drawString(this.font, left ? "<" : ">", x + 4, y + 3, color, false);
	}

	private void drawGrid(GuiGraphics graphics, int mouseX, int mouseY) {
		int per = perPage();
		int start = page * per;
		for (int slot = 0; slot < per; slot++) {
			int col = slot % layout.cols;
			int row = slot / layout.cols;
			int x = layout.gridX + col * (layout.cardW + layout.gap);
			int y = layout.gridY + row * (layout.cardH + layout.gap);
			int idx = start + slot;
			BinderCard card = idx < visible.size() ? visible.get(idx) : null;
			boolean hot = card != null && inside(mouseX, mouseY, x, y, layout.cardW, layout.cardH);
			drawSleeve(graphics, card, x, y, layout.cardW, layout.cardH, hot);
		}
		if (visible.isEmpty()) {
			String empty = (tab == 1 && !craftOwnedOnly)
				? (query.isBlank() ? "Type a card name & press Enter..." : "Press Enter to search, or try another query.")
				: (all.isEmpty() ? "Your collection is empty." : "No cards match current filters.");
			graphics.drawCenteredString(this.font, empty, layout.pageX + layout.pageW / 2, layout.pageY + layout.pageH / 2 - 4, SUB_MUTED);
		}
	}

	private void drawSleeve(GuiGraphics graphics, BinderCard card, int x, int y, int w, int h, boolean hovered) {
		if (card == null) {
			graphics.fill(x, y, x + w, y + h, 0xFF3A322C);
			graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF120F0D);
			return;
		}
		boolean selected;
		if (tab == 1 && !craftOwnedOnly) {
			selected = card.oracleId().equals(selectedOracle);
		} else {
			selected = card.oracleId().equals(selectedOracle)
				&& card.printingId().equals(selectedPrinting)
				&& card.finish().equals(selectedFinish);
		}
		int rim = selected ? GOLD_BRIGHT : (hovered ? 0xFF8A7560 : rarityColor(card.rarity()));
		graphics.fill(x, y, x + w, y + h, rim);
		if (!CardArt.draw(graphics, card.printingId(), card.finish(), x + 1, y + 1, w - 2, h - 2)) {
			graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF1A1714);
			graphics.fill(x + 3, y + 3, x + 6, y + h - 3, rarityColor(card.rarity()));
			int textW = w - 12;
			String[] lines = wrap(card.name(), textW);
			graphics.drawString(this.font, lines[0], x + 9, y + 5, PAPER, false);
			int manaY = y + 15;
			if (!lines[1].isEmpty()) {
				graphics.drawString(this.font, lines[1], x + 9, y + 15, PAPER, false);
				manaY = y + 25;
			}
			if (!card.manaCost().isEmpty() && manaY < y + h - 14) {
				graphics.drawString(this.font, fit(card.manaCost(), textW), x + 9, manaY, 0xFFB7C4CE, false);
			}
		}

		// Top-left favourite star
		boolean fav = (tab == 1 && !craftOwnedOnly) ? favOracles.contains(card.oracleId()) : isFav(card);
		if (fav) {
			graphics.fill(x + 2, y + 2, x + 11, y + 12, 0xCC000000);
			graphics.drawString(this.font, "*", x + 4, y + 3, GOLD_BRIGHT, false);
		}

		// Bottom-left owned badge
		if (card.count() > 0) {
			String count = "x" + card.count();
			int cw = this.font.width(count);
			graphics.fill(x + 2, y + h - 13, x + cw + 6, y + h - 2, 0xCC000000);
			graphics.drawString(this.font, count, x + 4, y + h - 12, 0xFFFFFFFF, false);
		}

		// Bottom-right dust value badge
		boolean craftBlocked = tab == 1 && isCardCraftBlocked(card);
		if (craftBlocked) {
			String badge = "Disabled";
			int bw = this.font.width(badge);
			graphics.fill(x + w - bw - 6, y + h - 13, x + w - 2, y + h - 2, 0xCC000000);
			graphics.drawString(this.font, badge, x + w - bw - 4, y + h - 12, 0xFFFF8A7A, false);
		} else {
			int value = tab == 0
				? DustValues.disenchant(card, economy)
				: DustValues.craft(card.oracleId(), card.name(), card.printingId(), card.setCode(), card.rarity(), card.treatment(), card.finish(), economy);
			String badge = tab == 0 ? ("+" + value) : ("" + value);
			int bw = this.font.width(badge);
			graphics.fill(x + w - bw - 6, y + h - 13, x + w - 2, y + h - 2, 0xCC000000);
			graphics.drawString(this.font, badge, x + w - bw - 4, y + h - 12, tab == 0 ? 0xFFA6E3A1 : GOLD_BRIGHT, false);
		}
	}

	private void drawInspector(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.drawString(this.font, "Workshop", layout.rightX + 10, layout.rightY + 7, GOLD, false);

		// Dust Balance pill box on top right
		String dustText = "Dust: " + dust;
		int dustW = this.font.width(dustText) + 12;
		int dustX = layout.rightX + layout.rightW - 14 - dustW;
		int dustY = layout.rightY + 5;
		graphics.fill(dustX, dustY, dustX + dustW, dustY + 14, 0xFF2C1E14);
		graphics.fill(dustX, dustY, dustX + dustW, dustY + 1, GOLD_BRIGHT);
		graphics.drawString(this.font, dustText, dustX + 6, dustY + 3, GOLD_BRIGHT, false);

		BinderCard sel = resolveSelectedCard();
		int x = layout.inspX + 8;
		int y = layout.inspY + 8;
		int w = layout.inspW - 16;

		if (sel != null) {
			int prevW = layout.previewW;
			int prevH = layout.previewH;
			boolean hasBack = CardArt.peekBack(sel.printingId()) != null;
			boolean hotPreview = inside(mouseX, mouseY, x, y, prevW, prevH);
			int rim = hotPreview ? GOLD_BRIGHT : rarityColor(sel.rarity());
			graphics.fill(x, y, x + prevW, y + prevH, rim);
			if (!CardArt.draw(graphics, sel.printingId(), sel.finish(), x + 1, y + 1, prevW - 2, prevH - 2, showBackFace)) {
				graphics.fill(x + 1, y + 1, x + prevW - 1, y + prevH - 1, 0xFF14110F);
				graphics.drawString(this.font, fit(sel.name(), prevW - 8), x + 4, y + 6, PAPER, false);
			}
			if (hasBack) {
				String flip = showBackFace ? "Front" : "Flip";
				int fw = this.font.width(flip) + 4;
				graphics.fill(x + prevW - fw - 2, y + prevH - 11, x + prevW - 2, y + prevH - 2, 0xCC000000);
				graphics.drawString(this.font, flip, x + prevW - fw, y + prevH - 10, GOLD_BRIGHT, false);
			}

			int metaX = x + prevW + 8;
			int metaW = Math.max(40, w - prevW - 8);
			String[] nameLines = wrap(sel.name(), metaW);
			graphics.drawString(this.font, nameLines[0], metaX, y, PAPER, false);
			int cy = y + 11;
			if (!nameLines[1].isEmpty()) {
				graphics.drawString(this.font, nameLines[1], metaX, cy, PAPER, false);
				cy += 11;
			}
			if (!sel.manaCost().isEmpty()) {
				graphics.drawString(this.font, fit(sel.manaCost(), metaW), metaX, cy, 0xFFB7C4CE, false);
				cy += 11;
			}
			if (!sel.typeLine().isEmpty()) {
				graphics.drawString(this.font, fit(sel.typeLine(), metaW), metaX, cy, MUTED, false);
				cy += 11;
			}
			String setMeta = sel.setCode().isEmpty() ? rarityLabel(sel.rarity())
				: sel.setCode().toUpperCase(Locale.ROOT) + " · " + rarityLabel(sel.rarity());
			PrintingOption activeOpt = activePrintingOption();
			if (tab == 1 && activeOpt != null && !activeOpt.collectorNumber().isEmpty()) {
				setMeta = activeOpt.setCode().toUpperCase(Locale.ROOT) + " #" + activeOpt.collectorNumber() + " · " + rarityLabel(sel.rarity());
			}
			graphics.drawString(this.font, fit(setMeta, metaW), metaX, cy, rarityColor(sel.rarity()), false);
			cy += 11;

			String treat = CardArt.caption(sel.treatment(), sel.finish());
			if (!treat.isEmpty()) {
				graphics.drawString(this.font, fit(treat, metaW), metaX, cy, GOLD, false);
				cy += 11;
			}
			int owned = ownedCount(sel.oracleId(), sel.printingId(), sel.finish());
			graphics.drawString(this.font, "Owned: " + owned, metaX, cy, SUB_MUTED, false);

			boolean isCardFav = isFav(sel);
			int favBtnY = Math.max(y + prevH - 14, cy + 10);
			if (favBtnY + 14 <= y + prevH + 10) {
				drawButton(graphics, metaX, favBtnY, Math.min(86, metaW), 14, isCardFav ? "* Favourited" : "Favourite", isCardFav, mouseX, mouseY);
			}
		} else {
			String prompt = tab == 0
				? "Click any card on the left to inspect or disenchant."
				: "Click any card on the left to browse printings and craft.";
			String[] lines = wrap(prompt, w);
			graphics.drawString(this.font, lines[0], x, y + 4, SUB_MUTED, false);
			if (!lines[1].isEmpty()) {
				graphics.drawString(this.font, lines[1], x, y + 15, SUB_MUTED, false);
			}
		}

		if (tab == 0) {
			drawDisenchantWorkshop(graphics, sel, mouseX, mouseY);
		} else {
			drawCraftWorkshop(graphics, sel, mouseX, mouseY);
		}

		// Bottom status inside right pane
		if (!status.isEmpty()) {
			graphics.drawString(
				this.font,
				fit(status, layout.rightW - 26),
				layout.rightX + 10,
				layout.rightY + layout.rightH - 12,
				GOLD,
				false
			);
		}
	}

	private void drawDisenchantWorkshop(GuiGraphics graphics, BinderCard sel, int mouseX, int mouseY) {
		int x = layout.inspX + 8;
		int w = layout.inspW - 16;
		int y = layout.inspY + layout.previewH + 24;

		// Single/stack disenchant block
		graphics.fill(x, y, x + w, y + 1, 0xFF3A2C22);
		y += 6;
		if (sel != null && sel.count() > 0) {
			int owned = sel.count();
			dustAmount = Mth.clamp(dustAmount, 1, owned);
			int per = DustValues.disenchant(sel, economy);
			int total = (int) Math.min(Integer.MAX_VALUE, (long) per * dustAmount);
			graphics.drawString(this.font, "Disenchant Copies (+" + per + " each)", x, y, GOLD, false);
			y += 13;

			drawButton(graphics, x, y, 20, 16, "-", false, mouseX, mouseY);
			String qty = dustAmount + " / " + owned;
			graphics.drawCenteredString(this.font, qty, x + 52, y + 4, PAPER);
			drawButton(graphics, x + 84, y, 20, 16, "+", false, mouseX, mouseY);
			drawButton(graphics, x + 108, y, 36, 16, "Max", dustAmount == owned, mouseX, mouseY);
			y += 20;

			drawActionButton(graphics, x, y, w, 18, "Disenchant " + dustAmount + "× (+" + total + " dust)", true, mouseX, mouseY);
		} else {
			graphics.drawString(this.font, "Select an owned card above", x, y + 4, SUB_MUTED, false);
			graphics.drawString(this.font, "to disenchant single or stack copies.", x, y + 16, SUB_MUTED, false);
		}

		// Bottom Mass Disenchant block
		int massY = layout.inspY + layout.inspH - 64;
		graphics.fill(x, massY - 6, x + w, massY - 5, 0xFF3A2C22);

		if (!economy.dust().allowDustAll()) {
			graphics.drawString(this.font, "Mass Disenchant", x, massY, SUB_MUTED, false);
			graphics.drawString(this.font, fit("Disabled by server rules.", w), x, massY + 14, SUB_MUTED, false);
			return;
		}

		MassDustSummary summary = computeMassDust();
		graphics.drawString(this.font, "Mass Disenchant", x, massY, GOLD, false);
		graphics.drawString(
			this.font,
			fit(summary.copies + " non-fav · +" + summary.dust + " dust", w),
			x,
			massY + 12,
			summary.copies > 0 ? PAPER : SUB_MUTED,
			false
		);
		graphics.drawString(
			this.font,
			fit("Protected *: " + summary.favCopies + " favourited", w),
			x,
			massY + 23,
			MUTED,
			false
		);

		int btnY = massY + 37;
		if (confirmDustAll && summary.copies > 0) {
			int half = (w - 6) / 2;
			drawDangerButton(graphics, x, btnY, half, 18, "Confirm +" + summary.dust, mouseX, mouseY);
			drawButton(graphics, x + half + 6, btnY, w - half - 6, 18, "Cancel", false, mouseX, mouseY);
		} else {
			drawActionButton(
				graphics,
				x,
				btnY,
				w,
				18,
				summary.copies > 0 ? "Dust All Non-Favs (+" + summary.dust + ")" : "No Non-Fav Cards",
				summary.copies > 0,
				mouseX,
				mouseY
			);
		}
	}

	private void drawCraftWorkshop(GuiGraphics graphics, BinderCard sel, int mouseX, int mouseY) {
		int x = layout.inspX + 8;
		int w = layout.inspW - 16;
		int y = layout.inspY + layout.previewH + 24;

		graphics.fill(x, y, x + w, y + 1, 0xFF3A2C22);
		y += 4;

		if (sel == null) {
			graphics.drawString(this.font, "Choose any card on the left", x, y + 4, SUB_MUTED, false);
			graphics.drawString(this.font, "to preview printings & craft.", x, y + 16, SUB_MUTED, false);
			return;
		}

		List<PrintingOption> filtered = filteredPrintings();

		// Printings Header
		String countTxt = printingFilter == 0
			? String.valueOf(printings.size())
			: (filtered.size() + "/" + printings.size());
		String pTitle = printingsLoading
			? "Printings (loading...)"
			: "Printings (" + countTxt + ")";
		graphics.drawString(this.font, fit(pTitle, w - 34), x, y + 2, GOLD, false);

		int rows = Math.max(1, layout.printListRows);
		printingScroll = Mth.clamp(printingScroll, 0, Math.max(0, filtered.size() - rows));
		boolean canUp = printingScroll > 0;
		boolean canDown = printingScroll + rows < filtered.size();
		drawButton(graphics, x + w - 30, y, 14, 12, "^", false, mouseX, mouseY, canUp);
		drawButton(graphics, x + w - 14, y, 14, 12, "v", false, mouseX, mouseY, canDown);
		y += 14;

		// Printing treatment filter chips
		int chipGap = 2;
		int chipW = (w - chipGap * (PRINT_FILTER_LABELS.length - 1)) / PRINT_FILTER_LABELS.length;
		for (int i = 0; i < PRINT_FILTER_LABELS.length; i++) {
			int cx = x + i * (chipW + chipGap);
			int cw = i == PRINT_FILTER_LABELS.length - 1 ? (x + w - cx) : chipW;
			drawButton(graphics, cx, y, cw, 13, PRINT_FILTER_LABELS[i], printingFilter == i, mouseX, mouseY);
		}
		y += 16;

		// Printings List
		int listH = rows * 15;
		graphics.fill(x, y, x + w, y + listH, 0xFF14110F);
		for (int i = 0; i < rows; i++) {
			int idx = printingScroll + i;
			int ry = y + i * 15;
			if (idx >= filtered.size()) {
				break;
			}
			PrintingOption opt = filtered.get(idx);
			boolean active = opt.printingId().equals(selectedPrinting);
			boolean hot = inside(mouseX, mouseY, x, ry, w, 14);
			if (active) {
				graphics.fill(x, ry, x + w, ry + 14, 0xFF4A301A);
				graphics.fill(x, ry, x + 2, ry + 14, GOLD_BRIGHT);
			} else if (hot) {
				graphics.fill(x, ry, x + w, ry + 14, 0xFF2B211A);
			}
			String setPart = opt.setCode().toUpperCase(Locale.ROOT);
			if (!opt.collectorNumber().isEmpty()) {
				setPart += " #" + opt.collectorNumber();
			}
			String treat = CardArt.treatmentLabel(opt.treatment());
			boolean optAllowed = economy.craft().isTreatmentAllowed(opt.treatment())
				&& !restrictions.isCraftBlocked(opt.oracleId(), opt.name(), opt.setCode(), opt.rarity(), opt.typeLine());
			String rowText = setPart + (treat.isEmpty() ? "" : " · " + treat) + " · " + rarityLabel(opt.rarity())
				+ (optAllowed ? "" : " (Blocked)");
			int rowColor = !optAllowed ? SUB_MUTED : (active ? GOLD_BRIGHT : PAPER);
			graphics.drawString(this.font, fit(rowText, w - 8), x + 5, ry + 3, rowColor, false);
		}
		if (filtered.isEmpty()) {
			String msg = printings.isEmpty()
				? (printingsLoading ? "Fetching Scryfall printings..." : "Default printing")
				: "No matching printings (click All)";
			graphics.drawString(this.font, fit(msg, w - 8), x + 5, y + 4, SUB_MUTED, false);
		}

		// Finish Selector + Quantity + Craft Button anchored to bottom of inspector
		PrintingOption activeOpt = activePrintingOption();
		String activeRarity = activeOpt != null ? activeOpt.rarity() : sel.rarity();
		String activeSetCode = activeOpt != null ? activeOpt.setCode() : sel.setCode();
		String activeTreatment = activeOpt != null ? activeOpt.treatment() : sel.treatment();
		String activePrintId = activeOpt != null ? activeOpt.printingId() : sel.printingId();
		Finish currentFinish = Finish.fromName(selectedFinish);
		if (!economy.craft().isFinishAllowed(currentFinish)) {
			currentFinish = Finish.NONFOIL;
			selectedFinish = Finish.NONFOIL.name();
		}
		int finishY = layout.inspY + layout.inspH - 58;
		graphics.drawString(this.font, "Finish", x, finishY - 10, GOLD, false);
		int fw = (w - 8) / 3;
		Finish[] finishes = {Finish.NONFOIL, Finish.FOIL, Finish.ETCHED};
		for (int i = 0; i < finishes.length; i++) {
			Finish f = finishes[i];
			boolean allowed = economy.craft().isFinishAllowed(f);
			boolean on = allowed && currentFinish == f;
			int fx = x + i * (fw + 4);
			drawButton(graphics, fx, finishY, i == 2 ? (w - (fw + 4) * 2) : fw, 14, f.label(), on, mouseX, mouseY, allowed);
		}

		// Quantity + Craft button
		int qtyY = finishY + 18;
		int maxBatch = economy.craft().maxBatch();
		craftAmount = Mth.clamp(craftAmount, 1, maxBatch);
		int unitCost = DustValues.craft(sel.oracleId(), sel.name(), activePrintId, activeSetCode, activeRarity, activeTreatment, currentFinish.name(), economy);
		int totalCost = (int) Math.min(Integer.MAX_VALUE, (long) unitCost * craftAmount);
		drawButton(graphics, x, qtyY, 20, 15, "-", false, mouseX, mouseY, craftAmount > 1);
		graphics.drawCenteredString(this.font, craftAmount + "×", x + 36, qtyY + 4, PAPER);
		drawButton(graphics, x + 52, qtyY, 20, 15, "+", false, mouseX, mouseY, craftAmount < maxBatch);
		String eachTxt = "(" + unitCost + "/ea)";
		graphics.drawString(this.font, fit(eachTxt, Math.max(20, w - 78)), x + 78, qtyY + 4, MUTED, false);

		int craftBtnY = qtyY + 19;
		boolean cardBlocked = isSelectedCraftBlocked(sel, activeOpt);
		boolean treatmentAllowed = economy.craft().isTreatmentAllowed(activeTreatment);
		boolean finishAllowed = economy.craft().isFinishAllowed(currentFinish);
		boolean canAfford = dust >= totalCost;
		boolean canCraft = !cardBlocked && treatmentAllowed && finishAllowed && canAfford;
		String craftLabel;
		if (cardBlocked) {
			craftLabel = "Crafting Disabled";
		} else if (!treatmentAllowed) {
			craftLabel = "Treatment Disabled";
		} else if (!finishAllowed) {
			craftLabel = currentFinish.label() + " Disabled";
		} else if (!canAfford) {
			craftLabel = "Need " + totalCost + " dust";
		} else {
			craftLabel = "Craft " + craftAmount + "× (−" + totalCost + " dust)";
		}
		drawActionButton(graphics, x, craftBtnY, w, 18, craftLabel, canCraft, mouseX, mouseY);
	}

	private void drawTabButton(GuiGraphics graphics, int x, int y, int w, int h, String label, boolean on, int mx, int my) {
		boolean hover = inside(mx, my, x, y, w, h);
		int fill = on ? 0xFF4A301A : (hover ? 0xFF3E2B1E : 0xFF2C1E14);
		graphics.fill(x, y, x + w, y + h, fill);
		if (on) {
			graphics.fill(x, y, x + w, y + 1, GOLD_BRIGHT);
			graphics.fill(x, y + h - 1, x + w, y + h, GOLD_BRIGHT);
		}
		graphics.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, on ? GOLD_BRIGHT : MUTED);
	}

	private void drawButton(GuiGraphics graphics, int x, int y, int w, int h, String label, boolean active, int mx, int my) {
		drawButton(graphics, x, y, w, h, label, active, mx, my, true);
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
		if (inside(mouseX, mouseY, layout.favX, layout.chipsY, layout.favW, 14)) {
			graphics.renderTooltip(this.font, Component.literal("Favourites only (F)"), mouseX, mouseY);
			return;
		}
		if (tab == 1 && !craftOwnedOnly && inside(mouseX, mouseY, layout.goBtnX, layout.searchY, layout.goBtnW, 14)) {
			graphics.renderTooltip(this.font, Component.literal("Search catalog (Enter)"), mouseX, mouseY);
			return;
		}
		if (tab == 1 && resolveSelectedCard() != null) {
			int ix = layout.inspX + 8;
			int iw = layout.inspW - 16;
			int fy = layout.inspY + layout.previewH + 24 + 4 + 14;
			int chipGap = 2;
			int chipW = (iw - chipGap * (PRINT_FILTER_LABELS.length - 1)) / PRINT_FILTER_LABELS.length;
			for (int i = 0; i < PRINT_FILTER_LABELS.length; i++) {
				int cx = ix + i * (chipW + chipGap);
				int cw = i == PRINT_FILTER_LABELS.length - 1 ? (ix + iw - cx) : chipW;
				if (inside(mouseX, mouseY, cx, fy, cw, 13)) {
					graphics.renderTooltip(this.font, Component.literal(PRINT_FILTER_TIPS[i]), mouseX, mouseY);
					return;
				}
			}
			int finishY = layout.inspY + layout.inspH - 58;
			int fw = (iw - 8) / 3;
			Finish[] finishes = {Finish.NONFOIL, Finish.FOIL, Finish.ETCHED};
			for (int i = 0; i < finishes.length; i++) {
				int fx = ix + i * (fw + 4);
				int btnW = i == 2 ? (iw - (fw + 4) * 2) : fw;
				if (inside(mouseX, mouseY, fx, finishY, btnW, 14)) {
					if (!economy.craft().isFinishAllowed(finishes[i])) {
						graphics.renderTooltip(this.font, Component.literal(finishes[i].label() + " · Disabled by server rules"), mouseX, mouseY);
					} else {
						double fMul = economy.craft().finishMultiplier(finishes[i].name());
						String mul = switch (finishes[i]) {
							case NONFOIL -> formatMultiplier(fMul) + "× dust cost";
							case FOIL -> formatMultiplier(fMul) + "× dust cost · Foil shimmer";
							case ETCHED -> formatMultiplier(fMul) + "× dust cost · Etched sheen";
						};
						graphics.renderTooltip(this.font, Component.literal(finishes[i].label() + " · " + mul), mouseX, mouseY);
					}
					return;
				}
			}
		}
		int slot = slotAt(mouseX, mouseY);
		if (slot < 0) {
			return;
		}
		int idx = page * perPage() + slot;
		if (idx >= visible.size()) {
			return;
		}
		BinderCard card = visible.get(idx);
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
		if (card.count() > 0) {
			meta += " · x" + card.count();
		}
		lines.add(Component.literal(meta));
		if (tab == 1 && isCardCraftBlocked(card)) {
			lines.add(Component.literal("Crafting disabled by server rules"));
		} else {
			int value = tab == 0
				? DustValues.disenchant(card, economy)
				: DustValues.craft(card.oracleId(), card.name(), card.printingId(), card.setCode(), card.rarity(), card.treatment(), card.finish(), economy);
			lines.add(Component.literal(tab == 0 ? ("Disenchant: +" + value + " dust") : ("Craft: " + value + " dust")));
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

		if (button == 0) {
			// Mode tabs
			int disX = craftEnabled ? layout.tabDisX : (layout.tabCraftX + layout.tabCraftW - layout.tabDisW);
			if (dustEnabled && inside(mouseX, mouseY, disX, layout.tabY, layout.tabDisW, 16) && tab != 0) {
				tab = 0;
				page = 0;
				confirmDustAll = false;
				rebuild();
				return true;
			}
			if (craftEnabled && inside(mouseX, mouseY, layout.tabCraftX, layout.tabY, layout.tabCraftW, 16) && tab != 1) {
				tab = 1;
				page = 0;
				confirmDustAll = false;
				if (!craftOwnedOnly) {
					if (catalogResults.isEmpty()) {
						sendSearchNow();
					} else {
						rebuild();
					}
				} else {
					rebuild();
				}
				return true;
			}
			// Search Go button in Craft All Cards mode
			if (tab == 1 && !craftOwnedOnly && inside(mouseX, mouseY, layout.goBtnX, layout.searchY, layout.goBtnW, 14)) {
				sendSearchNow();
				return true;
			}
			// Owned / All Cards toggle in Craft mode
			if (tab == 1 && !economy.craft().ownedOnly() && inside(mouseX, mouseY, layout.scopeBtnX, layout.searchY, layout.scopeBtnW, 14)) {
				craftOwnedOnly = !craftOwnedOnly;
				page = 0;
				if (!craftOwnedOnly) {
					if (catalogResults.isEmpty()) {
						sendSearchNow();
					} else {
						rebuild();
					}
				} else {
					rebuild();
				}
				return true;
			}

			// Filter chips
			for (int i = 0; i < COLOR_KEYS.length; i++) {
				if (inside(mouseX, mouseY, layout.colorX[i], layout.chipsY, 14, 14)) {
					colorOn[i] = !colorOn[i];
					page = 0;
					confirmDustAll = false;
					if (tab == 1 && !craftOwnedOnly) {
						sendSearchNow();
					} else {
						rebuild();
					}
					return true;
				}
			}
			for (int i = 0; i < RARITY_KEYS.length; i++) {
				if (inside(mouseX, mouseY, layout.rarityX[i], layout.chipsY, 14, 14)) {
					rarityOn[i] = !rarityOn[i];
					page = 0;
					confirmDustAll = false;
					if (tab == 1 && !craftOwnedOnly) {
						sendSearchNow();
					} else {
						rebuild();
					}
					return true;
				}
			}
			if (inside(mouseX, mouseY, layout.favX, layout.chipsY, layout.favW, 14)) {
				favOnly = !favOnly;
				page = 0;
				rebuild();
				return true;
			}

			// Page arrows
			if (inside(mouseX, mouseY, layout.prevX, layout.arrowY, 14, 14) && page > 0) {
				page--;
				return true;
			}
			if (inside(mouseX, mouseY, layout.nextX, layout.arrowY, 14, 14) && page + 1 < pageCount()) {
				page++;
				return true;
			}
		}

		// Sleeve grid click
		int slot = slotAt(mouseX, mouseY);
		if (slot >= 0) {
			int idx = page * perPage() + slot;
			if (idx < visible.size()) {
				BinderCard card = visible.get(idx);
				if (button == 1 || button == 2) {
					toggleFav(card);
					return true;
				}
				if (button == 0) {
					selectCard(card);
					return true;
				}
			}
		}

		if (button == 0 && handleInspectorClick(mouseX, mouseY)) {
			return true;
		}
		return true;
	}

	private boolean handleInspectorClick(double mouseX, double mouseY) {
		BinderCard sel = resolveSelectedCard();
		int x = layout.inspX + 8;
		int y = layout.inspY + 8;
		int w = layout.inspW - 16;

		if (sel != null) {
			// Preview flip
			if (inside(mouseX, mouseY, x, y, layout.previewW, layout.previewH) && CardArt.peekBack(sel.printingId()) != null) {
				showBackFace = !showBackFace;
				return true;
			}
			// Inspector Favourite toggle button
			int metaX = x + layout.previewW + 8;
			int metaW = Math.max(40, w - layout.previewW - 8);
			// Compute same cy to match favBtnY reliably
			int cy = y + 11;
			String[] nameLines = wrap(sel.name(), metaW);
			if (!nameLines[1].isEmpty()) {
				cy += 11;
			}
			if (!sel.manaCost().isEmpty()) {
				cy += 11;
			}
			if (!sel.typeLine().isEmpty()) {
				cy += 11;
			}
			cy += 11; // setMeta
			if (!CardArt.caption(sel.treatment(), sel.finish()).isEmpty()) {
				cy += 11;
			}
			int favBtnY = Math.max(y + layout.previewH - 14, cy + 10);
			if (favBtnY + 14 <= y + layout.previewH + 10 && inside(mouseX, mouseY, metaX, favBtnY, Math.min(86, metaW), 14)) {
				toggleFav(sel);
				return true;
			}
		}

		if (tab == 0) {
			return handleDisenchantClick(mouseX, mouseY, sel, x, w);
		}
		return handleCraftClick(mouseX, mouseY, sel, x, w);
	}

	private boolean handleDisenchantClick(double mouseX, double mouseY, BinderCard sel, int x, int w) {
		int y = layout.inspY + layout.previewH + 24 + 6;
		if (sel != null && sel.count() > 0) {
			int owned = sel.count();
			int qtyY = y + 13;
			if (inside(mouseX, mouseY, x, qtyY, 20, 16)) {
				dustAmount = Math.max(1, dustAmount - 1);
				return true;
			}
			if (inside(mouseX, mouseY, x + 84, qtyY, 20, 16)) {
				dustAmount = Math.min(owned, dustAmount + 1);
				return true;
			}
			if (inside(mouseX, mouseY, x + 108, qtyY, 36, 16)) {
				dustAmount = owned;
				return true;
			}
			int actY = qtyY + 20;
			if (inside(mouseX, mouseY, x, actY, w, 18)) {
				sendAction("dust", sel.oracleId(), sel.printingId(), sel.finish(), dustAmount);
				return true;
			}
		}

		if (!economy.dust().allowDustAll()) {
			return false;
		}
		int massY = layout.inspY + layout.inspH - 64;
		int btnY = massY + 37;
		MassDustSummary summary = computeMassDust();
		if (confirmDustAll && summary.copies > 0) {
			int half = (w - 6) / 2;
			if (inside(mouseX, mouseY, x, btnY, half, 18)) {
				confirmDustAll = false;
				sendAction("dust_all", "", "", "", 0);
				return true;
			}
			if (inside(mouseX, mouseY, x + half + 6, btnY, w - half - 6, 18)) {
				confirmDustAll = false;
				return true;
			}
		} else if (summary.copies > 0 && inside(mouseX, mouseY, x, btnY, w, 18)) {
			confirmDustAll = true;
			return true;
		}
		return false;
	}

	private boolean handleCraftClick(double mouseX, double mouseY, BinderCard sel, int x, int w) {
		if (sel == null) {
			return false;
		}
		int y = layout.inspY + layout.previewH + 24 + 4;
		int rows = Math.max(1, layout.printListRows);
		List<PrintingOption> filtered = filteredPrintings();

		// Printing list scroll buttons
		if (inside(mouseX, mouseY, x + w - 30, y, 14, 12) && printingScroll > 0) {
			printingScroll--;
			return true;
		}
		if (inside(mouseX, mouseY, x + w - 14, y, 14, 12) && printingScroll + rows < filtered.size()) {
			printingScroll++;
			return true;
		}
		y += 14;

		// Printing treatment filter chips
		int chipGap = 2;
		int chipW = (w - chipGap * (PRINT_FILTER_LABELS.length - 1)) / PRINT_FILTER_LABELS.length;
		for (int i = 0; i < PRINT_FILTER_LABELS.length; i++) {
			int cx = x + i * (chipW + chipGap);
			int cw = i == PRINT_FILTER_LABELS.length - 1 ? (x + w - cx) : chipW;
			if (inside(mouseX, mouseY, cx, y, cw, 13)) {
				if (printingFilter != i) {
					printingFilter = i;
					printingScroll = 0;
					List<PrintingOption> nextFiltered = filteredPrintings();
					if (!nextFiltered.isEmpty()) {
						boolean hasActive = false;
						for (int k = 0; k < nextFiltered.size(); k++) {
							if (nextFiltered.get(k).printingId().equals(selectedPrinting)) {
								hasActive = true;
								ensurePrintingVisible(k);
								break;
							}
						}
						if (!hasActive) {
							selectPrintingOption(nextFiltered.getFirst(), 0);
						}
					}
				}
				return true;
			}
		}
		y += 16;

		// Printing list rows
		for (int i = 0; i < rows; i++) {
			int idx = printingScroll + i;
			if (idx >= filtered.size()) {
				break;
			}
			int ry = y + i * 15;
			if (inside(mouseX, mouseY, x, ry, w, 14)) {
				selectPrintingOption(filtered.get(idx), idx);
				return true;
			}
		}

		// Finish pills — respect server allow_foil / allow_etched rules
		PrintingOption activeOpt = activePrintingOption();
		String activeRarity = activeOpt != null ? activeOpt.rarity() : sel.rarity();
		String activeSetCode = activeOpt != null ? activeOpt.setCode() : sel.setCode();
		String activeTreatment = activeOpt != null ? activeOpt.treatment() : sel.treatment();
		String activePrintId = activeOpt != null ? activeOpt.printingId() : sel.printingId();
		int finishY = layout.inspY + layout.inspH - 58;
		int fw = (w - 8) / 3;
		Finish[] finishes = {Finish.NONFOIL, Finish.FOIL, Finish.ETCHED};
		for (int i = 0; i < finishes.length; i++) {
			int fx = x + i * (fw + 4);
			int btnW = i == 2 ? (w - (fw + 4) * 2) : fw;
			if (inside(mouseX, mouseY, fx, finishY, btnW, 14) && economy.craft().isFinishAllowed(finishes[i])) {
				selectedFinish = finishes[i].name();
				return true;
			}
		}

		// Craft quantity
		int qtyY = finishY + 18;
		int maxBatch = economy.craft().maxBatch();
		if (inside(mouseX, mouseY, x, qtyY, 20, 15) && craftAmount > 1) {
			craftAmount--;
			return true;
		}
		if (inside(mouseX, mouseY, x + 52, qtyY, 20, 15) && craftAmount < maxBatch) {
			craftAmount++;
			return true;
		}

		// Craft action button
		int craftBtnY = qtyY + 19;
		Finish currentFinish = Finish.fromName(selectedFinish);
		boolean allowed = !isSelectedCraftBlocked(sel, activeOpt)
			&& economy.craft().isTreatmentAllowed(activeTreatment)
			&& economy.craft().isFinishAllowed(currentFinish);
		int unitCost = DustValues.craft(sel.oracleId(), sel.name(), activePrintId, activeSetCode, activeRarity, activeTreatment, selectedFinish, economy);
		long totalCost = (long) unitCost * craftAmount;
		if (allowed && dust >= totalCost && inside(mouseX, mouseY, x, craftBtnY, w, 18)) {
			sendAction("craft", sel.oracleId(), activePrintId, selectedFinish, craftAmount);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (tab == 1 && inside(mouseX, mouseY, layout.inspX, layout.inspY, layout.inspW, layout.inspH)) {
			List<PrintingOption> filtered = filteredPrintings();
			if (!filtered.isEmpty()) {
				int rows = Math.max(1, layout.printListRows);
				int max = Math.max(0, filtered.size() - rows);
				if (scrollY < 0 && printingScroll < max) {
					printingScroll++;
				} else if (scrollY > 0 && printingScroll > 0) {
					printingScroll--;
				}
				return true;
			}
		}
		if (scrollY > 0 && page > 0) {
			page--;
			return true;
		}
		if (scrollY < 0 && page + 1 < pageCount()) {
			page++;
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
				if (tab == 1 && !craftOwnedOnly && keyCode != GLFW.GLFW_KEY_ESCAPE) {
					sendSearchNow();
				}
				return true;
			}
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
		if (keyCode == GLFW.GLFW_KEY_F) {
			favOnly = !favOnly;
			page = 0;
			rebuild();
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private record MassDustSummary(int copies, int dust, int favCopies) {
	}

	private boolean isCardCraftBlocked(BinderCard card) {
		if (card == null) {
			return false;
		}
		return restrictions.isCraftBlocked(card);
	}

	private boolean isSelectedCraftBlocked(BinderCard sel, PrintingOption activeOpt) {
		if (sel == null) {
			return false;
		}
		if (activeOpt != null) {
			return restrictions.isCraftBlocked(activeOpt.oracleId(), activeOpt.name(), activeOpt.setCode(), activeOpt.rarity(), activeOpt.typeLine());
		}
		return restrictions.isCraftBlocked(sel);
	}

	private static String formatMultiplier(double mul) {
		long rounded = Math.round(mul);
		if (Math.abs(mul - rounded) < 0.005) {
			return Long.toString(rounded);
		}
		return String.format(Locale.ROOT, "%.2f", mul).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private MassDustSummary computeMassDust() {
		int copies = 0;
		long dustTotal = 0;
		int favCopies = 0;
		for (BinderCard card : all) {
			if (card.count() <= 0) {
				continue;
			}
			if (isFav(card)) {
				favCopies += card.count();
			} else {
				copies += card.count();
				dustTotal += (long) DustValues.disenchant(card, economy) * card.count();
			}
		}
		return new MassDustSummary(copies, (int) Math.min(Integer.MAX_VALUE, dustTotal), favCopies);
	}

	private int ownedCount(String oracleId, String printingId, String finish) {
		String fn = finish == null || finish.isEmpty() ? Finish.NONFOIL.name() : finish;
		return ownedByKey.getOrDefault(cardKey(oracleId, printingId, fn), 0);
	}

	private static String cardKey(String oracleId, String printingId, String finish) {
		return oracleId + "\0" + printingId + "\0" + finish;
	}

	private boolean isFav(BinderCard card) {
		return favourites.contains(cardKey(card.oracleId(), card.printingId(), card.finish()));
	}

	private void toggleFav(BinderCard card) {
		if (!ClientPlayNetworking.canSend(FavouriteC2SPayload.TYPE)) {
			return;
		}
		String finish = card.finish().isEmpty() ? Finish.NONFOIL.name() : card.finish();
		ClientPlayNetworking.send(new FavouriteC2SPayload(card.oracleId(), card.printingId(), finish, "dustery"));
	}

	private void sendAction(String action, String oracleId, String printingId, String finish, int amount) {
		if (!ClientPlayNetworking.canSend(DusteryActionC2SPayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new DusteryActionC2SPayload(action, oracleId, printingId, finish, amount));
	}

	private int slotAt(double mouseX, double mouseY) {
		int per = perPage();
		for (int slot = 0; slot < per; slot++) {
			int col = slot % layout.cols;
			int row = slot / layout.cols;
			int x = layout.gridX + col * (layout.cardW + layout.gap);
			int y = layout.gridY + row * (layout.cardH + layout.gap);
			if (inside(mouseX, mouseY, x, y, layout.cardW, layout.cardH)) {
				return slot;
			}
		}
		return -1;
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
		int tabDisX, int tabDisW, int tabCraftX, int tabCraftW, int tabY,
		int searchX, int searchY, int searchW, int searchW_OwnedCraft,
		int goBtnX, int goBtnW,
		int scopeBtnX, int scopeBtnW,
		int[] colorX, int favX, int favW, int[] rarityX, int chipsY,
		int pageX, int pageY, int pageW, int pageH,
		int cols, int cardW, int cardH, int gap, int gridX, int gridY,
		int prevX, int nextX, int arrowY,
		int inspX, int inspY, int inspW, int inspH,
		int previewW, int previewH, int printListRows
	) {
		private static final Layout EMPTY = new Layout(
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 60, 0, 44, 0,
			0, 0, 120, 120, 0, 24, 0, 56,
			new int[6], 0, 36, new int[4], 0,
			0, 0, 0, 0,
			3, 40, 56, 6, 0, 0,
			0, 0, 0,
			0, 0, 0, 0,
			50, 70, 3
		);

		private static Layout compute(int screenW, int screenH, int allCardsLabelW) {
			int margin = 8;
			int usableW = Math.max(380, screenW - margin * 2);
			int usableH = Math.max(220, screenH - margin * 2);
			int gapBetweenPanes = 6;

			int rightW = Mth.clamp((int) (usableW * 0.34f), 205, 290);
			int leftW = usableW - rightW - gapBetweenPanes;
			if (leftW < 230) {
				leftW = Math.max(200, usableW - 190 - gapBetweenPanes);
				rightW = usableW - leftW - gapBetweenPanes;
			}
			int leftX = margin;
			int leftY = margin;
			int leftH = usableH;
			int rightX = leftX + leftW + gapBetweenPanes;
			int rightY = margin;
			int rightH = usableH;

			// Header & tabs inside Left Pane
			int tabY = leftY + 5;
			int tabCraftW = 46;
			int tabDisW = 68;
			int tabCraftX = leftX + leftW - 12 - tabCraftW;
			int tabDisX = tabCraftX - 4 - tabDisW;

			// Search row
			int searchX = leftX + 14;
			int searchY = leftY + 30;
			int scopeBtnW = Math.max(58, allCardsLabelW + 12);
			int scopeBtnX = leftX + leftW - 12 - scopeBtnW;
			int goBtnW = 24;
			int goBtnX = scopeBtnX - 4 - goBtnW;
			int searchW = Math.max(70, goBtnX - searchX - 4);
			int searchW_OwnedCraft = Math.max(70, scopeBtnX - searchX - 6);

			// Chips row
			int chipsY = searchY + 19;
			int[] colorX = new int[COLOR_KEYS.length];
			for (int i = 0; i < colorX.length; i++) {
				colorX[i] = searchX + i * 17;
			}
			int favX = searchX + COLOR_KEYS.length * 17 + 6;
			int favW = 34;

			int[] rarityX = new int[RARITY_KEYS.length];
			int rarityStart = leftX + leftW - 12 - RARITY_KEYS.length * 17;
			int minRarityX = favX + favW + 8;
			if (rarityStart < minRarityX) {
				rarityStart = minRarityX;
			}
			for (int i = 0; i < rarityX.length; i++) {
				rarityX[i] = rarityStart + i * 17;
			}

			// Grid canvas
			int pageX = leftX + 10;
			int pageY = chipsY + 19;
			int pageW = leftW - 18;
			int pageH = leftH - (pageY - leftY) - 18;

			int gap = 6;
			int arrow = 18;
			int innerW = Math.max(60, pageW - arrow * 2 - 8);
			int innerH = Math.max(60, pageH - 8);
			int cardH = (innerH - gap * (ROWS - 1)) / ROWS;
			int cardW = cardH * 5 / 7;
			if (cardW < 36) {
				cardW = 36;
				cardH = 50;
			}
			int cols = Math.max(3, Math.min(5, (innerW + gap) / (cardW + gap)));
			while (cols > 3 && cols * cardW + (cols - 1) * gap > innerW) {
				cols--;
			}
			if (cols * cardW + (cols - 1) * gap > innerW) {
				cardW = (innerW - (cols - 1) * gap) / cols;
				cardH = cardW * 7 / 5;
			}
			int gridW = cols * cardW + (cols - 1) * gap;
			int gridH = ROWS * cardH + (ROWS - 1) * gap;
			int gridX = pageX + arrow + Math.max(0, (innerW - gridW) / 2) + 4;
			int gridY = pageY + Math.max(0, (pageH - gridH) / 2);

			int prevX = pageX + 2;
			int nextX = pageX + pageW - 16;
			int arrowY = pageY + pageH / 2 - 7;

			// Inspector canvas
			int inspX = rightX + 8;
			int inspY = rightY + 24;
			int inspW = rightW - 20;
			int inspH = rightH - 42;

			int previewH = Mth.clamp((inspH - 160) / 2, 46, 98);
			int previewW = previewH * 5 / 7;
			int availableForPrintList = inspH - previewH - 130;
			int printListRows = Mth.clamp(availableForPrintList / 15, 1, 10);

			return new Layout(
				leftX, leftY, leftW, leftH,
				rightX, rightY, rightW, rightH,
				tabDisX, tabDisW, tabCraftX, tabCraftW, tabY,
				searchX, searchY, searchW, searchW_OwnedCraft,
				goBtnX, goBtnW,
				scopeBtnX, scopeBtnW,
				colorX, favX, favW, rarityX, chipsY,
				pageX, pageY, pageW, pageH,
				cols, cardW, cardH, gap, gridX, gridY,
				prevX, nextX, arrowY,
				inspX, inspY, inspW, inspH,
				previewW, previewH, printListRows
			);
		}
	}
}
