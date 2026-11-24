package com.neugoga.spellblock.network.table;

import com.neugoga.spellblock.table.SeatZones;
import com.neugoga.spellblock.table.TableBlock;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableSeat;
import com.neugoga.spellblock.table.TableZone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

import static com.neugoga.spellblock.network.table.TableSync.refreshReveals;
import static com.neugoga.spellblock.network.table.TableSync.send;
import static com.neugoga.spellblock.network.table.TableText.pretty;
import static com.neugoga.spellblock.network.table.TableText.seatOf;

/**
 * Showing a hand, or one hand card, to the table or to one seat.
 */
public final class Reveals {
	private Reveals() {
	}

	public static void revealCard(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, String target, int cardId) {
		SeatZones zones = table.zones(seat);
		if (zones.indexOfId(TableZone.HAND, cardId) < 0) {
			send(player, origin, seat, table, "");
			return;
		}
		String message;
		if (target == null || target.isEmpty() || "off".equals(target)) {
			zones.setReveal("");
			message = "Card hidden.";
		} else if ("ALL".equals(target)) {
			zones.setReveal("ALL#" + cardId);
			message = "Card shown to the table.";
		} else {
			TableSeat other = seatOf(target);
			BlockState state = level.getBlockState(origin);
			boolean fits = other != null
				&& other != seat
				&& state.getBlock() instanceof TableBlock
				&& TableSeat.fits(state.getValue(TableBlock.SIZE), other)
				&& !table.get(other).isEmpty();
			if (!fits) {
				send(player, origin, seat, table, "That seat is not at the table.");
				return;
			}
			zones.setReveal(other.name() + "#" + cardId);
			message = "Card shown to " + pretty(other) + ".";
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.45f, 1.5f);
		send(player, origin, seat, table, message);
		refreshReveals(level, origin, table);
	}

	public static void revealHand(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat seat, String target) {
		SeatZones zones = table.zones(seat);
		String message;
		if (target == null || target.isEmpty() || "off".equals(target)) {
			zones.setReveal("");
			message = "Hand hidden.";
		} else if ("ALL".equals(target)) {
			zones.setReveal("ALL");
			message = "Hand shown to the table.";
		} else {
			TableSeat other = seatOf(target);
			BlockState state = level.getBlockState(origin);
			boolean fits = other != null
				&& other != seat
				&& state.getBlock() instanceof TableBlock
				&& TableSeat.fits(state.getValue(TableBlock.SIZE), other)
				&& !table.get(other).isEmpty();
			if (!fits) {
				send(player, origin, seat, table, "That seat is not at the table.");
				return;
			}
			zones.setReveal(other.name());
			message = "Hand shown to " + pretty(other) + ".";
		}
		table.sync();
		level.playSound(null, origin, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.45f, 1.4f);
		send(player, origin, seat, table, message);
		refreshReveals(level, origin, table);
	}
}
