package com.neugoga.spellblock.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** One player's PC. Counts, not slots. Server thread only. */
public final class PlayerLibrary {
	private final Map<CardKey, Integer> cards = new HashMap<>();
	private final Set<CardKey> favourites = new HashSet<>();
	private int dust;

	public void add(CardKey key, int count) {
		if (count == 0) {
			return;
		}
		int next = cards.getOrDefault(key, 0) + count;
		if (next <= 0) {
			cards.remove(key);
		} else {
			cards.put(key, next);
		}
	}

	public int count(CardKey key) {
		return cards.getOrDefault(key, 0);
	}

	public int uniqueCount() {
		return cards.size();
	}

	public int copyCount() {
		int total = 0;
		for (int count : cards.values()) {
			total += count;
		}
		return total;
	}

	public int dust() {
		return dust;
	}

	public void setDust(int dust) {
		this.dust = Math.max(0, dust);
	}

	public void addDust(int delta) {
		if (delta == 0) {
			return;
		}
		long next = (long) dust + delta;
		if (next < 0) {
			next = 0;
		}
		if (next > Integer.MAX_VALUE) {
			next = Integer.MAX_VALUE;
		}
		dust = (int) next;
	}

	public boolean isFavourite(CardKey key) {
		return key != null && favourites.contains(key);
	}

	public void setFavourite(CardKey key, boolean on) {
		if (key == null) {
			return;
		}
		if (on) {
			favourites.add(key);
		} else {
			favourites.remove(key);
		}
	}

	public void toggleFavourite(CardKey key) {
		if (key == null) {
			return;
		}
		if (!favourites.remove(key)) {
			favourites.add(key);
		}
	}

	public Set<CardKey> favourites() {
		return Collections.unmodifiableSet(favourites);
	}

	public Map<CardKey, Integer> cards() {
		return Collections.unmodifiableMap(cards);
	}

	/** Copies saved before a printing id existed move onto that card's default printing. */
	public boolean fillPrintings(Function<String, String> printingForOracle) {
		List<CardKey> bare = new ArrayList<>();
		for (CardKey key : cards.keySet()) {
			if (key.printingId().isEmpty()) {
				bare.add(key);
			}
		}
		boolean changed = false;
		for (CardKey key : bare) {
			String printing = printingForOracle.apply(key.oracleId());
			if (printing == null || printing.isEmpty()) {
				continue;
			}
			Integer count = cards.remove(key);
			if (count == null || count <= 0) {
				continue;
			}
			add(new CardKey(key.oracleId(), printing, key.finish()), count);
			changed = true;
		}
		return changed;
	}
}
