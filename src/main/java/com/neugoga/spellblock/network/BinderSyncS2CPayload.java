package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** The player's sleeves, already resolved to names. An empty status means the index was ready. */
public record BinderSyncS2CPayload(List<BinderCard> cards, String status, int dust, List<String> favourites) implements CustomPacketPayload {
	public static final int MAX_CARDS = 8192;
	public static final CustomPacketPayload.Type<BinderSyncS2CPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("binder_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BinderSyncS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.status);
			buf.writeVarInt(payload.dust);
			buf.writeVarInt(payload.cards.size());
			for (BinderCard card : payload.cards) {
				BinderCard.CODEC.encode(buf, card);
			}
			buf.writeVarInt(payload.favourites.size());
			for (String fav : payload.favourites) {
				buf.writeUtf(fav);
			}
		},
		buf -> {
			String status = buf.readUtf();
			int dust = buf.readVarInt();
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_CARDS) {
				throw new IllegalArgumentException("binder size " + size);
			}
			List<BinderCard> cards = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				cards.add(BinderCard.CODEC.decode(buf));
			}
			int favSize = buf.readVarInt();
			if (favSize < 0 || favSize > MAX_CARDS) {
				throw new IllegalArgumentException("fav size " + favSize);
			}
			List<String> favourites = new ArrayList<>(favSize);
			for (int i = 0; i < favSize; i++) {
				favourites.add(buf.readUtf());
			}
			return new BinderSyncS2CPayload(cards, status, dust, favourites);
		}
	);

	public BinderSyncS2CPayload {
		cards = List.copyOf(cards);
		status = status == null ? "" : status;
		dust = Math.max(0, dust);
		favourites = List.copyOf(favourites == null ? List.of() : favourites);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
