package com.neugoga.spellblock.library;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** One sleeve's worth of display data. The count lives in the corner, not in a list row. */
public record BinderCard(
	String oracleId,
	String printingId,
	String finish,
	int count,
	String name,
	String manaCost,
	double manaValue,
	String typeLine,
	String colorIdentity,
	String rarity,
	String setCode,
	String treatment
) {
	public static final StreamCodec<RegistryFriendlyByteBuf, BinderCard> CODEC = StreamCodec.of(
		(buf, card) -> {
			buf.writeUtf(card.oracleId);
			buf.writeUtf(card.printingId);
			buf.writeUtf(card.finish);
			buf.writeVarInt(card.count);
			buf.writeUtf(card.name);
			buf.writeUtf(card.manaCost);
			buf.writeDouble(card.manaValue);
			buf.writeUtf(card.typeLine);
			buf.writeUtf(card.colorIdentity);
			buf.writeUtf(card.rarity);
			buf.writeUtf(card.setCode);
			buf.writeUtf(card.treatment);
		},
		buf -> new BinderCard(
			buf.readUtf(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readVarInt(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readDouble(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readUtf()
		)
	);

	public BinderCard {
		oracleId = oracleId == null ? "" : oracleId;
		printingId = printingId == null ? "" : printingId;
		finish = finish == null || finish.isEmpty() ? Finish.NONFOIL.name() : finish;
		name = name == null || name.isEmpty() ? oracleId : name;
		manaCost = manaCost == null ? "" : manaCost;
		typeLine = typeLine == null ? "" : typeLine;
		colorIdentity = colorIdentity == null ? "" : colorIdentity;
		rarity = rarity == null ? "" : rarity;
		setCode = setCode == null ? "" : setCode;
		if (!"retro".equals(treatment) && !"borderless".equals(treatment) && !"special_guest".equals(treatment)) {
			treatment = "normal";
		}
		if (count < 0) {
			count = 0;
		}
	}

	public static BinderCard unknown(CardKey key, int count) {
		return new BinderCard(key.oracleId(), key.printingId(), key.finish().name(), count, key.oracleId(), "", 0, "", "", "", "", "normal");
	}
}
