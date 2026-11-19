package com.neugoga.spellblock.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class SpellBlockNetworking {
	private SpellBlockNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(OpenBinderC2SPayload.TYPE, OpenBinderC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DusteryActionC2SPayload.TYPE, DusteryActionC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DusterySearchC2SPayload.TYPE, DusterySearchC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DusteryPrintingsC2SPayload.TYPE, DusteryPrintingsC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(FavouriteC2SPayload.TYPE, FavouriteC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DeckEditC2SPayload.TYPE, DeckEditC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TableActionC2SPayload.TYPE, TableActionC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ScryFinishC2SPayload.TYPE, ScryFinishC2SPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TradeActionC2SPayload.TYPE, TradeActionC2SPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(BinderSyncS2CPayload.TYPE, BinderSyncS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DusterySyncS2CPayload.TYPE, DusterySyncS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DusterySearchS2CPayload.TYPE, DusterySearchS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DusteryPrintingsS2CPayload.TYPE, DusteryPrintingsS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PackOpenS2CPayload.TYPE, PackOpenS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DeckSyncS2CPayload.TYPE, DeckSyncS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(HandSyncS2CPayload.TYPE, HandSyncS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(HandRevealS2CPayload.TYPE, HandRevealS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ScrySyncS2CPayload.TYPE, ScrySyncS2CPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(TradeSyncS2CPayload.TYPE, TradeSyncS2CPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenBinderC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> BinderServer.open(player));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(DusteryActionC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> DusteryServer.act(player, payload));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(DusterySearchC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> DusteryServer.search(player, payload));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(DusteryPrintingsC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> DusteryServer.requestPrintings(player, payload));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(FavouriteC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> BinderServer.toggleFavourite(player, payload));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(DeckEditC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> DeckServer.edit(player, payload));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(TableActionC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> TableServer.act(player, payload));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ScryFinishC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> TableServer.finishScry(player, payload));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(TradeActionC2SPayload.TYPE, (payload, context) -> {
			var player = context.player();
			var server = player.getServer();
			if (server != null) {
				server.execute(() -> TradeServer.act(player, payload));
			}
		});
	}
}
