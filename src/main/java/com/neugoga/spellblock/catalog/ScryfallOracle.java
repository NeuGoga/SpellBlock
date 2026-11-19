package com.neugoga.spellblock.catalog;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.neugoga.spellblock.SpellBlock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** Streams Scryfall's oracle-cards bulk file and keeps only the fields the binder needs. */
final class ScryfallOracle {
	private static final java.util.Set<String> CHECKED = java.util.Set.of(
		"standard", "pioneer", "modern", "legacy", "vintage", "pauper", "commander", "paupercommander"
	);

	private ScryfallOracle() {
	}

	static List<OracleCard> readArray(InputStream in, IntConsumer progress) throws IOException {
		Map<String, String> intern = new HashMap<>();
		List<OracleCard> cards = new ArrayList<>(32000);

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}

				try (JsonReader jsonReader = new JsonReader(new StringReader(line))) {
					OracleCard card = readCard(jsonReader, intern);
					if (card != null) {
						cards.add(card);
						if (cards.size() % 10000 == 0) {
							progress.accept(cards.size());
						}
					}
				}
			}
		}
		SpellBlock.LOGGER.info("Oracle file contained {} cards.", cards.size());
		return cards;
	}

	private static OracleCard readCard(JsonReader reader, Map<String, String> intern) throws IOException {
		String oracleId = "";
		String name = "";
		String manaCost = "";
		double manaValue = 0;
		String typeLine = "";
		String colorIdentity = "";
		String rarity = "";
		String layout = "";
		String setCode = "";
		String printingId = "";
		StringBuilder legalities = new StringBuilder();
		StringBuilder relatedTokens = new StringBuilder();
		String oracleText = "";
		String faceText = "";
		String faceMana = "";
		String faceType = "";
		reader.beginObject();
		while (reader.hasNext()) {
			switch (reader.nextName()) {
				case "oracle_id" -> oracleId = nextString(reader);
				case "id" -> printingId = nextString(reader);
				case "name" -> name = nextString(reader);
				case "mana_cost" -> manaCost = OracleIndex.intern(intern, nextString(reader));
				case "cmc" -> manaValue = nextDouble(reader);
				case "type_line" -> typeLine = nextString(reader);
				case "color_identity" -> colorIdentity = readColors(reader, intern);
				case "rarity" -> rarity = OracleIndex.intern(intern, nextString(reader));
				case "layout" -> layout = OracleIndex.intern(intern, nextString(reader));
				case "set" -> setCode = OracleIndex.intern(intern, nextString(reader));
				case "legalities" -> readLegalities(reader, legalities);
				case "oracle_text" -> {
					String text = nextString(reader);
					if (!text.isEmpty()) {
						oracleText = text;
					}
				}
				case "card_faces" -> {
					FaceBits faces = readFaceBits(reader);
					faceText = faces.text;
					faceMana = faces.manaCost;
					faceType = faces.typeLine;
				}
				case "all_parts" -> readRelatedTokens(reader, relatedTokens);
				default -> reader.skipValue();
			}
		}
		reader.endObject();
		if (oracleId.isEmpty() || name.isEmpty()) {
			return null;
		}
		if (OracleIndex.isTokenLayout(layout)) {
			return new OracleCard(
				oracleId, name, "", 0, typeLine, colorIdentity, rarity, layout, setCode, "", "", printingId, ""
			);
		}
		if (OracleIndex.isNonPlayableLayout(layout)) {
			return null;
		}
		if (manaCost.isEmpty() && !faceMana.isEmpty()) {
			manaCost = OracleIndex.intern(intern, faceMana);
		}
		if (typeLine.isEmpty() && !faceType.isEmpty()) {
			typeLine = faceType;
		}
		String text = oracleText.isEmpty() ? faceText : oracleText;
		String legalityText = legalities.isEmpty() ? "" : OracleIndex.intern(intern, legalities.toString());
		String rules = OracleIndex.intern(intern, CardRules.parse(typeLine, text));
		String related = relatedTokens.isEmpty() ? "" : OracleIndex.intern(intern, relatedTokens.toString());
		return new OracleCard(
			oracleId,
			name,
			manaCost,
			manaValue,
			typeLine,
			colorIdentity,
			rarity,
			layout,
			setCode,
			legalityText,
			rules,
			printingId,
			related
		);
	}

	/**
	 * Scryfall all_parts entries for tokens only expose a printing {@code id}, not oracle_id.
	 * Encode each as {@code printingId|name|typeLine} joined by {@code ;}.
	 */
	private static void readRelatedTokens(JsonReader reader, StringBuilder out) throws IOException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			reader.skipValue();
			return;
		}
		reader.beginArray();
		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				reader.skipValue();
				continue;
			}
			reader.beginObject();
			String component = "";
			String partId = "";
			String partName = "";
			String partType = "";
			while (reader.hasNext()) {
				switch (reader.nextName()) {
					case "component" -> component = nextString(reader);
					case "id" -> partId = nextString(reader);
					case "name" -> partName = nextString(reader);
					case "type_line" -> partType = nextString(reader);
					default -> reader.skipValue();
				}
			}
			reader.endObject();
			if (!"token".equals(component) || partId.isEmpty()) {
				continue;
			}
			if (partName.isEmpty()) {
				partName = "Token";
			}
			// Strip characters we use as separators.
			partName = partName.replace('|', '/').replace(';', ',');
			partType = partType.replace('|', '/').replace(';', ',');
			if (!out.isEmpty()) {
				out.append(';');
			}
			out.append(partId).append('|').append(partName).append('|').append(partType);
		}
		reader.endArray();
	}

	private record FaceBits(String text, String manaCost, String typeLine) {
	}

	private static FaceBits readFaceBits(JsonReader reader) throws IOException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			reader.skipValue();
			return new FaceBits("", "", "");
		}
		StringBuilder text = new StringBuilder();
		String mana = "";
		String type = "";
		boolean first = true;
		reader.beginArray();
		while (reader.hasNext()) {
			if (reader.peek() != JsonToken.BEGIN_OBJECT) {
				reader.skipValue();
				continue;
			}
			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				switch (name) {
					case "oracle_text" -> {
						String face = nextString(reader);
						if (!face.isEmpty()) {
							if (!text.isEmpty()) {
								text.append('\n');
							}
							text.append(face);
						}
					}
					case "mana_cost" -> {
						String m = nextString(reader);
						if (first && mana.isEmpty() && !m.isEmpty()) {
							mana = m;
						}
					}
					case "type_line" -> {
						String tl = nextString(reader);
						if (first && type.isEmpty() && !tl.isEmpty()) {
							type = tl;
						}
					}
					default -> reader.skipValue();
				}
			}
			reader.endObject();
			first = false;
		}
		reader.endArray();
		return new FaceBits(text.toString(), mana, type);
	}

	private static String readColors(JsonReader reader, Map<String, String> intern) throws IOException {
		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			reader.skipValue();
			return "";
		}
		StringBuilder colors = new StringBuilder(5);
		reader.beginArray();
		while (reader.hasNext()) {
			colors.append(nextString(reader));
		}
		reader.endArray();
		return OracleIndex.intern(intern, colors.toString());
	}

	private static void readLegalities(JsonReader reader, StringBuilder out) throws IOException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			reader.skipValue();
			return;
		}
		reader.beginObject();
		while (reader.hasNext()) {
			String format = reader.nextName();
			String legality = nextString(reader);
			if (!CHECKED.contains(format) || legality.isEmpty() || "not_legal".equals(legality)) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(',');
			}
			out.append(format).append('=').append(legality);
		}
		reader.endObject();
	}

	private static String nextString(JsonReader reader) throws IOException {
		if (reader.peek() == JsonToken.NULL) {
			reader.nextNull();
			return "";
		}
		if (reader.peek() != JsonToken.STRING) {
			reader.skipValue();
			return "";
		}
		return reader.nextString();
	}

	private static double nextDouble(JsonReader reader) throws IOException {
		if (reader.peek() == JsonToken.NUMBER) {
			return reader.nextDouble();
		}
		if (reader.peek() == JsonToken.NULL) {
			reader.nextNull();
			return 0;
		}
		reader.skipValue();
		return 0;
	}
}
