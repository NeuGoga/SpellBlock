package com.neugoga.spellblock.catalog;

import java.util.Locale;

/** Compact deck-building flags parsed from a card's type line and oracle text. */
public final class CardRules {
	private CardRules() {
	}

	public static String parse(String typeLine, String oracleText) {
		String type = typeLine == null ? "" : typeLine.toLowerCase(Locale.ROOT);
		String raw = oracleText == null ? "" : oracleText;
		String text = raw.toLowerCase(Locale.ROOT);
		StringBuilder rules = new StringBuilder();
		if ((type.contains("legendary") && type.contains("creature")) || text.contains("can be your commander")) {
			add(rules, "cmd");
		}
		if (type.contains("time lord") && type.contains("doctor")) {
			add(rules, "doctor");
		}
		if (text.contains("choose a background")) {
			add(rules, "backgrounds");
		}
		if (type.contains("background")) {
			add(rules, "background");
		}
		if (text.contains("doctor's companion") || text.contains("doctor’s companion")) {
			add(rules, "companion");
		}
		if (text.contains("friends forever")) {
			add(rules, "friends");
		}
		int withAt = text.indexOf("partner with ");
		if (withAt >= 0) {
			String name = raw.substring(Math.min(raw.length(), withAt + "partner with ".length()));
			int end = name.length();
			for (int i = 0; i < name.length(); i++) {
				char c = name.charAt(i);
				if (c == '\n' || c == '(' || c == '.') {
					end = i;
					break;
				}
			}
			name = name.substring(0, end).replace('|', ' ').trim();
			if (!name.isEmpty()) {
				if (name.length() > 80) {
					name = name.substring(0, 80).trim();
				}
				add(rules, "with:" + name);
			}
		} else if (text.startsWith("partner") || text.contains("\npartner")) {
			add(rules, "partner");
		}
		if (text.contains("a deck can have any number of cards named")) {
			add(rules, "any");
		} else {
			int upTo = text.indexOf("a deck can have up to ");
			if (upTo >= 0) {
				int max = wordNumber(text.substring(upTo + "a deck can have up to ".length()));
				if (max > 1 && max <= 40) {
					add(rules, "max:" + max);
				}
			}
		}
		return rules.toString();
	}

	public static boolean has(String rules, String token) {
		if (rules == null || rules.isEmpty() || token == null || token.isEmpty()) {
			return false;
		}
		int start = 0;
		while (start <= rules.length()) {
			int bar = rules.indexOf('|', start);
			if (bar < 0) {
				bar = rules.length();
			}
			if (rules.substring(start, bar).equals(token)) {
				return true;
			}
			if (bar == rules.length()) {
				return false;
			}
			start = bar + 1;
		}
		return false;
	}

	public static String partnerWith(String rules) {
		return value(rules, "with:");
	}

	public static int statedMax(String rules) {
		String value = value(rules, "max:");
		if (value.isEmpty()) {
			return 0;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String value(String rules, String prefix) {
		if (rules == null || rules.isEmpty()) {
			return "";
		}
		int start = 0;
		while (start <= rules.length()) {
			int bar = rules.indexOf('|', start);
			if (bar < 0) {
				bar = rules.length();
			}
			String part = rules.substring(start, bar);
			if (part.startsWith(prefix)) {
				return part.substring(prefix.length());
			}
			if (bar == rules.length()) {
				return "";
			}
			start = bar + 1;
		}
		return "";
	}

	private static void add(StringBuilder rules, String token) {
		if (!rules.isEmpty()) {
			rules.append('|');
		}
		rules.append(token);
	}

	private static int wordNumber(String tail) {
		int end = 0;
		while (end < tail.length() && Character.isLetterOrDigit(tail.charAt(end))) {
			end++;
		}
		if (end == 0) {
			return 0;
		}
		String word = tail.substring(0, end);
		if (Character.isDigit(word.charAt(0))) {
			try {
				return Integer.parseInt(word);
			} catch (NumberFormatException e) {
				return 0;
			}
		}
		return switch (word) {
			case "one" -> 1;
			case "two" -> 2;
			case "three" -> 3;
			case "four" -> 4;
			case "five" -> 5;
			case "six" -> 6;
			case "seven" -> 7;
			case "eight" -> 8;
			case "nine" -> 9;
			case "ten" -> 10;
			case "eleven" -> 11;
			case "twelve" -> 12;
			case "thirteen" -> 13;
			case "fourteen" -> 14;
			case "fifteen" -> 15;
			case "twenty" -> 20;
			default -> 0;
		};
	}
}
