package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Dustery actions from the client.
 * action: "dust" or "craft"
 * amount: copies to disenchant (dust) or ignored for craft (always 1).
 */
public record DusteryActionC2SPayload(
	String action,
	String oracleId,
	String printingId,
	String finish,
	int amount
) implements CustomPacketPayload {
	public static final Type<DusteryActionC2SPayload> TYPE = new Type<>(SpellBlock.id("dustery_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DusteryActionC2SPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.action == null ? "" : payload.action);
			buf.writeUtf(payload.oracleId == null ? "" : payload.oracleId);
			buf.writeUtf(payload.printingId == null ? "" : payload.printingId);
			buf.writeUtf(payload.finish == null ? "" : payload.finish);
			buf.writeVarInt(payload.amount);
		},
		buf -> new DusteryActionC2SPayload(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
