package com.neugoga.spellblock.library;

public enum Finish {
	NONFOIL,
	FOIL,
	ETCHED;

	public String label() {
		return switch (this) {
			case NONFOIL -> "Normal";
			case FOIL -> "Foil";
			case ETCHED -> "Etched";
		};
	}

	public static Finish fromName(String name) {
		if (name == null || name.isEmpty()) {
			return NONFOIL;
		}
		try {
			return valueOf(name.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			return NONFOIL;
		}
	}
}
