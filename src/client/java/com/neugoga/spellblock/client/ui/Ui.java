package com.neugoga.spellblock.client.ui;

import java.util.Locale;

/**
 * Small helpers shared by the table, deck and binder screens.
 * Keep this to pure functions: no screen state, no drawing.
 */
public final class Ui {
	private Ui() {
	}

	public static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	public static int rarityColor(String rarity) {
		return switch (rarity) {
			case "uncommon" -> 0xFFC5CDD6;
			case "rare" -> 0xFFD4AF37;
			case "mythic" -> 0xFFE85D04;
			case "special", "bonus" -> 0xFFAA62E0;
			default -> 0xFF8E8E8E;
		};
	}

	public static String rarityLabel(String rarity) {
		if (rarity == null || rarity.isEmpty()) {
			return "Unknown";
		}
		return rarity.substring(0, 1).toUpperCase(Locale.ROOT) + rarity.substring(1);
	}

	public static int colorRank(String identity) {
		if (identity == null || identity.isEmpty()) {
			return 100;
		}
		if (identity.length() > 1) {
			return 50;
		}
		return switch (identity.charAt(0)) {
			case 'W' -> 0;
			case 'U' -> 1;
			case 'B' -> 2;
			case 'R' -> 3;
			case 'G' -> 4;
			default -> 90;
		};
	}

	public static boolean any(boolean[] flags) {
		for (boolean flag : flags) {
			if (flag) {
				return true;
			}
		}
		return false;
	}
}
