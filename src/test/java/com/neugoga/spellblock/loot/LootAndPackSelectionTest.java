package com.neugoga.spellblock.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.neugoga.spellblock.datapack.CardRestrictions;
import com.neugoga.spellblock.datapack.JsonHelper;
import com.neugoga.spellblock.datapack.PackRules;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.network.BinderServer;
import com.neugoga.spellblock.pack.PackDefinition;
import com.neugoga.spellblock.pack.Printing;
import com.neugoga.spellblock.pack.SetPool;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Rarity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LootAndPackSelectionTest {
	@AfterEach
	void reset() {
		SpellBlockDataPacks.resetDefaults();
	}

	@Test
	void weightedPackSelectionRespectsWeightsAndDisables() {
		PackDefinition alpha = pack("alpha_draft", "alp");
		PackDefinition beta = pack("beta_draft", "bet");
		PackDefinition gamma = pack("gamma_draft", "gam");
		PackRules rules = PackRules.fromJson(JsonHelper.parseObject("""
			{
			  "disabled_packs": ["gamma_draft"],
			  "loot_weights": {
			    "alpha_draft": 9,
			    "beta_draft": 1
			  }
			}
			"""));

		RandomSource rng = RandomSource.create(42L);
		Map<String, Integer> counts = new HashMap<>();
		for (int i = 0; i < 400; i++) {
			PackDefinition picked = rules.pickWeightedPack(List.of(alpha, beta, gamma), rng, null);
			assertNotNull(picked);
			counts.merge(picked.id(), 1, Integer::sum);
		}
		assertEquals(0, counts.getOrDefault("gamma_draft", 0));
		assertTrue(counts.getOrDefault("alpha_draft", 0) > counts.getOrDefault("beta_draft", 0) * 3);

		PackRules allZero = PackRules.fromJson(JsonHelper.parseObject("""
			{
			  "loot_weights": {
			    "alpha_draft": 0,
			    "beta_draft": 0,
			    "gamma_draft": 0
			  }
			}
			"""));
		assertNull(allZero.pickWeightedPack(List.of(alpha, beta, gamma), rng, null));
	}

	@Test
	void setPoolSkipsDisabledPrintings() {
		Printing allowed = new Printing("o-good", "p-good", "Opt", "{U}", 1, "Instant", "U", "common", "dom", false, "normal");
		Printing banned = new Printing("o-ban", "p-ban", "Time Walk", "{1}{U}", 2, "Sorcery", "U", "common", "dom", false, "normal");
		SetPool pool = SetPool.from("dom", List.of(allowed, banned), List.of("e:dom"));

		CardRestrictions restrictions = CardRestrictions.fromJson(JsonHelper.parseObject("""
			{
			  "disabled": ["Time Walk"]
			}
			"""));
		RandomSource rng = RandomSource.create(7L);
		for (int i = 0; i < 20; i++) {
			Printing picked = pool.pick("common", rng, Set.of(), card -> !restrictions.isDisabled(card));
			assertNotNull(picked);
			assertEquals("o-good", picked.oracleId());
		}

		CardRestrictions allBlocked = CardRestrictions.fromJson(JsonHelper.parseObject("""
			{
			  "disabled": ["set:dom"]
			}
			"""));
		assertNull(pool.pick("common", rng, Set.of(), card -> !allBlocked.isDisabled(card)));
	}

	@Test
	void binderCanAddCardEnforcesCardRestrictions() {
		CardRestrictions restrictions = CardRestrictions.fromJson(JsonHelper.parseObject("""
			{
			  "disabled": ["Black Lotus"],
			  "disabled_crafting": ["Sol Ring"]
			}
			"""));
		SpellBlockDataPacks.setForTests(new SpellBlockDataPacks.Snapshot(
			SpellBlockDataPacks.rules(),
			SpellBlockDataPacks.economy(),
			restrictions,
			SpellBlockDataPacks.packs(),
			SpellBlockDataPacks.loot()
		));

		assertFalse(BinderServer.canAddCard("o-lotus", "Black Lotus", "vma", "mythic", "Artifact"));
		// disabled_crafting only blocks Dustery craft, not binder insertion (pack/trade/grant)
		assertTrue(BinderServer.canAddCard("o-sol", "Sol Ring", "cmm", "uncommon", "Artifact"));
	}

	private static PackDefinition pack(String id, String set) {
		return new PackDefinition(id, set, id, id, Rarity.COMMON, List.of(), List.of(), "", 0, Rarity.COMMON);
	}
}
