package com.neugoga.spellblock.pack;

/** One card that can appear in a pack. This is a printing, not an owned copy. */
public record Printing(
	String oracleId,
	String printingId,
	String name,
	String manaCost,
	double manaValue,
	String typeLine,
	String colorIdentity,
	String rarity,
	String setCode,
	boolean basic,
	String treatment
) {
	public Printing {
		if (!"retro".equals(treatment) && !"borderless".equals(treatment) && !"special_guest".equals(treatment)) {
			treatment = "normal";
		}
	}

	/** Pool name a pack slot rolls. Special guests ignore rarity. Basics are filed separately. */
	public String poolKey() {
		if ("special_guest".equals(treatment)) {
			return "special_guest";
		}
		if ("normal".equals(treatment)) {
			return rarity;
		}
		return treatment + "_" + rarity;
	}
}
