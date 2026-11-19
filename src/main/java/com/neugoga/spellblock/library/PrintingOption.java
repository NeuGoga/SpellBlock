package com.neugoga.spellblock.library;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/** One unique printing of an oracle card that can be previewed and crafted in the Dustery. */
public record PrintingOption(
	String oracleId,
	String printingId,
	String name,
	String manaCost,
	double manaValue,
	String typeLine,
	String colorIdentity,
	String rarity,
	String setCode,
	String collectorNumber,
	String treatment,
	List<String> finishes
) {
	public static final int MAX_FINISHES = 6;

	public static final StreamCodec<RegistryFriendlyByteBuf, PrintingOption> CODEC = StreamCodec.of(
		(buf, opt) -> {
			buf.writeUtf(opt.oracleId);
			buf.writeUtf(opt.printingId);
			buf.writeUtf(opt.name);
			buf.writeUtf(opt.manaCost);
			buf.writeDouble(opt.manaValue);
			buf.writeUtf(opt.typeLine);
			buf.writeUtf(opt.colorIdentity);
			buf.writeUtf(opt.rarity);
			buf.writeUtf(opt.setCode);
			buf.writeUtf(opt.collectorNumber);
			buf.writeUtf(opt.treatment);
			buf.writeVarInt(opt.finishes.size());
			for (String f : opt.finishes) {
				buf.writeUtf(f);
			}
		},
		buf -> {
			String oracleId = buf.readUtf();
			String printingId = buf.readUtf();
			String name = buf.readUtf();
			String manaCost = buf.readUtf();
			double manaValue = buf.readDouble();
			String typeLine = buf.readUtf();
			String colorIdentity = buf.readUtf();
			String rarity = buf.readUtf();
			String setCode = buf.readUtf();
			String collectorNumber = buf.readUtf();
			String treatment = buf.readUtf();
			int count = Math.max(0, Math.min(MAX_FINISHES, buf.readVarInt()));
			List<String> finishes = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				finishes.add(buf.readUtf());
			}
			return new PrintingOption(oracleId, printingId, name, manaCost, manaValue, typeLine, colorIdentity, rarity, setCode, collectorNumber, treatment, finishes);
		}
	);

	public PrintingOption {
		oracleId = oracleId == null ? "" : oracleId;
		printingId = printingId == null ? "" : printingId;
		name = name == null ? "" : name;
		manaCost = manaCost == null ? "" : manaCost;
		typeLine = typeLine == null ? "" : typeLine;
		colorIdentity = colorIdentity == null ? "" : colorIdentity;
		rarity = rarity == null || rarity.isEmpty() ? "common" : rarity;
		setCode = setCode == null ? "" : setCode;
		collectorNumber = collectorNumber == null ? "" : collectorNumber;
		if (!"retro".equals(treatment) && !"borderless".equals(treatment) && !"special_guest".equals(treatment)) {
			treatment = "normal";
		}
		finishes = finishes == null || finishes.isEmpty() ? List.of(Finish.NONFOIL.name()) : List.copyOf(finishes);
	}

	public boolean supportsFinish(Finish finish) {
		return finish != null && finishes.contains(finish.name());
	}

	public BinderCard asBinderCard(int ownedCount) {
		return new BinderCard(
			oracleId,
			printingId,
			finishes.getFirst(),
			ownedCount,
			name,
			manaCost,
			manaValue,
			typeLine,
			colorIdentity,
			rarity,
			setCode,
			treatment
		);
	}
}
