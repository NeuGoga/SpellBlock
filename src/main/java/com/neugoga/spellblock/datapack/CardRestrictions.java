package com.neugoga.spellblock.datapack;

import com.google.gson.JsonObject;
import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.pack.Printing;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Disabled-card and craft-restricted-card rules from {@code data/spellblock/cards/disabled.json}.
 * <p>
 * Rules in {@code disabled} block opening in packs, granting, importing, crafting, and trade receiving
 * (while still allowing disenchanting owned copies so players are never stuck with banned cards).
 * Rules in {@code disabled_crafting} only block crafting in the Dustery.
 */
public final class CardRestrictions {
	public static final CardRestrictions EMPTY = new CardRestrictions(List.of(), List.of());

	public static final StreamCodec<FriendlyByteBuf, CardRestrictions> CODEC = StreamCodec.of(
		(buf, r) -> {
			buf.writeVarInt(r.disabledEntries.size());
			for (String s : r.disabledEntries) {
				buf.writeUtf(s);
			}
			buf.writeVarInt(r.craftDisabledEntries.size());
			for (String s : r.craftDisabledEntries) {
				buf.writeUtf(s);
			}
		},
		buf -> {
			int dCount = Math.max(0, Math.min(8192, buf.readVarInt()));
			List<String> d = new ArrayList<>(dCount);
			for (int i = 0; i < dCount; i++) {
				d.add(buf.readUtf());
			}
			int cCount = Math.max(0, Math.min(8192, buf.readVarInt()));
			List<String> c = new ArrayList<>(cCount);
			for (int i = 0; i < cCount; i++) {
				c.add(buf.readUtf());
			}
			return new CardRestrictions(d, c);
		}
	);

	private final List<String> disabledEntries;
	private final List<String> craftDisabledEntries;
	private final RuleSet disabledRules;
	private final RuleSet craftDisabledRules;

	public CardRestrictions(List<String> disabledEntries, List<String> craftDisabledEntries) {
		this.disabledEntries = disabledEntries == null ? List.of() : List.copyOf(disabledEntries);
		this.craftDisabledEntries = craftDisabledEntries == null ? List.of() : List.copyOf(craftDisabledEntries);
		this.disabledRules = RuleSet.compile(this.disabledEntries);
		this.craftDisabledRules = RuleSet.compile(this.craftDisabledEntries);
	}

	public static CardRestrictions fromJson(JsonObject root) {
		if (root == null) {
			return EMPTY;
		}
		Set<String> disabled = new LinkedHashSet<>();
		disabled.addAll(JsonHelper.stringList(root, "disabled"));
		disabled.addAll(JsonHelper.stringList(root, "oracle_ids"));
		disabled.addAll(JsonHelper.stringList(root, "names"));
		for (String set : JsonHelper.stringList(root, "sets")) {
			String s = set.toLowerCase(Locale.ROOT);
			disabled.add(s.startsWith("set:") ? s : "set:" + s);
		}

		Set<String> craftDisabled = new LinkedHashSet<>();
		craftDisabled.addAll(JsonHelper.stringList(root, "disabled_crafting"));
		craftDisabled.addAll(JsonHelper.stringList(root, "craft_disabled"));
		return new CardRestrictions(List.copyOf(disabled), List.copyOf(craftDisabled));
	}

	public List<String> disabledEntries() {
		return disabledEntries;
	}

	public List<String> craftDisabledEntries() {
		return craftDisabledEntries;
	}

	public boolean isDisabled(String oracleId, String name, String setCode, String rarity, String typeLine) {
		return disabledRules.matches(oracleId, name, setCode, rarity, typeLine);
	}

	public boolean isDisabled(OracleCard card) {
		if (card == null) {
			return false;
		}
		return isDisabled(card.oracleId(), card.name(), card.setCode(), card.rarity(), card.typeLine());
	}

	public boolean isDisabled(Printing card) {
		if (card == null) {
			return false;
		}
		return isDisabled(card.oracleId(), card.name(), card.setCode(), card.rarity(), card.typeLine());
	}

	public boolean isCraftBlocked(String oracleId, String name, String setCode, String rarity, String typeLine) {
		return disabledRules.matches(oracleId, name, setCode, rarity, typeLine)
			|| craftDisabledRules.matches(oracleId, name, setCode, rarity, typeLine);
	}

	public boolean isCraftBlocked(OracleCard card) {
		if (card == null) {
			return false;
		}
		return isCraftBlocked(card.oracleId(), card.name(), card.setCode(), card.rarity(), card.typeLine());
	}

	public boolean isCraftBlocked(BinderCard card) {
		if (card == null) {
			return false;
		}
		return isCraftBlocked(card.oracleId(), card.name(), card.setCode(), card.rarity(), card.typeLine());
	}

	private record RuleSet(
		Set<String> oracleIds,
		Set<String> foldedNames,
		Set<String> sets,
		Set<String> rarities,
		List<String> typeWords
	) {
		static RuleSet compile(List<String> entries) {
			if (entries == null || entries.isEmpty()) {
				return new RuleSet(Set.of(), Set.of(), Set.of(), Set.of(), List.of());
			}
			Set<String> oracles = new HashSet<>();
			Set<String> names = new HashSet<>();
			Set<String> sets = new HashSet<>();
			Set<String> rarities = new HashSet<>();
			List<String> types = new ArrayList<>();
			for (String raw : entries) {
				if (raw == null) {
					continue;
				}
				String s = raw.trim();
				if (s.isEmpty()) {
					continue;
				}
				String lower = s.toLowerCase(Locale.ROOT);
				if (lower.startsWith("set:")) {
					String val = lower.substring(4).trim();
					if (!val.isEmpty()) {
						sets.add(val);
					}
				} else if (lower.startsWith("rarity:")) {
					String val = lower.substring(7).trim();
					if (!val.isEmpty()) {
						rarities.add(val);
					}
				} else if (lower.startsWith("type:")) {
					String val = OracleIndex.fold(s.substring(5).trim());
					if (!val.isEmpty()) {
						types.add(val);
					}
				} else if (lower.startsWith("oracle:")) {
					String val = lower.substring(7).trim();
					if (!val.isEmpty()) {
						oracles.add(val);
					}
				} else if (lower.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
					oracles.add(lower);
				} else {
					String folded = OracleIndex.fold(s);
					if (!folded.isEmpty()) {
						names.add(folded);
					}
				}
			}
			return new RuleSet(Set.copyOf(oracles), Set.copyOf(names), Set.copyOf(sets), Set.copyOf(rarities), List.copyOf(types));
		}

		boolean matches(String oracleId, String name, String setCode, String rarity, String typeLine) {
			if (oracleIds.isEmpty() && foldedNames.isEmpty() && sets.isEmpty() && rarities.isEmpty() && typeWords.isEmpty()) {
				return false;
			}
			if (oracleId != null && !oracleId.isEmpty() && oracleIds.contains(oracleId.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
			if (name != null && !name.isEmpty() && !foldedNames.isEmpty()) {
				String folded = OracleIndex.fold(name);
				if (!folded.isEmpty() && foldedNames.contains(folded)) {
					return true;
				}
			}
			if (setCode != null && !setCode.isEmpty() && sets.contains(setCode.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
			if (rarity != null && !rarity.isEmpty() && rarities.contains(rarity.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
			if (typeLine != null && !typeLine.isEmpty() && !typeWords.isEmpty()) {
				String foldedType = OracleIndex.fold(typeLine);
				for (String word : typeWords) {
					if (foldedType.contains(word)) {
						return true;
					}
				}
			}
			return false;
		}
	}
}
