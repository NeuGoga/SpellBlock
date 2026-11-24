package com.neugoga.spellblock.table;

import com.neugoga.spellblock.item.DeckBoxItem;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.network.TableServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.BiConsumer;

/** One piece of a table. Boxes and play zones are saved on the minimum corner only. */
public final class TableBlockEntity extends BlockEntity {
	/** Bumped when the table NBT layout changes. */
	public static final int DATA_VERSION = 2;

	private final ItemStack[] boxes = emptyBoxes();
	private final SeatZones[] zones = emptyZones();
	/** The next card id this table hands out. Saved, so ids are never reused. */
	private int nextCardId = 1;
	/** Bumps whenever this entity reloads, so the cloth can keep a card layout until then. */
	private transient int clientGeneration;

	public TableBlockEntity(BlockPos pos, BlockState state) {
		super(TableBlocks.TABLE_BE, pos, state);
	}

	/** A new card on this table, numbered. Every card that enters play comes from here. */
	public TableCard newCard(CardKey key, boolean faceDown) {
		return TableCard.of(key, faceDown).withId(allocateCardId());
	}

	public int clientGeneration() {
		return clientGeneration;
	}

	public int allocateCardId() {
		return nextCardId++;
	}

	/** After a load: never hand out an id that is already in use, and number any card that has none. */
	private void settleCardIds() {
		for (SeatZones seatZones : zones) {
			nextCardId = Math.max(nextCardId, seatZones.maxId() + 1);
		}
		for (SeatZones seatZones : zones) {
			seatZones.numberCards(this::allocateCardId);
		}
	}

	public ItemStack get(TableSeat seat) {
		ItemStack stack = boxes[seat.ordinal()];
		return stack == null ? ItemStack.EMPTY : stack;
	}

	public SeatZones zones(TableSeat seat) {
		return zones[seat.ordinal()];
	}

	public void put(TableSeat seat, ItemStack stack) {
		boxes[seat.ordinal()] = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
		if (stack != null && !stack.isEmpty()) {
			zones[seat.ordinal()].seedCommanders(stack, this::allocateCardId);
		}
		mark();
	}

	public ItemStack take(TableSeat seat) {
		// Anyone scrying this library hands their cards back first, so they leave with the box.
		if (level instanceof ServerLevel serverLevel) {
			TableServer.restoreLooksAt(serverLevel, worldPosition, seat);
		}
		ItemStack stack = get(seat);
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		stack = zones[seat.ordinal()].returnTo(stack);
		boxes[seat.ordinal()] = ItemStack.EMPTY;
		zones[seat.ordinal()].clear();
		mark();
		if (level instanceof ServerLevel server) {
			TableServer.dropped(server, worldPosition);
		}
		return stack;
	}

	/** Removes every box and its zones without a block update. The caller decides where they go. */
	public void collect(BiConsumer<TableSeat, HeldSeat> consumer) {
		if (level instanceof ServerLevel serverLevel) {
			TableServer.restoreLooksAt(serverLevel, worldPosition, null);
		}
		boolean any = false;
		for (TableSeat seat : TableSeat.values()) {
			ItemStack stack = get(seat);
			SeatZones seatZones = zones[seat.ordinal()];
			if (stack.isEmpty() && seatZones.isEmpty()) {
				continue;
			}
			boxes[seat.ordinal()] = ItemStack.EMPTY;
			SeatZones moved = seatZones.copy();
			seatZones.clear();
			consumer.accept(seat, new HeldSeat(stack, moved));
			any = true;
		}
		if (any) {
			mark();
		}
	}

	public int count() {
		int count = 0;
		for (TableSeat seat : TableSeat.values()) {
			if (!get(seat).isEmpty()) {
				count++;
			}
		}
		return count;
	}

	public void dropAll() {
		if (level == null || level.isClientSide()) {
			return;
		}
		if (level instanceof ServerLevel serverLevel) {
			TableServer.restoreLooksAt(serverLevel, worldPosition, null);
		}
		for (TableSeat seat : TableSeat.values()) {
			ItemStack stack = zones[seat.ordinal()].returnTo(get(seat));
			boxes[seat.ordinal()] = ItemStack.EMPTY;
			zones[seat.ordinal()].clear();
			if (!stack.isEmpty()) {
				Block.popResource(level, worldPosition, stack);
			}
		}
		mark();
		if (level instanceof ServerLevel server) {
			TableServer.dropped(server, worldPosition);
		}
	}

	public void sync() {
		mark();
	}

	/** Puts a box and its zones on a free seat. Used when the table joins. */
	public void restore(TableSeat seat, ItemStack stack, SeatZones seatZones) {
		boxes[seat.ordinal()] = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
		SeatZones target = zones[seat.ordinal()];
		target.clear();
		if (seatZones != null && !seatZones.isEmpty()) {
			target.takeFrom(seatZones);
			// These ids came from another table. They mean nothing here, and could clash.
			target.renumber(this::allocateCardId);
		}
		mark();
	}

	private void mark() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt("DataVersion", DATA_VERSION);
		writeContents(tag, registries, true);
		tag.putInt("NextCardId", nextCardId);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		writeContents(tag, registries, false);
		if (!tag.contains("Boxes")) {
			tag.put("Boxes", new ListTag());
		}
		if (!tag.contains("Zones")) {
			tag.put("Zones", new ListTag());
		}
		return tag;
	}

	/** Hands stay on disk. The client packet leaves them out and adds public card names. */
	private void writeContents(CompoundTag tag, HolderLookup.Provider registries, boolean includeHand) {
		ListTag boxList = new ListTag();
		ListTag zoneList = new ListTag();
		for (TableSeat seat : TableSeat.values()) {
			ItemStack stack = get(seat);
			if (!stack.isEmpty()) {
				CompoundTag entry = new CompoundTag();
				entry.putString("Seat", seat.name());
				Tag saved = stack.save(registries);
				if (saved instanceof CompoundTag compound) {
					entry.put("Item", compound);
				}
				boxList.add(entry);
			}
			SeatZones seatZones = zones[seat.ordinal()];
			if (!seatZones.isEmpty() || seatZones.hasLife() || seatZones.revealing()) {
				CompoundTag entry = seatZones.save(includeHand);
				if (!includeHand) {
					TableNames.stamp(entry);
				}
				entry.putString("Seat", seat.name());
				zoneList.add(entry);
			}
		}
		if (!boxList.isEmpty()) {
			tag.put("Boxes", boxList);
		}
		if (!zoneList.isEmpty()) {
			tag.put("Zones", zoneList);
		}
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		int dataVersion = tag.contains("DataVersion") ? tag.getInt("DataVersion") : 0;
		if (dataVersion > DATA_VERSION) {
			// Newer mod wrote this table; keep fields we know and ignore the rest.
		}
		for (int i = 0; i < boxes.length; i++) {
			boxes[i] = ItemStack.EMPTY;
			zones[i].clear();
		}
		ListTag boxList = tag.getList("Boxes", Tag.TAG_COMPOUND);
		for (int i = 0; i < boxList.size(); i++) {
			CompoundTag entry = boxList.getCompound(i);
			TableSeat seat = seatOf(entry.getString("Seat"));
			if (seat == null || !(entry.get("Item") instanceof CompoundTag item)) {
				continue;
			}
			ItemStack stack = ItemStack.parseOptional(registries, item);
			if (stack.isEmpty() || !(stack.getItem() instanceof DeckBoxItem)) {
				continue;
			}
			boxes[seat.ordinal()] = stack;
		}
		ListTag zoneList = tag.getList("Zones", Tag.TAG_COMPOUND);
		for (int i = 0; i < zoneList.size(); i++) {
			CompoundTag entry = zoneList.getCompound(i);
			TableSeat seat = seatOf(entry.getString("Seat"));
			if (seat == null) {
				continue;
			}
			zones[seat.ordinal()].load(entry);
		}
		nextCardId = Math.max(1, tag.getInt("NextCardId"));
		settleCardIds();
		clientGeneration++;
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	private static TableSeat seatOf(String name) {
		try {
			return TableSeat.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static ItemStack[] emptyBoxes() {
		ItemStack[] stacks = new ItemStack[TableSeat.values().length];
		for (int i = 0; i < stacks.length; i++) {
			stacks[i] = ItemStack.EMPTY;
		}
		return stacks;
	}

	private static SeatZones[] emptyZones() {
		SeatZones[] list = new SeatZones[TableSeat.values().length];
		for (int i = 0; i < list.length; i++) {
			list[i] = new SeatZones();
		}
		return list;
	}

	/** A box and the play zones that belonged to the same seat. */
	public record HeldSeat(ItemStack stack, SeatZones zones) {
		public HeldSeat {
			stack = stack == null ? ItemStack.EMPTY : stack;
			zones = zones == null ? new SeatZones() : zones;
		}
	}
}
