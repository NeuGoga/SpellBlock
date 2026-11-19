package com.neugoga.spellblock.network;

import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.LibraryWorldData;
import com.neugoga.spellblock.library.PlayerLibrary;
import com.neugoga.spellblock.pack.Printing;
import com.neugoga.spellblock.pack.SetCatalog;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds the binder packet on the server thread. Cards are saved already; this only reads them. */
public final class BinderServer {
	private static final ConcurrentHashMap<String, LibraryWorldData.PrintingMeta> REMEMBERED_PRINTINGS = new ConcurrentHashMap<>();

	private BinderServer() {
	}

	public static void rememberPrinting(String printingId, LibraryWorldData.PrintingMeta meta) {
		if (printingId != null && !printingId.isEmpty() && meta != null) {
			REMEMBERED_PRINTINGS.put(printingId, meta);
		}
	}

	public static void rememberAllPrintings(Map<String, LibraryWorldData.PrintingMeta> all) {
		if (all != null && !all.isEmpty()) {
			REMEMBERED_PRINTINGS.putAll(all);
		}
	}

	public static void open(ServerPlayer player) {
		OracleIndex index = OracleService.index();
		if (index == null) {
			String status = OracleService.state() == OracleService.State.FAILED
				? "The card index failed. Run /spellblock reindex."
				: "The card index is still building (" + OracleService.detail() + ").";
			ServerPlayNetworking.send(player, new BinderSyncS2CPayload(List.of(), status, 0, List.of()));
			return;
		}
		PlayerLibrary library = LibraryWorldData.get(player.getServer()).get(player.getUUID());
		if (library != null && repairLibrary(library, index)) {
			LibraryWorldData.get(player.getServer()).setDirty();
		}
		List<BinderCard> cards = new ArrayList<>();
		if (library != null) {
			for (Map.Entry<CardKey, Integer> entry : library.cards().entrySet()) {
				if (entry.getValue() <= 0) {
					continue;
				}
				if (cards.size() >= BinderSyncS2CPayload.MAX_CARDS) {
					break;
				}
				cards.add(describe(index, entry.getKey(), entry.getValue()));
			}
		}
		String status = library != null && library.uniqueCount() > cards.size()
			? "Showing the first " + cards.size() + " sleeves."
			: "";
		int dust = library == null ? 0 : library.dust();
		List<String> favs = encodeFavourites(library);
		ServerPlayNetworking.send(player, new BinderSyncS2CPayload(cards, status, dust, favs));
	}

	public static List<String> encodeFavourites(PlayerLibrary library) {
		List<String> favs = new ArrayList<>();
		if (library == null) {
			return favs;
		}
		for (CardKey key : library.favourites()) {
			favs.add(key.oracleId() + "\0" + key.printingId() + "\0" + key.finish().name());
		}
		return favs;
	}

	public static void toggleFavourite(ServerPlayer player, FavouriteC2SPayload payload) {
		if (payload == null || payload.oracleId() == null || payload.oracleId().isEmpty()) {
			return;
		}
		PlayerLibrary library = LibraryWorldData.get(player.getServer()).getOrCreate(player.getUUID());
		CardKey key = new CardKey(payload.oracleId(), payload.printingId(), Finish.fromName(payload.finish()));
		library.toggleFavourite(key);
		LibraryWorldData.get(player.getServer()).setDirty();
		String refresh = payload.refresh() == null ? "" : payload.refresh();
		if ("dustery".equals(refresh)) {
			DusteryServer.refresh(player);
		} else if (!"pack".equals(refresh)) {
			open(player);
		}
	}

	/** @return true when a saved copy with no printing was moved onto the oracle printing */
	public static boolean repairLibrary(PlayerLibrary library, OracleIndex index) {
		if (library == null || index == null) {
			return false;
		}
		return library.fillPrintings(oracleId -> {
			OracleCard card = index.findByOracleId(oracleId);
			return card == null ? "" : card.printingId();
		});
	}

	public static BinderCard describe(OracleIndex index, CardKey key, int count) {
		Printing printing = SetCatalog.find(key.printingId());
		if (printing != null) {
			return new BinderCard(
				key.oracleId(),
				key.printingId(),
				key.finish().name(),
				count,
				printing.name(),
				printing.manaCost(),
				printing.manaValue(),
				printing.typeLine(),
				printing.colorIdentity(),
				printing.rarity(),
				printing.setCode(),
				printing.treatment()
			);
		}
		OracleCard card = index.findByOracleId(key.oracleId());
		if (card == null) {
			return BinderCard.unknown(key, count);
		}
		LibraryWorldData.PrintingMeta meta = key.printingId().isEmpty() ? null : REMEMBERED_PRINTINGS.get(key.printingId());
		String rarity = meta != null && !meta.rarity().isEmpty() ? meta.rarity() : card.rarity();
		String setCode = meta != null && !meta.setCode().isEmpty() ? meta.setCode() : card.setCode();
		String treatment = meta != null && !meta.treatment().isEmpty() ? meta.treatment() : "normal";
		return new BinderCard(
			key.oracleId(),
			key.printingId(),
			key.finish().name(),
			count,
			card.name(),
			card.manaCost(),
			card.manaValue(),
			card.typeLine(),
			card.colorIdentity(),
			rarity,
			setCode,
			treatment
		);
	}

	/** Central gate checked before adding cards into a binder (trade receive, grant, import, craft). */
	public static boolean canAddCard(String oracleId) {
		if (oracleId == null || oracleId.isEmpty()) {
			return false;
		}
		OracleIndex index = OracleService.index();
		OracleCard card = index == null ? null : index.findByOracleId(oracleId);
		if (card != null) {
			return canAddCard(card);
		}
		return !SpellBlockDataPacks.cards().isDisabled(oracleId, "", "", "", "");
	}

	public static boolean canAddCard(OracleCard card) {
		if (card == null || card.oracleId().isEmpty()) {
			return false;
		}
		return !SpellBlockDataPacks.cards().isDisabled(card);
	}

	public static boolean canAddCard(String oracleId, String name, String setCode, String rarity, String typeLine) {
		if (oracleId == null || oracleId.isEmpty()) {
			return false;
		}
		return !SpellBlockDataPacks.cards().isDisabled(oracleId, name, setCode, rarity, typeLine);
	}
}
