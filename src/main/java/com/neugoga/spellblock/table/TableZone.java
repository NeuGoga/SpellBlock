package com.neugoga.spellblock.table;

/**
 * The four places a card can sit on a table, besides the library (the deck box).
 * The wire name is what the client already sends, so old and new code agree.
 */
public enum TableZone {
	HAND("hand", "Your hand is full."),
	FIELD("field", "The battlefield is full."),
	/** Commanders and companions sit here until cast. Markers on these cards are tax. */
	COMMAND("cmd", "The command zone is full."),
	GRAVE("grave", "The graveyard is full."),
	EXILE("exile", "Exile is full.");

	private final String wire;
	private final String fullMessage;

	TableZone(String wire, String fullMessage) {
		this.wire = wire;
		this.fullMessage = fullMessage;
	}

	public String wire() {
		return wire;
	}

	/** What to tell the player when a card cannot be added here. */
	public String fullMessage() {
		return fullMessage;
	}

	/** The zone with this wire name, or null. */
	public static TableZone fromWire(String name) {
		if (name == null) {
			return null;
		}
		for (TableZone zone : values()) {
			if (zone.wire.equals(name)) {
				return zone;
			}
		}
		return null;
	}
}
