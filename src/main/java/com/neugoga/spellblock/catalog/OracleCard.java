package com.neugoga.spellblock.catalog;

/** One oracle card, plus the Scryfall id of one printing so a granted copy can show art. */
public record OracleCard(
	String oracleId,
	String name,
	String manaCost,
	double manaValue,
	String typeLine,
	String colorIdentity,
	String rarity,
	String layout,
	String setCode,
	String legalities,
	String rules,
	String printingId,
	/** Related tokens from Scryfall all_parts: printingId|name|typeLine;... */
	String relatedTokens
) {
	public OracleCard {
		oracleId = oracleId == null ? "" : oracleId;
		name = name == null ? "" : name;
		manaCost = manaCost == null ? "" : manaCost;
		typeLine = typeLine == null ? "" : typeLine;
		colorIdentity = colorIdentity == null ? "" : colorIdentity;
		rarity = rarity == null ? "" : rarity;
		layout = layout == null ? "" : layout;
		setCode = setCode == null ? "" : setCode;
		legalities = legalities == null ? "" : legalities;
		rules = rules == null ? "" : rules;
		printingId = printingId == null ? "" : printingId;
		relatedTokens = relatedTokens == null ? "" : relatedTokens;
	}

	public boolean isToken() {
		return "token".equals(layout) || "double_faced_token".equals(layout);
	}
}
