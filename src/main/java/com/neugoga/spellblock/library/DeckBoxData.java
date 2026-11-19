package com.neugoga.spellblock.library;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** The cards inside one deck box. This lives on the item, not in the player's binder. */
public record DeckBoxData(
	String name,
	String format,
	boolean locked,
	String owner,
	List<DeckEntry> commanders,
	List<DeckEntry> main,
	List<DeckEntry> side
) {
	public static final int MAX_LINES = 300;
	public static final int MAX_CARDS = 500;
	public static final int MAX_NAME = 32;

	public static final Codec<DeckBoxData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.optionalFieldOf("name", "").forGetter(DeckBoxData::name),
		Codec.STRING.optionalFieldOf("format", DeckFormat.COMMANDER.id()).forGetter(DeckBoxData::format),
		Codec.BOOL.optionalFieldOf("locked", false).forGetter(DeckBoxData::locked),
		Codec.STRING.optionalFieldOf("owner", "").forGetter(DeckBoxData::owner),
		DeckEntry.CODEC.listOf().optionalFieldOf("commanders", List.of()).forGetter(DeckBoxData::commanders),
		DeckEntry.CODEC.listOf().optionalFieldOf("main", List.of()).forGetter(DeckBoxData::main),
		DeckEntry.CODEC.listOf().optionalFieldOf("side", List.of()).forGetter(DeckBoxData::side)
	).apply(instance, DeckBoxData::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, DeckBoxData> STREAM_CODEC = StreamCodec.of(
		DeckBoxData::write,
		DeckBoxData::read
	);

	public DeckBoxData {
		name = cleanName(name);
		format = DeckFormat.fromId(format).id();
		owner = cleanOwner(owner);
		commanders = clean(commanders, 2);
		main = clean(main, MAX_LINES);
		side = clean(side, MAX_LINES);
		if (!locked || owner.isEmpty()) {
			locked = false;
			owner = "";
		}
	}

	public static DeckBoxData empty() {
		return new DeckBoxData("", DeckFormat.COMMANDER.id(), false, "", List.of(), List.of(), List.of());
	}

	public int total() {
		return copies(commanders) + copies(main) + copies(side);
	}

	/** Cards that sit in the library (mainboard only). Sideboard and commanders are separate. */
	public int librarySize() {
		return copies(main);
	}

	public DeckBoxData withName(String name) {
		return new DeckBoxData(name, format, locked, owner, commanders, main, side);
	}

	public DeckBoxData withFormat(String format) {
		return new DeckBoxData(name, format, locked, owner, commanders, main, side);
	}

	public DeckBoxData lockedBy(String owner) {
		return new DeckBoxData(name, format, true, owner, commanders, main, side);
	}

	public DeckBoxData unlocked() {
		return new DeckBoxData(name, format, false, "", commanders, main, side);
	}

	public DeckBoxData fillPrintings(Function<String, String> printingForOracle) {
		List<DeckEntry> nextCommanders = fillZone(commanders, printingForOracle);
		List<DeckEntry> nextMain = fillZone(main, printingForOracle);
		List<DeckEntry> nextSide = fillZone(side, printingForOracle);
		if (nextCommanders == commanders && nextMain == main && nextSide == side) {
			return this;
		}
		return new DeckBoxData(name, format, locked, owner, nextCommanders, nextMain, nextSide);
	}

	public DeckBoxData add(Zone zone, CardKey key) {
		if (zone == Zone.SIDE) {
			return new DeckBoxData(name, format, locked, owner, commanders, main, addOne(side, key));
		}
		return new DeckBoxData(name, format, locked, owner, commanders, addOne(main, key), side);
	}

	public DeckBoxData move(Zone from, CardKey key) {
		Zone to = from == Zone.SIDE ? Zone.MAIN : Zone.SIDE;
		List<DeckEntry> destination = to == Zone.SIDE ? side : main;
		boolean stacked = false;
		for (DeckEntry entry : destination) {
			if (!entry.same(key)) {
				continue;
			}
			stacked = true;
			if (entry.count() >= DeckEntry.MAX_COUNT) {
				return null;
			}
			break;
		}
		if (!stacked && destination.size() >= MAX_LINES) {
			return null;
		}
		DeckBoxData removed = remove(from, key);
		if (removed == null) {
			return null;
		}
		return removed.add(to, key);
	}

	public DeckBoxData remove(Zone zone, CardKey key) {
		if (zone == Zone.SIDE) {
			List<DeckEntry> next = removeOne(side, key);
			return next == null ? null : new DeckBoxData(name, format, locked, owner, commanders, main, next);
		}
		List<DeckEntry> next = removeOne(main, key);
		return next == null ? null : new DeckBoxData(name, format, locked, owner, commanders, next, side);
	}

	public ReplaceCommander putCommander(int slot, CardKey key) {
		if (slot < 0) {
			slot = 0;
		}
		if (slot > 1) {
			slot = 1;
		}
		ArrayList<DeckEntry> next = new ArrayList<>(commanders);
		if (slot == 1 && next.isEmpty()) {
			slot = 0;
		}
		DeckEntry previous = slot < next.size() ? next.get(slot) : null;
		DeckEntry entry = new DeckEntry(key.oracleId(), key.printingId(), key.finish().name(), 1);
		if (slot == next.size()) {
			next.add(entry);
		} else {
			next.set(slot, entry);
		}
		DeckBoxData data = new DeckBoxData(name, format, locked, owner, next, main, side);
		return new ReplaceCommander(data, previous);
	}

	public ReplaceCommander clearCommander(int slot) {
		if (slot < 0 || slot >= commanders.size()) {
			return new ReplaceCommander(this, null);
		}
		ArrayList<DeckEntry> next = new ArrayList<>(commanders);
		DeckEntry previous = next.remove(slot);
		return new ReplaceCommander(new DeckBoxData(name, format, locked, owner, next, main, side), previous);
	}

	public Draw drawTop() {
		if (main.isEmpty()) {
			return null;
		}
		return new Draw(new DeckBoxData(name, format, locked, owner, commanders, removeFirst(main), side), main.get(0).key());
	}

	/** Take the top N library cards from the mainboard only. */
	public TakeN takeTopN(int n) {
		if (n <= 0) {
			return new TakeN(this, List.of());
		}
		DeckBoxData data = this;
		ArrayList<CardKey> keys = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Draw drawn = data.drawTop();
			if (drawn == null) {
				break;
			}
			data = drawn.data();
			keys.add(drawn.card());
		}
		return new TakeN(data, List.copyOf(keys));
	}

	public DeckBoxData place(CardKey key, boolean onTop) {
		if (key == null || key.oracleId().isEmpty()) {
			return this;
		}
		ArrayList<DeckEntry> next = new ArrayList<>(main);
		int edge = onTop ? 0 : next.size() - 1;
		if (!next.isEmpty() && next.get(edge).same(key) && next.get(edge).count() < DeckEntry.MAX_COUNT) {
			DeckEntry edgeEntry = next.get(edge);
			next.set(edge, edgeEntry.withCount(edgeEntry.count() + 1));
			return new DeckBoxData(name, format, locked, owner, commanders, next, side);
		}
		if (next.size() >= MAX_LINES) {
			return null;
		}
		DeckEntry line = new DeckEntry(key.oracleId(), key.printingId(), key.finish().name(), 1);
		if (onTop) {
			next.add(0, line);
		} else {
			next.add(line);
		}
		return new DeckBoxData(name, format, locked, owner, commanders, next, side);
	}

	/** Put a card Nth from the top (1 = top, 2 = second, …). Free-play library order. */
	public DeckBoxData placeAt(CardKey key, int fromTop) {
		if (key == null || key.oracleId().isEmpty()) {
			return this;
		}
		if (fromTop <= 1) {
			return place(key, true);
		}
		if (main.size() >= MAX_LINES) {
			return null;
		}
		ArrayList<DeckEntry> next = new ArrayList<>(main);
		int index = Math.min(Math.max(0, fromTop - 1), next.size());
		next.add(index, new DeckEntry(key.oracleId(), key.printingId(), key.finish().name(), 1));
		return new DeckBoxData(name, format, locked, owner, commanders, next, side);
	}

	/** Shuffles the mainboard. That is the library you draw. The sideboard stays a sideboard. */
	public DeckBoxData shuffled(RandomSource random) {
		int total = copies(main);
		if (total < 2 || random == null) {
			return this;
		}
		ArrayList<DeckEntry> flat = new ArrayList<>(total);
		for (DeckEntry entry : main) {
			for (int i = 0; i < entry.count(); i++) {
				flat.add(entry.withCount(1));
			}
		}
		for (int i = flat.size() - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			DeckEntry swap = flat.get(i);
			flat.set(i, flat.get(j));
			flat.set(j, swap);
		}
		ArrayList<DeckEntry> next = new ArrayList<>();
		for (DeckEntry card : flat) {
			if (!next.isEmpty()) {
				DeckEntry last = next.get(next.size() - 1);
				if (last.same(card.key()) && last.count() < DeckEntry.MAX_COUNT) {
					next.set(next.size() - 1, last.withCount(last.count() + 1));
					continue;
				}
			}
			if (next.size() >= MAX_LINES) {
				return this;
			}
			next.add(card);
		}
		return new DeckBoxData(name, format, locked, owner, commanders, List.copyOf(next), side);
	}

	private static List<DeckEntry> removeFirst(List<DeckEntry> entries) {
		DeckEntry first = entries.get(0);
		ArrayList<DeckEntry> next = new ArrayList<>(entries);
		if (first.count() <= 1) {
			next.remove(0);
		} else {
			next.set(0, first.withCount(first.count() - 1));
		}
		return List.copyOf(next);
	}

	public static int copies(List<DeckEntry> entries) {
		int total = 0;
		for (DeckEntry entry : entries) {
			total += entry.count();
		}
		return total;
	}

	private static List<DeckEntry> fillZone(List<DeckEntry> entries, Function<String, String> printingForOracle) {
		ArrayList<DeckEntry> next = new ArrayList<>();
		boolean changed = false;
		for (DeckEntry entry : entries) {
			String printing = entry.printingId();
			if (printing.isEmpty()) {
				String found = printingForOracle.apply(entry.oracleId());
				if (found != null && !found.isEmpty()) {
					printing = found;
					changed = true;
				}
			}
			DeckEntry updated = printing.equals(entry.printingId())
				? entry
				: new DeckEntry(entry.oracleId(), printing, entry.finish(), entry.count());
			boolean stacked = false;
			for (int i = 0; i < next.size(); i++) {
				if (!next.get(i).same(updated.key())) {
					continue;
				}
				next.set(i, next.get(i).withCount(next.get(i).count() + updated.count()));
				stacked = true;
				changed = true;
				break;
			}
			if (!stacked) {
				next.add(updated);
			}
		}
		return changed ? List.copyOf(next) : entries;
	}

	private static List<DeckEntry> addOne(List<DeckEntry> entries, CardKey key) {
		ArrayList<DeckEntry> next = new ArrayList<>(entries);
		for (int i = 0; i < next.size(); i++) {
			DeckEntry entry = next.get(i);
			if (entry.same(key)) {
				next.set(i, entry.withCount(entry.count() + 1));
				return List.copyOf(next);
			}
		}
		next.add(new DeckEntry(key.oracleId(), key.printingId(), key.finish().name(), 1));
		return List.copyOf(next);
	}

	private static List<DeckEntry> removeOne(List<DeckEntry> entries, CardKey key) {
		ArrayList<DeckEntry> next = new ArrayList<>(entries);
		for (int i = 0; i < next.size(); i++) {
			DeckEntry entry = next.get(i);
			if (!entry.same(key)) {
				continue;
			}
			if (entry.count() <= 1) {
				next.remove(i);
			} else {
				next.set(i, entry.withCount(entry.count() - 1));
			}
			return List.copyOf(next);
		}
		return null;
	}

	private static List<DeckEntry> clean(List<DeckEntry> entries, int max) {
		if (entries == null || entries.isEmpty()) {
			return List.of();
		}
		ArrayList<DeckEntry> next = new ArrayList<>();
		for (DeckEntry entry : entries) {
			if (entry == null || entry.oracleId().isEmpty() || entry.count() <= 0) {
				continue;
			}
			next.add(entry);
			if (next.size() >= max) {
				break;
			}
		}
		return List.copyOf(next);
	}

	private static String cleanName(String name) {
		if (name == null || name.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(Math.min(name.length(), MAX_NAME));
		for (int i = 0; i < name.length() && out.length() < MAX_NAME; i++) {
			char c = name.charAt(i);
			if (c >= 32 && c != 127) {
				out.append(c);
			}
		}
		return out.toString().trim();
	}

	private static String cleanOwner(String owner) {
		if (owner == null || owner.isEmpty()) {
			return "";
		}
		try {
			return UUID.fromString(owner).toString();
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	private static void write(RegistryFriendlyByteBuf buf, DeckBoxData data) {
		buf.writeUtf(data.name, MAX_NAME * 4);
		buf.writeUtf(data.format, 32);
		buf.writeBoolean(data.locked);
		buf.writeUtf(data.owner, 40);
		writeList(buf, data.commanders);
		writeList(buf, data.main);
		writeList(buf, data.side);
	}

	private static DeckBoxData read(RegistryFriendlyByteBuf buf) {
		return new DeckBoxData(
			buf.readUtf(),
			buf.readUtf(),
			buf.readBoolean(),
			buf.readUtf(),
			readList(buf),
			readList(buf),
			readList(buf)
		);
	}

	private static void writeList(RegistryFriendlyByteBuf buf, List<DeckEntry> entries) {
		buf.writeVarInt(entries.size());
		for (DeckEntry entry : entries) {
			buf.writeUtf(entry.oracleId(), 80);
			buf.writeUtf(entry.printingId(), 80);
			buf.writeUtf(entry.finish(), 16);
			buf.writeVarInt(entry.count());
		}
	}

	private static List<DeckEntry> readList(RegistryFriendlyByteBuf buf) {
		int size = buf.readVarInt();
		if (size < 0 || size > MAX_LINES) {
			throw new IllegalArgumentException("deck list " + size);
		}
		ArrayList<DeckEntry> entries = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			entries.add(new DeckEntry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt()));
		}
		return entries;
	}

	public record ReplaceCommander(DeckBoxData data, DeckEntry previous) {
	}

	public record Draw(DeckBoxData data, CardKey card) {
	}

	public record TakeN(DeckBoxData data, List<CardKey> cards) {
	}

	public enum Zone {
		MAIN,
		SIDE
	}
}
