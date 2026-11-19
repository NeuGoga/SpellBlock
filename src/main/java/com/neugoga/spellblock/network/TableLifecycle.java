package com.neugoga.spellblock.network;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Hooks that keep the table's per-player server state honest.
 * Cards held for a scry, surveil, or search are off the library until the player finishes,
 * so a player who leaves, dies, or walks away must hand them back.
 */
public final class TableLifecycle {
	private TableLifecycle() {
	}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			TableServer.onDisconnect(server, handler.getPlayer());
			DusteryServer.onDisconnect(server, handler.getPlayer());
			TradeServer.onDisconnect(server, handler.getPlayer());
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TableServer.sweep(server);
			DusteryServer.sweep(server);
			TradeServer.sweep(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(TableServer::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TableServer.clearAll();
			DusteryServer.clearAll();
			TradeServer.clearAll();
		});
	}
}
