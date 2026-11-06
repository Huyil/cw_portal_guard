# cw_portal_guard

**English** | [简体中文](README.zh-CN.md)

> Runtime fixes for Cube Warper. A single jar covers both server and client.
> Forge 1.20.1 / Immersive Portals 3.0.7.

| | Fix | Side |
|:--|:--|:--|
| 1 | `Missing Dimension <id>` kick caused by a missing entry in the Immersive Portals dimension-id table | Server + single-player |
| 2 | `ChunkRenderDispatcher` null-pointer crash on the first look through a portal into an unloaded dimension | Client |
| 3 | Configured + JEI config-menu crash | Client |
| 4 | Botania progress-pie HUD leaving chunk rendering blank | Client |
| 5 | Create × CobbleGen: cobble-gen optimisation silently disabled in custom dimensions | Server + single-player |

Root causes, implementation and measurements:
**[docs/TECHNICAL.md](docs/TECHNICAL.md)** ([中文](docs/TECHNICAL.zh-CN.md)).

---

## Installation

Drop `cw_portal_guard-<version>.jar` from
[Releases](https://github.com/Huyil/cw_portal_guard/releases) into `mods/`:

- **Server**: fixes 1 and 5;
- **Client**: fixes 2, 3 and 4, plus fixes 1 and 5 inside single-player worlds;
- The same jar serves both sides; each side loads the fixes that apply to it.

<details>
<summary>Dependencies</summary>

- Required: Forge 47.x, Minecraft 1.20.1, Immersive Portals 3.x
- Enabled automatically when present: Create 6.x + CobbleGen 5.x (fix 5), Configured + JEI (fix 3)
- When an optional mod is absent, the corresponding code path is skipped

</details>

## Configuration

`config/cw_portal_guard.properties` is generated on first launch with every switch enabled.

| Switch | Default | Read at | Effect |
|:--|:--|:--|:--|
| `portal_sync_prewarm` | `true` | every frame | Fix 2: pre-create the dimension when a portal destination syncs |
| `tick_prewarm` | `true` | every frame | Fix 2: per-frame prewarm |
| `renderer_heal` | `true` | every frame | Fix 2: reinitialise half-initialised renderers |
| `render_guard` | `true` | every frame | Fix 2: skip terrain setup as a last resort |
| `configured_jei_filter` | `true` | every frame | Fix 3: filter entries whose name lookup throws |
| `configured_list_guard` | `true` | every frame | Fix 3: null-safe tick/render for half-initialised screens |
| `gl_state_reset` | `true` | every frame | Fix 4: reset GL state before world rendering |
| `dimension_id_guard` | `true` | world start | Fix 1: complete the IP dimension-id table |
| `cobblegen_compat` | `true` | world start | Fix 5: align Create's simulated output with CobbleGen |
| `log_messages` | `true` | every frame | Print this mod's log lines (command output is always shown) |
| `log_language` | *(empty)* | every frame | Language for logs and command output: `en_us`, `zh_cn`, or empty to follow the JVM locale |

> [!NOTE]
> Message text lives in `assets/cw_portal_guard/lang/*.json`. Setting `log_messages=false`
> silences this mod's log lines; `/cwguard` output stays available.
>
> The two switches marked **world start** are read once in `ServerStartedEvent`.
> Changes to them take effect after re-entering the world or restarting the server.
> The remaining switches are read live in their respective events.

## Commands

`/cwguard` requires **permission level 2** (server operator; in single-player, cheats enabled).

| Command | Available on | Description |
|:--|:--|:--|
| `/cwguard status` | both sides | Current switches and dimension-id repair result |
| `/cwguard idtable` | both sides | Lists loaded dimensions that have no id; empty means healthy |
| `/cwguard dims` | client | Lists client-loaded dimensions |
| `/cwguard repro <dimension\|auto> [radius]` | client (single-player) | Retargets the nearest portal to reproduce fix 2 |

`/cwguard idtable` doubles as a regression check for fix 1: any output means players entering
that dimension will be kicked.

## Building

```bash
bash build.sh      # produces dist/cw_portal_guard-<version>.jar
```

The build runs `javac` and `jar` offline. The classpath is taken from a local client
installation; each path can be overridden:

```bash
MC=/path/to/.minecraft MODS=/path/to/instance/mods JDK=/path/to/jdk17 bash build.sh
```

## Verification

Startup log example:

```
[cw_portal_guard] loaded (v1.4.0) side=DEDICATED_SERVER
[cw_portal_guard] ServerStartedEvent: 90 -> 91 entries
[cw_portal_guard] + added dimension id: ae2:spatial_storage -> 89
[cw_portal_guard] cobblegen compat installed: own interaction inserted at lava list head
```

Per-fix A/B measurements are in **[docs/TECHNICAL.md](docs/TECHNICAL.md)**.

## License

[MIT](LICENSE) — usable in any modpack and on any server, including commercial use.
Modification and redistribution require the copyright and licence notices to be retained.
