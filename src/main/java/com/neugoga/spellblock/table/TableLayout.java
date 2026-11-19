package com.neugoga.spellblock.table;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.neugoga.spellblock.network.table.TableState;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Joins same-wood tables into a 1x1, a 2x1, or a 2x2. Anything else stays separate. */
public final class TableLayout {
	private static final int[][] HORIZONTAL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static boolean busy;

	private TableLayout() {
	}

	public static void rebuild(Level level, BlockPos around, TableWood wood) {
		if (busy || level.isClientSide() || wood == null) {
			return;
		}
		busy = true;
		try {
			BlockPos start = around.immutable();
			if (isMatchingTable(level.getBlockState(start), wood)) {
				rebuildComponent(level, start, wood, new HashSet<>());
				return;
			}
			Set<BlockPos> seenAcrossComponents = new HashSet<>();
			for (int[] d : HORIZONTAL_DIRS) {
				BlockPos neighbor = start.offset(d[0], 0, d[1]).immutable();
				if (seenAcrossComponents.contains(neighbor)) {
					continue;
				}
				if (isMatchingTable(level.getBlockState(neighbor), wood)) {
					rebuildComponent(level, neighbor, wood, seenAcrossComponents);
				}
			}
		} finally {
			busy = false;
		}
	}

	private static boolean isMatchingTable(BlockState state, TableWood wood) {
		return state.getBlock() instanceof TableBlock && state.getValue(TableBlock.WOOD) == wood;
	}

	private static void rebuildComponent(Level level, BlockPos seed, TableWood wood, Set<BlockPos> seenAcrossComponents) {
		List<BlockPos> found = new ArrayList<>();
		Set<BlockPos> has = new HashSet<>();
		ArrayList<BlockPos> queue = new ArrayList<>();
		queue.add(seed);
		has.add(seed);
		int guard = 0;
		while (!queue.isEmpty() && guard++ < 64) {
			BlockPos pos = queue.remove(queue.size() - 1);
			if (!isMatchingTable(level.getBlockState(pos), wood)) {
				has.remove(pos);
				continue;
			}
			found.add(pos);
			seenAcrossComponents.add(pos);
			for (int[] d : HORIZONTAL_DIRS) {
				BlockPos next = pos.offset(d[0], 0, d[1]).immutable();
				if (has.add(next)) {
					queue.add(next);
				}
			}
		}
		if (found.isEmpty()) {
			return;
		}
		List<Held> held = new ArrayList<>();
		for (BlockPos pos : found) {
			if (level.getBlockEntity(pos) instanceof TableBlockEntity table) {
				table.collect((seat, seatHeld) -> held.add(new Held(pos, seat, seatHeld.stack(), seatHeld.zones())));
			}
		}
		List<Assignment> assignments = plan(found);
		Map<BlockPos, Assignment> byPos = new HashMap<>();
		Map<BlockPos, BlockPos> originMoves = new HashMap<>();
		for (Assignment assignment : assignments) {
			byPos.put(assignment.pos, assignment);
			BlockState current = level.getBlockState(assignment.pos);
			if (!(current.getBlock() instanceof TableBlock)) {
				continue;
			}
			BlockPos oldOrigin = assignment.pos.offset(-current.getValue(TableBlock.X), 0, -current.getValue(TableBlock.Z));
			BlockPos newOrigin = assignment.pos.offset(-assignment.x, 0, -assignment.z);
			if (!oldOrigin.equals(newOrigin)) {
				originMoves.put(oldOrigin.immutable(), newOrigin.immutable());
			}
			BlockState next = current
				.setValue(TableBlock.SIZE, assignment.size)
				.setValue(TableBlock.X, assignment.x)
				.setValue(TableBlock.Z, assignment.z);
			if (!current.equals(next)) {
				level.setBlock(assignment.pos, next, Block.UPDATE_ALL);
			}
		}
		for (var move : originMoves.entrySet()) {
			TableState.remapOrigin(move.getKey(), move.getValue());
		}
		for (Held seat : held) {
			restore(level, byPos, seat);
		}
	}

	/** Pure deterministic table layout partition for a connected component of table blocks. */
	public static List<Assignment> plan(Collection<BlockPos> positions) {
		if (positions == null || positions.isEmpty()) {
			return List.of();
		}
		List<BlockPos> sorted = new ArrayList<>(positions.size());
		Set<BlockPos> has = new HashSet<>(positions.size());
		for (BlockPos p : positions) {
			BlockPos imm = p.immutable();
			if (has.add(imm)) {
				sorted.add(imm);
			}
		}
		sorted.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));

		Set<BlockPos> used = new HashSet<>();
		List<Assignment> assignments = new ArrayList<>();
		for (BlockPos pos : sorted) {
			BlockPos east = pos.offset(1, 0, 0);
			BlockPos south = pos.offset(0, 0, 1);
			BlockPos corner = pos.offset(1, 0, 1);
			if (free(used, has, pos, east, south, corner)) {
				claim(used, assignments, TableSize.SQUARE, pos, east, south, corner);
			}
		}
		for (BlockPos pos : sorted) {
			BlockPos east = pos.offset(1, 0, 0);
			if (free(used, has, pos, east)) {
				claim(used, assignments, TableSize.LONG_X, pos, east);
			}
		}
		for (BlockPos pos : sorted) {
			BlockPos south = pos.offset(0, 0, 1);
			if (free(used, has, pos, south)) {
				claim(used, assignments, TableSize.LONG_Z, pos, south);
			}
		}
		for (BlockPos pos : sorted) {
			if (!used.contains(pos)) {
				assignments.add(new Assignment(pos, TableSize.SINGLE, 0, 0));
			}
		}
		return assignments;
	}

	private static void restore(Level level, Map<BlockPos, Assignment> byPos, Held held) {
		Assignment assignment = byPos.get(held.pos);
		if (assignment == null) {
			dropBox(level, held.pos, held.zones.returnTo(held.stack));
			return;
		}
		BlockPos origin = held.pos.offset(-assignment.x, 0, -assignment.z);
		if (!(level.getBlockEntity(origin) instanceof TableBlockEntity table)) {
			dropBox(level, held.pos, held.zones.returnTo(held.stack));
			return;
		}
		TableSeat seat = TableSeat.open(assignment.size, held.seat, table::get);
		if (seat == null || table.count() >= TableSeat.capacity(assignment.size)) {
			dropBox(level, held.pos, held.zones.returnTo(held.stack));
			return;
		}
		table.restore(seat, held.stack, held.zones);
	}

	private static void dropBox(Level level, BlockPos pos, ItemStack stack) {
		if (stack != null && !stack.isEmpty()) {
			Block.popResource(level, pos, stack);
		}
	}

	private static boolean free(Set<BlockPos> used, Set<BlockPos> has, BlockPos... positions) {
		for (BlockPos pos : positions) {
			if (!has.contains(pos) || used.contains(pos)) {
				return false;
			}
		}
		return true;
	}

	private static void claim(Set<BlockPos> used, List<Assignment> assignments, TableSize size, BlockPos... positions) {
		BlockPos origin = positions[0];
		for (BlockPos pos : positions) {
			used.add(pos);
			int x = pos.getX() - origin.getX();
			int z = pos.getZ() - origin.getZ();
			assignments.add(new Assignment(pos, size, x, z));
		}
	}

	public record Assignment(BlockPos pos, TableSize size, int x, int z) {
	}

	private record Held(BlockPos pos, TableSeat seat, ItemStack stack, SeatZones zones) {
	}
}
