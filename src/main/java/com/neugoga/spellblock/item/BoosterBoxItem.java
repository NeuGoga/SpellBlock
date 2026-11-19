package com.neugoga.spellblock.item;

import com.neugoga.spellblock.datapack.SpellBlockDataPacks;
import com.neugoga.spellblock.pack.PackDefinition;
import com.neugoga.spellblock.pack.Packs;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Opens into a fixed number of booster packs of one set. */
public final class BoosterBoxItem extends Item {
	private final String packId;
	private final int packCount;

	public BoosterBoxItem(Properties properties, String packId, int packCount) {
		super(properties);
		this.packId = packId;
		this.packCount = Math.max(1, packCount);
	}

	public String packId() {
		return packId;
	}

	public int packCount() {
		return packCount;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide) {
			return InteractionResultHolder.success(stack);
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.pass(stack);
		}
		PackDefinition packDef = Packs.get(packId);
		if (packDef == null) {
			serverPlayer.sendSystemMessage(Component.literal("Unknown pack in this box."));
			return InteractionResultHolder.fail(stack);
		}
		if (!SpellBlockDataPacks.packs().isBoxAllowed(packDef)) {
			serverPlayer.sendSystemMessage(Component.literal("This booster box is disabled by server rules."));
			return InteractionResultHolder.fail(stack);
		}
		Item packItem = SpellBlockItems.packItem(packId);
		if (packItem == null) {
			serverPlayer.sendSystemMessage(Component.literal("Unknown pack in this box."));
			return InteractionResultHolder.fail(stack);
		}
		int remaining = packCount;
		while (remaining > 0) {
			int give = Math.min(remaining, 64);
			ItemStack packs = new ItemStack(packItem, give);
			if (!serverPlayer.getInventory().add(packs) && !packs.isEmpty()) {
				ItemEntity dropped = serverPlayer.drop(packs, false);
				if (dropped != null) {
					dropped.setNoPickUpDelay();
				}
			}
			remaining -= give;
		}
		serverPlayer.sendSystemMessage(Component.literal("Opened a box: " + packCount + " boosters."));
		if (!serverPlayer.getAbilities().instabuild) {
			stack.shrink(1);
		}
		return InteractionResultHolder.consume(stack);
	}
}
