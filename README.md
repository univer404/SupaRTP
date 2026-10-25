# SupaRTP

A configurable random-teleport plugin for Paper/Purpur with three destinations: Overworld, Nether, and The End.

## Commands
- `/rtp` — opens the configurable GUI.
- `/rtp overworld`, `/rtp nether`, `/rtp end` — starts a teleport countdown directly.
- `/wild` — alias for `/rtp`.
- `/rtp reload` — reloads plugin configuration (requires `supar.tp.admin`).

## Configuration
- `config.yml` — world names, cooldown, countdown movement threshold, radius, and safe-location search.
- `gui.yml` — GUI title, size, filler, item slots, materials, names, and lore.
- `messages.yml` — messages and MiniMessage RGB/hex formatting.

The configured worlds must already be loaded by the server. Default world names are `world`, `world_nether`, and `world_the_end`.

## Build
Requires Java 21 and Maven. Run `mvn clean package`. The output is `target/SupaRTP-1.0.0.jar`. GitHub Actions also uploads the jar as a workflow artifact.
