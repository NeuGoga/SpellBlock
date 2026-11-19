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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import static com.neugoga.spellblock.client.TableScreen.GOLD;
import static com.neugoga.spellblock.client.TableScreen.MUTED;
import static com.neugoga.spellblock.client.TableScreen.PAPER;
import static com.neugoga.spellblock.client.TableScreen.shiftHeld;

/**
 * The scry, surveil, and search panel: lays out the cards you are looking at and sends your choice when you finish.
 */
final class ScryPanel {
	private final TableScreen screen;

	ScryPanel(TableScreen screen) {
		this.screen = screen;
	}

	/** Active scry/surveil. Null when not looking. */
	String scryMode;
	/** Seat whose library is being looked at (may differ from your seat). */
	String scrySeat;
	List<BinderCard> scryCards;
	final ArrayList<Integer> scryTop = new ArrayList<>();
	final ArrayList<Integer> scryLook = new ArrayList<>();
	final ArrayList<Integer> scryBottom = new ArrayList<>();
	final ArrayList<Integer> scryGrave = new ArrayList<>();
	/** Scroll offset in pixels per row ("top", "look", "bottom", "grave"). */
	final Map<String, Integer> rowScrolls = new HashMap<>();
	private final Map<String, RowBar> rowBars = new HashMap<>();
	private String draggingRow;
	private int dragOffsetX;
	/** When true the look panel is tucked away so you can see the boards. */
	boolean scryHidden;

	void drawScry(GuiGraphics graphics) {
		rowBars.clear();
		if (scryCards == null || scryMode == null) {
			return;
		}
		int tabBottom = 22;
		int handTop = screen.height - (screen.hand == null || screen.hand.isEmpty() ? 52 : Math.min(100, Math.max(74, screen.height / 6))) - 6;
		int boxX = 8;
		int boxY = tabBottom;
		int boxW = screen.width - 16;
		int boxH = Math.max(80, handTop - tabBottom - 4);
		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, 0.0f, 350.0f);
		boolean surveil = "surveil".equals(scryMode);
		boolean search = "search".equals(scryMode);
		String title = search ? "Search" : (surveil ? "Surveil" : "Scry");
		int hideW = 88;
		int hideH = 16;
		int hideX = boxX + boxW - hideW - 8;
		int hideY = boxY + boxH - hideH - 8;
		if (scryHidden) {
			graphics.fill(hideX, hideY, hideX + hideW, hideY + hideH, 0xFF3A2A22);
			graphics.fill(hideX, hideY, hideX + hideW, hideY + 1, GOLD);
			graphics.drawCenteredString(screen.uiFont(), "Show " + title, hideX + hideW / 2, hideY + 4, PAPER);
			screen.hit(hideX, hideY, hideW, hideH, "scrytoggle", 0, "Show the look panel", "", "", "", false, true);
			graphics.pose().popPose();
			return;
		}
		// One panel from under the tabs down to the hand. Hand stays clear below.
		graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF18100C);
		graphics.fill(boxX, boxY, boxX + boxW, boxY + 1, GOLD);
		graphics.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, GOLD);
		graphics.fill(boxX, boxY, boxX + 1, boxY + boxH, GOLD);
		graphics.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, GOLD);
		graphics.drawString(screen.uiFont(), title, boxX + 10, boxY + 6, GOLD);
		String hint = search
			? "Left-click moves between looking and take to hand. Scroll or drag bar to browse. Done shuffles the rest."
			: (surveil
				? "Left-click: top or looking. Right-click or shift-left: graveyard."
				: "Left-click: top or looking. Right-click or shift-left: bottom of library.");
		graphics.drawString(screen.uiFont(), screen.uiFont().plainSubstrByWidth(hint, boxW - 120), boxX + 10, boxY + 18, MUTED);
		hideW = 52;
		hideX = boxX + boxW - hideW - 8;
		graphics.fill(hideX, hideY, hideX + hideW, hideY + hideH, 0xFF3A2A22);
		graphics.fill(hideX, hideY, hideX + hideW, hideY + 1, GOLD);
		graphics.drawCenteredString(screen.uiFont(), "Hide", hideX + hideW / 2, hideY + 4, PAPER);
		screen.hit(hideX, hideY, hideW, hideH, "scrytoggle", 0, "Hide and look at the boards", "", "", "", false, true);
		int doneW = 64;
		int doneX = hideX - doneW - 6;
		graphics.fill(doneX, hideY, doneX + doneW, hideY + hideH, 0xFF3A2A22);
		graphics.fill(doneX, hideY, doneX + doneW, hideY + 1, GOLD);
		graphics.drawCenteredString(screen.uiFont(), "Done", doneX + doneW / 2, hideY + 4, PAPER);
		screen.hit(doneX, hideY, doneW, hideH, "scrydone", 0, "Finish", "", "", "", false, true);
		int innerX = boxX + 8;
		int innerW = boxW - 16;
		int innerTop = boxY + 32;
		int innerBot = hideY - 8;
		int innerH = Math.max(60, innerBot - innerTop);
		int rowH = innerH / 3;
		if (search) {
			int half = innerH / 2;
			drawScryRow(graphics, "Looking", scryLook, innerX, innerTop, innerW, half, "look");
			drawScryRow(graphics, "Take to hand", scryBottom, innerX, innerTop + half, innerW, innerH - half, "bottom");
		} else {
			drawScryRow(graphics, "On top", scryTop, innerX, innerTop, innerW, rowH, "top");
			drawScryRow(graphics, "Looking", scryLook, innerX, innerTop + rowH, innerW, rowH, "look");
			if (surveil) {
				drawScryRow(graphics, "Graveyard", scryGrave, innerX, innerTop + rowH * 2, innerW, rowH, "grave");
			} else {
				drawScryRow(graphics, "Bottom of library", scryBottom, innerX, innerTop + rowH * 2, innerW, rowH, "bottom");
			}
		}
		graphics.pose().popPose();
	}

	void drawScryRow(GuiGraphics graphics, String title, List<Integer> indexes, int x, int y, int w, int h, String row) {
		graphics.fill(x, y + h - 1, x + w, y + h, 0xFF3A2A22);
		int count = indexes == null ? 0 : indexes.size();
		String label = count > 0 ? title + " (" + count + ")" : title;
		graphics.drawString(screen.uiFont(), label, x + 4, y + 3, MUTED);
		if (count == 0 || scryCards == null) {
			rowScrolls.put(row, 0);
			if (row.equals(draggingRow)) {
				draggingRow = null;
			}
			return;
		}
		int barH = 6;
		int barReserve = 12;
		int cardH = Math.max(20, h - 14 - barReserve);
		int cardW = Math.max(14, (int) (cardH * 0.716f));
		int gap = 6;
		int step = cardW + gap;
		int viewX = x + 4;
		int viewW = Math.max(20, w - 8);
		int totalW = count * cardW + (count - 1) * gap;
		int maxScroll = Math.max(0, totalW - viewW);
		int scroll = clamp(rowScrolls.getOrDefault(row, 0), 0, maxScroll);
		rowScrolls.put(row, scroll);
		if (maxScroll == 0 && row.equals(draggingRow)) {
			draggingRow = null;
		}
		int cy = y + 14;
		int first = clamp((scroll - cardW + 1) / step, 0, count - 1);
		int last = clamp((scroll + viewW) / step, 0, count - 1);
		graphics.enableScissor(viewX, cy, viewX + viewW, cy + cardH);
		for (int i = first; i <= last; i++) {
			int cx = viewX + i * step - scroll;
			if (cx + cardW <= viewX || cx >= viewX + viewW) {
				continue;
			}
			int index = indexes.get(i);
			if (index < 0 || index >= scryCards.size()) {
				continue;
			}
			BinderCard card = scryCards.get(index);
			screen.paint(graphics, false, false, false, card.printingId(), card.finish(), card.name(), cx, cy, cardW, cardH);
			int hitX = Math.max(viewX, cx);
			int hitEnd = Math.min(viewX + viewW, cx + cardW);
			if (hitEnd > hitX) {
				screen.hit(hitX, cy, hitEnd - hitX, cardH, "scrycard", index, card.name(), row, card.printingId(), card.finish(), false, true);
			}
		}
		graphics.disableScissor();
		if (maxScroll > 0) {
			int barX = viewX;
			int barW = viewW;
			int barY = Math.min(cy + cardH + 3, y + h - barH - 2);
			graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF281A14);
			graphics.fill(barX, barY, barX + barW, barY + 1, 0xFF4A362C);
			int thumbW = Math.min(barW, Math.max(24, (int) ((long) barW * viewW / Math.max(1, totalW))));
			int trackSpan = Math.max(1, barW - thumbW);
			int thumbX = barX + (int) ((long) trackSpan * scroll / maxScroll);
			boolean active = row.equals(draggingRow);
			graphics.fill(thumbX, barY, thumbX + thumbW, barY + barH, active ? 0xFFE6C88A : GOLD);
			graphics.fill(thumbX, barY, thumbX + thumbW, barY + 1, 0xFFF2DEC0);
			rowBars.put(row, new RowBar(viewX, y, viewW, h, barX, barY - 2, barW, barH + 4, thumbX, thumbW, maxScroll, step));
		} else {
			rowBars.put(row, new RowBar(viewX, y, viewW, h, 0, 0, 0, 0, 0, 0, 0, step));
		}
	}

	/** @return true if the click was consumed by the look panel */
	boolean scryClick(double mouseX, double mouseY, int button) {
		boolean shift = shiftHeld();
		for (int i = screen.clicks.size() - 1; i >= 0; i--) {
			Click click = screen.clicks.get(i);
			if (!inside(mouseX, mouseY, click.x, click.y, click.w, click.h)) {
				continue;
			}
			if ("scrytoggle".equals(click.zone) && button == 0) {
				scryHidden = !scryHidden;
				draggingRow = null;
				return true;
			}
			if (scryHidden) {
				return false;
			}
			if ("scrydone".equals(click.zone) && button == 0) {
				scryFinish();
				return true;
			}
		}
		if (scryHidden) {
			return false;
		}
		if (button == 0) {
			for (Map.Entry<String, RowBar> entry : rowBars.entrySet()) {
				RowBar bar = entry.getValue();
				if (bar.maxScroll > 0 && inside(mouseX, mouseY, bar.barX, bar.barY, bar.barW, bar.barH)) {
					draggingRow = entry.getKey();
					if (mouseX >= bar.thumbX && mouseX <= bar.thumbX + bar.thumbW) {
						dragOffsetX = (int) mouseX - bar.thumbX;
					} else {
						dragOffsetX = bar.thumbW / 2;
						applyThumbDrag(draggingRow, bar, mouseX);
					}
					return true;
				}
			}
		}
		for (int i = screen.clicks.size() - 1; i >= 0; i--) {
			Click click = screen.clicks.get(i);
			if (!inside(mouseX, mouseY, click.x, click.y, click.w, click.h)) {
				continue;
			}
			if ("scrycard".equals(click.zone) && (button == 0 || button == 1)) {
				boolean side = shift || button == 1;
				scryCycle(click.index, side);
				return true;
			}
		}
		return true;
	}

	boolean scryDrag(double mouseX, double mouseY, int button) {
		if (button != 0 || draggingRow == null || scryHidden) {
			return false;
		}
		RowBar bar = rowBars.get(draggingRow);
		if (bar == null || bar.maxScroll <= 0) {
			return false;
		}
		applyThumbDrag(draggingRow, bar, mouseX);
		return true;
	}

	boolean scryRelease(double mouseX, double mouseY, int button) {
		if (draggingRow != null) {
			draggingRow = null;
			return true;
		}
		return false;
	}

	boolean scryScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scryMode == null || scryHidden) {
			return false;
		}
		double delta = scrollX != 0.0 ? scrollX : scrollY;
		if (delta == 0.0) {
			return false;
		}
		String targetRow = null;
		RowBar targetBar = null;
		for (Map.Entry<String, RowBar> entry : rowBars.entrySet()) {
			RowBar bar = entry.getValue();
			if (inside(mouseX, mouseY, bar.viewX, bar.viewY, bar.viewW, bar.viewH) && bar.maxScroll > 0) {
				targetRow = entry.getKey();
				targetBar = bar;
				break;
			}
		}
		if (targetRow == null) {
			int overflowing = 0;
			for (Map.Entry<String, RowBar> entry : rowBars.entrySet()) {
				if (entry.getValue().maxScroll > 0) {
					targetRow = entry.getKey();
					targetBar = entry.getValue();
					overflowing++;
				}
			}
			if (overflowing != 1) {
				return false;
			}
		}
		int step = Math.max(24, targetBar.step);
		int change = (int) Math.round(-delta * step);
		if (change == 0) {
			change = delta < 0 ? step : -step;
		}
		int current = rowScrolls.getOrDefault(targetRow, 0);
		rowScrolls.put(targetRow, clamp(current + change, 0, targetBar.maxScroll));
		return true;
	}

	private void applyThumbDrag(String row, RowBar bar, double mouseX) {
		int trackSpan = Math.max(1, bar.barW - bar.thumbW);
		int thumbX = clamp((int) mouseX - dragOffsetX, bar.barX, bar.barX + trackSpan);
		int scroll = (int) Math.round((double) (thumbX - bar.barX) * bar.maxScroll / trackSpan);
		rowScrolls.put(row, clamp(scroll, 0, bar.maxScroll));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private record RowBar(int viewX, int viewY, int viewW, int viewH, int barX, int barY, int barW, int barH, int thumbX, int thumbW, int maxScroll, int step) {
	}

	public void beginScry(ScrySyncS2CPayload payload) {
		if (screen.pos == null || payload.pos() == null || !screen.pos.equals(payload.pos())) {
			return;
		}
		// Empty mode + no cards = server cancelled the look; close the panel.
		if ((payload.mode() == null || payload.mode().isEmpty()) && (payload.cards() == null || payload.cards().isEmpty())) {
			scryClear();
			return;
		}
		// Opponent search uses the owner's seat in the payload; still open the panel.
		scryClear();
		scryMode = payload.mode() == null || payload.mode().isEmpty() ? "scry" : payload.mode();
		scrySeat = payload.seat();
		scryCards = payload.cards();
		for (int i = 0; i < scryCards.size(); i++) {
			scryLook.add(i);
		}
		screen.menu.menuZone = null;
	}

	void scryClear() {
		scryMode = null;
		scrySeat = null;
		scryCards = null;
		scryHidden = false;
		draggingRow = null;
		rowScrolls.clear();
		rowBars.clear();
		scryTop.clear();
		scryLook.clear();
		scryBottom.clear();
		scryGrave.clear();
	}

	void scryFinish() {
		if (screen.pos == null || screen.seat == null || scryMode == null || scryCards == null) {
			return;
		}
		if (!ClientPlayNetworking.canSend(ScryFinishC2SPayload.TYPE)) {
			return;
		}
		ArrayList<Integer> tops = new ArrayList<>(scryTop);
		tops.addAll(scryLook);
		// Search: looking leftovers stay in library (tops); take row is bottoms.
		String finishSeat = scrySeat != null && !scrySeat.isEmpty() ? scrySeat : screen.seat;
		ClientPlayNetworking.send(new ScryFinishC2SPayload(
			screen.pos,
			finishSeat,
			scryMode,
			tops,
			List.copyOf(scryBottom),
			List.copyOf(scryGrave)
		));
		scryClear();
	}

	void scryCycle(int index, boolean side) {
		if (scryCards == null || index < 0 || index >= scryCards.size()) {
			return;
		}
		boolean surveil = "surveil".equals(scryMode);
		boolean search = "search".equals(scryMode);
		if (search) {
			if (scryLook.remove((Integer) index)) {
				scryBottom.add(index);
				return;
			}
			if (scryBottom.remove((Integer) index)) {
				scryLook.add(index);
			}
			return;
		}
		// side = shift or right-click: surveil -> graveyard, scry -> bottom of library
		if (scryTop.remove((Integer) index)) {
			if (surveil && side) {
				scryGrave.add(index);
			} else if (surveil) {
				scryLook.add(index);
			} else {
				scryBottom.add(index);
			}
			return;
		}
		if (scryLook.remove((Integer) index)) {
			if (side) {
				if (surveil) {
					scryGrave.add(index);
				} else {
					scryBottom.add(index);
				}
			} else {
				scryTop.add(index);
			}
			return;
		}
		if (scryBottom.remove((Integer) index)) {
			if (side && surveil) {
				scryGrave.add(index);
			} else {
				scryLook.add(index);
			}
			return;
		}
		if (scryGrave.remove((Integer) index)) {
			scryLook.add(index);
		}
	}

	boolean zoomAllowed(Click click) {
		if (click == null) {
			return false;
		}
		if (scryMode != null && !scryHidden) {
			return "hand".equals(click.zone) || "scrycard".equals(click.zone);
		}
		return true;
	}
}
