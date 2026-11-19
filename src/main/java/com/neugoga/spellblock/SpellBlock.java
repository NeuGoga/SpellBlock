package com.neugoga.spellblock;

import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.command.SpellBlockCommands;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.item.SpellBlockItems;
import com.neugoga.spellblock.loot.SpellBlockLoot;
import com.neugoga.spellblock.network.SpellBlockNetworking;
import com.neugoga.spellblock.network.TableLifecycle;
import com.neugoga.spellblock.pack.Packs;
import com.neugoga.spellblock.pack.SetCatalog;
import com.neugoga.spellblock.dustery.DusteryBlocks;
import com.neugoga.spellblock.table.TableBlocks;
import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpellBlock implements ModInitializer {
	public static final String MOD_ID = "spellblock";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Packs.load();
		TableBlocks.register();
		DusteryBlocks.register();
		SpellBlockItems.register();
		SpellBlockLoot.register();
		SpellBlockDataPacks.register();
		SpellBlockNetworking.register();
		TableLifecycle.register();
		SpellBlockCommands.register();
		OracleService.register();
		SetCatalog.register();
		LOGGER.info("SpellBlock loaded. The card index builds when a world starts.");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
