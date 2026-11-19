package com.neugoga.spellblock.network.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.table.SeatZones;
import com.neugoga.spellblock.table.TableBlock;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableCard;
import com.neugoga.spellblock.table.TableSeat;
import com.neugoga.spellblock.table.TableZone;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.neugoga.spellblock.network.table.TableState.SearchAsk;
import com.neugoga.spellblock.network.table.TableState.TableRef;
import com.neugoga.spellblock.network.table.TableState.Watching;
import static com.neugoga.spellblock.network.table.Looks.startLook;
import static com.neugoga.spellblock.network.table.TableState.CONTROL_ASKS;
import static com.neugoga.spellblock.network.table.TableState.SEARCH_ASKS;
import static com.neugoga.spellblock.network.table.TableState.WATCHING;
import static com.neugoga.spellblock.network.table.TableSync.refreshSitters;
import static com.neugoga.spellblock.network.table.TableSync.send;
import static com.neugoga.spellblock.network.table.TableText.pretty;
import static com.neugoga.spellblock.network.table.TableText.seatOf;

/**
 * Asking another seat for something and their answer: searching their library, or taking control of a card.
 */
public final class Requests {
	private Requests() {
	}

	public static void askControl(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat askerSeat, String raw, int cardId) {
		// raw: OWNER:zone  e.g. NORTH:field
		if (raw == null || raw.isEmpty()) {
			send(player, origin, askerSeat, table, "");
			return;
		}
		int colon = raw.indexOf(':');
		if (colon <= 0 || colon + 1 >= raw.length()) {
			send(player, origin, askerSeat, table, "");
			return;
		}
		TableSeat owner = seatOf(raw.substring(0, colon));
		TableZone zone = TableZone.fromWire(raw.substring(colon + 1));
		if (owner == null || owner == askerSeat || table.get(owner).isEmpty()) {
			send(player, origin, askerSeat, table, "That seat is empty.");
			return;
		}
		if (zone == null || zone == TableZone.HAND) {
			send(player, origin, askerSeat, table, "");
			return;
		}
		if (table.zones(owner).indexOfId(zone, cardId) < 0) {
			send(player, origin, askerSeat, table, "");
			return;
		}
		String key = origin.asLong() + "|" + owner.name() + "|" + zone.wire() + "|" + cardId;
		// One pending ask per player per owner. A new request replaces the old card instead of stacking packets.
		String ownerPrefix = origin.asLong() + "|" + owner.name() + "|";
		CONTROL_ASKS.entrySet().removeIf(entry ->
			entry.getKey().startsWith(ownerPrefix)
				&& player.getUUID().equals(entry.getValue().requester())
		);
		CONTROL_ASKS.put(key, new SearchAsk(level.dimension(), player.getUUID(), askerSeat.name()));
		for (var entry : WATCHING.entrySet()) {
			Watching w = entry.getValue();
			if (!origin.equals(w.origin()) || !owner.name().equals(w.seat())) {
				continue;
			}
			ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(entry.getKey());
			if (ownerPlayer != null) {
				send(ownerPlayer, origin, owner, table, pretty(askerSeat) + " wants to take a card (" + zone.wire() + ").");
			}
		}
		send(player, origin, askerSeat, table, "Control requested.");
		refreshSitters(level, origin, table);
	}

	public static void allowControl(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat ownerSeat, String ignored) {
		String prefix = origin.asLong() + "|" + ownerSeat.name() + "|";
		String foundKey = null;
		SearchAsk ask = null;
		for (var entry : CONTROL_ASKS.entrySet()) {
			if (entry.getKey().startsWith(prefix)) {
				foundKey = entry.getKey();
				ask = entry.getValue();
				break;
			}
		}
		if (foundKey == null || ask == null) {
			send(player, origin, ownerSeat, table, "No control request.");
			return;
		}
		CONTROL_ASKS.remove(foundKey);
		String[] parts = foundKey.split(java.util.regex.Pattern.quote("|"));
		if (parts.length < 4) {
			send(player, origin, ownerSeat, table, "");
			return;
		}
		TableZone zone = TableZone.fromWire(parts[2]);
		int cardId;
		try {
			cardId = Integer.parseInt(parts[3]);
		} catch (NumberFormatException e) {
			send(player, origin, ownerSeat, table, "");
			return;
		}
		if (zone == null || zone == TableZone.HAND) {
			send(player, origin, ownerSeat, table, "");
			return;
		}
		ServerPlayer searcher = level.getServer().getPlayerList().getPlayer(ask.requester());
		TableSeat askerSeat = seatOf(ask.askerSeat());
		if (searcher == null || askerSeat == null) {
			send(player, origin, ownerSeat, table, "They left.");
			refreshSitters(level, origin, table);
			return;
		}
		SeatZones ownerZones = table.zones(ownerSeat);
		int index = ownerZones.indexOfId(zone, cardId);
		TableCard card = index < 0 ? null : ownerZones.remove(zone, index);
		if (card == null) {
			send(player, origin, ownerSeat, table, "That card is gone.");
			refreshSitters(level, origin, table);
			return;
		}
		TableCard moved = card.faceUp();
		if (moved.ownerSeat() == null || moved.ownerSeat().isEmpty()) {
			moved = moved.withOwner(ownerSeat.name());
		}
		if (!table.zones(askerSeat).addBattlefield(moved)) {
			ownerZones.insert(zone, index, card);
			send(player, origin, ownerSeat, table, "Their battlefield is full.");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.5f, 1.1f);
		send(player, origin, ownerSeat, table, "Control given.");
		send(searcher, origin, askerSeat, table, "You took control of a permanent.");
		refreshSitters(level, origin, table);
	}

	public static void denyControl(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat ownerSeat) {
		String prefix = origin.asLong() + "|" + ownerSeat.name() + "|";
		String foundKey = null;
		SearchAsk ask = null;
		for (var entry : CONTROL_ASKS.entrySet()) {
			if (entry.getKey().startsWith(prefix)) {
				foundKey = entry.getKey();
				ask = entry.getValue();
				break;
			}
		}
		if (foundKey == null) {
			send(player, origin, ownerSeat, table, "");
			return;
		}
		CONTROL_ASKS.remove(foundKey);
		if (ask != null) {
			ServerPlayer searcher = level.getServer().getPlayerList().getPlayer(ask.requester());
			TableSeat askerSeat = seatOf(ask.askerSeat());
			if (searcher != null && askerSeat != null) {
				send(searcher, origin, askerSeat, table, "Control denied.");
			}
		}
		send(player, origin, ownerSeat, table, "Control denied.");
		refreshSitters(level, origin, table);
	}

	public static void askSearch(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat askerSeat, String targetName) {
		TableSeat target = seatOf(targetName);
		BlockState state = level.getBlockState(origin);
		if (target == null || target == askerSeat
			|| !(state.getBlock() instanceof TableBlock)
			|| !TableSeat.fits(state.getValue(TableBlock.SIZE), target)
			|| table.get(target).isEmpty()) {
			send(player, origin, askerSeat, table, "That seat has no library.");
			return;
		}
		String key = origin.asLong() + "|" + target.name();
		SEARCH_ASKS.put(key, new SearchAsk(level.dimension(), player.getUUID(), askerSeat.name()));
		// Tell the owner if they are watching that seat.
		for (var entry : WATCHING.entrySet()) {
			Watching w = entry.getValue();
			if (!origin.equals(w.origin()) || !target.name().equals(w.seat())) {
				continue;
			}
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(entry.getKey());
			if (owner != null) {
				send(owner, origin, target, table, pretty(askerSeat) + " wants to search your library.");
			}
		}
		send(player, origin, askerSeat, table, "Search requested.");
		refreshSitters(level, origin, table);
	}

	public static void allowSearch(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat ownerSeat, String askerSeatName) {
		String key = origin.asLong() + "|" + ownerSeat.name();
		SearchAsk ask = SEARCH_ASKS.get(key);
		if (ask == null) {
			send(player, origin, ownerSeat, table, "No search request.");
			return;
		}
		if (askerSeatName != null && !askerSeatName.isEmpty() && !askerSeatName.equals(ask.askerSeat())) {
			send(player, origin, ownerSeat, table, "No search request from that seat.");
			return;
		}
		ServerPlayer searcher = level.getServer().getPlayerList().getPlayer(ask.requester());
		SEARCH_ASKS.remove(key);
		if (searcher == null) {
			send(player, origin, ownerSeat, table, "They left.");
			refreshSitters(level, origin, table);
			return;
		}
		// Searcher looks at the owner's library.
		startLook(searcher, level, origin, table, ownerSeat, "search", 200);
		send(player, origin, ownerSeat, table, "Search allowed.");
		refreshSitters(level, origin, table);
	}

	public static void denySearch(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat ownerSeat) {
		String key = origin.asLong() + "|" + ownerSeat.name();
		SearchAsk ask = SEARCH_ASKS.remove(key);
		if (ask == null) {
			send(player, origin, ownerSeat, table, "");
			return;
		}
		ServerPlayer searcher = level.getServer().getPlayerList().getPlayer(ask.requester());
		if (searcher != null) {
			TableSeat askerSeat = seatOf(ask.askerSeat());
			if (askerSeat != null) {
				send(searcher, origin, askerSeat, table, "Search denied.");
			}
		}
		send(player, origin, ownerSeat, table, "Search denied.");
		refreshSitters(level, origin, table);
	}

	/** ac short form: NORTH:f -> NORTH:field, g=grave, e=exile. */
	public static String expandControl(String shortForm) {
		if (shortForm == null || shortForm.isEmpty()) {
			return "";
		}
		int colon = shortForm.lastIndexOf(':');
		if (colon <= 0 || colon + 1 >= shortForm.length()) {
			return shortForm;
		}
		String seat = shortForm.substring(0, colon);
		String z = shortForm.substring(colon + 1);
		String zone = switch (z) {
			case "f" -> "field";
			case "g" -> "grave";
			case "e" -> "exile";
			default -> z;
		};
		return seat + ":" + zone;
	}

	public static void removeAsks(Predicate<SearchAsk> doomed, Set<TableRef> dirty) {
		removeAsks(SEARCH_ASKS, doomed, dirty);
		removeAsks(CONTROL_ASKS, doomed, dirty);
	}

	public static void removeAsks(Map<String, SearchAsk> asks, Predicate<SearchAsk> doomed, Set<TableRef> dirty) {
		asks.entrySet().removeIf(entry -> {
			if (!doomed.test(entry.getValue())) {
				return false;
			}
			BlockPos origin = originOf(entry.getKey());
			if (origin != null) {
				dirty.add(new TableRef(entry.getValue().dimension(), origin));
			}
			return true;
		});
	}

	/** Drops requests aimed at seats of this table that no longer hold a box. @return true when any were dropped */
	public static boolean pruneAsks(BlockPos origin, TableBlockEntity table) {
		String prefix = origin.asLong() + "|";
		boolean a = SEARCH_ASKS.keySet().removeIf(key -> key.startsWith(prefix) && seatEmpty(table, key));
		boolean b = CONTROL_ASKS.keySet().removeIf(key -> key.startsWith(prefix) && seatEmpty(table, key));
		return a || b;
	}

	/** Ask keys look like origin|SEAT or origin|SEAT|zone|index. */
	public static boolean seatEmpty(TableBlockEntity table, String key) {
		if (table == null) {
			return true;
		}
		String[] parts = key.split(java.util.regex.Pattern.quote("|"), 3);
		TableSeat seat = parts.length < 2 ? null : seatOf(parts[1]);
		return seat == null || table.get(seat).isEmpty();
	}

	public static BlockPos originOf(String key) {
		int bar = key.indexOf('|');
		if (bar <= 0) {
			return null;
		}
		try {
			return BlockPos.of(Long.parseLong(key.substring(0, bar)));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
