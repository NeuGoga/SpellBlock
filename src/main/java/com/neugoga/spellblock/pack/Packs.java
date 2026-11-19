package com.neugoga.spellblock.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neugoga.spellblock.SpellBlock;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.Rarity;

/** Pack tables shipped in the jar. Adding a pack is a JSON file plus a line in the index. */
public final class Packs {
	private static final Map<String, PackDefinition> BY_ID = new LinkedHashMap<>();

	private Packs() {
	}

	public static void load() {
		if (!BY_ID.isEmpty()) {
			return;
		}
		JsonArray index = readJson("/data/spellblock/packs/index.json").getAsJsonArray();
		for (JsonElement element : index) {
			String id = element.getAsString();
			PackDefinition pack = readPack("/data/spellblock/packs/" + id + ".json");
			if (!id.equals(pack.id())) {
				throw new IllegalStateException("Pack file " + id + " declares id " + pack.id());
			}
			BY_ID.put(pack.id(), pack);
			SpellBlock.LOGGER.info("Loaded pack {} ({} slots).", pack.id(), pack.slots().size());
		}
	}

	public static List<PackDefinition> all() {
		return List.copyOf(BY_ID.values());
	}

	public static PackDefinition get(String id) {
		return BY_ID.get(id);
	}

	private static PackDefinition readPack(String path) {
		JsonObject json = readJson(path).getAsJsonObject();
		String id = required(json, "id");
		String set = required(json, "set");
		String item = required(json, "item");
		String name = required(json, "name");
		Rarity rarity = readRarity(json, path);
		if (!id.matches("[a-z0-9_]+") || !item.matches("[a-z0-9_]+") || !set.matches("[a-z0-9]+")) {
			throw new IllegalStateException("Bad pack id in " + path);
		}
		List<String> queries = new ArrayList<>();
		if (json.has("queries") && json.get("queries").isJsonArray()) {
			for (JsonElement element : json.getAsJsonArray("queries")) {
				String query = element.getAsString().trim();
				if (query.isEmpty() || query.length() > 180 || query.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
					throw new IllegalStateException("Bad query in " + path);
				}
				queries.add(query);
			}
		}
		if (queries.isEmpty()) {
			queries.add("set:" + set);
		}
		List<PackDefinition.Slot> slots = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray("slots")) {
			List<PackDefinition.Option> options = new ArrayList<>();
			for (JsonElement optionElement : element.getAsJsonObject().getAsJsonArray("options")) {
				JsonObject option = optionElement.getAsJsonObject();
				String pool = required(option, "pool");
				int weight = option.get("weight").getAsInt();
				boolean foil = option.has("foil") && option.get("foil").getAsBoolean();
				if (!pool.matches("[a-z][a-z0-9_]*") || weight <= 0) {
					throw new IllegalStateException("Bad slot in " + path + ": " + pool + " " + weight);
				}
				options.add(new PackDefinition.Option(pool, weight, foil));
			}
			if (options.isEmpty()) {
				throw new IllegalStateException("Empty slot in " + path);
			}
			slots.add(new PackDefinition.Slot(List.copyOf(options)));
		}
		if (slots.isEmpty()) {
			throw new IllegalStateException("Pack " + id + " has no slots");
		}
		String boxItem = json.has("box_item") && !json.get("box_item").isJsonNull()
			? json.get("box_item").getAsString().trim()
			: "";
		int boxCount = 0;
		if (json.has("box_count") && json.get("box_count").isJsonPrimitive()) {
			boxCount = json.get("box_count").getAsInt();
		}
		Rarity boxRarity = rarity;
		if (json.has("box_rarity") && !json.get("box_rarity").isJsonNull()) {
			boxRarity = parseRarity(json.get("box_rarity").getAsString().trim().toLowerCase(), path);
		} else if (boxCount > 0) {
			boxRarity = rarer(rarity);
		}
		if (boxCount < 0 || boxCount > 120) {
			throw new IllegalStateException("Bad box_count in " + path);
		}
		if (boxCount > 0 && (boxItem.isEmpty() || !boxItem.matches("[a-z0-9_]+"))) {
			throw new IllegalStateException("Bad box_item in " + path);
		}
		return new PackDefinition(id, set, item, name, rarity, List.copyOf(queries), List.copyOf(slots), boxItem, boxCount, boxRarity);
	}


	private static Rarity readRarity(JsonObject json, String path) {
		if (!json.has("rarity") || json.get("rarity").isJsonNull()) {
			return Rarity.UNCOMMON;
		}
		return parseRarity(json.get("rarity").getAsString().trim().toLowerCase(), path);
	}

	private static Rarity parseRarity(String value, String path) {
		return switch (value) {
			case "common" -> Rarity.COMMON;
			case "uncommon" -> Rarity.UNCOMMON;
			case "rare" -> Rarity.RARE;
			case "epic", "legendary" -> Rarity.EPIC;
			default -> throw new IllegalStateException("Bad pack rarity in " + path + ": " + value);
		};
	}

	/** Box is one step above the pack when box_rarity is omitted. */
	private static Rarity rarer(Rarity rarity) {
		return switch (rarity) {
			case COMMON -> Rarity.UNCOMMON;
			case UNCOMMON -> Rarity.RARE;
			case RARE, EPIC -> Rarity.EPIC;
		};
	}

	private static String required(JsonObject json, String name) {
		JsonElement value = json.get(name);
		if (value == null || value.isJsonNull() || value.getAsString().isBlank()) {
			throw new IllegalStateException("Missing " + name);
		}
		return value.getAsString();
	}

	private static JsonElement readJson(String path) {
		InputStream in = Packs.class.getResourceAsStream(path);
		if (in == null) {
			throw new IllegalStateException("Missing " + path);
		}
		try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read " + path, e);
		}
	}
}
