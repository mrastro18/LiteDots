package dev.santos.litedots.render;

import java.util.List;

import org.joml.Matrix4fc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import dev.santos.litedots.dot.Dot;
import dev.santos.litedots.dot.DotStore;
import dev.santos.litedots.world.WorldKeyTracker;

/**
 * Renders every {@link Dot} for the current world key within 128 blocks of the camera, per
 * SPEC.md 3.5, using the exact call sequence proven in RENDER_NOTES.md: register on
 * {@link LevelRenderEvents#COLLECT_SUBMITS}, submit one camera-relative, depth-tested,
 * solid-color quad per dot via {@code submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
 * ...)}.
 */
public final class DotRenderer {
	private DotRenderer() {
	}

	/** Outward offset along the face normal, to avoid z-fighting with the block's own face. */
	private static final double NORMAL_OFFSET = 0.002;
	private static final double MAX_DISTANCE = 128.0;
	private static final double MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(DotRenderer::onCollectSubmits);
	}

	private static void onCollectSubmits(LevelRenderContext context) {
		String worldKey = WorldKeyTracker.get();
		if (worldKey == null) {
			return;
		}

		List<Dot> dots = DotStore.forWorld(worldKey);
		if (dots.isEmpty()) {
			return;
		}

		PoseStack poseStack = context.poseStack();
		if (poseStack == null) {
			// Per LevelRenderContext docs, poseStack() may not yet be populated in some call orders;
			// COLLECT_SUBMITS fires after LevelRendererMixin#onCreatePoseStack, but guard defensively.
			return;
		}

		Vec3 cameraPos = context.levelState().cameraRenderState.pos;

		for (Dot dot : dots) {
			if (!withinRange(dot, cameraPos)) {
				continue;
			}

			poseStack.pushPose();
			poseStack.translate(dot.x() - cameraPos.x, dot.y() - cameraPos.y, dot.z() - cameraPos.z);

			context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) ->
				emitFaceQuad(buffer, pose.pose(), dot.face(), dot.u(), dot.v(), dot.argb()));

			poseStack.popPose();
		}
	}

	private static boolean withinRange(Dot dot, Vec3 cameraPos) {
		double dx = dot.x() + 0.5 - cameraPos.x;
		double dy = dot.y() + 0.5 - cameraPos.y;
		double dz = dot.z() + 0.5 - cameraPos.z;
		return dx * dx + dy * dy + dz * dz <= MAX_DISTANCE_SQ;
	}

	/**
	 * Emits the 4 corners of the texel quad {@code [u/16, (u+1)/16] x [v/16, (v+1)/16]} on
	 * {@code face}'s plane, offset {@link #NORMAL_OFFSET} outward along the face's normal, in
	 * block-local coordinates (the pose stack has already been translated to the block's origin
	 * corner relative to the camera). Per SPEC.md 3.2's axis table: UP/DOWN -&gt; (x, z), NORTH/SOUTH
	 * -&gt; (x, y), EAST/WEST -&gt; (z, y); outward normals are +Y/-Y/-Z/+Z/+X/-X respectively. Vertices
	 * are ordered counter-clockwise as seen from outside the block along that normal (cull is off
	 * on {@code debugQuads()} per RENDER_NOTES.md 4, so this only matters for consistency).
	 */
	private static void emitFaceQuad(VertexConsumer buffer, Matrix4fc matrix, Direction face, int u, int v, int argb) {
		float u0 = u / 16.0F;
		float u1 = (u + 1) / 16.0F;
		float v0 = v / 16.0F;
		float v1 = (v + 1) / 16.0F;
		float e = (float) NORMAL_OFFSET;

		float[][] corners = switch (face) {
			case UP -> new float[][] {
				{u0, 1 + e, v0}, {u0, 1 + e, v1}, {u1, 1 + e, v1}, {u1, 1 + e, v0}
			};
			case DOWN -> new float[][] {
				{u0, -e, v0}, {u1, -e, v0}, {u1, -e, v1}, {u0, -e, v1}
			};
			case NORTH -> new float[][] {
				{u0, v0, -e}, {u0, v1, -e}, {u1, v1, -e}, {u1, v0, -e}
			};
			case SOUTH -> new float[][] {
				{u0, v0, 1 + e}, {u1, v0, 1 + e}, {u1, v1, 1 + e}, {u0, v1, 1 + e}
			};
			case EAST -> new float[][] {
				{1 + e, v0, u0}, {1 + e, v1, u0}, {1 + e, v1, u1}, {1 + e, v0, u1}
			};
			case WEST -> new float[][] {
				{-e, v0, u0}, {-e, v0, u1}, {-e, v1, u1}, {-e, v1, u0}
			};
		};

		for (float[] corner : corners) {
			buffer.addVertex(matrix, corner[0], corner[1], corner[2]).setColor(argb);
		}
	}
}
