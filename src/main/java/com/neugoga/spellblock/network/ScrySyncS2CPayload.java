package com.neugoga.spellblock.network;

import java.util.ArrayList;
import java.util.List;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Private cards for a scry or surveil. Only the seat that started it receives this. */
public record ScrySyncS2CPayload(BlockPos pos, String seat, String mode, List<BinderCard> cards) implements CustomPacketPayload {
	public static final int MAX = 200;
	public static final CustomPacketPayload.Type<ScrySyncS2CPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("scry_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ScrySyncS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeBlockPos(payload.pos);
			buf.writeUtf(payload.seat, 16);
			buf.writeUtf(payload.mode == null ? "" : payload.mode, 16);
			List<BinderCard> cards = payload.cards == null ? List.of() : payload.cards;
			buf.writeVarInt(Math.min(cards.size(), MAX));
			int n = 0;
			for (BinderCard card : cards) {
				if (n >= MAX) {
					break;
				}
				BinderCard.CODEC.encode(buf, card);
				n++;
			}
		},
		buf -> {
			BlockPos pos = buf.readBlockPos();
			String seat = buf.readUtf();
			String mode = buf.readUtf();
			int size = buf.readVarInt();
			if (size < 0 || size > MAX) {
				throw new IllegalArgumentException("scry " + size);
			}
			List<BinderCard> cards = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				cards.add(BinderCard.CODEC.decode(buf));
			}
			return new ScrySyncS2CPayload(pos, seat, mode, cards);
		}
	);

	public ScrySyncS2CPayload {
		seat = seat == null ? "" : seat;
		mode = mode == null ? "" : mode;
		cards = List.copyOf(cards == null ? List.of() : cards);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
