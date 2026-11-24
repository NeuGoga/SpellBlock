package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * How a scry or surveil ends. Each list holds original indexes from the sync packet.
 * Tops stay on top of the library in list order (first is the new top).
 * Bottoms go under the library in list order. Graves go to the graveyard (surveil only).
 */
public record ScryFinishC2SPayload(
	BlockPos pos,
	String seat,
	String mode,
	List<Integer> tops,
	List<Integer> bottoms,
	List<Integer> graves
) implements CustomPacketPayload {
	public static final int MAX = 200;
	public static final CustomPacketPayload.Type<ScryFinishC2SPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("scry_finish"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ScryFinishC2SPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeBlockPos(payload.pos);
			buf.writeUtf(payload.seat, 16);
			buf.writeUtf(payload.mode == null ? "" : payload.mode, 16);
			write(buf, payload.tops);
			write(buf, payload.bottoms);
			write(buf, payload.graves);
		},
		buf -> new ScryFinishC2SPayload(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), read(buf), read(buf), read(buf))
	);

	public ScryFinishC2SPayload {
		seat = seat == null ? "" : seat;
		mode = mode == null ? "" : mode;
		tops = List.copyOf(tops == null ? List.of() : tops);
		bottoms = List.copyOf(bottoms == null ? List.of() : bottoms);
		graves = List.copyOf(graves == null ? List.of() : graves);
	}

	private static void write(RegistryFriendlyByteBuf buf, List<Integer> values) {
		buf.writeVarInt(Math.min(values.size(), MAX));
		int n = 0;
		for (int value : values) {
			if (n >= MAX) {
				break;
			}
			buf.writeVarInt(value);
			n++;
		}
	}

	private static List<Integer> read(RegistryFriendlyByteBuf buf) {
		int size = buf.readVarInt();
		if (size < 0 || size > MAX) {
			throw new IllegalArgumentException("scry plan " + size);
		}
		List<Integer> values = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			values.add(buf.readVarInt());
		}
		return values;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
