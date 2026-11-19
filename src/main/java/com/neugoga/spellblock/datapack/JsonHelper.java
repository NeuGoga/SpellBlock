package com.neugoga.spellblock.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tolerant JSON utilities for datapack configs: supports trailing commas, // and /* * / comments,
 * and safe typed accessors so minor formatting slips warn cleanly without crashing the server.
 */
public final class JsonHelper {
	private JsonHelper() {
	}

	public static JsonObject parseObject(String raw) {
		if (raw == null || raw.isBlank()) {
			return new JsonObject();
		}
		String cleaned = stripTrailingCommas(raw);
		JsonReader reader = new JsonReader(new StringReader(cleaned));
		reader.setLenient(true);
		JsonElement parsed = JsonParser.parseReader(reader);
		if (parsed == null || parsed.isJsonNull()) {
			return new JsonObject();
		}
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("Expected a JSON object");
		}
		return parsed.getAsJsonObject();
	}

	/**
	 * Strips trailing commas immediately preceding `}` or `]` outside quoted string literals
	 * (and ignores `//` and `/* * /` comments when scanning).
	 */
	public static String stripTrailingCommas(String input) {
		if (input == null || input.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(input.length());
		int len = input.length();
		boolean inString = false;
		boolean escaped = false;
		for (int i = 0; i < len; i++) {
			char c = input.charAt(i);
			if (inString) {
				out.append(c);
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
				out.append(c);
				continue;
			}
			if (c == '/' && i + 1 < len) {
				char next = input.charAt(i + 1);
				if (next == '/') {
					while (i < len && input.charAt(i) != '\n') {
						i++;
					}
					if (i < len) {
						out.append('\n');
					}
					continue;
				}
				if (next == '*') {
					i += 2;
					while (i + 1 < len && !(input.charAt(i) == '*' && input.charAt(i + 1) == '/')) {
						i++;
					}
					i++;
					continue;
				}
			}
			if (c == ',') {
				int j = i + 1;
				while (j < len) {
					char look = input.charAt(j);
					if (Character.isWhitespace(look)) {
						j++;
						continue;
					}
					if (look == '/' && j + 1 < len && input.charAt(j + 1) == '/') {
						while (j < len && input.charAt(j) != '\n') {
							j++;
						}
						continue;
					}
					if (look == '/' && j + 1 < len && input.charAt(j + 1) == '*') {
						j += 2;
						while (j + 1 < len && !(input.charAt(j) == '*' && input.charAt(j + 1) == '/')) {
							j++;
						}
						j += 2;
						continue;
					}
					break;
				}
				if (j < len && (input.charAt(j) == '}' || input.charAt(j) == ']')) {
					continue;
				}
			}
			out.append(c);
		}
		return out.toString();
	}

	public static boolean bool(JsonObject obj, String key, boolean fallback) {
		if (obj == null || !obj.has(key)) {
			return fallback;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return el.getAsBoolean();
		} catch (Exception e) {
			return fallback;
		}
	}

	public static int intVal(JsonObject obj, String key, int fallback) {
		if (obj == null || !obj.has(key)) {
			return fallback;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return el.getAsInt();
		} catch (Exception e) {
			return fallback;
		}
	}

	public static double doubleVal(JsonObject obj, String key, double fallback) {
		if (obj == null || !obj.has(key)) {
			return fallback;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
			return fallback;
		}
		try {
			double v = el.getAsDouble();
			return Double.isFinite(v) ? v : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	public static String str(JsonObject obj, String key, String fallback) {
		if (obj == null || !obj.has(key)) {
			return fallback;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
			return fallback;
		}
		try {
			return el.getAsString().trim();
		} catch (Exception e) {
			return fallback;
		}
	}

	public static List<String> stringList(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
			return List.of();
		}
		JsonArray arr = obj.getAsJsonArray(key);
		Set<String> seen = new LinkedHashSet<>();
		for (JsonElement el : arr) {
			if (el != null && el.isJsonPrimitive()) {
				String s = el.getAsString().trim();
				if (!s.isEmpty()) {
					seen.add(s);
				}
			}
		}
		return List.copyOf(seen);
	}

	public static Map<String, Integer> stringIntMap(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
			return Map.of();
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(key).entrySet()) {
			JsonElement val = e.getValue();
			if (val != null && val.isJsonPrimitive()) {
				try {
					out.put(e.getKey().trim().toLowerCase(Locale.ROOT), val.getAsInt());
				} catch (Exception ignored) {
				}
			}
		}
		return Map.copyOf(out);
	}

	public static Map<String, Double> stringDoubleMap(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
			return Map.of();
		}
		Map<String, Double> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(key).entrySet()) {
			JsonElement val = e.getValue();
			if (val != null && val.isJsonPrimitive()) {
				try {
					double v = val.getAsDouble();
					if (Double.isFinite(v)) {
						out.put(e.getKey().trim().toLowerCase(Locale.ROOT), v);
					}
				} catch (Exception ignored) {
				}
			}
		}
		return Map.copyOf(out);
	}

	public static Map<String, Boolean> stringBoolMap(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonObject()) {
			return Map.of();
		}
		Map<String, Boolean> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(key).entrySet()) {
			JsonElement val = e.getValue();
			if (val != null && val.isJsonPrimitive()) {
				try {
					out.put(e.getKey().trim().toLowerCase(Locale.ROOT), val.getAsBoolean());
				} catch (Exception ignored) {
				}
			}
		}
		return Map.copyOf(out);
	}

	public static List<JsonObject> objectList(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
			return List.of();
		}
		List<JsonObject> out = new ArrayList<>();
		for (JsonElement el : obj.getAsJsonArray(key)) {
			if (el != null && el.isJsonObject()) {
				out.add(el.getAsJsonObject());
			}
		}
		return out;
	}
}
