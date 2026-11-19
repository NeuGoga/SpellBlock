package com.neugoga.spellblock.mixin;

import com.neugoga.spellblock.datapack.SpellBlockDataPacks;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.packs.resources.ResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Ensures SpellBlock datapack configs (loot targets, pack weights, rules switches) reload BEFORE
 * {@code LootTableEvents.MODIFY} runs during both world startup and {@code /reload}.
 */
@Mixin(ReloadableServerRegistries.class)
public final class ReloadableServerRegistriesMixin {
	@Inject(method = "reload", at = @At("HEAD"))
	private static void spellblock$reloadDatapacksBeforeLoot(
		LayeredRegistryAccess<RegistryLayer> registries,
		ResourceManager resourceManager,
		Executor executor,
		CallbackInfoReturnable<CompletableFuture<LayeredRegistryAccess<RegistryLayer>>> cir
	) {
		SpellBlockDataPacks.reload(resourceManager);
	}
}
