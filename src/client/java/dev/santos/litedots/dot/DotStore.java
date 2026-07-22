package dev.santos.litedots.dot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import net.fabricmc.loader.api.FabricLoader;

import dev.santos.litedots.LiteDotsClient;

/**
 * In-memory dot storage keyed by world key (SPEC.md 3.3), backed by a Gson JSON file at
 * {@code <config>/litedots.json} (SPEC.md 3.4).
 *
 * <p>All mutation/read entry points are {@code synchronized} on a private lock. In practice both
 * commands and rendering run on the client thread, so contention is not expected, but this keeps
 * the store safe regardless of what thread touches it.
 */
public final class DotStore {
	private DotStore() {
	}

	private static final Object LOCK = new Object();
	private static final Map<String, List<Dot>> WORLDS = new LinkedHashMap<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "litedots.json";

	private static Path configPath;

	/** Loads persisted dots from disk. Safe to call once at client init. */
	public static void load() {
		synchronized (LOCK) {
			configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
			WORLDS.clear();

			if (!Files.exists(configPath)) {
				return;
			}

			try {
				String json = Files.readString(configPath, StandardCharsets.UTF_8);
				SaveFile save = GSON.fromJson(json, SaveFile.class);
				if (save == null || save.worlds == null) {
					return;
				}
				for (Map.Entry<String, List<DotJson>> entry : save.worlds.entrySet()) {
					List<Dot> dots = new ArrayList<>();
					for (DotJson dotJson : entry.getValue()) {
						Dot dot = dotJson == null ? null : dotJson.toDot();
						if (dot != null) {
							dots.add(dot);
						}
					}
					WORLDS.put(entry.getKey(), dots);
				}
			} catch (IOException | JsonSyntaxException | IllegalStateException e) {
				LiteDotsClient.LOGGER.warn("litedots.json is missing or corrupt; backing it up and starting empty", e);
				backupCorruptFile();
				WORLDS.clear();
			}
		}
	}

	private static void backupCorruptFile() {
		try {
			Path backup = configPath.resolveSibling(configPath.getFileName() + ".corrupt-" + System.currentTimeMillis());
			Files.move(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
			LiteDotsClient.LOGGER.warn("Backed up corrupt config to {}", backup);
		} catch (IOException e) {
			LiteDotsClient.LOGGER.warn("Failed to back up corrupt litedots.json", e);
		}
	}

	/** Adds a dot, replacing any existing dot with the same (pos, face, u, v), per SPEC.md 3.2. */
	public static void add(String worldKey, Dot dot) {
		synchronized (LOCK) {
			List<Dot> dots = WORLDS.computeIfAbsent(worldKey, key -> new ArrayList<>());
			dots.removeIf(existing -> sameTexel(existing, dot));
			dots.add(dot);
			saveLocked();
		}
	}

	private static boolean sameTexel(Dot a, Dot b) {
		return a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && a.face() == b.face() && a.u() == b.u() && a.v() == b.v();
	}

	/** Removes every dot at {@code pos}/{@code face} in {@code worldKey}. Returns the count removed. */
	public static int removeFace(String worldKey, BlockPos pos, Direction face) {
		synchronized (LOCK) {
			List<Dot> dots = WORLDS.get(worldKey);
			if (dots == null || dots.isEmpty()) {
				return 0;
			}
			int before = dots.size();
			dots.removeIf(dot -> dot.x() == pos.getX() && dot.y() == pos.getY() && dot.z() == pos.getZ() && dot.face() == face);
			int removed = before - dots.size();
			if (removed > 0) {
				saveLocked();
			}
			return removed;
		}
	}

	/**
	 * Removes exactly the dot identified by {@code (x, y, z, face, u, v)} from {@code worldKey}
	 * (color is not part of the identity). Returns whether a dot was removed.
	 */
	public static boolean removeDot(String worldKey, int x, int y, int z, Direction face, int u, int v) {
		synchronized (LOCK) {
			List<Dot> dots = WORLDS.get(worldKey);
			if (dots == null || dots.isEmpty()) {
				return false;
			}
			boolean removed = dots.removeIf(dot ->
				dot.x() == x && dot.y() == y && dot.z() == z && dot.face() == face && dot.u() == u && dot.v() == v);
			if (removed) {
				saveLocked();
			}
			return removed;
		}
	}

	/** Removes every dot for {@code worldKey}. Returns the count removed. */
	public static int clearWorld(String worldKey) {
		synchronized (LOCK) {
			List<Dot> removed = WORLDS.remove(worldKey);
			int count = removed == null ? 0 : removed.size();
			if (count > 0) {
				saveLocked();
			}
			return count;
		}
	}

	/** Returns an immutable snapshot of the dots for {@code worldKey} (empty if none/no key). */
	public static List<Dot> forWorld(String worldKey) {
		if (worldKey == null) {
			return List.of();
		}
		synchronized (LOCK) {
			List<Dot> dots = WORLDS.get(worldKey);
			return dots == null || dots.isEmpty() ? List.of() : List.copyOf(dots);
		}
	}

	/** Must be called while holding {@link #LOCK}. */
	private static void saveLocked() {
		if (configPath == null) {
			// load() hasn't run (e.g. called from a test); nothing sensible to write to.
			return;
		}

		SaveFile save = new SaveFile();
		save.worlds = new LinkedHashMap<>();
		for (Map.Entry<String, List<Dot>> entry : WORLDS.entrySet()) {
			List<DotJson> dots = new ArrayList<>();
			for (Dot dot : entry.getValue()) {
				dots.add(DotJson.fromDot(dot));
			}
			save.worlds.put(entry.getKey(), dots);
		}

		try {
			Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
			Files.createDirectories(configPath.getParent());
			Files.writeString(tmp, GSON.toJson(save), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, configPath, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LiteDotsClient.LOGGER.warn("Failed to save litedots.json", e);
		}
	}

	/** Gson root shape, per SPEC.md 3.4: {@code {"worlds": {"<worldKey>": [...] } } }. */
	private static final class SaveFile {
		Map<String, List<DotJson>> worlds;
	}

	/** Gson per-dot shape, per SPEC.md 3.4. */
	private static final class DotJson {
		int x;
		int y;
		int z;
		String face;
		int u;
		int v;
		String color;

		static DotJson fromDot(Dot dot) {
			DotJson json = new DotJson();
			json.x = dot.x();
			json.y = dot.y();
			json.z = dot.z();
			json.face = dot.face().getName();
			json.u = dot.u();
			json.v = dot.v();
			json.color = "#%06X".formatted(dot.argb() & 0xFFFFFF);
			return json;
		}

		Dot toDot() {
			if (face == null || color == null) {
				return null;
			}
			Direction direction = Direction.byName(face.toLowerCase(Locale.ROOT));
			if (direction == null) {
				return null;
			}
			String hex = color.startsWith("#") ? color.substring(1) : color;
			int rgb;
			try {
				rgb = Integer.parseInt(hex, 16);
			} catch (NumberFormatException e) {
				return null;
			}
			int argb = 0xFF000000 | (rgb & 0xFFFFFF);
			return new Dot(x, y, z, direction, clampTexel(u), clampTexel(v), argb);
		}

		private static int clampTexel(int value) {
			return Math.max(0, Math.min(15, value));
		}
	}
}
