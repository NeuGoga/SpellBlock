package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.PrintingOption;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Sends the available unique printings for a card back to the Dustery UI. */
public record DusteryPrintingsS2CPayload(String oracleId, List<PrintingOption> printings, boolean loading, String status) implements CustomPacketPayload {
	public static final int MAX_PRINTINGS = 1200;
	public static final Type<DusteryPrintingsS2CPayload> TYPE = new Type<>(SpellBlock.id("dustery_printings_s2c"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DusteryPrintingsS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeUtf(payload.oracleId);
			buf.writeBoolean(payload.loading);
			buf.writeUtf(payload.status);
			buf.writeVarInt(payload.printings.size());
			for (PrintingOption opt : payload.printings) {
				PrintingOption.CODEC.encode(buf, opt);
			}
		},
		buf -> {
			String oracleId = buf.readUtf();
			boolean loading = buf.readBoolean();
			String status = buf.readUtf();
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_PRINTINGS) {
				throw new IllegalArgumentException("printings size " + size);
			}
			List<PrintingOption> list = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				list.add(PrintingOption.CODEC.decode(buf));
			}
			return new DusteryPrintingsS2CPayload(oracleId, list, loading, status);
		}
	);

	public DusteryPrintingsS2CPayload {
		oracleId = oracleId == null ? "" : oracleId;
		printings = List.copyOf(printings == null ? List.of() : printings);
		status = status == null ? "" : status;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
