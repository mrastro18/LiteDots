package dev.santos.litedots.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import dev.santos.litedots.chat.Messages;
import dev.santos.litedots.dot.Dot;
import dev.santos.litedots.dot.DotStore;
import dev.santos.litedots.world.WorldKeyTracker;

/**
 * Client-side command grammar, per SPEC.md 3.1:
 *
 * <pre>
 * /add-dot [color]                          -&gt; dot where the player is looking
 * /add-dot &lt;facing&gt; [color]                 -&gt; same ray, forced onto &lt;facing&gt; of the hit block
 * /add-dot &lt;facing&gt; &lt;yaw&gt; &lt;pitch&gt; [color]   -&gt; ray from the given yaw/pitch, forced onto &lt;facing&gt;
 * /remove-dot                               -&gt; remove all dots on the face the player is looking at
 * /remove-dot &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;face&gt; &lt;u&gt; &lt;v&gt;   -&gt; remove exactly that dot (used by the [Delete] button)
 * /clear-dots                               -&gt; remove every dot for the current world
 * /list-dots                                -&gt; dot count + world key, plus per-dot entries
 * </pre>
 *
 * <p>The precise remove form is what the chat [Delete] buttons execute via a
 * {@code run_command} click event. Clicked components route through
 * {@code ClientPacketListener.sendUnattendedCommand} (verified in the 26.2 {@code Screen}
 * sources), which Fabric's command API intercepts and dispatches to client commands with
 * {@code attended() == false} — intentionally fine here: nothing in these handlers gates on
 * {@code attended()}, so the button works without a confirmation screen.
 *
 * <p>{@code color} is one of Minecraft's 16 dye color names (case-insensitive) or a 6-digit hex
 * {@code RRGGBB} WITHOUT a leading {@code #} — brigadier's
 * {@code StringReader.isAllowedInUnquotedString} only permits {@code [0-9A-Za-z_\-.+]}, so a
 * {@code #} can never reach a {@code word()} argument. Omitted, the default
 * {@link Dot#DEFAULT_COLOR} applies.
 *
 * <p>Grammar-tree notes: facing words and dye color names are disjoint sets, so the first
 * position is a single word argument dispatched semantically at execute time. At the second
 * position (after a facing) the {@code color} node is registered BEFORE the {@code yaw} node
 * deliberately: brigadier tries both siblings and prefers the parse that consumes the whole
 * input, so {@code <yaw> <pitch>} still wins whenever both floats are present, while a lone
 * all-digit hex color (e.g. {@code /add-dot north 123456}) resolves to the color node (both
 * parses consume everything, and ties keep registration order).
 */
public final class DotCommands {
	private DotCommands() {
	}

	private static final double RAY_DISTANCE = 100.0;
	private static final String[] FACINGS = {"north", "south", "east", "west", "up", "down", "auto"};
	private static final String[] FACES_ONLY = {"north", "south", "east", "west", "up", "down"};
	private static final int LIST_LIMIT = 10;
	private static final String[] COLOR_NAMES = buildColorNames();
	private static final String[] FACINGS_AND_COLORS = concat(FACINGS, COLOR_NAMES);

	private static String[] buildColorNames() {
		DyeColor[] values = DyeColor.values();
		String[] names = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			names[i] = values[i].getName();
		}
		return names;
	}

	private static String[] concat(String[] a, String[] b) {
		String[] out = new String[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> registerCommands(dispatcher));
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(
			ClientCommands.literal("add-dot")
				.executes(ctx -> addDot(ctx.getSource(), null, null, null, null))
				.then(
					ClientCommands.argument("facing", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(FACINGS_AND_COLORS, builder))
						.executes(ctx -> addDotFacingOrColor(ctx.getSource(), StringArgumentType.getString(ctx, "facing")))
						// NOTE: "color" is registered before "yaw" on purpose — see class javadoc.
						.then(
							ClientCommands.argument("color", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(COLOR_NAMES, builder))
								.executes(ctx -> addDot(
									ctx.getSource(),
									StringArgumentType.getString(ctx, "facing"),
									null,
									null,
									StringArgumentType.getString(ctx, "color")
								))
						)
						.then(
							ClientCommands.argument("yaw", FloatArgumentType.floatArg())
								.then(
									ClientCommands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
										.executes(ctx -> addDot(
											ctx.getSource(),
											StringArgumentType.getString(ctx, "facing"),
											FloatArgumentType.getFloat(ctx, "yaw"),
											FloatArgumentType.getFloat(ctx, "pitch"),
											null
										))
										.then(
											ClientCommands.argument("color", StringArgumentType.word())
												.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(COLOR_NAMES, builder))
												.executes(ctx -> addDot(
													ctx.getSource(),
													StringArgumentType.getString(ctx, "facing"),
													FloatArgumentType.getFloat(ctx, "yaw"),
													FloatArgumentType.getFloat(ctx, "pitch"),
													StringArgumentType.getString(ctx, "color")
												))
										)
								)
						)
				)
		);

		dispatcher.register(
			ClientCommands.literal("remove-dot")
				.executes(ctx -> removeDot(ctx.getSource()))
				.then(
					ClientCommands.argument("x", IntegerArgumentType.integer())
						.then(
							ClientCommands.argument("y", IntegerArgumentType.integer())
								.then(
									ClientCommands.argument("z", IntegerArgumentType.integer())
										.then(
											ClientCommands.argument("face", StringArgumentType.word())
												.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(FACES_ONLY, builder))
												.then(
													ClientCommands.argument("u", IntegerArgumentType.integer(0, 15))
														.then(
															ClientCommands.argument("v", IntegerArgumentType.integer(0, 15))
																.executes(ctx -> removeDotPrecise(
																	ctx.getSource(),
																	IntegerArgumentType.getInteger(ctx, "x"),
																	IntegerArgumentType.getInteger(ctx, "y"),
																	IntegerArgumentType.getInteger(ctx, "z"),
																	StringArgumentType.getString(ctx, "face"),
																	IntegerArgumentType.getInteger(ctx, "u"),
																	IntegerArgumentType.getInteger(ctx, "v")
																))
														)
												)
										)
								)
						)
				)
		);
		dispatcher.register(ClientCommands.literal("clear-dots").executes(ctx -> clearDots(ctx.getSource())));
		dispatcher.register(ClientCommands.literal("list-dots").executes(ctx -> listDots(ctx.getSource())));
	}

	/**
	 * Handles the single-argument form {@code /add-dot <token>}, where the token may be either a
	 * facing or a color (the two vocabularies are disjoint).
	 */
	private static int addDotFacingOrColor(FabricClientCommandSource source, String token) {
		if (isFacingWord(token)) {
			return addDot(source, token, null, null, null);
		}
		if (parseColor(token) != null) {
			return addDot(source, null, null, null, token);
		}
		Messages.error(source,
			"'" + Messages.white(token) + "' is neither a facing (north|south|east|west|up|down|auto) nor a color. "
				+ "Colors: " + String.join(", ", COLOR_NAMES) + "; for hex use RRGGBB without #.");
		return 0;
	}

	private static boolean isFacingWord(String token) {
		String lower = token.toLowerCase(Locale.ROOT);
		return lower.equals("auto") || Direction.byName(lower) != null;
	}

	private static int addDot(FabricClientCommandSource source, String facingArg, Float yawArg, Float pitchArg, String colorArg) {
		String worldKey = worldKeyOrError(source);
		if (worldKey == null) {
			return 0;
		}

		LocalPlayer player = source.getPlayer();
		ClientLevel level = source.getLevel();
		if (player == null || level == null) {
			Messages.error(source, "Not in a world.");
			return 0;
		}

		Direction forcedFace = null;
		if (facingArg != null && !facingArg.equalsIgnoreCase("auto")) {
			forcedFace = Direction.byName(facingArg.toLowerCase(Locale.ROOT));
			if (forcedFace == null) {
				Messages.error(source,
					"Unknown facing '" + Messages.white(facingArg) + "'. Use north|south|east|west|up|down|auto.");
				return 0;
			}
		}

		ParsedColor color;
		if (colorArg == null) {
			color = new ParsedColor(Dot.DEFAULT_COLOR, "#%06X".formatted(Dot.DEFAULT_COLOR & 0xFFFFFF));
		} else {
			color = parseColor(colorArg);
			if (color == null) {
				Messages.error(source,
					"Unknown color '" + Messages.white(colorArg) + "'. Use a dye name ("
						+ String.join(", ", COLOR_NAMES) + "); for hex use RRGGBB without #.");
				return 0;
			}
		}

		Vec3 eyePos = player.getEyePosition();
		Vec3 direction = yawArg != null && pitchArg != null
			? player.calculateViewVector(pitchArg, yawArg)
			: player.getLookAngle();
		Vec3 to = eyePos.add(direction.scale(RAY_DISTANCE));

		BlockHitResult hit = level.clip(new ClipContext(eyePos, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
			Messages.error(source, "Not looking at a block.");
			return 0;
		}

		BlockPos blockPos = hit.getBlockPos();
		Direction hitFace = hit.getDirection();

		Direction face;
		int u;
		int v;
		if (forcedFace == null || forcedFace == hitFace) {
			face = hitFace;
			Vec3 rel = hit.getLocation().subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ());
			int[] uv = Dot.faceUV(face, rel);
			u = uv[0];
			v = uv[1];
		} else {
			face = forcedFace;
			int[] uv = intersectForcedFace(eyePos, direction, hit.getLocation(), blockPos, face);
			u = uv[0];
			v = uv[1];
		}

		Dot dot = new Dot(blockPos.getX(), blockPos.getY(), blockPos.getZ(), face, u, v, color.argb());
		DotStore.add(worldKey, dot);

		Messages.addSuccess(source, dot, worldKey);
		return 1;
	}

	/** Sends the styled no-world-data error and returns {@code null} when no key is known yet. */
	private static String worldKeyOrError(FabricClientCommandSource source) {
		String worldKey = WorldKeyTracker.get();
		if (worldKey == null) {
			Messages.error(source, "No world data yet; join a world and try again in a moment.");
		}
		return worldKey;
	}

	/** A parsed color: packed opaque {@code 0xFFRRGGBB} plus how to echo it back in feedback. */
	private record ParsedColor(int argb, String display) {
	}

	/**
	 * Parses a color token: first as one of Minecraft's 16 dye color names (case-insensitive,
	 * RGB from {@link DyeColor#getTextureDiffuseColor()}, which is already opaque-packed), then
	 * as a 6-digit hex {@code RRGGBB} (no {@code #} — brigadier word arguments cannot contain
	 * one). Returns {@code null} if the token is neither. Alpha is always forced opaque,
	 * matching the storage format (SPEC.md 3.4).
	 */
	private static ParsedColor parseColor(String token) {
		DyeColor dye = DyeColor.byName(token.toLowerCase(Locale.ROOT), null);
		if (dye != null) {
			return new ParsedColor(0xFF000000 | (dye.getTextureDiffuseColor() & 0xFFFFFF), dye.getName());
		}
		if (token.length() == 6 && isHex(token)) {
			int rgb = Integer.parseInt(token, 16);
			return new ParsedColor(0xFF000000 | rgb, "#%06X".formatted(rgb));
		}
		return null;
	}

	private static boolean isHex(String token) {
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
			if (!hexDigit) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Ray-plane intersection against the infinite plane containing {@code face} of the block at
	 * {@code blockPos}, per SPEC.md 3.1's forced-face bullet: clamp the in-plane point to the
	 * block's bounds, then derive the pixel from that intersection.
	 *
	 * <p>The plane intersection is only used when the ray genuinely crosses the plane ahead of
	 * the eye ({@code t > 1e-6}). If the plane lies behind the eye along the ray (e.g. forcing
	 * {@code down} on a block whose bottom face is below eye level while looking upward), or the
	 * ray is (near) parallel to the plane, the intersection point would be bogus — so instead the
	 * ORIGINAL raycast hit location is projected orthogonally onto the forced face's plane (its
	 * in-plane coordinates are kept; only the face-axis component is replaced), then clamped and
	 * converted to a texel as usual. Both paths are well-defined placements, so both succeed.
	 */
	private static int[] intersectForcedFace(Vec3 origin, Vec3 direction, Vec3 hitLocation, BlockPos blockPos, Direction face) {
		Direction.Axis axis = face.getAxis();
		boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;

		double blockOrigin = switch (axis) {
			case X -> blockPos.getX();
			case Y -> blockPos.getY();
			case Z -> blockPos.getZ();
		};
		double planeValue = blockOrigin + (positive ? 1.0 : 0.0);

		double originAxis = switch (axis) {
			case X -> origin.x;
			case Y -> origin.y;
			case Z -> origin.z;
		};
		double dirAxis = switch (axis) {
			case X -> direction.x;
			case Y -> direction.y;
			case Z -> direction.z;
		};

		Vec3 point = null;
		if (Math.abs(dirAxis) >= 1.0e-6) {
			double t = (planeValue - originAxis) / dirAxis;
			if (t > 1.0e-6) {
				point = origin.add(direction.scale(t));
			}
		}
		if (point == null) {
			// Ray parallel to the plane, or the plane lies behind the eye: project the original
			// raycast hit location orthogonally onto the forced face's plane instead.
			point = replaceAxis(hitLocation, axis, planeValue);
		}

		Vec3 rel = point.subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ());
		Vec3 clamped = new Vec3(clamp01(rel.x), clamp01(rel.y), clamp01(rel.z));
		return Dot.faceUV(face, clamped);
	}

	private static Vec3 replaceAxis(Vec3 point, Direction.Axis axis, double value) {
		return switch (axis) {
			case X -> new Vec3(value, point.y, point.z);
			case Y -> new Vec3(point.x, value, point.z);
			case Z -> new Vec3(point.x, point.y, value);
		};
	}

	private static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private static int removeDot(FabricClientCommandSource source) {
		String worldKey = worldKeyOrError(source);
		if (worldKey == null) {
			return 0;
		}

		LocalPlayer player = source.getPlayer();
		ClientLevel level = source.getLevel();
		if (player == null || level == null) {
			Messages.error(source, "Not in a world.");
			return 0;
		}

		Vec3 eyePos = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		Vec3 to = eyePos.add(direction.scale(RAY_DISTANCE));

		BlockHitResult hit = level.clip(new ClipContext(eyePos, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
			Messages.error(source, "Not looking at a block.");
			return 0;
		}

		BlockPos blockPos = hit.getBlockPos();
		Direction face = hit.getDirection();
		int removed = DotStore.removeFace(worldKey, blockPos, face);
		if (removed == 0) {
			Messages.info(source, Messages.body(
				"No dots on face " + Messages.white(face.getName()) + " of ("
					+ Messages.white(blockPos.getX() + ", " + blockPos.getY() + ", " + blockPos.getZ()) + ")."));
		} else {
			Messages.info(source, Messages.body(
				"Removed " + Messages.white(removed) + " dot(s) from face " + Messages.white(face.getName()) + " of ("
					+ Messages.white(blockPos.getX() + ", " + blockPos.getY() + ", " + blockPos.getZ()) + ")."));
		}
		return removed;
	}

	/**
	 * Precise removal, {@code /remove-dot <x> <y> <z> <face> <u> <v>} — the [Delete] button's
	 * target. Runs unattended when clicked (see class javadoc); no attended() gating anywhere.
	 */
	private static int removeDotPrecise(FabricClientCommandSource source, int x, int y, int z, String faceArg, int u, int v) {
		String worldKey = worldKeyOrError(source);
		if (worldKey == null) {
			return 0;
		}

		Direction face = Direction.byName(faceArg.toLowerCase(Locale.ROOT));
		if (face == null) {
			Messages.error(source, "Unknown face '" + Messages.white(faceArg) + "'. Use north|south|east|west|up|down.");
			return 0;
		}

		boolean removed = DotStore.removeDot(worldKey, x, y, z, face, u, v);
		if (!removed) {
			Messages.error(source, "No such dot at (" + Messages.white(x + ", " + y + ", " + z) + ") face "
				+ Messages.white(face.getName()) + " texel (" + Messages.white(u + ", " + v) + ").");
			return 0;
		}
		Messages.info(source, Messages.body("Dot removed."));
		return 1;
	}

	private static int clearDots(FabricClientCommandSource source) {
		String worldKey = worldKeyOrError(source);
		if (worldKey == null) {
			return 0;
		}
		int count = DotStore.clearWorld(worldKey);
		Messages.info(source, Messages.body("Cleared " + Messages.white(count) + " dot(s) for this world."));
		return count;
	}

	private static int listDots(FabricClientCommandSource source) {
		String worldKey = worldKeyOrError(source);
		if (worldKey == null) {
			return 0;
		}

		List<Dot> dots = DotStore.forWorld(worldKey);
		String header = Messages.body(
			Messages.white(dots.size()) + " dot(s) in this world "
				+ "<color:#6c7086>(" + Messages.shortWorldKey(worldKey) + ")</color>");

		List<String> lines = new ArrayList<>();
		int shown = Math.min(dots.size(), LIST_LIMIT);
		for (int i = 0; i < shown; i++) {
			lines.add(Messages.listEntry(dots.get(i), worldKey));
		}
		if (dots.size() > LIST_LIMIT) {
			lines.add(Messages.body("…and " + Messages.white(dots.size() - LIST_LIMIT) + " more"));
		}

		Messages.infoMultiline(source, header, lines);
		return dots.size();
	}
}
