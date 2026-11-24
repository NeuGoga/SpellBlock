package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.network.table.Looks;
import com.neugoga.spellblock.network.table.TableState;
import com.neugoga.spellblock.network.table.TableState.ScrySession;
import com.neugoga.spellblock.network.table.TableState.TableRef;
import com.neugoga.spellblock.network.table.TableState.Watching;
import com.neugoga.spellblock.table.TableBlock;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableSeat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.neugoga.spellblock.network.table.CardMoves.adjustCounters;
import static com.neugoga.spellblock.network.table.CardMoves.clearAllCounters;
import static com.neugoga.spellblock.network.table.CardMoves.flip;
import static com.neugoga.spellblock.network.table.CardMoves.playDown;
import static com.neugoga.spellblock.network.table.CardMoves.putBack;
import static com.neugoga.spellblock.network.table.CardMoves.relocate;
import static com.neugoga.spellblock.network.table.CardMoves.spawnToken;
import static com.neugoga.spellblock.network.table.CardMoves.tap;
import static com.neugoga.spellblock.network.table.CardMoves.tokenCopy;
import static com.neugoga.spellblock.network.table.CardMoves.turn;
import static com.neugoga.spellblock.network.table.CardMoves.untapAll;
import static com.neugoga.spellblock.network.table.Library.draw;
import static com.neugoga.spellblock.network.table.Library.drawMany;
import static com.neugoga.spellblock.network.table.Library.mill;
import static com.neugoga.spellblock.network.table.Library.placeLibrary;
import static com.neugoga.spellblock.network.table.Library.placeNth;
import static com.neugoga.spellblock.network.table.Library.shuffle;
import static com.neugoga.spellblock.network.table.Life.changeLife;
import static com.neugoga.spellblock.network.table.Life.commanderDamage;
import static com.neugoga.spellblock.network.table.Life.setLife;
import static com.neugoga.spellblock.network.table.Looks.restoreSession;
import static com.neugoga.spellblock.network.table.Looks.startLook;
import static com.neugoga.spellblock.network.table.Requests.allowControl;
import static com.neugoga.spellblock.network.table.Requests.allowSearch;
import static com.neugoga.spellblock.network.table.Requests.askControl;
import static com.neugoga.spellblock.network.table.Requests.askSearch;
import static com.neugoga.spellblock.network.table.Requests.denyControl;
import static com.neugoga.spellblock.network.table.Requests.denySearch;
import static com.neugoga.spellblock.network.table.Requests.expandControl;
import static com.neugoga.spellblock.network.table.Requests.pruneAsks;
import static com.neugoga.spellblock.network.table.Requests.removeAsks;
import static com.neugoga.spellblock.network.table.Reveals.revealCard;
import static com.neugoga.spellblock.network.table.Reveals.revealHand;
import static com.neugoga.spellblock.network.table.TableState.CONTROL_ASKS;
import static com.neugoga.spellblock.network.table.TableState.LOOK_REACH;
import static com.neugoga.spellblock.network.table.TableState.PEEKS;
import static com.neugoga.spellblock.network.table.TableState.REACH;
import static com.neugoga.spellblock.network.table.TableState.SCRY_SESSIONS;
import static com.neugoga.spellblock.network.table.TableState.SEARCH_ASKS;
import static com.neugoga.spellblock.network.table.TableState.SWEEP_TICKS;
import static com.neugoga.spellblock.network.table.TableState.WATCHING;
import static com.neugoga.spellblock.network.table.TableState.watch;
import static com.neugoga.spellblock.network.table.TableSync.pushReveal;
import static com.neugoga.spellblock.network.table.TableSync.refreshAll;
import static com.neugoga.spellblock.network.table.TableSync.refreshReveals;
import static com.neugoga.spellblock.network.table.TableSync.refreshSitters;
import static com.neugoga.spellblock.network.table.TableSync.send;
import static com.neugoga.spellblock.network.table.TableSync.sendClosed;
import static com.neugoga.spellblock.network.table.TableText.parseCount;
import static com.neugoga.spellblock.network.table.TableText.seatOf;

/** Moves cards between a seat's library, hand, and the public zones. No rules. */
public final class TableServer {
	private TableServer() {
	}

	public static boolean open(ServerPlayer player, BlockPos pos, BlockState state, BlockHitResult hit) {
		SeatAt seat = find(player, pos, state);
		if (seat == null) {
			return false;
		}
		TableSeat chosen = TableSeat.choose(player, hit, state, pos);
		if (chosen == null) {
			for (TableSeat candidate : TableSeat.values()) {
				if (TableSeat.fits(state.getValue(TableBlock.SIZE), candidate)) {
					chosen = candidate;
					break;
				}
			}
		}
		if (chosen == null) {
			return false;
		}
		watch(player, seat.origin, chosen);
		send(player, seat.origin, chosen, seat.table, "");
		if (player.level() instanceof ServerLevel level) {
			refreshSitters(level, seat.origin, seat.table);
		}
		return true;
	}

	public static void act(ServerPlayer player, TableActionC2SPayload payload) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		// Closing must work from anywhere. It only releases this player's own state.
		if ("close".equals(payload.action())) {
			closeTable(player, level.getServer());
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
			sendClosed(player, origin, payload.seat());
			return;
		}
		String action = payload.action();
		watch(player, origin, seat);
		refreshSitters(level, origin, table);
		if (table.get(seat).isEmpty()) {
			send(player, origin, seat, table, "No deck box on this side.");
			return;
		}
		TableAction.Parsed parsed = TableAction.parse(action);
		if (parsed == null) {
			return;
		}
		TableAction type = parsed.action();
		String arg = parsed.arg();
		// Actions on one card name it by id. Find where it sits now, which may differ from what the player saw.
		int index = -1;
		if (type.source() != null) {
			index = table.zones(seat).indexOfId(type.source(), payload.cardId());
			if (index < 0) {
				send(player, origin, seat, table, "");
				return;
			}
		}
		if (type.isMove()) {
			relocate(player, level, origin, table, seat, type.source(), type.destination(), index);
			return;
		}
		switch (type) {
			case HAND_TO_LIBRARY, FIELD_TO_LIBRARY, GRAVE_TO_LIBRARY, EXILE_TO_LIBRARY ->
				putBack(player, level, origin, table, seat, type.source(), index);
			case TAP -> tap(player, level, origin, table, seat, index);
			case COMMAND_TAX -> {
				int delta = payload.amount() == 0 ? 1 : payload.amount();
				if (table.zones(seat).adjustCommandTax(index, delta)) {
					table.sync();
				}
				send(player, origin, seat, table, "");
			}
			case PLAY_FACE_DOWN -> playDown(player, level, origin, table, seat, index);
			case TURN_FACE_DOWN -> turn(player, level, origin, table, seat, index, true);
			case TURN_FACE_UP -> turn(player, level, origin, table, seat, index, false);
			case FLIP -> flip(player, level, origin, table, seat, index);
			case TOKEN_COPY -> tokenCopy(player, level, origin, table, seat, index);
			case COUNTER_POWER -> adjustCounters(player, level, origin, table, seat, index, "p", payload.amount());
			case COUNTER_TOUGH -> adjustCounters(player, level, origin, table, seat, index, "t", payload.amount());
			case COUNTER_BOTH -> adjustCounters(player, level, origin, table, seat, index, "b", payload.amount());
			case COUNTER_LOYALTY -> adjustCounters(player, level, origin, table, seat, index, "l", payload.amount());
			case COUNTER_MARKER -> adjustCounters(player, level, origin, table, seat, index, "m", payload.amount());
			case CLEAR_COUNTERS -> clearAllCounters(player, level, origin, table, seat, index);
			case DRAW -> draw(player, level, origin, table, seat);
			case SHUFFLE -> shuffle(player, level, origin, table, seat);
			case SEARCH -> startLook(player, level, origin, table, seat, "search", 200);
			case DENY_SEARCH -> denySearch(player, level, origin, table, seat);
			case DENY_CONTROL -> denyControl(player, level, origin, table, seat);
			case LIBRARY_TOP, LIBRARY_BOTTOM ->
				placeLibrary(player, level, origin, table, seat, arg, payload.cardId(), type == TableAction.LIBRARY_TOP);
			case LIFE -> changeLife(player, level, origin, table, seat, arg, payload.amount());
			case SET_LIFE -> {
				String target = arg;
				int value = payload.amount();
				int colon = arg.lastIndexOf(':');
				if (colon > 0) {
					try {
						value = Integer.parseInt(arg.substring(colon + 1).trim());
						target = arg.substring(0, colon);
					} catch (NumberFormatException ignored) {
					}
				}
				if (value <= 0 && payload.amount() > 0) {
					value = payload.amount();
				}
				setLife(player, level, origin, table, seat, target, value);
			}
			case REVEAL_HAND -> revealHand(player, level, origin, table, seat, arg);
			case REVEAL_CARD -> revealCard(player, level, origin, table, seat, arg, payload.cardId());
			case ASK_SEARCH -> askSearch(player, level, origin, table, seat, arg);
			case ALLOW_SEARCH -> allowSearch(player, level, origin, table, seat, arg);
			case ASK_CONTROL -> askControl(player, level, origin, table, seat, arg, payload.cardId());
			case ASK_CONTROL_SHORT -> askControl(player, level, origin, table, seat, expandControl(arg), payload.cardId());
			case ALLOW_CONTROL -> allowControl(player, level, origin, table, seat, arg);
			case SCRY, SURVEIL ->
				startLook(player, level, origin, table, seat, type == TableAction.SCRY ? "scry" : "surveil", parseCount(action));
			case PEEK_OFF -> {
				if (PEEKS.remove(player.getUUID()) != null) {
					refreshSitters(level, origin, table);
				}
			}
			case PEEK -> {
				String target = arg.length() > 24 ? arg.substring(0, 24) : arg;
				PEEKS.put(player.getUUID(), target);
				refreshSitters(level, origin, table);
			}
			case MILL -> mill(player, level, origin, table, seat, parseCount(action));
			case DRAW_MANY -> drawMany(player, level, origin, table, seat, parseCount(action));
			case TOKEN_SPAWN -> spawnToken(player, level, origin, table, seat, arg);
			case CMD_DMG -> {
				String target = arg;
				String from = seat.name();
				int bar = arg.indexOf('|');
				if (bar >= 0) {
					target = arg.substring(0, bar);
					from = arg.substring(bar + 1);
				}
				int delta = payload.amount() == 0 ? 1 : payload.amount();
				commanderDamage(player, level, origin, table, seat, target, from, delta);
			}
			case LIB_NTH -> {
				int n = payload.amount() <= 0 ? 2 : payload.amount();
				placeNth(player, level, origin, table, seat, arg, payload.cardId(), n);
			}
			case UNTAP -> untapAll(player, level, origin, table, seat, arg.isEmpty() ? "all" : arg);
			default -> {
			}
		}
	}

	public static void dropped(ServerLevel level, BlockPos origin) {
		if (!(level.getBlockEntity(origin) instanceof TableBlockEntity table)) {
			pruneAsks(origin, null);
			for (TableSeat seat : TableSeat.values()) {
				pushReveal(level, origin, null, seat);
			}
			return;
		}
		if (pruneAsks(origin, table)) {
			refreshSitters(level, origin, table);
		}
		refreshReveals(level, origin, table);
	}

	/** The player closed the table screen. */
	private static void closeTable(ServerPlayer player, MinecraftServer server) {
		UUID id = player.getUUID();
		Set<TableRef> dirty = new LinkedHashSet<>();
		PEEKS.remove(id);
		Watching watching = WATCHING.remove(id);
		if (watching != null) {
			dirty.add(new TableRef(watching.dimension(), watching.origin()));
		}
		restoreSession(server, id, dirty);
		refreshAll(server, dirty);
	}

	/** A player left the server. Called from the disconnect event, while they are still in the player list. */
	public static void onDisconnect(MinecraftServer server, ServerPlayer player) {
		UUID id = player.getUUID();
		Set<TableRef> dirty = new LinkedHashSet<>();
		PEEKS.remove(id);
		Watching watching = WATCHING.remove(id);
		if (watching != null) {
			dirty.add(new TableRef(watching.dimension(), watching.origin()));
		}
		removeAsks(ask -> ask.requester().equals(id), dirty);
		restoreSession(server, id, dirty);
		refreshAll(server, dirty);
	}

	/** The server is stopping. Hand every held card back before the world saves. */
	public static void onServerStopping(MinecraftServer server) {
		Set<TableRef> dirty = new LinkedHashSet<>();
		for (UUID id : List.copyOf(SCRY_SESSIONS.keySet())) {
			try {
				restoreSession(server, id, dirty);
			} catch (RuntimeException e) {
				SpellBlock.LOGGER.error("Could not return looked-at cards on shutdown.", e);
			}
		}
	}

	/**
	 * Called once per server tick. Every {@link #SWEEP_TICKS} it gives back looks whose player is gone,
	 * dead, in another world, or too far to act, and forgets state that belongs to players who left.
	 */
	public static void sweep(MinecraftServer server) {
		if (server.getTickCount() % SWEEP_TICKS != 0) {
			return;
		}
		if (SCRY_SESSIONS.isEmpty() && WATCHING.isEmpty() && PEEKS.isEmpty() && SEARCH_ASKS.isEmpty() && CONTROL_ASKS.isEmpty()) {
			return;
		}
		Set<TableRef> dirty = new LinkedHashSet<>();
		for (var entry : List.copyOf(SCRY_SESSIONS.entrySet())) {
			ScrySession session = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			boolean gone = player == null
				|| !player.isAlive()
				|| !player.level().dimension().equals(session.dimension())
				|| player.distanceToSqr(Vec3.atCenterOf(session.origin())) > LOOK_REACH * LOOK_REACH;
			if (gone) {
				restoreSession(server, entry.getKey(), dirty);
			}
		}
		for (var entry : List.copyOf(WATCHING.entrySet())) {
			if (server.getPlayerList().getPlayer(entry.getKey()) == null) {
				Watching watching = WATCHING.remove(entry.getKey());
				if (watching != null) {
					dirty.add(new TableRef(watching.dimension(), watching.origin()));
				}
			}
		}
		// A peek only exists while its player has the table open.
		PEEKS.keySet().removeIf(id -> !WATCHING.containsKey(id));
		removeAsks(ask -> server.getPlayerList().getPlayer(ask.requester()) == null, dirty);
		refreshAll(server, dirty);
	}

	private static SeatAt find(ServerPlayer player, BlockPos pos, BlockState state) {
		if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(pos)) {
			return null;
		}
		if (player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
			return null;
		}
		if (!(state.getBlock() instanceof TableBlock)) {
			return null;
		}
		BlockPos origin = pos.offset(-state.getValue(TableBlock.X), 0, -state.getValue(TableBlock.Z));
		if (!(level.getBlockEntity(origin) instanceof TableBlockEntity table)) {
			return null;
		}
		return new SeatAt(origin, table);
	}

	private record SeatAt(BlockPos origin, TableBlockEntity table) {
	}

	/** Gives back the cards a player is looking at, for a table box being picked up or broken. */
	public static void restoreLooksAt(ServerLevel level, BlockPos origin, TableSeat seat) {
		Looks.restoreLooksAt(level, origin, seat);
	}

	public static void finishScry(ServerPlayer player, ScryFinishC2SPayload payload) {
		Looks.finishScry(player, payload);
	}

	public static void clearAll() {
		TableState.clearAll();
	}
}
