package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Search the full oracle index from the Dustery Craft tab. */
public record DusterySearchC2SPayload(String query, String colors, String rarities) implements CustomPacketPayload {
	public static final Type<DusterySearchC2SPayload> TYPE = new Type<>(SpellBlock.id("dustery_search_c2s"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DusterySearchC2SPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.query == null ? "" : payload.query);
			buf.writeUtf(payload.colors == null ? "" : payload.colors);
			buf.writeUtf(payload.rarities == null ? "" : payload.rarities);
		},
		buf -> new DusterySearchC2SPayload(buf.readUtf(), buf.readUtf(), buf.readUtf())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
