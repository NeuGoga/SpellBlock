package com.neugoga.spellblock.pack;

import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.library.DeckEntry;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tokens Scryfall links to cards in a deck box, for the create-token menu. */
public final class TokenKit {
	private TokenKit() {
	}

	public static List<OracleCard> fromBox(ItemStack box) {
		if (box == null || box.isEmpty()) {
			return List.of();
		}
		DeckBoxData data = box.get(SpellBlockComponents.DECK);
		if (data == null) {
			return List.of();
		}
		return fromDeck(data);
	}

	public static List<OracleCard> fromDeck(DeckBoxData data) {
		OracleIndex index = OracleService.index();
		if (index == null || data == null) {
			return List.of();
		}
		Map<String, OracleCard> out = new LinkedHashMap<>();
		collect(index, data.commanders(), out);
		collect(index, data.main(), out);
		collect(index, data.side(), out);
		return new ArrayList<>(out.values());
	}

	private static void collect(OracleIndex index, List<DeckEntry> entries, Map<String, OracleCard> out) {
		if (entries == null) {
			return;
		}
		for (DeckEntry entry : entries) {
			if (entry == null || entry.key() == null) {
				continue;
			}
			String oracle = entry.key().oracleId();
			for (OracleCard token : index.relatedTokens(oracle)) {
				String key = !token.printingId().isEmpty() ? token.printingId() : token.oracleId();
				if (key.isEmpty()) {
					key = token.name();
				}
				out.putIfAbsent(key, token);
			}
		}
	}
}
