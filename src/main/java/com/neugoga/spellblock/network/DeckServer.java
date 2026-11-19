package com.neugoga.spellblock.network;

import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.item.DeckBoxItem;
import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.item.SpellBlockItems;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.library.DeckEntry;
import com.neugoga.spellblock.library.DeckFormat;
import com.neugoga.spellblock.library.DeckRules;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.LibraryWorldData;
import com.neugoga.spellblock.library.PlayerLibrary;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Moves cards between a binder and the deck box in a player's hand. Server thread only. */
public final class DeckServer {
	private DeckServer() {
	}

	public static void open(ServerPlayer player, int slot) {
		send(player, slot, "");
	}

	public static void edit(ServerPlayer player, DeckEditC2SPayload payload) {
		int slot = payload.slot();
		ItemStack stack = stackAt(player, slot);
		if (!(stack.getItem() instanceof DeckBoxItem box)) {
			ServerPlayNetworking.send(player, DeckSyncS2CPayload.closed());
			return;
		}
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		data = attachPrintings(player, stack, data);
		if (data.locked() && !owns(player, data)) {
			send(player, slot, "Locked.");
			return;
		}
		String action = payload.action();
		String status = "";
		switch (action) {
			case "name" -> data = data.withName(payload.text());
			case "format" -> {
				if (DeckFormat.fromId(payload.text()).id().equals(payload.text())) {
					data = data.withFormat(payload.text());
				}
			}
			case "color" -> {
				if (!DeckBoxItem.knownColor(payload.text())) {
					break;
				}
				data = write(player, slot, stack, box, data, "");
				ItemStack next = new ItemStack(SpellBlockItems.box(payload.text()));
				next.set(SpellBlockComponents.DECK, data);
				applyName(next, data);
				player.getInventory().setItem(slot, next);
				send(player, slot, "");
				return;
			}
			case "lock" -> {
				if (!data.locked()) {
					data = data.lockedBy(player.getUUID().toString());
				}
			}
			case "unlock" -> {
				if (data.locked() && owns(player, data)) {
					data = data.unlocked();
				}
			}
			case "add", "side_add" -> {
				CardKey key = key(payload);
				if (key == null) {
					break;
				}
				if (data.total() >= DeckBoxData.MAX_CARDS) {
					status = "This box holds " + DeckBoxData.MAX_CARDS + " cards.";
					break;
				}
				DeckBoxData.Zone zone = "side_add".equals(action) ? DeckBoxData.Zone.SIDE : DeckBoxData.Zone.MAIN;
				List<DeckEntry> zoneList = zone == DeckBoxData.Zone.SIDE ? data.side() : data.main();
				boolean stacked = false;
				for (DeckEntry entry : zoneList) {
					if (!entry.same(key)) {
						continue;
					}
					stacked = true;
					if (entry.count() >= DeckEntry.MAX_COUNT) {
						status = "That stack is full.";
					}
					break;
				}
				if (!status.isEmpty()) {
					break;
				}
				if (!stacked && zoneList.size() >= DeckBoxData.MAX_LINES) {
					status = "Too many different cards.";
					break;
				}
				LibraryWorldData world = LibraryWorldData.get(player.getServer());
				PlayerLibrary library = world.getOrCreate(player.getUUID());
				if (library.count(key) <= 0) {
					status = "That card isn't in your binder.";
					break;
				}
				library.add(key, -1);
				world.setDirty();
				data = data.add(zone, key);
			}
			case "move" -> {
				CardKey key = key(payload);
				if (key == null) {
					break;
				}
				DeckBoxData.Zone from = "side".equals(payload.text()) ? DeckBoxData.Zone.SIDE : DeckBoxData.Zone.MAIN;
				DeckBoxData next = data.move(from, key);
				if (next == null) {
					status = "That card can't move.";
					break;
				}
				data = next;
			}
			case "remove", "side_remove" -> {
				CardKey key = key(payload);
				if (key == null) {
					break;
				}
				DeckBoxData.Zone zone = "side_remove".equals(action) ? DeckBoxData.Zone.SIDE : DeckBoxData.Zone.MAIN;
				DeckBoxData next = data.remove(zone, key);
				if (next == null) {
					break;
				}
				data = next;
				LibraryWorldData world = LibraryWorldData.get(player.getServer());
				world.getOrCreate(player.getUUID()).add(key, 1);
				world.setDirty();
			}
			case "commander" -> {
				CardKey key = key(payload);
				if (key == null || !DeckFormat.fromId(data.format()).commander()) {
					break;
				}
				int commanderSlot = "1".equals(payload.text()) ? 1 : 0;
				if (commanderSlot == 1 && data.commanders().isEmpty()) {
					commanderSlot = 0;
				}
				boolean replacing = commanderSlot < data.commanders().size();
				if (replacing && data.commanders().get(commanderSlot).same(key)) {
					break;
				}
				LibraryWorldData world = LibraryWorldData.get(player.getServer());
				PlayerLibrary library = world.getOrCreate(player.getUUID());
				if (library.count(key) <= 0) {
					status = "That card isn't in your binder.";
					break;
				}
				if (!replacing && data.total() >= DeckBoxData.MAX_CARDS) {
					status = "This box holds " + DeckBoxData.MAX_CARDS + " cards.";
					break;
				}
				library.add(key, -1);
				DeckBoxData.ReplaceCommander replaced = data.putCommander(commanderSlot, key);
				if (replaced.previous() != null) {
					library.add(replaced.previous().key(), replaced.previous().count());
				}
				world.setDirty();
				data = replaced.data();
			}
			case "commander_clear" -> {
				int commanderSlot = "1".equals(payload.text()) ? 1 : 0;
				DeckBoxData.ReplaceCommander replaced = data.clearCommander(commanderSlot);
				if (replaced.previous() == null) {
					break;
				}
				LibraryWorldData world = LibraryWorldData.get(player.getServer());
				world.getOrCreate(player.getUUID()).add(replaced.previous().key(), replaced.previous().count());
				world.setDirty();
				data = replaced.data();
			}
			default -> {
				return;
			}
		}
		write(player, slot, stack, box, data, status);
		send(player, slot, status);
	}

	private static DeckBoxData attachPrintings(ServerPlayer player, ItemStack stack, DeckBoxData data) {
		OracleIndex index = OracleService.index();
		if (index == null) {
			return data;
		}
		LibraryWorldData world = LibraryWorldData.get(player.getServer());
		if (BinderServer.repairLibrary(world.get(player.getUUID()), index)) {
			world.setDirty();
		}
		DeckBoxData next = data.fillPrintings(oracleId -> {
			OracleCard card = index.findByOracleId(oracleId);
			return card == null ? "" : card.printingId();
		});
		if (next != data) {
			stack.set(SpellBlockComponents.DECK, next);
			applyName(stack, next);
		}
		return next;
	}

	private static DeckBoxData write(ServerPlayer player, int slot, ItemStack stack, DeckBoxItem box, DeckBoxData data, String status) {
		stack.set(SpellBlockComponents.DECK, data);
		applyName(stack, data);
		return data;
	}

	private static void send(ServerPlayer player, int slot, String status) {
		ItemStack stack = stackAt(player, slot);
		if (!(stack.getItem() instanceof DeckBoxItem box)) {
			ServerPlayNetworking.send(player, DeckSyncS2CPayload.closed());
			return;
		}
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		data = attachPrintings(player, stack, data);
		OracleIndex index = OracleService.index();
		LibraryWorldData world = LibraryWorldData.get(player.getServer());
		PlayerLibrary library = world.get(player.getUUID());
		List<BinderCard> binder = new ArrayList<>();
		if (library != null && index != null) {
			for (Map.Entry<CardKey, Integer> entry : library.cards().entrySet()) {
				if (entry.getValue() <= 0) {
					continue;
				}
				if (binder.size() >= BinderSyncS2CPayload.MAX_CARDS) {
					break;
				}
				binder.add(BinderServer.describe(index, entry.getKey(), entry.getValue()));
			}
		}
		String note = status == null ? "" : status;
		if (index == null && note.isEmpty()) {
			note = OracleService.state() == OracleService.State.FAILED
				? "The card index failed. Run /spellblock reindex."
				: "The card index is still building.";
		}
		ServerPlayNetworking.send(player, new DeckSyncS2CPayload(
			slot,
			data.name(),
			data.format(),
			box.color(),
			data.locked(),
			owns(player, data),
			commanderCard(index, data, 0),
			commanderCard(index, data, 1),
			describeAll(index, data.main()),
			describeAll(index, data.side()),
			binder,
			DeckRules.check(index, data),
			note
		));
	}

	private static BinderCard commanderCard(OracleIndex index, DeckBoxData data, int slot) {
		if (slot >= data.commanders().size()) {
			return DeckSyncS2CPayload.emptyCard();
		}
		DeckEntry entry = data.commanders().get(slot);
		if (index == null) {
			return BinderCard.unknown(entry.key(), 1);
		}
		return BinderServer.describe(index, entry.key(), 1);
	}

	private static List<BinderCard> describeAll(OracleIndex index, List<DeckEntry> entries) {
		List<BinderCard> cards = new ArrayList<>(entries.size());
		for (DeckEntry entry : entries) {
			if (index == null) {
				cards.add(BinderCard.unknown(entry.key(), entry.count()));
			} else {
				cards.add(BinderServer.describe(index, entry.key(), entry.count()));
			}
		}
		return cards;
	}

	private static void applyName(ItemStack stack, DeckBoxData data) {
		if (data.name().isEmpty()) {
			stack.remove(DataComponents.CUSTOM_NAME);
		} else {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(data.name()));
		}
	}

	private static boolean owns(ServerPlayer player, DeckBoxData data) {
		return !data.locked() || data.owner().equals(player.getUUID().toString());
	}

	private static CardKey key(DeckEditC2SPayload payload) {
		if (payload.oracleId().isEmpty() || payload.oracleId().length() > 80 || payload.printingId().length() > 80) {
			return null;
		}
		try {
			return new CardKey(payload.oracleId(), payload.printingId(), Finish.fromName(payload.finish()));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static ItemStack stackAt(ServerPlayer player, int slot) {
		Inventory inventory = player.getInventory();
		if (slot < 0 || slot >= inventory.getContainerSize()) {
			return ItemStack.EMPTY;
		}
		return inventory.getItem(slot);
	}
}
