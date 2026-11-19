package com.neugoga.spellblock.library;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** One stacked printing inside a deck box. Foil and nonfoil are different rows. */
public record DeckEntry(String oracleId, String printingId, String finish, int count) {
	public static final int MAX_COUNT = 400;
	public static final Codec<DeckEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("oracle").forGetter(DeckEntry::oracleId),
		Codec.STRING.optionalFieldOf("printing", "").forGetter(DeckEntry::printingId),
		Codec.STRING.optionalFieldOf("finish", "NONFOIL").forGetter(DeckEntry::finish),
		Codec.INT.optionalFieldOf("count", 1).forGetter(DeckEntry::count)
	).apply(instance, DeckEntry::new));

	public DeckEntry {
		oracleId = oracleId == null ? "" : oracleId;
		printingId = printingId == null ? "" : printingId;
		finish = Finish.fromName(finish).name();
		if (count < 1) {
			count = 1;
		} else if (count > MAX_COUNT) {
			count = MAX_COUNT;
		}
		if (oracleId.length() > 80) {
			oracleId = oracleId.substring(0, 80);
		}
		if (printingId.length() > 80) {
			printingId = printingId.substring(0, 80);
		}
	}

	public CardKey key() {
		return new CardKey(oracleId, printingId, Finish.fromName(finish));
	}

	public boolean same(CardKey key) {
		return oracleId.equals(key.oracleId())
			&& printingId.equals(key.printingId())
			&& finish.equals(key.finish().name());
	}

	public DeckEntry withCount(int count) {
		return new DeckEntry(oracleId, printingId, finish, count);
	}
}
