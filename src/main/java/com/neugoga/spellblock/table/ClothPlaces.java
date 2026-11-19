package com.neugoga.spellblock.table;

import java.util.ArrayList;
import java.util.List;

/**
 * A rough picture of one seat on the cloth. Cards stay on that player's side, in their own
 * spots, and stop well short of the middle. The table screen is where you actually play.
 */
public final class ClothPlaces {
	private ClothPlaces() {
	}

	/** One drawn card. Layer 0 is the bottom of a stack. */
	public record Spot(
		TableSeat seat,
		String zone,
		int index,
		float x,
		float z,
		float w,
		float h,
		int layer,
		int count
	) {
	}

	/** True when this seat can use more of the cloth. Another occupied seat keeps everyone on their own side. */
	public static boolean roomy(TableBlockEntity entity) {
		int occupied = 0;
		for (TableSeat seat : TableSeat.values()) {
			if (!entity.get(seat).isEmpty() || !entity.zones(seat).isEmpty()) {
				occupied++;
			}
		}
		return occupied <= 1;
	}

	/** Deck box center, in the player's near-left corner. */
	public static float[] box(TableSize size, TableSeat seat) {
		float inset = 0.20f;
		return switch (seat) {
			case SOUTH -> new float[] {inset, (float) size.depth() - inset};
			case NORTH -> new float[] {(float) size.width() - inset, inset};
			case WEST -> new float[] {inset, inset};
			case EAST -> new float[] {(float) size.width() - inset, (float) size.depth() - inset};
		};
	}

	/**
	 * Small cards in three rows, packed against the player's edge.
	 * Lands are closest, then other cards, then creatures. Nothing is stacked on the same spot
	 * unless one row has more cards than the side can hold.
	 */
	public static List<Spot> play(
		TableSize size,
		TableSeat seat,
		List<TableCard> battlefield,
		List<String> rows,
		List<TableCard> graveyard,
		List<TableCard> exile,
		boolean roomy
	) {
		ArrayList<Spot> out = new ArrayList<>();
		Metrics metrics = metrics(size, seat, roomy);
		addRow(out, size, seat, battlefield, rows, "creature", 2, metrics);
		addRow(out, size, seat, battlefield, rows, "other", 1, metrics);
		addRow(out, size, seat, battlefield, rows, "down", 1, metrics);
		addRow(out, size, seat, battlefield, rows, "land", 0, metrics);
		addPile(out, size, seat, graveyard, "grave", false, metrics);
		addPile(out, size, seat, exile, "exile", true, metrics);
		return out;
	}

	/** The topmost spot under this cloth point, or null. */
	public static Spot pick(List<Spot> spots, double x, double z) {
		Spot hit = null;
		for (Spot spot : spots) {
			if (x < spot.x || x > spot.x + spot.w || z < spot.z || z > spot.z + spot.h) {
				continue;
			}
			hit = spot;
		}
		return hit;
	}

	private record Metrics(
		float edge,
		float along0,
		float along1,
		float along,
		float inward,
		float gap,
		double span
	) {
	}

	private static Metrics metrics(TableSize size, TableSeat seat, boolean roomy) {
		boolean alongZ = seat == TableSeat.EAST || seat == TableSeat.WEST;
		double span = alongZ ? size.depth() : size.width();
		double depth = alongZ ? size.width() : size.depth();
		float edge = 0.07f;
		float limit = roomy ? (float) depth - 0.08f : Math.min(0.62f, (float) depth * 0.40f);
		float inward = 0.145f;
		float gap = 0.008f;
		float used = edge + 3.0f * inward + 2.0f * gap;
		if (used > limit) {
			inward = Math.max(0.07f, (limit - edge - 2.0f * gap) / 3.0f);
		}
		float along = inward * 0.716f;
		float boxClear = Math.min(0.42f, (float) span * 0.30f);
		float pileClear = Math.min(0.32f, (float) span * 0.26f);
		float along0 = boxClear;
		float along1 = (float) span - pileClear;
		if (along1 - along0 < 0.28f) {
			along0 = 0.06f;
			along1 = (float) span - 0.22f;
		}
		return new Metrics(edge, along0, along1, along, inward, gap, span);
	}

	private static void addRow(
		List<Spot> out,
		TableSize size,
		TableSeat seat,
		List<TableCard> battlefield,
		List<String> rows,
		String role,
		int row,
		Metrics metrics
	) {
		ArrayList<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < battlefield.size(); i++) {
			String have = i < rows.size() ? rows.get(i) : "";
			if (have == null || have.isEmpty()) {
				have = "other";
			}
			if (role.equals(have)) {
				indexes.add(i);
			}
		}
		if (indexes.isEmpty()) {
			return;
		}
		float gap = metrics.gap;
		float usable = Math.max(0.12f, metrics.along1 - metrics.along0);
		int count = indexes.size();
		float along = metrics.along;
		float inward = metrics.inward;
		float need = count * along + (count - 1) * gap;
		float step = along + gap;
		if (need > usable) {
			along = (usable - (count - 1) * gap) / count;
			if (along < 0.045f) {
				along = 0.045f;
				step = count == 1 ? along : (usable - along) / (count - 1);
			} else {
				step = along + gap;
			}
			inward = Math.min(metrics.inward, along / 0.716f);
			need = along + (count - 1) * step;
		}
		float start = metrics.along0 + Math.max(0.0f, (usable - need) / 2.0f);
		float inwardPos = metrics.edge + row * (metrics.inward + metrics.gap);
		for (int i = 0; i < count; i++) {
			float alongPos = start + i * step;
			float[] at = world(size, seat, alongPos, inwardPos, along, inward);
			out.add(new Spot(seat, "field", indexes.get(i), at[0], at[1], at[2], at[3], 0, 1));
		}
	}

	/** Graveyard, then exile, in the player's near-right corner. */
	private static void addPile(
		List<Spot> out,
		TableSize size,
		TableSeat seat,
		List<TableCard> cards,
		String zone,
		boolean exile,
		Metrics metrics
	) {
		if (cards.isEmpty()) {
			return;
		}
		float w = 0.11f;
		float h = 0.15f;
		float alongPos = exile ? (float) metrics.span - 0.13f : (float) metrics.span - 0.26f;
		if (alongPos < 0.02f) {
			alongPos = exile ? 0.14f : 0.02f;
		}
		float[] at = world(size, seat, alongPos, metrics.edge, w, h);
		int top = cards.size() - 1;
		out.add(new Spot(seat, zone, top, at[0], at[1], at[2], at[3], 0, cards.size()));
	}

	/** alongPos is from the player's left. inward is from the player's edge toward the middle. */
	private static float[] world(TableSize size, TableSeat seat, float alongPos, float inward, float along, float inwardSize) {
		return switch (seat) {
			case NORTH -> new float[] {(float) size.width() - alongPos - along, inward, along, inwardSize};
			case SOUTH -> new float[] {alongPos, (float) size.depth() - inward - inwardSize, along, inwardSize};
			case WEST -> new float[] {inward, alongPos, inwardSize, along};
			case EAST -> new float[] {(float) size.width() - inward - inwardSize, (float) size.depth() - alongPos - along, inwardSize, along};
		};
	}
}
