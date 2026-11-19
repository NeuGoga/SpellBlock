package com.neugoga.spellblock.network.table;

import com.neugoga.spellblock.network.BinderServer;
import com.neugoga.spellblock.network.ScryFinishC2SPayload;
import com.neugoga.spellblock.network.ScrySyncS2CPayload;
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
import com.neugoga.spellblock.network.table.TableState.ScrySession;
import com.neugoga.spellblock.network.table.TableState.TableRef;
import com.neugoga.spellblock.network.table.TableState.Watching;
import static com.neugoga.spellblock.network.table.TableState.REACH;
import static com.neugoga.spellblock.network.table.TableState.SCRY_SESSIONS;
import static com.neugoga.spellblock.network.table.TableState.WATCHING;
import static com.neugoga.spellblock.network.table.TableSync.refreshAll;
import static com.neugoga.spellblock.network.table.TableSync.refreshSitters;
import static com.neugoga.spellblock.network.table.TableSync.send;
import static com.neugoga.spellblock.network.table.TableText.seatOf;

/**
 * Scry, surveil, and search. The cards are held off the library while the player looks, so every way a look can end comes through here.
 */
public final class Looks {
	private Looks() {
	}

	/**
	 * Gives a player's unfinished scry, surveil, or search cards back to the top of that library.
	 * Those cards are held off the library while the player looks, so every way a look can end
	 * (closing, leaving, dying, walking away, a broken table, a server stop) has to come through here.
	 * The table that is refreshed afterwards is added to dirty. Nothing is sent from here.
	 */
	public static void restoreSession(MinecraftServer server, UUID id, Set<TableRef> dirty) {
		ScrySession session = SCRY_SESSIONS.remove(id);
		if (session == null) {
			return;
		}
		TableSeat seat = seatOf(session.seat());
		ServerLevel level = server.getLevel(session.dimension());
		if (seat == null || level == null) {
			SpellBlock.LOGGER.warn("Could not return {} looked-at cards: unknown seat or world.", session.cards().size());
			return;
		}
		// This can load the chunk. That is the only way to put the cards back.
		if (!(level.getBlockEntity(session.origin()) instanceof TableBlockEntity table) || table.get(seat).isEmpty()) {
			SpellBlock.LOGGER.warn("Could not return {} looked-at cards: no deck box at {} {}.", session.cards().size(), session.origin(), seat);
			return;
		}
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		List<TableCard> cards = session.cards();
		int restored = 0;
		for (int i = cards.size() - 1; i >= 0; i--) {
			DeckBoxData next = data.place(cards.get(i).key(), true);
			if (next == null) {
				break;
			}
			data = next;
			restored++;
		}
		if (restored < cards.size()) {
			SpellBlock.LOGGER.warn("The library was full. {} looked-at cards could not be returned.", cards.size() - restored);
		}
		// The searcher saw the whole order, so a search that is cut short still ends in a shuffle.
		if ("search".equals(session.mode())) {
			data = data.shuffled(level.getRandom());
		}
		stack.set(SpellBlockComponents.DECK, data);
		table.sync();
		dirty.add(new TableRef(session.dimension(), session.origin()));
		// Close the client ScryPanel for this player.
		ServerPlayer viewer = server.getPlayerList().getPlayer(id);
		if (viewer != null) {
			ServerPlayNetworking.send(viewer, new ScrySyncS2CPayload(session.origin(), session.seat(), "", List.of()));
		}
	}

	/**
	 * A deck box is about to leave a seat, or the whole table is being taken apart.
	 * Anyone looking at that library gets their cards back first, so they ride along with the box.
	 * Pass a null seat for every seat. This does not refresh screens; the caller changes the table next.
	 */
	public static void restoreLooksAt(ServerLevel level, BlockPos origin, TableSeat seat) {
		if (SCRY_SESSIONS.isEmpty()) {
			return;
		}
		Set<TableRef> ignored = new LinkedHashSet<>();
		for (var entry : List.copyOf(SCRY_SESSIONS.entrySet())) {
			ScrySession session = entry.getValue();
			if (session.dimension().equals(level.dimension())
				&& session.origin().equals(origin)
				&& (seat == null || session.seat().equals(seat.name()))) {
				restoreSession(level.getServer(), entry.getKey(), ignored);
			}
		}
	}

	public static void startLook(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, String mode, int count) {
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
		table.sync();
		List<TableCard> held = new ArrayList<>();
		for (var key : taken.cards()) {
			held.add(table.newCard(key, false));
		}
		SCRY_SESSIONS.put(player.getUUID(), new ScrySession(level.dimension(), origin.immutable(), seat.name(), mode, held));
		OracleIndex index = OracleService.index();
		List<BinderCard> shown = new ArrayList<>(held.size());
		for (TableCard card : held) {
			if (index == null) {
				shown.add(BinderCard.unknown(card.key(), 1));
			} else {
				shown.add(BinderServer.describe(index, card.key(), 1));
			}
		}
		ServerPlayNetworking.send(player, new ScrySyncS2CPayload(origin, seat.name(), mode, shown));
		TableSeat handSeat = seat;
		Watching watching = WATCHING.get(player.getUUID());
		if (watching != null && watching.origin().equals(origin)) {
			TableSeat ws = seatOf(watching.seat());
			if (ws != null) {
				handSeat = ws;
			}
		}
		send(player, origin, handSeat, table, "");
		refreshSitters(level, origin, table);
	}

	public static void finishScry(ServerPlayer player, ScryFinishC2SPayload payload) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		BlockPos pos = payload.pos();
		if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof TableBlock)) {
			return;
		}
		BlockPos origin = pos.offset(-state.getValue(TableBlock.X), 0, -state.getValue(TableBlock.Z));
		if (!(level.getBlockEntity(origin) instanceof TableBlockEntity table)) {
			return;
		}
		TableSeat seat = seatOf(payload.seat());
		if (seat == null || !TableSeat.fits(state.getValue(TableBlock.SIZE), seat)) {
			return;
		}
		ScrySession session = SCRY_SESSIONS.get(player.getUUID());
		if (session == null || !session.origin().equals(origin) || !session.seat().equals(seat.name())) {
			send(player, origin, seat, table, "");
			return;
		}
		if (table.get(seat).isEmpty()) {
			SCRY_SESSIONS.remove(player.getUUID());
			send(player, origin, seat, table, "No deck box on this side.");
			return;
		}
		List<TableCard> held = session.cards();
		int n = held.size();
		boolean[] used = new boolean[n];
		ArrayList<TableCard> tops = new ArrayList<>();
		ArrayList<TableCard> bottoms = new ArrayList<>();
		ArrayList<TableCard> graves = new ArrayList<>();
		if (!collect(payload.tops(), held, used, tops) || !collect(payload.bottoms(), held, used, bottoms)) {
			send(player, origin, seat, table, "");
			return;
		}
		if ("surveil".equals(session.mode())) {
			if (!collect(payload.graves(), held, used, graves)) {
				send(player, origin, seat, table, "");
				return;
			}
		} else if (payload.graves() != null && !payload.graves().isEmpty()) {
			send(player, origin, seat, table, "");
			return;
		}
		for (boolean ok : used) {
			if (!ok) {
				send(player, origin, seat, table, "");
				return;
			}
		}
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		SeatZones zones = table.zones(seat);
		if ("search".equals(session.mode())) {
			// bottoms = take to the searcher's hand (stamped with library owner); tops = rest of library.
			TableSeat searcherSeat = seat;
			Watching watching = WATCHING.get(player.getUUID());
			if (watching != null && watching.origin().equals(origin)) {
				TableSeat ws = seatOf(watching.seat());
				if (ws != null) {
					searcherSeat = ws;
				}
			}
			SeatZones handZones = table.zones(searcherSeat);
			boolean foreign = searcherSeat != seat;
			ArrayList<TableCard> takenCards = new ArrayList<>();
			ArrayList<TableCard> overflow = new ArrayList<>();
			for (TableCard card : bottoms) {
				if (handZones.hand().size() + takenCards.size() >= SeatZones.MAX_HAND) {
					overflow.add(card);
					continue;
				}
				TableCard taken = card.faceUp();
				if (foreign) {
					taken = taken.withOwner(seat.name());
				}
				handZones.addHand(taken);
				takenCards.add(taken);
			}
			for (TableCard card : tops) {
				DeckBoxData next = data.place(card.key(), true);
				if (next == null) {
					break;
				}
				data = next;
			}
			for (TableCard card : overflow) {
				DeckBoxData next = data.place(card.key(), true);
				if (next == null) {
					break;
				}
				data = next;
			}
			data = data.shuffled(level.getRandom());
			stack.set(SpellBlockComponents.DECK, data);
			SCRY_SESSIONS.remove(player.getUUID());
			table.sync();
			level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.55f, 1.0f);
			String note;
			if (takenCards.isEmpty()) {
				note = "Search done.";
			} else if (overflow.isEmpty()) {
				note = "Took " + takenCards.size() + " from search.";
			} else {
				note = "Took " + takenCards.size() + " from search. Hand full; " + overflow.size() + " returned.";
			}
			send(player, origin, searcherSeat, table, note);
			refreshSitters(level, origin, table);
			return;
		}
		// Bottoms first so tops land on the new top of the library.
		for (TableCard card : bottoms) {
			DeckBoxData next = data.place(card.key(), false);
			if (next == null) {
				send(player, origin, seat, table, "The library is full.");
				return;
			}
			data = next;
		}
		for (int i = tops.size() - 1; i >= 0; i--) {
			DeckBoxData next = data.place(tops.get(i).key(), true);
			if (next == null) {
				send(player, origin, seat, table, "The library is full.");
				return;
			}
			data = next;
		}
		for (TableCard card : graves) {
			zones.addGraveyard(card);
		}
		stack.set(SpellBlockComponents.DECK, data);
		SCRY_SESSIONS.remove(player.getUUID());
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.55f, 1.0f);
		String note = "surveil".equals(session.mode()) ? "Surveil done." : "Scry done.";
		send(player, origin, seat, table, note);
		refreshSitters(level, origin, table);
	}

	public static boolean collect(List<Integer> indexes, List<TableCard> held, boolean[] used, List<TableCard> into) {
		if (indexes == null) {
			return true;
		}
		for (int index : indexes) {
			if (index < 0 || index >= held.size() || used[index]) {
				return false;
			}
			used[index] = true;
			into.add(held.get(index));
		}
		return true;
	}
}
