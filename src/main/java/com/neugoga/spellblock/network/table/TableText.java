package com.neugoga.spellblock.network.table;

import com.neugoga.spellblock.table.TableSeat;

import java.util.Locale;

/**
 * Seat names and small text and number parsing for table actions.
 */
public final class TableText {
	private TableText() {
	}

	public static String prettySeatName(String name) {
		TableSeat seat = seatOf(name);
		return seat == null ? (name == null || name.isEmpty() ? "?" : name) : pretty(seat);
	}

	public static String pretty(TableSeat seat) {
		String name = seat.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	public static int parseCount(String action) {
		int at = action.indexOf(':');
		if (at < 0 || at + 1 >= action.length()) {
			return 0;
		}
		try {
			return Integer.parseInt(action.substring(at + 1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static TableSeat seatOf(String name) {
		try {
			return TableSeat.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
