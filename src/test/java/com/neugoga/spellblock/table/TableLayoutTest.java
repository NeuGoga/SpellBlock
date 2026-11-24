package com.neugoga.spellblock.table;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TableLayoutTest {
	@Test
	void removingCornerOfSquareTableRebuildsIntoLongAndSingle() {
		// Original 2x2 minus (0, 0)
		List<BlockPos> remaining = List.of(
			new BlockPos(1, 64, 0),
			new BlockPos(0, 64, 1),
			new BlockPos(1, 64, 1)
		);
		Map<BlockPos, TableLayout.Assignment> byPos = index(TableLayout.plan(remaining));
		assertEquals(3, byPos.size());
		assertEquals(TableSize.SINGLE, byPos.get(new BlockPos(1, 64, 0)).size());
		assertEquals(0, byPos.get(new BlockPos(1, 64, 0)).x());
		assertEquals(0, byPos.get(new BlockPos(1, 64, 0)).z());

		assertEquals(TableSize.LONG_X, byPos.get(new BlockPos(0, 64, 1)).size());
		assertEquals(0, byPos.get(new BlockPos(0, 64, 1)).x());
		assertEquals(1, byPos.get(new BlockPos(1, 64, 1)).x());
	}

	@Test
	void removingHalfOfTwoBlockTableLeavesValidSingleTable() {
		Map<BlockPos, TableLayout.Assignment> byPos = index(TableLayout.plan(List.of(new BlockPos(5, 64, -3))));
		TableLayout.Assignment single = byPos.get(new BlockPos(5, 64, -3));
		assertEquals(TableSize.SINGLE, single.size());
		assertEquals(0, single.x());
		assertEquals(0, single.z());
	}

	private static Map<BlockPos, TableLayout.Assignment> index(List<TableLayout.Assignment> assignments) {
		Map<BlockPos, TableLayout.Assignment> out = new HashMap<>();
		for (TableLayout.Assignment a : assignments) {
			out.put(a.pos(), a);
		}
		return out;
	}
}
