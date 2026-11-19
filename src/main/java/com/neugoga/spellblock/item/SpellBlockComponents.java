package com.neugoga.spellblock.item;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.DeckBoxData;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

public final class SpellBlockComponents {
	public static final DataComponentType<DeckBoxData> DECK = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		SpellBlock.id("deck"),
		DataComponentType.<DeckBoxData>builder()
			.persistent(DeckBoxData.CODEC)
			.networkSynchronized(DeckBoxData.STREAM_CODEC)
			.build()
	);

	private SpellBlockComponents() {
	}

	public static void register() {
		// Touch the field so the component exists before any deck box item is built.
		if (DECK == null) {
			throw new IllegalStateException("deck component");
		}
	}
}
