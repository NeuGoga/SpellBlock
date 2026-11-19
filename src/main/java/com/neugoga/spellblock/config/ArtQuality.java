package com.neugoga.spellblock.config;

/**
 * Scryfall face size used when downloading card art.
 * Smaller settings use less disk and bandwidth; PNG is archival quality.
 */
public enum ArtQuality {
	/** ~488×680 JPEG — smallest, fine for the table and hand strip. */
	NORMAL("normal", "jpg", "Normal (small JPEG)"),
	/** ~672×936 JPEG — default balance of size and clarity. */
	LARGE("large", "jpg", "Large (JPEG)"),
	/** Full Scryfall PNG — highest fidelity, largest files. */
	PNG("png", "png", "High (PNG)");

	private final String scryfallFolder;
	private final String extension;
	private final String label;

	ArtQuality(String scryfallFolder, String extension, String label) {
		this.scryfallFolder = scryfallFolder;
		this.extension = extension;
		this.label = label;
	}

	public String scryfallFolder() {
		return scryfallFolder;
	}

	public String extension() {
		return extension;
	}

	public String label() {
		return label;
	}

	public static ArtQuality fromName(String raw) {
		if (raw == null || raw.isBlank()) {
			return LARGE;
		}
		try {
			return ArtQuality.valueOf(raw.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return LARGE;
		}
	}

	public ArtQuality next() {
		ArtQuality[] all = values();
		return all[(ordinal() + 1) % all.length];
	}
}
