package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.NotNull;

/** A hand action at one seat. The server checks the table before it moves a card. */
public record TableActionC2SPayload(BlockPos pos, String seat, String action, int cardId, int amount) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<TableActionC2SPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("table_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TableActionC2SPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeBlockPos(payload.pos);
				buf.writeUtf(payload.seat, 16);
				buf.writeUtf(payload.action, 64);
				buf.writeVarInt(payload.cardId);
				buf.writeVarInt(payload.amount);
			},
			buf -> new TableActionC2SPayload(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt())
	);

	public TableActionC2SPayload {
		seat = seat == null ? "" : seat;
		action = action == null ? "" : action;
	}

	@Override
	public @NotNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}