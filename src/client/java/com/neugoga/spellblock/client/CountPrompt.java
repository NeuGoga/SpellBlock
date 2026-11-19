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

/**
 * The small number prompt for actions that need a count, such as scry, surveil, mill and draw.
 */
final class CountPrompt {
	private final TableScreen screen;

	CountPrompt(TableScreen screen) {
		this.screen = screen;
	}

	/** Typing a custom draw/scry/surveil/mill count. */
	String typingKind;
	/** Card id for actions that need one (e.g. put Nth from top). */
	int typingCardId;
	EditBox countBox;

	void openCountPrompt(String kind) {
		typingKind = kind;
		typingCardId = screen.menu.menuCardId;
		screen.menu.menuZone = null;
		if (screen.uiMinecraft() == null) {
			return;
		}
		int w = 120;
		int h = 20;
		int x = screen.width / 2 - w / 2;
		int y = screen.height / 2 - 10;
		countBox = new EditBox(screen.uiFont(), x, y, w, h, Component.literal("Count"));
		countBox.setMaxLength(4);
		countBox.setValue("");
		countBox.setFilter(text -> text != null && text.matches("\\d{0,4}"));
		countBox.setFocused(true);
		screen.setFocused(countBox);
	}

	void confirmCountPrompt() {
		if (typingKind == null) {
			return;
		}
		String raw = countBox == null ? "" : countBox.getValue();
		int n = 0;
		try {
			n = Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			n = 0;
		}
		boolean lifeTotal = typingKind != null && typingKind.startsWith("setlife:");
		if (n < 1 && !lifeTotal) {
			screen.closeCountPrompt();
			return;
		}
		if (lifeTotal && n < 0) {
			screen.closeCountPrompt();
			return;
		}
		if (n > 200) {
			n = 200;
		}
		String kind = typingKind;
		int cardId = typingCardId;
		screen.closeCountPrompt();
		if (kind != null && kind.startsWith("nth:")) {
			// action is "nth:hand" (or field/…); amount is depth from top
			screen.send(kind, cardId, n);
		} else if (kind != null && kind.startsWith("setlife:")) {
			// kind is setlife:SEAT; amount is the total
			screen.send(kind, 0, n);
		} else if (kind != null) {
			screen.send(kind + ":" + n, 0);
		}
	}

	void drawCountPrompt(GuiGraphics graphics) {
		int pw = 200;
		int ph = 70;
		int px = screen.width / 2 - pw / 2;
		int py = screen.height / 2 - ph / 2;
		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, 0.0f, 450.0f);
		// Dim the table so the field is readable.
		graphics.fill(0, 0, screen.width, screen.height, 0xC0100908);
		graphics.fill(px - 6, py - 6, px + pw + 6, py + ph + 6, 0xFF0C0806);
		graphics.fill(px, py, px + pw, py + ph, 0xFF18100C);
		graphics.fill(px, py, px + pw, py + 1, GOLD);
		String kind = typingKind == null ? "" : typingKind;
		String title = switch (kind) {
			case "drawn" -> "Draw how many?";
			case "scry" -> "Scry how many?";
			case "surv" -> "Surveil how many?";
			case "mill" -> "Mill how many?";
			default -> kind.startsWith("nth:") ? "How many from the top?"
				: kind.startsWith("setlife:") ? "Set life to?"
				: "How many?";
		};
		graphics.drawCenteredString(screen.uiFont(), title, screen.width / 2, py + 8, GOLD);
		graphics.drawCenteredString(screen.uiFont(), "Enter to confirm · Esc to cancel", screen.width / 2, py + ph - 14, MUTED);
		if (countBox != null) {
			countBox.setX(screen.width / 2 - 60);
			countBox.setY(py + 28);
			countBox.render(graphics, 0, 0, 0);
		}
		graphics.pose().popPose();
	}
}
