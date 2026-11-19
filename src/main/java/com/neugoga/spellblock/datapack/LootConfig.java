package com.neugoga.spellblock.datapack;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Rarity;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * World chest & barrel loot configuration loaded from {@code data/spellblock/loot/inject.json}.
 */
public record LootConfig(
	boolean enabled,
	boolean allChests,
	float chance,
	int rolls,
	boolean boxEnabled,
	float boxChance,
	Set<ResourceLocation> targets,
	Set<ResourceLocation> excludedTargets,
	Map<ResourceLocation, Float> targetChances,
	Map<ResourceLocation, Integer> targetRolls,
	int commonMin,
	int commonMax,
	int uncommonMin,
	int uncommonMax,
	int rareMin,
	int rareMax,
	int epicMin,
	int epicMax
) {
	public static final LootConfig DEFAULTS = new LootConfig(
		true,
		true,
		0.65f,
		1,
		true,
		0.03f,
		Set.of(
			ResourceLocation.withDefaultNamespace("chests/simple_dungeon"),
			ResourceLocation.withDefaultNamespace("chests/abandoned_mineshaft"),
			ResourceLocation.withDefaultNamespace("chests/shipwreck_treasure"),
			ResourceLocation.withDefaultNamespace("chests/buried_treasure"),
			ResourceLocation.withDefaultNamespace("chests/pillager_outpost"),
			ResourceLocation.withDefaultNamespace("chests/end_city_treasure"),
			ResourceLocation.withDefaultNamespace("chests/ancient_city"),
			ResourceLocation.withDefaultNamespace("chests/trial_chambers/reward"),
			ResourceLocation.withDefaultNamespace("chests/trial_chambers/intersection_barrel")
		),
		Set.of(),
		Map.of(
			ResourceLocation.withDefaultNamespace("chests/end_city_treasure"), 0.85f,
			ResourceLocation.withDefaultNamespace("chests/ancient_city"), 0.85f,
			ResourceLocation.withDefaultNamespace("chests/buried_treasure"), 0.80f
		),
		Map.of(),
		2, 3,
		2, 3,
		1, 2,
		1, 2
	);

	public LootConfig(
		boolean enabled,
		boolean allChests,
		float chance,
		int rolls,
		boolean boxEnabled,
		float boxChance,
		Set<ResourceLocation> targets,
		Set<ResourceLocation> excludedTargets,
		Map<ResourceLocation, Float> targetChances,
		Map<ResourceLocation, Integer> targetRolls
	) {
		this(
			enabled, allChests, chance, rolls, boxEnabled, boxChance,
			targets, excludedTargets, targetChances, targetRolls,
			2, 3, 2, 3, 1, 2, 1, 2
		);
	}

	public LootConfig {
		chance = clamp01(chance);
		rolls = Math.max(0, Math.min(10, rolls));
		boxChance = clamp01(boxChance);
		targets = targets == null ? Set.of() : Set.copyOf(targets);
		excludedTargets = excludedTargets == null ? Set.of() : Set.copyOf(excludedTargets);
		targetChances = targetChances == null ? Map.of() : Map.copyOf(targetChances);
		targetRolls = targetRolls == null ? Map.of() : Map.copyOf(targetRolls);
		commonMin = clampCount(commonMin, 2);
		commonMax = Math.max(commonMin, clampCount(commonMax, 3));
		uncommonMin = clampCount(uncommonMin, 2);
		uncommonMax = Math.max(uncommonMin, clampCount(uncommonMax, 3));
		rareMin = clampCount(rareMin, 1);
		rareMax = Math.max(rareMin, clampCount(rareMax, 2));
		epicMin = clampCount(epicMin, 1);
		epicMax = Math.max(epicMin, clampCount(epicMax, 2));
	}

	public boolean isTarget(ResourceLocation tableId) {
		if (tableId == null || excludedTargets.contains(tableId)) {
			return false;
		}
		if ("spellblock".equals(tableId.getNamespace()) && "chests/pack_roll".equals(tableId.getPath())) {
			return false;
		}
		if (targets.contains(tableId)) {
			return true;
		}
		if (allChests) {
			return isChestPath(tableId.getPath());
		}
		return false;
	}

	private static boolean isChestPath(String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}
		return path.startsWith("chests/") || path.contains("/chests/");
	}

	public float chanceFor(ResourceLocation tableId) {
		if (tableId == null) {
			return chance;
		}
		return targetChances.getOrDefault(tableId, chance);
	}

	public int rollsFor(ResourceLocation tableId) {
		if (tableId == null) {
			return rolls;
		}
		return targetRolls.getOrDefault(tableId, rolls);
	}

	public int minCountFor(Rarity rarity) {
		if (rarity == null) {
			return uncommonMin;
		}
		return switch (rarity) {
			case COMMON -> commonMin;
			case UNCOMMON -> uncommonMin;
			case RARE -> rareMin;
			case EPIC -> epicMin;
		};
	}

	public int maxCountFor(Rarity rarity) {
		if (rarity == null) {
			return uncommonMax;
		}
		return switch (rarity) {
			case COMMON -> commonMax;
			case UNCOMMON -> uncommonMax;
			case RARE -> rareMax;
			case EPIC -> epicMax;
		};
	}

	public int rollPackCount(Rarity rarity, RandomSource random) {
		int min = minCountFor(rarity);
		int max = maxCountFor(rarity);
		if (random == null || max <= min) {
			return min;
		}
		return min + random.nextInt(max - min + 1);
	}

	public static LootConfig fromJson(JsonObject root) {
		if (root == null) {
			return DEFAULTS;
		}
		boolean enabled = JsonHelper.bool(root, "enabled", DEFAULTS.enabled);
		boolean allChests = JsonHelper.bool(root, "all_chests", DEFAULTS.allChests);
		float chance = clamp01((float) JsonHelper.doubleVal(root, "chance", DEFAULTS.chance));
		int rolls = Math.max(0, Math.min(10, JsonHelper.intVal(root, "rolls", DEFAULTS.rolls)));
		boolean boxEnabled = JsonHelper.bool(root, "box_enabled", DEFAULTS.boxEnabled);
		float boxChance = clamp01((float) JsonHelper.doubleVal(root, "box_chance", DEFAULTS.boxChance));

		int commonMin = clampCount(JsonHelper.intVal(root, "common_min", DEFAULTS.commonMin), DEFAULTS.commonMin);
		int commonMax = Math.max(commonMin, clampCount(JsonHelper.intVal(root, "common_max", DEFAULTS.commonMax), DEFAULTS.commonMax));
		int uncommonMin = clampCount(JsonHelper.intVal(root, "uncommon_min", DEFAULTS.uncommonMin), DEFAULTS.uncommonMin);
		int uncommonMax = Math.max(uncommonMin, clampCount(JsonHelper.intVal(root, "uncommon_max", DEFAULTS.uncommonMax), DEFAULTS.uncommonMax));
		int rareMin = clampCount(JsonHelper.intVal(root, "rare_min", DEFAULTS.rareMin), DEFAULTS.rareMin);
		int rareMax = Math.max(rareMin, clampCount(JsonHelper.intVal(root, "rare_max", DEFAULTS.rareMax), DEFAULTS.rareMax));
		int epicMin = clampCount(JsonHelper.intVal(root, "epic_min", DEFAULTS.epicMin), DEFAULTS.epicMin);
		int epicMax = Math.max(epicMin, clampCount(JsonHelper.intVal(root, "epic_max", DEFAULTS.epicMax), DEFAULTS.epicMax));

		Set<ResourceLocation> targets;
		if (root.has("targets") && root.get("targets").isJsonArray()) {
			targets = new LinkedHashSet<>();
			List<String> rawTargets = JsonHelper.stringList(root, "targets");
			for (String raw : rawTargets) {
				ResourceLocation parsed = ResourceLocation.tryParse(raw);
				if (parsed != null) {
					targets.add(parsed);
				}
			}
		} else {
			targets = DEFAULTS.targets;
		}

		Set<ResourceLocation> excludedTargets;
		if (root.has("excluded_targets") && root.get("excluded_targets").isJsonArray()) {
			excludedTargets = new LinkedHashSet<>();
			for (String raw : JsonHelper.stringList(root, "excluded_targets")) {
				ResourceLocation parsed = ResourceLocation.tryParse(raw);
				if (parsed != null) {
					excludedTargets.add(parsed);
				}
			}
		} else {
			excludedTargets = DEFAULTS.excludedTargets;
		}

		Map<ResourceLocation, Float> targetChances = new LinkedHashMap<>();
		if (root.has("target_chances") && root.get("target_chances").isJsonObject()) {
			for (Map.Entry<String, Double> e : JsonHelper.stringDoubleMap(root, "target_chances").entrySet()) {
				ResourceLocation parsed = ResourceLocation.tryParse(e.getKey());
				if (parsed != null) {
					targetChances.put(parsed, clamp01(e.getValue().floatValue()));
				}
			}
		} else if (!root.has("target_chances")) {
			targetChances.putAll(DEFAULTS.targetChances);
		}

		Map<ResourceLocation, Integer> targetRolls = new LinkedHashMap<>();
		if (root.has("target_rolls") && root.get("target_rolls").isJsonObject()) {
			for (Map.Entry<String, Integer> e : JsonHelper.stringIntMap(root, "target_rolls").entrySet()) {
				ResourceLocation parsed = ResourceLocation.tryParse(e.getKey());
				if (parsed != null) {
					targetRolls.put(parsed, Math.max(0, Math.min(10, e.getValue())));
				}
			}
		}

		return new LootConfig(
			enabled, allChests, chance, rolls, boxEnabled, boxChance,
			targets, excludedTargets, targetChances, targetRolls,
			commonMin, commonMax, uncommonMin, uncommonMax, rareMin, rareMax, epicMin, epicMax
		);
	}

	private static float clamp01(float v) {
		if (!Float.isFinite(v) || v < 0f) {
			return 0f;
		}
		return Math.min(1f, v);
	}

	private static int clampCount(int v, int fallback) {
		if (v < 1) {
			return Math.max(1, fallback);
		}
		return Math.min(64, v);
	}
}
