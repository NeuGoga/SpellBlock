package com.neugoga.spellblock.library;

/** Formats the deck box knows how to check. The id is Scryfall's legality key. */
public enum DeckFormat {
	COMMANDER("commander", "Commander", true),
	PAUPER_COMMANDER("paupercommander", "Pauper Commander", true),
	STANDARD("standard", "Standard", false),
	PIONEER("pioneer", "Pioneer", false),
	MODERN("modern", "Modern", false),
	LEGACY("legacy", "Legacy", false),
	VINTAGE("vintage", "Vintage", false),
	PAUPER("pauper", "Pauper", false);

	private final String id;
	private final String label;
	private final boolean commander;

	DeckFormat(String id, String label, boolean commander) {
		this.id = id;
		this.label = label;
		this.commander = commander;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	public boolean commander() {
		return commander;
	}

	public boolean sideboard() {
		return !commander;
	}

	public DeckFormat next() {
		DeckFormat[] values = values();
		return values[(ordinal() + 1) % values.length];
	}

	public static DeckFormat fromId(String id) {
		if (id == null) {
			return COMMANDER;
		}
		for (DeckFormat format : values()) {
			if (format.id.equals(id)) {
				return format;
			}
		}
		return COMMANDER;
	}
}
