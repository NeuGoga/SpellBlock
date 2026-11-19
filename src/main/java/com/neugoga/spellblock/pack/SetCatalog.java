package com.neugoga.spellblock.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.catalog.ScryfallClient;
import com.neugoga.spellblock.table.TableNames;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads the printings a pack can roll.
 * The oracle file cannot build a pack: it keeps a single printing per card.
 * Drop rules and treatment names match scripts/pack_pools.py and docs/pack-data.md.
 */
public final class SetCatalog {
	private static final int MIN_CARDS = 40; // Old sets like ARN are under 80 printings after filters.
	private static final Set<String> SKIP_PROMOS = Set.of(
		"bundle", "textured", "ripplefoil", "serialized", "prerelease", "promopack",
		"stamped", "datestamped", "release", "league", "tourney", "instore", "event", "playtest"
	);
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(20))
		.build();

	private static final ConcurrentHashMap<String, SetPool> READY = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, String> DETAIL = new ConcurrentHashMap<>();
	private static final Object LOCK = new Object();
	private static boolean running;

	private SetCatalog() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ensure());
	}

	public static SetPool pool(String setCode) {
		return READY.get(setCode);
	}

	public static Printing find(String printingId) {
		if (printingId == null || printingId.isEmpty()) {
			return null;
		}
		for (SetPool pool : READY.values()) {
			Printing card = pool.find(printingId);
			if (card != null) {
				return card;
			}
		}
		return null;
	}

	public static List<Printing> findByOracleId(String oracleId) {
		if (oracleId == null || oracleId.isEmpty()) {
			return List.of();
		}
		List<Printing> out = new ArrayList<>();
		for (SetPool pool : READY.values()) {
			out.addAll(pool.findByOracleId(oracleId));
		}
		return out;
	}

	public static String detail(String setCode) {
		return DETAIL.getOrDefault(setCode, "not started");
	}

	public static void ensure() {
		Map<String, List<String>> jobs = new LinkedHashMap<>();
		for (PackDefinition pack : Packs.all()) {
			List<String> queries = jobs.computeIfAbsent(pack.setCode(), key -> new ArrayList<>());
			for (String query : pack.queries()) {
				if (!queries.contains(query)) {
					queries.add(query);
				}
			}
		}
		if (jobs.isEmpty()) {
			return;
		}
		synchronized (LOCK) {
			if (running) {
				return;
			}
			running = true;
		}
		Thread thread = new Thread(() -> run(jobs), "spellblock-sets");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run(Map<String, List<String>> jobs) {
		try {
			for (Map.Entry<String, List<String>> job : jobs.entrySet()) {
				if (READY.containsKey(job.getKey())) {
					continue;
				}
				try {
					load(job.getKey(), List.copyOf(job.getValue()));
				} catch (Exception e) {
					String message = e.getMessage();
					if (message == null || message.isBlank()) {
						message = e.getClass().getSimpleName();
					}
					DETAIL.put(job.getKey(), "failed: " + message);
					SpellBlock.LOGGER.error("Set list {} failed: {}", job.getKey(), message);
				}
			}
		} finally {
			synchronized (LOCK) {
				running = false;
			}
		}
	}

	private static void load(String set, List<String> queries) throws IOException, InterruptedException {
		Path cache = cachePath(set);
		if (Files.isRegularFile(cache)) {
			DETAIL.put(set, "reading cache");
			try {
				SetPool loaded = SetPool.read(cache);
				if (set.equals(loaded.setCode()) && loaded.size() >= MIN_CARDS && loaded.sameQueries(queries)) {
					publish(loaded, "cached");
					return;
				}
				SpellBlock.LOGGER.info("Set cache {} is stale. Downloading again.", set);
			} catch (Exception e) {
				SpellBlock.LOGGER.warn("Set cache {} could not be read. Downloading again.", set, e);
			}
		}
		DETAIL.put(set, "downloading " + set);
		List<Printing> cards = download(set, queries);
		if (cards.size() < MIN_CARDS) {
			throw new IOException(set + " only contained " + cards.size() + " cards");
		}
		SetPool loaded = SetPool.from(set, cards, queries);
		loaded.write(cache);
		publish(loaded, "downloaded");
	}

	private static void publish(SetPool loaded, String how) {
		READY.put(loaded.setCode(), loaded);
		TableNames.clear();
		DETAIL.put(loaded.setCode(), loaded.size() + " cards, " + how);
		SpellBlock.LOGGER.info(
			"Set {} ready ({} cards, {}, commons {}, uncommons {}, rares {}, mythics {}, basics {}, retro {}, borderless {}, guests {}).",
			loaded.setCode(),
			loaded.size(),
			how,
			loaded.count("common"),
			loaded.count("uncommon"),
			loaded.count("rare"),
			loaded.count("mythic"),
			loaded.count("basic"),
			loaded.countPrefix("retro_"),
			loaded.countPrefix("borderless_"),
			loaded.count("special_guest")
		);
	}

	private static List<Printing> download(String set, List<String> queries) throws IOException, InterruptedException {
		List<Printing> cards = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String query : queries) {
			String url = "https://api.scryfall.com/cards/search?q="
				+ URLEncoder.encode(query, StandardCharsets.UTF_8)
				+ "&unique=prints";
			while (url != null && !url.isEmpty()) {
				HttpResponse<String> response = ScryfallClient.getString(HTTP, url);
				if (response.statusCode() == 404) {
					break;
				}
				if (response.statusCode() != 200) {
					throw new IOException("Scryfall returned " + response.statusCode() + " for " + query);
				}
				JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
				JsonArray data = root.getAsJsonArray("data");
				if (data == null) {
					throw new IOException("Scryfall query had no data: " + query);
				}
				for (JsonElement element : data) {
					Printing card = readCard(element.getAsJsonObject(), set);
					if (card != null && seen.add(card.printingId())) {
						cards.add(card);
					}
				}
				DETAIL.put(set, "reading " + set + " (" + cards.size() + ")");
				boolean more = root.has("has_more") && root.get("has_more").getAsBoolean();
				url = more && root.has("next_page") ? root.get("next_page").getAsString() : null;
			}
		}
		return cards;
	}

	private static Printing readCard(JsonObject card, String fallbackSet) {
		if (bool(card, "oversized") == Boolean.TRUE) {
			return null;
		}
		String layout = text(card, "layout");
		if (layout.equals("token") || layout.equals("art_series") || layout.equals("emblem") || layout.equals("double_faced_token")) {
			return null;
		}
		if (!paper(card) || skippedPromo(card) || effect(card, "etched") || etchedOnly(card)) {
			return null;
		}
		String rarity = text(card, "rarity");
		if (!rarity.equals("common") && !rarity.equals("uncommon") && !rarity.equals("rare") && !rarity.equals("mythic")) {
			return null;
		}
		String treatment = treatment(card);
		if ("normal".equals(treatment) && bool(card, "booster") == Boolean.FALSE) {
			return null;
		}
		String oracleId = text(card, "oracle_id");
		String printingId = text(card, "id");
		String name = text(card, "name");
		if (oracleId.isEmpty() || printingId.isEmpty() || name.isEmpty()) {
			return null;
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
		String cardSet = text(card, "set");
		if (cardSet.isEmpty()) {
			cardSet = fallbackSet;
		}
		return new Printing(
			oracleId,
			printingId,
			name,
			manaCost,
			card.has("cmc") && card.get("cmc").isJsonPrimitive() ? card.get("cmc").getAsDouble() : 0,
			typeLine,
			colors(card, "color_identity"),
			rarity,
			cardSet,
			typeLine.contains("Basic"),
			treatment
		);
	}

	private static String treatment(JsonObject card) {
		if ("spg".equals(text(card, "set"))) {
			return "special_guest";
		}
		if ("1997".equals(text(card, "frame"))) {
			return "retro";
		}
		if ("borderless".equals(text(card, "border_color")) || effect(card, "inverted") || effect(card, "showcase") || effect(card, "extendedart")) {
			return "borderless";
		}
		return "normal";
	}

	private static boolean skippedPromo(JsonObject card) {
		if (!card.has("promo_types") || !card.get("promo_types").isJsonArray()) {
			return false;
		}
		for (JsonElement promo : card.getAsJsonArray("promo_types")) {
			if (promo.isJsonPrimitive() && SKIP_PROMOS.contains(promo.getAsString())) {
				return true;
			}
		}
		return false;
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

	private static boolean etchedOnly(JsonObject card) {
		if (!card.has("finishes") || !card.get("finishes").isJsonArray()) {
			return false;
		}
		JsonArray finishes = card.getAsJsonArray("finishes");
		return finishes.size() == 1 && finishes.get(0).isJsonPrimitive() && "etched".equals(finishes.get(0).getAsString());
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

	private static void checkHost(String url) throws IOException {
		URI uri = URI.create(url);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean scryfall = host.equals("scryfall.com") || host.endsWith(".scryfall.com")
			|| host.equals("scryfall.io") || host.endsWith(".scryfall.io");
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !scryfall) {
			throw new IOException("Refusing unexpected download host: " + host);
		}
	}

	private static Path cachePath(String set) {
		return FabricLoader.getInstance().getConfigDir().resolve("spellblock").resolve("sets").resolve(set + ".json.gz");
	}
}
