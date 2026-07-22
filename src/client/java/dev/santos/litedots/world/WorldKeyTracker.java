package dev.santos.litedots.world;

/**
 * Holds the current "world key" — a string identifying the current world for dot-persistence
 * purposes, per SPEC.md 3.3: {@code "%016x:%s".formatted(hashedSeed, dimensionId)}.
 *
 * <p>Updated from {@code ClientPacketListenerMixin} at the tail of the login/respawn packet
 * handlers, which per RENDER_NOTES.md may run on the netty (network) thread rather than the
 * client thread. A single {@code volatile} field is sufficient here: writes are simple whole
 * reference assignments, and readers (commands, rendering) only ever need the latest value, not
 * a value synchronized with any other state.
 */
public final class WorldKeyTracker {
	private WorldKeyTracker() {
	}

	private static volatile String currentKey;

	/**
	 * Called from {@code ClientPacketListenerMixin} whenever a login/respawn packet reveals the
	 * current world's hashed seed and dimension.
	 */
	public static void update(long hashedSeed, String dimensionId) {
		currentKey = "%016x:%s".formatted(hashedSeed, dimensionId);
	}

	/**
	 * @return the current world key, or {@code null} if no login/respawn packet has been observed
	 * yet (e.g. still connecting).
	 */
	public static String get() {
		return currentKey;
	}
}
