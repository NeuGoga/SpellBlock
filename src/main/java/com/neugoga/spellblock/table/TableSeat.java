package com.neugoga.spellblock.table;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Function;

/** A side of a joined table. A 1x1 has one seat, a 2x1 has the two long sides, a 2x2 has four. */
public enum TableSeat {
	NORTH,
	EAST,
	SOUTH,
	WEST;

	public static TableSeat from(Direction direction) {
		return switch (direction) {
			case EAST -> EAST;
			case SOUTH -> SOUTH;
			case WEST -> WEST;
			default -> NORTH;
		};
	}

	public static boolean fits(TableSize size, TableSeat seat) {
		return switch (size) {
			case SINGLE -> true;
			case LONG_X -> seat == NORTH || seat == SOUTH;
			case LONG_Z -> seat == EAST || seat == WEST;
			case SQUARE -> true;
		};
	}

	public static int capacity(TableSize size) {
		return switch (size) {
			case SINGLE -> 1;
			case LONG_X, LONG_Z -> 2;
			case SQUARE -> 4;
		};
	}

	/** Keeps the old side when the new size still has it. Otherwise the first free side, so a join does not drop the box. */
	public static TableSeat open(TableSize size, TableSeat preferred, Function<TableSeat, ItemStack> occupied) {
		if (preferred != null && fits(size, preferred) && occupied.apply(preferred).isEmpty()) {
			return preferred;
		}
		if (preferred != null && fits(size, preferred)) {
			return null;
		}
		for (TableSeat seat : values()) {
			if (fits(size, seat) && occupied.apply(seat).isEmpty()) {
				return seat;
			}
		}
		return null;
	}

	/** The side the player is using. A face that is not a seat falls back to where they stand. */
	public static TableSeat choose(Player player, BlockHitResult hit, BlockState state, BlockPos pos) {
		TableSize size = state.getValue(TableBlock.SIZE);
		Direction face = hit.getDirection();
		if (face.getAxis().isHorizontal()) {
			TableSeat side = from(face);
			if (fits(size, side)) {
				return side;
			}
		}
		int ox = state.getValue(TableBlock.X);
		int oz = state.getValue(TableBlock.Z);
		double centerX = pos.getX() - ox + size.width() / 2.0;
		double centerZ = pos.getZ() - oz + size.depth() / 2.0;
		double dx = player.getX() - centerX;
		double dz = player.getZ() - centerZ;
		TableSeat side = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? EAST : WEST) : (dz > 0 ? SOUTH : NORTH);
		return fits(size, side) ? side : null;
	}
}
