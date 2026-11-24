package com.neugoga.spellblock.table;

import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.library.DeckFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Hand, battlefield, graveyard, and exile for one seat. The library is the deck box. */
public final class SeatZones {
	public static final int MAX_HAND = 60;
	public static final int MAX_BATTLEFIELD = 100;
	public static final int MAX_GRAVEYARD = 200;
	public static final int MAX_EXILE = 100;
	/** Commanders + companions. Markers on these cards are commander tax. */
	public static final int MAX_COMMAND = 4;

	private final ArrayList<TableCard> hand = new ArrayList<>();
	private final ArrayList<TableCard> battlefield = new ArrayList<>();
	private final ArrayList<TableCard> graveyard = new ArrayList<>();
	private final ArrayList<TableCard> exile = new ArrayList<>();
	private final ArrayList<TableCard> command = new ArrayList<>();
	/** Names filled from the client sync. Not saved. */
	private final ArrayList<String> battlefieldNames = new ArrayList<>();
	private final ArrayList<String> battlefieldRows = new ArrayList<>();
	private final ArrayList<String> graveyardNames = new ArrayList<>();
	private final ArrayList<String> exileNames = new ArrayList<>();
	private final ArrayList<String> commandNames = new ArrayList<>();
	/** UNSET until someone changes it. The screen uses the format's starting total until then. */
	private static final int UNSET = Integer.MIN_VALUE;
	private int life = UNSET;
	/** Commander damage taken from each other seat (seat name -> total). */
	private final Map<String, Integer> commanderDamage = new HashMap<>();
	/** Empty while the hand is private. ALL, or another seat's name, while it is shown. */
	private String reveal = "";

	public List<TableCard> hand() {
		return Collections.unmodifiableList(hand);
	}

	public List<TableCard> battlefield() {
		return Collections.unmodifiableList(battlefield);
	}

	public List<TableCard> graveyard() {
		return Collections.unmodifiableList(graveyard);
	}

	public List<TableCard> exile() {
		return Collections.unmodifiableList(exile);
	}

	public List<TableCard> command() {
		return Collections.unmodifiableList(command);
	}

	public List<String> battlefieldNames() {
		return Collections.unmodifiableList(battlefieldNames);
	}

	/** land, creature, or other. Parallel to the battlefield. Filled from the client sync. */
	public List<String> battlefieldRows() {
		return Collections.unmodifiableList(battlefieldRows);
	}

	public List<String> graveyardNames() {
		return Collections.unmodifiableList(graveyardNames);
	}

	public List<String> exileNames() {
		return Collections.unmodifiableList(exileNames);
	}

	public List<String> commandNames() {
		return Collections.unmodifiableList(commandNames);
	}

	/** One zone's cards, read only. */
	public List<TableCard> list(TableZone zone) {
		return switch (zone) {
			case HAND -> hand();
			case FIELD -> battlefield();
			case COMMAND -> command();
			case GRAVE -> graveyard();
			case EXILE -> exile();
		};
	}

	/** Where the card with this id sits in the zone right now, or -1. */
	public int indexOfId(TableZone zone, int id) {
		if (id <= 0) {
			return -1;
		}
		List<TableCard> cards = list(zone);
		for (int i = 0; i < cards.size(); i++) {
			if (cards.get(i).id() == id) {
				return i;
			}
		}
		return -1;
	}

	/** Adds a card to a zone. False when the zone is full. */
	public boolean add(TableZone zone, TableCard card) {
		return switch (zone) {
			case HAND -> addHand(card);
			case FIELD -> addBattlefield(card);
			case COMMAND -> addCommand(card);
			case GRAVE -> addGraveyard(card);
			case EXILE -> addExile(card);
		};
	}

	/** Takes the card at this position out of a zone, or returns null. */
	public TableCard remove(TableZone zone, int index) {
		return switch (zone) {
			case HAND -> removeHand(index);
			case FIELD -> removeBattlefield(index);
			case COMMAND -> removeCommand(index);
			case GRAVE -> removeGraveyard(index);
			case EXILE -> removeExile(index);
		};
	}

	/** Puts a card back at a position. Used to undo a move that did not fit. */
	public void insert(TableZone zone, int index, TableCard card) {
		switch (zone) {
			case HAND -> insertHand(index, card);
			case FIELD -> insertBattlefield(index, card);
			case COMMAND -> insertCommand(index, card);
			case GRAVE -> insertGraveyard(index, card);
			case EXILE -> insertExile(index, card);
		}
	}

	/** The largest card id on this seat, or 0. */
	public int maxId() {
		int max = 0;
		for (TableZone zone : TableZone.values()) {
			for (TableCard card : list(zone)) {
				max = Math.max(max, card.id());
			}
		}
		return max;
	}

	/** Gives an id to every card that has none. Old saves have none. */
	public void numberCards(java.util.function.IntSupplier ids) {
		number(hand, ids, false);
		number(battlefield, ids, false);
		number(command, ids, false);
		number(graveyard, ids, false);
		number(exile, ids, false);
	}

	/** Gives every card a new id. Used when cards come in from another table, where their ids mean nothing. */
	public void renumber(java.util.function.IntSupplier ids) {
		number(hand, ids, true);
		number(battlefield, ids, true);
		number(command, ids, true);
		number(graveyard, ids, true);
		number(exile, ids, true);
	}

	private static void number(ArrayList<TableCard> cards, java.util.function.IntSupplier ids, boolean all) {
		for (int i = 0; i < cards.size(); i++) {
			TableCard card = cards.get(i);
			if (all || card.id() == 0) {
				cards.set(i, card.withId(ids.getAsInt()));
			}
		}
	}

	public boolean hasLife() {
		return life != UNSET;
	}

	/** The stored total, or 40 for a commander box and 20 otherwise. */
	public int shownLife(ItemStack box) {
		return life != UNSET ? life : startingLife(box);
	}

	public int commanderDamageFrom(String seat) {
		if (seat == null || seat.isEmpty()) {
			return 0;
		}
		return commanderDamage.getOrDefault(seat, 0);
	}

	public Map<String, Integer> commanderDamage() {
		return Collections.unmodifiableMap(commanderDamage);
	}

	public void addCommanderDamage(String fromSeat, int delta) {
		if (fromSeat == null || fromSeat.isEmpty() || delta == 0) {
			return;
		}
		int next = Math.max(0, commanderDamage.getOrDefault(fromSeat, 0) + delta);
		if (next == 0) {
			commanderDamage.remove(fromSeat);
		} else {
			commanderDamage.put(fromSeat, Math.min(99, next));
		}
	}

	public void setLife(int value) {
		if (value > 999) {
			value = 999;
		} else if (value < -99) {
			value = -99;
		}
		life = value;
	}

	public String reveal() {
		return reveal;
	}

	public boolean revealing() {
		return !reveal.isEmpty();
	}

	public void setReveal(String value) {
		if (value == null || value.isEmpty()) {
			reveal = "";
			return;
		}
		// ALL, NORTH, or ALL#3 / NORTH#3 for a single card index.
		String audience = value;
		int hash = value.indexOf('#');
		if (hash >= 0) {
			audience = value.substring(0, hash);
		}
		if (!"ALL".equals(audience)) {
			try {
				TableSeat.valueOf(audience);
			} catch (IllegalArgumentException e) {
				reveal = "";
				return;
			}
		}
		reveal = value;
	}

	public static int startingLife(ItemStack box) {
		if (box == null || box.isEmpty()) {
			return 20;
		}
		DeckBoxData data = box.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		return DeckFormat.fromId(data.format()).commander() ? 40 : 20;
	}

	public boolean isEmpty() {
		return hand.isEmpty() && battlefield.isEmpty() && command.isEmpty() && graveyard.isEmpty() && exile.isEmpty();
	}

	public void clear() {
		hand.clear();
		battlefield.clear();
		command.clear();
		graveyard.clear();
		exile.clear();
		battlefieldNames.clear();
		battlefieldRows.clear();
		graveyardNames.clear();
		exileNames.clear();
		commandNames.clear();
		life = UNSET;
		commanderDamage.clear();
		reveal = "";
	}

	public boolean addHand(TableCard card) {
		if (card == null || hand.size() >= MAX_HAND) {
			return false;
		}
		hand.add(card.faceUp());
		return true;
	}

	public void insertHand(int index, TableCard card) {
		insert(hand, index, card);
	}

	public TableCard removeHand(int index) {
		if (index < 0 || index >= hand.size()) {
			return null;
		}
		return hand.remove(index);
	}

	public TableCard removeBattlefield(int index) {
		if (index < 0 || index >= battlefield.size()) {
			return null;
		}
		return battlefield.remove(index);
	}

	public void insertBattlefield(int index, TableCard card) {
		insert(battlefield, index, card);
	}

	public boolean toggleTap(int index) {
		if (index < 0 || index >= battlefield.size()) {
			return false;
		}
		TableCard card = battlefield.get(index);
		battlefield.set(index, card.withTapped(!card.tapped()));
		return true;
	}

	public boolean replaceBattlefield(int index, TableCard card) {
		if (card == null || index < 0 || index >= battlefield.size()) {
			return false;
		}
		battlefield.set(index, card);
		return true;
	}

	public TableCard removeLastGraveyard() {
		if (graveyard.isEmpty()) {
			return null;
		}
		return graveyard.remove(graveyard.size() - 1);
	}

	public TableCard removeGraveyard(int index) {
		if (index < 0 || index >= graveyard.size()) {
			return null;
		}
		return graveyard.remove(index);
	}

	public void insertGraveyard(int index, TableCard card) {
		insert(graveyard, index, card);
	}

	public TableCard removeLastExile() {
		if (exile.isEmpty()) {
			return null;
		}
		return exile.remove(exile.size() - 1);
	}

	public TableCard removeExile(int index) {
		if (index < 0 || index >= exile.size()) {
			return null;
		}
		return exile.remove(index);
	}

	public void insertExile(int index, TableCard card) {
		insert(exile, index, card);
	}

	public boolean addBattlefield(TableCard card) {
		if (card == null || battlefield.size() >= MAX_BATTLEFIELD) {
			return false;
		}
		battlefield.add(card.faceUp());
		return true;
	}

	/** Hand to battlefield face down. Morph and disguise, with no rules. */
	public boolean playDown(TableCard card) {
		if (card == null || battlefield.size() >= MAX_BATTLEFIELD) {
			return false;
		}
		battlefield.add(card.turnedDown().withTapped(false));
		return true;
	}

	public boolean setFaceDown(int index, boolean down) {
		if (index < 0 || index >= battlefield.size()) {
			return false;
		}
		battlefield.set(index, battlefield.get(index).withFaceDown(down).withTapped(false));
		return true;
	}

	public boolean toggleFlip(int index) {
		if (index < 0 || index >= battlefield.size()) {
			return false;
		}
		TableCard card = battlefield.get(index);
		battlefield.set(index, card.withFlipped(!card.flipped()));
		return true;
	}

	public boolean addGraveyard(TableCard card) {
		if (card == null || graveyard.size() >= MAX_GRAVEYARD) {
			return false;
		}
		graveyard.add(card.faceUp());
		return true;
	}

	public boolean addExile(TableCard card) {
		if (card == null || exile.size() >= MAX_EXILE) {
			return false;
		}
		exile.add(card.faceUp());
		return true;
	}

	public boolean addCommand(TableCard card) {
		if (card == null || command.size() >= MAX_COMMAND) {
			return false;
		}
		command.add(card.faceUp());
		return true;
	}

	public TableCard removeCommand(int index) {
		if (index < 0 || index >= command.size()) {
			return null;
		}
		return command.remove(index);
	}

	public void insertCommand(int index, TableCard card) {
		insert(command, index, card);
	}

	/** Adjust commander tax (stored as markers) on a command-zone card. */
	public boolean adjustCommandTax(int index, int delta) {
		if (index < 0 || index >= command.size() || delta == 0) {
			return false;
		}
		TableCard card = command.get(index);
		int next = Math.max(0, Math.min(TableCard.MAX_MARKERS, card.markers() + delta));
		if (next == card.markers()) {
			return false;
		}
		command.set(index, card.withCounters(card.power(), card.toughness(), card.loyalty(), next));
		return true;
	}

	private static void insert(List<TableCard> into, int index, TableCard card) {
		if (card == null) {
			return;
		}
		int at = index < 0 ? 0 : Math.min(index, into.size());
		into.add(at, card);
	}

	/**
	 * If this seat has no command-zone cards yet, pull commanders from the deck box.
	 * Markers start at 0 (tax). Ids come from the table.
	 */
	public void seedCommanders(ItemStack stack, java.util.function.IntSupplier ids) {
		if (stack == null || stack.isEmpty() || !command.isEmpty()) {
			return;
		}
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		// Seed whenever the box lists commanders (format flag alone is not enough if import
		// left the format as something else but still filled the commanders list).
		if (data.commanders().isEmpty()) {
			return;
		}
		for (var entry : data.commanders()) {
			if (command.size() >= MAX_COMMAND) {
				break;
			}
			TableCard card = TableCard.of(entry.key(), false).withId(ids.getAsInt());
			command.add(card);
			// If the same card was also left on the mainboard, pull it out of the library.
			DeckBoxData stripped = data.remove(DeckBoxData.Zone.MAIN, entry.key());
			if (stripped != null) {
				data = stripped;
			}
		}
		stack.set(SpellBlockComponents.DECK, data);
	}

	/** Puts every card on this seat back into the box, so picking it up does not delete them. */
	public ItemStack returnTo(ItemStack stack) {
		if (stack == null || stack.isEmpty() || isEmpty()) {
			return stack == null ? ItemStack.EMPTY : stack;
		}
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		data = putBackCommanders(data, command);
		data = putBack(data, battlefield);
		data = putBack(data, graveyard);
		data = putBack(data, exile);
		data = putBack(data, hand);
		stack.set(SpellBlockComponents.DECK, data);
		return stack;
	}

	/** Copies every zone into this one, then clears the source. */
	public void takeFrom(SeatZones other) {
		if (other == null || other == this) {
			return;
		}
		clear();
		hand.addAll(other.hand);
		battlefield.addAll(other.battlefield);
		command.addAll(other.command);
		graveyard.addAll(other.graveyard);
		exile.addAll(other.exile);
		life = other.life;
		commanderDamage.clear();
		commanderDamage.putAll(other.commanderDamage);
		reveal = other.reveal;
		other.clear();
	}

	public SeatZones copy() {
		SeatZones copy = new SeatZones();
		copy.hand.addAll(hand);
		copy.battlefield.addAll(battlefield);
		copy.command.addAll(command);
		copy.graveyard.addAll(graveyard);
		copy.exile.addAll(exile);
		copy.life = life;
		copy.commanderDamage.putAll(commanderDamage);
		copy.reveal = reveal;
		return copy;
	}

	public CompoundTag save(boolean includeHand) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("DataVersion", 1);
		if (includeHand && !hand.isEmpty()) {
			tag.put("Hand", saveList(hand));
		}
		if (!battlefield.isEmpty()) {
			tag.put("Battlefield", saveList(battlefield));
		}
		if (!graveyard.isEmpty()) {
			tag.put("Graveyard", saveList(graveyard));
		}
		if (!exile.isEmpty()) {
			tag.put("Exile", saveList(exile));
		}
		if (!command.isEmpty()) {
			tag.put("Command", saveList(command));
		}
		if (life != UNSET) {
			tag.putInt("Life", life);
		}
		if (!commanderDamage.isEmpty()) {
			ListTag cmd = new ListTag();
			for (Map.Entry<String, Integer> e : commanderDamage.entrySet()) {
				CompoundTag row = new CompoundTag();
				row.putString("From", e.getKey());
				row.putInt("Dmg", e.getValue());
				cmd.add(row);
			}
			tag.put("CmdDmg", cmd);
		}
		if (!reveal.isEmpty()) {
			tag.putString("Reveal", reveal);
		}
		return tag;
	}

	public void load(CompoundTag tag) {
		clear();
		if (tag == null) {
			return;
		}
		loadList(tag.getList("Hand", Tag.TAG_COMPOUND), hand, MAX_HAND);
		loadList(tag.getList("Battlefield", Tag.TAG_COMPOUND), battlefield, MAX_BATTLEFIELD);
		loadList(tag.getList("Graveyard", Tag.TAG_COMPOUND), graveyard, MAX_GRAVEYARD);
		loadList(tag.getList("Exile", Tag.TAG_COMPOUND), exile, MAX_EXILE);
		loadList(tag.getList("Command", Tag.TAG_COMPOUND), command, MAX_COMMAND);
		readNames(tag.getList("Battlefield", Tag.TAG_COMPOUND), battlefield, battlefieldNames);
		readRows(tag.getList("Battlefield", Tag.TAG_COMPOUND), battlefield, battlefieldRows);
		readNames(tag.getList("Graveyard", Tag.TAG_COMPOUND), graveyard, graveyardNames);
		readNames(tag.getList("Exile", Tag.TAG_COMPOUND), exile, exileNames);
		readNames(tag.getList("Command", Tag.TAG_COMPOUND), command, commandNames);
		if (tag.contains("Life", Tag.TAG_INT)) {
			setLife(tag.getInt("Life"));
		}
		commanderDamage.clear();
		if (tag.contains("CmdDmg", Tag.TAG_LIST)) {
			ListTag cmd = tag.getList("CmdDmg", Tag.TAG_COMPOUND);
			for (int i = 0; i < cmd.size(); i++) {
				CompoundTag row = cmd.getCompound(i);
				String from = row.getString("From");
				int dmg = row.getInt("Dmg");
				if (!from.isEmpty() && dmg > 0) {
					commanderDamage.put(from, Math.min(99, dmg));
				}
			}
		}
		setReveal(tag.getString("Reveal"));
	}

	private static void readNames(ListTag list, List<TableCard> cards, List<String> into) {
		for (int i = 0; i < cards.size() && i < list.size(); i++) {
			into.add(list.getCompound(i).getString("Name"));
		}
	}

	private static void readRows(ListTag list, List<TableCard> cards, List<String> into) {
		for (int i = 0; i < cards.size() && i < list.size(); i++) {
			String row = list.getCompound(i).getString("Row");
			into.add(row == null || row.isEmpty() ? "other" : row);
		}
	}

	/**
	 * Restore commanders from the command zone into the deck box.
	 * First two non-token cards become commander slots; any extra go to sideboard.
	 * Clears previous commander slots so a partially cast board does not leave stale entries.
	 */
	private static DeckBoxData putBackCommanders(DeckBoxData data, List<TableCard> cards) {
		while (!data.commanders().isEmpty()) {
			DeckBoxData.ReplaceCommander cleared = data.clearCommander(0);
			if (cleared == null || cleared.data() == data) {
				break;
			}
			data = cleared.data();
		}
		int slot = 0;
		for (TableCard card : cards) {
			if (card.token() || card.oracleId().isEmpty()) {
				continue;
			}
			if (slot < 2) {
				DeckBoxData.ReplaceCommander replaced = data.putCommander(slot, card.key());
				if (replaced != null) {
					data = replaced.data();
				}
				slot++;
			} else {
				data = data.add(DeckBoxData.Zone.SIDE, card.key());
			}
		}
		return data;
	}

	private static DeckBoxData putBack(DeckBoxData data, List<TableCard> cards) {
		for (TableCard card : cards) {
			// Tokens are battlefield objects only. Never write them back into a deck box.
			if (card.token() || card.oracleId().isEmpty()) {
				continue;
			}
			data = data.add(DeckBoxData.Zone.MAIN, card.key());
		}
		return data;
	}

	private static ListTag saveList(List<TableCard> cards) {
		ListTag list = new ListTag();
		for (TableCard card : cards) {
			list.add(card.save());
		}
		return list;
	}

	private static void loadList(ListTag list, List<TableCard> into, int max) {
		for (int i = 0; i < list.size() && into.size() < max; i++) {
			TableCard card = TableCard.load(list.getCompound(i));
			// Tokens (and pure labels) have no oracle id; still load them.
			if (card != null && (!card.oracleId().isEmpty() || card.token() || !card.label().isEmpty())) {
				into.add(card);
			}
		}
	}
}
