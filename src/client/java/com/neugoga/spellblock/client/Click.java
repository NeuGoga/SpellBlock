package com.neugoga.spellblock.client;

/**
 * One thing under the cursor on the table screen: a card, a button, a seat tab, a menu row.
 * The screen collects these while it draws, then resolves a click against them.
 * A class and not a record: the screen and its panels read the fields directly.
 */
final class Click {
	final int x;
	final int y;
	final int w;
	final int h;
	final String zone;
	final int index;
	/** The table's id for this card, or 0 when the thing under the cursor is not a card. */
	final int cardId;
	final String name;
	final String extra;
	final String printingId;
	final String finish;
	final boolean faceDown;
	final boolean flipped;
	final boolean owner;

	Click(int x, int y, int w, int h, String zone, int index, int cardId, String name, String extra, String printingId, String finish, boolean faceDown, boolean flipped, boolean owner) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.zone = zone;
		this.index = index;
		this.cardId = cardId;
		this.name = name;
		this.extra = extra;
		this.printingId = printingId;
		this.finish = finish;
		this.faceDown = faceDown;
		this.flipped = flipped;
		this.owner = owner;
	}
}
