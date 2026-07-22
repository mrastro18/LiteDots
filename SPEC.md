# LiteDots — Architecture Spec (v1)

Client-side Fabric mod for Minecraft **26.2**. Lets the player paint a single-pixel "dot" onto a block face they are looking at. Dots are rendered as a colored overlay covering exactly one texel (1/16 × 1/16) of the targeted face, persist to a JSON config, and are keyed per-world by the server-sent hashed seed + dimension so they reappear on identical maps (e.g. across Hypixel's duplicated lobby/game servers).

---

## 1. Toolchain (pinned — do not re-research)

| Component | Value |
|---|---|
| Minecraft | `26.2` (unobfuscated — use **official Mojang class names**; Yarn does not exist for 26.x) |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.155.2+26.2` |
| Loom plugin | id `net.fabricmc.fabric-loom`, version `1.17.16` (new no-remap plugin; **no `mappings` block**, plain `implementation` deps, plain `jar` task) |
| Gradle wrapper | `9.5.1` |
| Java | 25 (local JDK: Temurin 25.0.3 at `/Users/santos/Library/Java/JavaVirtualMachines/temurin-25.0.3/Contents/Home`) |

Scaffold from the `26.2` branch of https://github.com/FabricMC/fabric-example-mod (NOT the default branch, which is `26.1.2`; there is no `main` branch).

## 2. Mod identity

- mod id: `litedots`, name: `LiteDots`, version `1.0.0`
- package root: `dev.santos.litedots`
- `fabric.mod.json`: `"environment": "client"`, only a `client` entrypoint, client mixin config `litedots.client.mixins.json`.
- Keep the template's `splitEnvironmentSourceSets()`; all code lives in `src/client/java`.

## 3. Features

### 3.1 Command grammar (client commands, `fabric-command-api-v2`)

Register via `ClientCommandRegistrationCallback.EVENT` using `ClientCommands.literal/argument` (note: the old `ClientCommandManager` helper no longer exists; helpers live on `ClientCommands`). Feedback via `source.sendFeedback(Component.literal(...))`, errors via `sendError`.

```
/add-dot [color]                          → dot where the player is looking (ray from eyes along current look)
/add-dot <facing> [color]                 → same ray, but force the dot onto face <facing> of the hit block
/add-dot <facing> <yaw> <pitch> [color]   → ray from player eyes along the given yaw/pitch; dot on <facing>
/remove-dot                               → remove all dots on the block face the player is looking at
/remove-dot <x> <y> <z> <face> <u> <v>    → remove exactly that dot (int,int,int,word,int 0-15,int 0-15); plumbing for the chat [Delete] buttons (§3.6)
/clear-dots                               → remove every dot for the current world key (with count feedback)
/list-dots                                → feedback: dot count + world key, plus up to 10 per-dot entries with hover lore and [Delete] buttons (§3.6)
```

- `facing` ∈ `north|south|east|west|up|down|auto` (word argument + suggestions; `auto` = use the raycast hit face). This keeps the grammar a single unambiguous chain `facing → yaw → pitch → color`, matching `/add-dot <facing, yaw, pitch, color>` with every suffix optional (plus the facing-less `/add-dot <color>` form — facing words and color names are disjoint, so one word argument disambiguates at execute time).
- `color` (optional, all three forms): one of Minecraft's 16 dye color names (`white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black`, case-insensitive, suggestions offered; RGB from `DyeColor.getTextureDiffuseColor()`), or a 6-digit hex `RRGGBB` **without** a leading `#` (brigadier word arguments only allow `[0-9A-Za-z_\-.+]`, so `#` cannot be typed). Name match is tried first, then hex. Alpha is always forced opaque. Omitted → default `0xFFFF2020`. Unknown color → clean error listing the options.
- yaw/pitch: float arguments, Minecraft convention (yaw degrees, pitch −90..90).
- Raycast: from the player's eye position, distance **100** blocks, blocks only (ignore fluids), `ClipContext` with OUTLINE shape / NONE fluid. If nothing hit → `sendError("Not looking at a block")`.
- If forced `facing` differs from the hit face: intersect the same ray with the plane of the forced face of the hit block, clamp the in-plane coordinates to the block bounds, derive the pixel from that intersection.

### 3.2 Pixel (texel) math

From `BlockHitResult`: `getBlockPos()`, `getDirection()`, `getLocation()`. Let `rel = hitPos − blockPos` (components in 0..1). Face → (u,v) plane:

| Face axis | u | v |
|---|---|---|
| UP / DOWN (Y) | x | z |
| NORTH / SOUTH (Z) | x | y |
| EAST / WEST (X) | z | y |

`u16 = clamp(floor(u*16), 0, 15)`, same for `v16`. A dot = `(BlockPos, Direction face, int u ∈ [0,15], int v ∈ [0,15], int argb)`. Default color: opaque red `0xFFFF2020`. Adding a dot that duplicates an existing `(pos, face, u, v)` replaces it (no duplicates).

### 3.3 World keying (the "world-data" mapping key)

- Capture the **hashed seed** the server sends: mixin into `net.minecraft.client.multiplayer.ClientPacketListener`, `@Inject` at `TAIL` of the handlers for `ClientboundLoginPacket` and `ClientboundRespawnPacket` (respawn matters: Hypixel switches worlds via respawn on the same connection). Read `commonPlayerSpawnInfo().seed()` (a `long`) and the dimension id from the same `CommonPlayerSpawnInfo` (`dimension()` resource key → its `location()` string). **Verify exact method names against the real 26.2 jar in the Gradle cache before writing the mixin** — record components are public, so no accessor mixin is needed.
- World key string: `"%016x:%s".formatted(hashedSeed, dimensionId)` e.g. `"1c9e...:minecraft:overworld"`.
- Deliberately does NOT include server address — identical maps on different Hypixel servers share hashed seed + dimension, so dots transfer. Also works in singleplayer (hashed seed is sent there too).
- Singleton `WorldKeyTracker` holds the current key; command/render code no-ops with a clear error if no key yet.

### 3.4 Persistence

- Gson (bundled with Minecraft), file `FabricLoader.getInstance().getConfigDir().resolve("litedots.json")`.
- Shape:
```json
{
  "worlds": {
    "<worldKey>": [
      { "x": 1, "y": 64, "z": -3, "face": "north", "u": 5, "v": 9, "color": "#FF2020" }
    ]
  }
}
```
- Load once at client init (tolerate missing/corrupt file → start empty, log a warning, back up the corrupt file). Save on every mutation, atomically (write `.tmp`, then `Files.move` with `ATOMIC_MOVE`/`REPLACE_EXISTING` fallback).
- In-memory: `Map<String, List<Dot>>` behind a `DotStore` singleton with `add/removeFace/clearWorld/forWorld` operations.

### 3.5 Rendering

Fabric `fabric-rendering-v1` was rewritten for 26.x: `WorldRenderEvents` is now `LevelRenderEvents` (package `net.fabricmc.fabric.api.client.rendering.v1.level`) with a deferred **submit-node** model (`SubmitNodeCollector` / `submitCustom(...)` with a `SubmitRenderPhase` from `SubmitRenderPhases` — e.g. `GIZMOS`), and `PoseStack` instead of `MatrixStack`. Camera position lives in extraction/level state, not a flat `camera()` method. **The exact call sequence must be proven by the rendering spike (RENDER_NOTES.md) before implementation** — do not guess it.

Requirements regardless of mechanism:
- For every dot in the current world key within 128 blocks of the camera: draw a quad covering `[u/16, (u+1)/16] × [v/16, (v+1)/16]` on the dot's face, offset **0.002** outward along the face normal (z-fighting), camera-relative coordinates.
- Depth-tested (dots are occluded by terrain in front — they should look painted on, not x-ray). Solid color, no texture.
- Skip rendering entirely when no world key or no dots.

### 3.6 Chat styling (MiniMessage)

All command feedback is styled via Adventure MiniMessage (`net.kyori:adventure-text-minimessage`, bundled jar-in-jar with every runtime transitive `include`d explicitly — Loom's `include` does not pull transitives). Bridge to vanilla text: `MiniMessage.deserialize` → `GsonComponentSerializer.gson().serializeToTree` → `ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, …)`. Adventure ≥4.23's default gson serializer emits the modern 1.21.5+ JSON schema (snake_case `click_event`/`hover_event`, action-specific fields like `command`), which is exactly what 26.2's codec decodes — hover/click survive the bridge intact (proven by a boot-time self-test that logs the re-encoded vanilla JSON). Implemented in `chat/Messages.java`.

- Palette (catppuccin-mocha-ish): prefix gradient `#a6e3a1→#94e2d5`, muted gray `#6c7086`, body `#bac2de`, accent green `#a6e3a1`, error red `#f38ba8`, white for emphasized values.
- Every message starts with the prefix `LiteDots » ` (gradient name, gray »). Errors render their body in error red.
- Add success: `New dot saved ✔ [Delete]` — "dot" underlined with a multi-line hover lore (position/face/texel/color swatch `■` tinted the dot's actual color/shortened world key `xxxxxxxx…:dimension`); ✔ in accent green; `[Delete]` underlined error-red.
- `[Delete]` buttons are `run_command` click events executing the precise `/remove-dot <x> <y> <z> <face> <u> <v>` form. Clicked components route through `ClientPacketListener.sendUnattendedCommand`, which Fabric's client command API intercepts (`attended() == false`); no handler gates on attended, so the buttons work without a confirmation screen.
- `/list-dots` emits one multi-line message: styled count header, then up to 10 entries (`• (x,y,z) face (u,v) ■` with the same hover lore + per-entry [Delete]), then `…and N more` if applicable.

## 4. File map (target)

```
src/client/java/dev/santos/litedots/
  LiteDotsClient.java          (client entrypoint: load store, register commands + render hook)
  chat/Messages.java           (MiniMessage styling + Adventure→vanilla component bridge, §3.6)
  command/DotCommands.java
  dot/Dot.java                 (record)
  dot/DotStore.java            (in-memory store + Gson persistence)
  world/WorldKeyTracker.java
  render/DotRenderer.java
  mixin/ClientPacketListenerMixin.java
src/client/resources/litedots.client.mixins.json
src/main/resources/fabric.mod.json
```

## 5. Definition of done

1. `./gradlew build` succeeds (JAVA_HOME = Temurin 25.0.3 path above).
2. All commands registered and functional per grammar; pixel math correct per §3.2.
3. Dots persist across restarts; keyed per §3.3; file shape per §3.4.
4. Rendering verified per §3.5 spike notes; no compile-time use of removed pre-26.x APIs (`WorldRenderEvents`, `ClientCommandManager`, `MatrixStack`, yarn names).
5. `./gradlew runClient` boots to the title screen without crash and with the mixin applied cleanly (check logs).
