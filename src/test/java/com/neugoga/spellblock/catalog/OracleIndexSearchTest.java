package com.neugoga.spellblock.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

final class OracleIndexSearchTest {
	private static OracleCard card(String id, String name, String type, String colors, String rarity) {
		return new OracleCard(id, name, "{1}", 1.0, type, colors, rarity, "normal", "m21", "", "", "p-" + id, "");
	}

	private static OracleIndex sampleIndex() {
		return OracleIndex.build(List.of(
			card("1", "Bolt Bend", "Instant", "R", "uncommon"),
			card("2", "Lightning Bolt", "Instant", "R", "common"),
			card("3", "Bolt", "Instant", "R", "common"),
			card("4", "Sunbolt", "Sorcery", "R", "common"),
			card("5", "Sol Ring", "Artifact", "", "uncommon"),
			card("6", "Plains", "Basic Land — Plains", "", "common"),
			card("7", "Snapcaster Mage", "Creature — Human Wizard", "U", "rare"),
			card("8", "Solitude", "Creature — Elemental Incarnation", "W", "mythic")
		), "2026-01-01");
	}

	@Test
	void ranksExactPrefixWordStartAndSubstring() {
		OracleIndex index = sampleIndex();
		var result = index.search("bolt", "", "", 20);
		assertEquals(4, result.totalMatches());
		assertEquals(
			List.of("Bolt", "Bolt Bend", "Lightning Bolt", "Sunbolt"),
			result.matches().stream().map(OracleCard::name).toList()
		);
	}

	@Test
	void filtersByTypeColorAndRarity() {
		OracleIndex index = sampleIndex();
		var wizards = index.search("t:wizard", "U", "rare", 10);
		assertEquals(1, wizards.totalMatches());
		assertEquals("Snapcaster Mage", wizards.matches().getFirst().name());

		var colorless = index.search("", "C", "", 10);
		assertTrue(colorless.matches().stream().anyMatch(c -> "Sol Ring".equals(c.name())));
		assertEquals("Plains", colorless.matches().getFirst().name());
	}
}
