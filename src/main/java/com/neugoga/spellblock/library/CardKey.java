package com.neugoga.spellblock.library;

/** One owned printing. Identical keys stack. This is not a Minecraft item. */
public record CardKey(String oracleId, String printingId, Finish finish) {
	public CardKey {
		if (oracleId == null || oracleId.isEmpty()) {
			throw new IllegalArgumentException("oracleId");
		}
		printingId = printingId == null ? "" : printingId;
		if (finish == null) {
			finish = Finish.NONFOIL;
		}
	}
}
