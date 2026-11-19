package com.neugoga.spellblock.item;

import com.neugoga.spellblock.pack.PackOpener;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class PackItem extends Item {
	private final String packId;

	public PackItem(Properties properties, String packId) {
		super(properties);
		this.packId = packId;
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
		String error = PackOpener.tryOpen(serverPlayer, packId);
		if (error != null) {
			serverPlayer.sendSystemMessage(Component.literal(error));
			return InteractionResultHolder.fail(stack);
		}
		if (!serverPlayer.getAbilities().instabuild) {
			stack.shrink(1);
		}
		return InteractionResultHolder.consume(stack);
	}
}
