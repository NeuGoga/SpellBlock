package com.neugoga.spellblock.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.table.TableNames;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Downloads Scryfall's oracle bulk once, off the server thread, and reloads the slim cache after that.
 * The raw file is large. The cache is not. Nothing here runs on the server thread except scheduling.
 */
public final class OracleService {
	public static final String USER_AGENT = "SpellBlock/1.0 (https://github.com/NeuGoga/SpellBlock; unofficial fan project)";
	private static final String BULK_DATA = "https://api.scryfall.com/bulk-data";
	private static final int MIN_CARDS = 1000;

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(20))
		.build();

	private static final Object LOCK = new Object();
	private static boolean running;
	private static volatile State state = State.IDLE;
	private static volatile String detail = "not started";
	private static volatile String readyDetail = "";
	private static volatile OracleIndex index;

	public enum State {
		IDLE,
		LOADING,
		READY,
		FAILED
	}

	private OracleService() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ensure(false));
	}

	public static State state() {
		return state;
	}

	public static String detail() {
		return detail;
	}

	public static OracleIndex index() {
		return index;
	}

	/** @return false when a build is already running, or the index is already ready and this was not a forced refresh */
	public static boolean ensure(boolean force) {
		synchronized (LOCK) {
			if (running) {
				return false;
			}
			if (!force && state == State.READY && index != null) {
				return false;
			}
			running = true;
		}
		state = State.LOADING;
		detail = force ? "redownloading" : "starting";
		Thread thread = new Thread(() -> run(force), "spellblock-oracle");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	private static void run(boolean force) {
		try {
			Path cache = cachePath();
			if (!force && Files.isRegularFile(cache)) {
				detail = "reading cache";
				try {
					OracleIndex loaded = OracleIndex.read(cache);
					if (loaded.size() >= MIN_CARDS) {
						publish(loaded, "cached");
						return;
					}
					SpellBlock.LOGGER.warn("Card cache only had {} entries. Downloading again.", loaded.size());
				} catch (Exception e) {
					SpellBlock.LOGGER.warn("Card cache could not be read. Downloading again.", e);
				}
			}
			publish(download(), "downloaded");
		} catch (Exception e) {
			String message = e.getMessage();
			if (message == null || message.isBlank()) {
				message = e.getClass().getSimpleName();
			}
			String notice = ScryfallClient.lastNotice();
			if (notice != null && !notice.isEmpty()) {
				message = notice + " (" + message + ")";
			}
			if (index == null) {
				state = State.FAILED;
				detail = message;
			} else {
				state = State.READY;
				detail = readyDetail + " (refresh failed: " + message + ")";
			}
			SpellBlock.LOGGER.error("Card index failed: {}", message);
		} finally {
			synchronized (LOCK) {
				running = false;
			}
		}
	}

	private static void publish(OracleIndex loaded, String how) {
		index = loaded;
		state = State.READY;
		TableNames.clear();
		String updated = loaded.updatedAt().isEmpty() ? "" : ", updated " + loaded.updatedAt();
		detail = loaded.size() + " cards, " + how + updated;
		readyDetail = detail;
		SpellBlock.LOGGER.info("Card index ready ({} cards, {}).", loaded.size(), how);
	}

	private static OracleIndex download() throws IOException, InterruptedException {
		detail = "asking Scryfall for the oracle list";
		Bulk bulk = fetchBulk();
		long megabytes = Math.max(1, bulk.bytes() / (1024 * 1024));
		detail = "downloading oracle cards (" + megabytes + " MB)";
		SpellBlock.LOGGER.info("Downloading Scryfall oracle cards ({} MB). This happens once.", megabytes);
		politePause();
		HttpResponse<InputStream> response = HTTP.send(request(bulk.uri()), HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() == 429) {
			response.body().close();
			ScryfallClient.note("Scryfall rate limit while downloading the oracle list. Wait, then /spellblock reindex.");
			throw new IOException("Scryfall rate-limited oracle download (HTTP 429)");
		}
		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException("Scryfall returned " + response.statusCode() + " for the oracle file");
		}
		List<OracleCard> cards;

		try (InputStream in = response.body()) {
			InputStream decompressed = in;
			if (bulk.uri().endsWith(".gz")) {
				decompressed = new java.util.zip.GZIPInputStream(in);
			}

			cards = ScryfallOracle.readArray(decompressed, count -> {
				detail = "reading cards (" + count + ")";
				SpellBlock.LOGGER.info("Oracle cards read: {}", count);
			});
		}
		if (cards.size() < MIN_CARDS) {
			throw new IOException("Oracle file only contained " + cards.size() + " cards");
		}
		OracleIndex loaded = OracleIndex.build(cards, bulk.updatedAt());
		detail = "saving cache";
		loaded.write(cachePath());
		return loaded;
	}

	private static Bulk fetchBulk() throws IOException, InterruptedException {
		politePause();
		HttpResponse<String> response = HTTP.send(request(BULK_DATA), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 429) {
			ScryfallClient.note("Scryfall rate limit on bulk-data. Wait a minute, then /spellblock reindex.");
			throw new IOException("Scryfall rate-limited bulk-data (HTTP 429)");
		}
		if (response.statusCode() != 200) {
			throw new IOException("Scryfall bulk-data returned " + response.statusCode());
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonArray data = root.getAsJsonArray("data");
		if (data == null) {
			throw new IOException("Scryfall bulk-data had no data array");
		}
		for (JsonElement element : data) {
			JsonObject item = element.getAsJsonObject();
			if (!"oracle_cards".equals(stringField(item, "type"))) {
				continue;
			}

			String uri = stringField(item, "jsonl_download_uri");
			if (uri.isEmpty()) {
				uri = stringField(item, "download_uri");
			}

			if (uri.isEmpty()) {
				throw new IOException("Scryfall returned an oracle_cards entry with no download URI. Raw item: " + item.toString());
			}

			checkHost(uri);
			long bytes = 0;
		if (item.has("compressed_size") && item.get("compressed_size").isJsonPrimitive()) {
			bytes = item.get("compressed_size").getAsLong();
		} else if (item.has("size") && item.get("size").isJsonPrimitive()) {
			bytes = item.get("size").getAsLong();
		}
			return new Bulk(uri, stringField(item, "updated_at"), bytes);
		}
		throw new IOException("Scryfall bulk-data has no oracle_cards entry");
	}

	private static HttpRequest request(String url) throws IOException {
		ScryfallClient.checkHost(url);
		return HttpRequest.newBuilder(URI.create(url))
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.timeout(Duration.ofMinutes(10))
			.GET()
			.build();
	}

	private static void politePause() {
		try {
			ScryfallClient.throttle();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void checkHost(String url) throws IOException {
		if (url == null || url.isBlank()) {
			throw new IOException("Download URL is empty");
		}

		URI uri = URI.create(url);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean scryfall = host.equals("scryfall.com") || host.endsWith(".scryfall.com")
				|| host.equals("scryfall.io") || host.endsWith(".scryfall.io");

		if (!"https".equalsIgnoreCase(uri.getScheme()) || !scryfall) {
			throw new IOException("Refusing unexpected download host: '" + host + "' from URL: " + url);
		}
	}

	private static String stringField(JsonObject object, String name) {
		JsonElement value = object.get(name);
		if (value == null || value.isJsonNull()) {
			return "";
		}
		return value.getAsString();
	}

	private static Path cachePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("spellblock").resolve("oracle-index.json.gz");
	}

	private record Bulk(String uri, String updatedAt, long bytes) {
	}
}
