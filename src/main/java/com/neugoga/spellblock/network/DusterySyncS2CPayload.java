package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.datapack.CardRestrictions;
import com.neugoga.spellblock.datapack.EconomyRules;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Opens or refreshes the Dustery screen with the player's collection, dust balance, and active server economy rules. */
public record DusterySyncS2CPayload(
	List<BinderCard> cards,
	int dust,
	String status,
	List<String> favourites,
	int indexSize,
	boolean closed,
	boolean dustEnabled,
	boolean craftEnabled,
	EconomyRules economy,
	CardRestrictions restrictions
) implements CustomPacketPayload {
	public static final int MAX_CARDS = 8192;
	public static final Type<DusterySyncS2CPayload> TYPE = new Type<>(SpellBlock.id("dustery_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DusterySyncS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.status == null ? "" : payload.status);
			buf.writeVarInt(payload.dust);
			buf.writeVarInt(payload.indexSize);
			buf.writeBoolean(payload.closed);
			buf.writeBoolean(payload.dustEnabled);
			buf.writeBoolean(payload.craftEnabled);
			EconomyRules.CODEC.encode(buf, payload.economy);
			CardRestrictions.CODEC.encode(buf, payload.restrictions);
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
			int indexSize = buf.readVarInt();
			boolean closed = buf.readBoolean();
			boolean dustEnabled = buf.readBoolean();
			boolean craftEnabled = buf.readBoolean();
			EconomyRules economy = EconomyRules.CODEC.decode(buf);
			CardRestrictions restrictions = CardRestrictions.CODEC.decode(buf);
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_CARDS) {
				throw new IllegalArgumentException("dustery size " + size);
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
			return new DusterySyncS2CPayload(cards, dust, status, favourites, indexSize, closed, dustEnabled, craftEnabled, economy, restrictions);
		}
	);

	public DusterySyncS2CPayload {
		cards = List.copyOf(cards);
		status = status == null ? "" : status;
		dust = Math.max(0, dust);
		indexSize = Math.max(0, indexSize);
		favourites = List.copyOf(favourites == null ? List.of() : favourites);
		economy = economy == null ? EconomyRules.DEFAULTS : economy;
		restrictions = restrictions == null ? CardRestrictions.EMPTY : restrictions;
	}

	public static DusterySyncS2CPayload ofClosed() {
		return new DusterySyncS2CPayload(List.of(), 0, "closed", List.of(), 0, true, false, false, EconomyRules.DEFAULTS, CardRestrictions.EMPTY);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
