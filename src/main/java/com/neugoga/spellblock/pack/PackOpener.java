package com.neugoga.spellblock.pack;

import com.neugoga.spellblock.datapack.CardRestrictions;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.library.BinderCard;
import com.neugoga.spellblock.library.CardKey;
import com.neugoga.spellblock.library.Finish;
import com.neugoga.spellblock.library.LibraryWorldData;
import com.neugoga.spellblock.library.PlayerLibrary;
import com.neugoga.spellblock.network.PackOpenS2CPayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rolls a pack on the server, saves every card, then tells the client to play the reveal. */
public final class PackOpener {
	private PackOpener() {
	}

	/** @return an error to show the player, or null when the pack was opened */
	public static String tryOpen(ServerPlayer player, String packId) {
		PackDefinition pack = Packs.get(packId);
		if (pack == null) {
			return "Unknown pack.";
		}
		if (!SpellBlockDataPacks.packs().isPackEnabled(pack)) {
			return "This pack is disabled by server rules.";
		}
		SetPool pool = SetCatalog.pool(pack.setCode());
		if (pool == null) {
			String detail = SetCatalog.detail(pack.setCode());
			if (detail.startsWith("failed:")) {
				return "The " + pack.setCode() + " card list failed: " + detail.substring("failed:".length()).trim();
			}
			return "The " + pack.setCode() + " card list is still downloading (" + detail + ").";
		}
		Rolled rolled = roll(pack, pool, player.getRandom(), SpellBlockDataPacks.cards());
		if (rolled.cards() == null) {
			return "The " + pack.setCode() + " list has no allowed " + rolled.emptyPool() + " cards.";
		}
		LibraryWorldData data = LibraryWorldData.get(player.getServer());
		PlayerLibrary library = data.getOrCreate(player.getUUID());
		for (BinderCard card : rolled.cards()) {
			library.add(new CardKey(card.oracleId(), card.printingId(), Finish.fromName(card.finish())), 1);
		}
		data.setDirty();
		ServerPlayNetworking.send(player, new PackOpenS2CPayload(pack.name(), rolled.cards()));
		return null;
	}

	private static Rolled roll(PackDefinition pack, SetPool pool, RandomSource random, CardRestrictions restrictions) {
		List<BinderCard> cards = new ArrayList<>(pack.slots().size());
		Set<String> used = new HashSet<>();
		for (PackDefinition.Slot slot : pack.slots()) {
			PackDefinition.Option option = choose(slot, random);
			Printing printing = pool.pick(option.pool(), random, used, p -> !restrictions.isDisabled(p));
			if (printing == null) {
				return new Rolled(null, option.pool());
			}
			used.add(printing.oracleId());
			Finish finish = option.foil() ? Finish.FOIL : Finish.NONFOIL;
			cards.add(new BinderCard(
				printing.oracleId(),
				printing.printingId(),
				finish.name(),
				1,
				printing.name(),
				printing.manaCost(),
				printing.manaValue(),
				printing.typeLine(),
				printing.colorIdentity(),
				printing.rarity(),
				printing.setCode(),
				printing.treatment()
			));
		}
		return new Rolled(cards, "");
	}

	private static PackDefinition.Option choose(PackDefinition.Slot slot, RandomSource random) {
		int total = 0;
		for (PackDefinition.Option option : slot.options()) {
			total += option.weight();
		}
		int roll = random.nextInt(total);
		int cursor = 0;
		for (PackDefinition.Option option : slot.options()) {
			cursor += option.weight();
			if (roll < cursor) {
				return option;
			}
		}
		return slot.options().getLast();
	}

	private record Rolled(List<BinderCard> cards, String emptyPool) {
	}
}
