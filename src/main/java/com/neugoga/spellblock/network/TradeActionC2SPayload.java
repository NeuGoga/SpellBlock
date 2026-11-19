package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client-to-server actions inside the Trade screen (add, remove, lock, cancel_countdown, close). */
public record TradeActionC2SPayload(
	String action,
	String oracleId,
	String printingId,
	String finish,
	int amount
) implements CustomPacketPayload {
	public static final Type<TradeActionC2SPayload> TYPE = new Type<>(SpellBlock.id("trade_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TradeActionC2SPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.action == null ? "" : payload.action);
			buf.writeUtf(payload.oracleId == null ? "" : payload.oracleId);
			buf.writeUtf(payload.printingId == null ? "" : payload.printingId);
			buf.writeUtf(payload.finish == null ? "" : payload.finish);
			buf.writeVarInt(payload.amount);
		},
		buf -> new TradeActionC2SPayload(
			buf.readUtf(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readVarInt()
		)
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
