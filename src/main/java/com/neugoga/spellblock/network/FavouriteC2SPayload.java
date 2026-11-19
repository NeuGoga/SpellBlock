package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Toggle a favourite printing in the player's collection. */
public record FavouriteC2SPayload(
	String oracleId,
	String printingId,
	String finish,
	String refresh
) implements CustomPacketPayload {
	public static final Type<FavouriteC2SPayload> TYPE = new Type<>(SpellBlock.id("favourite"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FavouriteC2SPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.oracleId == null ? "" : payload.oracleId);
			buf.writeUtf(payload.printingId == null ? "" : payload.printingId);
			buf.writeUtf(payload.finish == null ? "" : payload.finish);
			buf.writeUtf(payload.refresh == null ? "" : payload.refresh);
		},
		buf -> new FavouriteC2SPayload(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
