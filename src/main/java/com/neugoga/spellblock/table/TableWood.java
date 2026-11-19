package com.neugoga.spellblock.table;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.SoundType;

/** Which plank texture a table is built from. Only the same wood joins together. */
public enum TableWood implements StringRepresentable {
	OAK("oak", "Oak", "oak_planks", SoundType.WOOD),
	SPRUCE("spruce", "Spruce", "spruce_planks", SoundType.WOOD),
	BIRCH("birch", "Birch", "birch_planks", SoundType.WOOD),
	JUNGLE("jungle", "Jungle", "jungle_planks", SoundType.WOOD),
	ACACIA("acacia", "Acacia", "acacia_planks", SoundType.WOOD),
	DARK_OAK("dark_oak", "Dark Oak", "dark_oak_planks", SoundType.WOOD),
	MANGROVE("mangrove", "Mangrove", "mangrove_planks", SoundType.WOOD),
	CHERRY("cherry", "Cherry", "cherry_planks", SoundType.CHERRY_WOOD),
	BAMBOO("bamboo", "Bamboo", "bamboo_planks", SoundType.BAMBOO_WOOD),
	CRIMSON("crimson", "Crimson", "crimson_planks", SoundType.NETHER_WOOD),
	WARPED("warped", "Warped", "warped_planks", SoundType.NETHER_WOOD);

	private final String id;
	private final String label;
	private final String plank;
	private final SoundType sound;

	TableWood(String id, String label, String plank, SoundType sound) {
		this.id = id;
		this.label = label;
		this.plank = plank;
		this.sound = sound;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	public String itemId() {
		return id + "_card_table";
	}

	public SoundType sound() {
		return sound;
	}

	public ResourceLocation planksTexture() {
		return ResourceLocation.withDefaultNamespace("block/" + plank);
	}

	public ResourceLocation planksItem() {
		return ResourceLocation.withDefaultNamespace(plank);
	}

	@Override
	public String getSerializedName() {
		return id;
	}

	public static TableWood fromId(String id) {
		for (TableWood wood : values()) {
			if (wood.id.equals(id)) {
				return wood;
			}
		}
		return OAK;
	}
}
