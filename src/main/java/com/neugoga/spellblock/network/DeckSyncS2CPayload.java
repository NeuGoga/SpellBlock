package com.neugoga.spellblock.network;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.library.BinderCard;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** The open deck box, the player's binder, and the format report. */
public record DeckSyncS2CPayload(
	int slot,
	String name,
	String format,
	String color,
	boolean locked,
	boolean yours,
	BinderCard commanderA,
	BinderCard commanderB,
	List<BinderCard> main,
	List<BinderCard> side,
	List<BinderCard> binder,
	List<String> issues,
	String status
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DeckSyncS2CPayload> TYPE = new CustomPacketPayload.Type<>(SpellBlock.id("deck_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DeckSyncS2CPayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeVarInt(payload.slot);
			buf.writeUtf(payload.name, 128);
			buf.writeUtf(payload.format, 32);
			buf.writeUtf(payload.color, 16);
			buf.writeBoolean(payload.locked);
			buf.writeBoolean(payload.yours);
			BinderCard.CODEC.encode(buf, payload.commanderA);
			BinderCard.CODEC.encode(buf, payload.commanderB);
			writeCards(buf, payload.main, 400);
			writeCards(buf, payload.side, 400);
			writeCards(buf, payload.binder, BinderSyncS2CPayload.MAX_CARDS);
			buf.writeVarInt(Math.min(payload.issues.size(), 24));
			int written = 0;
			for (String issue : payload.issues) {
				if (written >= 24) {
					break;
				}
				buf.writeUtf(issue.length() > 180 ? issue.substring(0, 180) : issue, 540);
				written++;
			}
			buf.writeUtf(payload.status, 120);
		},
		buf -> new DeckSyncS2CPayload(
			buf.readVarInt(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readUtf(),
			buf.readBoolean(),
			buf.readBoolean(),
			BinderCard.CODEC.decode(buf),
			BinderCard.CODEC.decode(buf),
			readCards(buf, 400),
			readCards(buf, 400),
			readCards(buf, BinderSyncS2CPayload.MAX_CARDS),
			readIssues(buf),
			buf.readUtf()
		)
	);

	public DeckSyncS2CPayload {
		name = name == null ? "" : name;
		format = format == null ? "" : format;
		color = color == null ? "" : color;
		commanderA = commanderA == null ? emptyCard() : commanderA;
		commanderB = commanderB == null ? emptyCard() : commanderB;
		main = List.copyOf(main);
		side = List.copyOf(side);
		binder = List.copyOf(binder);
		issues = List.copyOf(issues);
		status = status == null ? "" : status;
	}

	public static DeckSyncS2CPayload closed() {
		return new DeckSyncS2CPayload(-1, "", "", "", false, false, emptyCard(), emptyCard(), List.of(), List.of(), List.of(), List.of(), "closed");
	}

	public static BinderCard emptyCard() {
		return new BinderCard("", "", "NONFOIL", 0, "", "", 0, "", "", "", "", "normal");
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static void writeCards(RegistryFriendlyByteBuf buf, List<BinderCard> cards, int max) {
		int size = Math.min(cards.size(), max);
		buf.writeVarInt(size);
		for (int i = 0; i < size; i++) {
			BinderCard.CODEC.encode(buf, cards.get(i));
		}
	}

	private static List<BinderCard> readCards(RegistryFriendlyByteBuf buf, int max) {
		int size = buf.readVarInt();
		if (size < 0 || size > max) {
			throw new IllegalArgumentException("deck cards " + size);
		}
		List<BinderCard> cards = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			cards.add(BinderCard.CODEC.decode(buf));
		}
		return cards;
	}

	private static List<String> readIssues(RegistryFriendlyByteBuf buf) {
		int size = buf.readVarInt();
		if (size < 0 || size > 24) {
			throw new IllegalArgumentException("deck issues " + size);
		}
		List<String> issues = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			issues.add(buf.readUtf());
		}
		return issues;
	}
}
