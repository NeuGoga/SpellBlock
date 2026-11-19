package com.neugoga.spellblock.library;

import com.neugoga.spellblock.catalog.CardRules;
import com.neugoga.spellblock.catalog.OracleCard;
import com.neugoga.spellblock.catalog.OracleIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Format checks. An illegal deck is still saved; these strings are only the report. */
public final class DeckRules {
	public static final int MAX_ISSUES = 12;

	private DeckRules() {
	}

	public static List<String> check(OracleIndex index, DeckBoxData deck) {
		ArrayList<String> issues = new ArrayList<>();
		if (index == null) {
			issues.add("The card index is still building.");
			return issues;
		}
		DeckFormat format = DeckFormat.fromId(deck.format());
		boolean knowLegalities = index.hasLegalityData();
		if (!knowLegalities) {
			issues.add("Format data is missing. Run /spellblock reindex.");
		}
		List<OracleCard> commanders = new ArrayList<>();
		for (DeckEntry entry : deck.commanders()) {
			OracleCard card = index.findByOracleId(entry.oracleId());
			if (card == null) {
				continue;
			}
			commanders.add(card);
		}
		if (format.commander()) {
			if (commanders.isEmpty() && deck.commanders().isEmpty()) {
				issues.add("Needs a commander.");
			} else if (format == DeckFormat.PAUPER_COMMANDER) {
				// Pauper Commander: any uncommon creature (not only legendary).
				for (OracleCard card : commanders) {
					String type = card.typeLine() == null ? "" : card.typeLine().toLowerCase(Locale.ROOT);
					String rarity = card.rarity() == null ? "" : card.rarity().toLowerCase(Locale.ROOT);
					if (!type.contains("creature") || !"uncommon".equals(rarity)) {
						issues.add(card.name() + " can't be your Pauper Commander.");
					}
				}
				if (commanders.size() >= 2 && !partnered(commanders.get(0), commanders.get(1))) {
					issues.add("These commanders can't go together.");
				}
			} else if (commanders.size() == 1 && !CardRules.has(commanders.get(0).rules(), "cmd")) {
				issues.add(commanders.get(0).name() + " can't be your commander.");
			} else if (commanders.size() >= 2 && !partnered(commanders.get(0), commanders.get(1))) {
				issues.add("These commanders can't go together.");
			}
		} else if (!deck.commanders().isEmpty()) {
			issues.add("This format has no commander.");
		}

		int required = format.commander() ? mainRequired(deck.commanders().size()) : 60;
		int mainCount = DeckBoxData.copies(deck.main());
		if (format.commander()) {
			if (mainCount != required) {
				issues.add("Main deck has " + mainCount + " cards (" + required + " required).");
			}
		} else if (mainCount < required) {
			issues.add("Main deck has " + mainCount + " cards (" + required + " required).");
		}
		int sideCount = DeckBoxData.copies(deck.side());
		if (format.sideboard()) {
			if (sideCount > 15) {
				issues.add("Sideboard has " + sideCount + " cards (15 max).");
			}
		} else if (sideCount > 0) {
			issues.add("This format has no sideboard (" + sideCount + " cards).");
		}

		String allowed = "";
		if (format.commander() && !commanders.isEmpty()) {
			allowed = commanders.get(0).colorIdentity();
			if (commanders.size() >= 2) {
				allowed = union(allowed, commanders.get(1).colorIdentity());
			}
		}

		Map<String, Integer> copies = new LinkedHashMap<>();
		Map<String, OracleCard> cards = new LinkedHashMap<>();
		count(index, deck.commanders(), copies, cards);
		count(index, deck.main(), copies, cards);
		count(index, deck.side(), copies, cards);
		int unknown = countMissing(index, deck.commanders()) + countMissing(index, deck.main()) + countMissing(index, deck.side());
		if (unknown > 0) {
			issues.add(unknown + (unknown == 1 ? " card is missing from the index." : " cards are missing from the index."));
		}

		for (Map.Entry<String, OracleCard> entry : cards.entrySet()) {
			OracleCard card = entry.getValue();
			if (format.commander() && !commanders.isEmpty() && !isCommander(commanders, card)) {
				if (!within(card.colorIdentity(), allowed)) {
					issues.add("Outside the commander's colors: " + card.name());
				}
			}
			String legality = knowLegalities ? legality(card.legalities(), format.id()) : "legal";
			if (knowLegalities && "banned".equals(legality)) {
				issues.add("Banned in " + format.label() + ": " + card.name());
			} else if (knowLegalities && !"legal".equals(legality) && !"restricted".equals(legality)) {
				issues.add("Not legal in " + format.label() + ": " + card.name());
			}
			int has = copies.getOrDefault(entry.getKey(), 0);
			int limit = copyLimit(card, format, legality);
			if (limit > 0 && has > limit) {
				issues.add(limit + (limit == 1 ? " copy allowed, has " : " copies allowed, has ") + has + ": " + card.name());
			}
		}
		if (issues.size() > MAX_ISSUES) {
			int extra = issues.size() - MAX_ISSUES;
			issues.subList(MAX_ISSUES, issues.size()).clear();
			issues.add("+" + extra + " more.");
		}
		return List.copyOf(issues);
	}

	private static int mainRequired(int commanders) {
		if (commanders <= 0) {
			return 99;
		}
		if (commanders == 1) {
			return 99;
		}
		return 98;
	}

	private static void count(OracleIndex index, List<DeckEntry> entries, Map<String, Integer> copies, Map<String, OracleCard> cards) {
		for (DeckEntry entry : entries) {
			OracleCard card = index.findByOracleId(entry.oracleId());
			if (card == null) {
				continue;
			}
			copies.merge(card.oracleId(), entry.count(), Integer::sum);
			cards.putIfAbsent(card.oracleId(), card);
		}
	}

	private static int countMissing(OracleIndex index, List<DeckEntry> entries) {
		int missing = 0;
		for (DeckEntry entry : entries) {
			if (index.findByOracleId(entry.oracleId()) == null) {
				missing += entry.count();
			}
		}
		return missing;
	}

	private static boolean isCommander(List<OracleCard> commanders, OracleCard card) {
		for (OracleCard commander : commanders) {
			if (commander.oracleId().equals(card.oracleId())) {
				return true;
			}
		}
		return false;
	}

	private static boolean partnered(OracleCard a, OracleCard b) {
		String left = a.rules();
		String right = b.rules();
		if (CardRules.has(left, "partner") && CardRules.has(right, "partner")) {
			return true;
		}
		if (CardRules.has(left, "friends") && CardRules.has(right, "friends")) {
			return true;
		}
		if (CardRules.has(left, "backgrounds") && CardRules.has(right, "background")) {
			return true;
		}
		if (CardRules.has(right, "backgrounds") && CardRules.has(left, "background")) {
			return true;
		}
		if (CardRules.has(left, "doctor") && CardRules.has(right, "companion")) {
			return true;
		}
		if (CardRules.has(right, "doctor") && CardRules.has(left, "companion")) {
			return true;
		}
		return namesMatch(CardRules.partnerWith(left), b.name()) && namesMatch(CardRules.partnerWith(right), a.name());
	}

	private static boolean namesMatch(String wanted, String cardName) {
		if (wanted == null || wanted.isEmpty() || cardName == null || cardName.isEmpty()) {
			return false;
		}
		if (wanted.equalsIgnoreCase(cardName)) {
			return true;
		}
		int split = cardName.indexOf(" // ");
		return split > 0 && wanted.equalsIgnoreCase(cardName.substring(0, split));
	}

	private static String union(String left, String right) {
		StringBuilder out = new StringBuilder(left == null ? "" : left);
		if (right != null) {
			for (int i = 0; i < right.length(); i++) {
				char c = right.charAt(i);
				if (out.indexOf(String.valueOf(c)) < 0) {
					out.append(c);
				}
			}
		}
		return out.toString();
	}

	private static boolean within(String identity, String allowed) {
		if (identity == null || identity.isEmpty()) {
			return true;
		}
		String have = allowed == null ? "" : allowed;
		for (int i = 0; i < identity.length(); i++) {
			if (have.indexOf(identity.charAt(i)) < 0) {
				return false;
			}
		}
		return true;
	}

	static String legality(String encoded, String format) {
		if (encoded == null || encoded.isEmpty() || format == null || format.isEmpty()) {
			return "not_legal";
		}
		String needle = format + "=";
		int start = 0;
		while (start < encoded.length()) {
			int comma = encoded.indexOf(',', start);
			if (comma < 0) {
				comma = encoded.length();
			}
			String part = encoded.substring(start, comma);
			if (part.startsWith(needle)) {
				return part.substring(needle.length());
			}
			start = comma + 1;
		}
		return "not_legal";
	}

	private static int copyLimit(OracleCard card, DeckFormat format, String legality) {
		if (isBasicLand(card.typeLine()) || CardRules.has(card.rules(), "any")) {
			return 0;
		}
		if (format == DeckFormat.VINTAGE && "restricted".equals(legality)) {
			return 1;
		}
		int stated = CardRules.statedMax(card.rules());
		if (stated > 0) {
			return stated;
		}
		return format.commander() ? 1 : 4;
	}

	private static boolean isBasicLand(String typeLine) {
		if (typeLine == null) {
			return false;
		}
		String type = typeLine.toLowerCase(Locale.ROOT);
		return type.contains("basic") && type.contains("land");
	}
}
