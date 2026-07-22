package dev.santos.litedots.dot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * A single painted texel: one 1/16 x 1/16 patch of color on one face of one block.
 *
 * <p>Position is stored as raw {@code x}/{@code y}/{@code z} ints (rather than holding a
 * {@link BlockPos} directly) so the record stays trivially equal/hashable/serializable;
 * {@link #pos()} reconstructs a {@link BlockPos} on demand for callers that need one.
 *
 * <p>{@code (u, v)} are texel coordinates in {@code [0, 15]} on the face's local (u, v) plane, per
 * SPEC.md 3.2. {@code argb} is a packed {@code 0xAARRGGBB} color, matching what
 * {@code VertexConsumer#setColor(int)} expects directly (see RENDER_NOTES.md 5).
 */
public record Dot(int x, int y, int z, Direction face, int u, int v, int argb) {
	/** Default dot color per SPEC.md 3.2: opaque red. */
	public static final int DEFAULT_COLOR = 0xFFFF2020;

	public BlockPos pos() {
		return new BlockPos(x, y, z);
	}

	/**
	 * Maps a point on a block face (relative coordinates in {@code [0, 1]} within the block) to a
	 * texel {@code (u, v)} in {@code [0, 15]}, per the per-face axis table in SPEC.md 3.2:
	 * UP/DOWN -> (x, z), NORTH/SOUTH -> (x, y), EAST/WEST -> (z, y).
	 *
	 * @param face the block face the point lies on
	 * @param rel  the point, relative to the block's origin corner, with components in {@code [0, 1]}
	 * @return {@code {u, v}}, each clamped to {@code [0, 15]}
	 */
	public static int[] faceUV(Direction face, Vec3 rel) {
		double u;
		double v;
		switch (face.getAxis()) {
			case Y -> {
				u = rel.x;
				v = rel.z;
			}
			case Z -> {
				u = rel.x;
				v = rel.y;
			}
			case X -> {
				u = rel.z;
				v = rel.y;
			}
			default -> throw new IllegalStateException("Unexpected face axis: " + face.getAxis());
		}
		return new int[] {texel(u), texel(v)};
	}

	private static int texel(double coord) {
		int t = (int) Math.floor(coord * 16.0);
		if (t < 0) {
			return 0;
		}
		if (t > 15) {
			return 15;
		}
		return t;
	}
}
