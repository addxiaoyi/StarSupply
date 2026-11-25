# Survival progression acceptance

This workflow continues the saved `SupplyCook` survival profile in the disposable Folia stage. Its existing mining route and crafting locations belong to that world; it is not a fresh-world bootstrap. The client uses normal movement, digging, furnace inventories and crafting clicks. It sends Minecraft 1.21.11's `player_loaded` acknowledgement after terrain arrives, and waits for server block and inventory packets instead of treating Mineflayer predictions as proof.

On Windows, build StarSupply with Java 21 and `mvnw.cmd verify`, then run:

```powershell
.\tools\acceptance\survival-progression\stage-current.ps1 `
  -Stage C:\path\to\isolated-folia-stage `
  -JavaHome C:\path\to\jdk-21 `
  -MineflayerModule C:\path\to\node_modules\mineflayer `
  -PathfinderModule C:\path\to\node_modules\mineflayer-pathfinder `
  -Profile vanilla
```

The stage must be stopped, bind to `127.0.0.1`, use offline authentication and survival mode, and already contain the acceptance player's saved progress. The wrapper backs up the original StarSupply JAR, stages the current build with a descriptor-only Folia loading override, and verifies every restored plugin hash after shutdown. `vanilla` temporarily removes other JARs and defaults to a 384 MiB heap. `full` keeps the installed plugin set and defaults to 1024 MiB. Use `-Heap` to select a different size. These profiles describe only the chosen test inputs; production descriptors remain unchanged. Gameplay progress, worlds and databases persist between runs.

The runner reserves 512 MiB beyond the selected heap for the separate client and JVM native allocations. On Windows it checks both physical memory and commit headroom before launching Java. Heap OOM, native allocation failures, plugin errors and missing startup milestones prevent a passing result. Both StarCore and StarSupply must finish enabling before the client starts.

The client has a 256 MiB V8 heap limit and bounded path searches. The launcher releases disposed JAR-compression buffers before starting the server and records the managed-memory measurements. If an interrupted launcher left a pending backup manifest, the next launch first restores that exact plugin set. Restoration checks closed listeners, running Folia JVMs and every backup hash before changing files; an independently replaced plugin is preserved and reported. It can also be invoked explicitly with `restore-stage.ps1 -Stage <stage> -ManifestPath <stage>/folia-proof/current-build-.../manifest.json`.

Shutdown first uses authenticated loopback RCON. Folia's console command source can lack a world after a plugin startup failure; RCON still permits normal saving and stopping in that situation. If startup fails before RCON opens, the runner waits up to 60 seconds for that listener without launching the gameplay client. A console fallback remains available, and the final server exit, saved-player milestone and closed ports are still required. Credentials are read from the isolated stage and are not logged.

Each run writes separate server/client logs and a result JSON under the stage's `folia-proof/`. The wrapper writes a backup manifest and preserves the test JAR there. The default iron-pickaxe workflow also reads the stopped server's player NBT: a survival-mode save containing an iron pickaxe is required for success. A client-predicted pickaxe with the original ingredients still in the save fails acceptance. The script resumes directly at crafting when three smelted ingots are already saved.

## Six-resource progression

`six-resources.cjs` continues this same saved player through the phases below. Pass `-ClientScript .\tools\acceptance\survival-progression\six-resources.cjs -ClientArguments @('mining')` to `stage-current.ps1`, replacing `mining` for later phases. These coordinates belong to the existing isolated world; this is not a general-purpose terrain solver. The source is implemented, but all six phases have not passed live acceptance.

| Phase | Normal survival actions | Required stopped-player inventory |
| --- | --- | --- |
| `mining` | Mine coal and iron, smelt ingots, ascend, collect sand, smelt glass and craft bottles | 6 iron ingots, 3 glass bottles |
| `surface` | Harvest birch, craft planks, sticks, sword and campfires | 3 campfires, 1 stone sword |
| `farming` | Harvest sugar cane, till hydrated soil, plant/replant wheat, use existing bone meal and wait for growth, craft bread | 6 bread, 3 sugar cane |
| `honey` | Walk to naturally full nests, place smoke-producing campfires and fill bottles | 3 honey bottles |
| `scaffolding` | Harvest bamboo and mineshaft cobweb string, craft scaffolding | 6 scaffolding |
| `fireworks` | Craft paper, collect gunpowder from naturally generated dungeon chests, craft rockets | 3 firework rockets |

Use `-ClientTimeoutMs 2100000` for farming, which may wait for natural crop growth. Each phase stops normally and requires its output in survival-mode NBT before reporting a pass. Inventory checkpoints establish possession; the action logs and previous saved checkpoints establish acquisition. They do not themselves prove subsequent purchase, transport or frontline consumption. Mining and walking remain bounded and stop on unsafe terrain, missing materials or unresolved server confirmation. No phase grants items or teleports a player.

Explicit digging waits for real single- or multi-block server packets, including negative chunk coordinates; local Mineflayer prediction is insufficient. Craft output requires server window packets. Script hashes are captured before the gameplay client launches. `inspect-world.cjs <world> <mineflayerModule> [x y z]` provides a read-only survey of a stopped acceptance world, including natural resources and unopened loot containers; survey results only guide navigation and are not acquisition evidence.

Run the acceptance-tool regression tests without starting a server:

```powershell
node --test tools/acceptance/survival-progression/runtime-health.test.cjs tools/acceptance/survival-progression/memory-preflight.test.cjs tools/acceptance/survival-progression/survival-checkpoint.test.cjs tools/acceptance/survival-progression/rcon-stop.test.cjs tools/acceptance/survival-progression/server-dig.test.cjs
.\tools\acceptance\survival-progression\restore-stage.test.ps1
```

These checks do not establish six-resource survival production, a complete Folia plugin combination, graphical resource-pack rendering, or world-unload recovery. Current runtime scope and artifact hashes are recorded in `docs/FOLIA.md` and the linked evidence.
