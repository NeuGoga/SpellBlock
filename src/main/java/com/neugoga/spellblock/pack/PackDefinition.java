package com.neugoga.spellblock.pack;

import java.util.List;

import net.minecraft.world.item.Rarity;

/** One booster's slot table. Weights are relative inside a slot, not percents. */
public record PackDefinition(
	String id,
	String setCode,
	String itemPath,
	String name,
	/** Item name color: common, uncommon, rare, or epic. */
	Rarity rarity,
	List<String> queries,
	List<Slot> slots,
	/** Empty when this pack has no booster box item. */
	String boxItemPath,
	/** Draft boosters granted when the box is opened. 0 means no box. */
	int boxCount,
	Rarity boxRarity
) {
	public boolean hasBox() {
		return boxCount > 0 && boxItemPath != null && !boxItemPath.isBlank();
	}

	public record Slot(List<Option> options) {
	}

	public record Option(String pool, int weight, boolean foil) {
	}
}
