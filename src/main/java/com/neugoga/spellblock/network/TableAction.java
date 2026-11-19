package com.neugoga.spellblock.network;

import com.neugoga.spellblock.table.TableZone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a player can ask the table to do. The wire names are the strings the client already sends,
 * so this is the one place that turns text into a typed action.
 *
 * An action with a source zone acts on one card in that zone, named by its card id.
 * An action with a destination as well moves that card there.
 * A prefixed action carries an argument after its prefix, such as "life:NORTH" or "scry:3".
 */
public enum TableAction {
	// Moves between zones.
	PLAY("play", TableZone.HAND, TableZone.FIELD),
	HAND_TO_GRAVE("grave", TableZone.HAND, TableZone.GRAVE),
	HAND_TO_EXILE("handexile", TableZone.HAND, TableZone.EXILE),
	FIELD_TO_GRAVE("fieldgrave", TableZone.FIELD, TableZone.GRAVE),
	FIELD_TO_EXILE("fieldexile", TableZone.FIELD, TableZone.EXILE),
	FIELD_TO_HAND("fieldhand", TableZone.FIELD, TableZone.HAND),
	COMMAND_TO_FIELD("cmdplay", TableZone.COMMAND, TableZone.FIELD),
	COMMAND_TO_HAND("cmdhand", TableZone.COMMAND, TableZone.HAND),
	FIELD_TO_COMMAND("fieldcmd", TableZone.FIELD, TableZone.COMMAND),
	GRAVE_TO_HAND("gravehand", TableZone.GRAVE, TableZone.HAND),
	GRAVE_TO_EXILE("graveexile", TableZone.GRAVE, TableZone.EXILE),
	GRAVE_TO_FIELD("gravefield", TableZone.GRAVE, TableZone.FIELD),
	EXILE_TO_HAND("exilehand", TableZone.EXILE, TableZone.HAND),
	EXILE_TO_FIELD("exilefield", TableZone.EXILE, TableZone.FIELD),
	EXILE_TO_GRAVE("exilegrave", TableZone.EXILE, TableZone.GRAVE),

	// Back into the library.
	HAND_TO_LIBRARY("handlib", TableZone.HAND),
	FIELD_TO_LIBRARY("fieldback", TableZone.FIELD),
	GRAVE_TO_LIBRARY("graveback", TableZone.GRAVE),
	EXILE_TO_LIBRARY("exileback", TableZone.EXILE),

	// Changes to one card.
	TAP("tap", TableZone.FIELD),
	/** amount is +1 or -1 tax on a command-zone card. */
	COMMAND_TAX("cmdtax", TableZone.COMMAND),
	PLAY_FACE_DOWN("down:hand", TableZone.HAND),
	TURN_FACE_DOWN("down:field", TableZone.FIELD),
	TURN_FACE_UP("up:field", TableZone.FIELD),
	FLIP("flip:field", TableZone.FIELD),
	TOKEN_COPY("tokcopy", TableZone.FIELD),
	COUNTER_POWER("cp", TableZone.FIELD),
	COUNTER_TOUGH("ct", TableZone.FIELD),
	COUNTER_BOTH("cb", TableZone.FIELD),
	COUNTER_LOYALTY("cl", TableZone.FIELD),
	COUNTER_MARKER("cm", TableZone.FIELD),
	CLEAR_COUNTERS("cr", TableZone.FIELD),

	// No card involved.
	DRAW("draw"),
	SHUFFLE("shuffle"),
	SEARCH("search"),
	DENY_SEARCH("denysearch"),
	DENY_CONTROL("denyctrl"),
	PEEK_OFF("peek:off"),
	CLOSE("close"),

	// An argument follows the prefix. The card id, when there is one, travels beside the action.
	LIBRARY_TOP("top:", true),
	LIBRARY_BOTTOM("bot:", true),
	LIFE("life:", true),
	SET_LIFE("setlife:", true),
	REVEAL_HAND("reveal:", true),
	REVEAL_CARD("rcard:", true),
	ASK_SEARCH("asksearch:", true),
	ALLOW_SEARCH("allowsearch:", true),
	ASK_CONTROL("askctrl:", true),
	ASK_CONTROL_SHORT("ac:", true),
	ALLOW_CONTROL("allowctrl:", true),
	SCRY("scry:", true),
	SURVEIL("surv:", true),
	PEEK("peek:", true),
	MILL("mill:", true),
	DRAW_MANY("drawn:", true),
	TOKEN_SPAWN("token:", true),
	/** arg = targetSeat|fromSeat */
	CMD_DMG("cmd:", true),
	/** arg = zone, amount = N from top */
	LIB_NTH("nth:", true),
	UNTAP("untap:", true);

	private final String wire;
	private final boolean prefixed;
	private final TableZone source;
	private final TableZone destination;

	/** An action with no card and no argument. */
	TableAction(String wire) {
		this(wire, false, null, null);
	}

	/** An action that takes an argument after the prefix. */
	TableAction(String wire, boolean prefixed) {
		this(wire, prefixed, null, null);
	}

	/** An action on one card in a zone. */
	TableAction(String wire, TableZone source) {
		this(wire, false, source, null);
	}

	/** An action that moves one card between zones. */
	TableAction(String wire, TableZone source, TableZone destination) {
		this(wire, false, source, destination);
	}

	private TableAction(String wire, boolean prefixed, TableZone source, TableZone destination) {
		this.wire = wire;
		this.prefixed = prefixed;
		this.source = source;
		this.destination = destination;
	}

	/** The exact text, or the prefix for a prefixed action. */
	public String wire() {
		return wire;
	}

	/** The zone of the card this action acts on, or null when the action has no fixed zone. */
	public TableZone source() {
		return source;
	}

	/** The zone a moved card lands in, or null when this action is not a move. */
	public TableZone destination() {
		return destination;
	}

	public boolean isMove() {
		return source != null && destination != null;
	}

	private static final Map<String, TableAction> EXACT = new HashMap<>();
	private static final List<TableAction> PREFIXED = new ArrayList<>();

	static {
		for (TableAction action : values()) {
			if (action.prefixed) {
				PREFIXED.add(action);
			} else {
				EXACT.put(action.wire, action);
			}
		}
	}

	/** A parsed action and whatever followed its prefix. */
	public record Parsed(TableAction action, String arg) {
	}

	/** The action for this wire text, or null when it is not one we know. */
	public static Parsed parse(String raw) {
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		TableAction exact = EXACT.get(raw);
		if (exact != null) {
			return new Parsed(exact, "");
		}
		for (TableAction action : PREFIXED) {
			if (raw.startsWith(action.wire)) {
				return new Parsed(action, raw.substring(action.wire.length()));
			}
		}
		return null;
	}
}
