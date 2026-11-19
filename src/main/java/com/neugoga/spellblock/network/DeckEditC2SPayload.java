package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** One change to the deck box in an inventory slot. The server checks the binder before it moves a card. */
public record DeckEditC2SPayload(
	int slot,
	String action,
	String oracleId,
	String printingId,
	String finish,
	String text
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DeckEditC2SPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("deck_edit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DeckEditC2SPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeVarInt(payload.slot);
			buf.writeUtf(payload.action, 24);
			buf.writeUtf(payload.oracleId, 80);
			buf.writeUtf(payload.printingId, 80);
			buf.writeUtf(payload.finish, 16);
			buf.writeUtf(payload.text, 128);
		},
		buf -> new DeckEditC2SPayload(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf())
	);

	public DeckEditC2SPayload {
		action = action == null ? "" : action;
		oracleId = oracleId == null ? "" : oracleId;
		printingId = printingId == null ? "" : printingId;
		finish = finish == null ? "" : finish;
		text = text == null ? "" : text;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
