package com.neugoga.spellblock.library;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DustValuesTest {
	@Test
	void disenchantAndCraftRatesMatchSpec() {
		assertEquals(5, DustValues.disenchant("common", "NONFOIL"));
		assertEquals(5, DustValues.disenchant("common", "nonfoil"));
		assertEquals(20, DustValues.disenchant("uncommon", "NONFOIL"));
		assertEquals(100, DustValues.disenchant("rare", "NONFOIL"));
		assertEquals(400, DustValues.disenchant("mythic", "NONFOIL"));

		assertEquals(20, DustValues.craft("common", "NONFOIL"));
		assertEquals(80, DustValues.craft("uncommon", "NONFOIL"));
		assertEquals(400, DustValues.craft("rare", "NONFOIL"));
		assertEquals(1600, DustValues.craft("mythic", "NONFOIL"));
	}

	@Test
	void foilAndEtchedDoubleDustRates() {
		assertEquals(200, DustValues.disenchant("rare", "FOIL"));
		assertEquals(200, DustValues.disenchant("rare", "ETCHED"));
		assertEquals(800, DustValues.craft("rare", "FOIL"));
		assertEquals(3200, DustValues.craft("mythic", "ETCHED"));
	}

	@Test
	void borderlessAndSpecialGuestCardsCostAndDisenchantDoubleNormal() {
		int normalDis = DustValues.disenchant("o1", "Card", "lea", "rare", "normal", "NONFOIL", null);
		int borderlessDis = DustValues.disenchant("o1", "Card", "lea", "rare", "borderless", "NONFOIL", null);
		int guestDis = DustValues.disenchant("o1", "Card", "spg", "rare", "special_guest", "NONFOIL", null);
		assertEquals(100, normalDis);
		assertEquals(200, borderlessDis);
		assertEquals(200, guestDis);
		assertEquals(400, DustValues.disenchant("o1", "Card", "lea", "rare", "borderless", "FOIL", null));
		assertEquals(400, DustValues.disenchant("o1", "Card", "spg", "rare", "special_guest", "FOIL", null));

		int normalCraft = DustValues.craft("o1", "Card", "p1", "lea", "rare", "normal", "NONFOIL", null);
		int borderlessCraft = DustValues.craft("o1", "Card", "p1", "lea", "rare", "borderless", "NONFOIL", null);
		int guestCraft = DustValues.craft("o1", "Card", "p1", "spg", "rare", "special_guest", "NONFOIL", null);
		assertEquals(400, normalCraft);
		assertEquals(800, borderlessCraft);
		assertEquals(800, guestCraft);

		BinderCard ownedBorderless = new BinderCard("o1", "Card", "NONFOIL", 1, "Card", "{R}", 1, "Instant", "R", "rare", "lea", "borderless");
		BinderCard ownedGuest = new BinderCard("o1", "Card", "NONFOIL", 1, "Card", "{R}", 1, "Instant", "R", "rare", "spg", "special_guest");
		assertEquals(200, DustValues.disenchant(ownedBorderless, null));
		assertEquals(200, DustValues.disenchant(ownedGuest, null));
	}
}
