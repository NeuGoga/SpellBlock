package com.neugoga.spellblock.table;

import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.pack.Printing;
import com.neugoga.spellblock.pack.SetCatalog;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Public card names for the client. Hands are not named here. */
public final class TableNames {
	/** Name and battlefield row for one printing. Cleared when the oracle or a set pool reloads. */
	private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();

	private TableNames() {
	}

	private record Cached(String name, String row) {
	}

	/** Drop cached names. The next block update stamps them again from the new index. */
	public static void clear() {
		CACHE.clear();
	}

	public static void stamp(CompoundTag zones) {
		stampList(zones.getList("Battlefield", Tag.TAG_COMPOUND));
		stampList(zones.getList("Graveyard", Tag.TAG_COMPOUND));
		stampList(zones.getList("Exile", Tag.TAG_COMPOUND));
	}

	public static String of(CardKey key) {
		Cached cached = lookup(key);
		return cached == null ? "" : cached.name;
	}

	/** land closest to the player, creature on the far row, everything else in the middle. */
	public static String row(CardKey key) {
		Cached cached = lookup(key);
		return cached == null ? "other" : cached.row;
	}

	private static Cached lookup(CardKey key) {
		if (key == null) {
			return null;
		}
		String id = key.printingId();
		String cacheKey = id == null || id.isEmpty() ? "o:" + key.oracleId() : id;
		Cached hit = CACHE.get(cacheKey);
		if (hit != null) {
			return hit;
		}
		String name = resolveName(key);
		String row = resolveRow(key);
		Cached cached = new Cached(name, row);
		CACHE.put(cacheKey, cached);
		return cached;
	}

	private static String resolveName(CardKey key) {
		Printing printing = SetCatalog.find(key.printingId());
		if (printing != null && printing.name() != null && !printing.name().isEmpty()) {
			return printing.name();
		}
		OracleCard card = oracle(key);
		return card == null || card.name() == null ? "" : card.name();
	}

	private static String resolveRow(CardKey key) {
		String type = typeLine(key).toLowerCase(Locale.ROOT);
		if (type.contains("land")) {
			return "land";
		}
		if (type.contains("creature")) {
			return "creature";
		}
		return "other";
	}

	private static String typeLine(CardKey key) {
		if (key == null) {
			return "";
		}
		Printing printing = SetCatalog.find(key.printingId());
		if (printing != null && printing.typeLine() != null && !printing.typeLine().isEmpty()) {
			return printing.typeLine();
		}
		OracleCard card = oracle(key);
		return card == null || card.typeLine() == null ? "" : card.typeLine();
	}

	private static OracleCard oracle(CardKey key) {
		OracleIndex index = OracleService.index();
		if (index == null || key == null) {
			return null;
		}
		return index.findByOracleId(key.oracleId());
	}

	private static void stampList(ListTag list) {
		for (int i = 0; i < list.size(); i++) {
			CompoundTag card = list.getCompound(i);
			boolean token = card.getBoolean("Token");
			String label = card.getString("Label");
			String oracle = card.getString("Oracle");
			Cached cached = null;
			if (oracle != null && !oracle.isEmpty()) {
				CardKey key = new CardKey(oracle, card.getString("Printing"), Finish.fromName(card.getString("Finish")));
				cached = lookup(key);
			}
			String name = cached == null ? "" : cached.name;
			if (name.isEmpty() && !label.isEmpty()) {
				name = label;
			}
			if (token && !name.isEmpty() && !name.toLowerCase(Locale.ROOT).contains("token")) {
				name = name + " (token)";
			} else if (token && name.isEmpty()) {
				name = label.isEmpty() ? "Token" : label;
			}
			if (!name.isEmpty()) {
				if (name.length() > 80) {
					name = name.substring(0, 80);
				}
				card.putString("Name", name);
			}
			if (card.getBoolean("FaceDown")) {
				card.putString("Row", "down");
			} else if (token) {
				// Tokens always sit in the middle "other" row so pure tokens are not lost off a creature lane.
				String row = cached == null ? "other" : cached.row;
				if ("land".equals(row) || "down".equals(row)) {
					row = "other";
				}
				if (oracle == null || oracle.isEmpty()) {
					row = "other";
				}
				card.putString("Row", row);
			} else {
				card.putString("Row", cached == null ? "other" : cached.row);
			}
		}
	}
}
