package com.neugoga.spellblock.table;

import com.mojang.serialization.MapCodec;

import com.neugoga.spellblock.item.DeckBoxItem;
import com.neugoga.spellblock.network.TableServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One block of a card table. Neighbours of the same wood join into a bigger top. */
public final class TableBlock extends Block implements EntityBlock {
	public static final MapCodec<TableBlock> CODEC = simpleCodec(TableBlock::new);
	public static final EnumProperty<TableWood> WOOD = EnumProperty.create("wood", TableWood.class);
	public static final EnumProperty<TableSize> SIZE = EnumProperty.create("size", TableSize.class);
	public static final IntegerProperty X = IntegerProperty.create("x", 0, 1);
	public static final IntegerProperty Z = IntegerProperty.create("z", 0, 1);

	private static final VoxelShape[][][] SHAPES = buildShapes();

	public TableBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
			.setValue(WOOD, TableWood.OAK)
			.setValue(SIZE, TableSize.SINGLE)
			.setValue(X, 0)
			.setValue(Z, 0));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(WOOD, SIZE, X, Z);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TableBlockEntity(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.ENTITYBLOCK_ANIMATED;
	}

	@Override
	protected SoundType getSoundType(BlockState state) {
		return state.getValue(WOOD).sound();
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(this)) {
			TableLayout.rebuild(level, pos, state.getValue(WOOD));
		}
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!newState.is(this)) {
			if (!level.isClientSide()) {
				dropBoxes(level, pos, state);
			}
			TableLayout.rebuild(level, pos, state.getValue(WOOD));
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	@Override
	protected ItemInteractionResult useItemOn(
		ItemStack stack,
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		InteractionHand hand,
		BlockHitResult hit
	) {
		if (player.isSpectator() || !(stack.getItem() instanceof DeckBoxItem) || !player.isShiftKeyDown()) {
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}
		if (level.isClientSide()) {
			return ItemInteractionResult.SUCCESS;
		}
		if (tryPlace(level, pos, state, player, stack, hit)) {
			return ItemInteractionResult.SUCCESS;
		}
		return ItemInteractionResult.CONSUME;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (player.isShiftKeyDown()) {
			if (!player.getMainHandItem().isEmpty()) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide()) {
				return InteractionResult.SUCCESS;
			}
			return take(level, pos, state, player, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			return TableServer.open(serverPlayer, pos, state, hit) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		return InteractionResult.PASS;
	}

	/** Used by the block and by the deck-box item (sneak skips the block path). */
	public static boolean tryPlace(Level level, BlockPos pos, BlockState state, Player player, ItemStack stack, BlockHitResult hit) {
		TableSeat seat = TableSeat.choose(player, hit, state, pos);
		TableBlockEntity table = origin(level, pos, state);
		if (seat == null || table == null) {
			return false;
		}
		TableSize size = state.getValue(SIZE);
		if (!table.get(seat).isEmpty() || table.count() >= TableSeat.capacity(size)) {
			return false;
		}
		table.put(seat, stack.split(1));
		level.playSound(null, pos, SoundEvents.CHISELED_BOOKSHELF_INSERT, SoundSource.BLOCKS, 0.8f, 1.0f);
		return true;
	}

	private static boolean take(Level level, BlockPos pos, BlockState state, Player player, BlockHitResult hit) {
		TableSeat seat = TableSeat.choose(player, hit, state, pos);
		TableBlockEntity table = origin(level, pos, state);
		if (seat == null || table == null || table.get(seat).isEmpty()) {
			return false;
		}
		ItemStack stack = table.take(seat);
		if (!player.getInventory().add(stack) || !stack.isEmpty()) {
			table.put(seat, stack);
			return false;
		}
		level.playSound(null, pos, SoundEvents.CHISELED_BOOKSHELF_PICKUP, SoundSource.BLOCKS, 0.8f, 1.0f);
		return true;
	}

	private static TableBlockEntity origin(Level level, BlockPos pos, BlockState state) {
		BlockPos origin = pos.offset(-state.getValue(X), 0, -state.getValue(Z));
		if (level.getBlockEntity(origin) instanceof TableBlockEntity table) {
			return table;
		}
		return null;
	}

	private static void dropBoxes(Level level, BlockPos pos, BlockState state) {
		TableSize size = state.getValue(SIZE);
		BlockPos origin = pos.offset(-state.getValue(X), 0, -state.getValue(Z));
		for (int x = 0; x < size.width(); x++) {
			for (int z = 0; z < size.depth(); z++) {
				dropAt(level, origin.offset(x, 0, z));
			}
		}
		dropAt(level, pos);
	}

	private static void dropAt(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof TableBlockEntity table) {
			table.dropAll();
		}
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape(state);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shape(state);
	}

	@Override
	public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
		return new ItemStack(TableBlocks.item(state.getValue(WOOD)));
	}

	private static VoxelShape shape(BlockState state) {
		return SHAPES[state.getValue(SIZE).ordinal()][state.getValue(X)][state.getValue(Z)];
	}

	private static VoxelShape[][][] buildShapes() {
		VoxelShape[][][] shapes = new VoxelShape[TableSize.values().length][2][2];
		for (TableSize size : TableSize.values()) {
			for (int x = 0; x < 2; x++) {
				for (int z = 0; z < 2; z++) {
					shapes[size.ordinal()][x][z] = shapeFor(size, x, z);
				}
			}
		}
		return shapes;
	}

	private static VoxelShape shapeFor(TableSize size, int ox, int oz) {
		if (ox >= size.width() || oz >= size.depth()) {
			return Shapes.block();
		}
		VoxelShape shape = Shapes.empty();
		for (TableParts.Part part : TableParts.of(size)) {
			if (part.cloth()) {
				continue;
			}
			double x0 = Math.max(part.x0(), ox);
			double x1 = Math.min(part.x1(), ox + 1.0);
			double z0 = Math.max(part.z0(), oz);
			double z1 = Math.min(part.z1(), oz + 1.0);
			if (x1 - x0 < 1.0e-4 || z1 - z0 < 1.0e-4 || part.y1() - part.y0() < 1.0e-4) {
				continue;
			}
			shape = Shapes.or(shape, Block.box(
				clamp((x0 - ox) * 16.0),
				clamp(part.y0() * 16.0),
				clamp((z0 - oz) * 16.0),
				clamp((x1 - ox) * 16.0),
				clamp(part.y1() * 16.0),
				clamp((z1 - oz) * 16.0)
			));
		}
		// Cloth surface is the walkable top without the hanging part.
		double top = TableParts.WOOD_TOP * 16.0;
		shape = Shapes.or(shape, Block.box(0, top, 0, 16, 16, 16));
		return shape;
	}

	private static double clamp(double v) {
		if (v < 0) {
			return 0;
		}
		if (v > 16) {
			return 16;
		}
		return v;
	}
}
