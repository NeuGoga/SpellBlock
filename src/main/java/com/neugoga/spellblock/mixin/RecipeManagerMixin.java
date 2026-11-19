package com.neugoga.spellblock.mixin;

import com.google.gson.JsonElement;
import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.datapack.SpellBlockDataPacks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Suppresses the {@code spellblock:dustery} crafting recipe when {@code rules.json} sets {@code "dustery": false}.
 */
@Mixin(RecipeManager.class)
public final class RecipeManagerMixin {
	@Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
	private void spellblock$suppressDisabledDusteryRecipe(
		Map<ResourceLocation, JsonElement> recipes,
		ResourceManager resourceManager,
		ProfilerFiller profiler,
		CallbackInfo ci
	) {
		if (!SpellBlockDataPacks.rules().dustery()) {
			recipes.remove(SpellBlock.id("dustery"));
		}
	}
}
