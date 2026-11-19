package com.neugoga.spellblock.table;

import java.util.ArrayList;
import java.util.List;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.item.TableBlockItem;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

public final class TableBlocks {
	public static TableBlock CARD_TABLE;
	public static BlockEntityType<TableBlockEntity> TABLE_BE;
	private static final List<Item> ITEMS = new ArrayList<>();

	private TableBlocks() {
	}

	public static void register() {
		if (CARD_TABLE != null) {
			return;
		}
		CARD_TABLE = Registry.register(
			BuiltInRegistries.BLOCK,
			SpellBlock.id("card_table"),
			new TableBlock(BlockBehaviour.Properties.of()
				.strength(2.0f)
				.sound(SoundType.WOOD)
				.noOcclusion()
				.pushReaction(PushReaction.BLOCK)
				.isRedstoneConductor((state, level, pos) -> false)
				.isSuffocating((state, level, pos) -> false)
				.isViewBlocking((state, level, pos) -> false))
		);
		TABLE_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			SpellBlock.id("card_table"),
			BlockEntityType.Builder.of(TableBlockEntity::new, CARD_TABLE).build(null)
		);
		for (TableWood wood : TableWood.values()) {
			Item item = new TableBlockItem(CARD_TABLE, wood, new Item.Properties());
			Registry.register(BuiltInRegistries.ITEM, SpellBlock.id(wood.itemId()), item);
			ITEMS.add(item);
		}
	}

	public static List<Item> items() {
		return ITEMS;
	}

	public static Item item(TableWood wood) {
		for (Item item : ITEMS) {
			if (item instanceof TableBlockItem table && table.wood() == wood) {
				return item;
			}
		}
		return ITEMS.get(0);
	}
}
