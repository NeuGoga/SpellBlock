package com.neugoga.spellblock.network.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.catalog.OracleIndex;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.item.SpellBlockComponents;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.table.SeatZones;
import com.neugoga.spellblock.table.TableBlock;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableCard;
import com.neugoga.spellblock.table.TableSeat;
import com.neugoga.spellblock.table.TableZone;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Seat names and small text and number parsing for table actions.
 */
public final class TableText {
	private TableText() {
	}

	public static String prettySeatName(String name) {
		TableSeat seat = seatOf(name);
		return seat == null ? (name == null || name.isEmpty() ? "?" : name) : pretty(seat);
	}

	public static String pretty(TableSeat seat) {
		String name = seat.name().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	public static int parseCount(String action) {
		int at = action.indexOf(':');
		if (at < 0 || at + 1 >= action.length()) {
			return 0;
		}
		try {
			return Integer.parseInt(action.substring(at + 1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static TableSeat seatOf(String name) {
		try {
			return TableSeat.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
