package com.neugoga.spellblock.pack;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import net.minecraft.util.RandomSource;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Cards of one set, split into the pools a pack JSON can name. */
public final class SetPool {
	static final int VERSION = 2;

	private final String setCode;
	private final List<String> queries;
	private final Map<String, List<Printing>> byPool;
	private final Map<String, Printing> byPrinting;

	private SetPool(String setCode, List<Printing> cards, List<String> queries) {
		this.setCode = setCode;
		this.queries = List.copyOf(queries);
		Map<String, List<Printing>> pools = new HashMap<>();
		Map<String, Printing> byId = new LinkedHashMap<>();
		for (Printing card : cards) {
			if (byId.putIfAbsent(card.printingId(), card) != null) {
				continue;
			}
			if (card.basic() && "common".equals(card.rarity())) {
				pools.computeIfAbsent("basic", key -> new ArrayList<>()).add(card);
			}
			if (!(card.basic() && "normal".equals(card.treatment()) && "common".equals(card.rarity()))) {
				pools.computeIfAbsent(card.poolKey(), key -> new ArrayList<>()).add(card);
			}
		}
		this.byPool = pools;
		this.byPrinting = byId;
	}

	public String setCode() {
		return setCode;
	}

	public int size() {
		return byPrinting.size();
	}

	public int count(String pool) {
		List<Printing> cards = byPool.get(pool);
		return cards == null ? 0 : cards.size();
	}

	public int countPrefix(String prefix) {
		int total = 0;
		for (Map.Entry<String, List<Printing>> entry : byPool.entrySet()) {
			if (entry.getKey().startsWith(prefix)) {
				total += entry.getValue().size();
			}
		}
		return total;
	}

	public boolean sameQueries(List<String> expected) {
		return queries.equals(expected);
	}

	public Printing find(String printingId) {
		if (printingId == null || printingId.isEmpty()) {
			return null;
		}
		return byPrinting.get(printingId);
	}

	public List<Printing> findByOracleId(String oracleId) {
		if (oracleId == null || oracleId.isEmpty()) {
			return List.of();
		}
		List<Printing> out = new ArrayList<>();
		for (Printing card : byPrinting.values()) {
			if (oracleId.equals(card.oracleId())) {
				out.add(card);
			}
		}
		return out;
	}

	/** @return null when that pool was not downloaded. Callers surface the miss instead of substituting a common. */
	public Printing pick(String pool, RandomSource random, Set<String> avoid) {
		return pick(pool, random, avoid, card -> true);
	}

	/** @return null when that pool has no allowed printings. Callers surface the miss instead of substituting. */
	public Printing pick(String pool, RandomSource random, Set<String> avoid, Predicate<Printing> allowed) {
		List<Printing> cards = byPool.get(pool);
		if (cards == null || cards.isEmpty()) {
			return null;
		}
		List<Printing> eligible = allowed == null ? cards : cards.stream().filter(allowed).toList();
		if (eligible.isEmpty()) {
			return null;
		}
		for (int attempt = 0; attempt < 8; attempt++) {
			Printing card = eligible.get(random.nextInt(eligible.size()));
			if (!avoid.contains(card.oracleId())) {
				return card;
			}
		}
		List<Printing> nonDup = eligible.stream().filter(c -> !avoid.contains(c.oracleId())).toList();
		if (!nonDup.isEmpty()) {
			return nonDup.get(random.nextInt(nonDup.size()));
		}
		return eligible.get(random.nextInt(eligible.size()));
	}

	public void write(Path path) throws IOException {
		Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
		Files.createDirectories(path.getParent());
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp));
			 JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
			writer.beginObject();
			writer.name("version").value(VERSION);
			writer.name("set").value(setCode);
			writer.name("queries");
			writer.beginArray();
			for (String query : queries) {
				writer.value(query);
			}
			writer.endArray();
			writer.name("cards");
			writer.beginArray();
			for (Printing card : byPrinting.values()) {
				writer.beginObject();
				writer.name("o").value(card.oracleId());
				writer.name("p").value(card.printingId());
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
				writer.name("r").value(card.rarity());
				writer.name("s").value(card.setCode());
				if (card.basic()) {
					writer.name("b").value(true);
				}
				if (!"normal".equals(card.treatment())) {
					writer.name("k").value(card.treatment());
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

	public static SetPool read(Path path) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(path));
			 JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			int version = 0;
			String set = "";
			List<String> queries = null;
			List<Printing> cards = null;
			reader.beginObject();
			while (reader.hasNext()) {
				switch (reader.nextName()) {
					case "version" -> version = reader.nextInt();
					case "set" -> set = reader.nextString();
					case "queries" -> queries = readQueries(reader);
					case "cards" -> cards = readCards(reader);
					default -> reader.skipValue();
				}
			}
			reader.endObject();
			if (version != VERSION || set.isEmpty() || queries == null || queries.isEmpty() || cards == null) {
				throw new IOException("Unrecognized set cache");
			}
			return new SetPool(set, cards, queries);
		}
	}

	public static SetPool from(String setCode, List<Printing> cards, List<String> queries) {
		return new SetPool(setCode, cards, queries);
	}

	private static List<String> readQueries(JsonReader reader) throws IOException {
		List<String> queries = new ArrayList<>();
		reader.beginArray();
		while (reader.hasNext()) {
			queries.add(reader.nextString());
		}
		reader.endArray();
		return queries;
	}

	private static List<Printing> readCards(JsonReader reader) throws IOException {
		List<Printing> cards = new ArrayList<>();
		reader.beginArray();
		while (reader.hasNext()) {
			String oracleId = "";
			String printingId = "";
			String name = "";
			String manaCost = "";
			double manaValue = 0;
			String typeLine = "";
			String colorIdentity = "";
			String rarity = "";
			String setCode = "";
			boolean basic = false;
			String treatment = "normal";
			reader.beginObject();
			while (reader.hasNext()) {
				switch (reader.nextName()) {
					case "o" -> oracleId = reader.nextString();
					case "p" -> printingId = reader.nextString();
					case "n" -> name = reader.nextString();
					case "m" -> manaCost = reader.nextString();
					case "v" -> manaValue = reader.nextDouble();
					case "t" -> typeLine = reader.nextString();
					case "c" -> colorIdentity = reader.nextString();
					case "r" -> rarity = reader.nextString();
					case "s" -> setCode = reader.nextString();
					case "b" -> basic = reader.nextBoolean();
					case "k" -> treatment = reader.nextString();
					default -> reader.skipValue();
				}
			}
			reader.endObject();
			if (!oracleId.isEmpty() && !printingId.isEmpty() && !name.isEmpty()) {
				cards.add(new Printing(oracleId, printingId, name, manaCost, manaValue, typeLine, colorIdentity, rarity, setCode, basic, treatment));
			}
		}
		reader.endArray();
		return cards;
	}
}
