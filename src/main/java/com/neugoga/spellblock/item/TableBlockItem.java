package com.neugoga.spellblock.item;

import com.neugoga.spellblock.table.TableBlock;
import com.neugoga.spellblock.table.TableWood;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

public final class TableBlockItem extends BlockItem {
	private final TableWood wood;

	public TableBlockItem(TableBlock block, TableWood wood, Properties properties) {
		super(block, properties);
		this.wood = wood;
	}

	public TableWood wood() {
		return wood;
	}

	@Override
	protected BlockState getPlacementState(BlockPlaceContext context) {
		BlockState state = super.getPlacementState(context);
		if (state == null) {
			return null;
		}
		return state.setValue(TableBlock.WOOD, wood);
	}

	@Override
	public String getDescriptionId() {
		return "item.spellblock." + wood.itemId();
	}
}
