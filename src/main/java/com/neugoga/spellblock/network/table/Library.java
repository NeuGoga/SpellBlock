package com.neugoga.spellblock.network.table;

import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.network.table.TableState.TableRef;
import com.neugoga.spellblock.table.SeatZones;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableCard;
import com.neugoga.spellblock.table.TableSeat;
import com.neugoga.spellblock.table.TableZone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.neugoga.spellblock.network.table.Looks.restoreSession;
import static com.neugoga.spellblock.network.table.TableState.SCRY_SESSIONS;
import static com.neugoga.spellblock.network.table.TableSync.refreshAll;
import static com.neugoga.spellblock.network.table.TableSync.send;

/**
 * Actions on a seat's library: draw, shuffle, mill, and putting a card on top or bottom.
 */
public final class Library {
	private Library() {
	}

	public static void shuffle(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat) {
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		stack.set(SpellBlockComponents.DECK, data.shuffled(level.getRandom()));
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.7f, 0.7f);
		send(player, origin, seat, table, "Library shuffled.");
	}

	public static void draw(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat) {
		if (SCRY_SESSIONS.containsKey(player.getUUID())) {
			Set<TableRef> stale = new LinkedHashSet<>();
			restoreSession(level.getServer(), player.getUUID(), stale);
			refreshAll(level.getServer(), stale);
		}
		SeatZones zones = table.zones(seat);
		if (zones.hand().size() >= SeatZones.MAX_HAND) {
			send(player, origin, seat, table, "Your hand is full.");
			return;
		}
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		DeckBoxData.Draw drawn = data.drawTop();
		if (drawn == null) {
			send(player, origin, seat, table, "The library is empty.");
			return;
		}
		stack.set(SpellBlockComponents.DECK, drawn.data());
		zones.addHand(table.newCard(drawn.card(), false));
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.6f, 1.1f);
		send(player, origin, seat, table, "");
	}

	public static void placeLibrary(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, String zoneName, int cardId, boolean onTop) {
		TableZone zone = TableZone.fromWire(zoneName);
		if (zone == null) {
			send(player, origin, seat, table, "");
			return;
		}
		SeatZones zones = table.zones(seat);
		int index = zones.indexOfId(zone, cardId);
		TableCard card = index < 0 ? null : zones.remove(zone, index);
		if (card == null) {
			send(player, origin, seat, table, "");
			return;
		}
		if (card.token() || card.key().oracleId().isEmpty()) {
			table.sync();
			send(player, origin, seat, table, "");
			return;
		}
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		DeckBoxData placed = data.place(card.key(), onTop);
		if (placed == null) {
			zones.insert(zone, index, card);
			send(player, origin, seat, table, "The library is full.");
			return;
		}
		stack.set(SpellBlockComponents.DECK, placed);
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.55f, onTop ? 1.15f : 0.85f);
		send(player, origin, seat, table, onTop ? "Put on top of the library." : "Put on the bottom of the library.");
	}

	public static void mill(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int count) {
		if (count < 1) {
			send(player, origin, seat, table, "");
			return;
		}
		if (count > 200) {
			count = 200;
		}
		// A second look must not overwrite the first one. That would lose the first one's cards.
		if (SCRY_SESSIONS.containsKey(player.getUUID())) {
			Set<TableRef> stale = new LinkedHashSet<>();
			restoreSession(level.getServer(), player.getUUID(), stale);
			refreshAll(level.getServer(), stale);
		}
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		DeckBoxData.TakeN taken = data.takeTopN(count);
		if (taken.cards().isEmpty()) {
			send(player, origin, seat, table, "The library is empty.");
			return;
		}
		stack.set(SpellBlockComponents.DECK, taken.data());
		SeatZones zones = table.zones(seat);
		for (var key : taken.cards()) {
			zones.addGraveyard(table.newCard(key, false));
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.55f, 0.8f);
		send(player, origin, seat, table, "Milled " + taken.cards().size() + ".");
	}

	public static void drawMany(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int count) {
		if (count < 1) {
			send(player, origin, seat, table, "");
			return;
		}
		if (count > 200) {
			count = 200;
		}
		if (SCRY_SESSIONS.containsKey(player.getUUID())) {
			Set<TableRef> stale = new LinkedHashSet<>();
			restoreSession(level.getServer(), player.getUUID(), stale);
			refreshAll(level.getServer(), stale);
		}
		SeatZones zones = table.zones(seat);
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		int drawn = 0;
		for (int i = 0; i < count; i++) {
			if (zones.hand().size() >= SeatZones.MAX_HAND) {
				break;
			}
			DeckBoxData.Draw next = data.drawTop();
			if (next == null) {
				break;
			}
			data = next.data();
			zones.addHand(table.newCard(next.card(), false));
			drawn++;
		}
		if (drawn == 0) {
			send(player, origin, seat, table, zones.hand().size() >= SeatZones.MAX_HAND ? "Your hand is full." : "The library is empty.");
			return;
		}
		stack.set(SpellBlockComponents.DECK, data);
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.6f, 1.05f);
		send(player, origin, seat, table, drawn == 1 ? "Drew 1." : "Drew " + drawn + ".");
	}

	/** Put a card Nth from the top of the library (1 = top, 2 = second, …). */
	public static void placeNth(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, String zoneName, int cardId, int fromTop) {
		TableZone zone = TableZone.fromWire(zoneName);
		if (zone == null) {
			send(player, origin, seat, table, "");
			return;
		}
		SeatZones zones = table.zones(seat);
		int index = zones.indexOfId(zone, cardId);
		TableCard card = index < 0 ? null : zones.remove(zone, index);
		if (card == null) {
			send(player, origin, seat, table, "");
			return;
		}
		if (card.token() || card.key().oracleId().isEmpty()) {
			// Tokens are not real library cards.
			table.sync();
			send(player, origin, seat, table, "");
			return;
		}
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		int n = Math.max(1, Math.min(fromTop, 40));
		DeckBoxData placed = data.placeAt(card.key(), n);
		if (placed == null) {
			zones.insert(zone, index, card);
			send(player, origin, seat, table, "The library is full.");
			return;
		}
		stack.set(SpellBlockComponents.DECK, placed);
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.55f, 1.05f);
		String ord = n == 1 ? "top" : (n + (n == 2 ? "nd" : n == 3 ? "rd" : "th") + " from top");
		send(player, origin, seat, table, "Put " + ord + " of the library.");
	}
}
