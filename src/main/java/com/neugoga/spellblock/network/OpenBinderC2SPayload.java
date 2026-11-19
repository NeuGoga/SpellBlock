package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client asks the server for this player's binder. The screen opens only after the reply. */
public record OpenBinderC2SPayload() implements CustomPacketPayload {
	public static final OpenBinderC2SPayload INSTANCE = new OpenBinderC2SPayload();
	public static final CustomPacketPayload.Type<OpenBinderC2SPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("open_binder"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenBinderC2SPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
