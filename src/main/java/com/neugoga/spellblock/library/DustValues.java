package com.neugoga.spellblock.library;

import com.neugoga.spellblock.datapack.EconomyRules;

/** Dust gained when disenchanting and spent when crafting. Foil is double by default. */
public final class DustValues {
	private DustValues() {
	}

	public static int disenchant(String rarity, String finish) {
		return EconomyRules.DEFAULTS.disenchant("", "", rarity, "normal", finish);
	}

	public static int disenchant(BinderCard card, EconomyRules rules) {
		if (card == null) {
			return 0;
		}
		EconomyRules active = rules == null ? EconomyRules.DEFAULTS : rules;
		return active.disenchant(card.oracleId(), card.name(), card.setCode(), card.rarity(), card.treatment(), card.finish());
	}

	public static int disenchant(
		String oracleId,
		String cardName,
		String rarity,
		String treatment,
		String finish,
		EconomyRules rules
	) {
		return disenchant(oracleId, cardName, "", rarity, treatment, finish, rules);
	}

	public static int disenchant(
		String oracleId,
		String cardName,
		String setCode,
		String rarity,
		String treatment,
		String finish,
		EconomyRules rules
	) {
		EconomyRules active = rules == null ? EconomyRules.DEFAULTS : rules;
		return active.disenchant(oracleId, cardName, setCode, rarity, treatment, finish);
	}

	public static int craft(String rarity, String finish) {
		return EconomyRules.DEFAULTS.craftCost("", "", "", "", rarity, "normal", finish);
	}

	public static int craft(
		String oracleId,
		String cardName,
		String printingId,
		String rarity,
		String treatment,
		String finish,
		EconomyRules rules
	) {
		return craft(oracleId, cardName, printingId, "", rarity, treatment, finish, rules);
	}

	public static int craft(
		String oracleId,
		String cardName,
		String printingId,
		String setCode,
		String rarity,
		String treatment,
		String finish,
		EconomyRules rules
	) {
		EconomyRules active = rules == null ? EconomyRules.DEFAULTS : rules;
		return active.craftCost(oracleId, cardName, printingId, setCode, rarity, treatment, finish);
	}
}
