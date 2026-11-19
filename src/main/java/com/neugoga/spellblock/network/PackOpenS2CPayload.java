package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** The cards just added to the binder, in the order the player reveals them. */
public record PackOpenS2CPayload(String name, List<BinderCard> cards) implements CustomPacketPayload {
	public static final int MAX_CARDS = 40;
	public static final CustomPacketPayload.Type<PackOpenS2CPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("pack_open"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PackOpenS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.name);
			buf.writeVarInt(payload.cards.size());
			for (BinderCard card : payload.cards) {
				BinderCard.CODEC.encode(buf, card);
			}
		},
		buf -> {
			String name = buf.readUtf();
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_CARDS) {
				throw new IllegalArgumentException("pack size " + size);
			}
			List<BinderCard> cards = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				cards.add(BinderCard.CODEC.decode(buf));
			}
			return new PackOpenS2CPayload(name, cards);
		}
	);

	public PackOpenS2CPayload {
		name = name == null ? "" : name;
		cards = List.copyOf(cards);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
