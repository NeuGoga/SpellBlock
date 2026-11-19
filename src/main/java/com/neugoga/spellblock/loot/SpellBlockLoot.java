package com.neugoga.spellblock.loot;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.datapack.LootConfig;
import com.neugoga.spellblock.datapack.ModRules;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers SpellBlock loot types and injects {@link PackRollLootEntry} pools into configured
 * vanilla and modded chest/barrel loot tables.
 */
public final class SpellBlockLoot {
	public static final ResourceKey<LootTable> PACK_ROLL_TABLE = ResourceKey.create(
		Registries.LOOT_TABLE,
		SpellBlock.id("chests/pack_roll")
	);

	public static final LootPoolEntryType PACK_ROLL_ENTRY_TYPE = new LootPoolEntryType(PackRollLootEntry.CODEC);
	public static final LootItemConditionType CHEST_LOOT_CONDITION_TYPE = new LootItemConditionType(SpellBlockLootCondition.CODEC);

	private static final Set<ResourceLocation> WARNED_MISSING = ConcurrentHashMap.newKeySet();
	private static boolean registered;

	private SpellBlockLoot() {
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		Registry.register(BuiltInRegistries.LOOT_POOL_ENTRY_TYPE, SpellBlock.id("pack_roll"), PACK_ROLL_ENTRY_TYPE);
		Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, SpellBlock.id("chest_loot"), CHEST_LOOT_CONDITION_TYPE);

		LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
			if (PACK_ROLL_TABLE.equals(key)) {
				return;
			}
			ModRules rules = SpellBlockDataPacks.rules();
			LootConfig loot = SpellBlockDataPacks.loot();
			if (!rules.chestLoot() || !loot.enabled()) {
				return;
			}
			ResourceLocation tableId = key.location();
			if (!loot.isTarget(tableId)) {
				return;
			}
			int rolls = loot.rollsFor(tableId);
			if (rolls <= 0) {
				return;
			}
			tableBuilder.withPool(
				LootPool.lootPool()
					.setRolls(ConstantValue.exactly(rolls))
					.when(SpellBlockLootCondition.forTable(tableId))
					.add(PackRollLootEntry.builder())
			);
		});

		LootTableEvents.ALL_LOADED.register((resourceManager, lootRegistry) -> {
			SpellBlockDataPacks.reload(resourceManager);
			ModRules rules = SpellBlockDataPacks.rules();
			LootConfig loot = SpellBlockDataPacks.loot();
			if (!rules.chestLoot() || !loot.enabled()) {
				SpellBlock.LOGGER.info("SpellBlock chest loot disabled by rules/config.");
				return;
			}
			SpellBlock.LOGGER.info(
				"SpellBlock chest loot active (all_chests={}, chance={}, rolls={}, explicit_targets={}).",
				loot.allChests(),
				loot.chance(),
				loot.rolls(),
				loot.targets().size()
			);
			for (ResourceLocation target : loot.targets()) {
				if (lootRegistry.containsKey(target)) {
					WARNED_MISSING.remove(target);
				} else if (WARNED_MISSING.add(target)) {
					SpellBlock.LOGGER.warn("Configured SpellBlock loot target '{}' does not exist; skipping.", target);
				}
			}
		});
	}
}
