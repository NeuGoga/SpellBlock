package com.neugoga.spellblock.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.item.DeckBoxItem;
import com.neugoga.spellblock.table.ClothPlaces;
import com.neugoga.spellblock.table.TableBlock;
import com.neugoga.spellblock.table.TableBlockEntity;
import com.neugoga.spellblock.table.TableCard;
import com.neugoga.spellblock.table.TableParts;
import com.neugoga.spellblock.table.TableSeat;
import com.neugoga.spellblock.table.TableSize;
import com.neugoga.spellblock.table.TableWood;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

/** Draws the joined table from each block's own cell, so the pedestal sits in the middle. */
public final class TableRenderer implements BlockEntityRenderer<TableBlockEntity> {
	private static final double EPS = 1.0e-5;
	private static final double GAP = 0.001;
	private static final ResourceLocation WOOL = ResourceLocation.withDefaultNamespace("block/red_wool");
	private static final float BOX_SCALE = 0.40f;
	/** Past this distance the cloth and boxes stay, and the card quads are skipped. */
	private static final double CARD_RANGE = 16.0;
	private static final java.util.WeakHashMap<TableBlockEntity, PlayCache> PLAY = new java.util.WeakHashMap<>();

	private record PlayCache(int generation, TableSize size, java.util.List<ClothPlaces.Spot> spots) {
	}

	public TableRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public boolean shouldRenderOffScreen(TableBlockEntity entity) {
		return true;
	}

	@Override
	public void render(TableBlockEntity entity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
		BlockState state = entity.getBlockState();
		if (!(state.getBlock() instanceof TableBlock)) {
			return;
		}
		TableSize size = state.getValue(TableBlock.SIZE);
		int ox = state.getValue(TableBlock.X);
		int oz = state.getValue(TableBlock.Z);
		if (ox >= size.width() || oz >= size.depth()) {
			return;
		}
		TableWood wood = state.getValue(TableBlock.WOOD);
		TextureAtlasSprite planks = sprite(wood.planksTexture());
		TextureAtlasSprite cloth = sprite(WOOL);
		VertexConsumer consumer = buffers.getBuffer(RenderType.solid());
		PoseStack.Pose pose = poseStack.last();
		double minX = ox == 0 ? -TableParts.OVERHANG - 0.01 : ox;
		double maxX = ox == size.width() - 1 ? ox + 1.0 + TableParts.OVERHANG + 0.01 : ox + 1.0;
		double minZ = oz == 0 ? -TableParts.OVERHANG - 0.01 : oz;
		double maxZ = oz == size.depth() - 1 ? oz + 1.0 + TableParts.OVERHANG + 0.01 : oz + 1.0;
		for (TableParts.Part part : TableParts.of(size)) {
			drawClipped(consumer, pose, part, ox, oz, size, minX, maxX, minZ, maxZ, part.cloth() ? cloth : planks, light, overlay);
		}
		if (ox == 0 && oz == 0) {
			drawBoxes(entity, size, poseStack, buffers, light, overlay);
			if (!cardsTooFar(entity, size)) {
				drawPlay(entity, size, poseStack, buffers, light, overlay);
			} else {
				drawLife(entity, size, poseStack, buffers, light);
			}
		}
	}

	private static void drawBoxes(
		TableBlockEntity entity,
		TableSize size,
		PoseStack poseStack,
		MultiBufferSource buffers,
		int light,
		int overlay
	) {
		for (TableSeat seat : TableSeat.values()) {
			ItemStack stack = entity.get(seat);
			if (stack.isEmpty()) {
				continue;
			}
			float[] at = ClothPlaces.box(size, seat);
			double x = at[0];
			double z = at[1];
			float yaw = switch (seat) {
				case NORTH -> 0.0f;
				case EAST -> 270.0f;
				case SOUTH -> 180.0f;
				case WEST -> 90.0f;
			};
			poseStack.pushPose();
			poseStack.translate(x, 1.02f + 0.5f * BOX_SCALE, z);
			poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
			poseStack.scale(BOX_SCALE, BOX_SCALE, BOX_SCALE);
			Minecraft.getInstance().getItemRenderer().renderStatic(
				stack,
				ItemDisplayContext.FIXED,
				light,
				overlay,
				poseStack,
				buffers,
				entity.getLevel(),
				seat.ordinal()
			);
			poseStack.popPose();
		}
	}

	private static void drawPlay(
		TableBlockEntity entity,
		TableSize size,
		PoseStack poseStack,
		MultiBufferSource buffers,
		int light,
		int overlay
	) {
		java.util.ArrayList<Shown> shown = new java.util.ArrayList<>();
		for (ClothPlaces.Spot spot : spotsFor(entity, size)) {
			addSpot(shown, entity, spot);
		}
		TextureAtlasSprite paper = sprite(ResourceLocation.withDefaultNamespace("block/white_wool"));
		VertexConsumer wool = buffers.getBuffer(RenderType.solid());
		PoseStack.Pose pose = poseStack.last();
		for (Shown card : shown) {
			if (card.texture != null) {
				continue;
			}
			oriented(wool, pose, paper.getU0(), paper.getV0(), paper.getU1(), paper.getV1(), card, 1.012f + card.layer * 0.0022f, 255, light, overlay);
		}
		for (Shown card : shown) {
			if (card.texture == null) {
				continue;
			}
			VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutout(card.texture));
			float y = (card.pile ? 1.03f : 1.016f) + card.layer * 0.0022f;
			oriented(consumer, poseStack.last(), 0.0f, 0.0f, 1.0f, 1.0f, card, y, 255, light, overlay);
		}
		Shown hit = underCursor(entity.getBlockPos(), shown);
		if (hit != null) {
			String label = hit.faceDown ? "Face down" : hit.name;
			if (!hit.faceDown && hit.flipped) {
				label = label == null || label.isEmpty() ? "Flipped" : label + " · flipped";
			}
			if (!hit.faceDown && hit.count > 1 && !label.isEmpty()) {
				label = label + " (" + hit.count + ")";
			}
			drawLabel(poseStack, buffers, label, hit.x + hit.w / 2.0f, hit.z + hit.h / 2.0f, light, 0.012f, 1.14f);
		}
		drawLife(entity, size, poseStack, buffers, light);
	}

	private static void drawLife(TableBlockEntity entity, TableSize size, PoseStack poseStack, MultiBufferSource buffers, int light) {
		float cx = size.width() / 2.0f;
		float cz = size.depth() / 2.0f;
		for (TableSeat seat : TableSeat.values()) {
			if (!TableSeat.fits(size, seat) || entity.get(seat).isEmpty()) {
				continue;
			}
			float[] box = ClothPlaces.box(size, seat);
			float x = box[0] + (cx - box[0]) * 0.55f;
			float z = box[1] + (cz - box[1]) * 0.55f;
			drawLabel(poseStack, buffers, Integer.toString(entity.zones(seat).shownLife(entity.get(seat))), x, z, light, 0.02f, 1.28f);
		}
	}

	/** Card spots stay put until the client block entity reloads. Art is resolved when drawing. */
	private static java.util.List<ClothPlaces.Spot> spotsFor(TableBlockEntity entity, TableSize size) {
		int generation = entity.clientGeneration();
		PlayCache cache = PLAY.get(entity);
		if (cache != null && cache.generation == generation && cache.size == size) {
			return cache.spots;
		}
		boolean roomy = ClothPlaces.roomy(entity);
		java.util.ArrayList<ClothPlaces.Spot> spots = new java.util.ArrayList<>();
		for (TableSeat seat : TableSeat.values()) {
			if (!TableSeat.fits(size, seat)) {
				continue;
			}
			spots.addAll(ClothPlaces.play(
				size,
				seat,
				entity.zones(seat).battlefield(),
				entity.zones(seat).battlefieldRows(),
				entity.zones(seat).graveyard(),
				entity.zones(seat).exile(),
				roomy
			));
		}
		PLAY.put(entity, new PlayCache(generation, size, spots));
		return spots;
	}

	private static boolean cardsTooFar(TableBlockEntity entity, TableSize size) {
		Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		double x0 = entity.getBlockPos().getX();
		double z0 = entity.getBlockPos().getZ();
		double x1 = x0 + size.width();
		double z1 = z0 + size.depth();
		double x = Math.max(x0, Math.min(cam.x, x1));
		double z = Math.max(z0, Math.min(cam.z, z1));
		double dx = cam.x - x;
		double dy = cam.y - (entity.getBlockPos().getY() + 1.0);
		double dz = cam.z - z;
		return dx * dx + dy * dy + dz * dz > CARD_RANGE * CARD_RANGE;
	}

	private static void addSpot(java.util.List<Shown> into, TableBlockEntity entity, ClothPlaces.Spot spot) {
		TableSeat seat = spot.seat();
		java.util.List<TableCard> cards;
		java.util.List<String> names;
		if ("grave".equals(spot.zone())) {
			cards = entity.zones(seat).graveyard();
			names = entity.zones(seat).graveyardNames();
		} else if ("exile".equals(spot.zone())) {
			cards = entity.zones(seat).exile();
			names = entity.zones(seat).exileNames();
		} else {
			cards = entity.zones(seat).battlefield();
			names = entity.zones(seat).battlefieldNames();
		}
		if (spot.index() < 0 || spot.index() >= cards.size()) {
			return;
		}
		TableCard card = cards.get(spot.index());
		String name = spot.index() < names.size() ? names.get(spot.index()) : "";
		boolean pile = !"field".equals(spot.zone());
		into.add(new Shown(
			spot.x(),
			spot.z(),
			spot.w(),
			spot.h(),
			seat,
			name,
			artOf(card, boxTexture(entity, seat)),
			card.faceDown(),
			pile,
			spot.count(),
			spot.layer(),
			card.tapped() && !pile,
			card.flipped() && !pile
		));
	}

	private static ResourceLocation artOf(TableCard card, ResourceLocation back) {
		if (card.faceDown()) {
			return back;
		}
		if (card.flipped()) {
			CardArt.Face other = CardArt.peekBack(card.printingId());
			if (other != null) {
				return other.texture();
			}
		}
		CardArt.Face face = CardArt.peek(card.printingId());
		return face == null ? null : face.texture();
	}

	private static ResourceLocation boxTexture(TableBlockEntity entity, TableSeat seat) {
		String color = "blue";
		if (entity.get(seat).getItem() instanceof DeckBoxItem box) {
			color = box.color();
		}
		return SpellBlock.id("textures/item/deck_box_" + color + ".png");
	}

	private record Shown(float x, float z, float w, float h, TableSeat seat, String name, ResourceLocation texture, boolean faceDown, boolean pile, int count, int layer, boolean tapped, boolean flipped) {
	}

	private static Shown underCursor(BlockPos origin, java.util.List<Shown> cards) {
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Vec3 eye = camera.getPosition();
		Vector3f look = camera.getLookVector();
		if (Math.abs(look.y()) < 1.0e-4f) {
			return null;
		}
		double tableY = origin.getY() + 1.02;
		double t = (tableY - eye.y) / look.y();
		if (t < 0.0 || t > 8.0) {
			return null;
		}
		double x = eye.x + look.x() * t - origin.getX();
		double z = eye.z + look.z() * t - origin.getZ();
		Shown hit = null;
		for (Shown card : cards) {
			if (x < card.x || x > card.x + card.w || z < card.z || z > card.z + card.h) {
				continue;
			}
			hit = card;
		}
		return hit;
	}

	private static void oriented(
		VertexConsumer consumer,
		PoseStack.Pose pose,
		float u0,
		float v0,
		float u1,
		float v1,
		Shown card,
		float y,
		int shade,
		int light,
		int overlay
	) {
		float x0 = card.x;
		float z0 = card.z;
		float x1 = card.x + card.w;
		float z1 = card.z + card.h;
		float[] t = uvCorner(card.seat, card.tapped);
		face(consumer, pose.pose(), pose, light, overlay, 0, 1, 0, shade,
			x0, y, z0, u(u0, u1, t[0]), u(v0, v1, t[1]),
			x0, y, z1, u(u0, u1, t[2]), u(v0, v1, t[3]),
			x1, y, z1, u(u0, u1, t[4]), u(v0, v1, t[5]),
			x1, y, z0, u(u0, u1, t[6]), u(v0, v1, t[7]));
	}

	private static float u(float from, float to, float t) {
		return from + (to - from) * t;
	}

	private static float[] uvCorner(TableSeat seat, boolean tapped) {
		float[] upright = switch (seat) {
			case NORTH -> new float[] {1, 1, 1, 0, 0, 0, 0, 1};
			case SOUTH -> new float[] {0, 0, 0, 1, 1, 1, 1, 0};
			case WEST -> new float[] {1, 1, 0, 1, 0, 0, 1, 0};
			case EAST -> new float[] {0, 0, 1, 0, 1, 1, 0, 1};
		};
		if (!tapped) {
			return upright;
		}
		return new float[] {upright[2], upright[3], upright[4], upright[5], upright[6], upright[7], upright[0], upright[1]};
	}

	private static void drawLabel(PoseStack poseStack, MultiBufferSource buffers, String text, float x, float z, int light) {
		drawLabel(poseStack, buffers, text, x, z, light, 0.012f, 1.14f);
	}

	private static void drawLabel(PoseStack poseStack, MultiBufferSource buffers, String text, float x, float z, int light, float scale, float y) {
		if (text == null || text.isEmpty()) {
			return;
		}
		if (text.length() > 18) {
			text = text.substring(0, 18);
		}
		Font font = Minecraft.getInstance().font;
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		poseStack.pushPose();
		poseStack.translate(x, y, z);
		poseStack.mulPose(camera.rotation());
		poseStack.scale(-scale, -scale, scale);
		float width = font.width(text);
		font.drawInBatch(text, -width / 2.0f, -4.0f, 0xFFF4EFE4, false, poseStack.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
		poseStack.popPose();
	}

	private static TextureAtlasSprite sprite(ResourceLocation id) {
		return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(id);
	}

	private static void drawClipped(
		VertexConsumer consumer,
		PoseStack.Pose pose,
		TableParts.Part part,
		int ox,
		int oz,
		TableSize size,
		double cellMinX,
		double cellMaxX,
		double cellMinZ,
		double cellMaxZ,
		TextureAtlasSprite sprite,
		int light,
		int overlay
	) {
		double x0 = Math.max(part.x0(), cellMinX);
		double x1 = Math.min(part.x1(), cellMaxX);
		double z0 = Math.max(part.z0(), cellMinZ);
		double z1 = Math.min(part.z1(), cellMaxZ);
		double y0 = part.y0();
		double y1 = part.y1();
		if (x1 - x0 < EPS || z1 - z0 < EPS || y1 - y0 < EPS) {
			return;
		}
		boolean clothCap = part.cloth() && part.y1() > 0.99;
		boolean woodTop = !part.cloth() && Math.abs(part.y0() - TableParts.STEM_TOP) < EPS;
		boolean stem = !part.cloth() && Math.abs(part.y1() - TableParts.STEM_TOP) < EPS;
		boolean skirt = part.cloth() && !clothCap;
		if (!part.cloth() && !woodTop) {
			y1 -= GAP;
		}
		if (clothCap) {
			y0 += GAP;
		}
		if (skirt) {
			y1 += GAP;
		}
		boolean west = x0 <= part.x0() + EPS;
		boolean east = x1 >= part.x1() - EPS;
		boolean north = z0 <= part.z0() + EPS;
		boolean south = z1 >= part.z1() - EPS;
		boolean drawUp = !woodTop && !stem && !skirt;
		double x = x0;
		int guardX = 0;
		while (x < x1 - EPS && guardX++ < 6) {
			double nextX = Math.min(x1, Math.floor(x) + 1.0);
			if (nextX <= x + EPS) {
				nextX = x1;
			}
			double z = z0;
			int guardZ = 0;
			while (z < z1 - EPS && guardZ++ < 6) {
				double nextZ = Math.min(z1, Math.floor(z) + 1.0);
				if (nextZ <= z + EPS) {
					nextZ = z1;
				}
				boolean sticksOut = x < -EPS || nextX > size.width() + EPS || z < -EPS || nextZ > size.depth() + EPS;
				boolean drawDown = !clothCap || sticksOut;
				cuboid(
					consumer,
					pose,
					sprite,
					x - ox,
					y0,
					z - oz,
					nextX - ox,
					y1,
					nextZ - oz,
					x,
					nextX,
					z,
					nextZ,
					west && x <= x0 + EPS,
					east && nextX >= x1 - EPS,
					north && z <= z0 + EPS,
					south && nextZ >= z1 - EPS,
					drawUp,
					drawDown,
					light,
					overlay
				);
				z = nextZ;
			}
			x = nextX;
		}
	}

	private static void cuboid(
		VertexConsumer consumer,
		PoseStack.Pose pose,
		TextureAtlasSprite sprite,
		double x0,
		double y0,
		double z0,
		double x1,
		double y1,
		double z1,
		double structureX0,
		double structureX1,
		double structureZ0,
		double structureZ1,
		boolean west,
		boolean east,
		boolean north,
		boolean south,
		boolean drawUp,
		boolean drawDown,
		int light,
		int overlay
	) {
		Matrix4f matrix = pose.pose();
		float xa = (float) x0;
		float xb = (float) x1;
		float ya = (float) y0;
		float yb = (float) y1;
		float za = (float) z0;
		float zb = (float) z1;
		float[] ux = span(sprite, structureX0, structureX1, true);
		float[] uz = span(sprite, structureZ0, structureZ1, false);
		float[] uzAcross = span(sprite, structureZ0, structureZ1, true);
		float[] uy = span(sprite, y0, y1, false);
		if (south) {
			face(consumer, matrix, pose, light, overlay, 0, 0, 1, 200,
				xa, ya, zb, ux[0], uy[1],
				xb, ya, zb, ux[1], uy[1],
				xb, yb, zb, ux[1], uy[0],
				xa, yb, zb, ux[0], uy[0]);
		}
		if (north) {
			face(consumer, matrix, pose, light, overlay, 0, 0, -1, 200,
				xb, ya, za, ux[1], uy[1],
				xa, ya, za, ux[0], uy[1],
				xa, yb, za, ux[0], uy[0],
				xb, yb, za, ux[1], uy[0]);
		}
		if (east) {
			face(consumer, matrix, pose, light, overlay, 1, 0, 0, 160,
				xb, ya, zb, uzAcross[1], uy[1],
				xb, ya, za, uzAcross[0], uy[1],
				xb, yb, za, uzAcross[0], uy[0],
				xb, yb, zb, uzAcross[1], uy[0]);
		}
		if (west) {
			face(consumer, matrix, pose, light, overlay, -1, 0, 0, 160,
				xa, ya, za, uzAcross[0], uy[1],
				xa, ya, zb, uzAcross[1], uy[1],
				xa, yb, zb, uzAcross[1], uy[0],
				xa, yb, za, uzAcross[0], uy[0]);
		}
		if (drawUp) {
			face(consumer, matrix, pose, light, overlay, 0, 1, 0, 255,
				xa, yb, za, ux[0], uz[0],
				xa, yb, zb, ux[0], uz[1],
				xb, yb, zb, ux[1], uz[1],
				xb, yb, za, ux[1], uz[0]);
		}
		if (drawDown) {
			face(consumer, matrix, pose, light, overlay, 0, -1, 0, 130,
				xa, ya, zb, ux[0], uz[1],
				xa, ya, za, ux[0], uz[0],
				xb, ya, za, ux[1], uz[0],
				xb, ya, zb, ux[1], uz[1]);
		}
	}

	private static float[] span(TextureAtlasSprite sprite, double start, double end, boolean uAxis) {
		double a = start * 16.0;
		double b = end * 16.0;
		double u0;
		double u1;
		if (b <= 0) {
			u0 = 0;
			u1 = Math.min(16.0, b - a);
		} else if (a < 0) {
			u0 = 0;
			u1 = Math.min(16.0, b);
		} else {
			double base = Math.floor(a / 16.0) * 16.0;
			u0 = a - base;
			u1 = Math.min(16.0, b - base);
		}
		float s = uv(sprite, u0, uAxis);
		float e = uv(sprite, u1, uAxis);
		return new float[] {s, e};
	}

	private static float uv(TextureAtlasSprite sprite, double pixel, boolean uAxis) {
		float t = (float) (pixel / 16.0);
		float from = uAxis ? sprite.getU0() : sprite.getV0();
		float to = uAxis ? sprite.getU1() : sprite.getV1();
		return from + (to - from) * t;
	}

	private static void face(
		VertexConsumer consumer,
		Matrix4f matrix,
		PoseStack.Pose pose,
		int light,
		int overlay,
		float nx,
		float ny,
		float nz,
		int shade,
		float x0, float y0, float z0, float u0, float v0,
		float x1, float y1, float z1, float u1, float v1,
		float x2, float y2, float z2, float u2, float v2,
		float x3, float y3, float z3, float u3, float v3
	) {
		int r = shade;
		int g = shade;
		int b = shade;
		consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, 255).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
		consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, 255).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
		consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, 255).setUv(u2, v2).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
		consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, 255).setUv(u3, v3).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
	}
}
