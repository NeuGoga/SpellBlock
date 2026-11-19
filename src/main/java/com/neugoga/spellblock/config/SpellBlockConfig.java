package com.neugoga.spellblock.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.neugoga.spellblock.SpellBlock;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client-facing settings stored in config/spellblock.json. */
public final class SpellBlockConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static SpellBlockConfig instance = new SpellBlockConfig();

	private ArtQuality artQuality = ArtQuality.LARGE;

	private SpellBlockConfig() {
	}

	public static SpellBlockConfig get() {
		return instance;
	}

	public ArtQuality artQuality() {
		return artQuality == null ? ArtQuality.LARGE : artQuality;
	}

	public void setArtQuality(ArtQuality quality) {
		this.artQuality = quality == null ? ArtQuality.LARGE : quality;
		save();
	}

	public static void load() {
		Path path = path();
		if (!Files.isRegularFile(path)) {
			instance = new SpellBlockConfig();
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);
			SpellBlockConfig next = new SpellBlockConfig();
			if (root != null && root.has("artQuality")) {
				next.artQuality = ArtQuality.fromName(root.get("artQuality").getAsString());
			}
			instance = next;
		} catch (Exception e) {
			SpellBlock.LOGGER.warn("Could not read SpellBlock config; using defaults.", e);
			instance = new SpellBlockConfig();
		}
	}

	public static void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("artQuality", instance.artQuality().name());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(root, writer);
			}
		} catch (IOException e) {
			SpellBlock.LOGGER.warn("Could not write SpellBlock config.", e);
		}
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("spellblock.json");
	}
}
