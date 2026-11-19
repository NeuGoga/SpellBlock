package com.neugoga.spellblock.client;

import static com.neugoga.spellblock.client.ui.Ui.inside;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.math.Axis;

import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.network.HandRevealS2CPayload;
import com.neugoga.spellblock.network.HandSyncS2CPayload;
import com.neugoga.spellblock.network.ScryFinishC2SPayload;
import com.neugoga.spellblock.network.ScrySyncS2CPayload;
import com.neugoga.spellblock.network.TableActionC2SPayload;
import com.neugoga.spellblock.table.SeatZones;
import com.neugoga.spellblock.table.TableBlock;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableCard;
import com.neugoga.spellblock.table.TableSeat;
import com.neugoga.spellblock.table.TableSize;
import com.neugoga.spellblock.table.TableZone;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The table you play on. The cloth outside is only a sketch.
 * Shift is read from the keyboard, because opening a screen clears sneak.
 */
public final class TableScreen extends Screen {
	static final int GOLD = 0xFFC4A574;
	static final int TEXT = 0xFFE6D3A3;
	static final int MUTED = 0xFF8C7564;
	static final int PAPER = 0xFFF7F1E4;
	private static final int YOURS = 0xE0461C16;
	private static final int OTHER = 0xD028120E;
	private static final String[] YOURS_ROWS = {"creature", "other", "down", "land"};
	private static final String[] THEIRS_ROWS = {"land", "down", "other", "creature"};

	final List<Click> clicks = new ArrayList<>();

	BlockPos pos;
	String seat;
	private int library;
	List<BinderCard> hand;
	/** The table's id for each hand card, in the same order as hand. */
	private List<Integer> handIds = List.of();
	String status;
	/** seat name -> player name for anyone with this table open. */
	private final Map<String, String> sitters = new HashMap<>();
	/** Seats currently scrying/surveilling (library look). */
	private final java.util.HashSet<String> lookers = new java.util.HashSet<>();
	/** Face-down peeks: seat -> zone:cardId */
	private final Map<String, String> peeks = new HashMap<>();
	/** Pending control asks: ownerSeat -> zone:index (highlight that card). */
	private final Map<String, String> controlTargets = new HashMap<>();
	/** Last peek we told the server about, so we do not spam packets. */
	private String lastPeekSent;
	/** Seat shown large. Null means your own. */
	private TableSeat focus;
	/** Every seat at once. Yours keeps the gold frame. */
	private boolean overview;
	/** Zoom while Alt is held. Alt and the wheel change it, and it stays put between cards. */
	private static float zoom = 1.0f;
	/** Hands other seats chose to show. Keyed by that seat's name. */
	private final Map<String, List<BinderCard>> revealed = new HashMap<>();

	// Parts of the screen that own their own state. Each draws and handles one piece.
	final GuidePanel guide = new GuidePanel(this);
	final CountPrompt prompt = new CountPrompt(this);
	final ScryPanel scry = new ScryPanel(this);
	final ContextMenu menu = new ContextMenu(this);

	public TableScreen(HandSyncS2CPayload payload) {
		super(Component.literal("Table"));
		apply(payload);
	}

	/** Package-private view of Screen's font, for the panels. */
	Font uiFont() {
		return this.font;
	}

	/** Package-private view of Screen's minecraft, for the panels. */
	Minecraft uiMinecraft() {
		return this.minecraft;
	}

	/** Called when the server opens a scry, surveil, or search for this player. */
	public void beginScry(ScrySyncS2CPayload payload) {
		scry.beginScry(payload);
	}

	public void replace(HandSyncS2CPayload payload) {
		if ("closed".equals(payload.status())) {
			onClose();
			return;
		}
		apply(payload);
	}

	@Override
	public void onClose() {
		send("close", 0);
		super.onClose();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		CardArt.beginFrame();
		clicks.clear();
		graphics.fill(0, 0, this.width, this.height, 0xF0100908);
		TableBlockEntity table = table();
		TableSeat mine = seatOf(seat);
		int handH = hand == null || hand.isEmpty() ? 52 : Math.min(100, Math.max(74, this.height / 6));
		int tabY = 4;
		int areaY = tabY + 18;
		int statusH = status != null && !status.isEmpty() ? 12 : 0;
		int areaH = this.height - areaY - handH - 8 - statusH;
		if (areaH < 48) {
			areaH = 48;
		}
		if (mine != null) {
			drawTabs(graphics, table, mine, 8, tabY, this.width - 16);
			if (overview) {
				drawOverview(graphics, table, mine, 8, areaY, this.width - 16, areaH);
			} else {
				TableSeat shown = focus == null ? mine : focus;
				if (!exists(table, sizeOf(table), shown, mine)) {
					shown = mine;
				}
				drawBoard(graphics, table, shown, mine, 8, areaY, this.width - 16, areaH, shown != mine, false);
			}
		}
		int handY = this.height - handH - 4;
		if (status != null && !status.isEmpty()) {
			graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(status, this.width - 24), this.width / 2, handY - 12, 0xFFE0B4A0);
		}
		graphics.fill(8, handY - 3, this.width - 8, handY - 2, GOLD);
		int mark = 16;
		drawHand(graphics, 8, handY, this.width - 16 - mark - 6, handH);
		guide.drawHelp(graphics, this.width - 8 - mark, handY + handH - mark, mark);
		Click top = top(mouseX, mouseY);
		// Hover under the middle-click menu and under Alt zoom.
		if (menu.menuZone == null && !guide.guideOpen && top != null && !top.name.isEmpty() && !"menu".equals(top.zone)
			&& !(isCard(top) && hasAltDown())) {
			int tw = this.font.width(top.name);
			int tx = Math.min(mouseX + 8, this.width - tw - 6);
			int ty = Math.max(4, mouseY - 14);
			graphics.pose().pushPose();
			graphics.pose().translate(0.0f, 0.0f, 200.0f);
			graphics.fill(tx - 3, ty - 2, tx + tw + 3, ty + 10, 0xFF18100C);
			graphics.drawString(this.font, top.name, tx, ty, PAPER);
			graphics.pose().popPose();
		}
		menu.drawMenu(graphics);
		if (guide.guideOpen) {
			guide.drawGuide(graphics);
		}
		if (scry.scryMode != null) {
			scry.drawScry(graphics);
		}
		// Re-hit after scry so Alt zoom can see looked cards (hits were registered during drawScry).
		Click zoomTop = top(mouseX, mouseY);
		if (menu.menuZone == null && !guide.guideOpen && prompt.typingKind == null && zoomTop != null && isCard(zoomTop) && hasAltDown() && scry.zoomAllowed(zoomTop)) {
			drawZoom(graphics, zoomTop, mouseX, mouseY);
			reportPeek(zoomTop);
		} else {
			clearPeek();
		}
		if (prompt.typingKind != null) {
			prompt.drawCountPrompt(graphics);
		}
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (prompt.typingKind != null) {
			if (button == 0 && prompt.countBox != null) {
				prompt.countBox.setFocused(true);
				this.setFocused(prompt.countBox);
			}
			return true;
		}
		if (scry.scryMode != null) {
			if (scry.scryClick(mouseX, mouseY, button)) {
				return true;
			}
			if (!scry.scryHidden) {
				return true;
			}
			// Hidden: fall through so tabs and the board still work.
		}
		if (guide.guideOpen) {
			boolean mark = inside(mouseX, mouseY, guide.markX, guide.markY, guide.markS, guide.markS);
			boolean panel = inside(mouseX, mouseY, guide.panelX, guide.panelY, guide.panelW, guide.panelH);
			if (button == 0 && (mark || !panel)) {
				guide.guideOpen = false;
			}
			return true;
		}
		if (menu.menuZone != null) {
			Click picked = null;
			for (int i = clicks.size() - 1; i >= 0; i--) {
				Click click = clicks.get(i);
				if ("menu".equals(click.zone) && inside(mouseX, mouseY, click.x, click.y, click.w, click.h)) {
					picked = click;
					break;
				}
			}
			int cardId = menu.menuCardId;
			String openZone = menu.menuZone;
			boolean stickyCmd = openZone != null && openZone.startsWith("pick:cmd:");
			if (picked != null && (button == 0 || (button == 1 && stickyCmd))) {
				if (picked.extra != null && picked.extra.startsWith("pick:")) {
					menu.menuZone = picked.extra;
					menu.menuCardId = cardId;
					return true;
				}
				if (picked.extra != null && picked.extra.startsWith("type:")) {
					menu.menuZone = null;
					prompt.openCountPrompt(picked.extra.substring(5));
					return true;
				}
				if (picked.extra != null && !picked.extra.isEmpty()) {
					if (picked.extra.startsWith("cmd:")) {
						int delta = button == 1 ? -1 : 1;
						send(picked.extra, 0, delta);
						// Keep the commander-damage menu open until click-out.
						menu.menuZone = openZone;
						menu.menuCardId = cardId;
						return true;
					}
					menu.menuZone = null;
					if (picked.extra.startsWith("nth:")) {
						send(picked.extra, cardId, 2);
					} else {
						send(picked.extra, cardId);
					}
					return true;
				}
				return true;
			}
			// Click outside the menu (or middle-click elsewhere) closes it.
			if (button != 2 || picked == null) {
				menu.menuZone = null;
			}
			if (button != 2) {
				return true;
			}
		}
		boolean shift = hasShiftDown();
		for (int i = clicks.size() - 1; i >= 0; i--) {
			Click click = clicks.get(i);
			if (!inside(mouseX, mouseY, click.x, click.y, click.w, click.h)) {
				continue;
			}
			if (click.owner && click.zone != null && click.zone.startsWith("ctr:") && (button == 0 || button == 1)) {
				int delta = button == 0 ? 1 : -1;
				String kind = click.zone.substring(4);
				int cardId;
				try {
					cardId = Integer.parseInt(click.extra);
				} catch (NumberFormatException e) {
					cardId = click.cardId;
				}
				String action = switch (kind) {
					case "p" -> "cp";
					case "t" -> "ct";
					case "b" -> "cb";
					case "l" -> "cl";
					case "m" -> "cm";
					default -> "";
				};
				if (!action.isEmpty()) {
					send(action, cardId, delta);
				}
				return true;
			}
			if (button == 2 && click.owner && "fieldempty".equals(click.zone)) {
				menu.menuZone = "fieldempty";
				menu.menuCardId = 0;
				menu.menuX = (int) mouseX;
				menu.menuY = (int) mouseY;
				menu.menuOwner = true;
				menu.menuExtra = "";
				java.util.ArrayList<String> labels = new java.util.ArrayList<>();
				java.util.ArrayList<String> actions = new java.util.ArrayList<>();
				TableBlockEntity te = table();
				TableSeat mine = seatOf(seat);
				if (te != null && mine != null) {
					for (com.neugoga.spellblock.catalog.OracleCard tok : com.neugoga.spellblock.pack.TokenKit.fromBox(te.get(mine))) {
						String label = tok.name();
						if (label.length() > 28) {
							label = label.substring(0, 28);
						}
						labels.add(label);
						// Scryfall all_parts gives a printing id, not an oracle id.
						String pid = tok.printingId();
						String action = "token:pid:" + pid;
						String shortName = tok.name().replace('|', '/');
						if (shortName.length() > 12) {
							shortName = shortName.substring(0, 12);
						}
						if (!shortName.isEmpty() && action.length() + 1 + shortName.length() <= 64) {
							action = action + "|" + shortName;
						}
						actions.add(action);
					}
				}
				menu.tokenLabels = labels;
				menu.tokenActions = actions;
				return true;
			}
			if (button == 2 && click.owner && "library".equals(click.zone)) {
				menu.menuZone = "library";
				menu.menuCardId = 0;
				menu.menuFaceDown = false;
				menu.menuX = (int) mouseX;
				menu.menuY = (int) mouseY;
				return true;
			}
			if (button == 2 && isCard(click) && ("field".equals(click.zone) || "cmd".equals(click.zone) || "grave".equals(click.zone) || "exile".equals(click.zone) || click.owner)) {
				menu.menuZone = click.zone;
				menu.menuCardId = click.cardId;
				menu.menuFaceDown = click.faceDown;
				menu.menuX = (int) mouseX;
				menu.menuY = (int) mouseY;
				menu.menuExtra = click.extra; // owner seat name when not yours
				menu.menuOwner = click.owner;
				menu.menuHasCounters = false;
				if ("field".equals(click.zone) && click.owner) {
					TableCard fieldCard = fieldCardById(click.cardId);
					menu.menuHasCounters = fieldCard != null && (fieldCard.hasCounters() || fieldCard.loyalty() > 0);
				}
				return true;
			}
			if ("draw".equals(click.zone) && button == 0) {
				send("draw", 0);
				return true;
			}
			if ("help".equals(click.zone) && button == 0) {
				guide.guideOpen = true;
				menu.menuZone = null;
				return true;
			}
			if (("tab".equals(click.zone) || "focus".equals(click.zone)) && button == 0) {
				select(click.extra);
				return true;
			}
			if ("life".equals(click.zone) && (button == 0 || button == 1)) {
				int delta = button == 1 ? -1 : 1;
				if (shift) {
					delta *= 5;
				}
				send("life:" + click.extra, 0, delta);
				return true;
			}
			if ("life".equals(click.zone) && button == 2) {
				menu.menuZone = "pick:setlife:" + click.extra;
				menu.menuCardId = 0;
				menu.menuX = (int) mouseX;
				menu.menuY = (int) mouseY;
				menu.menuOwner = true;
				menu.menuExtra = click.extra;
				return true;
			}
			if ("cmddmg".equals(click.zone) && button == 0) {
				// Target seat is in extra; open opponent picker for commander damage.
				menu.menuZone = "pick:cmd:" + click.extra;
				menu.menuCardId = 0;
				menu.menuX = (int) mouseX;
				menu.menuY = (int) mouseY;
				menu.menuOwner = true;
				menu.menuExtra = click.extra;
				return true;
			}
			if ("hand".equals(click.zone)) {
				if (button == 0 && !shift) {
					send("play", click.cardId);
				} else if (button == 0 && shift) {
					send("handexile", click.cardId);
				} else if (button == 1 && !shift) {
					send("grave", click.cardId);
				} else if (button == 1 && shift) {
					send("handlib", click.cardId);
				}
				return true;
			}
			if (click.owner && "field".equals(click.zone)) {
				// Left = tap, right = graveyard (shift-left exile, shift-right hand).
				if (button == 0 && !shift) {
					send("tap", click.cardId);
				} else if (button == 0 && shift) {
					send("fieldexile", click.cardId);
				} else if (button == 1 && !shift) {
					send("fieldgrave", click.cardId);
				} else if (button == 1 && shift) {
					send("fieldhand", click.cardId);
				}
				return true;
			}
			if (click.owner && "cmd".equals(click.zone)) {
				// Left/right adjust tax; middle-click casts to the battlefield.
				if (button == 0) {
					send("cmdtax", click.cardId, 1);
				} else if (button == 1) {
					send("cmdtax", click.cardId, -1);
				} else if (button == 2) {
					send("cmdplay", click.cardId);
				}
				return true;
			}
			if (click.owner && "grave".equals(click.zone)) {
				if (button == 0 && !shift) {
					send("gravehand", click.cardId);
				} else if (button == 0 && shift) {
					send("graveexile", click.cardId);
				} else if (button == 1 && shift) {
					send("graveback", click.cardId);
				}
				return true;
			}
			if (click.owner && "exile".equals(click.zone)) {
				if (button == 0 && !shift) {
					send("exilehand", click.cardId);
				} else if (button == 1 && shift) {
					send("exileback", click.cardId);
				}
				return true;
			}
			if (isCard(click)) {
				if (button == 0 && !click.extra.isEmpty()) {
					select(click.extra);
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (prompt.typingKind != null) {
			if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				closeCountPrompt();
				return true;
			}
			if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
				prompt.confirmCountPrompt();
				return true;
			}
			if (prompt.countBox != null && prompt.countBox.keyPressed(keyCode, scanCode, modifiers)) {
				return true;
			}
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if (prompt.typingKind != null && prompt.countBox != null && prompt.countBox.charTyped(codePoint, modifiers)) {
			return true;
		}
		return super.charTyped(codePoint, modifiers);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (scry.scryMode != null && !scry.scryHidden && scry.scryDrag(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (scry.scryRelease(mouseX, mouseY, button)) {
			return true;
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (hasAltDown()) {
			zoom += (float) scrollY * 0.15f;
			if (zoom < 0.4f) {
				zoom = 0.4f;
			} else if (zoom > 2.5f) {
				zoom = 2.5f;
			}
			return true;
		}
		if (scry.scryMode != null && !scry.scryHidden && scry.scryScroll(mouseX, mouseY, scrollX, scrollY)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
	}

	private void select(String extra) {
		if ("ALL".equals(extra)) {
			overview = true;
			return;
		}
		TableSeat next = seatOf(extra);
		if (next != null) {
			focus = next;
			overview = false;
		}
	}

	private void drawTabs(GuiGraphics graphics, TableBlockEntity table, TableSeat mine, int x, int y, int w) {
		ArrayList<String> extras = new ArrayList<>();
		ArrayList<String> labels = new ArrayList<>();
		addTab(extras, labels, table, mine, mine, true);
		TableSeat left = leftOf(mine);
		TableSeat across = opposite(mine);
		TableSeat right = rightOf(mine);
		TableSize size = sizeOf(table);
		if (exists(table, size, left, mine)) {
			addTab(extras, labels, table, mine, left, false);
		}
		if (exists(table, size, across, mine)) {
			addTab(extras, labels, table, mine, across, false);
		}
		if (exists(table, size, right, mine)) {
			addTab(extras, labels, table, mine, right, false);
		}
		boolean several = extras.size() > 1;
		if (several) {
			extras.add("ALL");
			labels.add(w > 640 ? "All boards" : "All");
		}
		int n = extras.size();
		int gap = 4;
		int tabW = Math.min(108, (w - gap * (n - 1)) / n);
		int used = n * tabW + (n - 1) * gap;
		int cursor = x + Math.max(0, (w - used) / 2);
		String active = overview ? "ALL" : (focus == null ? mine.name() : focus.name());
		for (int i = 0; i < n; i++) {
			String extra = extras.get(i);
			boolean on = extra.equals(active);
			boolean yours = mine.name().equals(extra);
			int color = on ? 0xFF4A2820 : 0xFF241610;
			graphics.fill(cursor, y, cursor + tabW, y + 16, color);
			graphics.fill(cursor, y, cursor + tabW, y + 1, on ? GOLD : (yours ? 0xFF6A5040 : 0xFF3A2420));
			// Seat identity chip (always) and brighter when that seat is looking at their library.
			if (!"ALL".equals(extra)) {
				int chip = seatColor(extra);
				boolean looking = lookers.contains(extra);
				graphics.fill(cursor + 3, y + 4, cursor + 9, y + 12, chip);
				if (looking) {
					graphics.fill(cursor + 2, y + 3, cursor + 10, y + 4, PAPER);
					graphics.fill(cursor + 2, y + 12, cursor + 10, y + 13, PAPER);
					graphics.fill(cursor + 2, y + 3, cursor + 3, y + 13, PAPER);
					graphics.fill(cursor + 9, y + 3, cursor + 10, y + 13, PAPER);
				}
			}
			int text = yours ? GOLD : TEXT;
			int textPad = "ALL".equals(extra) ? 6 : 14;
			graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(labels.get(i), tabW - textPad), cursor + tabW / 2 + ( "ALL".equals(extra) ? 0 : 3), y + 4, text);
			hit(cursor, y, tabW, 16, "tab", 0, "", extra, "", "", false, true);
			cursor += tabW + gap;
		}
	}

	private void addTab(List<String> extras, List<String> labels, TableBlockEntity table, TableSeat mine, TableSeat seat, boolean yours) {
		String label = yours ? "You" : label(seat);
		if (occupied(table, seat)) {
			label = label + "  " + lifeOf(table, seat);
		}
		extras.add(seat.name());
		labels.add(label);
	}

	private void drawOverview(GuiGraphics graphics, TableBlockEntity table, TableSeat mine, int x, int y, int w, int h) {
		TableSize size = sizeOf(table);
		TableSeat across = opposite(mine);
		TableSeat left = leftOf(mine);
		TableSeat right = rightOf(mine);
		boolean up = exists(table, size, across, mine);
		boolean l = exists(table, size, left, mine);
		boolean r = exists(table, size, right, mine);
		int gap = 6;
		int topH = up ? (h - gap) / 2 : 0;
		int botY = y + (up ? topH + gap : 0);
		int botH = h - (up ? topH + gap : 0);
		if (up) {
			drawBoard(graphics, table, across, mine, x, y, w, topH, true, true);
		}
		int cols = 1 + (l ? 1 : 0) + (r ? 1 : 0);
		int colW = (w - gap * (cols - 1)) / cols;
		int cursor = x;
		if (l) {
			drawBoard(graphics, table, left, mine, cursor, botY, colW, botH, false, true);
			cursor += colW + gap;
		}
		drawBoard(graphics, table, mine, mine, cursor, botY, colW, botH, false, true);
		cursor += colW + gap;
		if (r) {
			drawBoard(graphics, table, right, mine, cursor, botY, colW, botH, false, true);
		}
	}

	private void drawBoard(
		GuiGraphics graphics,
		TableBlockEntity table,
		TableSeat seat,
		TableSeat mine,
		int x,
		int y,
		int w,
		int h,
		boolean landsTowardTop,
		boolean compact
	) {
		if (w < 16 || h < 16) {
			return;
		}
		boolean owner = seat == mine;
		boolean hasBox = occupied(table, seat);
		int border = owner ? GOLD : 0xFF3A2420;
		int inset = owner ? 2 : 1;
		int fill = !hasBox ? 0xC0180C0A : (owner ? YOURS : OTHER);
		graphics.fill(x, y, x + w, y + h, border);
		graphics.fill(x + inset, y + inset, x + w - inset, y + h - inset, fill);
		if (compact) {
			String look = owner ? "" : "Click to inspect " + label(seat);
			hit(x, y, w, h, "focus", 0, look, seat.name(), "", "", false, owner);
		}
		int badgeW = compact ? 34 : 52;
		int badgeH = compact ? 14 : 22;
		int badgeX = x + w - badgeW - 5;
		int badgeY = y + 5;
		String title = owner ? (compact ? "You" : "Your board") : label(seat) + (compact ? "" : "'s board");
		int titleRoom = hasBox ? badgeX - x - 8 : w - 10;
		graphics.drawString(this.font, this.font.plainSubstrByWidth(title, Math.max(8, titleRoom)), x + 6, y + 6, owner ? GOLD : TEXT);
		boolean seatLooking = lookers.contains(seat.name());
		if (!compact) {
			String sub;
			if (!hasBox) {
				sub = "Empty seat";
			} else {
				sub = libraryOf(table, seat, owner) + " in library";
				if (seatLooking) {
					sub = sub + "  ·  looking";
				} else if (!owner) {
					sub = sub + "  ·  not your seat";
				}
			}
			int subColor = seatLooking ? seatColor(seat) : MUTED;
			graphics.drawString(this.font, this.font.plainSubstrByWidth(sub, Math.max(8, titleRoom)), x + 6, y + 16, subColor);
		} else if (!hasBox) {
			graphics.drawString(this.font, "Empty", x + 6, y + 16, MUTED);
		} else if (seatLooking) {
			graphics.fill(x + 6, y + 17, x + 12, y + 23, seatColor(seat));
		}
		if (hasBox) {
			drawLife(graphics, seat, lifeOf(table, seat), badgeX, badgeY, badgeW, badgeH, !compact && owner);
		}
		List<BinderCard> peek = owner ? null : revealed.get(seat.name());
		int peekH = peek == null ? 0 : (compact ? Math.min(42, Math.max(28, h / 4)) : Math.min(78, Math.max(50, h / 5)));
		int header = compact ? 20 : 30;
		if (table == null || h < header + 16) {
			return;
		}
		SeatZones zones = table.zones(seat);
		int bodyY = y + header;
		int bodyH = h - header - 4 - peekH;
		int bodyX = x + 4;
		int bodyW = w - 8;
		if (bodyH < 8) {
			bodyH = 8;
		}
		int pileW = Math.min(156, Math.max(76, bodyW / 5));
		if (bodyW < 180) {
			pileW = Math.max(64, bodyW / 3);
		}
		if (!compact) {
			int rowsW = bodyW - pileW - 6;
			drawZoneRows(graphics, zones, seat, bodyX, bodyY, rowsW, bodyH, landsTowardTop, false, owner);
			int pileX = bodyX + rowsW + 6;
			boolean hasCmd = !zones.command().isEmpty();
			int gap = 4;
			if (hasCmd) {
				// Command tray sits above grave/exile, same width — not a full-board strip.
				int cmdH = Math.min(Math.max(72, bodyH * 2 / 5), bodyH - 48);
				int rest = bodyH - cmdH - gap * 2;
				int trayH = Math.max(20, rest / 2);
				drawCommandTray(graphics, zones, seat, pileX, bodyY, pileW, cmdH, owner);
				drawTray(graphics, zones.graveyard(), zones.graveyardNames(), pileX, bodyY + cmdH + gap, pileW, trayH, owner, seat, "grave", "Grave", !landsTowardTop);
				drawTray(graphics, zones.exile(), zones.exileNames(), pileX, bodyY + cmdH + gap + trayH + gap, pileW, bodyH - cmdH - gap * 2 - trayH, owner, seat, "exile", "Exile", !landsTowardTop);
			} else {
				int trayH = (bodyH - 4) / 2;
				drawTray(graphics, zones.graveyard(), zones.graveyardNames(), pileX, bodyY, pileW, trayH, owner, seat, "grave", "Grave", !landsTowardTop);
				drawTray(graphics, zones.exile(), zones.exileNames(), pileX, bodyY + trayH + 4, pileW, bodyH - trayH - 4, owner, seat, "exile", "Exile", !landsTowardTop);
			}
			if (peek != null) {
				drawPeek(graphics, peek, bodyX, y + h - peekH - 2, bodyW, peekH - 2);
			}
			return;
		}
		boolean piles = !zones.graveyard().isEmpty() || !zones.exile().isEmpty();
		int strip = piles ? Math.min(34, Math.max(18, bodyH / 4)) : 0;
		if (landsTowardTop) {
			if (strip > 0) {
				drawCompactPiles(graphics, zones, seat, bodyX, bodyY, bodyW, strip, owner);
			}
			drawZoneRows(graphics, zones, seat, bodyX, bodyY + strip, bodyW, bodyH - strip, true, true, owner);
		} else {
			drawZoneRows(graphics, zones, seat, bodyX, bodyY, bodyW, bodyH - strip, false, true, owner);
			if (strip > 0) {
				drawCompactPiles(graphics, zones, seat, bodyX, bodyY + bodyH - strip, bodyW, strip, owner);
			}
		}
		if (peek != null) {
			drawPeek(graphics, peek, bodyX, y + h - peekH - 2, bodyW, peekH - 2);
		}
	}


	/**
	 * Command tray in the pile column (same width as grave/exile). No full-width strip.
	 * Tax is a life-style badge under each commander.
	 */
	private void drawCommandTray(
		GuiGraphics graphics,
		SeatZones zones,
		TableSeat seat,
		int x,
		int y,
		int w,
		int h,
		boolean owner
	) {
		graphics.fill(x, y, x + w, y + h, 0x66080004);
		List<TableCard> cards = zones.command();
		if (cards.isEmpty() || h < 20) {
			graphics.drawString(this.font, "Cmd  0", x + 3, y + 3, MUTED);
			return;
		}
		int n = cards.size();
		int gap = n > 1 ? 4 : 0;
		int taxH = 16;
		int topPad = 2;
		int cardH = h - taxH - topPad - 2;
		if (cardH < 20) {
			cardH = Math.max(14, h - 14);
			taxH = Math.max(10, h - cardH - topPad - 1);
		}
		int cardW = Math.max(18, (w - 6 - (n - 1) * gap) / Math.max(1, n));
		// Prefer card-shaped aspect when height allows.
		int aspectW = Math.max(18, cardH * 5 / 7);
		if (cardW > aspectW + 8) {
			cardW = aspectW;
		}
		int total = n * cardW + (n - 1) * gap;
		int startX = x + Math.max(3, (w - total) / 2);
		int cy = y + topPad;
		ArrayList<Integer> indexes = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			indexes.add(i);
		}
		// Place cards with exact positions (no re-centering that pushes them off-screen).
		List<String> names = zones.commandNames();
		for (int i = 0; i < n; i++) {
			TableCard card = cards.get(i);
			String name = i < names.size() ? names.get(i) : "";
			int cx = startX + i * (cardW + gap);
			paint(graphics, card.faceDown(), false, card.flipped(), card.printingId(), card.finish(), name, cx, cy, cardW, cardH, card.token());
			String shown = card.faceDown() ? "Face down" : name;
			hitCard(cx, cy, cardW, cardH, "cmd", i, card.id(), shown, owner ? "" : seat.name(), card.printingId(), card.finish(), card.faceDown(), card.flipped(), owner);
			// Life-style tax badge under the card.
			int tax = card.markers();
			int bx = cx;
			int by = cy + cardH + 1;
			int bw = cardW;
			int bh = Math.max(10, taxH - 1);
			graphics.fill(bx, by, bx + bw, by + bh, 0xF0100806);
			graphics.fill(bx, by, bx + bw, by + 1, GOLD);
			graphics.fill(bx, by + bh - 1, bx + bw, by + bh, GOLD);
			int color = tax > 0 ? PAPER : MUTED;
			if (bh >= 14) {
				graphics.pose().pushPose();
				graphics.pose().translate(bx + bw / 2.0f, by + bh / 2.0f, 0);
				graphics.pose().scale(1.25f, 1.25f, 1.0f);
				graphics.drawCenteredString(this.font, Integer.toString(tax), 0, -4, color);
				graphics.pose().popPose();
			} else {
				graphics.drawCenteredString(this.font, Integer.toString(tax), bx + bw / 2, by + Math.max(0, (bh - 8) / 2), color);
			}
			if (owner) {
				hit(bx, by, bw, bh, "cmd", card.id(), "Tax  ·  left +1, right −1, middle cast", "", "", "", false, true);
			}
		}
	}

	/** Rows packed against the near edge, so empty zones do not shove cards into the middle. */
	private void drawZoneRows(
		GuiGraphics graphics,
		SeatZones zones,
		TableSeat seat,
		int x,
		int y,
		int w,
		int h,
		boolean landsTowardTop,
		boolean compact,
		boolean owner
	) {
		if (w < 8 || h < 8) {
			return;
		}
		if (owner) {
			hit(x, y, w, h, "fieldempty", 0, "", "", "", "", false, true);
		}
		String[] order = landsTowardTop ? THEIRS_ROWS : YOURS_ROWS;
		int n = order.length;
		@SuppressWarnings("unchecked")
		ArrayList<Integer>[] groups = new ArrayList[n];
		int[] counts = new int[n];
		for (int i = 0; i < n; i++) {
			groups[i] = ofRole(zones.battlefieldRows(), zones.battlefield().size(), order[i]);
			counts[i] = groups[i].size();
		}
		int prefer = choosePrefer(counts, w, h, compact);
		int gap = compact ? 2 : 4;
		int sum = 0;
		int[] heights = new int[n];
		for (int i = 0; i < n; i++) {
			heights[i] = rowHeight(counts[i], w, prefer, compact);
			if (heights[i] > 0) {
				sum += heights[i] + gap;
			}
		}
		if (sum > 0) {
			sum -= gap;
		}
		int cursor = y + (landsTowardTop ? 0 : Math.max(0, h - sum));
		for (int i = 0; i < n; i++) {
			if (heights[i] <= 0) {
				continue;
			}
			drawOneRow(graphics, zones, groups[i], seat, order[i], x, cursor, w, heights[i], prefer, compact, owner);
			cursor += heights[i] + gap;
		}
	}

	private void drawOneRow(
		GuiGraphics graphics,
		SeatZones zones,
		List<Integer> indexes,
		TableSeat seat,
		String role,
		int x,
		int y,
		int w,
		int h,
		int prefer,
		boolean compact,
		boolean owner
	) {
		int labelH = 0;
		if (!compact) {
			labelH = 12;
			String title = rowTitle(role);
			if (indexes.size() > 0) {
				title = title + "  " + indexes.size();
			}
			graphics.drawString(this.font, title, x, y, MUTED);
		}
		if (indexes.isEmpty()) {
			return;
		}
		Grid grid = grid(indexes.size(), w, prefer, compact);
		int blockH = grid.lines * grid.cardH + Math.max(0, grid.lines - 1) * grid.gap;
		int cardsY = y + labelH;
		if (!compact && labelH + blockH < h) {
			boolean near = "land".equals(role) || "down".equals(role);
			if (near) {
				cardsY = y + h - blockH;
			}
		}
		placeCards(graphics, zones.battlefield(), zones.battlefieldNames(), indexes, x, cardsY, w, grid, "field", owner, seat, false);
	}

	private void drawTray(
		GuiGraphics graphics,
		List<TableCard> cards,
		List<String> names,
		int x,
		int y,
		int w,
		int h,
		boolean owner,
		TableSeat seat,
		String zone,
		String label,
		boolean nearBottom
	) {
		graphics.fill(x, y, x + w, y + h, 0x66080004);
		if (cards.isEmpty() || h < 20) {
			graphics.drawString(this.font, label + "  0", x + 3, y + 3, MUTED);
			return;
		}
		ArrayList<Integer> indexes = new ArrayList<>(cards.size());
		for (int i = 0; i < cards.size(); i++) {
			indexes.add(i);
		}
		int prefer = choosePrefer(new int[] {cards.size()}, w - 4, Math.max(8, h - 16), false);
		Grid grid = grid(cards.size(), w - 4, prefer, false);
		int blockH = grid.lines * grid.cardH + Math.max(0, grid.lines - 1) * grid.gap;
		int cardsY = y + 14;
		if (nearBottom && blockH + 14 < h) {
			cardsY = y + h - blockH - 2;
		}
		graphics.drawString(this.font, label + "  " + cards.size(), x + 3, Math.max(y + 2, cardsY - 11), MUTED);
		placeCards(graphics, cards, names, indexes, x + 2, cardsY, w - 4, grid, zone, owner, seat, true);
	}

	private void drawPeek(GuiGraphics graphics, List<BinderCard> cards, int x, int y, int w, int h) {
		if (w < 8 || h < 12) {
			return;
		}
		String title = "Hand  " + cards.size();
		graphics.drawString(this.font, title, x, y, GOLD);
		if (cards.isEmpty() || h < 22) {
			return;
		}
		int cardH = Math.max(8, h - 12);
		int cardW = Math.max(1, (int) (cardH * 0.716f));
		int gap = 3;
		int count = cards.size();
		int step = cardW + gap;
		int cardsY = y + 11;
		if (count > 1 && cardW + (count - 1) * step > w) {
			step = Math.max(6, (w - cardW) / (count - 1));
		}
		for (int i = 0; i < count; i++) {
			BinderCard card = cards.get(i);
			int cardX = x + i * step;
			int hitW = i == count - 1 ? cardW : Math.min(cardW, step);
			paint(graphics, false, false, false, card.printingId(), card.finish(), card.name(), cardX, cardsY, cardW, cardH);
			hit(cardX, cardsY, hitW, cardH, "peek", i, card.name(), "", card.printingId(), card.finish(), false, false);
		}
	}

	private void drawCompactPiles(GuiGraphics graphics, SeatZones zones, TableSeat seat, int x, int y, int w, int h, boolean owner) {
		int gap = 4;
		int half = (w - gap) / 2;
		drawMiniPile(graphics, zones.graveyard(), zones.graveyardNames(), x, y, half, h, owner, seat, "grave", "Grave");
		drawMiniPile(graphics, zones.exile(), zones.exileNames(), x + half + gap, y, w - half - gap, h, owner, seat, "exile", "Exile");
	}

	private void drawMiniPile(
		GuiGraphics graphics,
		List<TableCard> cards,
		List<String> names,
		int x,
		int y,
		int w,
		int h,
		boolean owner,
		TableSeat seat,
		String zone,
		String label
	) {
		graphics.drawString(this.font, label + " " + cards.size(), x, y, MUTED);
		if (cards.isEmpty() || h < 16 || w < 8) {
			return;
		}
		int show = Math.min(4, cards.size());
		ArrayList<Integer> indexes = new ArrayList<>(show);
		for (int i = cards.size() - show; i < cards.size(); i++) {
			indexes.add(i);
		}
		int cardH = h - 11;
		int cardW = Math.max(1, (int) (cardH * 0.716f));
		int gap = 2;
		int step = cardW + gap;
		if (show > 1 && cardW + (show - 1) * step > w) {
			step = Math.max(4, (w - cardW) / (show - 1));
		}
		for (int i = 0; i < show; i++) {
			int index = indexes.get(i);
			TableCard card = cards.get(index);
			String name = index < names.size() ? names.get(index) : "";
			int cx = x + i * step;
			paint(graphics, card.faceDown(), false, false, card.printingId(), card.finish(), name, cx, y + 10, cardW, cardH);
			String shown = card.faceDown() ? "Face down" : name;
			hitCard(cx, y + 10, i == show - 1 ? cardW : Math.min(cardW, step), cardH, zone, index, card.id(), shown, owner ? "" : seat.name(), card.printingId(), card.finish(), card.faceDown(), card.flipped(), owner);
		}
	}

	private void placeCards(
		GuiGraphics graphics,
		List<TableCard> cards,
		List<String> names,
		List<Integer> indexes,
		int x,
		int y,
		int w,
		Grid grid,
		String zone,
		boolean owner,
		TableSeat seat,
		boolean tail
	) {
		if (grid.use <= 0) {
			return;
		}
		int start = tail ? Math.max(0, indexes.size() - grid.use) : 0;
		int gap = grid.gap;
		for (int i = 0; i < grid.use; i++) {
			int index = indexes.get(start + i);
			if (index < 0 || index >= cards.size()) {
				continue;
			}
			int col = i % grid.cols;
			int line = i / grid.cols;
			int lineCount = Math.min(grid.cols, grid.use - line * grid.cols);
			int lineW = lineCount * grid.cardW + (lineCount - 1) * gap;
			int lineX = x + Math.max(0, (w - lineW) / 2);
			TableCard card = cards.get(index);
			String name = index < names.size() ? names.get(index) : "";
			int cx = lineX + col * (grid.cardW + gap);
			int cy = y + line * (grid.cardH + gap);
			String faceName = name;
			if ((faceName == null || faceName.isEmpty()) && card.token()) {
				faceName = !card.label().isEmpty() ? card.label() : "Token";
			}
			boolean tapped = "field".equals(zone) && card.tapped();
			paint(graphics, card.faceDown(), tapped, card.flipped(), card.printingId(), card.finish(), faceName, cx, cy, grid.cardW, grid.cardH, card.token());
			if ("field".equals(zone)) {
				drawFieldMarkers(graphics, card, cx, cy, grid.cardW, grid.cardH, tapped);
			}
			// Pending control request: bright frame so everyone sees which card is asked for.
			if (seat != null) {
				String want = controlTargets.get(seat.name());
				if (want != null && want.equals(zone + ":" + card.id())) {
					int frame = 0xFFE8C070;
					graphics.fill(cx - 2, cy - 2, cx + grid.cardW + 2, cy, frame);
					graphics.fill(cx - 2, cy + grid.cardH, cx + grid.cardW + 2, cy + grid.cardH + 2, frame);
					graphics.fill(cx - 2, cy, cx, cy + grid.cardH, frame);
					graphics.fill(cx + grid.cardW, cy, cx + grid.cardW + 2, cy + grid.cardH, frame);
					graphics.fill(cx - 1, cy - 1, cx + grid.cardW + 1, cy, 0xFFFFF0C0);
					graphics.fill(cx - 1, cy + grid.cardH, cx + grid.cardW + 1, cy + grid.cardH + 1, 0xFFFFF0C0);
				}
			}
			// Controlled permanent: owner chip (not native to this seat).
			if (card.ownerSeat() != null && !card.ownerSeat().isEmpty()
				&& (seat == null || !card.ownerSeat().equals(seat.name()))) {
				int oc = seatColor(card.ownerSeat());
				graphics.fill(cx + 2, cy + 2, cx + 8, cy + 8, oc);
				graphics.fill(cx + 1, cy + 1, cx + 9, cy + 2, PAPER);
				graphics.fill(cx + 1, cy + 8, cx + 9, cy + 9, PAPER);
			}
			// Eye when someone is peeking this face-down card.
			if (card.faceDown() && seat != null) {
				String peek = peeks.get(seat.name());
				if (peek != null && peek.equals(zone + ":" + card.id())) {
					drawEye(graphics, cx + grid.cardW / 2, cy + 8, seatColor(seat));
				}
			}
			String shown = card.faceDown() ? "Face down" : name;
			if ("field".equals(zone) && card.tapped()) {
				shown = shown == null || shown.isEmpty() ? "Tapped" : shown + " · tapped";
			}
			if ("field".equals(zone) && card.flipped() && !card.faceDown()) {
				shown = shown == null || shown.isEmpty() ? "Flipped" : shown + " · flipped";
			}
			hitCard(cx, cy, grid.cardW, grid.cardH, zone, index, card.id(), shown, owner ? "" : seat.name(), card.printingId(), card.finish(), card.faceDown(), card.flipped(), owner);
			if ("field".equals(zone) && owner && !card.faceDown()) {
				String id = Integer.toString(card.id());
				if (card.loyalty() > 0) {
					// Loyalty mode: thin bottom band (leave most of the card free for tap).
					int band = Math.max(6, grid.cardH / 6);
					hit(cx, cy + grid.cardH - band, grid.cardW, band, "ctr:l", index, "Loyalty  ·  LMB +1 / RMB −1", id, "", "", false, true);
				} else {
					int third = Math.max(1, grid.cardW / 3);
					// Thin strip so right-click tap still hits the card body above.
					int band = Math.max(5, grid.cardH / 7);
					int by = cy + grid.cardH - band;
					hit(cx, by, third, band, "ctr:p", index, "Power  ·  LMB +1 / RMB −1", id, "", "", false, true);
					hit(cx + third, by, third, band, "ctr:b", index, "+1/+1 or −1/−1  ·  LMB / RMB", id, "", "", false, true);
					hit(cx + 2 * third, by, grid.cardW - 2 * third, band, "ctr:t", index, "Toughness  ·  LMB +1 / RMB −1", id, "", "", false, true);
				}
				if (card.markers() > 0) {
					hit(cx + 1, cy + 1, Math.max(12, grid.cardW / 3), 10, "ctr:m", index, "Other counters  ·  LMB +1 / RMB −1", id, "", "", false, true);
				}
			}
		}
	}

	private void drawFieldMarkers(GuiGraphics graphics, TableCard card, int cx, int cy, int cardW, int cardH) {
		drawFieldMarkers(graphics, card, cx, cy, cardW, cardH, false);
	}

	private void drawFieldMarkers(GuiGraphics graphics, TableCard card, int cx, int cy, int cardW, int cardH, boolean tapped) {
		// When tapped the card is drawn rotated; an upright frame looks like a second card outline.
		if (card.token() && !tapped) {
			int frame = 0xFF70D0E8;
			graphics.fill(cx, cy, cx + cardW, cy + 1, frame);
			graphics.fill(cx, cy + cardH - 1, cx + cardW, cy + cardH, frame);
			graphics.fill(cx, cy, cx + 1, cy + cardH, frame);
			graphics.fill(cx + cardW - 1, cy, cx + cardW, cy + cardH, frame);
		}
		if (!card.hasCounters() && card.loyalty() == 0 && !(card.token() && (card.power() != 0 || card.toughness() != 0 || !card.label().isEmpty()))) {
			return;
		}
		int y = cy + 1;
		if (card.power() != 0 || card.toughness() != 0) {
			String pt = signed(card.power()) + "/" + signed(card.toughness());
			int tw = this.font.width(pt);
			graphics.fill(cx + 1, y, cx + tw + 3, y + 9, 0xC0000000);
			graphics.drawString(this.font, pt, cx + 2, y + 1, 0xFFE8F0FF);
			y += 10;
		}
		if (card.loyalty() > 0) {
			String ly = Integer.toString(card.loyalty());
			int tw = this.font.width(ly);
			int lx = cx + cardW - tw - 3;
			graphics.fill(lx, cy + cardH - 10, cx + cardW - 1, cy + cardH - 1, 0xC0000000);
			graphics.drawString(this.font, ly, lx + 1, cy + cardH - 9, 0xFFFFD070);
		}
		if (card.markers() > 0) {
			String mk = "·" + card.markers();
			int tw = this.font.width(mk);
			graphics.fill(cx + 1, y, cx + tw + 3, y + 9, 0xC0000000);
			graphics.drawString(this.font, mk, cx + 2, y + 1, 0xFFC0FFC0);
		}
	}

	private static String signed(int value) {
		return value > 0 ? "+" + value : Integer.toString(value);
	}

	private void drawHand(GuiGraphics graphics, int x, int y, int w, int h) {
		int buttonW = 52;
		int buttonH = 16;
		graphics.fill(x, y, x + buttonW, y + buttonH, 0xFF3A2A22);
		graphics.fill(x, y, x + buttonW, y + 1, GOLD);
		graphics.drawCenteredString(this.font, "Draw", x + buttonW / 2, y + 4, PAPER);
		hit(x, y, buttonW, buttonH, "draw", 0, "Draw the top card", "", "", "", false, true);
		int libX = x;
		int libY = y + 18;
		int libW = Math.min(52, buttonW);
		int libH = Math.max(28, h - 20);
		boolean lookingHere = seat != null && lookers.contains(seat);
		int libFill = lookingHere ? 0xFF3A2818 : 0xFF241610;
		graphics.fill(libX, libY, libX + libW, libY + libH, libFill);
		graphics.fill(libX, libY, libX + libW, libY + 1, lookingHere ? seatColor(seat) : GOLD);
		graphics.drawCenteredString(this.font, Integer.toString(library), libX + libW / 2, libY + Math.max(2, (libH - 8) / 2 - (lookingHere ? 5 : 0)), PAPER);
		if (lookingHere) {
			drawEye(graphics, libX + libW / 2, libY + libH - 8, seatColor(seat));
		}
		hit(libX, libY, libW, libH, "library", 0, "Middle-click for more", "", "", "", false, true);
		int cardsX = x + buttonW + 8;
		int cardsW = w - (cardsX - x);
		int count = hand == null ? 0 : hand.size();
		if (count == 0 || cardsW < 8) {
			if (count == 0) {
				graphics.drawString(this.font, "Hand is empty", cardsX, y + 4, MUTED);
			}
			return;
		}
		int cardH = Math.max(8, h - 2);
		int cardW = Math.max(1, (int) (cardH * 0.716f));
		int gap = 4;
		int step = cardW + gap;
		if (count > 1 && cardW + (count - 1) * step > cardsW) {
			step = Math.max(8, (cardsW - cardW) / (count - 1));
		}
		for (int i = 0; i < count; i++) {
			BinderCard card = hand.get(i);
			int cardX = cardsX + i * step;
			int hitW = i == count - 1 ? cardW : Math.min(cardW, step);
			paint(graphics, false, false, false, card.printingId(), card.finish(), card.name(), cardX, y, cardW, cardH);
			hitCard(cardX, y, hitW, cardH, "hand", i, i < handIds.size() ? handIds.get(i) : 0, card.name(), "", card.printingId(), card.finish(), false, false, true);
		}
	}

	void paint(GuiGraphics graphics, boolean faceDown, boolean tapped, boolean flipped, String printingId, String finish, String name, int x, int y, int w, int h) {
		paint(graphics, faceDown, tapped, flipped, printingId, finish, name, x, y, w, h, false);
	}

	void paint(GuiGraphics graphics, boolean faceDown, boolean tapped, boolean flipped, String printingId, String finish, String name, int x, int y, int w, int h, boolean token) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (!tapped) {
			ink(graphics, faceDown, flipped, printingId, finish, name, x, y, w, h, token);
			return;
		}
		int ph = Math.max(1, w);
		int pw = Math.max(1, (int) (ph * 0.716f));
		graphics.pose().pushPose();
		graphics.pose().translate(x + w / 2.0f, y + h / 2.0f, 0);
		graphics.pose().mulPose(Axis.ZP.rotationDegrees(90.0f));
		ink(graphics, faceDown, flipped, printingId, finish, name, -pw / 2, -ph / 2, pw, ph, token);
		graphics.pose().popPose();
	}

	private void ink(GuiGraphics graphics, boolean faceDown, boolean flipped, String printingId, String finish, String name, int x, int y, int w, int h, boolean token) {
		graphics.fill(x, y, x + w, y + h, 0xFF000000);
		int ix = w > 4 && h > 4 ? x + 1 : x;
		int iy = w > 4 && h > 4 ? y + 1 : y;
		int iw = w > 4 && h > 4 ? w - 2 : w;
		int ih = w > 4 && h > 4 ? h - 2 : h;
		boolean art = false;
		if (!faceDown && printingId != null && !printingId.isEmpty()) {
			if (flipped) {
				art = CardArt.draw(graphics, printingId, finish, ix, iy, iw, ih, true);
			}
			if (!art) {
				art = CardArt.draw(graphics, printingId, finish, ix, iy, iw, ih, false);
			}
		}
		if (!art) {
			int fill = faceDown ? 0xFF3A2A22 : (token ? 0xFF1A3A48 : 0xFF1C1612);
			graphics.fill(ix, iy, ix + iw, iy + ih, fill);
			if (token && !faceDown) {
				// Always readable token face, even on tiny grid cells.
				String top = name == null || name.isEmpty() ? "Token" : name;
				if (top.toLowerCase().contains("(token)")) {
					top = top.replace(" (token)", "").replace(" (Token)", "");
				}
				graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(top, Math.max(4, iw - 2)), ix + iw / 2, iy + Math.max(1, ih / 2 - 4), 0xFFE8F6FF);
			} else if (!faceDown && iw >= 24 && ih >= 12 && name != null && !name.isEmpty()) {
				graphics.drawString(this.font, this.font.plainSubstrByWidth(name, iw - 4), ix + 2, iy + 3, TEXT);
			}
		} else if (token) {
			// Real-art token copy: thin cyan edge so it still reads as a token.
			graphics.fill(ix, iy, ix + iw, iy + 1, 0xFF70D0E8);
			graphics.fill(ix, iy + ih - 1, ix + iw, iy + ih, 0xFF70D0E8);
			graphics.fill(ix, iy, ix + 1, iy + ih, 0xFF70D0E8);
			graphics.fill(ix + iw - 1, iy, ix + iw, iy + ih, 0xFF70D0E8);
		}
	}

	private void drawLife(GuiGraphics graphics, TableSeat seat, int life, int x, int y, int w, int h, boolean large) {
		graphics.fill(x, y, x + w, y + h, 0xF0100806);
		graphics.fill(x, y, x + w, y + 1, GOLD);
		graphics.fill(x, y + h - 1, x + w, y + h, GOLD);
		String text = Integer.toString(life);
		if (large) {
			graphics.pose().pushPose();
			graphics.pose().translate(x + w / 2.0f, y + h / 2.0f, 0);
			graphics.pose().scale(1.5f, 1.5f, 1.0f);
			graphics.drawCenteredString(this.font, text, 0, -4, PAPER);
			graphics.pose().popPose();
		} else {
			graphics.drawCenteredString(this.font, text, x + w / 2, y + (h - 8) / 2, PAPER);
		}
		hit(x, y, w, h, "life", 0, "Life  ·  left +1, right −1, shift ±5", seat.name(), "", "", false, true);
		// Commander damage: inverted triangle to the LEFT of the life badge.
		int tx = x - 13;
		int ty = y + Math.max(0, (h - 10) / 2);
		int tw = 10;
		int th = 10;
		int tri = 0xFFE8A040;
		graphics.fill(tx + 4, ty + 8, tx + 6, ty + 9, tri);
		graphics.fill(tx + 3, ty + 6, tx + 7, ty + 8, tri);
		graphics.fill(tx + 2, ty + 4, tx + 8, ty + 6, tri);
		graphics.fill(tx + 1, ty + 2, tx + 9, ty + 4, tri);
		graphics.fill(tx, ty, tx + 10, ty + 2, tri);
		TableBlockEntity table = table();
		if (table != null) {
			int totalCmd = 0;
			for (int v : table.zones(seat).commanderDamage().values()) {
				totalCmd += v;
			}
			if (totalCmd > 0) {
				String cd = Integer.toString(totalCmd);
				int cw = this.font.width(cd);
				graphics.drawString(this.font, cd, tx - cw - 2, ty + 1, 0xFFE8A040);
			}
		}
		hit(tx - 8, ty, tw + 10, th, "cmddmg", 0, "Commander damage", seat.name(), "", "", false, true);
	}

	private void drawZoom(GuiGraphics graphics, Click click, int mouseX, int mouseY) {
		int base = Math.min(380, Math.max(180, this.height - 32));
		int zh = Math.min(this.height - 16, Math.max(64, (int) (base * zoom)));
		int zw = Math.max(40, (int) (zh * 0.716f));
		int zx = mouseX + 16;
		int zy = mouseY - zh / 3;
		if (zx + zw > this.width - 8) {
			zx = mouseX - zw - 16;
		}
		if (zx < 8) {
			zx = 8;
		}
		if (zy < 8) {
			zy = 8;
		}
		if (zy + zh > this.height - 8) {
			zy = this.height - zh - 8;
		}
		// Above zone titles, tray labels, hover, and the middle-click menu.
		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, 0.0f, 400.0f);
		graphics.fill(zx - 4, zy - 4, zx + zw + 4, zy + zh + 4, 0xFF100908);
		graphics.fill(zx - 4, zy - 4, zx + zw + 4, zy - 2, GOLD);
		// Alt alone shows the face everyone sees. Alt+Shift peeks the real face (face-down or the other side of a flip).
		boolean peek = hasShiftDown();
		boolean hideFace = click.faceDown && !peek;
		boolean useBack = click.flipped && !peek && !click.faceDown;
		boolean art = false;
		if (!hideFace) {
			if (useBack) {
				art = CardArt.draw(graphics, click.printingId, click.finish, zx, zy, zw, zh, true);
			}
			if (!art) {
				art = CardArt.draw(graphics, click.printingId, click.finish, zx, zy, zw, zh, false);
			}
		}
		if (!art) {
			graphics.fill(zx, zy, zx + zw, zy + zh, hideFace ? 0xFF3A2A22 : 0xFF1C1612);
			String title = hideFace ? "Face down" : click.name;
			graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(title, zw - 12), zx + zw / 2, zy + zh / 2 - 4, TEXT);
		}
		if (!hideFace && "field".equals(click.zone) && click.cardId > 0) {
			TableCard zoomCard = fieldCardById(click.cardId, click);
			if (zoomCard != null && (zoomCard.hasCounters() || zoomCard.loyalty() > 0 || zoomCard.token())) {
				int line = zy + 6;
				if (zoomCard.token()) {
					graphics.fill(zx + 4, line, zx + 4 + this.font.width("TOKEN") + 4, line + 10, 0xC0103040);
					graphics.drawString(this.font, "TOKEN", zx + 6, line + 1, 0xFF70D0E8);
					line += 12;
				}
				if (zoomCard.power() != 0 || zoomCard.toughness() != 0) {
					String pt = signed(zoomCard.power()) + "/" + signed(zoomCard.toughness());
					graphics.fill(zx + 4, line, zx + 4 + this.font.width(pt) + 4, line + 10, 0xC0000000);
					graphics.drawString(this.font, pt, zx + 6, line + 1, 0xFFE8F0FF);
					line += 12;
				}
				if (zoomCard.loyalty() > 0) {
					String ly = "Loyalty " + zoomCard.loyalty();
					graphics.fill(zx + 4, line, zx + 4 + this.font.width(ly) + 4, line + 10, 0xC0000000);
					graphics.drawString(this.font, ly, zx + 6, line + 1, 0xFFFFD070);
					line += 12;
				}
				if (zoomCard.markers() > 0) {
					String mk = "Other ×" + zoomCard.markers();
					graphics.fill(zx + 4, line, zx + 4 + this.font.width(mk) + 4, line + 10, 0xC0000000);
					graphics.drawString(this.font, mk, zx + 6, line + 1, 0xFFC0FFC0);
				}
			}
		}
		String level = Math.round(zoom * 100.0f) + "%";
		if (peek && (click.faceDown || click.flipped)) {
			level = "Peek  ·  " + level;
		}
		graphics.drawString(this.font, level, zx + 2, zy + zh - 12, MUTED);
		graphics.pose().popPose();
	}

	static boolean shiftHeld() {
		if (hasShiftDown()) {
			return true;
		}
		var client = net.minecraft.client.Minecraft.getInstance();
		if (client == null || client.getWindow() == null) {
			return false;
		}
		long window = client.getWindow().getWindow();
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)
			|| com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	boolean showing() {
		TableBlockEntity table = table();
		TableSeat mine = seatOf(seat);
		return table != null && mine != null && table.zones(mine).revealing();
	}

	private int choosePrefer(int[] counts, int w, int h, boolean compact) {
		int prefer = compact ? 48 : 100;
		int min = 10;
		while (prefer > min && stackHeight(counts, w, prefer, compact) > h) {
			prefer -= 4;
		}
		return prefer;
	}

	private int stackHeight(int[] counts, int w, int prefer, boolean compact) {
		int gap = compact ? 2 : 4;
		int sum = 0;
		int seen = 0;
		for (int count : counts) {
			int height = rowHeight(count, w, prefer, compact);
			if (height <= 0) {
				continue;
			}
			sum += height;
			seen++;
		}
		if (seen > 1) {
			sum += gap * (seen - 1);
		}
		return sum;
	}

	private int rowHeight(int count, int w, int prefer, boolean compact) {
		if (count <= 0) {
			return 0;
		}
		Grid grid = grid(count, w, prefer, compact);
		int chrome = compact ? 0 : 12;
		return chrome + grid.lines * grid.cardH + Math.max(0, grid.lines - 1) * grid.gap;
	}

	private Grid grid(int count, int w, int prefer, boolean compact) {
		int use = count;
		if (use <= 0 || w <= 0) {
			return new Grid(1, 1, 1, 0, 0, 3);
		}
		int cardH = Math.max(1, prefer);
		int cardW = Math.max(1, (int) (cardH * 0.716f));
		if (cardW > w) {
			cardW = Math.max(1, w);
			cardH = Math.max(1, (int) (cardW / 0.716f));
		}
		int gap = compact ? 2 : 3;
		int cols = Math.max(1, (w + gap) / (cardW + gap));
		int lines = (use + cols - 1) / cols;
		return new Grid(cardW, cardH, cols, lines, use, gap);
	}

	private static ArrayList<Integer> ofRole(List<String> roles, int size, String role) {
		ArrayList<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			String have = i < roles.size() ? roles.get(i) : "";
			if (have == null || have.isEmpty()) {
				have = "other";
			}
			if (role.equals(have)) {
				indexes.add(i);
			}
		}
		return indexes;
	}

	private static String rowTitle(String role) {
		return switch (role) {
			case "land" -> "Lands";
			case "creature" -> "Creatures";
			case "down" -> "Face down";
			default -> "Spells";
		};
	}

	private int libraryOf(TableBlockEntity table, TableSeat seat, boolean mine) {
		if (mine) {
			return library;
		}
		if (table == null) {
			return 0;
		}
		ItemStack stack = table.get(seat);
		if (stack.isEmpty()) {
			return 0;
		}
		return stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty()).librarySize();
	}

	private int lifeOf(TableBlockEntity table, TableSeat seat) {
		if (table == null) {
			return 20;
		}
		return table.zones(seat).shownLife(table.get(seat));
	}

	boolean occupied(TableBlockEntity table, TableSeat seat) {
		return table != null && seat != null && !table.get(seat).isEmpty();
	}

	TableBlockEntity table() {
		if (this.minecraft == null || this.minecraft.level == null || pos == null) {
			return null;
		}
		if (this.minecraft.level.getBlockEntity(pos) instanceof TableBlockEntity table) {
			return table;
		}
		return null;
	}

	static TableSize sizeOf(TableBlockEntity table) {
		if (table == null) {
			return null;
		}
		BlockState state = table.getBlockState();
		if (!(state.getBlock() instanceof TableBlock)) {
			return null;
		}
		return state.getValue(TableBlock.SIZE);
	}

	/** A 1×1 has one real seat. Larger tables have a seat on every side that fits. */
	boolean exists(TableBlockEntity table, TableSize size, TableSeat seat, TableSeat mine) {
		if (seat == null) {
			return false;
		}
		if (size == null) {
			return seat == mine;
		}
		if (!TableSeat.fits(size, seat)) {
			return false;
		}
		if (size == TableSize.SINGLE) {
			return seat == mine || occupied(table, seat);
		}
		return true;
	}

	private Click top(double mouseX, double mouseY) {
		for (int i = clicks.size() - 1; i >= 0; i--) {
			Click click = clicks.get(i);
			if (inside(mouseX, mouseY, click.x, click.y, click.w, click.h)) {
				return click;
			}
		}
		return null;
	}

	private void parseSitters(String raw) {
		if (raw == null || raw.isEmpty()) {
			return;
		}
		for (String part : raw.split(";")) {
			int eq = part.indexOf('=');
			if (eq <= 0 || eq >= part.length() - 1) {
				continue;
			}
			sitters.put(part.substring(0, eq), part.substring(eq + 1));
		}
	}

	private void parseLookers(String raw) {
		if (raw == null || raw.isEmpty()) {
			return;
		}
		for (String part : raw.split(";")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			part = part.trim();
			if (part.startsWith("CTRL:")) {
				// CTRL:owner:zone:index:asker
				String[] bits = part.substring(5).split(":", 4);
				if (bits.length >= 3) {
					controlTargets.put(bits[0], bits[1] + ":" + bits[2]);
				}
				continue;
			}
			int at = part.indexOf('@');
			if (at > 0) {
				peeks.put(part.substring(0, at), part.substring(at + 1));
				lookers.add(part.substring(0, at));
			} else {
				lookers.add(part);
			}
		}
	}

	private void apply(HandSyncS2CPayload payload) {
		if (this.pos == null || payload.pos() == null || !this.pos.equals(payload.pos())) {
			revealed.clear();
		}
		this.pos = payload.pos();
		this.seat = payload.seat();
		this.library = payload.library();
		this.hand = payload.hand();
		this.handIds = payload.handIds();
		this.status = payload.status();
		sitters.clear();
		parseSitters(payload.sitters());
		lookers.clear();
		peeks.clear();
		controlTargets.clear();
		parseLookers(payload.lookers());
	}

	public void reveal(HandRevealS2CPayload payload) {
		if (pos == null || payload.pos() == null || !pos.equals(payload.pos())) {
			return;
		}
		if (seat != null && seat.equals(payload.seat())) {
			return;
		}
		if (payload.audience() == null || payload.audience().isEmpty()) {
			revealed.remove(payload.seat());
		} else {
			revealed.put(payload.seat(), payload.cards());
		}
	}

	/** Small pixel eye — seat color fill with a dark pupil. */
	private void drawEye(GuiGraphics graphics, int cx, int cy, int color) {
		// Pattern (centered on cx,cy), ~12x8:
		// ▄▄██████▄▄ / ▄██▀ ██ ▀██▄ / ▀██▄ ██ ▄██▀ / ▀▀██████▀▀
		int x = cx - 6;
		int y = cy - 4;
		graphics.fill(x + 2, y, x + 10, y + 1, color);
		graphics.fill(x + 1, y + 1, x + 11, y + 2, color);
		graphics.fill(x, y + 2, x + 12, y + 6, color);
		graphics.fill(x + 1, y + 6, x + 11, y + 7, color);
		graphics.fill(x + 2, y + 7, x + 10, y + 8, color);
		// pupil
		graphics.fill(x + 5, y + 2, x + 7, y + 6, 0xFF100908);
		graphics.fill(x + 4, y + 3, x + 8, y + 5, 0xFF100908);
	}

	void closeCountPrompt() {
		prompt.typingKind = null;
		prompt.countBox = null;
		this.setFocused(null);
	}

	private void reportPeek(Click click) {
		if (click == null || !click.faceDown || !click.owner) {
			clearPeek();
			return;
		}
		if (!hasShiftDown() && !shiftHeld()) {
			clearPeek();
			return;
		}
		String key = click.zone + ":" + click.cardId;
		if (key.equals(lastPeekSent)) {
			return;
		}
		lastPeekSent = key;
		send("peek:" + key, 0);
	}

	private void clearPeek() {
		if (lastPeekSent == null) {
			return;
		}
		lastPeekSent = null;
		send("peek:off", 0);
	}

	/** The permanent with this table id on the given seat, or null. */
	private TableCard fieldCardById(int cardId) {
		return fieldCardById(cardId, null);
	}

	private TableCard fieldCardById(int cardId, Click click) {
		TableBlockEntity table = table();
		if (table == null || seat == null || cardId <= 0) {
			return null;
		}
		TableSeat target = seatOf(seat);
		if (click != null && !click.owner && click.extra != null && !click.extra.isEmpty()) {
			TableSeat other = seatOf(click.extra);
			if (other != null) {
				target = other;
			}
		}
		if (target == null) {
			return null;
		}
		int index = table.zones(target).indexOfId(TableZone.FIELD, cardId);
		if (index < 0) {
			return null;
		}
		return table.zones(target).battlefield().get(index);
	}

	/** An action on one card, named by its id. Use 0 for actions that are about no card. */
	void send(String action, int cardId) {
		send(action, cardId, 0);
	}

	void send(String action, int cardId, int amount) {
		if (pos == null || seat == null || !ClientPlayNetworking.canSend(TableActionC2SPayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new TableActionC2SPayload(pos, seat, action, cardId, amount));
	}

	void hit(int x, int y, int w, int h, String zone, int index, String name, String extra, String printingId, String finish, boolean faceDown, boolean owner) {
		hit(x, y, w, h, zone, index, name, extra, printingId, finish, faceDown, false, owner);
	}

	void hit(int x, int y, int w, int h, String zone, int index, String name, String extra, String printingId, String finish, boolean faceDown, boolean flipped, boolean owner) {
		hitCard(x, y, w, h, zone, index, 0, name, extra, printingId, finish, faceDown, flipped, owner);
	}

	/** A hit area for a card. The id is what the server is told, so it must be the card's own id. */
	private void hitCard(int x, int y, int w, int h, String zone, int index, int cardId, String name, String extra, String printingId, String finish, boolean faceDown, boolean flipped, boolean owner) {
		if (w <= 0 || h <= 0) {
			return;
		}
		clicks.add(new Click(
			x,
			y,
			w,
			h,
			zone,
			index,
			cardId,
			name == null ? "" : name,
			extra == null ? "" : extra,
			printingId == null ? "" : printingId,
			finish == null ? "" : finish,
			faceDown,
			flipped,
			owner
		));
	}

	private static boolean isCard(Click click) {
		return "hand".equals(click.zone) || "field".equals(click.zone) || "cmd".equals(click.zone) || "grave".equals(click.zone) || "exile".equals(click.zone) || "peek".equals(click.zone) || "scrycard".equals(click.zone);
	}

	static TableSeat seatOf(String name) {
		try {
			return TableSeat.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static TableSeat opposite(TableSeat seat) {
		return switch (seat) {
			case SOUTH -> TableSeat.NORTH;
			case NORTH -> TableSeat.SOUTH;
			case WEST -> TableSeat.EAST;
			case EAST -> TableSeat.WEST;
		};
	}

	static TableSeat leftOf(TableSeat seat) {
		return switch (seat) {
			case SOUTH -> TableSeat.WEST;
			case NORTH -> TableSeat.EAST;
			case WEST -> TableSeat.NORTH;
			case EAST -> TableSeat.SOUTH;
		};
	}

	static TableSeat rightOf(TableSeat seat) {
		return switch (seat) {
			case SOUTH -> TableSeat.EAST;
			case NORTH -> TableSeat.WEST;
			case WEST -> TableSeat.SOUTH;
			case EAST -> TableSeat.NORTH;
		};
	}

	/** Stable color chip per seat so everyone can tell who is looking. */
	private static int seatColor(TableSeat seat) {
		if (seat == null) {
			return 0xFF888888;
		}
		return switch (seat) {
			case NORTH -> 0xFFE74C3C;
			case EAST -> 0xFFF1C40F;
			case SOUTH -> 0xFF2ECC71;
			case WEST -> 0xFF3498DB;
		};
	}

	private static int seatColor(String name) {
		try {
			return seatColor(TableSeat.valueOf(name));
		} catch (Exception e) {
			return 0xFF888888;
		}
	}

	/** Package helper for context menu color chips. */
	static int seatColorPublic(String name) {
		return seatColor(name);
	}

	String label(TableSeat seat) {
		if (seat == null) {
			return "";
		}
		String player = sitters.get(seat.name());
		if (player != null && !player.isEmpty()) {
			return player;
		}
		String name = seat.name().toLowerCase();
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private record Grid(int cardW, int cardH, int cols, int lines, int use, int gap) {
	}
}
