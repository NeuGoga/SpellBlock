package com.neugoga.spellblock.table;

import java.util.ArrayList;
import java.util.List;

/**
 * Anvil-shaped table in block units, origin at the north-west corner.
 * The foot, plinth, and stem sit in the middle and stretch with the top.
 */
public final class TableParts {
	public static final double OVERHANG = 2.0 / 16.0;
	public static final double FOOT_TOP = 3.0 / 16.0;
	public static final double PLINTH_TOP = 5.0 / 16.0;
	public static final double STEM_TOP = 10.0 / 16.0;
	public static final double WOOD_TOP = 13.0 / 16.0;
	public static final double SKIRT_BOTTOM = 6.0 / 16.0;

	private TableParts() {
	}

	public static List<Part> of(TableSize size) {
		double width = size.width();
		double depth = size.depth();
		double extraX = size.width() - 1;
		double extraZ = size.depth() - 1;
		double footX = 12.0 / 16.0 + extraX * 8.0 / 16.0;
		double footZ = 12.0 / 16.0 + extraZ * 8.0 / 16.0;
		double plinthX = 8.0 / 16.0 + extraX * 6.0 / 16.0;
		double plinthZ = 8.0 / 16.0 + extraZ * 6.0 / 16.0;
		double stemX = 4.0 / 16.0 + extraX * 4.0 / 16.0;
		double stemZ = 4.0 / 16.0 + extraZ * 4.0 / 16.0;
		double cx = width / 2.0;
		double cz = depth / 2.0;
		List<Part> parts = new ArrayList<>();
		parts.add(wood(cx - footX / 2.0, 0, cz - footZ / 2.0, cx + footX / 2.0, FOOT_TOP, cz + footZ / 2.0));
		parts.add(wood(cx - plinthX / 2.0, FOOT_TOP, cz - plinthZ / 2.0, cx + plinthX / 2.0, PLINTH_TOP, cz + plinthZ / 2.0));
		parts.add(wood(cx - stemX / 2.0, PLINTH_TOP, cz - stemZ / 2.0, cx + stemX / 2.0, STEM_TOP, cz + stemZ / 2.0));
		parts.add(wood(0, STEM_TOP, 0, width, WOOD_TOP, depth));
		parts.add(cloth(-OVERHANG, WOOD_TOP, -OVERHANG, width + OVERHANG, 1.0, depth + OVERHANG));
		addSkirts(parts, width, depth);
		return List.copyOf(parts);
	}

	private static void addSkirts(List<Part> parts, double width, double depth) {
		double hang = SKIRT_BOTTOM;
		double top = WOOD_TOP;
		double outer = OVERHANG;
		double thick = 1.0 / 16.0;
		parts.add(cloth(-outer, hang, 0, -outer + thick, top, depth));
		parts.add(cloth(width + outer - thick, hang, 0, width + outer, top, depth));
		parts.add(cloth(0, hang, -outer, width, top, -outer + thick));
		parts.add(cloth(0, hang, depth + outer - thick, width, top, depth + outer));
		parts.add(cloth(-outer, hang, -outer, 0, top, 0));
		parts.add(cloth(width, hang, -outer, width + outer, top, 0));
		parts.add(cloth(-outer, hang, depth, 0, top, depth + outer));
		parts.add(cloth(width, hang, depth, width + outer, top, depth + outer));
	}

	private static Part wood(double x0, double y0, double z0, double x1, double y1, double z1) {
		return new Part(x0, y0, z0, x1, y1, z1, false);
	}

	private static Part cloth(double x0, double y0, double z0, double x1, double y1, double z1) {
		return new Part(x0, y0, z0, x1, y1, z1, true);
	}

	public record Part(double x0, double y0, double z0, double x1, double y1, double z1, boolean cloth) {
	}
}
