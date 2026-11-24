package com.neugoga.spellblock.network.table;

import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.DeckBoxData;
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

import java.util.List;
import java.util.Locale;

import static com.neugoga.spellblock.network.table.TableSync.send;
import static com.neugoga.spellblock.network.table.TableText.seatOf;

/**
 * Single-card actions at a seat: move between zones, tap, turn face down or up, flip, play face down.
 */
public final class CardMoves {
	private CardMoves() {
	}

	public static void playDown(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int index) {
		SeatZones zones = table.zones(seat);
		TableCard card = zones.removeHand(index);
		if (card == null) {
			send(player, origin, seat, table, "");
			return;
		}
		clearRevealIfCard(zones, card.id());
		if (!zones.playDown(card)) {
			zones.insertHand(index, card);
			send(player, origin, seat, table, "The battlefield is full.");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.CHISELED_BOOKSHELF_INSERT, SoundSource.BLOCKS, 0.5f, 0.9f);
		send(player, origin, seat, table, "");
	}

	public static void turn(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int index, boolean down) {
		if (!table.zones(seat).setFaceDown(index, down)) {
			send(player, origin, seat, table, "");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.45f, down ? 0.8f : 1.2f);
		send(player, origin, seat, table, "");
	}

	public static void flip(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int index) {
		if (!table.zones(seat).toggleFlip(index)) {
			send(player, origin, seat, table, "");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.45f, 1.0f);
		send(player, origin, seat, table, "");
	}

	public static void relocate(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, TableZone from, TableZone to, int index) {
		SeatZones zones = table.zones(seat);
		TableCard card = zones.remove(from, index);
		if (card == null) {
			send(player, origin, seat, table, "");
			return;
		}
		if (from == TableZone.HAND) {
			clearRevealIfCard(zones, card.id());
		}
		// Tokens exist only on the battlefield. Leaving the field destroys them.
		if (card.token() && from == TableZone.FIELD && to != TableZone.FIELD) {
			table.sync();
			level.playSound(null, origin, SoundEvents.WOOL_HIT, SoundSource.PLAYERS, 0.35f, 1.1f);
			send(player, origin, seat, table, "");
			return;
		}
		if (card.token() && to == TableZone.FIELD && from != TableZone.FIELD) {
			// Should not happen; refuse.
			zones.insert(from, index, card);
			send(player, origin, seat, table, "");
			return;
		}
		// Only deck-declared commanders may enter the command zone.
		if (to == TableZone.COMMAND && !isDeclaredCommander(table, seat, card)) {
			zones.insert(from, index, card);
			send(player, origin, seat, table, "");
			return;
		}
		// Counters are battlefield state. They do not follow a card to hand/grave/exile/library.
		// Command-zone markers are tax and stay when a commander returns there.
		if (from == TableZone.FIELD && to != TableZone.FIELD && to != TableZone.COMMAND) {
			card = card.clearCounters();
		}
		// Borrowed cards (from a search or similar) go to the owner's grave/exile, not the controller's.
		SeatZones destZones = zones;
		if ((to == TableZone.GRAVE || to == TableZone.EXILE) && card.ownerSeat() != null && !card.ownerSeat().isEmpty()) {
			TableSeat owner = seatOf(card.ownerSeat());
			if (owner != null && owner != seat && !table.get(owner).isEmpty()) {
				destZones = table.zones(owner);
			}
		}
		if (!destZones.add(to, card)) {
			zones.insert(from, index, card);
			send(player, origin, seat, table, to.fullMessage());
			return;
		}
		table.sync();
		float pitch = switch (to) {
			case GRAVE -> 0.8f;
			case EXILE -> 1.0f;
			case HAND -> 1.3f;
			case FIELD -> 1.2f;
			case COMMAND -> 1.05f;
		};
		level.playSound(null, origin, SoundEvents.CHISELED_BOOKSHELF_INSERT, SoundSource.BLOCKS, 0.5f, pitch);
		send(player, origin, seat, table, "");
	}

	public static void tap(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int index) {
		if (!table.zones(seat).toggleTap(index)) {
			send(player, origin, seat, table, "");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, 1.6f);
		send(player, origin, seat, table, "");
	}

	public static void putBack(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, TableZone zone, int index) {
		SeatZones zones = table.zones(seat);
		TableCard card = zones.remove(zone, index);
		if (card == null) {
			send(player, origin, seat, table, "");
			return;
		}
		if (zone == TableZone.HAND) {
			clearRevealIfCard(zones, card.id());
		}
		if (card.token()) {
			table.sync();
			level.playSound(null, origin, SoundEvents.WOOL_HIT, SoundSource.PLAYERS, 0.35f, 1.1f);
			send(player, origin, seat, table, "");
			return;
		}
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		stack.set(SpellBlockComponents.DECK, data.add(DeckBoxData.Zone.MAIN, card.key()));
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.55f, 0.9f);
		send(player, origin, seat, table, "");
	}

	public static void tokenCopy(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int index) {
		SeatZones zones = table.zones(seat);
		if (index < 0 || index >= zones.battlefield().size()) {
			return;
		}
		TableCard source = zones.battlefield().get(index);
		TableCard copy = source.asTokenCopy().withId(table.allocateCardId()).withOwner("");
		if (!zones.addBattlefield(copy)) {
			send(player, origin, seat, table, "");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.35f, 1.4f);
		send(player, origin, seat, table, "");
	}

	public static void adjustCounters(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int index, String kind, int amount) {
		if (amount == 0) {
			amount = 1;
		}
		SeatZones zones = table.zones(seat);
		if (index < 0 || index >= zones.battlefield().size()) {
			return;
		}
		TableCard card = zones.battlefield().get(index);
		TableCard next = switch (kind) {
			case "p" -> card.addPower(amount);
			case "t" -> card.addToughness(amount);
			case "b" -> card.addBoth(amount);
			case "l" -> card.addLoyalty(amount);
			case "m" -> card.addMarkers(amount);
			default -> card;
		};
		if (next == card) {
			return;
		}
		zones.replaceBattlefield(index, next);
		table.sync();
		send(player, origin, seat, table, "");
	}

	public static void spawnToken(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, String spec) {
		if (spec == null || spec.isEmpty()) {
			return;
		}
		SeatZones zones = table.zones(seat);
		TableCard token;
				if (spec.startsWith("pid:")) {
			String rest = spec.substring(4);
			String printing = rest;
			String name = "Token";
			int bar = rest.indexOf('|');
			if (bar >= 0) {
				printing = rest.substring(0, bar);
				name = rest.substring(bar + 1);
				if (name.length() > 40) {
					name = name.substring(0, 40);
				}
			}
			token = new TableCard("", printing, "NONFOIL", false, false, false, "", 0, true, name, 0, 0, 0, 0)
				.withId(table.allocateCardId());
		} else if (spec.startsWith("oid:")) {
			String oracleId = spec.substring(4);
			OracleCard src = OracleService.index() == null ? null : OracleService.index().findToken(oracleId);
			if (src == null) {
				send(player, origin, seat, table, "Unknown token.");
				return;
			}
			token = new TableCard(
				src.oracleId(),
				src.printingId(),
				"NONFOIL",
				false,
				false,
				false,
				"",
				0,
				true,
				src.name(),
				0,
				0,
				0,
				0
			).withId(table.allocateCardId());
		} else {
			token = switch (spec) {
				case "1/1" -> TableCard.pureToken("1/1", 1, 1);
				case "2/2" -> TableCard.pureToken("2/2", 2, 2);
				case "3/3" -> TableCard.pureToken("3/3", 3, 3);
				case "0/0" -> TableCard.pureToken("0/0", 0, 0);
				case "5/5" -> TableCard.pureToken("5/5", 5, 5);
				default -> TableCard.pureToken(spec.length() > 16 ? spec.substring(0, 16) : spec, 0, 0);
			};
			token = token.withId(table.allocateCardId());
		}
		if (!zones.addBattlefield(token)) {
			send(player, origin, seat, table, "");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.35f, 1.2f);
		send(player, origin, seat, table, "");
	}

	public static void clearAllCounters(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, int index) {
		SeatZones zones = table.zones(seat);
		if (index < 0 || index >= zones.battlefield().size()) {
			return;
		}
		TableCard card = zones.battlefield().get(index);
		if (!card.hasCounters() && card.loyalty() == 0) {
			return;
		}
		zones.replaceBattlefield(index, card.clearCounters());
		table.sync();
		send(player, origin, seat, table, "");
	}

	public static void untapAll(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, String filter) {
		SeatZones zones = table.zones(seat);
		OracleIndex index = OracleService.index();
		boolean changed = false;
		List<TableCard> field = zones.battlefield();
		for (int i = 0; i < field.size(); i++) {
			TableCard card = field.get(i);
			if (!card.tapped()) {
				continue;
			}
			if (!matchesUntapFilter(card, filter, index)) {
				continue;
			}
			zones.replaceBattlefield(i, card.withTapped(false));
			changed = true;
		}
		if (!changed) {
			send(player, origin, seat, table, "");
			return;
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.35f, 1.2f);
		send(player, origin, seat, table, "");
	}

	private static boolean matchesUntapFilter(TableCard card, String filter, OracleIndex index) {
		if (filter == null || filter.isEmpty() || "all".equals(filter)) {
			return true;
		}
		String type = "";
		if (index != null && !card.oracleId().isEmpty()) {
			OracleCard oracle = index.findByOracleId(card.oracleId());
			if (oracle != null && oracle.typeLine() != null) {
				type = oracle.typeLine().toLowerCase(Locale.ROOT);
			}
		}
		if (type.isEmpty() && card.label() != null) {
			type = card.label().toLowerCase(Locale.ROOT);
		}
		return switch (filter) {
			case "land" -> type.contains("land");
			case "creature" -> type.contains("creature") || card.token();
			default -> true;
		};
	}

	private static void clearRevealIfCard(SeatZones zones, int cardId) {
		if (cardId <= 0 || zones == null) {
			return;
		}
		String reveal = zones.reveal();
		if (reveal == null || reveal.isEmpty()) {
			return;
		}
		int hash = reveal.indexOf('#');
		if (hash < 0) {
			return;
		}
		try {
			if (Integer.parseInt(reveal.substring(hash + 1)) == cardId) {
				zones.setReveal("");
			}
		} catch (NumberFormatException ignored) {
		}
	}
	/** Matches the card's oracle id against the commanders list on this seat's deck box. */
	private static boolean isDeclaredCommander(TableBlockEntity table, TableSeat seat, TableCard card) {
		if (table == null || seat == null || card == null || card.token() || card.oracleId().isEmpty()) {
			return false;
		}
		ItemStack box = table.get(seat);
		if (box == null || box.isEmpty()) {
			return false;
		}
		DeckBoxData data = box.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		String oracle = card.oracleId();
		for (var entry : data.commanders()) {
			if (oracle.equals(entry.key().oracleId())) {
				return true;
			}
		}
		return false;
	}

}
