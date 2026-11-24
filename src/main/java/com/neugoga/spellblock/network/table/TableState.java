package com.neugoga.spellblock.network.table;

import com.neugoga.spellblock.table.TableCard;
import com.neugoga.spellblock.table.TableSeat;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player and per-table state that lives on the server: who has a table open, looks in progress, pending requests.
 * Everything here is touched on the server thread only.
 */
public final class TableState {
	private TableState() {
	}

	public static final double REACH = 8.0;

	public static final double REVEAL_REACH = 24.0;

	/** A look (scry, surveil, search) is given back when its player is this far from the table. */
	public static final double LOOK_REACH = REACH + 2.0;

	/** How often stale per-player state is swept, in ticks. */
	public static final int SWEEP_TICKS = 20;

	public static final Map<UUID, Watching> WATCHING = new HashMap<>();

	/** Private scry/surveil cards held off the library until the player finishes. */
	public static final Map<UUID, ScrySession> SCRY_SESSIONS = new HashMap<>();

	/** Face-down peeks: UUID -> "zone:index". */
	public static final Map<UUID, String> PEEKS = new HashMap<>();

	/** origin|targetSeat -> who asked to search that library. */
	public static final Map<String, SearchAsk> SEARCH_ASKS = new HashMap<>();

	/** origin|ownerSeat|zone|index -> requester. */
	public static final Map<String, SearchAsk> CONTROL_ASKS = new HashMap<>();

	public static void remapOrigin(BlockPos from, BlockPos to) {
		if (from == null || to == null || from.equals(to)) {
			return;
		}
		BlockPos dest = to.immutable();
		for (var entry : List.copyOf(WATCHING.entrySet())) {
			Watching w = entry.getValue();
			if (w != null && from.equals(w.origin())) {
				WATCHING.put(entry.getKey(), new Watching(w.dimension(), dest, w.seat()));
			}
		}
		for (var entry : List.copyOf(SCRY_SESSIONS.entrySet())) {
			ScrySession s = entry.getValue();
			if (s != null && from.equals(s.origin())) {
				SCRY_SESSIONS.put(entry.getKey(), new ScrySession(s.dimension(), dest, s.seat(), s.mode(), s.cards()));
			}
		}
		String fromPrefix = from.asLong() + "|";
		String toPrefix = dest.asLong() + "|";
		for (var entry : List.copyOf(SEARCH_ASKS.entrySet())) {
			if (entry.getKey().startsWith(fromPrefix)) {
				SEARCH_ASKS.put(toPrefix + entry.getKey().substring(fromPrefix.length()), entry.getValue());
				SEARCH_ASKS.remove(entry.getKey());
			}
		}
		for (var entry : List.copyOf(CONTROL_ASKS.entrySet())) {
			if (entry.getKey().startsWith(fromPrefix)) {
				CONTROL_ASKS.put(toPrefix + entry.getKey().substring(fromPrefix.length()), entry.getValue());
				CONTROL_ASKS.remove(entry.getKey());
			}
		}
	}

	public static void watch(ServerPlayer player, BlockPos origin, TableSeat seat) {
		PEEKS.remove(player.getUUID());
		WATCHING.put(player.getUUID(), new Watching(player.level().dimension(), origin, seat.name()));
	}

	/** The server has stopped. Nothing here may outlive it, because the JVM can host another world. */
	public static void clearAll() {
		WATCHING.clear();
		SCRY_SESSIONS.clear();
		PEEKS.clear();
		SEARCH_ASKS.clear();
		CONTROL_ASKS.clear();
	}

	public record SearchAsk(ResourceKey<Level> dimension, UUID requester, String askerSeat) {
	}

	public record Watching(ResourceKey<Level> dimension, BlockPos origin, String seat) {
	}

	public record ScrySession(ResourceKey<Level> dimension, BlockPos origin, String seat, String mode, List<TableCard> cards) {
	}

	/** A table, named by world and minimum corner. */
	public record TableRef(ResourceKey<Level> dimension, BlockPos origin) {
	}
}
