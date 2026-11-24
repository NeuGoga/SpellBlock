package com.neugoga.spellblock.library;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FinishTest {
	@Test
	void fromNameRoundTrip() {
		assertEquals(Finish.NONFOIL, Finish.fromName(""));
		assertEquals(Finish.NONFOIL, Finish.fromName(null));
		assertEquals(Finish.FOIL, Finish.fromName("FOIL"));
		assertEquals(Finish.FOIL, Finish.fromName("foil"));
		assertEquals(Finish.NONFOIL, Finish.fromName("not-a-finish"));
	}
}
