package com.neugoga.spellblock.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.neugoga.spellblock.config.SpellBlockConfig;
import com.neugoga.spellblock.network.BinderSyncS2CPayload;
import com.neugoga.spellblock.network.DeckSyncS2CPayload;
import com.neugoga.spellblock.network.DusteryPrintingsS2CPayload;
import com.neugoga.spellblock.network.DusterySearchS2CPayload;
import com.neugoga.spellblock.network.DusterySyncS2CPayload;
import com.neugoga.spellblock.network.HandRevealS2CPayload;
import com.neugoga.spellblock.network.HandSyncS2CPayload;
import com.neugoga.spellblock.network.OpenBinderC2SPayload;
import com.neugoga.spellblock.network.PackOpenS2CPayload;
import com.neugoga.spellblock.network.ScrySyncS2CPayload;
import com.neugoga.spellblock.network.TradeSyncS2CPayload;
import com.neugoga.spellblock.table.TableBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import org.lwjgl.glfw.GLFW;

public class SpellBlockClient implements ClientModInitializer {
	private static KeyMapping binderKey;
	private static KeyMapping settingsKey;

	@Override
	public void onInitializeClient() {
		SpellBlockConfig.load();
		BlockEntityRenderers.register(TableBlocks.TABLE_BE, TableRenderer::new);
		binderKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.spellblock.binder",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_B,
			"category.spellblock"
		));
		settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.spellblock.settings",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			"category.spellblock"
		));
		ClientPlayNetworking.registerGlobalReceiver(BinderSyncS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().screen instanceof BinderScreen open) {
					open.replace(payload);
				} else {
					context.client().setScreen(new BinderScreen(payload));
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(DusterySyncS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (payload.closed()) {
					if (context.client().screen instanceof DusteryScreen) {
						context.client().setScreen(null);
					}
					return;
				}
				if (context.client().screen instanceof DusteryScreen open) {
					open.replace(payload);
				} else {
					context.client().setScreen(new DusteryScreen(payload));
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(DusterySearchS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().screen instanceof DusteryScreen open) {
					open.onSearchResults(payload);
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(DusteryPrintingsS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().screen instanceof DusteryScreen open) {
					open.onPrintingsLoaded(payload);
				}
			}));

		ClientPlayNetworking.registerGlobalReceiver(PackOpenS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> context.client().setScreen(new PackOpenScreen(payload))));
		ClientPlayNetworking.registerGlobalReceiver(DeckSyncS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().screen instanceof DeckScreen open) {
					open.replace(payload);
				} else if (!"closed".equals(payload.status())) {
					context.client().setScreen(new DeckScreen(payload));
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(HandSyncS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().screen instanceof TableScreen open) {
					open.replace(payload);
				} else if (!"closed".equals(payload.status())) {
					context.client().setScreen(new TableScreen(payload));
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(HandRevealS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().screen instanceof TableScreen open) {
					open.reveal(payload);
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(ScrySyncS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (context.client().screen instanceof TableScreen open) {
					open.beginScry(payload);
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(TradeSyncS2CPayload.TYPE, (payload, context) ->
			context.client().execute(() -> {
				if (payload.closed()) {
					if (context.client().screen instanceof TradeScreen) {
						context.client().setScreen(null);
					}
					return;
				}
				if (context.client().screen instanceof TradeScreen open) {
					open.replace(payload);
				} else {
					context.client().setScreen(new TradeScreen(payload));
				}
			}));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (binderKey.consumeClick()) {
				if (client.player != null && client.screen == null && ClientPlayNetworking.canSend(OpenBinderC2SPayload.TYPE)) {
					ClientPlayNetworking.send(OpenBinderC2SPayload.INSTANCE);
				}
			}
			while (settingsKey.consumeClick()) {
				if (client.player != null && !(client.screen instanceof ArtConfigScreen)) {
					client.setScreen(new ArtConfigScreen(client.screen));
				}
			}
		});
	}
}
