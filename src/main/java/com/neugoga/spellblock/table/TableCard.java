package com.neugoga.spellblock.table;

import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.Finish;

import net.minecraft.nbt.CompoundTag;

/**
 * One card on the table. Not an inventory item.
 *
 * The id names this card inside one table, so an action still hits the right card after the lists
 * change. A card keeps its id when it moves between zones. 0 means it has not been numbered yet;
 * the table numbers it.
 *
 * Counters are free-play only: power/toughness are signed totals, loyalty is non-negative,
 * markers are generic 0/0-style piles. A token is not a real card in a deck box.
 */
public record TableCard(
	String oracleId,
	String printingId,
	String finish,
	boolean faceDown,
	boolean tapped,
	boolean flipped,
	String ownerSeat,
	int id,
	boolean token,
	/** Optional label for pure tokens (no oracle). Empty for normal cards and token-copies. */
	String label,
	int power,
	int toughness,
	int loyalty,
	int markers
) {
	public static final int MAX_PT = 99;
	public static final int MAX_LOYALTY = 999;
	public static final int MAX_MARKERS = 99;

	public TableCard {
		oracleId = oracleId == null ? "" : oracleId;
		printingId = printingId == null ? "" : printingId;
		finish = Finish.fromName(finish).name();
		ownerSeat = ownerSeat == null ? "" : ownerSeat;
		label = label == null ? "" : label;
		if (oracleId.length() > 80) {
			oracleId = oracleId.substring(0, 80);
		}
		if (printingId.length() > 80) {
			printingId = printingId.substring(0, 80);
		}
		if (ownerSeat.length() > 16) {
			ownerSeat = ownerSeat.substring(0, 16);
		}
		if (label.length() > 40) {
			label = label.substring(0, 40);
		}
		if (id < 0) {
			id = 0;
		}
		power = clamp(power, -MAX_PT, MAX_PT);
		toughness = clamp(toughness, -MAX_PT, MAX_PT);
		loyalty = clamp(loyalty, 0, MAX_LOYALTY);
		markers = clamp(markers, 0, MAX_MARKERS);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/** A card that has no id yet. Use the table's newCard to get a numbered one. */
	public static TableCard of(CardKey key, boolean faceDown) {
		return new TableCard(key.oracleId(), key.printingId(), key.finish().name(), faceDown, false, false, "", 0, false, "", 0, 0, 0, 0);
	}

	/** A pure token with no oracle art. */
	public static TableCard pureToken(String label, int power, int toughness) {
		// Printed P/T lives in the label only. power/toughness fields are counter totals.
		String face = label == null || label.isEmpty() ? (power + "/" + toughness) : label;
		return new TableCard("", "", "NONFOIL", false, false, false, "", 0, true, face, 0, 0, 0, 0);
	}

	public CardKey key() {
		return new CardKey(oracleId, printingId, Finish.fromName(finish));
	}

	public boolean hasCounters() {
		return power != 0 || toughness != 0 || loyalty != 0 || markers != 0;
	}

	public TableCard withId(int next) {
		return next == id ? this : copy(oracleId, printingId, finish, faceDown, tapped, flipped, ownerSeat, next, token, label, power, toughness, loyalty, markers);
	}

	/** Face up and untapped. A flipped card stays flipped. Zones other than the battlefield store cards this way. */
	public TableCard faceUp() {
		return !faceDown && !tapped ? this : copy(oracleId, printingId, finish, false, false, flipped, ownerSeat, id, token, label, power, toughness, loyalty, markers);
	}

	/** A copy that is face down. Not named faceDown: that is the boolean component. */
	public TableCard turnedDown() {
		return faceDown ? this : copy(oracleId, printingId, finish, true, tapped, flipped, ownerSeat, id, token, label, power, toughness, loyalty, markers);
	}

	public TableCard withTapped(boolean next) {
		return next == tapped ? this : copy(oracleId, printingId, finish, faceDown, next, flipped, ownerSeat, id, token, label, power, toughness, loyalty, markers);
	}

	public TableCard withFaceDown(boolean down) {
		return down == faceDown ? this : copy(oracleId, printingId, finish, down, tapped, flipped, ownerSeat, id, token, label, power, toughness, loyalty, markers);
	}

	public TableCard withOwner(String seat) {
		String next = seat == null ? "" : seat;
		return next.equals(ownerSeat) ? this : copy(oracleId, printingId, finish, faceDown, tapped, flipped, next, id, token, label, power, toughness, loyalty, markers);
	}

	public TableCard withFlipped(boolean next) {
		return next == flipped ? this : copy(oracleId, printingId, finish, faceDown, tapped, next, ownerSeat, id, token, label, power, toughness, loyalty, markers);
	}

	public TableCard withToken(boolean next) {
		return next == token ? this : copy(oracleId, printingId, finish, faceDown, tapped, flipped, ownerSeat, id, next, label, power, toughness, loyalty, markers);
	}

	public TableCard withCounters(int nextPower, int nextTough, int nextLoyalty, int nextMarkers) {
		nextPower = clamp(nextPower, -MAX_PT, MAX_PT);
		nextTough = clamp(nextTough, -MAX_PT, MAX_PT);
		nextLoyalty = clamp(nextLoyalty, 0, MAX_LOYALTY);
		nextMarkers = clamp(nextMarkers, 0, MAX_MARKERS);
		if (nextPower == power && nextTough == toughness && nextLoyalty == loyalty && nextMarkers == markers) {
			return this;
		}
		return copy(oracleId, printingId, finish, faceDown, tapped, flipped, ownerSeat, id, token, label, nextPower, nextTough, nextLoyalty, nextMarkers);
	}

	public TableCard addPower(int delta) {
		return withCounters(power + delta, toughness, loyalty, markers);
	}

	public TableCard addToughness(int delta) {
		return withCounters(power, toughness + delta, loyalty, markers);
	}

	public TableCard addBoth(int delta) {
		return withCounters(power + delta, toughness + delta, loyalty, markers);
	}

	public TableCard addLoyalty(int delta) {
		return withCounters(power, toughness, loyalty + delta, markers);
	}

	public TableCard addMarkers(int delta) {
		return withCounters(power, toughness, loyalty, markers + delta);
	}

	public TableCard clearCounters() {
		return withCounters(0, 0, 0, 0);
	}

	/** Battlefield token-copy of this card: same art, new id later, no counters, marked token. */
	public TableCard asTokenCopy() {
		return new TableCard(oracleId, printingId, finish, false, false, flipped, ownerSeat, 0, true, "", 0, 0, 0, 0);
	}

	private static TableCard copy(
		String oracleId, String printingId, String finish, boolean faceDown, boolean tapped, boolean flipped,
		String ownerSeat, int id, boolean token, String label, int power, int toughness, int loyalty, int markers
	) {
		return new TableCard(oracleId, printingId, finish, faceDown, tapped, flipped, ownerSeat, id, token, label, power, toughness, loyalty, markers);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Oracle", oracleId);
		if (!printingId.isEmpty()) {
			tag.putString("Printing", printingId);
		}
		if (!"NONFOIL".equals(finish)) {
			tag.putString("Finish", finish);
		}
		if (faceDown) {
			tag.putBoolean("FaceDown", true);
		}
		if (tapped) {
			tag.putBoolean("Tapped", true);
		}
		if (flipped) {
			tag.putBoolean("Flipped", true);
		}
		if (ownerSeat != null && !ownerSeat.isEmpty()) {
			tag.putString("Owner", ownerSeat);
		}
		if (id > 0) {
			tag.putInt("Id", id);
		}
		if (token) {
			tag.putBoolean("Token", true);
		}
		if (!label.isEmpty()) {
			tag.putString("Label", label);
		}
		if (power != 0) {
			tag.putInt("Power", power);
		}
		if (toughness != 0) {
			tag.putInt("Tough", toughness);
		}
		if (loyalty != 0) {
			tag.putInt("Loyalty", loyalty);
		}
		if (markers != 0) {
			tag.putInt("Markers", markers);
		}
		return tag;
	}

	public static TableCard load(CompoundTag tag) {
		String oracle = tag.getString("Oracle");
		String label = tag.getString("Label");
		boolean token = tag.getBoolean("Token");
		if (oracle.isEmpty() && !token && label.isEmpty()) {
			return null;
		}
		return new TableCard(
			oracle,
			tag.getString("Printing"),
			tag.contains("Finish") ? tag.getString("Finish") : "NONFOIL",
			tag.getBoolean("FaceDown"),
			tag.getBoolean("Tapped"),
			tag.getBoolean("Flipped"),
			tag.getString("Owner"),
			tag.getInt("Id"),
			token,
			label,
			tag.getInt("Power"),
			tag.getInt("Tough"),
			tag.getInt("Loyalty"),
			tag.getInt("Markers")
		);
	}
}
