package com.neugoga.spellblock.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
