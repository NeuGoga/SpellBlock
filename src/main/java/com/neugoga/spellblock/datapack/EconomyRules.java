package com.neugoga.spellblock.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.library.Finish;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configurable Dustery disenchant & crafting economy loaded from
 * {@code data/spellblock/economy/dust.json} and {@code data/spellblock/economy/craft.json}.
 * <p>
 * Supports per-rarity base dust, global multiplier/percent, finish multipliers (nonfoil/foil/etched),
 * treatment multipliers (normal/retro/borderless/special_guest), per-card overrides by oracle ID or card name,
 * pinned-printing craft overrides, owned-only craft restriction, and finish/treatment craft permissions.
 */
public record EconomyRules(
	DustProfile dust,
	CraftProfile craft
) {
	public static final List<String> RARITIES = List.of("common", "uncommon", "rare", "mythic", "special", "bonus");
	public static final List<String> TREATMENTS = List.of("normal", "retro", "borderless", "special_guest");
	public static final List<String> FINISHES = List.of("NONFOIL", "FOIL", "ETCHED");

	public static final EconomyRules DEFAULTS = new EconomyRules(DustProfile.DEFAULTS, CraftProfile.DEFAULTS);

	public static final StreamCodec<FriendlyByteBuf, EconomyRules> CODEC = StreamCodec.of(
		(buf, rules) -> {
			DustProfile.CODEC.encode(buf, rules.dust);
			CraftProfile.CODEC.encode(buf, rules.craft);
		},
		buf -> new EconomyRules(
			DustProfile.CODEC.decode(buf),
			CraftProfile.CODEC.decode(buf)
		)
	);

	public EconomyRules {
		dust = dust == null ? DustProfile.DEFAULTS : dust;
		craft = craft == null ? CraftProfile.DEFAULTS : craft;
	}

	public int disenchant(String oracleId, String cardName, String rarity, String treatment, String finish) {
		return disenchant(oracleId, cardName, "", rarity, treatment, finish);
	}

	public int disenchant(String oracleId, String cardName, String setCode, String rarity, String treatment, String finish) {
		return dust.compute(oracleId, cardName, "", setCode, rarity, treatment, finish);
	}

	public int craftCost(String oracleId, String cardName, String printingId, String rarity, String treatment, String finish) {
		return craftCost(oracleId, cardName, printingId, "", rarity, treatment, finish);
	}

	public int craftCost(String oracleId, String cardName, String printingId, String setCode, String rarity, String treatment, String finish) {
		return craft.compute(oracleId, cardName, printingId, setCode, rarity, treatment, finish);
	}

	/** Per-card override entry: either scaled base cost or explicit per-finish/unscaled values. */
	public record CardOverride(
		int amount,
		boolean scale,
		int nonfoil,
		int foil,
		int etched
	) {
		public static final StreamCodec<FriendlyByteBuf, CardOverride> CODEC = StreamCodec.of(
			(buf, ov) -> {
				buf.writeVarInt(ov.amount);
				buf.writeBoolean(ov.scale);
				buf.writeVarInt(ov.nonfoil);
				buf.writeVarInt(ov.foil);
				buf.writeVarInt(ov.etched);
			},
			buf -> new CardOverride(
				buf.readVarInt(),
				buf.readBoolean(),
				buf.readVarInt(),
				buf.readVarInt(),
				buf.readVarInt()
			)
		);

		public CardOverride {
			amount = Math.max(-1, amount);
			nonfoil = Math.max(-1, nonfoil);
			foil = Math.max(-1, foil);
			etched = Math.max(-1, etched);
		}

		public static CardOverride scaled(int amount) {
			return new CardOverride(Math.max(0, amount), true, -1, -1, -1);
		}

		public static CardOverride flat(int amount) {
			return new CardOverride(Math.max(0, amount), false, -1, -1, -1);
		}

		public int finishOverride(Finish finish) {
			if (finish == null) {
				return nonfoil;
			}
			return switch (finish) {
				case NONFOIL -> nonfoil;
				case FOIL -> foil;
				case ETCHED -> etched;
			};
		}
	}

	public record CraftRecipe(
		String id,
		String oracleId,
		String name,
		String printingId,
		int cost,
		boolean scale
	) {
		public static final StreamCodec<FriendlyByteBuf, CraftRecipe> CODEC = StreamCodec.of(
			(buf, r) -> {
				buf.writeUtf(r.id);
				buf.writeUtf(r.oracleId);
				buf.writeUtf(r.name);
				buf.writeUtf(r.printingId);
				buf.writeVarInt(r.cost);
				buf.writeBoolean(r.scale);
			},
			buf -> new CraftRecipe(
				buf.readUtf(),
				buf.readUtf(),
				buf.readUtf(),
				buf.readUtf(),
				buf.readVarInt(),
				buf.readBoolean()
			)
		);

		public CraftRecipe {
			id = id == null ? "" : id.trim();
			oracleId = oracleId == null ? "" : oracleId.trim().toLowerCase(Locale.ROOT);
			name = name == null ? "" : OracleIndex.fold(name);
			printingId = printingId == null ? "" : printingId.trim().toLowerCase(Locale.ROOT);
			cost = Math.max(0, cost);
		}

		public boolean matches(String oracleId, String cardName, String printingId) {
			if (!this.printingId.isEmpty() && !this.printingId.equalsIgnoreCase(printingId == null ? "" : printingId)) {
				return false;
			}
			if (!this.oracleId.isEmpty() && this.oracleId.equalsIgnoreCase(oracleId == null ? "" : oracleId)) {
				return true;
			}
			if (!this.name.isEmpty() && cardName != null && !cardName.isEmpty() && this.name.equals(OracleIndex.fold(cardName))) {
				return true;
			}
			return !this.printingId.isEmpty() && this.oracleId.isEmpty() && this.name.isEmpty();
		}
	}

	public record DustProfile(
		boolean enabled,
		boolean allowDustAll,
		double multiplier,
		Map<String, Integer> rarityBase,
		Map<String, Double> finishMultipliers,
		Map<String, Double> treatmentMultipliers,
		Map<String, Double> setMultipliers,
		Map<String, CardOverride> overrides
	) {
		public static final DustProfile DEFAULTS = new DustProfile(
			true,
			true,
			1.0,
			Support.defaultDustRarities(),
			Support.defaultFinishMultipliers(),
			Support.defaultTreatmentMultipliers(),
			Map.of(),
			Map.of()
		);

		public static final StreamCodec<FriendlyByteBuf, DustProfile> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBoolean(p.enabled);
				buf.writeBoolean(p.allowDustAll);
				buf.writeDouble(p.multiplier);
				Support.writeIntMap(buf, p.rarityBase);
				Support.writeDoubleMap(buf, p.finishMultipliers);
				Support.writeDoubleMap(buf, p.treatmentMultipliers);
				Support.writeDoubleMap(buf, p.setMultipliers);
				Support.writeOverrideMap(buf, p.overrides);
			},
			buf -> new DustProfile(
				buf.readBoolean(),
				buf.readBoolean(),
				buf.readDouble(),
				Support.readIntMap(buf),
				Support.readDoubleMap(buf),
				Support.readDoubleMap(buf),
				Support.readDoubleMap(buf),
				Support.readOverrideMap(buf)
			)
		);

		public DustProfile(
			boolean enabled,
			boolean allowDustAll,
			double multiplier,
			Map<String, Integer> rarityBase,
			Map<String, Double> finishMultipliers,
			Map<String, Double> treatmentMultipliers,
			Map<String, CardOverride> overrides
		) {
			this(enabled, allowDustAll, multiplier, rarityBase, finishMultipliers, treatmentMultipliers, Map.of(), overrides);
		}

		public DustProfile {
			multiplier = Support.sanitizeNonNeg(multiplier, 1.0);
			rarityBase = Support.mergeIntDefaults(Support.defaultDustRarities(), rarityBase);
			finishMultipliers = Support.mergeDoubleDefaults(Support.defaultFinishMultipliers(), finishMultipliers);
			treatmentMultipliers = Support.mergeDoubleDefaults(Support.defaultTreatmentMultipliers(), treatmentMultipliers);
			setMultipliers = Support.mergeDoubleDefaults(Map.of(), setMultipliers);
			overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
		}

		public double setMultiplier(String setCode) {
			return setMultipliers.getOrDefault(normalizeSetCode(setCode), 1.0);
		}

		public int compute(String oracleId, String cardName, String printingId, String rarity, String treatment, String finish) {
			return compute(oracleId, cardName, printingId, "", rarity, treatment, finish);
		}

		public int compute(String oracleId, String cardName, String printingId, String setCode, String rarity, String treatment, String finish) {
			return computeValue(
				multiplier, rarityBase, finishMultipliers, treatmentMultipliers, setMultipliers, overrides, List.of(),
				oracleId, cardName, printingId, setCode, rarity, treatment, finish
			);
		}

		public static DustProfile fromJson(JsonObject root) {
			if (root == null) {
				return DEFAULTS;
			}
			boolean enabled = JsonHelper.bool(root, "enabled", true);
			boolean allowDustAll = JsonHelper.bool(root, "allow_dust_all", true);
			double globalMul = parseGlobalMultiplier(root);
			Map<String, Integer> rarities = Support.mergeIntDefaults(Support.defaultDustRarities(), JsonHelper.stringIntMap(root, "rarity"));
			Map<String, Double> finishes = Support.mergeDoubleDefaults(Support.defaultFinishMultipliers(), JsonHelper.stringDoubleMap(root, "finish_multipliers"));
			Map<String, Double> treatments = Support.mergeDoubleDefaults(Support.defaultTreatmentMultipliers(), JsonHelper.stringDoubleMap(root, "treatment_multipliers"));
			Map<String, Double> sets = Support.mergeDoubleDefaults(Map.of(), JsonHelper.stringDoubleMap(root, "set_multipliers"));
			Map<String, CardOverride> overrides = parseOverrides(root);
			return new DustProfile(enabled, allowDustAll, globalMul, rarities, finishes, treatments, sets, overrides);
		}
	}

	public record CraftProfile(
		boolean enabled,
		boolean ownedOnly,
		boolean allowFoil,
		boolean allowEtched,
		int maxBatch,
		double multiplier,
		Map<String, Boolean> allowTreatments,
		Map<String, Integer> rarityBase,
		Map<String, Double> finishMultipliers,
		Map<String, Double> treatmentMultipliers,
		Map<String, Double> setMultipliers,
		Map<String, CardOverride> overrides,
		List<CraftRecipe> recipes
	) {
		public static final CraftProfile DEFAULTS = new CraftProfile(
			true,
			false,
			true,
			true,
			4,
			1.0,
			Support.defaultAllowedTreatments(),
			Support.defaultCraftRarities(),
			Support.defaultFinishMultipliers(),
			Support.defaultTreatmentMultipliers(),
			Map.of(),
			Map.of(),
			List.of()
		);

		public static final StreamCodec<FriendlyByteBuf, CraftProfile> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBoolean(p.enabled);
				buf.writeBoolean(p.ownedOnly);
				buf.writeBoolean(p.allowFoil);
				buf.writeBoolean(p.allowEtched);
				buf.writeVarInt(p.maxBatch);
				buf.writeDouble(p.multiplier);
				Support.writeBoolMap(buf, p.allowTreatments);
				Support.writeIntMap(buf, p.rarityBase);
				Support.writeDoubleMap(buf, p.finishMultipliers);
				Support.writeDoubleMap(buf, p.treatmentMultipliers);
				Support.writeDoubleMap(buf, p.setMultipliers);
				Support.writeOverrideMap(buf, p.overrides);
				buf.writeVarInt(p.recipes.size());
				for (CraftRecipe r : p.recipes) {
					CraftRecipe.CODEC.encode(buf, r);
				}
			},
			buf -> {
				boolean enabled = buf.readBoolean();
				boolean ownedOnly = buf.readBoolean();
				boolean allowFoil = buf.readBoolean();
				boolean allowEtched = buf.readBoolean();
				int maxBatch = buf.readVarInt();
				double multiplier = buf.readDouble();
				Map<String, Boolean> allowTreatments = Support.readBoolMap(buf);
				Map<String, Integer> rarityBase = Support.readIntMap(buf);
				Map<String, Double> finishMultipliers = Support.readDoubleMap(buf);
				Map<String, Double> treatmentMultipliers = Support.readDoubleMap(buf);
				Map<String, Double> setMultipliers = Support.readDoubleMap(buf);
				Map<String, CardOverride> overrides = Support.readOverrideMap(buf);
				int recipeCount = Math.max(0, Math.min(4096, buf.readVarInt()));
				List<CraftRecipe> recipes = new ArrayList<>(recipeCount);
				for (int i = 0; i < recipeCount; i++) {
					recipes.add(CraftRecipe.CODEC.decode(buf));
				}
				return new CraftProfile(
					enabled, ownedOnly, allowFoil, allowEtched, maxBatch, multiplier,
					allowTreatments, rarityBase, finishMultipliers, treatmentMultipliers, setMultipliers, overrides, recipes
				);
			}
		);

		public CraftProfile(
			boolean enabled,
			boolean ownedOnly,
			boolean allowFoil,
			boolean allowEtched,
			int maxBatch,
			double multiplier,
			Map<String, Boolean> allowTreatments,
			Map<String, Integer> rarityBase,
			Map<String, Double> finishMultipliers,
			Map<String, Double> treatmentMultipliers,
			Map<String, CardOverride> overrides,
			List<CraftRecipe> recipes
		) {
			this(
				enabled, ownedOnly, allowFoil, allowEtched, maxBatch, multiplier,
				allowTreatments, rarityBase, finishMultipliers, treatmentMultipliers, Map.of(), overrides, recipes
			);
		}

		public CraftProfile {
			maxBatch = Math.max(1, Math.min(64, maxBatch));
			multiplier = Support.sanitizeNonNeg(multiplier, 1.0);
			allowTreatments = Support.mergeBoolDefaults(Support.defaultAllowedTreatments(), allowTreatments);
			rarityBase = Support.mergeIntDefaults(Support.defaultCraftRarities(), rarityBase);
			finishMultipliers = Support.mergeDoubleDefaults(Support.defaultFinishMultipliers(), finishMultipliers);
			treatmentMultipliers = Support.mergeDoubleDefaults(Support.defaultTreatmentMultipliers(), treatmentMultipliers);
			setMultipliers = Support.mergeDoubleDefaults(Map.of(), setMultipliers);
			overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
			recipes = recipes == null ? List.of() : List.copyOf(recipes);
		}

		public boolean isFinishAllowed(Finish finish) {
			if (finish == null || finish == Finish.NONFOIL) {
				return true;
			}
			return switch (finish) {
				case NONFOIL -> true;
				case FOIL -> allowFoil;
				case ETCHED -> allowEtched;
			};
		}

		public boolean isTreatmentAllowed(String treatment) {
			String norm = normalizeTreatment(treatment);
			return allowTreatments.getOrDefault(norm, true);
		}

		public double finishMultiplier(String finish) {
			return finishMultipliers.getOrDefault(normalizeFinishKey(finish), 1.0);
		}

		public double treatmentMultiplier(String treatment) {
			return treatmentMultipliers.getOrDefault(normalizeTreatment(treatment), 1.0);
		}

		public double setMultiplier(String setCode) {
			return setMultipliers.getOrDefault(normalizeSetCode(setCode), 1.0);
		}

		public int compute(String oracleId, String cardName, String printingId, String rarity, String treatment, String finish) {
			return compute(oracleId, cardName, printingId, "", rarity, treatment, finish);
		}

		public int compute(String oracleId, String cardName, String printingId, String setCode, String rarity, String treatment, String finish) {
			return computeValue(
				multiplier, rarityBase, finishMultipliers, treatmentMultipliers, setMultipliers, overrides, recipes,
				oracleId, cardName, printingId, setCode, rarity, treatment, finish
			);
		}

		public static CraftProfile fromJson(JsonObject root) {
			if (root == null) {
				return DEFAULTS;
			}
			boolean enabled = JsonHelper.bool(root, "enabled", true);
			boolean ownedOnly = JsonHelper.bool(root, "owned_only", false);
			boolean allowFoil = JsonHelper.bool(root, "allow_foil", true);
			boolean allowEtched = JsonHelper.bool(root, "allow_etched", true);
			int maxBatch = JsonHelper.intVal(root, "max_batch", 4);
			double globalMul = parseGlobalMultiplier(root);
			Map<String, Boolean> allowTreatments = Support.mergeBoolDefaults(Support.defaultAllowedTreatments(), JsonHelper.stringBoolMap(root, "allow_treatments"));
			Map<String, Integer> rarities = Support.mergeIntDefaults(Support.defaultCraftRarities(), JsonHelper.stringIntMap(root, "rarity"));
			Map<String, Double> finishes = Support.mergeDoubleDefaults(Support.defaultFinishMultipliers(), JsonHelper.stringDoubleMap(root, "finish_multipliers"));
			Map<String, Double> treatments = Support.mergeDoubleDefaults(Support.defaultTreatmentMultipliers(), JsonHelper.stringDoubleMap(root, "treatment_multipliers"));
			Map<String, Double> sets = Support.mergeDoubleDefaults(Map.of(), JsonHelper.stringDoubleMap(root, "set_multipliers"));
			Map<String, CardOverride> overrides = parseOverrides(root);
			List<CraftRecipe> recipes = parseRecipes(root);
			return new CraftProfile(
				enabled, ownedOnly, allowFoil, allowEtched, maxBatch, globalMul,
				allowTreatments, rarities, finishes, treatments, sets, overrides, recipes
			);
		}
	}

	private static int computeValue(
		double globalMultiplier,
		Map<String, Integer> rarityBase,
		Map<String, Double> finishMultipliers,
		Map<String, Double> treatmentMultipliers,
		Map<String, Double> setMultipliers,
		Map<String, CardOverride> overrides,
		List<CraftRecipe> recipes,
		String oracleId,
		String cardName,
		String printingId,
		String setCode,
		String rarity,
		String treatment,
		String finishRaw
	) {
		Finish finish = Finish.fromName(finishRaw);
		double fMul = finishMultipliers.getOrDefault(finishKey(finish), 1.0);
		double tMul = treatmentMultipliers.getOrDefault(normalizeTreatment(treatment), 1.0);
		double sMul = setMultipliers.getOrDefault(normalizeSetCode(setCode), 1.0);

		for (CraftRecipe recipe : recipes) {
			if (recipe.matches(oracleId, cardName, printingId)) {
				if (!recipe.scale()) {
					return recipe.cost();
				}
				return scaled(recipe.cost(), globalMultiplier * fMul * tMul * sMul);
			}
		}

		CardOverride override = findOverride(overrides, oracleId, cardName);
		if (override != null) {
			int explicitFinish = override.finishOverride(finish);
			if (explicitFinish >= 0) {
				return override.scale() ? scaled(explicitFinish, globalMultiplier * tMul * sMul) : explicitFinish;
			}
			if (override.amount() >= 0) {
				if (!override.scale()) {
					return override.amount();
				}
				return scaled(override.amount(), globalMultiplier * fMul * tMul * sMul);
			}
		}

		String rar = normalizeRarity(rarity);
		int base = rarityBase.getOrDefault(rar, rarityBase.getOrDefault("common", 5));
		return scaled(base, globalMultiplier * fMul * tMul * sMul);
	}

	private static int scaled(int base, double factor) {
		if (base <= 0 || factor <= 0.0 || !Double.isFinite(factor)) {
			return 0;
		}
		long rounded = Math.round(base * factor);
		if (rounded <= 0L) {
			return 1;
		}
		return (int) Math.min(Integer.MAX_VALUE, rounded);
	}

	private static CardOverride findOverride(Map<String, CardOverride> overrides, String oracleId, String cardName) {
		if (overrides == null || overrides.isEmpty()) {
			return null;
		}
		if (oracleId != null && !oracleId.isEmpty()) {
			CardOverride byId = overrides.get(oracleId.trim().toLowerCase(Locale.ROOT));
			if (byId != null) {
				return byId;
			}
		}
		if (cardName != null && !cardName.isEmpty()) {
			String folded = OracleIndex.fold(cardName);
			if (!folded.isEmpty()) {
				CardOverride byFold = overrides.get(folded);
				if (byFold != null) {
					return byFold;
				}
			}
			CardOverride byLower = overrides.get(cardName.trim().toLowerCase(Locale.ROOT));
			if (byLower != null) {
				return byLower;
			}
		}
		return null;
	}

	private static double parseGlobalMultiplier(JsonObject root) {
		double mul = Support.sanitizeNonNeg(JsonHelper.doubleVal(root, "multiplier", 1.0), 1.0);
		double pct = Support.sanitizeNonNeg(JsonHelper.doubleVal(root, "percent", 100.0), 100.0);
		return mul * (pct / 100.0);
	}

	private static Map<String, CardOverride> parseOverrides(JsonObject root) {
		if (root == null || !root.has("overrides") || !root.get("overrides").isJsonObject()) {
			return Map.of();
		}
		Map<String, CardOverride> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("overrides").entrySet()) {
			String rawKey = e.getKey() == null ? "" : e.getKey().trim();
			if (rawKey.isEmpty() || e.getValue() == null || e.getValue().isJsonNull()) {
				continue;
			}
			CardOverride ov = parseOverrideValue(e.getValue());
			if (ov == null) {
				continue;
			}
			String lower = rawKey.toLowerCase(Locale.ROOT);
			out.put(lower, ov);
			String folded = OracleIndex.fold(rawKey);
			if (!folded.isEmpty() && !folded.equals(lower)) {
				out.putIfAbsent(folded, ov);
			}
		}
		return Map.copyOf(out);
	}

	private static CardOverride parseOverrideValue(JsonElement el) {
		if (el.isJsonPrimitive()) {
			try {
				return CardOverride.scaled(el.getAsInt());
			} catch (Exception e) {
				return null;
			}
		}
		if (el.isJsonObject()) {
			JsonObject obj = el.getAsJsonObject();
			int amount = JsonHelper.intVal(obj, "cost", JsonHelper.intVal(obj, "dust", JsonHelper.intVal(obj, "amount", -1)));
			boolean scale = JsonHelper.bool(obj, "scale", true);
			int nonfoil = JsonHelper.intVal(obj, "nonfoil", -1);
			int foil = JsonHelper.intVal(obj, "foil", -1);
			int etched = JsonHelper.intVal(obj, "etched", -1);
			if (amount < 0 && nonfoil < 0 && foil < 0 && etched < 0) {
				return null;
			}
			return new CardOverride(amount, scale, nonfoil, foil, etched);
		}
		return null;
	}

	private static List<CraftRecipe> parseRecipes(JsonObject root) {
		List<JsonObject> raw = JsonHelper.objectList(root, "recipes");
		if (raw.isEmpty()) {
			return List.of();
		}
		List<CraftRecipe> out = new ArrayList<>(raw.size());
		for (JsonObject obj : raw) {
			String id = JsonHelper.str(obj, "id", "");
			String oracleId = JsonHelper.str(obj, "oracle_id", "");
			String name = JsonHelper.str(obj, "name", "");
			String printingId = JsonHelper.str(obj, "printing_id", "");
			int cost = JsonHelper.intVal(obj, "cost", JsonHelper.intVal(obj, "dust", -1));
			if (cost < 0 || (oracleId.isEmpty() && name.isEmpty() && printingId.isEmpty())) {
				continue;
			}
			boolean scale = JsonHelper.bool(obj, "scale", printingId.isEmpty());
			out.add(new CraftRecipe(id, oracleId, name, printingId, cost, scale));
		}
		return List.copyOf(out);
	}

	private static String normalizeRarity(String rarity) {
		if (rarity == null || rarity.isBlank()) {
			return "common";
		}
		String r = rarity.trim().toLowerCase(Locale.ROOT);
		return switch (r) {
			case "common", "uncommon", "rare", "mythic", "special", "bonus" -> r;
			default -> "common";
		};
	}

	public static String normalizeTreatment(String treatment) {
		if (treatment == null || treatment.isBlank()) {
			return "normal";
		}
		String t = treatment.trim().toLowerCase(Locale.ROOT);
		return switch (t) {
			case "retro", "borderless", "special_guest" -> t;
			default -> "normal";
		};
	}

	public static String normalizeSetCode(String setCode) {
		if (setCode == null || setCode.isBlank()) {
			return "";
		}
		return setCode.trim().toLowerCase(Locale.ROOT);
	}

	private static String normalizeFinishKey(String finish) {
		return finishKey(Finish.fromName(finish));
	}

	private static String finishKey(Finish finish) {
		return switch (finish) {
			case NONFOIL -> "nonfoil";
			case FOIL -> "foil";
			case ETCHED -> "etched";
		};
	}

	private static final class Support {
		private static double sanitizeNonNeg(double value, double fallback) {
			if (!Double.isFinite(value) || value < 0.0) {
				return fallback;
			}
			return value;
		}

		private static Map<String, Integer> defaultDustRarities() {
			Map<String, Integer> map = new LinkedHashMap<>();
			map.put("common", 5);
			map.put("uncommon", 20);
			map.put("rare", 100);
			map.put("mythic", 400);
			map.put("special", 400);
			map.put("bonus", 400);
			return Map.copyOf(map);
		}

		private static Map<String, Integer> defaultCraftRarities() {
			Map<String, Integer> map = new LinkedHashMap<>();
			map.put("common", 20);
			map.put("uncommon", 80);
			map.put("rare", 400);
			map.put("mythic", 1600);
			map.put("special", 1600);
			map.put("bonus", 1600);
			return Map.copyOf(map);
		}

		private static Map<String, Double> defaultFinishMultipliers() {
			Map<String, Double> map = new LinkedHashMap<>();
			map.put("nonfoil", 1.0);
			map.put("foil", 2.0);
			map.put("etched", 2.0);
			return Map.copyOf(map);
		}

		private static Map<String, Double> defaultTreatmentMultipliers() {
			Map<String, Double> map = new LinkedHashMap<>();
			map.put("normal", 1.0);
			map.put("retro", 1.0);
			map.put("borderless", 2.0);
			map.put("special_guest", 2.0);
			return Map.copyOf(map);
		}

		private static Map<String, Boolean> defaultAllowedTreatments() {
			Map<String, Boolean> map = new LinkedHashMap<>();
			map.put("normal", true);
			map.put("retro", true);
			map.put("borderless", true);
			map.put("special_guest", true);
			return Map.copyOf(map);
		}

		private static Map<String, Integer> mergeIntDefaults(Map<String, Integer> defaults, Map<String, Integer> extra) {
			Map<String, Integer> out = new LinkedHashMap<>(defaults);
			if (extra != null) {
				for (Map.Entry<String, Integer> e : extra.entrySet()) {
					if (e.getValue() != null && e.getValue() >= 0) {
						out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
					}
				}
			}
			return Map.copyOf(out);
		}

		private static Map<String, Double> mergeDoubleDefaults(Map<String, Double> defaults, Map<String, Double> extra) {
			Map<String, Double> out = new LinkedHashMap<>(defaults);
			if (extra != null) {
				for (Map.Entry<String, Double> e : extra.entrySet()) {
					if (e.getValue() != null && Double.isFinite(e.getValue()) && e.getValue() >= 0.0) {
						out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
					}
				}
			}
			return Map.copyOf(out);
		}

		private static Map<String, Boolean> mergeBoolDefaults(Map<String, Boolean> defaults, Map<String, Boolean> extra) {
			Map<String, Boolean> out = new LinkedHashMap<>(defaults);
			if (extra != null) {
				for (Map.Entry<String, Boolean> e : extra.entrySet()) {
					if (e.getValue() != null) {
						out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
					}
				}
			}
			return Map.copyOf(out);
		}

		private static void writeIntMap(FriendlyByteBuf buf, Map<String, Integer> map) {
			buf.writeVarInt(map.size());
			for (Map.Entry<String, Integer> e : map.entrySet()) {
				buf.writeUtf(e.getKey());
				buf.writeVarInt(e.getValue());
			}
		}

		private static Map<String, Integer> readIntMap(FriendlyByteBuf buf) {
			int size = Math.max(0, Math.min(2048, buf.readVarInt()));
			Map<String, Integer> out = new LinkedHashMap<>(size);
			for (int i = 0; i < size; i++) {
				out.put(buf.readUtf(), buf.readVarInt());
			}
			return out;
		}

		private static void writeDoubleMap(FriendlyByteBuf buf, Map<String, Double> map) {
			buf.writeVarInt(map.size());
			for (Map.Entry<String, Double> e : map.entrySet()) {
				buf.writeUtf(e.getKey());
				buf.writeDouble(e.getValue());
			}
		}

		private static Map<String, Double> readDoubleMap(FriendlyByteBuf buf) {
			int size = Math.max(0, Math.min(2048, buf.readVarInt()));
			Map<String, Double> out = new LinkedHashMap<>(size);
			for (int i = 0; i < size; i++) {
				out.put(buf.readUtf(), buf.readDouble());
			}
			return out;
		}

		private static void writeBoolMap(FriendlyByteBuf buf, Map<String, Boolean> map) {
			buf.writeVarInt(map.size());
			for (Map.Entry<String, Boolean> e : map.entrySet()) {
				buf.writeUtf(e.getKey());
				buf.writeBoolean(e.getValue());
			}
		}

		private static Map<String, Boolean> readBoolMap(FriendlyByteBuf buf) {
			int size = Math.max(0, Math.min(2048, buf.readVarInt()));
			Map<String, Boolean> out = new LinkedHashMap<>(size);
			for (int i = 0; i < size; i++) {
				out.put(buf.readUtf(), buf.readBoolean());
			}
			return out;
		}

		private static void writeOverrideMap(FriendlyByteBuf buf, Map<String, CardOverride> map) {
			buf.writeVarInt(map.size());
			for (Map.Entry<String, CardOverride> e : map.entrySet()) {
				buf.writeUtf(e.getKey());
				CardOverride.CODEC.encode(buf, e.getValue());
			}
		}

		private static Map<String, CardOverride> readOverrideMap(FriendlyByteBuf buf) {
			int size = Math.max(0, Math.min(8192, buf.readVarInt()));
			Map<String, CardOverride> out = new LinkedHashMap<>(size);
			for (int i = 0; i < size; i++) {
				out.put(buf.readUtf(), CardOverride.CODEC.decode(buf));
			}
			return out;
		}
	}
}
