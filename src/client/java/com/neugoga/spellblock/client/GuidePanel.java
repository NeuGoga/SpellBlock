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
import static com.neugoga.spellblock.client.TableScreen.PAPER;
import static com.neugoga.spellblock.client.TableScreen.TEXT;

/**
 * The help button and the guide panel that explains the table controls.
 */
final class GuidePanel {
	private final TableScreen screen;

	GuidePanel(TableScreen screen) {
		this.screen = screen;
	}

	static final String[] GUIDE = {
		"Alt — zoom the card under the cursor",
		"Alt + Shift — peek a face-down or flipped card",
		"Alt + wheel — zoom size. It stays until you change it",
		"Middle-click a card — library, face down, flip, and more",
		"Middle-click the library pile — shuffle, search, scry, surveil, mill, draw",
		"Colored chip / eye — that seat is searching, scrying, surveilling, or peeking a face-down card",
		"Hand — left play, right grave, shift-left exile, shift-right library",
		"Board — left tap, shift-left exile, right grave, shift-right hand",
		"Command zone — left +tax, right −tax; middle-click menu to cast or return a commander",
		"Grave — left hand, shift-left exile, shift-right library",
		"Exile — left hand, shift-right library",
		"Life — left +1, right -1, shift changes it by 5",
		"Middle-click a hand card — show hand or this card to a seat or everyone",
		"Scry / surveil — one panel under the tabs. Hide tucks it away. Surveil uses the graveyard row"
	};
	boolean guideOpen;
	int markX;
	int markY;
	int markS;
	int panelX;
	int panelY;
	int panelW;
	int panelH;

	void drawHelp(GuiGraphics graphics, int x, int y, int size) {
		markX = x;
		markY = y;
		markS = size;
		graphics.fill(x, y, x + size, y + size, guideOpen ? 0xFF4A2820 : 0xFF241610);
		graphics.fill(x, y, x + size, y + 1, GOLD);
		graphics.fill(x, y + size - 1, x + size, y + size, GOLD);
		graphics.drawCenteredString(screen.uiFont(), "?", x + size / 2, y + (size - 8) / 2, PAPER);
		screen.hit(x, y, size, size, "help", 0, "Controls", "", "", "", false, true);
	}

	void drawGuide(GuiGraphics graphics) {
		int rowH = 12;
		panelW = Math.min(420, Math.max(220, screen.width - 16));
		panelH = 10 + GUIDE.length * rowH + 8;
		panelX = screen.width - panelW - 8;
		panelY = markY - panelH - 4;
		if (panelX < 8) {
			panelX = 8;
		}
		if (panelY < 4) {
			panelY = 4;
		}
		graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF18100C);
		graphics.fill(panelX, panelY, panelX + panelW, panelY + 1, GOLD);
		graphics.drawString(screen.uiFont(), "Controls", panelX + 8, panelY + 6, GOLD);
		for (int i = 0; i < GUIDE.length; i++) {
			graphics.drawString(
				screen.uiFont(),
				screen.uiFont().plainSubstrByWidth(GUIDE[i], panelW - 16),
				panelX + 8,
				panelY + 20 + i * rowH,
				TEXT
			);
		}
	}
}
