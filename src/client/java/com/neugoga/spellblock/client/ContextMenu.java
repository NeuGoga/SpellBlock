package com.neugoga.spellblock.client;

import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableCard;
import com.neugoga.spellblock.table.TableSeat;
import com.neugoga.spellblock.table.TableSize;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;

import static com.neugoga.spellblock.client.TableScreen.GOLD;
import static com.neugoga.spellblock.client.TableScreen.MUTED;
import static com.neugoga.spellblock.client.TableScreen.PAPER;
import static com.neugoga.spellblock.client.TableScreen.leftOf;
import static com.neugoga.spellblock.client.TableScreen.opposite;
import static com.neugoga.spellblock.client.TableScreen.rightOf;
import static com.neugoga.spellblock.client.TableScreen.seatOf;
import static com.neugoga.spellblock.client.TableScreen.sizeOf;

/**
 * The right-click menu for a card, a library, or a seat, with its sub-menus.
 */
final class ContextMenu {
	private final TableScreen screen;

	ContextMenu(TableScreen screen) {
		this.screen = screen;
	}

	String menuZone;
	/** The card the open menu is about. An id, so the menu stays right if the lists change under it. */
	int menuCardId;
	int menuX;
	int menuY;
	/** Face-down state of the card that opened the middle-click menu. */
	boolean menuFaceDown;
	/** True when the middle-clicked card is on your seat. */
	boolean menuOwner = true;
	/** When middle-clicking another seat's card, that seat's name (from click.extra). */
	String menuExtra = "";
	/** True when the middle-clicked field card has any counters. */
	boolean menuHasCounters;
	/** Deck-related Scryfall tokens for the empty-field create menu. */
	java.util.List<String> tokenLabels = java.util.List.of();
	java.util.List<String> tokenActions = java.util.List.of();

	void drawMenu(GuiGraphics graphics) {

		if (menuZone == null) {
			return;
		}
		ArrayList<String> labels = new ArrayList<>();
		ArrayList<String> actions = new ArrayList<>();
		ArrayList<Boolean> headers = new ArrayList<>();
		if (menuZone != null && menuZone.startsWith("pick:")) {
			String kind = menuZone.substring(5);
			if (kind.startsWith("cmd:")) {
				String targetSeat = kind.substring(4);
				labels.add("Commander damage");
				actions.add("");
				headers.add(true);
				TableBlockEntity tableCmd = screen.table();
				TableSeat target = seatOf(targetSeat);
				TableSeat mine = seatOf(screen.seat);
				if (tableCmd != null && target != null) {
					TableSize sizeCmd = sizeOf(tableCmd);
					for (TableSeat other : TableSeat.values()) {
						if (other == target) {
							continue;
						}
						if (!screen.exists(tableCmd, sizeCmd, other, mine != null ? mine : target) || !screen.occupied(tableCmd, other)) {
							continue;
						}
						int have = tableCmd.zones(target).commanderDamageFrom(other.name());
						String label = screen.label(other) + "  (" + have + ")";
						labels.add(label);
						// sticky menu actions use cmd: target|from
						actions.add("cmd:" + targetSeat + "|" + other.name());
						headers.add(false);
					}
				}
				if (labels.size() <= 1) {
					labels.add("(No opponents)");
					actions.add("");
					headers.add(false);
				}
			} else if (kind.startsWith("setlife:")) {
				String targetSeat = kind.substring(8);
				labels.add("Set life");
				actions.add("");
				headers.add(true);
				labels.add("20");
				actions.add("setlife:" + targetSeat + ":20");
				headers.add(false);
				labels.add("40");
				actions.add("setlife:" + targetSeat + ":40");
				headers.add(false);
				labels.add("Custom…");
				actions.add("type:setlife:" + targetSeat);
				headers.add(false);
			} else if ("asksearch".equals(kind) || "revealhand".equals(kind) || "revealcard".equals(kind)) {
				String title = switch (kind) {
					case "asksearch" -> "Search whose library?";
					case "revealhand" -> "Show hand to";
					default -> "Show card to";
				};
				labels.add(title);
				actions.add("");
				headers.add(true);
				if (!"asksearch".equals(kind)) {
					labels.add("Everyone");
					actions.add("revealcard".equals(kind) ? "rcard:ALL" : "reveal:ALL");
					headers.add(false);
					if (screen.showing()) {
						labels.add("Hide");
						actions.add("reveal:off");
						headers.add(false);
					}
				}
				TableBlockEntity tableAsk = screen.table();
				TableSeat mineAsk = seatOf(screen.seat);
				if (tableAsk != null && mineAsk != null) {
					TableSize sizeAsk = sizeOf(tableAsk);
					for (TableSeat other : new TableSeat[] {leftOf(mineAsk), opposite(mineAsk), rightOf(mineAsk)}) {
						if (!screen.exists(tableAsk, sizeAsk, other, mineAsk) || !screen.occupied(tableAsk, other)) {
							continue;
						}
						labels.add(screen.label(other));
						if ("asksearch".equals(kind)) {
							actions.add("asksearch:" + other.name());
						} else if ("revealcard".equals(kind)) {
							actions.add("rcard:" + other.name());
						} else {
							actions.add("reveal:" + other.name());
						}
						headers.add(false);
					}
				}
			} else {
				String title = switch (kind) {
					case "drawn" -> "Draw";
					case "scry" -> "Scry";
					case "surv" -> "Surveil";
					case "mill" -> "Mill";
					default -> "Count";
				};
				labels.add(title);
				actions.add("");
				headers.add(true);
				labels.add("3");
				actions.add(kind + ":3");
				headers.add(false);
				labels.add("5");
				actions.add(kind + ":5");
				headers.add(false);
				labels.add("Custom");
				actions.add("type:" + kind);
				headers.add(false);
			}
		} else if ("library".equals(menuZone)) {
			// Deck section (shuffle, search, etc. later)
			labels.add("Shuffle");
			actions.add("shuffle");
			headers.add(false);
			labels.add("Search library");
			actions.add("search");
			headers.add(false);
			labels.add("Request search");
			actions.add("pick:asksearch");
			headers.add(false);
			if (screen.status != null && screen.status.contains("wants to search")) {
				labels.add("Allow search");
				actions.add("allowsearch:");
				headers.add(false);
				labels.add("Deny search");
				actions.add("denysearch");
				headers.add(false);
			}
			labels.add("");
			actions.add("");
			headers.add(true);
			labels.add("Draw 2");
			actions.add("drawn:2");
			headers.add(false);
			labels.add("Draw X");
			actions.add("pick:drawn");
			headers.add(false);
			labels.add("");
			actions.add("");
			headers.add(true);
			labels.add("Scry 1");
			actions.add("scry:1");
			headers.add(false);
			labels.add("Scry 2");
			actions.add("scry:2");
			headers.add(false);
			labels.add("Scry X");
			actions.add("pick:scry");
			headers.add(false);
			labels.add("");
			actions.add("");
			headers.add(true);
			labels.add("Surveil 1");
			actions.add("surv:1");
			headers.add(false);
			labels.add("Surveil X");
			actions.add("pick:surv");
			headers.add(false);
			labels.add("");
			actions.add("");
			headers.add(true);
			labels.add("Mill 1");
			actions.add("mill:1");
			headers.add(false);
			labels.add("Mill X");
			actions.add("pick:mill");
			headers.add(false);
		} else {
			if (menuOwner) {
				labels.add("Library");
				actions.add("");
				headers.add(true);
				labels.add("Top of library");
				actions.add("top:" + menuZone);
				headers.add(false);
				labels.add("2nd from top");
				actions.add("nth:" + menuZone);
				headers.add(false);
				labels.add("Nth from top…");
				actions.add("type:nth:" + menuZone);
				headers.add(false);
				labels.add("Bottom of library");
				actions.add("bot:" + menuZone);
				headers.add(false);
			}
			if ("hand".equals(menuZone)) {
				labels.add("Play");
				actions.add("");
				headers.add(true);
				labels.add("Play face down");
				actions.add("down:hand");
				headers.add(false);
				labels.add("Reveal");
				actions.add("");
				headers.add(true);
				labels.add("Show hand");
				actions.add("pick:revealhand");
				headers.add(false);
				labels.add("Show this card");
				actions.add("pick:revealcard");
				headers.add(false);
				if (screen.showing()) {
					labels.add("Hide");
					actions.add("reveal:off");
					headers.add(false);
				}
			}
			if ("field".equals(menuZone)) {
				if (menuOwner) {
					labels.add("Face");
					actions.add("");
					headers.add(true);
					if (menuFaceDown) {
						labels.add("Turn face up");
						actions.add("up:field");
					} else {
						labels.add("Turn face down");
						actions.add("down:field");
					}
					headers.add(false);
					labels.add("Flip / transform");
					actions.add("flip:field");
					headers.add(false);
				} else if (menuExtra != null && !menuExtra.isEmpty()) {
					labels.add("Control");
					actions.add("");
					headers.add(true);
					labels.add("Request control");
					actions.add("ac:" + menuExtra + ":f");
					headers.add(false);
				}
			}
			if ("grave".equals(menuZone) || "exile".equals(menuZone)) {
				if (menuOwner) {
					labels.add("Move");
					actions.add("");
					headers.add(true);
					labels.add("To battlefield");
					actions.add(("grave".equals(menuZone) ? "grave" : "exile") + "field");
					headers.add(false);
				} else if (menuExtra != null && !menuExtra.isEmpty()) {
					labels.add("Control");
					actions.add("");
					headers.add(true);
					labels.add("Request control");
					actions.add("ac:" + menuExtra + ":" + ("grave".equals(menuZone) ? "g" : "e"));
					headers.add(false);
				}
			}
			if ("cmd".equals(menuZone) && menuOwner) {
				labels.add("Command");
				actions.add("");
				headers.add(true);
				labels.add("Cast to battlefield");
				actions.add("cmdplay");
				headers.add(false);
				labels.add("To hand");
				actions.add("cmdhand");
				headers.add(false);
			}
			if ("field".equals(menuZone) && menuOwner) {
				if (isDeclaredCommander(menuCardId)) {
					labels.add("Move");
					actions.add("");
					headers.add(true);
					labels.add("To command zone");
					actions.add("fieldcmd");
					headers.add(false);
				}
				labels.add("Counters");
				actions.add("");
				headers.add(true);
				labels.add("Add loyalty");
				actions.add("cl");
				headers.add(false);
				labels.add("Add other counter");
				actions.add("cm");
				headers.add(false);
				if (menuHasCounters) {
					labels.add("Remove all counters");
					actions.add("cr");
					headers.add(false);
				}
				labels.add("Tokens");
				actions.add("");
				headers.add(true);
				labels.add("Make a token copy");
				actions.add("tokcopy");
				headers.add(false);
			}
			if ("fieldempty".equals(menuZone) && menuOwner) {
				labels.add("Battlefield");
				actions.add("");
				headers.add(true);
				labels.add("Untap all");
				actions.add("untap:all");
				headers.add(false);
				labels.add("Untap lands");
				actions.add("untap:land");
				headers.add(false);
				labels.add("Untap creatures");
				actions.add("untap:creature");
				headers.add(false);
				if (screen.status != null && screen.status.contains("wants to take a card")) {
					labels.add("Allow take permanent");
					actions.add("allowctrl:");
					headers.add(false);
					labels.add("Deny take permanent");
					actions.add("denyctrl");
					headers.add(false);
				}
				labels.add("Create token");
				actions.add("");
				headers.add(true);
				if (tokenLabels != null) {
					for (int i = 0; i < tokenLabels.size() && i < tokenActions.size(); i++) {
						labels.add(tokenLabels.get(i));
						actions.add(tokenActions.get(i));
						headers.add(false);
					}
				}
				if (tokenLabels == null || tokenLabels.isEmpty()) {
					labels.add("(No deck tokens yet — reindex oracle)");
					actions.add("");
					headers.add(false);
				}
				labels.add("Generic");
				actions.add("");
				headers.add(true);
				labels.add("1/1");
				actions.add("token:1/1");
				headers.add(false);
				labels.add("2/2");
				actions.add("token:2/2");
				headers.add(false);
				labels.add("3/3");
				actions.add("token:3/3");
				headers.add(false);
				labels.add("5/5");
				actions.add("token:5/5");
				headers.add(false);
				labels.add("0/0 (blank)");
				actions.add("token:0/0");
				headers.add(false);
			}
		}
		int rowH = 16;
		int mw = 140;
		int mh = labels.size() * rowH + 4;
		int x = menuX;
		int y = menuY;
		// X-count submenus open mid-board (near lands), not glued to the cursor at the bottom.
		if (menuZone != null && menuZone.startsWith("pick:")) {
			int handH = screen.hand == null || screen.hand.isEmpty() ? 52 : Math.min(100, Math.max(74, screen.height / 6));
			y = screen.height - handH - mh - 28;
			if (y < 40) {
				y = 40;
			}
		}
		if (x + mw > screen.width - 4) {
			x = screen.width - mw - 4;
		}
		if (y + mh > screen.height - 4) {
			y = screen.height - mh - 4;
		}
		if (x < 4) {
			x = 4;
		}
		if (y < 4) {
			y = 4;
		}
		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, 0.0f, 300.0f);
		graphics.fill(x, y, x + mw, y + mh, 0xFF18100C);
		graphics.fill(x, y, x + mw, y + 1, GOLD);
		for (int i = 0; i < labels.size(); i++) {
			int rowY = y + 2 + i * rowH;
			boolean header = headers.get(i);
			boolean active = !header && actions.get(i) != null && !actions.get(i).isEmpty();
			String label = labels.get(i);
			if (header && (label == null || label.isEmpty())) {
				graphics.fill(x + 4, rowY + rowH / 2 - 1, x + mw - 4, rowY + rowH / 2 + 2, 0xFFD4B484);
			} else if (header) {
				graphics.fill(x + 2, rowY + rowH - 1, x + mw - 2, rowY + rowH, 0xFF3A2A22);
				graphics.drawString(screen.uiFont(), label, x + 6, rowY + 4, GOLD);
			} else {
				int color = active ? PAPER : MUTED;
				int textX = x + 6;
				String action = actions.get(i);
				if (active && action != null && action.startsWith("cmd:")) {
					// cmd:TARGET|FROM — color by the dealing seat
					String from = action;
					int bar = action.indexOf('|');
					if (bar >= 0) {
						from = action.substring(bar + 1);
					}
					int chip = com.neugoga.spellblock.client.TableScreen.seatColorPublic(from);
					graphics.fill(x + 4, rowY + 4, x + 10, rowY + 12, chip);
					graphics.fill(x + 3, rowY + 3, x + 11, rowY + 4, PAPER);
					graphics.fill(x + 3, rowY + 12, x + 11, rowY + 13, PAPER);
					textX = x + 14;
				}
				graphics.drawString(screen.uiFont(), label, textX, rowY + 4, color);
			}
			if (active) {
				screen.hit(x, rowY, mw, rowH, "menu", menuCardId, label, actions.get(i), "", "", false, true);
			}
		}
		graphics.pose().popPose();
	}
	/** True when this field card is one of the commanders listed on the seat's deck box. */
	private boolean isDeclaredCommander(int cardId) {
		if (cardId <= 0) {
			return false;
		}
		TableBlockEntity table = screen.table();
		if (table == null || screen.seat == null || screen.seat.isEmpty()) {
			return false;
		}
		TableSeat seat = seatOf(screen.seat);
		if (seat == null) {
			return false;
		}
		TableCard card = null;
		for (TableCard c : table.zones(seat).battlefield()) {
			if (c.id() == cardId) {
				card = c;
				break;
			}
		}
		if (card == null || card.token() || card.oracleId().isEmpty()) {
			return false;
		}
		ItemStack box = table.get(seat);
		if (box == null || box.isEmpty()) {
			return false;
		}
		DeckBoxData data = box.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		String oracle = card.oracleId();
		for (var entry : data.commanders()) {
			if (oracle.equals(entry.key().oracleId())) {
				return true;
			}
		}
		return false;
	}

}
