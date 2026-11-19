package com.neugoga.spellblock.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.neugoga.spellblock.datapack.LootConfig;
import com.neugoga.spellblock.datapack.PackRules;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.item.SpellBlockItems;
import com.neugoga.spellblock.pack.PackDefinition;
import com.neugoga.spellblock.pack.Packs;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.List;
import java.util.function.Consumer;

/**
 * Custom loot entry ({@code spellblock:pack_roll}) that rolls a booster pack (or rare booster box)
 * dynamically using live {@link PackRules} weights and {@link LootConfig} settings.
 */
public final class PackRollLootEntry extends LootPoolSingletonContainer {
	public static final MapCodec<PackRollLootEntry> CODEC = RecordCodecBuilder.mapCodec(instance ->
		singletonFields(instance).apply(instance, PackRollLootEntry::new)
	);

	private PackRollLootEntry(
		int weight,
		int quality,
		List<LootItemCondition> conditions,
		List<LootItemFunction> functions
	) {
		super(weight, quality, conditions, functions);
	}

	public static LootPoolSingletonContainer.Builder<?> builder() {
		return simpleBuilder(PackRollLootEntry::new);
	}

	@Override
	public LootPoolEntryType getType() {
		return SpellBlockLoot.PACK_ROLL_ENTRY_TYPE;
	}

	@Override
	protected void createItemStack(Consumer<ItemStack> consumer, LootContext context) {
		ItemStack rolled = rollDrop(
			SpellBlockDataPacks.packs(),
			SpellBlockDataPacks.loot(),
			Packs.all(),
			context.getRandom()
		);
		if (!rolled.isEmpty()) {
			consumer.accept(rolled);
		}
	}

	static ItemStack rollDrop(
		PackRules packRules,
		LootConfig loot,
		List<PackDefinition> allPacks,
		RandomSource random
	) {
		PackDefinition chosen = pickWeightedPack(packRules, allPacks, random, true);
		if (chosen == null) {
			return ItemStack.EMPTY;
		}
		if (loot.boxEnabled() && packRules.isBoxAllowed(chosen) && chosen.hasBox()) {
			Item boxItem = SpellBlockItems.boosterBoxItem(chosen.id());
			if (boxItem != null && random.nextFloat() < loot.boxChance()) {
				return new ItemStack(boxItem);
			}
		}
		Item packItem = SpellBlockItems.packItem(chosen.id());
		if (packItem == null) {
			return ItemStack.EMPTY;
		}
		int count = Math.min(loot.rollPackCount(chosen.rarity(), random), packItem.getDefaultMaxStackSize());
		return new ItemStack(packItem, Math.max(1, count));
	}

	/** Weighted selection delegating to {@link PackRules#pickWeightedPack}. */
	public static PackDefinition pickWeightedPack(
		PackRules packRules,
		List<PackDefinition> allPacks,
		RandomSource random,
		boolean requireRegisteredItem
	) {
		return packRules.pickWeightedPack(
			allPacks,
			random,
			requireRegisteredItem ? pack -> SpellBlockItems.packItem(pack.id()) != null : null
		);
	}
}
