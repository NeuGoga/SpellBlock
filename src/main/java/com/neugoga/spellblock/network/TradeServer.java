package com.neugoga.spellblock.network;

import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.datapack.ModRules;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.LibraryWorldData;
import com.neugoga.spellblock.library.PlayerLibrary;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server-side player-to-player card trading (/spellblock trade <player> | cancel).
 * Offers are selections against each player's binder; cards only move atomically once both lock
 * and the 3-second cancel countdown finishes.
 */
public final class TradeServer {
	public static final int MAX_DISTANCE_BLOCKS = 64;
	public static final double MAX_DISTANCE_SQR = (double) MAX_DISTANCE_BLOCKS * MAX_DISTANCE_BLOCKS;
	public static final long ASK_TIMEOUT_MS = 30_000L;
	public static final int LOCK_COUNTDOWN_TICKS = 60; // 3 seconds at 20 TPS
	public static final int DEFAULT_COOLDOWN_SECONDS = 60;

	private static final Map<UUID, PendingAsk> OUTGOING_ASKS = new HashMap<>();
	private static final Map<UUID, TradeSession> ACTIVE_SESSIONS = new HashMap<>();
	private static final Map<UUID, Long> INITIATOR_COOLDOWNS = new HashMap<>();

	private TradeServer() {
	}

	public record CommandResult(boolean ok, String message) {
		static CommandResult success(String message) {
			return new CommandResult(true, message);
		}

		static CommandResult fail(String message) {
			return new CommandResult(false, message);
		}
	}

	public record PendingAsk(UUID asker, UUID target, String askerName, String targetName, long expiresAtMs) {
	}

	public record SwapResult(boolean ok, String error) {
		public static SwapResult success() {
			return new SwapResult(true, "");
		}

		public static SwapResult fail(String error) {
			return new SwapResult(false, error);
		}
	}

	/** Pure trade session state between initiator ({@code playerA}) and partner ({@code playerB}). */
	public static final class TradeSession {
		private final UUID playerA;
		private final UUID playerB;
		private final Map<CardKey, Integer> offerA = new LinkedHashMap<>();
		private final Map<CardKey, Integer> offerB = new LinkedHashMap<>();
		private boolean lockedA;
		private boolean lockedB;
		private int countdownTicks = -1;

		public TradeSession(UUID initiator, UUID partner) {
			this.playerA = initiator;
			this.playerB = partner;
		}

		public UUID initiator() {
			return playerA;
		}

		public UUID playerA() {
			return playerA;
		}

		public UUID playerB() {
			return playerB;
		}

		public boolean involves(UUID id) {
			return playerA.equals(id) || playerB.equals(id);
		}

		public UUID partnerOf(UUID id) {
			return playerA.equals(id) ? playerB : playerA;
		}

		public Map<CardKey, Integer> offerOf(UUID id) {
			return playerA.equals(id) ? offerA : offerB;
		}

		public Map<CardKey, Integer> otherOfferOf(UUID id) {
			return playerA.equals(id) ? offerB : offerA;
		}

		public boolean isLocked(UUID id) {
			return playerA.equals(id) ? lockedA : lockedB;
		}

		public boolean isOtherLocked(UUID id) {
			return playerA.equals(id) ? lockedB : lockedA;
		}

		public boolean bothLocked() {
			return lockedA && lockedB;
		}

		public int countdownTicks() {
			return countdownTicks;
		}

		public int countdownSeconds() {
			if (countdownTicks < 0) {
				return -1;
			}
			return countdownTicks == 0 ? 0 : (countdownTicks + 19) / 20;
		}

		public void clearBothLocks() {
			lockedA = false;
			lockedB = false;
			countdownTicks = -1;
		}

		public String addCard(UUID playerId, PlayerLibrary library, CardKey key, int amount) {
			if (!involves(playerId) || key == null || key.oracleId().isEmpty() || amount <= 0) {
				return "Invalid card selection.";
			}
			int owned = library == null ? 0 : library.count(key);
			if (owned <= 0) {
				return "You do not own that card.";
			}
			Map<CardKey, Integer> offer = offerOf(playerId);
			int offered = offer.getOrDefault(key, 0);
			int add = Math.min(amount, owned - offered);
			if (add <= 0) {
				return "All owned copies are already offered.";
			}
			if (!offer.containsKey(key) && offer.size() >= TradeSyncS2CPayload.MAX_OFFER_CARDS) {
				return "Offer is full (max " + TradeSyncS2CPayload.MAX_OFFER_CARDS + " slots).";
			}
			offer.put(key, offered + add);
			clearBothLocks();
			return "";
		}

		public String removeCard(UUID playerId, CardKey key, int amount) {
			if (!involves(playerId) || key == null || key.oracleId().isEmpty() || amount <= 0) {
				return "Invalid card.";
			}
			Map<CardKey, Integer> offer = offerOf(playerId);
			int offered = offer.getOrDefault(key, 0);
			if (offered <= 0) {
				return "That card is not in your offer.";
			}
			int next = offered - amount;
			if (next <= 0) {
				offer.remove(key);
			} else {
				offer.put(key, next);
			}
			clearBothLocks();
			return "";
		}

		public String toggleLock(UUID playerId) {
			if (!involves(playerId)) {
				return "No active trade.";
			}
			if (countdownTicks >= 0) {
				return "Use Cancel during the countdown.";
			}
			if (!isLocked(playerId) && offerA.isEmpty() && offerB.isEmpty()) {
				return "Offer at least one card before locking.";
			}
			if (playerA.equals(playerId)) {
				lockedA = !lockedA;
			} else {
				lockedB = !lockedB;
			}
			if (lockedA && lockedB) {
				countdownTicks = LOCK_COUNTDOWN_TICKS;
			}
			return "";
		}

		public boolean cancelCountdown() {
			if (!lockedA && !lockedB && countdownTicks < 0) {
				return false;
			}
			clearBothLocks();
			return true;
		}

		/** Advances countdown by one server tick. Returns true if the displayed second changed or hit zero. */
		public boolean tickCountdown() {
			if (!bothLocked() || countdownTicks <= 0) {
				return false;
			}
			int prevSec = countdownSeconds();
			countdownTicks--;
			return countdownSeconds() != prevSec || countdownTicks == 0;
		}
	}

	/** Executes an atomic swap between two libraries; aborts cleanly with no mutation on any error. */
	public static SwapResult executeSwap(
		PlayerLibrary libA,
		PlayerLibrary libB,
		Map<CardKey, Integer> offerA,
		Map<CardKey, Integer> offerB,
		Predicate<String> canAddOracleId
	) {
		if ((offerA == null || offerA.isEmpty()) && (offerB == null || offerB.isEmpty())) {
			return SwapResult.fail("Trade aborted: both offers are empty.");
		}
		if (libA == null || libB == null) {
			return SwapResult.fail("Trade aborted: player binder missing.");
		}
		if (offerA != null) {
			for (Map.Entry<CardKey, Integer> e : offerA.entrySet()) {
				if (e.getValue() <= 0 || libA.count(e.getKey()) < e.getValue()) {
					return SwapResult.fail("Trade aborted: offered cards are no longer in binder.");
				}
				if (canAddOracleId != null && !canAddOracleId.test(e.getKey().oracleId())) {
					return SwapResult.fail("Trade aborted: card is disabled by server rules.");
				}
			}
		}
		if (offerB != null) {
			for (Map.Entry<CardKey, Integer> e : offerB.entrySet()) {
				if (e.getValue() <= 0 || libB.count(e.getKey()) < e.getValue()) {
					return SwapResult.fail("Trade aborted: offered cards are no longer in binder.");
				}
				if (canAddOracleId != null && !canAddOracleId.test(e.getKey().oracleId())) {
					return SwapResult.fail("Trade aborted: card is disabled by server rules.");
				}
			}
		}
		if (!canHoldIncoming(libA, offerA, offerB) || !canHoldIncoming(libB, offerB, offerA)) {
			return SwapResult.fail("Trade aborted: receiver binder cannot hold the incoming cards.");
		}

		if (offerA != null) {
			for (Map.Entry<CardKey, Integer> e : offerA.entrySet()) {
				CardKey key = e.getKey();
				int count = e.getValue();
				libA.add(key, -count);
				if (libA.count(key) <= 0) {
					libA.setFavourite(key, false);
				}
				libB.add(key, count);
			}
		}
		if (offerB != null) {
			for (Map.Entry<CardKey, Integer> e : offerB.entrySet()) {
				CardKey key = e.getKey();
				int count = e.getValue();
				libB.add(key, -count);
				if (libB.count(key) <= 0) {
					libB.setFavourite(key, false);
				}
				libA.add(key, count);
			}
		}
		return SwapResult.success();
	}

	private static boolean canHoldIncoming(
		PlayerLibrary receiver,
		Map<CardKey, Integer> outgoing,
		Map<CardKey, Integer> incoming
	) {
		if (incoming == null || incoming.isEmpty()) {
			return true;
		}
		Set<CardKey> keysAfter = new HashSet<>(receiver.cards().keySet());
		if (outgoing != null) {
			for (Map.Entry<CardKey, Integer> e : outgoing.entrySet()) {
				if (receiver.count(e.getKey()) - e.getValue() <= 0) {
					keysAfter.remove(e.getKey());
				}
			}
		}
		for (Map.Entry<CardKey, Integer> e : incoming.entrySet()) {
			CardKey key = e.getKey();
			int outCount = outgoing == null ? 0 : outgoing.getOrDefault(key, 0);
			int existingRemaining = Math.max(0, receiver.count(key) - outCount);
			if ((long) existingRemaining + e.getValue() > Integer.MAX_VALUE) {
				return false;
			}
			keysAfter.add(key);
		}
		return keysAfter.size() <= BinderSyncS2CPayload.MAX_CARDS;
	}

	public static int cooldownSecondsLeft(UUID playerId, long nowMs) {
		if (playerId == null) {
			return 0;
		}
		Long until = INITIATOR_COOLDOWNS.get(playerId);
		if (until == null) {
			return 0;
		}
		long remainingMs = until - nowMs;
		if (remainingMs <= 0L) {
			INITIATOR_COOLDOWNS.remove(playerId);
			return 0;
		}
		return (int) ((remainingMs + 999L) / 1000L);
	}

	public static void startCooldown(UUID initiatorId, long nowMs, int cooldownSeconds) {
		if (initiatorId == null || cooldownSeconds <= 0) {
			return;
		}
		INITIATOR_COOLDOWNS.put(initiatorId, nowMs + cooldownSeconds * 1000L);
	}

	private static int configuredMaxDistance() {
		return SpellBlockDataPacks.rules().tradeMaxDistance();
	}

	private static int configuredCooldownSeconds() {
		ModRules rules = SpellBlockDataPacks.rules();
		return rules.trade() ? rules.tradeCooldownSeconds() : 0;
	}

	public static CommandResult requestOrAccept(ServerPlayer player, ServerPlayer target) {
		if (!SpellBlockDataPacks.rules().trade()) {
			return CommandResult.fail("Trading is turned off on this server.");
		}
		if (player == null || target == null) {
			return CommandResult.fail("Target player is not online.");
		}
		if (player.getUUID().equals(target.getUUID())) {
			return CommandResult.fail("You cannot trade with yourself.");
		}
		if (!player.isAlive()) {
			return CommandResult.fail("You cannot trade right now.");
		}
		if (!target.isAlive()) {
			return CommandResult.fail(target.getScoreboardName() + " cannot trade right now.");
		}
		if (OracleService.index() == null) {
			return CommandResult.fail("Card index is still building (" + OracleService.detail() + ").");
		}
		if (ACTIVE_SESSIONS.containsKey(player.getUUID())) {
			return CommandResult.fail("You are already in a trade.");
		}
		if (ACTIVE_SESSIONS.containsKey(target.getUUID())) {
			return CommandResult.fail(target.getScoreboardName() + " is already in a trade.");
		}
		if (!player.level().dimension().equals(target.level().dimension())) {
			return CommandResult.fail(target.getScoreboardName() + " must be in the same dimension.");
		}
		int maxDist = configuredMaxDistance();
		double maxDistSqr = (double) maxDist * maxDist;
		double distSqr = player.distanceToSqr(target);
		if (distSqr > maxDistSqr) {
			int dist = (int) Math.ceil(Math.sqrt(distSqr));
			return CommandResult.fail(target.getScoreboardName() + " is too far away (" + dist + " blocks; max " + maxDist + ").");
		}

		long now = System.currentTimeMillis();
		purgeExpiredAsks(player.getServer(), now);

		// Check if target already sent a pending ask to player -> accept it!
		PendingAsk incoming = OUTGOING_ASKS.get(target.getUUID());
		if (incoming != null && incoming.target().equals(player.getUUID())) {
			OUTGOING_ASKS.remove(target.getUUID());
			cancelAnyOtherIncomingFor(player.getUUID(), target.getUUID());
			openTradeSession(player.getServer(), target, player);
			return CommandResult.success("Opened trade with " + target.getScoreboardName() + ".");
		}

		// Starting a new ask: check initiator cooldown & existing outgoing ask
		int wait = cooldownSecondsLeft(player.getUUID(), now);
		if (wait > 0) {
			return CommandResult.fail("Trade cooldown: wait " + wait + "s before starting another trade.");
		}
		PendingAsk existing = OUTGOING_ASKS.get(player.getUUID());
		if (existing != null) {
			return CommandResult.fail("You already have a pending trade request to " + existing.targetName() + ". Run /spellblock trade cancel first.");
		}

		String askerName = player.getScoreboardName();
		String targetName = target.getScoreboardName();
		OUTGOING_ASKS.put(
			player.getUUID(),
			new PendingAsk(player.getUUID(), target.getUUID(), askerName, targetName, now + ASK_TIMEOUT_MS)
		);
		target.sendSystemMessage(Component.literal(
			askerName + " wants to trade. /spellblock trade " + askerName + " to accept, or /spellblock trade cancel."
		));
		return CommandResult.success("Sent trade request to " + targetName + " (expires in 30s).");
	}

	public static CommandResult cancel(ServerPlayer player) {
		if (player == null) {
			return CommandResult.fail("Only a player can cancel a trade.");
		}
		MinecraftServer server = player.getServer();
		UUID id = player.getUUID();
		long now = System.currentTimeMillis();

		// 1. Active trade
		TradeSession session = ACTIVE_SESSIONS.get(id);
		if (session != null) {
			cancelActiveSession(server, session, player.getScoreboardName() + " cancelled the trade.", true);
			return CommandResult.success("Trade cancelled.");
		}

		// 2. Outgoing pending ask
		PendingAsk outgoing = OUTGOING_ASKS.remove(id);
		if (outgoing != null) {
			startCooldown(outgoing.asker(), now, configuredCooldownSeconds());
			if (server != null) {
				ServerPlayer target = server.getPlayerList().getPlayer(outgoing.target());
				if (target != null) {
					target.sendSystemMessage(Component.literal(outgoing.askerName() + " cancelled their trade request."));
				}
			}
			return CommandResult.success("Trade request to " + outgoing.targetName() + " cancelled.");
		}

		// 3. Incoming pending ask (refuse) -> cooldown applies ONLY to the asker, not receiver
		PendingAsk incoming = findIncomingAsk(id);
		if (incoming != null) {
			OUTGOING_ASKS.remove(incoming.asker());
			startCooldown(incoming.asker(), now, configuredCooldownSeconds());
			if (server != null) {
				ServerPlayer asker = server.getPlayerList().getPlayer(incoming.asker());
				if (asker != null) {
					asker.sendSystemMessage(Component.literal(player.getScoreboardName() + " declined your trade request."));
				}
			}
			return CommandResult.success("Declined trade request from " + incoming.askerName() + ".");
		}

		return CommandResult.fail("No active trade or pending trade request.");
	}

	public static void act(ServerPlayer player, TradeActionC2SPayload payload) {
		if (player == null || payload == null || payload.action() == null) {
			return;
		}
		String action = payload.action().trim().toLowerCase(Locale.ROOT);
		TradeSession session = ACTIVE_SESSIONS.get(player.getUUID());
		if (session == null) {
			if (!"close".equals(action)) {
				ServerPlayNetworking.send(player, TradeSyncS2CPayload.ofClosed("Trade is no longer active."));
			}
			return;
		}
		MinecraftServer server = player.getServer();
		if ("close".equals(action)) {
			cancelActiveSession(server, session, player.getScoreboardName() + " closed the trade.", true);
			return;
		}

		OracleIndex index = OracleService.index();
		if (index == null) {
			cancelActiveSession(server, session, "Card index unavailable; trade cancelled.", true);
			return;
		}

		String statusA = "";
		String statusB = "";
		switch (action) {
			case "add" -> {
				PlayerLibrary lib = LibraryWorldData.get(server).get(player.getUUID());
				CardKey key = new CardKey(payload.oracleId(), payload.printingId(), Finish.fromName(payload.finish()));
				String err = session.addCard(player.getUUID(), lib, key, Math.max(1, payload.amount()));
				if (!err.isEmpty()) {
					if (session.playerA().equals(player.getUUID())) {
						statusA = err;
					} else {
						statusB = err;
					}
				}
			}
			case "remove" -> {
				CardKey key = new CardKey(payload.oracleId(), payload.printingId(), Finish.fromName(payload.finish()));
				String err = session.removeCard(player.getUUID(), key, Math.max(1, payload.amount()));
				if (!err.isEmpty()) {
					if (session.playerA().equals(player.getUUID())) {
						statusA = err;
					} else {
						statusB = err;
					}
				}
			}
			case "lock" -> {
				String err = session.toggleLock(player.getUUID());
				if (!err.isEmpty()) {
					if (session.playerA().equals(player.getUUID())) {
						statusA = err;
					} else {
						statusB = err;
					}
				}
			}
			case "cancel_countdown" -> {
				if (session.cancelCountdown()) {
					String msg = player.getScoreboardName() + " cancelled lock.";
					statusA = msg;
					statusB = msg;
				}
			}
			default -> {
				return;
			}
		}

		syncSession(server, session, index, statusA, statusB, false);
	}

	public static void onDisconnect(MinecraftServer server, ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID id = player.getUUID();
		TradeSession session = ACTIVE_SESSIONS.get(id);
		if (session != null) {
			cancelActiveSession(server, session, player.getScoreboardName() + " disconnected; trade cancelled.", true);
		}
		PendingAsk outgoing = OUTGOING_ASKS.remove(id);
		if (outgoing != null) {
			startCooldown(outgoing.asker(), System.currentTimeMillis(), configuredCooldownSeconds());
			if (server != null) {
				ServerPlayer target = server.getPlayerList().getPlayer(outgoing.target());
				if (target != null) {
					target.sendSystemMessage(Component.literal(outgoing.askerName() + " disconnected; trade request cancelled."));
				}
			}
		}
		PendingAsk incoming = findIncomingAsk(id);
		if (incoming != null) {
			OUTGOING_ASKS.remove(incoming.asker());
			startCooldown(incoming.asker(), System.currentTimeMillis(), configuredCooldownSeconds());
			if (server != null) {
				ServerPlayer asker = server.getPlayerList().getPlayer(incoming.asker());
				if (asker != null) {
					asker.sendSystemMessage(Component.literal(player.getScoreboardName() + " disconnected; trade request cancelled."));
				}
			}
		}
	}

	public static void sweep(MinecraftServer server) {
		if (server == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (server.getTickCount() % 20 == 0) {
			purgeExpiredAsks(server, now);
		}
		if (ACTIVE_SESSIONS.isEmpty()) {
			return;
		}
		int maxDist = configuredMaxDistance();
		double maxDistSqr = (double) maxDist * maxDist;
		Set<TradeSession> unique = new HashSet<>(ACTIVE_SESSIONS.values());
		for (TradeSession session : unique) {
			ServerPlayer a = server.getPlayerList().getPlayer(session.playerA());
			ServerPlayer b = server.getPlayerList().getPlayer(session.playerB());
			if (a == null || b == null) {
				cancelActiveSession(server, session, "Trade cancelled: partner left.", true);
				continue;
			}
			if (!a.isAlive() || !b.isAlive()) {
				cancelActiveSession(server, session, "Trade cancelled: a player died.", true);
				continue;
			}
			if (!a.level().dimension().equals(b.level().dimension()) || a.distanceToSqr(b) > maxDistSqr) {
				cancelActiveSession(server, session, "Trade cancelled: players moved out of range (max " + maxDist + " blocks).", true);
				continue;
			}
			if (session.bothLocked() && session.tickCountdown()) {
				if (session.countdownTicks() <= 0) {
					finishTradeSwap(server, session, a, b);
				} else {
					OracleIndex index = OracleService.index();
					if (index != null) {
						syncSession(server, session, index, "", "", false);
					}
				}
			}
		}
	}

	public static void onDataPackReload(MinecraftServer server) {
		if (SpellBlockDataPacks.rules().trade()) {
			return;
		}
		if (!OUTGOING_ASKS.isEmpty()) {
			for (PendingAsk ask : List.copyOf(OUTGOING_ASKS.values())) {
				if (server != null) {
					ServerPlayer asker = server.getPlayerList().getPlayer(ask.asker());
					if (asker != null) {
						asker.sendSystemMessage(Component.literal("Trade request cancelled: trading disabled by server rules."));
					}
				}
			}
			OUTGOING_ASKS.clear();
		}
		if (!ACTIVE_SESSIONS.isEmpty()) {
			for (TradeSession session : new HashSet<>(ACTIVE_SESSIONS.values())) {
				cancelActiveSession(server, session, "Trade cancelled: trading disabled by server rules.", false);
			}
		}
	}

	public static void clearAll() {
		OUTGOING_ASKS.clear();
		ACTIVE_SESSIONS.clear();
		INITIATOR_COOLDOWNS.clear();
	}

	private static void openTradeSession(MinecraftServer server, ServerPlayer initiator, ServerPlayer partner) {
		// Release any Dustery locks held by either player before opening Trade UI
		DusteryServer.onDisconnect(server, initiator);
		DusteryServer.onDisconnect(server, partner);

		TradeSession session = new TradeSession(initiator.getUUID(), partner.getUUID());
		ACTIVE_SESSIONS.put(initiator.getUUID(), session);
		ACTIVE_SESSIONS.put(partner.getUUID(), session);

		OracleIndex index = OracleService.index();
		syncSession(server, session, index, "Trade opened with " + partner.getScoreboardName() + ".", "Trade opened with " + initiator.getScoreboardName() + ".", true);
	}

	private static void finishTradeSwap(MinecraftServer server, TradeSession session, ServerPlayer a, ServerPlayer b) {
		OracleIndex index = OracleService.index();
		if (index == null) {
			session.clearBothLocks();
			syncSession(server, session, null, "Trade aborted: card index not ready.", "Trade aborted: card index not ready.", false);
			return;
		}
		LibraryWorldData worldData = LibraryWorldData.get(server);
		PlayerLibrary libA = worldData.getOrCreate(a.getUUID());
		PlayerLibrary libB = worldData.getOrCreate(b.getUUID());

		SwapResult result = executeSwap(
			libA,
			libB,
			session.offerOf(a.getUUID()),
			session.offerOf(b.getUUID()),
			BinderServer::canAddCard
		);
		if (!result.ok()) {
			session.clearBothLocks();
			syncSession(server, session, index, result.error(), result.error(), true);
			return;
		}

		worldData.setDirty();
		ACTIVE_SESSIONS.remove(session.playerA());
		ACTIVE_SESSIONS.remove(session.playerB());
		startCooldown(session.initiator(), System.currentTimeMillis(), configuredCooldownSeconds());

		ServerPlayNetworking.send(a, TradeSyncS2CPayload.ofClosed("Trade complete."));
		ServerPlayNetworking.send(b, TradeSyncS2CPayload.ofClosed("Trade complete."));
		a.sendSystemMessage(Component.literal("Trade complete with " + b.getScoreboardName() + "."));
		b.sendSystemMessage(Component.literal("Trade complete with " + a.getScoreboardName() + "."));
	}

	private static void cancelActiveSession(MinecraftServer server, TradeSession session, String reason, boolean applyCooldown) {
		ACTIVE_SESSIONS.remove(session.playerA());
		ACTIVE_SESSIONS.remove(session.playerB());
		if (applyCooldown) {
			startCooldown(session.initiator(), System.currentTimeMillis(), configuredCooldownSeconds());
		}
		if (server == null) {
			return;
		}
		TradeSyncS2CPayload closePacket = TradeSyncS2CPayload.ofClosed(reason);
		ServerPlayer a = server.getPlayerList().getPlayer(session.playerA());
		if (a != null) {
			ServerPlayNetworking.send(a, closePacket);
			if (reason != null && !reason.isEmpty()) {
				a.sendSystemMessage(Component.literal(reason));
			}
		}
		ServerPlayer b = server.getPlayerList().getPlayer(session.playerB());
		if (b != null) {
			ServerPlayNetworking.send(b, closePacket);
			if (reason != null && !reason.isEmpty()) {
				b.sendSystemMessage(Component.literal(reason));
			}
		}
	}

	private static void syncSession(
		MinecraftServer server,
		TradeSession session,
		OracleIndex index,
		String statusA,
		String statusB,
		boolean includeBinders
	) {
		if (server == null || index == null) {
			return;
		}
		ServerPlayer a = server.getPlayerList().getPlayer(session.playerA());
		ServerPlayer b = server.getPlayerList().getPlayer(session.playerB());
		if (a == null || b == null) {
			cancelActiveSession(server, session, "Trade cancelled: partner left.", true);
			return;
		}
		List<BinderCard> cardsA = describeOffer(index, session.offerOf(a.getUUID()));
		List<BinderCard> cardsB = describeOffer(index, session.offerOf(b.getUUID()));
		int countdown = session.countdownSeconds();

		List<BinderCard> binderA = includeBinders ? buildBinderCards(server, index, a.getUUID()) : List.of();
		List<BinderCard> binderB = includeBinders ? buildBinderCards(server, index, b.getUUID()) : List.of();

		ServerPlayNetworking.send(a, new TradeSyncS2CPayload(
			a.getScoreboardName(),
			b.getScoreboardName(),
			cardsA,
			cardsB,
			session.isLocked(a.getUUID()),
			session.isLocked(b.getUUID()),
			countdown,
			statusA,
			false,
			includeBinders,
			binderA
		));
		ServerPlayNetworking.send(b, new TradeSyncS2CPayload(
			b.getScoreboardName(),
			a.getScoreboardName(),
			cardsB,
			cardsA,
			session.isLocked(b.getUUID()),
			session.isLocked(a.getUUID()),
			countdown,
			statusB,
			false,
			includeBinders,
			binderB
		));
	}

	private static List<BinderCard> describeOffer(OracleIndex index, Map<CardKey, Integer> offer) {
		List<BinderCard> out = new ArrayList<>(offer.size());
		for (Map.Entry<CardKey, Integer> e : offer.entrySet()) {
			if (e.getValue() > 0) {
				out.add(BinderServer.describe(index, e.getKey(), e.getValue()));
			}
		}
		return out;
	}

	private static List<BinderCard> buildBinderCards(MinecraftServer server, OracleIndex index, UUID playerId) {
		PlayerLibrary library = LibraryWorldData.get(server).get(playerId);
		if (library == null) {
			return List.of();
		}
		if (BinderServer.repairLibrary(library, index)) {
			LibraryWorldData.get(server).setDirty();
		}
		List<BinderCard> cards = new ArrayList<>();
		for (Map.Entry<CardKey, Integer> entry : library.cards().entrySet()) {
			if (entry.getValue() <= 0) {
				continue;
			}
			if (cards.size() >= BinderSyncS2CPayload.MAX_CARDS) {
				break;
			}
			cards.add(BinderServer.describe(index, entry.getKey(), entry.getValue()));
		}
		return cards;
	}

	private static void purgeExpiredAsks(MinecraftServer server, long now) {
		if (OUTGOING_ASKS.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, PendingAsk>> it = OUTGOING_ASKS.entrySet().iterator();
		while (it.hasNext()) {
			PendingAsk ask = it.next().getValue();
			if (now >= ask.expiresAtMs()) {
				it.remove();
				startCooldown(ask.asker(), now, configuredCooldownSeconds());
				if (server != null) {
					ServerPlayer asker = server.getPlayerList().getPlayer(ask.asker());
					if (asker != null) {
						asker.sendSystemMessage(Component.literal("Trade request to " + ask.targetName() + " expired."));
					}
					ServerPlayer target = server.getPlayerList().getPlayer(ask.target());
					if (target != null) {
						target.sendSystemMessage(Component.literal("Trade request from " + ask.askerName() + " expired."));
					}
				}
			}
		}
	}

	private static PendingAsk findIncomingAsk(UUID targetId) {
		for (PendingAsk ask : OUTGOING_ASKS.values()) {
			if (ask.target().equals(targetId)) {
				return ask;
			}
		}
		return null;
	}

	private static void cancelAnyOtherIncomingFor(UUID accepterId, UUID acceptedAskerId) {
		Iterator<Map.Entry<UUID, PendingAsk>> it = OUTGOING_ASKS.entrySet().iterator();
		while (it.hasNext()) {
			PendingAsk ask = it.next().getValue();
			if (ask.target().equals(accepterId) && !ask.asker().equals(acceptedAskerId)) {
				it.remove();
			}
		}
	}
}
