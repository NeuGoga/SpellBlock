package com.neugoga.spellblock.datapack;

import com.google.gson.JsonObject;
import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.network.DusteryServer;
import com.neugoga.spellblock.network.TradeServer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Central holder for SpellBlock rules, economy rates, card restrictions, pack weights/toggles,
 * and chest/barrel loot injection settings.
 * <p>
 * On startup, pre-generates default JSON files under {@code config/spellblock/} so modpacks and
 * players can tweak everything directly without writing a datapack. Layer priority (lowest to highest):
 * built-in jar defaults &lt; {@code config/spellblock/*.json} &lt; external datapacks ({@code data/spellblock/...}).
 * Malformed JSON files log a warning and preserve the previous valid configuration.
 */
public final class SpellBlockDataPacks {
	private static final String RULES_PATH = "rules.json";
	private static final String DUST_PATH = "economy/dust.json";
	private static final String CRAFT_PATH = "economy/craft.json";
	private static final String DISABLED_CARDS_PATH = "cards/disabled.json";
	private static final String PACK_RULES_PATH = "packs/rules.json";
	private static final String LOOT_INJECT_PATH = "loot/inject.json";

	private static final ResourceLocation RULES_ID = SpellBlock.id(RULES_PATH);
	private static final ResourceLocation DUST_ID = SpellBlock.id(DUST_PATH);
	private static final ResourceLocation CRAFT_ID = SpellBlock.id(CRAFT_PATH);
	private static final ResourceLocation DISABLED_CARDS_ID = SpellBlock.id(DISABLED_CARDS_PATH);
	private static final ResourceLocation PACK_RULES_ID = SpellBlock.id(PACK_RULES_PATH);
	private static final ResourceLocation LOOT_INJECT_ID = SpellBlock.id(LOOT_INJECT_PATH);

	private static final List<String> MANAGED_FILES = List.of(
		RULES_PATH,
		DUST_PATH,
		CRAFT_PATH,
		DISABLED_CARDS_PATH,
		PACK_RULES_PATH,
		LOOT_INJECT_PATH
	);

	public record Snapshot(
		ModRules rules,
		EconomyRules economy,
		CardRestrictions cards,
		PackRules packs,
		LootConfig loot
	) {
	}

	private static volatile boolean useDefaultConfigDir = true;
	private static volatile Path testConfigDir;
	private static volatile Snapshot current = loadClasspathDefaults();
	private static boolean registered;

	private SpellBlockDataPacks() {
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ensureDefaultConfigs();
		reloadFromConfigOnly();
		ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resourceManager) -> SpellBlockDataPacks.reload(resourceManager));
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return SpellBlock.id("datapacks");
			}

			@Override
			public void onResourceManagerReload(ResourceManager resourceManager) {
				SpellBlockDataPacks.reload(resourceManager);
			}
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
			if (success && server != null) {
				DusteryServer.onDataPackReload(server);
				TradeServer.onDataPackReload(server);
			}
		});
	}

	public static Snapshot current() {
		return current;
	}

	public static ModRules rules() {
		return current.rules();
	}

	public static EconomyRules economy() {
		return current.economy();
	}

	public static CardRestrictions cards() {
		return current.cards();
	}

	public static PackRules packs() {
		return current.packs();
	}

	public static LootConfig loot() {
		return current.loot();
	}

	public static synchronized void setForTests(Snapshot snapshot) {
		current = snapshot == null ? loadClasspathDefaults() : snapshot;
	}

	public static synchronized void setConfigDirForTests(Path dir) {
		testConfigDir = dir;
		useDefaultConfigDir = dir != null;
	}

	public static synchronized void resetDefaults() {
		testConfigDir = null;
		useDefaultConfigDir = true;
		current = loadClasspathDefaults();
	}

	public static synchronized void reloadFromConfigOnly() {
		reload(null);
	}

	public static synchronized void reload(ResourceManager manager) {
		Path cfgDir = resolveConfigDir();
		ensureDefaultConfigs(cfgDir);
		Snapshot prev = current;
		Snapshot defaults = loadClasspathDefaults();

		ModRules nextRules = loadLayered(manager, cfgDir, RULES_ID, RULES_PATH, ModRules::fromJson, defaults.rules(), prev.rules());
		EconomyRules.DustProfile nextDust = loadLayered(manager, cfgDir, DUST_ID, DUST_PATH, EconomyRules.DustProfile::fromJson, defaults.economy().dust(), prev.economy().dust());
		EconomyRules.CraftProfile nextCraft = loadLayered(manager, cfgDir, CRAFT_ID, CRAFT_PATH, EconomyRules.CraftProfile::fromJson, defaults.economy().craft(), prev.economy().craft());
		CardRestrictions nextCards = loadLayered(manager, cfgDir, DISABLED_CARDS_ID, DISABLED_CARDS_PATH, CardRestrictions::fromJson, defaults.cards(), prev.cards());
		PackRules nextPacks = loadLayered(manager, cfgDir, PACK_RULES_ID, PACK_RULES_PATH, PackRules::fromJson, defaults.packs(), prev.packs());
		LootConfig nextLoot = loadLayered(manager, cfgDir, LOOT_INJECT_ID, LOOT_INJECT_PATH, LootConfig::fromJson, defaults.loot(), prev.loot());
		current = new Snapshot(nextRules, new EconomyRules(nextDust, nextCraft), nextCards, nextPacks, nextLoot);
	}

	public static void ensureDefaultConfigs() {
		ensureDefaultConfigs(resolveConfigDir());
	}

	private static void ensureDefaultConfigs(Path cfgDir) {
		if (cfgDir == null) {
			return;
		}
		for (String relPath : MANAGED_FILES) {
			Path target = cfgDir.resolve(relPath);
			if (Files.exists(target)) {
				continue;
			}
			try (InputStream in = SpellBlockDataPacks.class.getResourceAsStream("/data/spellblock/" + relPath)) {
				if (in == null) {
					continue;
				}
				Files.createDirectories(target.getParent());
				Files.write(target, in.readAllBytes());
			} catch (IOException e) {
				SpellBlock.LOGGER.warn("Could not write default config {}: {}", target, e.getMessage());
			}
		}
	}

	private static Path resolveConfigDir() {
		if (testConfigDir != null) {
			return testConfigDir;
		}
		if (!useDefaultConfigDir) {
			return null;
		}
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("spellblock");
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static <T> T loadLayered(
		ResourceManager manager,
		Path cfgDir,
		ResourceLocation id,
		String relPath,
		Function<JsonObject, T> parser,
		T builtinDefault,
		T prevValid
	) {
		// 1. Check external datapacks (highest priority; skip built-in mod/vanilla resources).
		if (manager != null) {
			List<Resource> stack = manager.getResourceStack(id);
			for (int i = stack.size() - 1; i >= 0; i--) {
				Resource res = stack.get(i);
				if (isBuiltinPack(res.sourcePackId())) {
					continue;
				}
				try (InputStream in = res.open()) {
					String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
					return parser.apply(JsonHelper.parseObject(raw));
				} catch (Exception e) {
					SpellBlock.LOGGER.warn("Ignoring malformed datapack file {} from {}: {}", id, res.sourcePackId(), e.getMessage());
				}
			}
		}

		// 2. Check pre-generated local config file under config/spellblock/<relPath>.
		if (cfgDir != null) {
			Path cfgFile = cfgDir.resolve(relPath);
			if (Files.isRegularFile(cfgFile)) {
				try {
					String raw = Files.readString(cfgFile, StandardCharsets.UTF_8);
					return parser.apply(JsonHelper.parseObject(raw));
				} catch (Exception e) {
					SpellBlock.LOGGER.warn("Ignoring malformed config file {}: {}; keeping previous config.", cfgFile, e.getMessage());
					return prevValid;
				}
			}
		}

		// 3. Fall back to built-in jar default (or previous valid snapshot if resource packs failed).
		if (manager != null && !manager.getResourceStack(id).isEmpty()) {
			return prevValid != null ? prevValid : builtinDefault;
		}
		return builtinDefault;
	}

	private static boolean isBuiltinPack(String sourcePackId) {
		if (sourcePackId == null) {
			return false;
		}
		return "fabric".equals(sourcePackId)
			|| "vanilla".equals(sourcePackId)
			|| "spellblock".equals(sourcePackId)
			|| "mod/spellblock".equals(sourcePackId);
	}

	private static Snapshot loadClasspathDefaults() {
		ModRules rules = readClasspath("/data/spellblock/rules.json", ModRules::fromJson, ModRules.DEFAULTS);
		EconomyRules.DustProfile dust = readClasspath("/data/spellblock/economy/dust.json", EconomyRules.DustProfile::fromJson, EconomyRules.DustProfile.DEFAULTS);
		EconomyRules.CraftProfile craft = readClasspath("/data/spellblock/economy/craft.json", EconomyRules.CraftProfile::fromJson, EconomyRules.CraftProfile.DEFAULTS);
		CardRestrictions cards = readClasspath("/data/spellblock/cards/disabled.json", CardRestrictions::fromJson, CardRestrictions.EMPTY);
		PackRules packs = readClasspath("/data/spellblock/packs/rules.json", PackRules::fromJson, PackRules.DEFAULTS);
		LootConfig loot = readClasspath("/data/spellblock/loot/inject.json", LootConfig::fromJson, LootConfig.DEFAULTS);
		return new Snapshot(rules, new EconomyRules(dust, craft), cards, packs, loot);
	}

	private static <T> T readClasspath(String path, Function<JsonObject, T> parser, T fallback) {
		try (InputStream in = SpellBlockDataPacks.class.getResourceAsStream(path)) {
			if (in == null) {
				return fallback;
			}
			String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			return parser.apply(JsonHelper.parseObject(raw));
		} catch (IOException | RuntimeException e) {
			SpellBlock.LOGGER.warn("Could not read built-in {}: {}", path, e.getMessage());
			return fallback;
		}
	}
}
