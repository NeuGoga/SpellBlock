package com.neugoga.spellblock.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CardRulesTest {
	@Test
	void legendaryCreatureIsCommander() {
		String rules = CardRules.parse("Legendary Creature — Human Wizard", "Flying");
		assertTrue(CardRules.has(rules, "cmd"));
	}

	@Test
	void canBeYourCommanderFlag() {
		String rules = CardRules.parse("Creature — Eldrazi", "You may have this card can be your commander.");
		assertTrue(CardRules.has(rules, "cmd"));
	}

	@Test
	void partnerWithTokenPresent() {
		String rules = CardRules.parse("Legendary Creature — Human", "Partner with Halana\nWhen this enters...");
		assertTrue(rules.contains("with:Halana"), rules);
	}

	@Test
	void plainPartner() {
		String rules = CardRules.parse("Legendary Creature — Human", "Partner");
		assertTrue(CardRules.has(rules, "partner"));
	}

	@Test
	void anyNumberRelentless() {
		String rules = CardRules.parse("Creature — Rat", "A deck can have any number of cards named Relentless Rats.");
		assertTrue(CardRules.has(rules, "any"));
	}

	@Test
	void upToSeven() {
		String rules = CardRules.parse("Creature — Dwarf", "A deck can have up to seven cards named Seven Dwarves.");
		assertTrue(CardRules.has(rules, "max:7"));
	}

	@Test
	void emptyTextHasNoRules() {
		assertEquals("", CardRules.parse("Instant", ""));
		assertFalse(CardRules.has("", "cmd"));
	}
}
