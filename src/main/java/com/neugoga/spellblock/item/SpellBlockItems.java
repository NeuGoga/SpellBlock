package com.neugoga.spellblock.item;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.dustery.DusteryBlocks;
import com.neugoga.spellblock.pack.PackDefinition;
import com.neugoga.spellblock.pack.Packs;
import com.neugoga.spellblock.table.TableBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SpellBlockItems {
	private static final Map<String, Item> PACKS = new LinkedHashMap<>();
	private static final Map<String, Item> BOOSTER_BOXES = new LinkedHashMap<>();
	private static final Map<String, DeckBoxItem> BOXES = new LinkedHashMap<>();

	private SpellBlockItems() {
	}

	public static void register() {
		SpellBlockComponents.register();
		for (PackDefinition pack : Packs.all()) {
			Item item = new PackItem(new Item.Properties().rarity(pack.rarity()), pack.id());
			Registry.register(BuiltInRegistries.ITEM, SpellBlock.id(pack.itemPath()), item);
			PACKS.put(pack.id(), item);
		}
		for (PackDefinition pack : Packs.all()) {
			if (!pack.hasBox()) {
				continue;
			}
			Item box = new BoosterBoxItem(new Item.Properties().rarity(pack.boxRarity()).stacksTo(16), pack.id(), pack.boxCount());
			Registry.register(BuiltInRegistries.ITEM, SpellBlock.id(pack.boxItemPath()), box);
			BOOSTER_BOXES.put(pack.id(), box);
		}
		for (String color : DeckBoxItem.COLORS) {
			DeckBoxItem item = new DeckBoxItem(new Item.Properties().rarity(Rarity.RARE), color);
			Registry.register(BuiltInRegistries.ITEM, SpellBlock.id("deck_box_" + color), item);
			BOXES.put(color, item);
		}
		if (PACKS.isEmpty() && BOOSTER_BOXES.isEmpty() && BOXES.isEmpty() && TableBlocks.items().isEmpty()) {
			return;
		}
		Item icon = PACKS.isEmpty()
			? (BOXES.isEmpty() ? TableBlocks.items().get(0) : BOXES.values().iterator().next())
			: PACKS.values().iterator().next();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, SpellBlock.id("main"), FabricItemGroup.builder()
			.title(Component.translatable("itemGroup.spellblock"))
			.icon(() -> new ItemStack(icon))
			.displayItems((context, entries) -> {
				for (Item item : PACKS.values()) {
					entries.accept(item);
				}
				for (Item item : BOOSTER_BOXES.values()) {
					entries.accept(item);
				}
				for (Item item : BOXES.values()) {
					entries.accept(item);
				}
				for (Item item : TableBlocks.items()) {
					entries.accept(item);
				}
				if (DusteryBlocks.DUSTERY_ITEM != null) {
					entries.accept(DusteryBlocks.DUSTERY_ITEM);
				}
			})
			.build());
	}

	public static Item packItem(String packId) {
		return PACKS.get(packId);
	}

	public static Item boosterBoxItem(String packId) {
		return BOOSTER_BOXES.get(packId);
	}

	public static DeckBoxItem box(String color) {
		DeckBoxItem item = BOXES.get(color);
		return item == null ? BOXES.get("blue") : item;
	}
}
