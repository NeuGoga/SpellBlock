package com.neugoga.spellblock.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neugoga.spellblock.pack.PackDefinition;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-pack enable/disable rules, booster box allowance, and world-loot drop weights
 * loaded from {@code data/spellblock/packs/rules.json}.
 */
public record PackRules(
	Set<String> disabledPacks,
	Set<String> disabledSets,
	Set<String> enabledPacks,
	boolean allowBoosterBoxes,
	Map<String, Integer> lootWeights,
	Map<String, Boolean> boxAllowedOverrides
) {
	public static final PackRules DEFAULTS = new PackRules(Set.of(), Set.of(), Set.of(), true, Map.of(), Map.of());

	public PackRules {
		disabledPacks = disabledPacks == null ? Set.of() : Set.copyOf(disabledPacks);
		disabledSets = disabledSets == null ? Set.of() : Set.copyOf(disabledSets);
		enabledPacks = enabledPacks == null ? Set.of() : Set.copyOf(enabledPacks);
		lootWeights = lootWeights == null ? Map.of() : Map.copyOf(lootWeights);
		boxAllowedOverrides = boxAllowedOverrides == null ? Map.of() : Map.copyOf(boxAllowedOverrides);
	}

	public boolean isPackEnabled(PackDefinition pack) {
		if (pack == null) {
			return false;
		}
		String id = pack.id().toLowerCase(Locale.ROOT);
		String set = pack.setCode().toLowerCase(Locale.ROOT);
		if (disabledPacks.contains(id) || disabledSets.contains(set)) {
			return false;
		}
		return enabledPacks.isEmpty() || enabledPacks.contains(id);
	}

	public boolean isBoxAllowed(PackDefinition pack) {
		if (!allowBoosterBoxes || !isPackEnabled(pack)) {
			return false;
		}
		return boxAllowedOverrides.getOrDefault(pack.id().toLowerCase(Locale.ROOT), true);
	}

	public int lootWeight(PackDefinition pack) {
		if (!isPackEnabled(pack)) {
			return 0;
		}
		return Math.max(0, lootWeights.getOrDefault(pack.id().toLowerCase(Locale.ROOT), 1));
	}

	public PackDefinition pickWeightedPack(
		java.util.List<PackDefinition> allPacks,
		net.minecraft.util.RandomSource random,
		java.util.function.Predicate<PackDefinition> extraFilter
	) {
		if (allPacks == null || allPacks.isEmpty()) {
			return null;
		}
		java.util.List<PackDefinition> eligible = new java.util.ArrayList<>();
		java.util.List<Integer> weights = new java.util.ArrayList<>();
		long total = 0L;
		for (PackDefinition pack : allPacks) {
			if (extraFilter != null && !extraFilter.test(pack)) {
				continue;
			}
			int weight = lootWeight(pack);
			if (weight > 0) {
				eligible.add(pack);
				weights.add(weight);
				total += weight;
			}
		}
		if (total <= 0L || eligible.isEmpty()) {
			return null;
		}
		long roll = (long) (random.nextDouble() * total);
		long cursor = 0L;
		for (int i = 0; i < eligible.size(); i++) {
			cursor += weights.get(i);
			if (roll < cursor) {
				return eligible.get(i);
			}
		}
		return eligible.getLast();
	}

	public static PackRules fromJson(JsonObject root) {
		if (root == null) {
			return DEFAULTS;
		}
		Set<String> disabledPacks = new HashSet<>();
		Set<String> disabledSets = new HashSet<>();
		for (String entry : JsonHelper.stringList(root, "disabled_packs")) {
			String low = entry.toLowerCase(Locale.ROOT);
			if (low.startsWith("set:")) {
				String s = low.substring(4).trim();
				if (!s.isEmpty()) {
					disabledSets.add(s);
				}
			} else {
				disabledPacks.add(low);
			}
		}
		Set<String> enabledPacks = new HashSet<>();
		for (String entry : JsonHelper.stringList(root, "enabled_packs")) {
			enabledPacks.add(entry.toLowerCase(Locale.ROOT));
		}
		boolean allowBoxes = JsonHelper.bool(root, "allow_booster_boxes", true);
		Map<String, Integer> weights = new LinkedHashMap<>(JsonHelper.stringIntMap(root, "loot_weights"));
		Map<String, Boolean> boxOverrides = new LinkedHashMap<>();

		if (root.has("overrides") && root.get("overrides").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("overrides").entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				String id = e.getKey().trim().toLowerCase(Locale.ROOT);
				JsonObject ov = e.getValue().getAsJsonObject();
				if (ov.has("enabled") && !JsonHelper.bool(ov, "enabled", true)) {
					disabledPacks.add(id);
				}
				if (ov.has("loot_weight")) {
					weights.put(id, Math.max(0, JsonHelper.intVal(ov, "loot_weight", 1)));
				}
				if (ov.has("allow_box")) {
					boxOverrides.put(id, JsonHelper.bool(ov, "allow_box", true));
				}
			}
		}

		return new PackRules(disabledPacks, disabledSets, enabledPacks, allowBoxes, weights, boxOverrides);
	}
}
