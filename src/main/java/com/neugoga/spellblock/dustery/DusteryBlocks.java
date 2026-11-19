package com.neugoga.spellblock.dustery;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class DusteryBlocks {
	public static DusteryBlock DUSTERY;
	public static Item DUSTERY_ITEM;

	private DusteryBlocks() {
	}

	public static void register() {
		if (DUSTERY != null) {
			return;
		}
		DUSTERY = Registry.register(
			BuiltInRegistries.BLOCK,
			SpellBlock.id("dustery"),
			new DusteryBlock(BlockBehaviour.Properties.of()
				.mapColor(MapColor.COLOR_PURPLE)
				.strength(2.0f)
				.sound(SoundType.WOOD)
				.noOcclusion()
				.requiresCorrectToolForDrops())
		);
		DUSTERY_ITEM = Registry.register(
			BuiltInRegistries.ITEM,
			SpellBlock.id("dustery"),
			new BlockItem(DUSTERY, new Item.Properties())
		);
	}
}
