package com.neugoga.spellblock.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.catalog.ScryfallClient;
import com.neugoga.spellblock.datapack.CardRestrictions;
import com.neugoga.spellblock.datapack.EconomyRules;
import com.neugoga.spellblock.datapack.ModRules;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.dustery.DusteryBlock;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.DustValues;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.LibraryWorldData;
import com.neugoga.spellblock.library.PlayerLibrary;
import com.neugoga.spellblock.library.PrintingOption;
import com.neugoga.spellblock.network.table.TableState;
import com.neugoga.spellblock.pack.Printing;
import com.neugoga.spellblock.pack.SetCatalog;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-side Dustery: single-user block lock, disenchant single/stack/non-favourites,
 * full oracle catalog search, unique-printing lookup, and crafting.
 */
public final class DusteryServer {
	private static final int MAX_SEARCH_RESULTS = 120;
	private static final int MAX_PRINTING_PAGES = 6;
	private static final int MAX_PRINTINGS = 1000;
	private static final int MAX_CACHED_ORACLES = 512;

	private static final Set<String> NON_PLAYABLE_LAYOUTS = Set.of(
		"art_series", "token", "double_faced_token", "emblem", "planar", "scheme", "vanguard"
	);

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(15))
		.build();

	private static final ExecutorService PRINTINGS_EXEC = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "spellblock-dustery-printings");
		t.setDaemon(true);
		return t;
	});

	private static final Map<BlockRef, UUID> BLOCK_USERS = new HashMap<>();
	private static final Map<UUID, BlockRef> USER_BLOCKS = new HashMap<>();

	private static final Map<String, List<PrintingOption>> PRINTINGS_CACHE = Collections.synchronizedMap(
		new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, List<PrintingOption>> eldest) {
				return size() > MAX_CACHED_ORACLES;
			}
		}
	);
	private static final ConcurrentHashMap<String, PrintingOption> KNOWN_PRINTINGS = new ConcurrentHashMap<>();
	private static final Map<String, Set<UUID>> IN_FLIGHT_PRINTINGS = new HashMap<>();

	private DusteryServer() {
	}

	private record BlockRef(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private static boolean isDustAllowed(ModRules rules, EconomyRules economy) {
		return rules.dustery() && rules.dust() && economy.dust().enabled();
	}

	private static boolean isCraftAllowed(ModRules rules, EconomyRules economy) {
		return rules.dustery() && rules.craft() && economy.craft().enabled();
	}

	public static void open(ServerPlayer player, BlockPos pos) {
		ModRules rules = SpellBlockDataPacks.rules();
		EconomyRules economy = SpellBlockDataPacks.economy();
		if (!rules.dustery() || (!isDustAllowed(rules, economy) && !isCraftAllowed(rules, economy))) {
			player.displayClientMessage(Component.literal("Dustery is disabled by server rules."), true);
			return;
		}
		if (!(player.level() instanceof ServerLevel level) || pos == null || !level.isLoaded(pos)) {
			return;
		}
		if (player.distanceToSqr(Vec3.atCenterOf(pos)) > TableState.REACH * TableState.REACH) {
			return;
		}
		if (!(level.getBlockState(pos).getBlock() instanceof DusteryBlock)) {
			return;
		}
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		BlockRef ref = new BlockRef(level.dimension(), pos.immutable());
		UUID holder = BLOCK_USERS.get(ref);
		if (holder != null && !holder.equals(player.getUUID())) {
			ServerPlayer other = server.getPlayerList().getPlayer(holder);
			boolean active = other != null
				&& other.isAlive()
				&& other.level().dimension().equals(ref.dimension())
				&& other.distanceToSqr(Vec3.atCenterOf(ref.pos())) <= TableState.LOOK_REACH * TableState.LOOK_REACH;
			if (active) {
				player.displayClientMessage(Component.literal("Someone is using this."), true);
				return;
			}
			releasePlayer(holder);
		}
		releasePlayer(player.getUUID());
		BLOCK_USERS.put(ref, player.getUUID());
		USER_BLOCKS.put(player.getUUID(), ref);
		sync(player, "");
	}

	public static void refresh(ServerPlayer player) {
		if (player == null) {
			return;
		}
		if (hasActiveSession(player)) {
			sync(player, "");
		}
	}

	public static void onDisconnect(MinecraftServer server, ServerPlayer player) {
		if (player != null) {
			releasePlayer(player.getUUID());
		}
	}

	public static void onBlockRemoved(Level level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		BlockRef ref = new BlockRef(level.dimension(), pos.immutable());
		UUID holder = BLOCK_USERS.remove(ref);
		if (holder == null) {
			return;
		}
		USER_BLOCKS.remove(holder);
		MinecraftServer server = level.getServer();
		if (server != null) {
			ServerPlayer player = server.getPlayerList().getPlayer(holder);
			if (player != null) {
				ServerPlayNetworking.send(player, DusterySyncS2CPayload.ofClosed());
			}
		}
	}

	public static void sweep(MinecraftServer server) {
		if (server == null || server.getTickCount() % TableState.SWEEP_TICKS != 0 || USER_BLOCKS.isEmpty()) {
			return;
		}
		for (var entry : List.copyOf(USER_BLOCKS.entrySet())) {
			UUID id = entry.getKey();
			BlockRef ref = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			boolean gone = player == null
				|| !player.isAlive()
				|| !player.level().dimension().equals(ref.dimension())
				|| !(player.level().getBlockState(ref.pos()).getBlock() instanceof DusteryBlock)
				|| player.distanceToSqr(Vec3.atCenterOf(ref.pos())) > TableState.LOOK_REACH * TableState.LOOK_REACH;
			if (gone) {
				releasePlayer(id);
				if (player != null) {
					ServerPlayNetworking.send(player, DusterySyncS2CPayload.ofClosed());
				}
			}
		}
	}

	public static void onDataPackReload(MinecraftServer server) {
		if (server == null || USER_BLOCKS.isEmpty()) {
			return;
		}
		ModRules rules = SpellBlockDataPacks.rules();
		EconomyRules economy = SpellBlockDataPacks.economy();
		boolean closedAll = !rules.dustery() || (!isDustAllowed(rules, economy) && !isCraftAllowed(rules, economy));
		for (UUID id : List.copyOf(USER_BLOCKS.keySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (closedAll) {
				releasePlayer(id);
				if (player != null) {
					ServerPlayNetworking.send(player, DusterySyncS2CPayload.ofClosed());
					player.displayClientMessage(Component.literal("Dustery disabled by server rules."), true);
				}
			} else if (player != null) {
				sync(player, "Server rules reloaded.");
			}
		}
	}

	public static void clearAll() {
		BLOCK_USERS.clear();
		USER_BLOCKS.clear();
		synchronized (IN_FLIGHT_PRINTINGS) {
			IN_FLIGHT_PRINTINGS.clear();
		}
	}

	private static void releasePlayer(UUID playerId) {
		if (playerId == null) {
			return;
		}
		BlockRef ref = USER_BLOCKS.remove(playerId);
		if (ref != null) {
			BLOCK_USERS.remove(ref, playerId);
		}
	}

	private static boolean hasActiveSession(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return false;
		}
		BlockRef ref = USER_BLOCKS.get(player.getUUID());
		if (ref == null) {
			return false;
		}
		if (!level.dimension().equals(ref.dimension())
			|| !level.isLoaded(ref.pos())
			|| !(level.getBlockState(ref.pos()).getBlock() instanceof DusteryBlock)
			|| player.distanceToSqr(Vec3.atCenterOf(ref.pos())) > TableState.LOOK_REACH * TableState.LOOK_REACH) {
			releasePlayer(player.getUUID());
			return false;
		}
		return true;
	}

	public static void act(ServerPlayer player, DusteryActionC2SPayload payload) {
		if (payload == null || payload.action() == null) {
			return;
		}
		String action = payload.action().trim().toLowerCase(Locale.ROOT);
		if ("close".equals(action)) {
			releasePlayer(player.getUUID());
			return;
		}
		if (!hasActiveSession(player)) {
			ServerPlayNetworking.send(player, DusterySyncS2CPayload.ofClosed());
			return;
		}
		switch (action) {
			case "dust" -> dust(player, payload);
			case "dust_all" -> dustAll(player);
			case "craft" -> craft(player, payload);
			default -> sync(player, "Unknown action.");
		}
	}

	private static void dust(ServerPlayer player, DusteryActionC2SPayload payload) {
		ModRules rules = SpellBlockDataPacks.rules();
		EconomyRules economy = SpellBlockDataPacks.economy();
		if (!isDustAllowed(rules, economy)) {
			sync(player, "Disenchanting is disabled by server rules.");
			return;
		}
		OracleIndex index = OracleService.index();
		if (index == null) {
			sync(player, "Card index not ready.");
			return;
		}
		CardKey key = keyOf(payload);
		if (key.oracleId().isEmpty()) {
			sync(player, "Pick a card first.");
			return;
		}
		int want = Math.max(1, payload.amount());
		PlayerLibrary library = LibraryWorldData.get(player.getServer()).get(player.getUUID());
		if (library == null) {
			sync(player, "No collection.");
			return;
		}
		int have = library.count(key);
		if (have <= 0) {
			sync(player, "You do not own that card.");
			return;
		}
		int take = Math.min(want, have);
		BinderCard described = describe(index, key, have);
		int per = DustValues.disenchant(described, economy);
		int gained = (int) Math.min(Integer.MAX_VALUE, (long) per * take);
		library.add(key, -take);
		if (library.count(key) <= 0) {
			library.setFavourite(key, false);
		}
		library.addDust(gained);
		LibraryWorldData.get(player.getServer()).setDirty();
		sync(player, "Disenchanted " + take + " × " + described.name() + " for +" + gained + " dust.");
	}

	private static void dustAll(ServerPlayer player) {
		ModRules rules = SpellBlockDataPacks.rules();
		EconomyRules economy = SpellBlockDataPacks.economy();
		if (!isDustAllowed(rules, economy) || !economy.dust().allowDustAll()) {
			sync(player, "Mass disenchanting is disabled by server rules.");
			return;
		}
		OracleIndex index = OracleService.index();
		if (index == null) {
			sync(player, "Card index not ready.");
			return;
		}
		PlayerLibrary library = LibraryWorldData.get(player.getServer()).get(player.getUUID());
		if (library == null || library.cards().isEmpty()) {
			sync(player, "No cards to disenchant.");
			return;
		}
		int dustedCopies = 0;
		int skippedFavCopies = 0;
		long gained = 0;
		for (Map.Entry<CardKey, Integer> entry : List.copyOf(library.cards().entrySet())) {
			int count = entry.getValue();
			if (count <= 0) {
				continue;
			}
			CardKey key = entry.getKey();
			if (library.isFavourite(key)) {
				skippedFavCopies += count;
				continue;
			}
			BinderCard described = describe(index, key, count);
			int per = DustValues.disenchant(described, economy);
			gained += (long) per * count;
			dustedCopies += count;
			library.add(key, -count);
		}
		if (dustedCopies <= 0) {
			String msg = skippedFavCopies > 0
				? "Kept " + skippedFavCopies + " favourited cards."
				: "No cards to disenchant.";
			sync(player, msg);
			return;
		}
		int added = (int) Math.min(Integer.MAX_VALUE, gained);
		library.addDust(added);
		LibraryWorldData.get(player.getServer()).setDirty();
		String summary = "Disenchanted " + dustedCopies + " cards for +" + added + " dust";
		if (skippedFavCopies > 0) {
			summary += " (" + skippedFavCopies + " fav kept).";
		} else {
			summary += ".";
		}
		sync(player, summary);
	}

	private static void craft(ServerPlayer player, DusteryActionC2SPayload payload) {
		ModRules rules = SpellBlockDataPacks.rules();
		EconomyRules economy = SpellBlockDataPacks.economy();
		CardRestrictions cards = SpellBlockDataPacks.cards();
		if (!isCraftAllowed(rules, economy)) {
			sync(player, "Crafting is disabled by server rules.");
			return;
		}
		OracleIndex index = OracleService.index();
		if (index == null) {
			sync(player, "Card index not ready.");
			return;
		}
		String oracleId = payload.oracleId() == null ? "" : payload.oracleId().trim();
		if (oracleId.isEmpty()) {
			sync(player, "Pick a card first.");
			return;
		}
		OracleCard oracle = index.findByOracleId(oracleId);
		if (oracle == null) {
			sync(player, "Unknown card.");
			return;
		}
		String printingId = payload.printingId() == null || payload.printingId().isEmpty()
			? oracle.printingId()
			: payload.printingId().trim();
		if (printingId.isEmpty()) {
			sync(player, "No printing available for " + oracle.name() + ".");
			return;
		}

		PlayerLibrary library = LibraryWorldData.get(player.getServer()).getOrCreate(player.getUUID());
		Finish finish = Finish.fromName(payload.finish());
		Printing setPrinting = SetCatalog.find(printingId);
		PrintingOption knownOpt = KNOWN_PRINTINGS.get(printingId);

		boolean validPrinting = printingId.equals(oracle.printingId())
			|| (setPrinting != null && oracleId.equals(setPrinting.oracleId()))
			|| (knownOpt != null && oracleId.equals(knownOpt.oracleId()))
			|| ownsPrinting(library, oracleId, printingId);
		if (!validPrinting) {
			sync(player, "Unknown printing for " + oracle.name() + ".");
			return;
		}

		String rarity = setPrinting != null ? setPrinting.rarity()
			: (knownOpt != null ? knownOpt.rarity() : oracle.rarity());
		String setCode = setPrinting != null ? setPrinting.setCode()
			: (knownOpt != null ? knownOpt.setCode() : oracle.setCode());
		String treatment = setPrinting != null ? setPrinting.treatment()
			: (knownOpt != null ? knownOpt.treatment() : "normal");

		if (cards.isCraftBlocked(oracle.oracleId(), oracle.name(), setCode, rarity, oracle.typeLine())
			|| !BinderServer.canAddCard(oracle.oracleId(), oracle.name(), setCode, rarity, oracle.typeLine())) {
			sync(player, "Crafting \"" + oracle.name() + "\" is disabled by server rules.");
			return;
		}
		if (economy.craft().ownedOnly() && !ownsOracle(library, oracleId)) {
			sync(player, "You can only craft cards you already own.");
			return;
		}
		if (!economy.craft().isFinishAllowed(finish)) {
			sync(player, finish.label() + " crafting is disabled by server rules.");
			return;
		}
		if (!economy.craft().isTreatmentAllowed(treatment)) {
			sync(player, "That card treatment cannot be crafted under server rules.");
			return;
		}

		int amount = Math.max(1, Math.min(economy.craft().maxBatch(), payload.amount()));
		CardKey key = new CardKey(oracleId, printingId, finish);
		if (!library.cards().containsKey(key) && library.uniqueCount() >= BinderSyncS2CPayload.MAX_CARDS) {
			sync(player, "Binder is full (max " + BinderSyncS2CPayload.MAX_CARDS + " unique cards).");
			return;
		}
		int per = DustValues.craft(oracleId, oracle.name(), printingId, setCode, rarity, treatment, finish.name(), economy);
		long totalCostLong = (long) per * amount;
		if (totalCostLong > Integer.MAX_VALUE || library.dust() < totalCostLong) {
			sync(player, "Need " + totalCostLong + " dust (have " + library.dust() + ").");
			return;
		}
		int totalCost = (int) totalCostLong;
		library.addDust(-totalCost);
		library.add(key, amount);
		LibraryWorldData worldData = LibraryWorldData.get(player.getServer());
		if (setPrinting == null) {
			worldData.rememberPrinting(printingId, setCode, rarity, treatment);
		}
		worldData.setDirty();
		sync(player, "Crafted " + amount + " × " + oracle.name() + " (−" + totalCost + " dust).");
	}

	private static boolean ownsOracle(PlayerLibrary library, String oracleId) {
		if (library == null || oracleId == null || oracleId.isEmpty()) {
			return false;
		}
		for (Map.Entry<CardKey, Integer> e : library.cards().entrySet()) {
			if (e.getValue() > 0 && oracleId.equals(e.getKey().oracleId())) {
				return true;
			}
		}
		return false;
	}

	private static boolean ownsPrinting(PlayerLibrary library, String oracleId, String printingId) {
		for (CardKey key : library.cards().keySet()) {
			if (oracleId.equals(key.oracleId()) && printingId.equals(key.printingId())) {
				return true;
			}
		}
		return false;
	}

	public static void search(ServerPlayer player, DusterySearchC2SPayload payload) {
		if (!hasActiveSession(player)) {
			return;
		}
		ModRules rules = SpellBlockDataPacks.rules();
		EconomyRules economy = SpellBlockDataPacks.economy();
		CardRestrictions restrictions = SpellBlockDataPacks.cards();
		String query = payload == null || payload.query() == null ? "" : payload.query();
		if (!isCraftAllowed(rules, economy)) {
			ServerPlayNetworking.send(player, new DusterySearchS2CPayload(query, List.of(), 0, "Crafting is disabled by server rules."));
			return;
		}
		if (economy.craft().ownedOnly()) {
			ServerPlayNetworking.send(player, new DusterySearchS2CPayload(query, List.of(), 0, "Only owned cards can be crafted."));
			return;
		}
		String colors = payload == null || payload.colors() == null ? "" : payload.colors();
		String rarities = payload == null || payload.rarities() == null ? "" : payload.rarities();
		OracleIndex index = OracleService.index();
		if (index == null) {
			ServerPlayNetworking.send(player, new DusterySearchS2CPayload(query, List.of(), 0, "Card index still building."));
			return;
		}
		PlayerLibrary library = LibraryWorldData.get(player.getServer()).get(player.getUUID());
		Map<String, Integer> ownedByOracle = new HashMap<>();
		if (library != null) {
			for (Map.Entry<CardKey, Integer> entry : library.cards().entrySet()) {
				if (entry.getValue() > 0) {
					ownedByOracle.merge(entry.getKey().oracleId(), entry.getValue(), Integer::sum);
				}
			}
		}
		OracleIndex.SearchResult result = index.search(query, colors, rarities, MAX_SEARCH_RESULTS);
		List<BinderCard> cards = new ArrayList<>(result.matches().size());
		int skipped = 0;
		for (OracleCard card : result.matches()) {
			if (restrictions.isDisabled(card)) {
				skipped++;
				continue;
			}
			int owned = ownedByOracle.getOrDefault(card.oracleId(), 0);
			cards.add(new BinderCard(
				card.oracleId(),
				card.printingId(),
				Finish.NONFOIL.name(),
				owned,
				card.name(),
				card.manaCost(),
				card.manaValue(),
				card.typeLine(),
				card.colorIdentity(),
				card.rarity(),
				card.setCode(),
				"normal"
			));
		}
		int shownTotal = Math.max(cards.size(), result.totalMatches() - skipped);
		ServerPlayNetworking.send(player, new DusterySearchS2CPayload(query, cards, shownTotal, ""));
	}

	public static void requestPrintings(ServerPlayer player, DusteryPrintingsC2SPayload payload) {
		if (!hasActiveSession(player) || payload == null || payload.oracleId() == null) {
			return;
		}
		String oracleId = payload.oracleId().trim();
		if (oracleId.isEmpty()) {
			return;
		}
		OracleIndex index = OracleService.index();
		if (index == null) {
			ServerPlayNetworking.send(player, new DusteryPrintingsS2CPayload(oracleId, List.of(), false, "Card index not ready."));
			return;
		}
		OracleCard oracle = index.findByOracleId(oracleId);
		if (oracle == null) {
			ServerPlayNetworking.send(player, new DusteryPrintingsS2CPayload(oracleId, List.of(), false, "Unknown card."));
			return;
		}
		List<PrintingOption> cached = PRINTINGS_CACHE.get(oracleId);
		if (cached != null && !cached.isEmpty()) {
			ServerPlayNetworking.send(player, new DusteryPrintingsS2CPayload(oracleId, cached, false, ""));
			return;
		}

		List<PrintingOption> seed = seedPrintings(oracle);
		for (PrintingOption opt : seed) {
			KNOWN_PRINTINGS.putIfAbsent(opt.printingId(), opt);
		}
		ServerPlayNetworking.send(player, new DusteryPrintingsS2CPayload(oracleId, seed, true, ""));

		boolean shouldFetch;
		synchronized (IN_FLIGHT_PRINTINGS) {
			Set<UUID> waiters = IN_FLIGHT_PRINTINGS.computeIfAbsent(oracleId, k -> new LinkedHashSet<>());
			shouldFetch = waiters.isEmpty();
			waiters.add(player.getUUID());
		}
		if (!shouldFetch) {
			return;
		}
		MinecraftServer server = player.getServer();
		PRINTINGS_EXEC.submit(() -> {
			List<PrintingOption> fetched = null;
			String error = "";
			try {
				fetched = fetchScryfallPrintings(oracle);
				if (fetched.isEmpty()) {
					fetched = seed;
				} else {
					PRINTINGS_CACHE.put(oracleId, fetched);
					for (PrintingOption opt : fetched) {
						KNOWN_PRINTINGS.put(opt.printingId(), opt);
					}
				}
			} catch (Exception e) {
				SpellBlock.LOGGER.warn("Dustery printings lookup failed for {}: {}", oracleId, e.toString());
				fetched = seed;
				error = "Offline / Scryfall unavailable — showing local printings.";
			}
			List<PrintingOption> resultList = fetched;
			String statusNote = error;
			if (server != null) {
				server.execute(() -> {
					Set<UUID> waiters;
					synchronized (IN_FLIGHT_PRINTINGS) {
						waiters = IN_FLIGHT_PRINTINGS.remove(oracleId);
					}
					if (waiters == null) {
						return;
					}
					for (UUID uuid : waiters) {
						ServerPlayer waiter = server.getPlayerList().getPlayer(uuid);
						if (waiter != null && hasActiveSession(waiter)) {
							ServerPlayNetworking.send(waiter, new DusteryPrintingsS2CPayload(oracleId, resultList, false, statusNote));
						}
					}
				});
			} else {
				synchronized (IN_FLIGHT_PRINTINGS) {
					IN_FLIGHT_PRINTINGS.remove(oracleId);
				}
			}
		});
	}

	private static List<PrintingOption> seedPrintings(OracleCard oracle) {
		LinkedHashMap<String, PrintingOption> byId = new LinkedHashMap<>();
		List<String> defaultFinishes = List.of(Finish.NONFOIL.name(), Finish.FOIL.name());
		for (Printing p : SetCatalog.findByOracleId(oracle.oracleId())) {
			byId.putIfAbsent(p.printingId(), new PrintingOption(
				p.oracleId(),
				p.printingId(),
				p.name(),
				p.manaCost(),
				p.manaValue(),
				p.typeLine(),
				p.colorIdentity(),
				p.rarity(),
				p.setCode(),
				"",
				p.treatment(),
				defaultFinishes
			));
		}
		if (!oracle.printingId().isEmpty()) {
			byId.putIfAbsent(oracle.printingId(), new PrintingOption(
				oracle.oracleId(),
				oracle.printingId(),
				oracle.name(),
				oracle.manaCost(),
				oracle.manaValue(),
				oracle.typeLine(),
				oracle.colorIdentity(),
				oracle.rarity(),
				oracle.setCode(),
				"",
				"normal",
				defaultFinishes
			));
		}
		return List.copyOf(byId.values());
	}

	private static List<PrintingOption> fetchScryfallPrintings(OracleCard oracle) throws Exception {
		String query = "oracleid:" + oracle.oracleId();
		String url = "https://api.scryfall.com/cards/search?q="
			+ URLEncoder.encode(query, StandardCharsets.UTF_8)
			+ "&unique=prints&order=released&dir=desc";
		LinkedHashMap<String, PrintingOption> byId = new LinkedHashMap<>();
		for (int page = 0; page < MAX_PRINTING_PAGES && url != null && !url.isEmpty() && byId.size() < MAX_PRINTINGS; page++) {
			HttpResponse<String> response = ScryfallClient.getString(HTTP, url);
			if (response.statusCode() == 404) {
				break;
			}
			if (response.statusCode() != 200) {
				throw new IllegalStateException("HTTP " + response.statusCode());
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray data = root.getAsJsonArray("data");
			if (data != null) {
				for (JsonElement element : data) {
					if (!element.isJsonObject()) {
						continue;
					}
					PrintingOption opt = parseScryfallPrinting(element.getAsJsonObject(), oracle);
					if (opt != null) {
						byId.putIfAbsent(opt.printingId(), opt);
						if (byId.size() >= MAX_PRINTINGS) {
							break;
						}
					}
				}
			}
			boolean more = root.has("has_more") && root.get("has_more").getAsBoolean();
			url = more && root.has("next_page") ? root.get("next_page").getAsString() : null;
		}
		return List.copyOf(byId.values());
	}

	private static PrintingOption parseScryfallPrinting(JsonObject card, OracleCard oracle) {
		if (bool(card, "oversized") == Boolean.TRUE) {
			return null;
		}
		String layout = text(card, "layout");
		if (NON_PLAYABLE_LAYOUTS.contains(layout)) {
			return null;
		}
		if (!paper(card)) {
			return null;
		}
		String printingId = text(card, "id");
		if (printingId.isEmpty()) {
			return null;
		}
		String oracleId = text(card, "oracle_id");
		if (oracleId.isEmpty()) {
			oracleId = oracle.oracleId();
		}
		String name = text(card, "name");
		if (name.isEmpty()) {
			name = oracle.name();
		}
		String manaCost = text(card, "mana_cost");
		String typeLine = text(card, "type_line");
		if ((manaCost.isEmpty() || typeLine.isEmpty()) && card.has("card_faces") && card.get("card_faces").isJsonArray()) {
			JsonArray faces = card.getAsJsonArray("card_faces");
			if (!faces.isEmpty() && faces.get(0).isJsonObject()) {
				JsonObject face = faces.get(0).getAsJsonObject();
				if (manaCost.isEmpty()) {
					manaCost = text(face, "mana_cost");
				}
				if (typeLine.isEmpty()) {
					typeLine = text(face, "type_line");
				}
			}
		}
		if (manaCost.isEmpty()) {
			manaCost = oracle.manaCost();
		}
		if (typeLine.isEmpty()) {
			typeLine = oracle.typeLine();
		}
		double cmc = card.has("cmc") && card.get("cmc").isJsonPrimitive() ? card.get("cmc").getAsDouble() : oracle.manaValue();
		String colors = colors(card, "color_identity");
		if (colors.isEmpty()) {
			colors = oracle.colorIdentity();
		}
		String rarity = text(card, "rarity").toLowerCase(Locale.ROOT);
		if (rarity.isEmpty()) {
			rarity = oracle.rarity();
		}
		String setCode = text(card, "set");
		String collectorNumber = text(card, "collector_number");
		String treatment = treatment(card);
		List<String> finishes = parseFinishes(card);
		return new PrintingOption(
			oracleId,
			printingId,
			name,
			manaCost,
			cmc,
			typeLine,
			colors,
			rarity,
			setCode,
			collectorNumber,
			treatment,
			finishes
		);
	}

	private static List<String> parseFinishes(JsonObject card) {
		Set<String> out = new LinkedHashSet<>();
		if (card.has("finishes") && card.get("finishes").isJsonArray()) {
			for (JsonElement el : card.getAsJsonArray("finishes")) {
				if (!el.isJsonPrimitive()) {
					continue;
				}
				switch (el.getAsString().toLowerCase(Locale.ROOT)) {
					case "nonfoil" -> out.add(Finish.NONFOIL.name());
					case "foil", "glossy" -> out.add(Finish.FOIL.name());
					case "etched" -> out.add(Finish.ETCHED.name());
					default -> {
					}
				}
			}
		}
		if (out.isEmpty()) {
			out.add(Finish.NONFOIL.name());
		}
		return List.copyOf(out);
	}

	private static String treatment(JsonObject card) {
		if ("spg".equals(text(card, "set"))) {
			return "special_guest";
		}
		String frame = text(card, "frame");
		if ("1997".equals(frame) || "1993".equals(frame)) {
			return "retro";
		}
		if ("borderless".equals(text(card, "border_color"))
			|| bool(card, "full_art") == Boolean.TRUE
			|| effect(card, "inverted")
			|| effect(card, "showcase")
			|| effect(card, "extendedart")) {
			return "borderless";
		}
		return "normal";
	}

	private static boolean effect(JsonObject card, String name) {
		if (!card.has("frame_effects") || !card.get("frame_effects").isJsonArray()) {
			return false;
		}
		for (JsonElement effect : card.getAsJsonArray("frame_effects")) {
			if (effect.isJsonPrimitive() && name.equals(effect.getAsString())) {
				return true;
			}
		}
		return false;
	}

	private static boolean paper(JsonObject card) {
		if (!card.has("games") || !card.get("games").isJsonArray()) {
			return true;
		}
		for (JsonElement game : card.getAsJsonArray("games")) {
			if (game.isJsonPrimitive() && "paper".equals(game.getAsString())) {
				return true;
			}
		}
		return false;
	}

	private static String colors(JsonObject card, String name) {
		if (!card.has(name) || !card.get(name).isJsonArray()) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (JsonElement color : card.getAsJsonArray(name)) {
			if (color.isJsonPrimitive()) {
				out.append(color.getAsString());
			}
		}
		return out.toString();
	}

	private static Boolean bool(JsonObject card, String name) {
		if (!card.has(name) || !card.get(name).isJsonPrimitive()) {
			return null;
		}
		return card.get(name).getAsBoolean();
	}

	private static String text(JsonObject card, String name) {
		JsonElement value = card.get(name);
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
			return "";
		}
		return value.getAsString();
	}

	private static BinderCard describe(OracleIndex index, CardKey key, int count) {
		Printing setPrinting = SetCatalog.find(key.printingId());
		if (setPrinting != null) {
			return BinderServer.describe(index, key, count);
		}
		PrintingOption known = KNOWN_PRINTINGS.get(key.printingId());
		if (known != null) {
			return new BinderCard(
				key.oracleId(),
				key.printingId(),
				key.finish().name(),
				count,
				known.name(),
				known.manaCost(),
				known.manaValue(),
				known.typeLine(),
				known.colorIdentity(),
				known.rarity(),
				known.setCode(),
				known.treatment()
			);
		}
		return BinderServer.describe(index, key, count);
	}

	private static CardKey keyOf(DusteryActionC2SPayload payload) {
		return new CardKey(
			payload.oracleId() == null ? "" : payload.oracleId(),
			payload.printingId() == null ? "" : payload.printingId(),
			Finish.fromName(payload.finish())
		);
	}

	private static void sync(ServerPlayer player, String status) {
		OracleIndex index = OracleService.index();
		PlayerLibrary library = LibraryWorldData.get(player.getServer()).get(player.getUUID());
		List<BinderCard> cards = new ArrayList<>();
		int dust = library == null ? 0 : library.dust();
		int indexSize = index == null ? 0 : index.size();
		if (library != null && index != null) {
			if (BinderServer.repairLibrary(library, index)) {
				LibraryWorldData.get(player.getServer()).setDirty();
			}
			for (Map.Entry<CardKey, Integer> entry : library.cards().entrySet()) {
				if (entry.getValue() <= 0) {
					continue;
				}
				if (cards.size() >= DusterySyncS2CPayload.MAX_CARDS) {
					break;
				}
				cards.add(describe(index, entry.getKey(), entry.getValue()));
			}
		} else if (index == null) {
			status = status.isEmpty() ? "Card index not ready." : status;
		}
		ModRules rules = SpellBlockDataPacks.rules();
		EconomyRules economy = SpellBlockDataPacks.economy();
		CardRestrictions restrictions = SpellBlockDataPacks.cards();
		ServerPlayNetworking.send(
			player,
			new DusterySyncS2CPayload(
				cards,
				dust,
				status,
				BinderServer.encodeFavourites(library),
				indexSize,
				false,
				isDustAllowed(rules, economy),
				isCraftAllowed(rules, economy),
				economy,
				restrictions
			)
		);
	}
}
