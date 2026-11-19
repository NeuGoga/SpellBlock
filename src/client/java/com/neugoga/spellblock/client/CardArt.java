package com.neugoga.spellblock.client;

import com.neugoga.spellblock.SpellBlock;
import com.neugoga.spellblock.catalog.OracleService;
import com.neugoga.spellblock.config.ArtQuality;
import com.neugoga.spellblock.config.SpellBlockConfig;
import com.neugoga.spellblock.library.Finish;

import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Fetches one Scryfall printing image when a sleeve asks for it. Nothing here is shipped in the jar. */
public final class CardArt {
	private static final int MEMORY = 96;
	private static final int MAX_EDGE = 400;
	private static final int MAX_DOWNLOADS = 3;
	private static final int MAX_UPLOADS_PER_FRAME = 2;
	private static final int MAX_BYTES = 6_000_000;
	private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private static final Map<String, Face> FACES = new java.util.LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Face> eldest) {
			if (size() <= MEMORY || PINNED.contains(eldest.getKey())) {
				return false;
			}
			Minecraft.getInstance().getTextureManager().release(eldest.getValue().texture());
			return true;
		}
	};
	private static final Set<String> PINNED = ConcurrentHashMap.newKeySet();
	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
	private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();
	private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
	private static final ConcurrentLinkedQueue<String> READY_FILES = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
	private static final AtomicInteger ART_GENERATION = new AtomicInteger();
	private static final ConcurrentLinkedQueue<ReadyArt> READY_ART = new ConcurrentLinkedQueue<>();
	private static int uploadsThisFrame;
	private static long lastFrameResetNs = System.nanoTime();

	private CardArt() {
	}

	/** Drop last frame's pins. Call once at the start of a screen render. */
	public static void beginFrame() {
		PINNED.clear();
		uploadsThisFrame = 0;
		lastFrameResetNs = System.nanoTime();
	}

	/** Keep this printing in memory while the sleeve is on screen. */
	public static void pin(String printingId) {
		String id = validId(printingId);
		if (id != null) {
			PINNED.add(id);
		}
	}

	/** Queue a download or a disk read without drawing. Disk hits do not touch the network. */
	public static void preload(String printingId) {
		request(printingId, true, false);
	}

	public record Face(ResourceLocation texture, int width, int height) {
	}

	public static boolean draw(GuiGraphics graphics, String printingId, String finish, int x, int y, int w, int h) {
		return draw(graphics, printingId, finish, x, y, w, h, false);
	}

	/** @return false when the art is not ready. back is the other face of a double-faced card. */
	public static boolean draw(GuiGraphics graphics, String printingId, String finish, int x, int y, int w, int h, boolean back) {
		Face face = request(printingId, true, back);
		if (face == null || w <= 0 || h <= 0 || face.width() <= 0 || face.height() <= 0) {
			return false;
		}
		graphics.blit(face.texture(), x, y, w, h, 0.0F, 0.0F, face.width(), face.height(), face.width(), face.height());
		if (!back && finish != null && !Finish.NONFOIL.name().equals(finish)) {
			shimmer(graphics, x, y, w, h, finish);
		}
		return true;
	}

	public static String treatmentLabel(String treatment) {
		return switch (treatment == null ? "" : treatment) {
			case "retro" -> "retro";
			case "borderless" -> "borderless";
			case "special_guest" -> "special guest";
			default -> "";
		};
	}

	public static String caption(String treatment, String finish) {
		String label = treatmentLabel(treatment);
		String finishLabel = Finish.ETCHED.name().equals(finish) ? "etched" : Finish.FOIL.name().equals(finish) ? "foil" : "";
		if (label.isEmpty()) {
			return finishLabel;
		}
		if (finishLabel.isEmpty()) {
			return label;
		}
		return label + " · " + finishLabel;
	}

	/** The printing texture, or null until it has been uploaded. Does not pin it in memory. */
	public static Face peek(String printingId) {
		return request(printingId, false, false);
	}

	/** The other face, if Scryfall has one. Null until it uploads, or if this printing has no back. */
	public static Face peekBack(String printingId) {
		return request(printingId, false, true);
	}

	private static Face request(String printingId, boolean pin, boolean back) {
		uploadReady();
		String id = validId(printingId);
		if (id == null) {
			return null;
		}
		String key = back ? id + "b" : id;
		if (pin) {
			PINNED.add(key);
		}
		Face face = FACES.get(key);
		if (face != null) {
			return face;
		}
		synchronized (QUEUE) {
			if (FAILED.contains(key) || FACES.containsKey(key) || !QUEUED.add(key)) {
				return FACES.get(key);
			}
			QUEUE.addLast(key);
		}
		pump();
		return null;
	}

	private static String validId(String printingId) {
		if (printingId == null || printingId.length() < 36) {
			return null;
		}
		String id = printingId.toLowerCase(Locale.ROOT);
		if (!ID.matcher(id).matches() || FAILED.contains(id)) {
			return null;
		}
		return id;
	}

	private static void pump() {
		while (true) {
			String id;
			synchronized (QUEUE) {
				if (IN_FLIGHT.get() >= MAX_DOWNLOADS) {
					return;
				}
				id = QUEUE.pollFirst();
				if (id == null) {
					return;
				}
				IN_FLIGHT.incrementAndGet();
			}
			Thread thread = new Thread(() -> download(id), "spellblock-art");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static void download(String id) {
		int gen = ART_GENERATION.get();
		try {
			Path path = cache(id);
			if (!validArtFile(path)) {
				fetch(id, path);
			}
			if (validArtFile(path)) {
				READY_ART.add(new ReadyArt(id, gen));
			} else {
				fail(id);
			}
		} catch (Exception e) {
			SpellBlock.LOGGER.warn("Card art download failed for {}: {}", id, e.toString());
			fail(id);
		} finally {
			IN_FLIGHT.decrementAndGet();
			pump();
		}
	}

	private static void fetch(String id, Path path) throws IOException, InterruptedException {
		boolean back = id.length() == 37 && id.charAt(36) == 'b';
		String face = back ? id.substring(0, 36) : id;
		ArtQuality quality = SpellBlockConfig.get().artQuality();
		String folder = quality.scryfallFolder();
		String ext = quality.extension();
		String url = "https://cards.scryfall.io/" + folder + "/" + (back ? "back" : "front") + "/"
			+ face.charAt(0) + "/" + face.charAt(1) + "/" + face + "." + ext;
		HttpResponse<byte[]> response = com.neugoga.spellblock.catalog.ScryfallClient.getBytes(HTTP, url);
		if (response.statusCode() != 200) {
			throw new IOException("art status " + response.statusCode());
		}
		byte[] body = response.body();
		if (body.length < 8 || body.length > MAX_BYTES || !validArtBytes(body)) {
			throw new IOException("art was not a supported image");
		}
		// Minecraft NativeImage only loads PNG. Convert JPEG downloads before caching.
		if (isJpeg(body)) {
			body = jpegToPng(body);
		}
		if (!isPng(body)) {
			throw new IOException("art could not be stored as PNG");
		}
		Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
		Files.createDirectories(path.getParent());
		Files.write(tmp, body);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Decode JPEG with ImageIO and re-encode as PNG for NativeImage. */
	private static byte[] jpegToPng(byte[] jpeg) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
		if (image == null) {
			throw new IOException("ImageIO could not decode JPEG");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16_384, jpeg.length));
		if (!ImageIO.write(image, "png", out)) {
			throw new IOException("ImageIO could not encode PNG");
		}
		return out.toByteArray();
	}

	private static void uploadReady() {
		long now = System.nanoTime();
		if (now - lastFrameResetNs > 50_000_000L) {
			uploadsThisFrame = 0;
			lastFrameResetNs = now;
		}
		while (uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
			ReadyArt ready = READY_ART.poll();
			if (ready == null) {
				return;
			}
			String id = ready.id();
			if (ready.generation() != ART_GENERATION.get() || FAILED.contains(id) || FACES.containsKey(id)) {
				synchronized (QUEUE) {
					QUEUED.remove(id);
				}
				continue;
			}
			uploadsThisFrame++;
			Path path = cache(id);
			try (InputStream in = Files.newInputStream(path)) {
				NativeImage image = fitted(NativeImage.read(in));
				DynamicTexture texture = new DynamicTexture(image);
				texture.setFilter(true, false);
				texture.upload();
				ResourceLocation location = SpellBlock.id("art/" + id);
				Minecraft.getInstance().getTextureManager().register(location, texture);
				FACES.put(id, new Face(location, image.getWidth(), image.getHeight()));
				synchronized (QUEUE) {
					QUEUED.remove(id);
				}
			} catch (Exception e) {
				SpellBlock.LOGGER.warn("Card art upload failed for {}: {}", id, e.toString());
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
				}
				fail(id);
			}
		}
	}

	private static NativeImage fitted(NativeImage image) {
		int srcW = image.getWidth();
		int srcH = image.getHeight();
		int maxEdge = switch (SpellBlockConfig.get().artQuality()) {
			case NORMAL -> 320;
			case LARGE -> 400;
			case PNG -> 512;
		};
		if (srcW <= maxEdge) {
			return image;
		}
		int dstW = maxEdge;
		int dstH = Math.max(1, srcH * dstW / srcW);
		NativeImage scaled = new NativeImage(dstW, dstH, false);
		try {
			image.resizeSubRectTo(0, 0, srcW, srcH, scaled);
		} catch (RuntimeException e) {
			scaled.close();
			return image;
		}
		image.close();
		return scaled;
	}

	private static void fail(String id) {
		FAILED.add(id);
		synchronized (QUEUE) {
			QUEUED.remove(id);
		}
	}

	private static boolean validArtFile(Path path) {
		if (!Files.isRegularFile(path)) {
			return false;
		}
		try {
			long size = Files.size(path);
			if (size < 8 || size > MAX_BYTES) {
				return false;
			}
			byte[] head = new byte[8];
			try (InputStream in = Files.newInputStream(path)) {
				if (in.read(head) != 8) {
					return false;
				}
			}
			return isPng(head);
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean validArtBytes(byte[] body) {
		return isPng(body) || isJpeg(body);
	}

	private static boolean isPng(byte[] body) {
		return body.length >= 8
			&& (body[0] & 0xFF) == 0x89
			&& body[1] == 0x50
			&& body[2] == 0x4E
			&& body[3] == 0x47;
	}

	private static boolean isJpeg(byte[] body) {
		return body.length >= 3
			&& (body[0] & 0xFF) == 0xFF
			&& (body[1] & 0xFF) == 0xD8
			&& (body[2] & 0xFF) == 0xFF;
	}

	private static volatile boolean legacyArtMigrated;

	private static Path artRoot() {
		Path target = FabricLoader.getInstance().getGameDir()
			.resolve("SpellBlock")
			.resolve("art");
		if (!legacyArtMigrated) {
			legacyArtMigrated = true;
			Path legacy = FabricLoader.getInstance().getConfigDir()
				.resolve("spellblock")
				.resolve("art");
			if (Files.isDirectory(legacy) && !Files.exists(target)) {
				try {
					Files.createDirectories(target.getParent());
					Files.move(legacy, target);
					SpellBlock.LOGGER.info("Moved card art cache from {} to {}", legacy, target);
				} catch (IOException e) {
					SpellBlock.LOGGER.warn("Could not migrate legacy card art cache from {}: {}", legacy, e.getMessage());
				}
			}
		}
		return target;
	}

	private static Path cache(String id) {
		ArtQuality quality = SpellBlockConfig.get().artQuality();
		String safe = id.replace(':', '_');
		// Always .png on disk — JPEG sources are converted after download.
		return artRoot()
			.resolve(quality.name().toLowerCase(Locale.ROOT))
			.resolve(safe + ".png");
	}

	/** Flush memory textures after the player changes art quality. */
	public static void onConfigChanged() {
		Minecraft client = Minecraft.getInstance();
		ART_GENERATION.incrementAndGet();
		synchronized (QUEUE) {
			QUEUE.clear();
			QUEUED.clear();
		}
		READY_ART.clear();
		READY_FILES.clear();
		FAILED.clear();
		for (Face face : FACES.values()) {
			client.getTextureManager().release(face.texture());
		}
		FACES.clear();
		PINNED.clear();
	}

	private record ReadyArt(String id, int generation) {
	}

	/** Delete cached art files for every quality tier. Returns how many files were removed. */
	public static int clearDiskCache() {
		Path root = artRoot();
		int removed = 0;
		if (!Files.isDirectory(root)) {
			return 0;
		}
		try (var walk = Files.walk(root)) {
			for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				if (Files.isRegularFile(path)) {
					try {
						Files.deleteIfExists(path);
						removed++;
					} catch (IOException ignored) {
					}
				}
			}
		} catch (IOException ignored) {
		}
		return removed;
	}

	private static void shimmer(GuiGraphics graphics, int x, int y, int w, int h, String finish) {
		int color = Finish.ETCHED.name().equals(finish) ? 0x668FD0C8 : 0x55FFFFFF;
		graphics.enableScissor(x, y, x + w, y + h);
		float span = h + 14f;
		float cycle = (System.nanoTime() / 1_000_000_000f) * 70f;
		float band = cycle - span * (float) Math.floor(cycle / span) - 7f;
		int base = (int) Math.floor(band);
		float frac = band - base;
		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, frac, 0.0f);
		int thick = Math.max(4, h / 10);
		graphics.fill(x, y + base, x + w, y + base + thick, color);
		graphics.pose().popPose();
		graphics.disableScissor();
	}
}
