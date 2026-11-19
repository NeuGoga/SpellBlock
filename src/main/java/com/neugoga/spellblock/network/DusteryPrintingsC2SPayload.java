package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Request all unique paper printings for a given oracle id from the Dustery Craft screen. */
public record DusteryPrintingsC2SPayload(String oracleId) implements CustomPacketPayload {
	public static final Type<DusteryPrintingsC2SPayload> TYPE = new Type<>(SpellBlock.id("dustery_printings_c2s"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DusteryPrintingsC2SPayload> CODEC = StreamCodec.of(
		(buf, payload) -> buf.writeUtf(payload.oracleId == null ? "" : payload.oracleId),
		buf -> new DusteryPrintingsC2SPayload(buf.readUtf())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
