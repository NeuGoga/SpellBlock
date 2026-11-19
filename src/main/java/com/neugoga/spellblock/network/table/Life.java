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
import static com.neugoga.spellblock.network.table.TableSync.send;
import static com.neugoga.spellblock.network.table.TableText.seatOf;

/**
 * Life total changes.
 */
public final class Life {
	private Life() {
	}

	public static void changeLife(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat viewer, String targetName, int delta) {
		if (delta != 1 && delta != -1 && delta != 5 && delta != -5) {
			send(player, origin, viewer, table, "");
			return;
		}
		TableSeat target = seatOf(targetName);
		BlockState state = level.getBlockState(origin);
		if (target == null || !(state.getBlock() instanceof TableBlock) || !TableSeat.fits(state.getValue(TableBlock.SIZE), target)) {
			send(player, origin, viewer, table, "");
			return;
		}
		ItemStack stack = table.get(target);
		if (stack.isEmpty()) {
			send(player, origin, viewer, table, "No deck box on that side.");
			return;
		}
		SeatZones zones = table.zones(target);
		zones.setLife(zones.shownLife(stack) + delta);
		table.sync();
		level.playSound(null, origin, SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.35f, 1.4f);
		send(player, origin, viewer, table, "");
	}

	/** Set life to an absolute value (e.g. 20 or 40). */
	public static void setLife(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat viewer, String targetName, int value) {
		if (value < 0) {
			value = 0;
		}
		if (value > 999) {
			value = 999;
		}
		TableSeat target = seatOf(targetName);
		BlockState state = level.getBlockState(origin);
		if (target == null || !(state.getBlock() instanceof TableBlock) || !TableSeat.fits(state.getValue(TableBlock.SIZE), target)) {
			send(player, origin, viewer, table, "");
			return;
		}
		ItemStack stack = table.get(target);
		if (stack.isEmpty()) {
			send(player, origin, viewer, table, "No deck box on that side.");
			return;
		}
		SeatZones zones = table.zones(target);
		zones.setLife(value);
		table.sync();
		level.playSound(null, origin, SoundEvents.WOODEN_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.35f, 1.1f);
		send(player, origin, viewer, table, "");
	}

	/** +1 commander damage from an opponent seat and −1 life on the target seat. */
	public static void commanderDamage(ServerPlayer player, ServerLevel level, BlockPos origin, TableBlockEntity table, TableSeat viewer, String targetName, String fromName, int delta) {
		if (delta == 0) {
			delta = 1;
		}
		TableSeat target = seatOf(targetName);
		TableSeat from = seatOf(fromName);
		if (target == null || from == null || target == from) {
			send(player, origin, viewer, table, "");
			return;
		}
		if (table.get(target).isEmpty()) {
			send(player, origin, viewer, table, "No one sits there.");
			return;
		}
		SeatZones zones = table.zones(target);
		ItemStack stack = table.get(target);
		int before = zones.commanderDamageFrom(from.name());
		zones.addCommanderDamage(from.name(), delta);
		int applied = zones.commanderDamageFrom(from.name()) - before;
		zones.setLife(zones.shownLife(stack) - applied);
		table.sync();
		level.playSound(null, origin, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.25f, 1.4f);
		send(player, origin, viewer, table, "");
	}
}
