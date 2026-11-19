package com.neugoga.spellblock.item;

import com.neugoga.spellblock.library.DeckBoxData;
import com.neugoga.spellblock.library.DeckFormat;
import com.neugoga.spellblock.network.DeckServer;
import com.neugoga.spellblock.table.TableBlock;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/** A deck lives on this item. Dropping the box hands over every card inside it. */
public final class DeckBoxItem extends Item {
	public static final String[] COLORS = {"blue", "red", "white", "green", "black", "grey"};

	private final String color;

	public DeckBoxItem(Properties properties, String color) {
		super(properties.stacksTo(1).component(SpellBlockComponents.DECK, DeckBoxData.empty()));
		this.color = color;
	}

	public String color() {
		return color;
	}

	public static boolean knownColor(String color) {
		for (String known : COLORS) {
			if (known.equals(color)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Sneak skips the block's useItemOn. Place the box from the item so shift-right-click
	 * on a table does not open the deck builder.
	 */
	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		Level level = context.getLevel();
		if (player == null || player.isSpectator() || !player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		BlockPos pos = context.getClickedPos();
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof TableBlock)) {
			return InteractionResult.PASS;
		}
		BlockHitResult hit = new BlockHitResult(
			context.getClickLocation(),
			context.getClickedFace(),
			pos,
			context.isInside()
		);
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (TableBlock.tryPlace(level, pos, state, player, context.getItemInHand(), hit)) {
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.CONSUME;
	}

	@Override
	public Component getName(ItemStack stack) {
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		if (data.name() != null && !data.name().isEmpty()) {
			return Component.literal(data.name());
		}
		return super.getName(stack);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.success(stack);
		}
		int slot = hand == InteractionHand.MAIN_HAND ? serverPlayer.getInventory().selected : Inventory.SLOT_OFFHAND;
		DeckServer.open(serverPlayer, slot);
		return InteractionResultHolder.consume(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		DeckBoxData data = stack.getOrDefault(SpellBlockComponents.DECK, DeckBoxData.empty());
		tooltip.add(Component.literal(DeckFormat.fromId(data.format()).label()).withStyle(ChatFormatting.GRAY));
		int library = data.librarySize();
		int side = data.side().stream().mapToInt(e -> e.count()).sum();
		if (side > 0) {
			tooltip.add(Component.literal(library + " library · " + side + " sideboard").withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(Component.literal(library + (library == 1 ? " card" : " cards")).withStyle(ChatFormatting.GRAY));
		}
		if (data.locked()) {
			tooltip.add(Component.literal("Locked").withStyle(ChatFormatting.RED));
		}
		tooltip.add(Component.literal("Right-click to build").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.add(Component.literal("Sneak and right-click a table to seat it").withStyle(ChatFormatting.DARK_GRAY));
	}
}
