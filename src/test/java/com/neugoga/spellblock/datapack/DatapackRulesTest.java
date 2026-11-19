package com.neugoga.spellblock.datapack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.DustValues;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.pack.PackDefinition;
import com.neugoga.spellblock.pack.Printing;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Rarity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DatapackRulesTest {
	@AfterEach
	void resetDataPacks() {
		SpellBlockDataPacks.resetDefaults();
	}

	@Test
	void modRulesParseSwitchesAndTradeLimits() {
		JsonObject obj = JsonHelper.parseObject("""
			{
			  "dustery": false,
			  "dust": true,
			  "craft": false,
			  "trade": false,
			  "chest_loot": false,
			  "import": false,
			  "grant": true,
			  "trade_cooldown_seconds": 120,
			  "trade_max_distance": 16,
			}
			""");
		ModRules rules = ModRules.fromJson(obj);
		assertFalse(rules.dustery());
		assertTrue(rules.dust());
		assertFalse(rules.craft());
		assertFalse(rules.trade());
		assertFalse(rules.chestLoot());
		assertFalse(rules.importEnabled());
		assertTrue(rules.grantEnabled());
		assertEquals(120, rules.tradeCooldownSeconds());
		assertEquals(16, rules.tradeMaxDistance());
	}

	@Test
	void economyRulesApplyTreatmentFinishGlobalPercentOverridesAndPinnedRecipes() {
		EconomyRules.DustProfile dust = EconomyRules.DustProfile.fromJson(JsonHelper.parseObject("""
			{
			  "enabled": true,
			  "allow_dust_all": false,
			  "percent": 50,
			  "rarity": { "rare": 200 },
			  "finish_multipliers": { "foil": 3.0 },
			  "treatment_multipliers": { "borderless": 2.0 },
			  "overrides": {
			    "Black Lotus": { "dust": 5000, "scale": false },
			    "11111111-1111-1111-1111-111111111111": 300
			  }
			}
			"""));
		EconomyRules.CraftProfile craft = EconomyRules.CraftProfile.fromJson(JsonHelper.parseObject("""
			{
			  "enabled": true,
			  "owned_only": true,
			  "allow_foil": false,
			  "allow_etched": true,
			  "max_batch": 8,
			  "multiplier": 1.0,
			  "allow_treatments": { "special_guest": false },
			  "treatment_multipliers": { "borderless": 4.0 },
			  "overrides": {
			    "Lightning Bolt": { "nonfoil": 100, "foil": 250, "scale": false },
			    "Sol Ring": 500
			  },
			  "recipes": [
			    { "printing_id": "print-pinned", "name": "Sol Ring", "cost": 777, "scale": false },
			    { "name": "Counterspell", "cost": 60, "scale": true }
			  ]
			}
			"""));
		EconomyRules rules = new EconomyRules(dust, craft);

		assertFalse(dust.allowDustAll());
		assertTrue(craft.ownedOnly());
		assertFalse(craft.isFinishAllowed(Finish.FOIL));
		assertTrue(craft.isFinishAllowed(Finish.ETCHED));
		assertFalse(craft.isTreatmentAllowed("special_guest"));
		assertTrue(craft.isTreatmentAllowed("borderless"));
		assertEquals(8, craft.maxBatch());

		// 200 * 0.5 (percent) * 3.0 (foil) * 2.0 (borderless) = 600
		assertEquals(600, DustValues.disenchant("o1", "Custom Card", "rare", "borderless", "FOIL", rules));
		// Flat override ignores multipliers
		assertEquals(5000, DustValues.disenchant("o2", "Black Lotus", "mythic", "borderless", "FOIL", rules));
		// Scaled UUID override: 300 * 0.5 = 150
		assertEquals(150, DustValues.disenchant("11111111-1111-1111-1111-111111111111", "Ignored", "common", "normal", "NONFOIL", rules));

		// Craft rare borderless nonfoil = 400 * 4.0 = 1600
		assertEquals(1600, DustValues.craft("o3", "Any Rare", "p1", "rare", "borderless", "NONFOIL", rules));
		// Craft rare borderless foil = 400 * 2.0 (default foil) * 4.0 (borderless) = 3200
		assertEquals(3200, DustValues.craft("o3", "Any Rare", "p1", "rare", "borderless", "FOIL", rules));
		// Explicit finish flat override
		assertEquals(100, DustValues.craft("o4", "Lightning Bolt", "p2", "common", "borderless", "NONFOIL", rules));
		assertEquals(250, DustValues.craft("o4", "Lightning Bolt", "p2", "common", "borderless", "FOIL", rules));
		// Pinned printing recipe beats card override
		assertEquals(777, DustValues.craft("o5", "Sol Ring", "print-pinned", "uncommon", "borderless", "FOIL", rules));
		// Unpinned Sol Ring falls back to Sol Ring override (500 * 4.0 borderless = 2000)
		assertEquals(2000, DustValues.craft("o5", "Sol Ring", "other-print", "uncommon", "borderless", "NONFOIL", rules));
		// Scaled recipe for Counterspell: 60 * 2.0 foil = 120
		assertEquals(120, DustValues.craft("o6", "Counterspell", "p3", "common", "normal", "FOIL", rules));

		// Set multiplier test
		EconomyRules.DustProfile setDust = EconomyRules.DustProfile.fromJson(JsonHelper.parseObject("""
			{
			  "set_multipliers": { "lea": 5.0, "mh3": 1.5 }
			}
			"""));
		EconomyRules.CraftProfile setCraft = EconomyRules.CraftProfile.fromJson(JsonHelper.parseObject("""
			{
			  "set_multipliers": { "LEA": 10.0 }
			}
			"""));
		EconomyRules setEconomy = new EconomyRules(setDust, setCraft);
		assertEquals(500, DustValues.disenchant("o1", "Shivan Dragon", "lea", "rare", "normal", "NONFOIL", setEconomy));
		assertEquals(100, DustValues.disenchant("o1", "Shivan Dragon", "dom", "rare", "normal", "NONFOIL", setEconomy));
		assertEquals(4000, DustValues.craft("o1", "Shivan Dragon", "p1", "lea", "rare", "normal", "NONFOIL", setEconomy));
		assertEquals(400, DustValues.craft("o1", "Shivan Dragon", "p1", "dom", "rare", "normal", "NONFOIL", setEconomy));

		// StreamCodec round-trip
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		EconomyRules.CODEC.encode(buf, setEconomy);
		EconomyRules decoded = EconomyRules.CODEC.decode(buf);
		assertEquals(setEconomy, decoded);
	}

	@Test
	void cardRestrictionsDistinguishGlobalDisableFromCraftOnlyDisable() {
		CardRestrictions restrictions = CardRestrictions.fromJson(JsonHelper.parseObject("""
			{
			  "disabled": [
			    "22222222-2222-2222-2222-222222222222",
			    "Black Lotus",
			    "set:lea"
			  ],
			  "disabled_crafting": [
			    "rarity:mythic",
			    "type:Planeswalker",
			    "Sol Ring"
			  ]
			}
			"""));

		Printing lotus = new Printing("o-lotus", "p1", "Black Lotus", "{0}", 0, "Artifact", "", "rare", "vma", false, "normal");
		assertTrue(restrictions.isDisabled(lotus));
		assertTrue(restrictions.isCraftBlocked(lotus.oracleId(), lotus.name(), lotus.setCode(), lotus.rarity(), lotus.typeLine()));

		OracleCard leaCard = oracle("o-lea", "p2", "Grizzly Bears", "Creature — Bear", "common", "lea");
		assertTrue(restrictions.isDisabled(leaCard));
		assertTrue(restrictions.isCraftBlocked(leaCard));

		OracleCard mythicCard = oracle("o-m", "p3", "Force of Will", "Instant", "mythic", "dmr");
		assertFalse(restrictions.isDisabled(mythicCard));
		assertTrue(restrictions.isCraftBlocked(mythicCard));

		BinderCard walker = new BinderCard("o-pw", "p4", "NONFOIL", 1, "Jace, the Mind Sculptor", "{2}{U}{U}", 4, "Legendary Planeswalker — Jace", "U", "rare", "a25", "normal");
		assertFalse(restrictions.isDisabled("o-pw", walker.name(), walker.setCode(), walker.rarity(), walker.typeLine()));
		assertTrue(restrictions.isCraftBlocked(walker));

		OracleCard solRing = oracle("o-sr", "p5", "Sol Ring", "Artifact", "uncommon", "cmm");
		assertFalse(restrictions.isDisabled(solRing));
		assertTrue(restrictions.isCraftBlocked(solRing));

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		CardRestrictions.CODEC.encode(buf, restrictions);
		CardRestrictions decoded = CardRestrictions.CODEC.decode(buf);
		assertEquals(restrictions.disabledEntries(), decoded.disabledEntries());
		assertEquals(restrictions.craftDisabledEntries(), decoded.craftDisabledEntries());
	}

	@Test
	void packRulesSupportDisableEnableWeightsAndBoxOverrides() {
		PackRules rules = PackRules.fromJson(JsonHelper.parseObject("""
			{
			  "disabled_packs": ["set:eld", "custom_pack"],
			  "allow_booster_boxes": true,
			  "loot_weights": {
			    "dmu_draft": 5,
			    "mkm_draft": 0
			  },
			  "overrides": {
			    "fdn_draft": { "loot_weight": 3, "allow_box": false },
			    "disabled_via_ov": { "enabled": false }
			  }
			}
			"""));

		PackDefinition eld = pack("eld_draft", "eld", true);
		PackDefinition fdn = pack("fdn_draft", "fdn", true);
		PackDefinition dmu = pack("dmu_draft", "dmu", true);
		PackDefinition mkm = pack("mkm_draft", "mkm", true);
		PackDefinition ovDisabled = pack("disabled_via_ov", "neo", true);

		assertFalse(rules.isPackEnabled(eld));
		assertEquals(0, rules.lootWeight(eld));
		assertFalse(rules.isBoxAllowed(eld));

		assertFalse(rules.isPackEnabled(ovDisabled));
		assertTrue(rules.isPackEnabled(fdn));
		assertFalse(rules.isBoxAllowed(fdn));
		assertEquals(3, rules.lootWeight(fdn));

		assertTrue(rules.isPackEnabled(dmu));
		assertTrue(rules.isBoxAllowed(dmu));
		assertEquals(5, rules.lootWeight(dmu));

		assertTrue(rules.isPackEnabled(mkm));
		assertEquals(0, rules.lootWeight(mkm));
	}

	@Test
	void lootConfigParsesCustomTargetsAndSkipsInvalidIds() {
		LootConfig cfg = LootConfig.fromJson(JsonHelper.parseObject("""
			{
			  "enabled": true,
			  "chance": 0.4,
			  "rolls": 2,
			  "box_enabled": false,
			  "box_chance": 0.05,
			  "targets": [
			    "minecraft:chests/simple_dungeon",
			    "somemod:chests/vault",
			    "not valid resource location!!!"
			  ],
			  "target_chances": {
			    "somemod:chests/vault": 0.9
			  },
			  "target_rolls": {
			    "somemod:chests/vault": 3
			  }
			}
			"""));

		ResourceLocation vault = ResourceLocation.fromNamespaceAndPath("somemod", "chests/vault");
		ResourceLocation dungeon = ResourceLocation.withDefaultNamespace("chests/simple_dungeon");
		assertEquals(2, cfg.targets().size());
		assertTrue(cfg.isTarget(vault));
		assertTrue(cfg.isTarget(dungeon));
		assertFalse(cfg.boxEnabled());
		assertEquals(0.9f, cfg.chanceFor(vault), 0.0001f);
		assertEquals(0.4f, cfg.chanceFor(dungeon), 0.0001f);
		assertEquals(3, cfg.rollsFor(vault));
		assertEquals(2, cfg.rollsFor(dungeon));
	}

	@Test
	void lootConfigAllChestsMatchesVanillaAndModdedChestsAndRespectsExclusions() {
		LootConfig defaults = LootConfig.DEFAULTS;
		assertTrue(defaults.allChests());
		assertTrue(defaults.isTarget(ResourceLocation.withDefaultNamespace("chests/village/village_weaponsmith")));
		assertTrue(defaults.isTarget(ResourceLocation.fromNamespaceAndPath("betterdungeons", "zombie_dungeon/chests/common")));
		assertTrue(defaults.isTarget(ResourceLocation.fromNamespaceAndPath("lootr", "chests/treasure")));
		assertFalse(defaults.isTarget(ResourceLocation.fromNamespaceAndPath("spellblock", "chests/pack_roll")));
		assertFalse(defaults.isTarget(ResourceLocation.withDefaultNamespace("entities/zombie")));
		assertFalse(defaults.isTarget(ResourceLocation.withDefaultNamespace("blocks/stone")));

		LootConfig restricted = LootConfig.fromJson(JsonHelper.parseObject("""
			{
			  "all_chests": true,
			  "excluded_targets": ["minecraft:chests/spawn_bonus_chest"]
			}
			"""));
		assertFalse(restricted.isTarget(ResourceLocation.withDefaultNamespace("chests/spawn_bonus_chest")));
		assertTrue(restricted.isTarget(ResourceLocation.withDefaultNamespace("chests/desert_pyramid")));

		LootConfig explicitOnly = LootConfig.fromJson(JsonHelper.parseObject("""
			{
			  "all_chests": false,
			  "targets": ["somemod:vault/reward"]
			}
			"""));
		assertTrue(explicitOnly.isTarget(ResourceLocation.fromNamespaceAndPath("somemod", "vault/reward")));
		assertFalse(explicitOnly.isTarget(ResourceLocation.withDefaultNamespace("chests/simple_dungeon")));
	}

	@Test
	void lootConfigRollsExpectedPackQuantitiesByRarity() {
		LootConfig defaults = LootConfig.DEFAULTS;
		net.minecraft.util.RandomSource rng = net.minecraft.util.RandomSource.create(123L);
		for (int i = 0; i < 40; i++) {
			int common = defaults.rollPackCount(Rarity.COMMON, rng);
			int uncommon = defaults.rollPackCount(Rarity.UNCOMMON, rng);
			int rare = defaults.rollPackCount(Rarity.RARE, rng);
			int epic = defaults.rollPackCount(Rarity.EPIC, rng);
			assertTrue(common >= 2 && common <= 3);
			assertTrue(uncommon >= 2 && uncommon <= 3);
			assertTrue(rare >= 1 && rare <= 2);
			assertTrue(epic >= 1 && epic <= 2);
		}

		LootConfig custom = LootConfig.fromJson(JsonHelper.parseObject("""
			{
			  "uncommon_min": 4,
			  "uncommon_max": 5,
			  "rare_min": 2,
			  "rare_max": 2
			}
			"""));
		assertEquals(4, custom.minCountFor(Rarity.UNCOMMON));
		assertEquals(5, custom.maxCountFor(Rarity.UNCOMMON));
		assertEquals(2, custom.rollPackCount(Rarity.RARE, rng));
	}

	@Test
	void builtInRecipeJsonKeysUseIngredientObjects() throws Exception {
		List<String> recipeNames = List.of(
			"acacia_card_table.json",
			"bamboo_card_table.json",
			"birch_card_table.json",
			"cherry_card_table.json",
			"crimson_card_table.json",
			"dark_oak_card_table.json",
			"jungle_card_table.json",
			"mangrove_card_table.json",
			"oak_card_table.json",
			"spruce_card_table.json",
			"warped_card_table.json",
			"deck_box.json",
			"dustery.json"
		);
		for (String name : recipeNames) {
			try (var in = DatapackRulesTest.class.getResourceAsStream("/data/spellblock/recipe/" + name)) {
				assertTrue(in != null, "Missing recipe resource: " + name);
				JsonObject recipe = JsonHelper.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				JsonObject key = recipe.getAsJsonObject("key");
				assertTrue(key != null && !key.isEmpty(), "Missing key object in " + name);
				for (var entry : key.entrySet()) {
					assertTrue(entry.getValue().isJsonObject(), "Recipe " + name + " key '" + entry.getKey() + "' must be a JSON object");
				}
			}
		}
	}

	@Test
	void datapackReloadUsesHighestValidLayerAndFallsBackOnMalformedJson() {
		SpellBlockDataPacks.setConfigDirForTests(null);
		ResourceLocation rulesId = ResourceLocation.fromNamespaceAndPath("spellblock", "rules.json");
		ResourceLocation lootId = ResourceLocation.fromNamespaceAndPath("spellblock", "loot/inject.json");

		ResourceManager manager = new FakeLayeredResourceManager(Map.of(
			rulesId, List.of(
				res("base", "{ \"dustery\": true, \"trade\": false }"),
				res("override", "{ \"dustery\": false, \"trade\": true }")
			),
			lootId, List.of(
				res("base", "{ \"enabled\": true, \"chance\": 0.5, \"targets\": [\"minecraft:chests/simple_dungeon\"] }"),
				res("broken_top", "{ \"enabled\": ")
			)
		));

		SpellBlockDataPacks.reload(manager);
		assertFalse(SpellBlockDataPacks.rules().dustery());
		assertTrue(SpellBlockDataPacks.rules().trade());
		// Broken higher layer falls back to valid lower layer ("base")
		assertEquals(0.5f, SpellBlockDataPacks.loot().chance(), 0.0001f);
		assertEquals(1, SpellBlockDataPacks.loot().targets().size());

		// If every layer is broken, preserves previous valid snapshot
		ResourceManager allBroken = new FakeLayeredResourceManager(Map.of(
			rulesId, List.of(res("broken", "[not-an-object]"))
		));
		SpellBlockDataPacks.reload(allBroken);
		assertFalse(SpellBlockDataPacks.rules().dustery());
		assertTrue(SpellBlockDataPacks.rules().trade());
	}

	@Test
	void pregeneratedConfigsLoadDefaultsAndAllowDatapackOverrides(@TempDir Path tempCfg) throws Exception {
		SpellBlockDataPacks.setConfigDirForTests(tempCfg);
		SpellBlockDataPacks.reloadFromConfigOnly();

		assertTrue(Files.isRegularFile(tempCfg.resolve("rules.json")));
		assertTrue(Files.isRegularFile(tempCfg.resolve("loot/inject.json")));
		assertTrue(Files.isRegularFile(tempCfg.resolve("packs/rules.json")));
		assertTrue(Files.isRegularFile(tempCfg.resolve("cards/disabled.json")));
		assertTrue(Files.isRegularFile(tempCfg.resolve("economy/dust.json")));
		assertTrue(Files.isRegularFile(tempCfg.resolve("economy/craft.json")));
		assertTrue(SpellBlockDataPacks.loot().allChests());
		assertTrue(SpellBlockDataPacks.rules().chestLoot());

		// Editing config/spellblock/loot/inject.json updates active loot rules on reload
		Files.writeString(tempCfg.resolve("loot/inject.json"), "{ \"chance\": 0.75, \"all_chests\": false, \"targets\": [\"minecraft:chests/end_city_treasure\"] }");
		SpellBlockDataPacks.reloadFromConfigOnly();
		assertFalse(SpellBlockDataPacks.loot().allChests());
		assertEquals(0.75f, SpellBlockDataPacks.loot().chance(), 0.0001f);

		// External datapack overrides local config
		ResourceLocation lootId = ResourceLocation.fromNamespaceAndPath("spellblock", "loot/inject.json");
		ResourceManager withDatapack = new FakeLayeredResourceManager(Map.of(
			lootId, List.of(
				res("fabric", "{ \"chance\": 0.1 }"),
				res("file/modpack", "{ \"chance\": 0.9, \"all_chests\": true }")
			)
		));
		SpellBlockDataPacks.reload(withDatapack);
		assertTrue(SpellBlockDataPacks.loot().allChests());
		assertEquals(0.9f, SpellBlockDataPacks.loot().chance(), 0.0001f);
	}

	private static OracleCard oracle(String oracleId, String printingId, String name, String typeLine, String rarity, String setCode) {
		return new OracleCard(oracleId, name, "", 0.0, typeLine, "", rarity, "normal", setCode, "", "", printingId, "");
	}

	private static PackDefinition pack(String id, String set, boolean hasBox) {
		return new PackDefinition(
			id,
			set,
			id,
			id,
			Rarity.COMMON,
			List.of(),
			List.of(),
			hasBox ? id + "_box" : "",
			hasBox ? 36 : 0,
			Rarity.RARE
		);
	}

	private static Resource res(String packId, String json) {
		PackResources dummyPack = new net.minecraft.server.packs.VanillaPackResourcesBuilder().build(
			new PackLocationInfo(packId, net.minecraft.network.chat.Component.literal(packId), PackSource.BUILT_IN, Optional.empty())
		);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		return new Resource(dummyPack, () -> new ByteArrayInputStream(bytes));
	}

	private static final class FakeLayeredResourceManager extends MultiPackResourceManager {
		private final Map<ResourceLocation, List<Resource>> stacks;

		FakeLayeredResourceManager(Map<ResourceLocation, List<Resource>> stacks) {
			super(PackType.SERVER_DATA, List.of());
			this.stacks = stacks;
		}

		@Override
		public List<Resource> getResourceStack(ResourceLocation location) {
			return stacks.getOrDefault(location, List.of());
		}
	}
}
