package com.neugoga.spellblock.catalog;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared Scryfall HTTP access: polite spacing between calls, 429 retries, and a short notice
 * players can see when Scryfall is rate-limiting or offline.
 */
public final class ScryfallClient {
	/** Scryfall asks for ~50–100 ms between requests. */
	private static final long MIN_GAP_MS = 110;
	private static final int MAX_RETRIES = 4;

	private static final AtomicLong nextAllowedAt = new AtomicLong(0);
	private static final AtomicReference<String> lastNotice = new AtomicReference<>("");

	private ScryfallClient() {
	}

	public static String lastNotice() {
		return lastNotice.get();
	}

	public static void clearNotice() {
		lastNotice.set("");
	}

	/** Block until the next Scryfall call is allowed. */
	public static void throttle() throws InterruptedException {
		long now = System.currentTimeMillis();
		long allowed = nextAllowedAt.get();
		if (now < allowed) {
			Thread.sleep(allowed - now);
		}
		nextAllowedAt.set(System.currentTimeMillis() + MIN_GAP_MS);
	}

	public static HttpRequest.Builder request(String url) throws IOException {
		return request(url, "application/json");
	}

	public static HttpRequest.Builder request(String url, String accept) throws IOException {
		checkHost(url);
		return HttpRequest.newBuilder(URI.create(url))
			.header("User-Agent", OracleService.USER_AGENT)
			.header("Accept", accept == null || accept.isBlank() ? "*/*" : accept)
			.timeout(Duration.ofMinutes(2))
			.GET();
	}

	public static HttpResponse<String> getString(HttpClient client, String url) throws IOException, InterruptedException {
		return send(client, url, "application/json", HttpResponse.BodyHandlers.ofString());
	}

	public static HttpResponse<byte[]> getBytes(HttpClient client, String url) throws IOException, InterruptedException {
		// Card images must not ask for JSON — Scryfall may refuse or return an error body.
		return send(client, url, "image/*,*/*", HttpResponse.BodyHandlers.ofByteArray());
	}

	public static <T> HttpResponse<T> send(HttpClient client, String url, HttpResponse.BodyHandler<T> handler)
		throws IOException, InterruptedException {
		return send(client, url, "application/json", handler);
	}

	public static <T> HttpResponse<T> send(HttpClient client, String url, String accept, HttpResponse.BodyHandler<T> handler)
		throws IOException, InterruptedException {
		IOException last = null;
		for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
			throttle();
			HttpRequest request = request(url, accept).build();
			HttpResponse<T> response = client.send(request, handler);
			int code = response.statusCode();
			if (code == 200 || code == 404) {
				if (code == 200) {
					clearNotice();
				}
				return response;
			}
			if (code == 429) {
				long waitMs = retryAfterMs(response, 5_000L * (attempt + 1));
				String notice = "Scryfall is rate-limiting requests. Waiting " + (waitMs / 1000) + "s, then retrying…";
				note(notice);
				SpellBlock.LOGGER.warn(notice);
				Thread.sleep(waitMs);
				continue;
			}
			if (code >= 500 && code < 600) {
				long waitMs = 2_000L * (attempt + 1);
				String notice = "Scryfall is having trouble (HTTP " + code + "). Retrying in " + (waitMs / 1000) + "s…";
				note(notice);
				SpellBlock.LOGGER.warn(notice);
				Thread.sleep(waitMs);
				continue;
			}
			last = new IOException("Scryfall returned HTTP " + code + " for " + url);
			break;
		}
		String offline = "Scryfall is unavailable or still rate-limiting. Wait a minute and try again.";
		note(offline);
		if (last != null) {
			throw last;
		}
		throw new IOException(offline);
	}

	public static void note(String message) {
		if (message == null || message.isBlank()) {
			return;
		}
		lastNotice.set(message);
	}

	/** Tell every online player once about the current Scryfall notice. */
	public static void broadcastNotice(MinecraftServer server) {
		if (server == null) {
			return;
		}
		String message = lastNotice.get();
		if (message == null || message.isEmpty()) {
			return;
		}
		Component text = Component.literal("[SpellBlock] " + message);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(text);
		}
	}

	private static long retryAfterMs(HttpResponse<?> response, long fallbackMs) {
		String header = response.headers().firstValue("Retry-After").orElse("");
		if (header.isEmpty()) {
			return fallbackMs;
		}
		try {
			return Math.max(1_000L, Long.parseLong(header.trim()) * 1000L);
		} catch (NumberFormatException ignored) {
			return fallbackMs;
		}
	}

	public static void checkHost(String url) throws IOException {
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
}
