package com.neugoga.spellblock.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TableCardCountersTest {
	@Test
	void powerAndToughnessIndependent() {
		TableCard card = TableCard.pureToken("0/0", 0, 0).withId(1);
		card = card.addPower(3).addToughness(-1);
		assertEquals(3, card.power());
		assertEquals(-1, card.toughness());
	}

	@Test
	void bothStepsTogether() {
		TableCard card = TableCard.pureToken("0/0", 0, 0).addBoth(2).addBoth(-1);
		assertEquals(1, card.power());
		assertEquals(1, card.toughness());
	}

	@Test
	void tokenCopyClearsCounters() {
		TableCard source = TableCard.pureToken("x", 0, 0).withId(2).addPower(5).addLoyalty(3);
		TableCard copy = source.asTokenCopy();
		assertTrue(copy.token());
		assertEquals(0, copy.power());
		assertEquals(0, copy.loyalty());
	}
}
