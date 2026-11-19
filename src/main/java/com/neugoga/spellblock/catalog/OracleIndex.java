package com.neugoga.spellblock.catalog;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Name lookup over the slim oracle cache. Built once, then only read. */
public final class OracleIndex {
	static final int VERSION = 6;

	/** Layouts that are not a playable card. Grant and name lookup skip these. */
	private static final Set<String> NON_PLAYABLE_LAYOUTS = Set.of(
		"art_series",
		"token",
		"double_faced_token",
		"emblem",
		"planar",
		"scheme",
		"vanguard"
	);

	private final List<OracleCard> cards;
	private final List<OracleCard> tokens;
	private final String updatedAt;
	private final Map<String, OracleCard> byLower = new HashMap<>();
	private final Map<String, OracleCard> byFold = new HashMap<>();

	private final Map<String, OracleCard> byOracleId = new HashMap<>();
	private final Map<String, OracleCard> tokenByOracleId = new HashMap<>();

	private OracleIndex(List<OracleCard> cards, List<OracleCard> tokens, String updatedAt) {
		this.cards = cards;
		this.tokens = tokens;
		this.updatedAt = updatedAt == null ? "" : updatedAt;
		for (OracleCard card : cards) {
			if (!card.oracleId().isEmpty()) {
				byOracleId.putIfAbsent(card.oracleId(), card);
			}
		}
		for (OracleCard token : tokens) {
			if (!token.oracleId().isEmpty()) {
				tokenByOracleId.putIfAbsent(token.oracleId(), token);
			}
		}
		index(false);
		index(true);
	}

	public static OracleIndex build(List<OracleCard> all, String updatedAt) {
		ArrayList<OracleCard> playable = new ArrayList<>();
		ArrayList<OracleCard> tokenList = new ArrayList<>();
		for (OracleCard card : all) {
			if (card == null) {
				continue;
			}
			if (isTokenLayout(card.layout())) {
				tokenList.add(card);
			} else if (!isNonPlayableLayout(card.layout())) {
				playable.add(card);
			}
		}
		return new OracleIndex(List.copyOf(playable), List.copyOf(tokenList), updatedAt);
	}

	public OracleCard findToken(String oracleId) {
		if (oracleId == null || oracleId.isEmpty()) {
			return null;
		}
		return tokenByOracleId.get(oracleId);
	}

	/** Tokens related to this playable card via Scryfall all_parts (printing id + name). */
	public List<OracleCard> relatedTokens(String oracleId) {
		OracleCard card = findByOracleId(oracleId);
		if (card == null || card.relatedTokens().isEmpty()) {
			return List.of();
		}
		ArrayList<OracleCard> out = new ArrayList<>();
		for (String piece : card.relatedTokens().split(";")) {
			if (piece.isEmpty()) {
				continue;
			}
			// Don't use String.split("|") — | is a regex metacharacter.
			String printingId = piece;
			String name = "Token";
			String typeLine = "";
			int bar1 = piece.indexOf('|');
			if (bar1 >= 0) {
				printingId = piece.substring(0, bar1);
				int bar2 = piece.indexOf('|', bar1 + 1);
				if (bar2 >= 0) {
					name = piece.substring(bar1 + 1, bar2);
					typeLine = piece.substring(bar2 + 1);
				} else {
					name = piece.substring(bar1 + 1);
				}
			}
			if (printingId.isEmpty()) {
				continue;
			}
			OracleCard known = null;
			for (OracleCard tok : tokens) {
				if (printingId.equals(tok.printingId())) {
					known = tok;
					break;
				}
			}
			if (known != null) {
				out.add(known);
			} else {
				// Oracle bulk has no token objects; all_parts only gives printing id + name.
				out.add(new OracleCard(
					"",
					name,
					"",
					0,
					typeLine,
					"",
					"",
					"token",
					"",
					"",
					"",
					printingId,
					""
				));
			}
		}
		return out;
	}

	public int tokenCount() {
		return tokens.size();
	}

	public int size() {
		return cards.size();
	}

	public String updatedAt() {
		return updatedAt;
	}

	public OracleCard findByOracleId(String oracleId) {
		if (oracleId == null || oracleId.isEmpty()) {
			return null;
		}
		return byOracleId.get(oracleId);
	}

	public boolean hasLegalityData() {
		for (OracleCard card : cards) {
			if (!card.legalities().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public OracleCard find(String query) {
		if (query == null) {
			return null;
		}
		String trimmed = query.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		OracleCard exact = byLower.get(trimmed.toLowerCase(Locale.ROOT));
		if (exact != null) {
			return exact;
		}
		return byFold.get(fold(trimmed));
	}

	public record SearchResult(List<OracleCard> matches, int totalMatches) {
	}

	/**
	 * Search all playable oracle cards by name (or {@code t:<type>}), optional color identity letters
	 * (e.g. "WUC"), and optional comma-separated rarities (e.g. "rare,mythic").
	 */
	public SearchResult search(String query, String colorFilter, String rarityFilter, int limit) {
		int cap = Math.max(1, limit);
		String raw = query == null ? "" : query.trim();
		boolean byType = raw.toLowerCase(Locale.ROOT).startsWith("t:");
		String term = byType ? raw.substring(2).trim() : raw;
		String foldedQuery = fold(term);
		String colors = colorFilter == null ? "" : colorFilter.toUpperCase(Locale.ROOT);
		Set<String> rarities = parseRarities(rarityFilter);

		record Ranked(OracleCard card, int rank) {
		}
		List<Ranked> hits = new ArrayList<>();
		for (OracleCard card : cards) {
			if (!matchesColor(card.colorIdentity(), colors)) {
				continue;
			}
			if (!rarities.isEmpty() && !rarities.contains(card.rarity().toLowerCase(Locale.ROOT))) {
				continue;
			}
			int rank;
			if (foldedQuery.isEmpty()) {
				rank = basicLandRank(card.name());
			} else if (byType) {
				String foldedType = fold(card.typeLine());
				if (!foldedType.contains(foldedQuery)) {
					continue;
				}
				rank = foldedType.startsWith(foldedQuery) ? 1 : 2;
			} else {
				String foldedName = fold(card.name());
				if (foldedName.equals(foldedQuery)) {
					rank = 0;
				} else if (foldedName.startsWith(foldedQuery)) {
					rank = 1;
				} else if (foldedName.contains(" " + foldedQuery)) {
					rank = 2;
				} else if (foldedName.contains(foldedQuery)) {
					rank = 3;
				} else {
					continue;
				}
			}
			hits.add(new Ranked(card, rank));
		}
		hits.sort((a, b) -> {
			int cmp = Integer.compare(a.rank, b.rank);
			if (cmp != 0) {
				return cmp;
			}
			return a.card.name().compareToIgnoreCase(b.card.name());
		});
		int total = hits.size();
		int take = Math.min(cap, total);
		List<OracleCard> out = new ArrayList<>(take);
		for (int i = 0; i < take; i++) {
			out.add(hits.get(i).card);
		}
		return new SearchResult(List.copyOf(out), total);
	}

	private static int basicLandRank(String name) {
		return switch (name) {
			case "Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes" -> 0;
			default -> 1;
		};
	}

	private static Set<String> parseRarities(String rarityFilter) {
		if (rarityFilter == null || rarityFilter.isBlank()) {
			return Set.of();
		}
		java.util.HashSet<String> out = new java.util.HashSet<>();
		for (String part : rarityFilter.split(",")) {
			String trimmed = part.trim().toLowerCase(Locale.ROOT);
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	private static boolean matchesColor(String identity, String filter) {
		if (filter.isEmpty()) {
			return true;
		}
		String id = identity == null ? "" : identity;
		for (int i = 0; i < filter.length(); i++) {
			char c = filter.charAt(i);
			if (c == 'C' && id.isEmpty()) {
				return true;
			}
			if (c != 'C' && id.indexOf(c) >= 0) {
				return true;
			}
		}
		return false;
	}

	/** Letters and digits only, accents stripped. "Lim-Dûl's Vault" and "lim duls vault" do not collide on purpose; punctuation becomes a space. */
	public static String fold(String name) {
		String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
		StringBuilder out = new StringBuilder(decomposed.length());
		boolean pendingSpace = false;
		for (int i = 0; i < decomposed.length(); i++) {
			char c = decomposed.charAt(i);
			if (Character.getType(c) == Character.NON_SPACING_MARK) {
				continue;
			}
			if (Character.isLetterOrDigit(c)) {
				if (pendingSpace && !out.isEmpty()) {
					out.append(' ');
				}
				pendingSpace = false;
				out.append(Character.toLowerCase(c));
			} else {
				pendingSpace = true;
			}
		}
		return out.toString();
	}

	public void write(Path path) throws IOException {
		Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
		Files.createDirectories(path.getParent());
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp));
			 JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
			writer.beginObject();
			writer.name("version").value(VERSION);
			writer.name("updated").value(updatedAt);
			writer.name("cards");
			writer.beginArray();
			for (OracleCard card : cards) {
				writer.beginObject();
				writer.name("o").value(card.oracleId());
				writer.name("n").value(card.name());
				if (!card.manaCost().isEmpty()) {
					writer.name("m").value(card.manaCost());
				}
				if (card.manaValue() != 0) {
					writer.name("v").value(card.manaValue());
				}
				if (!card.typeLine().isEmpty()) {
					writer.name("t").value(card.typeLine());
				}
				if (!card.colorIdentity().isEmpty()) {
					writer.name("c").value(card.colorIdentity());
				}
				if (!card.rarity().isEmpty()) {
					writer.name("r").value(card.rarity());
				}
				if (!card.layout().isEmpty()) {
					writer.name("l").value(card.layout());
				}
				if (!card.setCode().isEmpty()) {
					writer.name("s").value(card.setCode());
				}
				if (!card.legalities().isEmpty()) {
					writer.name("g").value(card.legalities());
				}
				if (!card.rules().isEmpty()) {
					writer.name("k").value(card.rules());
				}
				if (!card.printingId().isEmpty()) {
					writer.name("p").value(card.printingId());
				}
				if (!card.relatedTokens().isEmpty()) {
					writer.name("rt").value(card.relatedTokens());
				}
				writer.endObject();
			}
			writer.endArray();
			writer.name("tokens");
			writer.beginArray();
			for (OracleCard card : tokens) {
				writer.beginObject();
				writer.name("o").value(card.oracleId());
				writer.name("n").value(card.name());
				if (!card.typeLine().isEmpty()) {
					writer.name("t").value(card.typeLine());
				}
				if (!card.layout().isEmpty()) {
					writer.name("l").value(card.layout());
				}
				if (!card.setCode().isEmpty()) {
					writer.name("s").value(card.setCode());
				}
				if (!card.printingId().isEmpty()) {
					writer.name("p").value(card.printingId());
				}
				writer.endObject();
			}
			writer.endArray();
			writer.endObject();
		}
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static OracleIndex read(Path path) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(path));
			 JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			int version = 0;
			String updated = "";
			List<OracleCard> cards = null;
			List<OracleCard> tokens = List.of();
			reader.beginObject();
			while (reader.hasNext()) {
				switch (reader.nextName()) {
					case "version" -> version = reader.nextInt();
					case "updated" -> updated = reader.nextString();
					case "cards" -> cards = readCards(reader);
					case "tokens" -> tokens = readCards(reader);
					default -> reader.skipValue();
				}
			}
			reader.endObject();
			if (version != VERSION || cards == null) {
				throw new IOException("Unrecognized card cache");
			}
			return new OracleIndex(cards, tokens, updated);
		}
	}

	private static List<OracleCard> readCards(JsonReader reader) throws IOException {
		Map<String, String> intern = new HashMap<>();
		List<OracleCard> cards = new ArrayList<>();
		reader.beginArray();
		while (reader.hasNext()) {
			cards.add(readCached(reader, intern));
		}
		reader.endArray();
		return cards;
	}

	private static OracleCard readCached(JsonReader reader, Map<String, String> intern) throws IOException {
		String oracleId = "";
		String name = "";
		String manaCost = "";
		double manaValue = 0;
		String typeLine = "";
		String colorIdentity = "";
		String rarity = "";
		String layout = "";
		String setCode = "";
		String legalities = "";
		String rules = "";
		String printingId = "";
		String relatedTokens = "";
		reader.beginObject();
		while (reader.hasNext()) {
			switch (reader.nextName()) {
				case "o" -> oracleId = reader.nextString();
				case "n" -> name = reader.nextString();
				case "m" -> manaCost = intern(intern, reader.nextString());
				case "v" -> manaValue = reader.nextDouble();
				case "t" -> typeLine = reader.nextString();
				case "c" -> colorIdentity = intern(intern, reader.nextString());
				case "r" -> rarity = intern(intern, reader.nextString());
				case "l" -> layout = intern(intern, reader.nextString());
				case "s" -> setCode = intern(intern, reader.nextString());
				case "g" -> legalities = reader.nextString();
				case "k" -> rules = reader.nextString();
				case "p" -> printingId = reader.nextString();
				case "rt" -> relatedTokens = reader.nextString();
				default -> reader.skipValue();
			}
		}
		reader.endObject();
		return new OracleCard(oracleId, name, manaCost, manaValue, typeLine, colorIdentity, rarity, layout, setCode, legalities, rules, printingId, relatedTokens);
	}

	private void index(boolean backsOnly) {
		for (OracleCard card : cards) {
			if (card.oracleId().isEmpty() || card.name().isEmpty()) {
				continue;
			}
			String name = card.name();
			int split = name.indexOf(" // ");
			if (!backsOnly) {
				put(name, card);
				if (split >= 0) {
					put(name.substring(0, split), card);
				}
			} else if (split >= 0) {
				put(name.substring(split + 4), card);
			}
		}
	}

	private void put(String name, OracleCard card) {
		String lower = name.toLowerCase(Locale.ROOT);
		String folded = fold(name);
		OracleCard existingLower = byLower.get(lower);
		if (existingLower == null || prefers(card, existingLower)) {
			byLower.put(lower, card);
		}
		OracleCard existingFold = byFold.get(folded);
		if (existingFold == null || prefers(card, existingFold)) {
			byFold.put(folded, card);
		}
	}

	/** Prefer a real playable card over art series, tokens, and type-line-only "Card" memorabilia. */
	static boolean prefers(OracleCard candidate, OracleCard current) {
		return score(candidate) > score(current);
	}

	static int score(OracleCard card) {
		if (isNonPlayableLayout(card.layout())) {
			return 0;
		}
		int score = 1;
		if (!isArtOnlyType(card.typeLine())) {
			score += 4;
		}
		if (!card.legalities().isEmpty()) {
			score += 2;
		}
		if (!card.manaCost().isEmpty() || card.manaValue() > 0) {
			score += 1;
		}
		return score;
	}

	static boolean isTokenLayout(String layout) {
		return "token".equals(layout) || "double_faced_token".equals(layout);
	}

	static boolean isNonPlayableLayout(String layout) {
		return layout != null && NON_PLAYABLE_LAYOUTS.contains(layout);
	}

	/** Art series and similar memorabilia use type line "Card" or "Card // Card". */
	static boolean isArtOnlyType(String typeLine) {
		if (typeLine == null || typeLine.isEmpty()) {
			return false;
		}
		String trimmed = typeLine.trim();
		if ("Card".equals(trimmed)) {
			return true;
		}
		if (!trimmed.contains(" // ")) {
			return false;
		}
		for (String face : trimmed.split(" // ", -1)) {
			if (!"Card".equals(face.trim())) {
				return false;
			}
		}
		return true;
	}

	static String intern(Map<String, String> intern, String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		return intern.computeIfAbsent(value, key -> key);
	}
}
