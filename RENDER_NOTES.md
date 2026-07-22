# LiteDots Rendering Spike — Findings

This document is the proven contract for SPEC.md §3.5 (rendering). Everything below was verified
by reading the actual decompiled Minecraft 26.2 sources and the Fabric API 25.3.1+6988455e9e
(bundled by `fabric-api-0.155.2+26.2`, per SPEC) sources, and by compiling real code against the
real jars (`src/client/java/dev/santos/litedots/render/DotRenderer.java`, wired into
`LiteDotsClient`, `./gradlew build` green).

Sources used:
- `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` (unobfuscated, official names) — `javap -p`
- Decompiled MC sources via `./gradlew genClientOnlySources` / `genCommonSources` (Vineflower,
  the default decompiler for this loom version). Output lands in the **project-local**
  `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-{clientOnly,common}-<hash>/26.2/*-sources.jar`
  — NOT under the global `~/.gradle/caches/fabric-loom`. That global cache only has the raw jars.
- `fabric-rendering-v1-25.3.1+6988455e9e-sources.jar`, fetched directly from
  `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-rendering-v1/25.3.1+6988455e9e/...`.
  Note: the top-level `fabric-api-0.155.2+26.2.jar`/`-sources.jar` is just an aggregator POM with no
  code of its own (only `META-INF`/`assets`); the actual rendering code lives in the
  `fabric-rendering-v1` submodule jar, resolved transitively, whose own sources jar has to be
  fetched separately (it wasn't pulled automatically by Loom).

## 1. The event: `LevelRenderEvents.COLLECT_SUBMITS`

```
package net.fabricmc.fabric.api.client.rendering.v1.level;
public final class LevelRenderEvents {
    public static final Event<CollectSubmits> COLLECT_SUBMITS = ...;
    @FunctionalInterface
    public interface CollectSubmits {
        void collectSubmits(LevelRenderContext context);
    }
}
```

Fired from a mixin into `LevelRenderer` (`LevelRendererMixin`) at `@Inject(method =
"submitFeatures", at = @At("RETURN"))`, i.e. **after** terrain/entity/block-entity/particle submits
are queued and **before** any submit geometry is actually drawn. Its own javadoc says exactly what
we need: "Use this event to add additional submits to `LevelRenderContext#submitNodeCollector()`."
This is the correct phase for adding custom world geometry — confirmed both by the doc comment and
by the fact vanilla's own block-outline submission (`submitBlockOutline`) happens later in the same
method, at the same pose-stack/camera setup we replicate below.

Registration (in `DotRenderer.init()`, called once from `LiteDotsClient.onInitializeClient()`):

```java
LevelRenderEvents.COLLECT_SUBMITS.register(DotRenderer::onCollectSubmits);
```

`Event<T>.register(T listener)` is the standard `fabric-api-base` pattern (verified in
`net.fabricmc.fabric.api.event.Event`).

## 2. Context accessors

```
package net.fabricmc.fabric.api.client.rendering.v1.level;

public interface AbstractLevelRenderContext {
    GameRenderer gameRenderer();
    LevelRenderer levelRenderer();
    LevelRenderState levelState();       // net.minecraft.client.renderer.state.level.LevelRenderState
}

public interface LevelTerrainRenderContext extends AbstractLevelRenderContext {
    @Nullable ChunkSectionsToRender sectionsToRender();
}

public interface LevelRenderContext extends LevelTerrainRenderContext {
    SubmitNodeCollector submitNodeCollector();   // net.minecraft.client.renderer.SubmitNodeCollector
    PoseStack poseStack();                       // com.mojang.blaze3d.vertex.PoseStack, may be null
}
```

- `submitNodeCollector()` — where you submit geometry.
- `poseStack()` — the *current* pose stack for this submit call. It's set via a
  `@ModifyExpressionValue` mixin hook on the `new PoseStack()` inside `submitFeatures`, which runs
  before `COLLECT_SUBMITS` fires, so by the time our callback runs it's non-null in practice (we
  still null-check defensively since the interface marks it `@Nullable`).
- **Camera position does NOT come from a `camera()` method on `LevelRenderContext`** (that only
  exists on the separate `LevelExtractionContext`, used during the earlier extraction phase, e.g.
  `LevelExtractionEvents.END_EXTRACTION`). For the *drawing* phase, camera position is:

  ```java
  Vec3 cameraPos = context.levelState().cameraRenderState.pos;
  ```

  `LevelRenderState.cameraRenderState` is a public field of type
  `net.minecraft.client.renderer.state.level.CameraRenderState`, which itself has a public field
  `pos` of type `net.minecraft.world.phys.Vec3`. This is exactly what vanilla's own
  `LevelRenderer.submitBlockOutline` reads: `Vec3 cameraPos = levelRenderState.cameraRenderState.pos;`
  — confirmed by decompiling `LevelRenderer.java`.

## 3. Submitting geometry: `submitCustomGeometry`, not `submitCustom`

This is the biggest surprise of the spike. SPEC/the task brief assumed the path would be Fabric's
`submitCustom(SubmitRenderPhase<T>, T node)` extension:

```
package net.fabricmc.fabric.api.client.rendering.v1;
public interface FabricOrderedSubmitNodeCollector {
    default <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) { ... }
}
```

That mechanism is real, but it requires defining your own `SubmitNode` implementation *and* a
`FeatureRendererType` registered through `FeatureRendererRegistry` — it's built for persistent,
entity-like custom renderers with their own draw/sort lifecycle, not a one-off list of static
colored quads.

Vanilla itself has a much lighter escape hatch for exactly our use case, declared directly on the
vanilla interface `SubmitNodeCollector` extends (`OrderedSubmitNodeCollector`):

```
package net.minecraft.client.renderer;
public interface OrderedSubmitNodeCollector {
    void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer);

    interface CustomGeometryRenderer {
        void render(PoseStack.Pose pose, VertexConsumer buffer);
    }
}
```

Proof this is the idiomatic vanilla path for simple colored geometry: `LightningBoltRenderer`
(`net.minecraft.client.renderer.entity.LightningBoltRenderer`) draws its translucent colored quads
with exactly this call:

```java
submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
    Matrix4fc poseMatrix = pose.pose();
    buffer.addVertex(poseMatrix, x, y, z).setColor(r, g, b, a);
    ...
});
```

`submitCustomGeometry` auto-buckets into the right internal phase based on properties of the
`RenderType` you pass (verified in decompiled `SubmitNodeCollection.submitCustomGeometry`):

```java
if (renderType.isOutline())      this.outline.submit(submit);
else if (renderType.hasBlending()) this.translucentCustomGeometry.submit(submit);
else                              this.solid.submit(submit);
```

So **you never manually pick a `SubmitRenderPhase` for this path** — the phase (`solid` /
`translucentCustomGeometry` / `outline`, i.e. `SubmitRenderPhases.SOLID` /
`TRANSLUCENT_CUSTOM_GEOMETRY` / `OUTLINE`) falls out of the `RenderType`'s own blend/outline state.
`SubmitRenderPhase`/`SubmitRenderPhases`/`submitCustom` only matter if you go the heavier
custom-`SubmitNode`-type route, which we don't need.

## 4. Which `RenderType` gives depth-tested solid color

`RenderTypes.debugQuads()` (`net.minecraft.client.renderer.rendertype.RenderTypes`), backed by
pipeline `RenderPipelines.DEBUG_QUADS`, built from the shared `DEBUG_FILLED_SNIPPET`:

```java
RenderPipeline.builder(GLOBALS_SNIPPET)
    .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
    .withVertexShader("core/position_color")
    .withFragmentShader("core/position_color")
    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
    .withPrimitiveTopology(PrimitiveTopology.QUADS)
    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
    .withCull(false)
```

- **Depth test: on** (`CompareOp.GREATER_THAN_OR_EQUAL` — MC's depth buffer is reversed-Z, so this
  *is* a real "closer wins" test against existing scene depth; terrain/entities in front of a dot
  will correctly occlude it).
- **Depth write: off** (the `false` in `DepthStencilState(cmp, writeEnabled)`). Fine for painted-on
  decals — dots don't need to occlude each other or write depth, they just need to respect what's
  already in the depth buffer.
- **Format: `DefaultVertexFormat.POSITION_COLOR`** — position + packed color, no UV, i.e. solid
  color with no texture, exactly per SPEC §3.5.
  **Topology: `QUADS`** — feed exactly 4 vertices per quad, in order (matches our 1-quad use case
  directly; no need to split into two triangles).
- **Blend: `BlendFunction.TRANSLUCENT`** — this is why `submitCustomGeometry` buckets it into
  `translucentCustomGeometry` rather than `solid`, but that only affects *sort order*, not whether
  the depth test runs. Since our dot color is fully opaque (`0xFFFF2020`, alpha 0xFF), blending is a
  no-op in practice.
- **Cull: off** — quad visible regardless of winding order (matters because we don't bother
  computing an outward-facing winding per block face for this spike; the real feature should still
  pick a consistent winding per face for correctness/robustness, cull-off is just insurance).

`RenderTypes.debugFilledBox()` is pipeline-identical except it doesn't re-assert `withCull(false)`
(redundant — `DEBUG_FILLED_SNIPPET` already sets it) and represents the same guarantees; either
works. `debugQuads()` was chosen since our shape *is* a quad.

## 5. Vertex emission

```
package com.mojang.blaze3d.vertex;
public interface VertexConsumer {
    VertexConsumer addVertex(float, float, float);
    default VertexConsumer addVertex(PoseStack.Pose, float, float, float);
    default VertexConsumer addVertex(Matrix4fc, float, float, float);
    VertexConsumer setColor(int r, int g, int b, int a);
    VertexConsumer setColor(int argb);          // <-- used: 0xAARRGGBB, matches SPEC's dot color format
    ...
}
```

`setColor(int)` takes a standard `0xAARRGGBB` packed int — verified in
`BufferBuilder.setColor(int)` → `putRgba` → `ARGB.toABGR(argb)` (the ABGR/byte-order conversion for
the GPU buffer happens internally; callers always use ARGB order). So SPEC's `0xFFFF2020` default
dot color can be passed straight to `setColor(int)` with no conversion.

Emission pattern used (mirrors `LightningBoltRenderer` exactly):

```java
context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
    var matrix = pose.pose(); // org.joml.Matrix4f, implements Matrix4fc
    buffer.addVertex(matrix, x0, y0, z0).setColor(argb);
    buffer.addVertex(matrix, x1, y1, z1).setColor(argb);
    buffer.addVertex(matrix, x2, y2, z2).setColor(argb);
    buffer.addVertex(matrix, x3, y3, z3).setColor(argb);
});
```

The callback receives `pose.pose()` (a `Matrix4fc` snapshot), not the live `PoseStack` — so all
push/translate calls on the `PoseStack` **must happen before** `submitCustomGeometry` is called;
the pose is captured immediately at submit time (`SubmitNodeCollection.submitCustomGeometry` does
`poseStack.last().copy()` synchronously), not lazily at draw time.

## 6. Camera-relative math (the proven pattern)

Directly copied from vanilla's `LevelRenderer.submitBlockOutline`, which does exactly this
push/translate/submit/pop dance:

```java
Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
poseStack.pushPose();
poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
// ... submit calls using poseStack here, using LOCAL (block-relative) vertex coordinates ...
poseStack.popPose();
```

`DotRenderer.onCollectSubmits` follows this exactly: translate the pose stack by
`(blockX - cameraPos.x, blockY - cameraPos.y, blockZ - cameraPos.z)`, then emit vertices in
block-local space (`0..1/16` for our test texel, `+0.002` added to the face-normal axis to avoid
z-fighting per SPEC §3.5), then pop.

## 7. Full proven call sequence (as implemented in `DotRenderer.java`)

1. `LiteDotsClient.onInitializeClient()` → `DotRenderer.init()`
2. `DotRenderer.init()` → `LevelRenderEvents.COLLECT_SUBMITS.register(DotRenderer::onCollectSubmits)`
3. Each frame, `onCollectSubmits(LevelRenderContext context)`:
   - `PoseStack poseStack = context.poseStack();` (null-checked)
   - `Vec3 cameraPos = context.levelState().cameraRenderState.pos;`
   - `poseStack.pushPose(); poseStack.translate(blockX - cameraPos.x, worldY - cameraPos.y, blockZ - cameraPos.z);`
   - `context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> { buffer.addVertex(pose.pose(), lx, ly, lz).setColor(argb); ... 4 vertices ... });`
   - `poseStack.popPose();`

This compiles and links against the real 26.2 + Fabric API 0.155.2+26.2 jars
(`./gradlew build` → `BUILD SUCCESSFUL`, including a full `clean build`).

## 8. Not used, but investigated: the Gizmos system

26.x has a genuine new "gizmos" debug-primitive system:
`net.minecraft.gizmos.GizmoPrimitives` (interface with `addPoint`/`addLine`/`addTriangleFan`/
`addQuad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color)`), implemented by
`net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives`, submitted via
`SubmitNodeCollector.submitGizmoPrimitives(...)` and rendered through
`SubmitRenderPhases.GIZMOS` (`LevelRenderEvents.BEFORE_GIZMOS` is the fabric hook point for adding
to it). `addQuad` takes **world-space absolute `Vec3` corners** (camera-relative translation is
handled internally by the gizmo renderer), which would have been a legitimate simpler fallback if
`submitCustomGeometry` hadn't panned out — SPEC explicitly names this as the fallback path. It
wasn't needed: `submitCustomGeometry` gives full control over vertex format/depth-state via
`RenderType` and needs no extra plumbing, so gizmos add no value here (mentioned for completeness).

## 9. Other surprises / renames worth flagging to the feature-implementation agent

- **`ResourceKey<T>` has no `location()` method in 26.2.** SPEC §3.3 says "the dimension id from
  the same `CommonPlayerSpawnInfo` (`dimension()` resource key → its `location()` string)" — that
  accessor is now named **`identifier()`**, returning `net.minecraft.resources.Identifier`
  (renamed from the old `ResourceLocation`). So the world-key code should be:
  `spawnInfo.dimension().identifier().toString()` (or `.getNamespace()`/`.getPath()` if building
  the string manually), not `.location()`.
- `archivesBaseName = "..."` on the root `Project` no longer works under Gradle 9 (`Could not set
  unknown property 'archivesBaseName'`). Use the `base` extension instead:
  `base { archivesName = project.archives_base_name }`.
- Decompiled Minecraft sources from `genClientOnlySources`/`genCommonSources` land in the
  **project-local** `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-{clientOnly,common}-<hash>/26.2/*-sources.jar`,
  not the global `~/.gradle/caches/fabric-loom` (which only holds raw/merged jars, decompile
  cache blobs, and the asset/version-manifest cache). Needed both `genClientOnlySources` (for
  `LevelRenderer`, `ClientPacketListener`) and `genCommonSources` (for `CommonPlayerSpawnInfo`,
  which lives in the shared client/server module, not the client-only one).
- The top-level `fabric-api` artifact is a metadata-only aggregator; its `-sources.jar` contains no
  Java at all. Real submodule sources (e.g. `fabric-rendering-v1`) must be resolved/fetched
  separately per submodule+version (found via that submodule's own POM/maven coordinates).

## 10. Mixin targets (SPEC §3.3, verified against 26.2 jar)

Class: `net.minecraft.client.multiplayer.ClientPacketListener`

| Packet | Handler method | Signature |
|---|---|---|
| `ClientboundLoginPacket` | `handleLogin` | `public void handleLogin(net.minecraft.network.protocol.game.ClientboundLoginPacket packet)` |
| `ClientboundRespawnPacket` | `handleRespawn` | `public void handleRespawn(net.minecraft.network.protocol.game.ClientboundRespawnPacket packet)` |

Both methods call `packet.commonPlayerSpawnInfo()` to obtain a
`net.minecraft.network.protocol.game.CommonPlayerSpawnInfo` — confirmed as a `public record` with
public accessors, so no accessor mixin is needed:

```java
public record CommonPlayerSpawnInfo(
    Holder<DimensionType> dimensionType,
    ResourceKey<Level> dimension,
    long seed,
    GameType gameType,
    @Nullable GameType previousGameType,
    boolean isDebug,
    boolean isFlat,
    Optional<GlobalPos> lastDeathLocation,
    int portalCooldown,
    int seaLevel
)
```

- Hashed seed: `spawnInfo.seed()` → `long`.
- Dimension id: `spawnInfo.dimension()` → `ResourceKey<Level>`, then **`.identifier()`** (not
  `.location()` — see surprise above) → `net.minecraft.resources.Identifier`, whose `toString()`
  gives the `"namespace:path"` form (e.g. `"minecraft:overworld"`) SPEC's world-key format wants.

Recommended mixin shape (for the feature-implementation agent, not yet implemented in this spike —
scaffold only requires an empty `litedots.client.mixins.json` per Task 1):

```java
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void litedots$onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        CommonPlayerSpawnInfo info = packet.commonPlayerSpawnInfo();
        WorldKeyTracker.update(info.seed(), info.dimension().identifier().toString());
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void litedots$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        CommonPlayerSpawnInfo info = packet.commonPlayerSpawnInfo();
        WorldKeyTracker.update(info.seed(), info.dimension().identifier().toString());
    }
}
```
