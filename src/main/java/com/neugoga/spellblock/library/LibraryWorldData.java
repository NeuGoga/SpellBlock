package com.neugoga.spellblock.library;

import com.neugoga.spellblock.network.BinderServer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/** Every player's binder, stored on the overworld so it is not an inventory and not per-dimension. */
public final class LibraryWorldData extends SavedData {
	/** Bumped when the NBT layout of this file changes. */
	public static final int DATA_VERSION = 1;

	public static final String ID = "spellblock_libraries";
	/** Pre-rename id; still loaded so existing worlds keep their binders. */
	private static final String LEGACY_ID = "spelltable_libraries";

	private static final SavedData.Factory<LibraryWorldData> FACTORY = new SavedData.Factory<>(
		LibraryWorldData::new,
		LibraryWorldData::load,
		// 1.21.1 requires a datafixer type. A same-version load does not rewrite the file.
		DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES
	);

	private final Map<UUID, PlayerLibrary> libraries = new HashMap<>();
	private final Map<String, PrintingMeta> printingMeta = new HashMap<>();

	public record PrintingMeta(String setCode, String rarity, String treatment) {
		public PrintingMeta {
			setCode = setCode == null ? "" : setCode;
			rarity = rarity == null ? "" : rarity;
			treatment = treatment == null ? "normal" : treatment;
		}
	}

	public static LibraryWorldData get(MinecraftServer server) {
		var storage = server.overworld().getDataStorage();
		LibraryWorldData data = storage.computeIfAbsent(FACTORY, ID);
		if (data.libraries.isEmpty()) {
			LibraryWorldData legacy = storage.get(FACTORY, LEGACY_ID);
			if (legacy != null && !legacy.libraries.isEmpty()) {
				data.libraries.putAll(legacy.libraries);
				data.printingMeta.putAll(legacy.printingMeta);
				BinderServer.rememberAllPrintings(legacy.printingMeta);
				data.setDirty();
			}
		}
		return data;
	}

	public PlayerLibrary get(UUID player) {
		return libraries.get(player);
	}

	public PlayerLibrary getOrCreate(UUID player) {
		return libraries.computeIfAbsent(player, id -> new PlayerLibrary());
	}

	public void rememberPrinting(String printingId, String setCode, String rarity, String treatment) {
		if (printingId == null || printingId.isEmpty()) {
			return;
		}
		PrintingMeta meta = new PrintingMeta(setCode, rarity, treatment);
		PrintingMeta prev = printingMeta.put(printingId, meta);
		BinderServer.rememberPrinting(printingId, meta);
		if (!meta.equals(prev)) {
			setDirty();
		}
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putInt("DataVersion", DATA_VERSION);
		Set<String> referencedPrintings = new HashSet<>();
		ListTag list = new ListTag();
		for (Map.Entry<UUID, PlayerLibrary> entry : libraries.entrySet()) {
			PlayerLibrary library = entry.getValue();
			if (library.uniqueCount() == 0 && library.dust() == 0 && library.favourites().isEmpty()) {
				continue;
			}
			for (CardKey key : library.cards().keySet()) {
				if (!key.printingId().isEmpty()) {
					referencedPrintings.add(key.printingId());
				}
			}
			for (CardKey key : library.favourites()) {
				if (!key.printingId().isEmpty()) {
					referencedPrintings.add(key.printingId());
				}
			}
			list.add(writeLibrary(entry.getKey(), library));
		}
		tag.put("players", list);
		if (!printingMeta.isEmpty() && !referencedPrintings.isEmpty()) {
			ListTag metaList = new ListTag();
			for (Map.Entry<String, PrintingMeta> entry : printingMeta.entrySet()) {
				if (!referencedPrintings.contains(entry.getKey())) {
					continue;
				}
				PrintingMeta meta = entry.getValue();
				CompoundTag m = new CompoundTag();
				m.putString("id", entry.getKey());
				if (!meta.setCode().isEmpty()) {
					m.putString("set", meta.setCode());
				}
				if (!meta.rarity().isEmpty()) {
					m.putString("rarity", meta.rarity());
				}
				if (!meta.treatment().isEmpty() && !"normal".equals(meta.treatment())) {
					m.putString("treatment", meta.treatment());
				}
				metaList.add(m);
			}
			if (!metaList.isEmpty()) {
				tag.put("printing_meta", metaList);
			}
		}
		return tag;
	}

	public static LibraryWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
		LibraryWorldData data = new LibraryWorldData();
		int version = tag.contains("DataVersion") ? tag.getInt("DataVersion") : 0;
		if (version > DATA_VERSION) {
			// Newer mod wrote this world; keep what we can, ignore unknown fields.
		}
		ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			readLibrary(data, list.getCompound(i));
		}
		ListTag metaList = tag.getList("printing_meta", Tag.TAG_COMPOUND);
		for (int i = 0; i < metaList.size(); i++) {
			CompoundTag m = metaList.getCompound(i);
			String id = m.getString("id");
			if (id.isEmpty()) {
				continue;
			}
			String treatment = m.getString("treatment");
			PrintingMeta meta = new PrintingMeta(
				m.getString("set"),
				m.getString("rarity"),
				treatment.isEmpty() ? "normal" : treatment
			);
			data.printingMeta.put(id, meta);
		}
		BinderServer.rememberAllPrintings(data.printingMeta);
		return data;
	}

	private static CompoundTag writeLibrary(UUID id, PlayerLibrary library) {
		CompoundTag tag = new CompoundTag();
		tag.putString("uuid", id.toString());
		tag.putInt("dust", library.dust());
		ListTag cards = new ListTag();
		for (Map.Entry<CardKey, Integer> entry : library.cards().entrySet()) {
			if (entry.getValue() <= 0) {
				continue;
			}
			CardKey key = entry.getKey();
			CompoundTag card = new CompoundTag();
			card.putString("oracle", key.oracleId());
			if (!key.printingId().isEmpty()) {
				card.putString("printing", key.printingId());
			}
			if (key.finish() != Finish.NONFOIL) {
				card.putString("finish", key.finish().name());
			}
			card.putInt("count", entry.getValue());
			cards.add(card);
		}
		tag.put("cards", cards);
		ListTag favs = new ListTag();
		for (CardKey key : library.favourites()) {
			CompoundTag fav = new CompoundTag();
			fav.putString("oracle", key.oracleId());
			if (!key.printingId().isEmpty()) {
				fav.putString("printing", key.printingId());
			}
			if (key.finish() != Finish.NONFOIL) {
				fav.putString("finish", key.finish().name());
			}
			favs.add(fav);
		}
		if (!favs.isEmpty()) {
			tag.put("favourites", favs);
		}
		return tag;
	}

	private static void readLibrary(LibraryWorldData data, CompoundTag tag) {
		String uuidText = tag.getString("uuid");
		if (uuidText.isEmpty()) {
			return;
		}
		UUID id;
		try {
			id = UUID.fromString(uuidText);
		} catch (IllegalArgumentException e) {
			return;
		}
		PlayerLibrary library = data.getOrCreate(id);
		library.setDust(tag.getInt("dust"));
		ListTag cards = tag.getList("cards", Tag.TAG_COMPOUND);
		for (int i = 0; i < cards.size(); i++) {
			CompoundTag card = cards.getCompound(i);
			String oracle = card.getString("oracle");
			int count = card.getInt("count");
			if (oracle.isEmpty() || count <= 0) {
				continue;
			}
			library.add(new CardKey(oracle, card.getString("printing"), Finish.fromName(card.getString("finish"))), count);
		}
		ListTag favs = tag.getList("favourites", Tag.TAG_COMPOUND);
		for (int i = 0; i < favs.size(); i++) {
			CompoundTag fav = favs.getCompound(i);
			String oracle = fav.getString("oracle");
			if (oracle.isEmpty()) {
				continue;
			}
			library.setFavourite(new CardKey(oracle, fav.getString("printing"), Finish.fromName(fav.getString("finish"))), true);
		}
	}
}
