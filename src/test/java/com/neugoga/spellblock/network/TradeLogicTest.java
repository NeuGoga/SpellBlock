package com.neugoga.spellblock.network;

import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.PlayerLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TradeLogicTest {
	@AfterEach
	void cleanup() {
		TradeServer.clearAll();
	}

	@Test
	void editOfferClearsBothLocksAndStopsCountdown() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		CardKey bolt = new CardKey("oracle-bolt", "print-1", Finish.NONFOIL);
		CardKey island = new CardKey("oracle-island", "print-2", Finish.FOIL);

		PlayerLibrary libA = new PlayerLibrary();
		libA.add(bolt, 3);
		PlayerLibrary libB = new PlayerLibrary();
		libB.add(island, 2);

		TradeServer.TradeSession session = new TradeServer.TradeSession(a, b);
		assertEquals("", session.addCard(a, libA, bolt, 1));
		assertEquals("", session.addCard(a, libA, bolt, 10)); // capped to owned (3)
		assertEquals(3, session.offerOf(a).get(bolt));

		assertEquals("", session.toggleLock(a));
		assertEquals("", session.toggleLock(b));
		assertTrue(session.bothLocked());
		assertEquals(3, session.countdownSeconds());

		// Editing an offer after locks clears BOTH locks and cancels countdown
		assertEquals("", session.removeCard(a, bolt, 1));
		assertFalse(session.isLocked(a));
		assertFalse(session.isLocked(b));
		assertEquals(-1, session.countdownSeconds());
		assertEquals(2, session.offerOf(a).get(bolt));

		// Re-locking counts down 3 -> 2 -> 1 -> 0
		assertEquals("", session.toggleLock(a));
		assertEquals("", session.toggleLock(b));
		assertEquals(3, session.countdownSeconds());
		for (int i = 0; i < TradeServer.LOCK_COUNTDOWN_TICKS; i++) {
			session.tickCountdown();
		}
		assertEquals(0, session.countdownTicks());
		assertEquals(0, session.countdownSeconds());
	}

	@Test
	void cancelDuringCountdownClearsLocksWithoutClosing() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		CardKey card = new CardKey("oracle-card", "print-1", Finish.NONFOIL);
		PlayerLibrary libA = new PlayerLibrary();
		libA.add(card, 1);

		TradeServer.TradeSession session = new TradeServer.TradeSession(a, b);
		assertEquals("", session.addCard(a, libA, card, 1));
		assertEquals("", session.toggleLock(a));
		assertEquals("", session.toggleLock(b));
		assertTrue(session.bothLocked());

		assertTrue(session.cancelCountdown());
		assertFalse(session.isLocked(a));
		assertFalse(session.isLocked(b));
		assertEquals(-1, session.countdownTicks());
	}

	@Test
	void atomicSwapTransfersCardsAndClearsEmptyFavourites() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		CardKey cardA = new CardKey("oracle-a", "print-a", Finish.FOIL);
		CardKey cardB = new CardKey("oracle-b", "print-b", Finish.ETCHED);

		PlayerLibrary libA = new PlayerLibrary();
		libA.add(cardA, 2);
		libA.setFavourite(cardA, true);

		PlayerLibrary libB = new PlayerLibrary();
		libB.add(cardB, 1);
		libB.setFavourite(cardB, true);

		TradeServer.TradeSession session = new TradeServer.TradeSession(a, b);
		assertEquals("", session.addCard(a, libA, cardA, 2));
		assertEquals("", session.addCard(b, libB, cardB, 1));

		TradeServer.SwapResult result = TradeServer.executeSwap(
			libA, libB, session.offerOf(a), session.offerOf(b), id -> true
		);
		assertTrue(result.ok());
		assertEquals(0, libA.count(cardA));
		assertFalse(libA.isFavourite(cardA));
		assertEquals(1, libA.count(cardB));
		assertEquals(2, libB.count(cardA));
		assertEquals(0, libB.count(cardB));
		assertFalse(libB.isFavourite(cardB));
	}

	@Test
	void failedSwapLeavesBothBindersUntouched() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		CardKey cardA = new CardKey("oracle-a", "print-a", Finish.NONFOIL);
		CardKey disabled = new CardKey("oracle-banned", "print-b", Finish.NONFOIL);

		PlayerLibrary libA = new PlayerLibrary();
		libA.add(cardA, 1);
		PlayerLibrary libB = new PlayerLibrary();
		libB.add(disabled, 1);

		TradeServer.TradeSession session = new TradeServer.TradeSession(a, b);
		assertEquals("", session.addCard(a, libA, cardA, 1));
		assertEquals("", session.addCard(b, libB, disabled, 1));

		// Disabled card check aborts atomically
		TradeServer.SwapResult banned = TradeServer.executeSwap(
			libA, libB, session.offerOf(a), session.offerOf(b), id -> !"oracle-banned".equals(id)
		);
		assertFalse(banned.ok());
		assertEquals(1, libA.count(cardA));
		assertEquals(0, libA.count(disabled));
		assertEquals(1, libB.count(disabled));
		assertEquals(0, libB.count(cardA));

		// Missing copies also abort atomically
		libA.add(cardA, -1);
		TradeServer.SwapResult missing = TradeServer.executeSwap(
			libA, libB, session.offerOf(a), session.offerOf(b), id -> true
		);
		assertFalse(missing.ok());
		assertEquals(0, libA.count(disabled));
		assertEquals(1, libB.count(disabled));
	}

	@Test
	void receiverCapacityLimitAbortsSwap() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		PlayerLibrary libA = new PlayerLibrary();
		PlayerLibrary libB = new PlayerLibrary();

		for (int i = 0; i < BinderSyncS2CPayload.MAX_CARDS; i++) {
			libB.add(new CardKey("o-" + i, "p-" + i, Finish.NONFOIL), 1);
		}
		CardKey extra = new CardKey("o-extra", "p-extra", Finish.NONFOIL);
		libA.add(extra, 1);

		TradeServer.TradeSession session = new TradeServer.TradeSession(a, b);
		assertEquals("", session.addCard(a, libA, extra, 1));

		TradeServer.SwapResult full = TradeServer.executeSwap(
			libA, libB, session.offerOf(a), session.offerOf(b), id -> true
		);
		assertFalse(full.ok());
		assertEquals(1, libA.count(extra));
		assertEquals(0, libB.count(extra));
	}

	@Test
	void cooldownCountsRemainingSecondsAndExpires() {
		UUID initiator = UUID.randomUUID();
		long now = 1_000_000L;
		TradeServer.startCooldown(initiator, now, 60);
		assertEquals(60, TradeServer.cooldownSecondsLeft(initiator, now));
		assertEquals(1, TradeServer.cooldownSecondsLeft(initiator, now + 59_500L));
		assertEquals(0, TradeServer.cooldownSecondsLeft(initiator, now + 60_000L));
	}
}
