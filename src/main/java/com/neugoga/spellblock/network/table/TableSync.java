package com.neugoga.spellblock.network.table;

import com.neugoga.spellblock.network.BinderServer;
import com.neugoga.spellblock.network.HandRevealS2CPayload;
import com.neugoga.spellblock.network.HandSyncS2CPayload;
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
import com.neugoga.spellblock.network.table.TableState.SearchAsk;
import com.neugoga.spellblock.network.table.TableState.TableRef;
import com.neugoga.spellblock.network.table.TableState.Watching;
import static com.neugoga.spellblock.network.table.TableState.CONTROL_ASKS;
import static com.neugoga.spellblock.network.table.TableState.PEEKS;
import static com.neugoga.spellblock.network.table.TableState.REVEAL_REACH;
import static com.neugoga.spellblock.network.table.TableState.SCRY_SESSIONS;
import static com.neugoga.spellblock.network.table.TableState.SEARCH_ASKS;
import static com.neugoga.spellblock.network.table.TableState.WATCHING;
import static com.neugoga.spellblock.network.table.TableText.prettySeatName;
import static com.neugoga.spellblock.network.table.TableText.seatOf;

/**
 * Sends table state to the players who have a table open: their hand, who else is sitting, and revealed hands.
 */
public final class TableSync {
	private TableSync() {
	}

	public static void send(ServerPlayer player, BlockPos origin, TableSeat seat, TableBlockEntity table, String status) {
		send(player, origin, seat, table, status, null, null);
	}

	public static void send(
		ServerPlayer player,
		BlockPos origin,
		TableSeat seat,
		TableBlockEntity table,
		String status,
		String sitters,
		String lookers
	) {
		ItemStack stack = table.get(seat);
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		SeatZones zones = table.zones(seat);
		OracleIndex index = OracleService.index();
		List<BinderCard> hand = new ArrayList<>(zones.hand().size());
		List<Integer> handIds = new ArrayList<>(zones.hand().size());
		for (TableCard card : zones.hand()) {
			handIds.add(card.id());
			if (index == null) {
				hand.add(BinderCard.unknown(card.key(), 1));
			} else {
				hand.add(BinderServer.describe(index, card.key(), 1));
			}
		}
		// Pending asks always win over transient status toasts so Allow/Deny stay visible.
		String outbound = "";
		SearchAsk pending = SEARCH_ASKS.get(origin.asLong() + "|" + seat.name());
		if (pending != null) {
			outbound = prettySeatName(pending.askerSeat()) + " wants to search your library.";
		} else {
			String cprefix = origin.asLong() + "|" + seat.name() + "|";
			for (var entry : CONTROL_ASKS.entrySet()) {
				if (entry.getKey().startsWith(cprefix)) {
					outbound = prettySeatName(entry.getValue().askerSeat()) + " wants to take a card.";
					break;
				}
			}
		}
		if (outbound.isEmpty() && status != null) {
			outbound = status;
		}
		ServerLevel level = levelOf(player);
		ServerPlayNetworking.send(player, new HandSyncS2CPayload(
			origin,
			seat.name(),
			data.librarySize(),
			zones.battlefield().size(),
			zones.graveyard().size(),
			hand,
			handIds,
			outbound,
			sitters != null ? sitters : sittersAt(level, origin),
			lookers != null ? lookers : lookersAt(origin)
		));
	}

	public static void refreshReveals(ServerLevel level, BlockPos origin, TableBlockEntity table) {
		for (TableSeat seat : TableSeat.values()) {
			pushReveal(level, origin, table, seat);
		}
	}

	public static void pushReveal(ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat) {
		String raw = table == null ? "" : table.zones(seat).reveal();
		String audience = raw;
		int cardId = -1;
		int hash = raw == null ? -1 : raw.indexOf('#');
		if (hash >= 0) {
			audience = raw.substring(0, hash);
			try {
				cardId = Integer.parseInt(raw.substring(hash + 1));
			} catch (NumberFormatException e) {
				cardId = -1;
			}
		}
		List<BinderCard> cards = List.of();
		if (!audience.isEmpty() && table != null) {
			cards = cardId >= 0 ? describeOne(table.zones(seat), cardId) : describe(table.zones(seat));
		}
		double max = REVEAL_REACH * REVEAL_REACH;
		for (ServerPlayer viewer : level.players()) {
			if (viewer.distanceToSqr(Vec3.atCenterOf(origin)) > max) {
				continue;
			}
			Watching watching = WATCHING.get(viewer.getUUID());
			if (watching == null || !origin.equals(watching.origin())) {
				continue;
			}
			if (seat.name().equals(watching.seat())) {
				continue;
			}
			boolean show = !audience.isEmpty() && ("ALL".equals(audience) || audience.equals(watching.seat()));
			ServerPlayNetworking.send(viewer, new HandRevealS2CPayload(
				origin,
				seat.name(),
				show ? audience : "",
				show ? cards : List.of()
			));
		}
	}

	public static List<BinderCard> describeOne(SeatZones zones, int cardId) {
		int index = zones.indexOfId(TableZone.HAND, cardId);
		if (index < 0) {
			return List.of();
		}
		TableCard card = zones.hand().get(index);
		OracleIndex oracle = OracleService.index();
		if (oracle == null) {
			return List.of(BinderCard.unknown(card.key(), 1));
		}
		return List.of(BinderServer.describe(oracle, card.key(), 1));
	}

	public static List<BinderCard> describe(SeatZones zones) {
		OracleIndex index = OracleService.index();
		List<BinderCard> cards = new ArrayList<>(zones.hand().size());
		for (TableCard card : zones.hand()) {
			if (index == null) {
				cards.add(BinderCard.unknown(card.key(), 1));
			} else {
				cards.add(BinderServer.describe(index, card.key(), 1));
			}
		}
		return cards;
	}

	/** Who currently has this table open, as seat=name pairs for the tabs. */
	public static String sittersAt(ServerLevel level, BlockPos origin) {
		if (level == null || origin == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (var entry : WATCHING.entrySet()) {
			Watching watching = entry.getValue();
			if (!origin.equals(watching.origin())) {
				continue;
			}
			ServerPlayer other = level.getServer().getPlayerList().getPlayer(entry.getKey());
			if (other == null) {
				continue;
			}
			String name = other.getGameProfile().getName();
			if (name == null || name.isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				out.append(';');
			}
			out.append(watching.seat()).append('=').append(name);
		}
		return out.toString();
	}

	/** Seats currently scrying or surveilling at this table. */
	public static String lookersAt(BlockPos origin) {
		StringBuilder out = new StringBuilder();
		for (ScrySession session : SCRY_SESSIONS.values()) {
			if (session == null || session.origin() == null || !session.origin().equals(origin)) {
				continue;
			}
			if (out.length() > 0) {
				out.append(';');
			}
			out.append(session.seat());
		}
		// Face-down peeks: SEAT@zone:index
		for (var entry : PEEKS.entrySet()) {
			Watching watching = WATCHING.get(entry.getKey());
			if (watching == null || watching.origin() == null || !watching.origin().equals(origin)) {
				continue;
			}
			if (out.length() > 0) {
				out.append(';');
			}
			out.append(watching.seat()).append('@').append(entry.getValue());
		}
		// Pending take-control: CTRL:ownerSeat:zone:index:askerSeat
		String prefix = origin.asLong() + "|";
		for (var entry : CONTROL_ASKS.entrySet()) {
			if (!entry.getKey().startsWith(prefix)) {
				continue;
			}
			String[] parts = entry.getKey().split(java.util.regex.Pattern.quote("|"), 4);
			if (parts.length < 4) {
				continue;
			}
			if (out.length() > 0) {
				out.append(';');
			}
			out.append("CTRL:").append(parts[1]).append(':').append(parts[2])
				.append(':').append(parts[3]).append(':').append(entry.getValue().askerSeat());
		}
		return out.toString();
	}

	/** Refreshes each table once, for everyone who has it open. Skips tables that are not loaded. */
	public static void refreshAll(MinecraftServer server, Set<TableRef> dirty) {
		for (TableRef ref : dirty) {
			ServerLevel level = server.getLevel(ref.dimension());
			if (level == null || !level.isLoaded(ref.origin())) {
				continue;
			}
			if (level.getBlockEntity(ref.origin()) instanceof TableBlockEntity table) {
				refreshSitters(level, ref.origin(), table);
			}
		}
	}

	/** Push a fresh hand packet so every open seat sees updated player names. Reveals go out once, not once per seat. */
	public static void refreshSitters(ServerLevel level, BlockPos origin, TableBlockEntity table) {
		if (level == null || origin == null || table == null) {
			return;
		}
		String sitters = sittersAt(level, origin);
		String lookers = lookersAt(origin);
		for (var entry : List.copyOf(WATCHING.entrySet())) {
			Watching watching = entry.getValue();
			if (!origin.equals(watching.origin())) {
				continue;
			}
			ServerPlayer other = level.getServer().getPlayerList().getPlayer(entry.getKey());
			if (other == null) {
				WATCHING.remove(entry.getKey());
				continue;
			}
			TableSeat seat = seatOf(watching.seat());
			if (seat == null) {
				continue;
			}
			send(other, origin, seat, table, "", sitters, lookers);
		}
		refreshReveals(level, origin, table);
	}

	public static ServerLevel levelOf(ServerPlayer player) {
		return player.level() instanceof ServerLevel level ? level : null;
	}

	public static void sendClosed(ServerPlayer player, BlockPos origin, String seat) {
		ServerPlayNetworking.send(player, new HandSyncS2CPayload(origin, seat, 0, 0, 0, List.of(), List.of(), "closed", "", ""));
	}
}
