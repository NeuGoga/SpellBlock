package com.neugoga.spellblock.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.neugoga.spellblock.datapack.LootConfig;
import com.neugoga.spellblock.datapack.ModRules;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.Optional;

/**
 * Dynamic loot condition ({@code spellblock:chest_loot}) evaluated at chest/barrel open time against
 * {@link ModRules#chestLoot()} and {@link LootConfig} per-table chance overrides.
 */
public record SpellBlockLootCondition(Optional<ResourceLocation> tableId) implements LootItemCondition {
	public static final MapCodec<SpellBlockLootCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(
			ResourceLocation.CODEC.optionalFieldOf("table").forGetter(SpellBlockLootCondition::tableId)
		).apply(instance, SpellBlockLootCondition::new)
	);

	public static Builder forTable(ResourceLocation tableId) {
		return () -> new SpellBlockLootCondition(Optional.ofNullable(tableId));
	}

	@Override
	public LootItemConditionType getType() {
		return SpellBlockLoot.CHEST_LOOT_CONDITION_TYPE;
	}

	@Override
	public boolean test(LootContext context) {
		if (!SpellBlockDataPacks.rules().chestLoot()) {
			return false;
		}
		LootConfig loot = SpellBlockDataPacks.loot();
		if (!loot.enabled()) {
			return false;
		}
		float chance = tableId.map(loot::chanceFor).orElse(loot.chance());
		return chance > 0f && context.getRandom().nextFloat() < chance;
	}
}
