package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Matching oracle cards sent back to the Dustery Craft search tab. */
public record DusterySearchS2CPayload(String query, List<BinderCard> cards, int totalMatches, String status) implements CustomPacketPayload {
	public static final int MAX_RESULTS = 200;
	public static final Type<DusterySearchS2CPayload> TYPE = new Type<>(SpellBlock.id("dustery_search_s2c"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DusterySearchS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.query);
			buf.writeVarInt(payload.totalMatches);
			buf.writeUtf(payload.status);
			buf.writeVarInt(payload.cards.size());
			for (BinderCard card : payload.cards) {
				BinderCard.CODEC.encode(buf, card);
			}
		},
		buf -> {
			String query = buf.readUtf();
			int total = buf.readVarInt();
			String status = buf.readUtf();
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_RESULTS) {
				throw new IllegalArgumentException("search size " + size);
			}
			List<BinderCard> cards = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				cards.add(BinderCard.CODEC.decode(buf));
			}
			return new DusterySearchS2CPayload(query, cards, total, status);
		}
	);

	public DusterySearchS2CPayload {
		query = query == null ? "" : query;
		cards = List.copyOf(cards == null ? List.of() : cards);
		totalMatches = Math.max(0, totalMatches);
		status = status == null ? "" : status;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
