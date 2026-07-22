# LiteDots

Client-side [Fabric](https://fabricmc.net/) mod for Minecraft **26.2** that paints persistent, single-texel (1/16 × 1/16) colored dots onto block faces. Look at a block, run `/add-dot`, and the exact pixel you were aiming at gets marked — permanently.

## Features

- **Per-world persistence, Hypixel-friendly.** Dots are keyed by the *server-sent hashed seed + dimension*, not the server address. Identical maps share the same key, so dots placed in one Hypixel lobby reappear in every other copy of that lobby — and across reconnects, world switches (respawn-based server transfers are handled), and game restarts.
- **Pixel-precise.** A dot covers exactly one texel of the targeted face, derived from the raycast hit position. Duplicate placements on the same texel replace the old dot.
- **Depth-tested rendering.** Dots look painted onto the block — terrain in front occludes them; no x-ray.
- **16 dye colors + arbitrary hex.** Optional color argument on placement.
- **Styled chat.** Every message carries hover details (position, face, texel, color swatch, world key) and a click-to-delete button, built with [Adventure MiniMessage](https://docs.advntr.dev/minimessage/index.html) (bundled — no extra install).
- **Fully client-side.** Works in singleplayer and on any server; nothing is sent to or required from the server.

## Commands

| Command | Effect |
|---|---|
| `/add-dot [color]` | Dot on the block face you're looking at (100-block reach) |
| `/add-dot <facing> [color]` | Same ray, but force the dot onto face `<facing>` of the hit block |
| `/add-dot <facing> <yaw> <pitch> [color]` | Cast the ray along the given yaw/pitch instead of your look direction |
| `/remove-dot` | Remove all dots on the face you're looking at |
| `/remove-dot <x> <y> <z> <face> <u> <v>` | Remove exactly one dot (what the chat **[Delete]** buttons run) |
| `/clear-dots` | Remove every dot for the current world |
| `/list-dots` | Count + world key, plus up to 10 entries with hover details and per-entry delete buttons |

- `facing` ∈ `north | south | east | west | up | down | auto` (`auto` = use the face the ray hits).
- `color` is optional everywhere it appears: one of the 16 Minecraft dye names (`white`, `orange`, `magenta`, `light_blue`, `yellow`, `lime`, `pink`, `gray`, `light_gray`, `cyan`, `purple`, `blue`, `brown`, `green`, `red`, `black`, case-insensitive) **or** a 6-digit hex `RRGGBB` — *without* a leading `#` (chat arguments can't contain one), e.g. `/add-dot ff8800`. Omitted → red (`#FF2020`).

## Configuration

Dots are stored in `config/litedots.json` (auto-created, saved atomically on every change):

```json
{
  "worlds": {
    "1c9e33ab52f00d47:minecraft:overworld": [
      { "x": 1, "y": 64, "z": -3, "face": "north", "u": 5, "v": 9, "color": "#FF2020" }
    ]
  }
}
```

The world key is `<16-hex hashed seed>:<dimension id>`. `u`/`v` are texel coordinates (0–15) on the face. A corrupt file is backed up and replaced rather than crashing the game.

## Requirements

- Minecraft **26.2**
- Fabric Loader **≥ 0.19.3**
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**

## Building from source

```sh
./gradlew build
```

The mod jar lands in `build/libs/`. `./gradlew runClient` launches a dev client.

## License

[CC0 1.0](LICENSE) — public domain dedication.
