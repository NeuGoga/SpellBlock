package com.neugoga.spellblock.dustery;

import com.mojang.serialization.MapCodec;
import com.neugoga.spellblock.network.DusteryServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Arcane card-dusting and crafting desk: right-click opens the Dustery menu. */
public final class DusteryBlock extends Block {
	public static final MapCodec<DusteryBlock> CODEC = simpleCodec(DusteryBlock::new);
	private static final VoxelShape SHAPE = Shapes.or(
		Block.box(1, 0, 1, 15, 2, 15),
		Block.box(2, 2, 2, 14, 12, 14),
		Block.box(0, 12, 0, 16, 15, 16),
		Block.box(5, 15, 5, 11, 16, 11)
	);

	public DusteryBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
			DusteryServer.open(serverPlayer, pos);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!newState.is(this) && !level.isClientSide) {
			DusteryServer.onBlockRemoved(level, pos);
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}
}
