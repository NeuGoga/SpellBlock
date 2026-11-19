package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C state for an open player-to-player trade.
 * Carries both offers, both lock states, and the lock countdown (-1 when not running).
 * Only carries the receiver's own binder when {@code hasBinder} is true (initial open / post-abort).
 */
public record TradeSyncS2CPayload(
	String myName,
	String partnerName,
	List<BinderCard> myOffer,
	List<BinderCard> theirOffer,
	boolean myLocked,
	boolean theirLocked,
	int countdownSeconds,
	String status,
	boolean closed,
	boolean hasBinder,
	List<BinderCard> myBinder
) implements CustomPacketPayload {
	public static final int MAX_OFFER_CARDS = 256;
	public static final int MAX_BINDER_CARDS = BinderSyncS2CPayload.MAX_CARDS;
	public static final Type<TradeSyncS2CPayload> TYPE = new Type<>(SpellBlock.id("trade_sync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TradeSyncS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.myName);
			buf.writeUtf(payload.partnerName);
			buf.writeBoolean(payload.myLocked);
			buf.writeBoolean(payload.theirLocked);
			buf.writeVarInt(payload.countdownSeconds + 1); // shifted by +1 so -1 encodes cleanly as 0
			buf.writeUtf(payload.status);
			buf.writeBoolean(payload.closed);
			writeCards(buf, payload.myOffer);
			writeCards(buf, payload.theirOffer);
			buf.writeBoolean(payload.hasBinder);
			if (payload.hasBinder) {
				writeCards(buf, payload.myBinder);
			}
		},
		buf -> {
			String myName = buf.readUtf();
			String partnerName = buf.readUtf();
			boolean myLocked = buf.readBoolean();
			boolean theirLocked = buf.readBoolean();
			int countdownSeconds = buf.readVarInt() - 1;
			String status = buf.readUtf();
			boolean closed = buf.readBoolean();
			List<BinderCard> myOffer = readCards(buf, MAX_OFFER_CARDS, "trade myOffer");
			List<BinderCard> theirOffer = readCards(buf, MAX_OFFER_CARDS, "trade theirOffer");
			boolean hasBinder = buf.readBoolean();
			List<BinderCard> myBinder = hasBinder ? readCards(buf, MAX_BINDER_CARDS, "trade myBinder") : List.of();
			return new TradeSyncS2CPayload(
				myName,
				partnerName,
				myOffer,
				theirOffer,
				myLocked,
				theirLocked,
				countdownSeconds,
				status,
				closed,
				hasBinder,
				myBinder
			);
		}
	);

	public TradeSyncS2CPayload {
		myName = myName == null ? "" : myName;
		partnerName = partnerName == null ? "" : partnerName;
		myOffer = List.copyOf(myOffer == null ? List.of() : myOffer);
		theirOffer = List.copyOf(theirOffer == null ? List.of() : theirOffer);
		status = status == null ? "" : status;
		myBinder = List.copyOf(myBinder == null ? List.of() : myBinder);
	}

	public static TradeSyncS2CPayload ofClosed(String status) {
		return new TradeSyncS2CPayload("", "", List.of(), List.of(), false, false, -1, status, true, false, List.of());
	}

	private static void writeCards(RegistryFriendlyByteBuf buf, List<BinderCard> cards) {
		buf.writeVarInt(cards.size());
		for (BinderCard card : cards) {
			BinderCard.CODEC.encode(buf, card);
		}
	}

	private static List<BinderCard> readCards(RegistryFriendlyByteBuf buf, int max, String label) {
		int size = buf.readVarInt();
		if (size < 0 || size > max) {
			throw new IllegalArgumentException(label + " size " + size);
		}
		List<BinderCard> cards = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			cards.add(BinderCard.CODEC.decode(buf));
		}
		return cards;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
