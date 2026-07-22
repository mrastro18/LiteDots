package dev.santos.litedots.chat;

import java.util.List;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import org.slf4j.Logger;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import dev.santos.litedots.dot.Dot;

/**
 * Styled chat feedback (SPEC.md 3.6), built with Adventure MiniMessage and bridged to vanilla
 * {@link Component}s.
 *
 * <p>Bridge: {@code MiniMessage.miniMessage().deserialize(...)} (Adventure component) ->
 * {@code GsonComponentSerializer.gson().serializeToTree(...)} (JSON) ->
 * {@code ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, ...)} (vanilla component).
 * Adventure 4.23.0's default gson serializer emits the modern 1.21.5+ schema (verified in
 * {@code JSONOptions.byDataVersion()}: snake_case {@code click_event}/{@code hover_event} with
 * action-specific fields like {@code command}) — exactly what 26.2's
 * {@code Style.Serializer.MAP_CODEC} decodes, so hover and click survive the round trip. This is
 * proven at boot by {@link #logBridgeSelfTest(Logger)}.
 *
 * <p>Palette (catppuccin-mocha-ish pastels): gradient {@code #a6e3a1->#94e2d5} prefix, muted gray
 * {@code #6c7086}, body {@code #bac2de}, accent green {@code #a6e3a1}, error red {@code #f38ba8},
 * white for emphasized values.
 */
public final class Messages {
	private Messages() {
	}

	private static final String C_MUTED = "#6c7086";
	private static final String C_BODY = "#bac2de";
	private static final String C_ACCENT = "#a6e3a1";
	private static final String C_ERROR = "#f38ba8";
	private static final String C_WHITE = "#ffffff";

	/** {@code LiteDots » } — prepended to every message. */
	private static final String PREFIX =
		"<gradient:" + C_ACCENT + ":#94e2d5>LiteDots</gradient> <color:" + C_MUTED + ">»</color> ";

	private static final char CHECK = '✔';   // ✔
	private static final char SQUARE = '■';  // ■
	private static final char BULLET = '•';  // •
	private static final char ELLIPSIS = '…'; // …

	private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
	private static final GsonComponentSerializer GSON_SERIALIZER = GsonComponentSerializer.gson();

	private static Logger logger;

	public static void init(Logger log) {
		logger = log;
	}

	/**
	 * Renders a MiniMessage string into a vanilla {@link Component} via the Adventure-JSON
	 * bridge. Never throws: on any bridge failure the raw string is shown unstyled (and a
	 * warning logged) rather than crashing a command.
	 */
	public static Component render(String miniMessage) {
		try {
			net.kyori.adventure.text.Component adventure = MINI_MESSAGE.deserialize(miniMessage);
			JsonElement json = GSON_SERIALIZER.serializeToTree(adventure);
			return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json)
				.resultOrPartial(error -> warn("Component bridge rejected JSON: " + error))
				.orElseGet(() -> Component.literal(miniMessage));
		} catch (Exception e) {
			warn("MiniMessage bridge failed: " + e);
			return Component.literal(miniMessage);
		}
	}

	private static void warn(String message) {
		if (logger != null) {
			logger.warn(message);
		}
	}

	/** Sends a prefixed feedback message; {@code bodyMm} is MiniMessage markup for the body. */
	public static void info(FabricClientCommandSource source, String bodyMm) {
		source.sendFeedback(render(PREFIX + bodyMm));
	}

	/** Sends a prefixed error; the body is wrapped in the palette's error red. */
	public static void error(FabricClientCommandSource source, String bodyMm) {
		source.sendError(render(PREFIX + "<color:" + C_ERROR + ">" + bodyMm + "</color>"));
	}

	/** Wraps a value in emphasized white, for use inside body markup. */
	public static String white(Object value) {
		return "<color:" + C_WHITE + ">" + value + "</color>";
	}

	/** Body-colored span, for embedding inside otherwise-uncolored markup. */
	public static String body(String inner) {
		return "<color:" + C_BODY + ">" + inner + "</color>";
	}

	/**
	 * {@code New dot saved ✔ [Delete]}: "dot" underlined with the full hover lore; the check in
	 * accent green; Delete underlined error-red, clicking runs the precise remove command.
	 */
	public static void addSuccess(FabricClientCommandSource source, Dot dot, String worldKey) {
		String mm = PREFIX
			+ "<color:" + C_BODY + ">New <underlined><hover:show_text:'" + hoverLore(dot, worldKey)
			+ "'>dot</hover></underlined> saved</color> <color:" + C_ACCENT + ">" + CHECK + "</color> "
			+ deleteButton(dot);
		source.sendFeedback(render(mm));
	}

	/**
	 * One {@code /list-dots} entry: {@code • (x,y,z) face (u,v) ■ [Delete]}, the text carrying
	 * the same hover lore as the add-success message and the square tinted the dot's color.
	 */
	public static String listEntry(Dot dot, String worldKey) {
		String entryText = "<color:" + C_BODY + ">(" + white(dot.x() + ", " + dot.y() + ", " + dot.z()) + ") "
			+ white(dot.face().getName()) + " (" + white(dot.u() + ", " + dot.v()) + ")</color> "
			+ "<color:" + rgbHex(dot.argb()) + ">" + SQUARE + "</color>";
		return "<color:" + C_MUTED + ">" + BULLET + "</color> "
			+ "<hover:show_text:'" + hoverLore(dot, worldKey) + "'>" + entryText + "</hover> "
			+ deleteButton(dot);
	}

	/**
	 * Multi-line hover lore for a dot. NOTE: embedded inside a single-quoted
	 * {@code <hover:show_text:'...'>} argument, so it must never contain a single quote — all
	 * interpolated values are digits, face names, hex colors, and the world key (hex +
	 * identifier characters).
	 */
	private static String hoverLore(Dot dot, String worldKey) {
		String colorHex = rgbHex(dot.argb());
		return "<color:" + C_BODY + ">Dot @ (" + white(dot.x() + ", " + dot.y() + ", " + dot.z()) + ")"
			+ "<newline>Face: " + white(dot.face().getName())
			+ "<newline>Texel: (" + white(dot.u() + ", " + dot.v()) + ")"
			+ "<newline>Color: <color:" + colorHex + ">" + SQUARE + "</color> " + white(colorHex.toUpperCase(java.util.Locale.ROOT))
			+ "<newline>World: " + white(shortWorldKey(worldKey))
			+ "</color>";
	}

	/** {@code [Delete]} button: error-red underlined, click runs the precise remove command. */
	private static String deleteButton(Dot dot) {
		String command = "/remove-dot %d %d %d %s %d %d".formatted(
			dot.x(), dot.y(), dot.z(), dot.face().getName(), dot.u(), dot.v());
		return "<click:run_command:'" + command + "'>"
			+ "<hover:show_text:'<color:" + C_BODY + ">Remove this dot</color>'>"
			+ "<color:" + C_ERROR + "><underlined>[Delete]</underlined></color>"
			+ "</hover></click>";
	}

	/** First 8 hex chars of the seed + {@code …} + the dimension id, e.g. {@code d6450e04…:minecraft:overworld}. */
	public static String shortWorldKey(String worldKey) {
		int colon = worldKey.indexOf(':');
		if (colon < 8) {
			return worldKey;
		}
		return worldKey.substring(0, 8) + ELLIPSIS + worldKey.substring(colon);
	}

	/** {@code #rrggbb} for a packed ARGB int (alpha dropped). */
	private static String rgbHex(int argb) {
		return "#%06x".formatted(argb & 0xFFFFFF);
	}

	/**
	 * Boot-time proof (SPEC.md 3.6 / smoke test) that the full bridge works: renders a message
	 * exercising gradient + hover + click, re-encodes the resulting vanilla component to JSON
	 * with the vanilla codec, and logs it. "OK" requires the JSON to still carry
	 * {@code click_event} (with {@code run_command}/{@code command}) and {@code hover_event} —
	 * i.e. nothing was dropped crossing the bridge.
	 */
	public static void logBridgeSelfTest(Logger log) {
		String mm = PREFIX + "<color:" + C_BODY + ">bridge self-test</color> "
			+ "<color:" + C_ACCENT + ">" + CHECK + "</color> "
			+ "<click:run_command:'/list-dots'>"
			+ "<hover:show_text:'<color:" + C_BODY + ">hover intact</color>'>"
			+ "<color:" + C_ERROR + "><underlined>[Click]</underlined></color>"
			+ "</hover></click>";
		Component component = render(mm);
		String json = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component)
			.result().map(JsonElement::toString).orElse("<vanilla re-encode failed>");
		boolean ok = json.contains("click_event") && json.contains("run_command")
			&& json.contains("command") && json.contains("hover_event") && json.contains("show_text");
		log.info("MiniMessage bridge self-test {}: {}", ok ? "OK (gradient+hover+click intact)" : "FAILED", json);
	}

	/** Convenience for list output: joins pre-built lines into one prefixed multi-line message. */
	public static void infoMultiline(FabricClientCommandSource source, String headerMm, List<String> lines) {
		StringBuilder mm = new StringBuilder(PREFIX).append(headerMm);
		for (String line : lines) {
			mm.append("<newline>  ").append(line);
		}
		source.sendFeedback(render(mm.toString()));
	}
}
