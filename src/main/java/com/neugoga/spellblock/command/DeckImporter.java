package com.neugoga.spellblock.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.library.DeckEntry;
import com.neugoga.spellblock.library.DeckFormat;
import com.neugoga.spellblock.library.Finish;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches a public Moxfield or Archidekt deck and maps it onto the local oracle index. */
public final class DeckImporter {
	private static final Pattern MOXFIELD_CODE = Pattern.compile(
		"(?:https?://)?(?:www\\.)?moxfield\\.com/decks/([A-Za-z0-9_-]+)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern ARCHIDEKT_CODE = Pattern.compile(
		"(?:https?://)?(?:www\\.)?archidekt\\.com/decks/(\\d+)",
		Pattern.CASE_INSENSITIVE
	);
	private static final int TIMEOUT_MS = 15_000;

	private DeckImporter() {
	}

	public record Line(String name, String oracleId, String printingId, String finish, int count) {
	}

	public record ImportedDeck(
		String name,
		DeckFormat format,
		List<Line> commanders,
		List<Line> main,
		List<Line> side,
		List<String> missing
	) {
	}

	public static String extractCode(String source, String raw) {
		if (raw == null) {
			return "";
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		if ("moxfield".equals(source)) {
			Matcher m = MOXFIELD_CODE.matcher(trimmed);
			if (m.find()) {
				return m.group(1);
			}
		} else if ("archidekt".equals(source)) {
			Matcher m = ARCHIDEKT_CODE.matcher(trimmed);
			if (m.find()) {
				return m.group(1);
			}
		}
		// Bare code: strip trailing slash and path noise.
		int slash = trimmed.lastIndexOf('/');
		if (slash >= 0 && slash + 1 < trimmed.length()) {
			trimmed = trimmed.substring(slash + 1);
		}
		int q = trimmed.indexOf('?');
		if (q >= 0) {
			trimmed = trimmed.substring(0, q);
		}
		return trimmed.trim();
	}

	public static ImportedDeck fetch(String source, String code, OracleIndex index) throws Exception {
		if ("moxfield".equals(source)) {
			return fromMoxfield(code, index);
		}
		if ("archidekt".equals(source)) {
			return fromArchidekt(code, index);
		}
		throw new IllegalArgumentException("Unknown source: " + source);
	}

	private static ImportedDeck fromMoxfield(String code, OracleIndex index) throws Exception {
		JsonObject root = getJson("https://api2.moxfield.com/v2/decks/all/" + code);
		String name = text(root, "name");
		DeckFormat format = mapFormat(text(root, "format"));
		Map<String, Line> commanders = new LinkedHashMap<>();
		Map<String, Line> main = new LinkedHashMap<>();
		Map<String, Line> side = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		// Prefer boards.*.cards when present (v2/v3 shape); fall back to top-level maps.
		JsonObject boards = root.has("boards") && root.get("boards").isJsonObject() ? root.getAsJsonObject("boards") : null;
		readMoxBoard(moxBoardCards(boards, "commanders", root.get("commanders")), commanders, missing, index);
		readMoxBoard(moxBoardCards(boards, "mainboard", root.get("mainboard")), main, missing, index);
		readMoxBoard(moxBoardCards(boards, "sideboard", root.get("sideboard")), side, missing, index);
		// Maybeboard is ignored on purpose.
		return new ImportedDeck(name.isEmpty() ? "Imported deck" : name, format, listOf(commanders), listOf(main), listOf(side), missing);
	}

	/** Moxfield puts cards either at the board root or under board.cards. */
	private static JsonObject moxBoardCards(JsonObject boards, String boardName, JsonElement topLevel) {
		if (boards != null && boards.has(boardName) && boards.get(boardName).isJsonObject()) {
			JsonObject board = boards.getAsJsonObject(boardName);
			if (board.has("cards") && board.get("cards").isJsonObject()) {
				return board.getAsJsonObject("cards");
			}
			// Some payloads are already a map of card rows (no "cards" wrapper).
			if (!board.has("count") || board.size() > 2) {
				return board;
			}
		}
		if (topLevel != null && topLevel.isJsonObject()) {
			JsonObject top = topLevel.getAsJsonObject();
			if (top.has("cards") && top.get("cards").isJsonObject()) {
				return top.getAsJsonObject("cards");
			}
			return top;
		}
		return null;
	}

	private static void readMoxBoard(JsonObject board, Map<String, Line> into, List<String> missing, OracleIndex index) {
		if (board == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : board.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject row = entry.getValue().getAsJsonObject();
			// Skip board metadata if a wrapper leaked through.
			if (!row.has("card") && !row.has("quantity") && !row.has("name")) {
				continue;
			}
			int qty = jsonInt(row, "quantity", 1);
			if (qty < 1) {
				continue;
			}
			JsonObject card = row.has("card") && row.get("card").isJsonObject() ? row.getAsJsonObject("card") : row;
			String cardName = text(card, "name");
			String oracleId = text(card, "oracle_id");
			String printingId = text(card, "scryfall_id");
			if (printingId.isEmpty()) {
				printingId = text(card, "id");
			}
			Finish finish = moxFinish(row, card);
			addResolved(into, missing, index, cardName, oracleId, printingId, finish, qty);
		}
	}

	private static Finish moxFinish(JsonObject row, JsonObject card) {
		String finish = text(row, "finish");
		if (finish.isEmpty()) {
			finish = text(card, "finish");
		}
		if (!finish.isEmpty()) {
			String key = finish.toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
			return switch (key) {
				case "foil", "foiled" -> Finish.FOIL;
				case "etched", "etchedfoil" -> Finish.ETCHED;
				default -> Finish.NONFOIL;
			};
		}
		if (jsonBool(row, "isFoil")) {
			return Finish.FOIL;
		}
		if (jsonBool(row, "is_foil")) {
			return Finish.FOIL;
		}
		if (jsonBool(row, "isEtched")) {
			return Finish.ETCHED;
		}
		return Finish.NONFOIL;
	}

	private static Finish archFinish(JsonObject row) {
		String finish = text(row, "finish");
		if (finish.isEmpty() && row.has("card") && row.get("card").isJsonObject()) {
			finish = text(row.getAsJsonObject("card"), "finish");
		}
		if (!finish.isEmpty()) {
			String key = finish.toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
			if ((key.contains("foil") && !key.contains("non")) || key.equals("foil")) {
				return Finish.FOIL;
			}
			if (key.contains("etch")) {
				return Finish.ETCHED;
			}
		}
		if (row.has("modifier") && row.get("modifier").isJsonObject()) {
			String mod = text(row.getAsJsonObject("modifier"), "finish").toLowerCase(Locale.ROOT);
			if (mod.contains("foil") && !mod.contains("non")) {
				return Finish.FOIL;
			}
			if (mod.contains("etch")) {
				return Finish.ETCHED;
			}
		}
		return Finish.NONFOIL;
	}

	private static ImportedDeck fromArchidekt(String code, OracleIndex index) throws Exception {
		JsonObject root = getJson("https://archidekt.com/api/decks/" + code + "/");
		String name = text(root, "name");
		DeckFormat format = DeckFormat.COMMANDER;
		if (root.has("deckFormat") && root.get("deckFormat").isJsonObject()) {
			format = mapFormat(text(root.getAsJsonObject("deckFormat"), "name"));
		} else if (root.has("format")) {
			format = mapFormat(text(root, "format"));
		}
		Map<String, Line> commanders = new LinkedHashMap<>();
		Map<String, Line> main = new LinkedHashMap<>();
		Map<String, Line> side = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		JsonArray cards = root.has("cards") && root.get("cards").isJsonArray() ? root.getAsJsonArray("cards") : new JsonArray();
		for (JsonElement el : cards) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject row = el.getAsJsonObject();
			int qty = jsonInt(row, "quantity", 1);
			if (qty < 1) {
				continue;
			}
			Finish finish = archFinish(row);
			String categories = categoriesOf(row);
			if (categories.contains("maybeboard") || categories.contains("wishlist")) {
				continue;
			}
			JsonObject card = row.has("card") && row.get("card").isJsonObject() ? row.getAsJsonObject("card") : row;
			String cardName = text(card, "name");
			if (cardName.isEmpty() && card.has("oracleCard") && card.get("oracleCard").isJsonObject()) {
				cardName = text(card.getAsJsonObject("oracleCard"), "name");
			}
			String oracleId = text(card, "oracleid");
			if (oracleId.isEmpty()) {
				oracleId = text(card, "oracleId");
			}
			if (oracleId.isEmpty() && card.has("oracleCard") && card.get("oracleCard").isJsonObject()) {
				oracleId = text(card.getAsJsonObject("oracleCard"), "uid");
			}
			String printingId = text(card, "uid");
			if (printingId.isEmpty()) {
				printingId = text(card, "scryfall");
			}
			boolean isCommander = categories.contains("commander") || categories.contains("companion");
			boolean isSide = categories.contains("sideboard") || categories.contains("side board");
			if (isCommander) {
				addResolved(commanders, missing, index, cardName, oracleId, printingId, finish, qty);
			} else if (isSide) {
				addResolved(side, missing, index, cardName, oracleId, printingId, finish, qty);
			} else {
				addResolved(main, missing, index, cardName, oracleId, printingId, finish, qty);
			}
		}
		return new ImportedDeck(name.isEmpty() ? "Imported deck" : name, format, listOf(commanders), listOf(main), listOf(side), missing);
	}

	private static String categoriesOf(JsonObject row) {
		StringBuilder out = new StringBuilder();
		if (row.has("categories") && row.get("categories").isJsonArray()) {
			for (JsonElement c : row.getAsJsonArray("categories")) {
				out.append(c.getAsString().toLowerCase(Locale.ROOT)).append(' ');
			}
		}
		if (row.has("category")) {
			out.append(text(row, "category").toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	private static void addResolved(
		Map<String, Line> into,
		List<String> missing,
		OracleIndex index,
		String name,
		String oracleId,
		String printingId,
		Finish finish,
		int qty
	) {
		OracleCard resolved = resolve(index, name, oracleId, printingId);
		if (resolved == null) {
			if (!name.isEmpty() && missing.size() < 40) {
				missing.add(name);
			} else if (missing.size() < 40) {
				missing.add(oracleId.isEmpty() ? printingId : oracleId);
			}
			return;
		}
		String o = resolved.oracleId();
		String p = !printingId.isEmpty() ? printingId : resolved.printingId();
		if (p.isEmpty()) {
			p = resolved.printingId();
		}
		Finish f = finish == null ? Finish.NONFOIL : finish;
		// Foil and nonfoil are separate stacks.
		String key = o + "|" + p + "|" + f.name();
		Line existing = into.get(key);
		if (existing == null) {
			into.put(key, new Line(resolved.name(), o, p, f.name(), qty));
		} else {
			into.put(key, new Line(existing.name(), o, p, f.name(), existing.count() + qty));
		}
	}

	private static OracleCard resolve(OracleIndex index, String name, String oracleId, String printingId) {
		if (index == null) {
			return null;
		}
		if (oracleId != null && !oracleId.isEmpty()) {
			OracleCard byOracle = index.findByOracleId(oracleId);
			if (byOracle != null) {
				return byOracle;
			}
		}
		if (name != null && !name.isEmpty()) {
			OracleCard byName = index.find(name);
			if (byName != null) {
				return byName;
			}
			// Split faces "A // B"
			int face = name.indexOf(" // ");
			if (face > 0) {
				byName = index.find(name.substring(0, face).trim());
				if (byName != null) {
					return byName;
				}
			}
		}
		return null;
	}

	public static List<DeckEntry> toEntries(List<Line> lines) {
		ArrayList<DeckEntry> out = new ArrayList<>(lines.size());
		for (Line line : lines) {
			out.add(new DeckEntry(line.oracleId(), line.printingId(), line.finish(), line.count()));
		}
		return out;
	}

	private static List<Line> listOf(Map<String, Line> map) {
		return List.copyOf(map.values());
	}

	private static DeckFormat mapFormat(String raw) {
		if (raw == null || raw.isEmpty()) {
			return DeckFormat.COMMANDER;
		}
		String key = raw.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
		return switch (key) {
			case "commander", "edh", "duelcommander", "duel_commander" -> DeckFormat.COMMANDER;
			case "paupercommander", "pauper_commander" -> DeckFormat.PAUPER_COMMANDER;
			case "standard" -> DeckFormat.STANDARD;
			case "pioneer" -> DeckFormat.PIONEER;
			case "modern" -> DeckFormat.MODERN;
			case "legacy" -> DeckFormat.LEGACY;
			case "vintage" -> DeckFormat.VINTAGE;
			case "pauper" -> DeckFormat.PAUPER;
			default -> DeckFormat.COMMANDER;
		};
	}

	private static JsonObject getJson(String url) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setConnectTimeout(TIMEOUT_MS);
		conn.setReadTimeout(TIMEOUT_MS);
		conn.setRequestProperty("User-Agent", "SpellBlock-Minecraft-Mod/1.0");
		conn.setRequestProperty("Accept", "application/json");
		int code = conn.getResponseCode();
		if (code < 200 || code >= 300) {
			throw new IllegalStateException("HTTP " + code + " from " + url);
		}
		try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
			JsonElement el = JsonParser.parseReader(reader);
			if (!el.isJsonObject()) {
				throw new IllegalStateException("Unexpected response from " + url);
			}
			return el.getAsJsonObject();
		} finally {
			conn.disconnect();
		}
	}

	private static JsonObject asObject(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static String text(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return "";
		}
		try {
			return obj.get(key).getAsString().trim();
		} catch (Exception e) {
			return "";
		}
	}

	private static int jsonInt(JsonObject row, String key, int fallback) {
		if (row == null || !row.has(key) || row.get(key).isJsonNull() || !row.get(key).isJsonPrimitive()) {
			return fallback;
		}
		try {
			return row.get(key).getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static boolean jsonBool(JsonObject row, String key) {
		if (row == null || !row.has(key) || row.get(key).isJsonNull() || !row.get(key).isJsonPrimitive()) {
			return false;
		}
		try {
			return row.get(key).getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}
}
