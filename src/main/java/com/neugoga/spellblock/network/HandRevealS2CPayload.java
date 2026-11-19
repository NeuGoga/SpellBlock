package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** A seat's hand, sent only to the players that seat chose to show it to. An empty audience means hide it. */
public record HandRevealS2CPayload(
	BlockPos pos,
	String seat,
	String audience,
	List<BinderCard> cards
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<HandRevealS2CPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("hand_reveal"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HandRevealS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeBlockPos(payload.pos);
			buf.writeUtf(payload.seat, 16);
			buf.writeUtf(payload.audience, 16);
			buf.writeVarInt(Math.min(payload.cards.size(), HandSyncS2CPayload.MAX_HAND));
			int written = 0;
			for (BinderCard card : payload.cards) {
				if (written >= HandSyncS2CPayload.MAX_HAND) {
					break;
				}
				BinderCard.CODEC.encode(buf, card);
				written++;
			}
		},
		buf -> {
			BlockPos pos = buf.readBlockPos();
			String seat = buf.readUtf();
			String audience = buf.readUtf();
			int size = buf.readVarInt();
			if (size < 0 || size > HandSyncS2CPayload.MAX_HAND) {
				throw new IllegalArgumentException("reveal " + size);
			}
			List<BinderCard> cards = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				cards.add(BinderCard.CODEC.decode(buf));
			}
			return new HandRevealS2CPayload(pos, seat, audience, cards);
		}
	);

	public HandRevealS2CPayload {
		seat = seat == null ? "" : seat;
		audience = audience == null ? "" : audience;
		cards = List.copyOf(cards == null ? List.of() : cards);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
