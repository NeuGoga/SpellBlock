package com.neugoga.spellblock.datapack;

import com.google.gson.JsonObject;

/**
 * Feature switches and trade limits loaded from {@code data/spellblock/rules.json}.
 * Any omitted switch defaults to {@code true}.
 */
public record ModRules(
	boolean dustery,
	boolean dust,
	boolean craft,
	boolean trade,
	boolean chestLoot,
	boolean importEnabled,
	boolean grantEnabled,
	int tradeCooldownSeconds,
	int tradeMaxDistance
) {
	public static final ModRules DEFAULTS = new ModRules(true, true, true, true, true, true, true, 60, 64);

	public ModRules {
		tradeCooldownSeconds = Math.max(0, tradeCooldownSeconds);
		tradeMaxDistance = Math.max(1, tradeMaxDistance);
	}

	public static ModRules fromJson(JsonObject root) {
		if (root == null) {
			return DEFAULTS;
		}
		boolean dustery = JsonHelper.bool(root, "dustery", DEFAULTS.dustery);
		boolean dust = JsonHelper.bool(root, "dust", DEFAULTS.dust);
		boolean craft = JsonHelper.bool(root, "craft", DEFAULTS.craft);
		boolean trade = JsonHelper.bool(root, "trade", DEFAULTS.trade);
		boolean chestLoot = JsonHelper.bool(root, "chest_loot", DEFAULTS.chestLoot);
		boolean importEnabled = JsonHelper.bool(root, "import", DEFAULTS.importEnabled);
		boolean grantEnabled = JsonHelper.bool(root, "grant", DEFAULTS.grantEnabled);
		int cooldown = JsonHelper.intVal(root, "trade_cooldown_seconds", DEFAULTS.tradeCooldownSeconds);
		int maxDist = JsonHelper.intVal(root, "trade_max_distance", DEFAULTS.tradeMaxDistance);
		return new ModRules(dustery, dust, craft, trade, chestLoot, importEnabled, grantEnabled, cooldown, maxDist);
	}
}
