package com.neugoga.spellblock.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

final class DeckFormatTest {
	@Test
	void fromIdFallsBack() {
		assertEquals(DeckFormat.COMMANDER, DeckFormat.fromId("commander"));
		assertNotNull(DeckFormat.fromId("not-real"));
		assertEquals(DeckFormat.fromId("not-real"), DeckFormat.fromId(""));
	}
}
