package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** One seat's hand, sent only to the player who opened it. */
public record HandSyncS2CPayload(
	BlockPos pos,
	String seat,
	int library,
	int battlefield,
	int graveyard,
	List<BinderCard> hand,
	/** The table's id for each hand card, in the same order. */
	List<Integer> handIds,
	String status,
	String sitters,
	String lookers
) implements CustomPacketPayload {
	public static final int MAX_HAND = 60;
	public static final CustomPacketPayload.Type<HandSyncS2CPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("hand_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HandSyncS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeBlockPos(payload.pos);
			buf.writeUtf(payload.seat, 16);
			buf.writeVarInt(payload.library);
			buf.writeVarInt(payload.battlefield);
			buf.writeVarInt(payload.graveyard);
			buf.writeVarInt(Math.min(payload.hand.size(), MAX_HAND));
			int written = 0;
			for (BinderCard card : payload.hand) {
				if (written >= MAX_HAND) {
					break;
				}
				BinderCard.CODEC.encode(buf, card);
				buf.writeVarInt(written < payload.handIds.size() ? payload.handIds.get(written) : 0);
				written++;
			}
			buf.writeUtf(clip(payload.status, 240), 240);
			buf.writeUtf(clip(payload.sitters, 800), 800);
			buf.writeUtf(clip(payload.lookers, 2000), 2000);
		},
		buf -> {
			BlockPos pos = buf.readBlockPos();
			String seat = buf.readUtf();
			int library = buf.readVarInt();
			int battlefield = buf.readVarInt();
			int graveyard = buf.readVarInt();
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_HAND) {
				throw new IllegalArgumentException("hand " + size);
			}
			List<BinderCard> hand = new ArrayList<>(size);
			List<Integer> handIds = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				hand.add(BinderCard.CODEC.decode(buf));
				handIds.add(buf.readVarInt());
			}
			return new HandSyncS2CPayload(pos, seat, library, battlefield, graveyard, hand, handIds, buf.readUtf(), buf.readUtf(), buf.readUtf());
		}
	);

	public HandSyncS2CPayload {
		seat = seat == null ? "" : seat;
		hand = List.copyOf(hand == null ? List.of() : hand);
		handIds = List.copyOf(handIds == null ? List.of() : handIds);
		status = status == null ? "" : status;
		sitters = sitters == null ? "" : sitters;
		lookers = lookers == null ? "" : lookers;
	}

	private static String clip(String value, int max) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
