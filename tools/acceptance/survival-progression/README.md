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

Placement now also requires a matching server packet for the target position and block type. A local world-cache change cannot pass; late server confirmation may recover a known Mineflayer placement timeout, while disconnects and other failures still stop the phase. All packet listeners are removed on completion or timeout.

Smelting counts the open furnace's player inventory, pending input and finished output before gathering more ingredients. It collects partial batches, adds only missing input and replenishes depleted fuel from real coal or charcoal. Unrelated furnace contents are preserved, and shortages or closed windows fail with a resumable checkpoint. These fixes have automated protocol/fixture coverage; their live Folia validation remains pending.

Run the acceptance-tool regression tests without starting a server:

```powershell
$tests = Get-ChildItem tools/acceptance/survival-progression -Filter '*.test.cjs'
node --max-old-space-size=64 --test --test-concurrency=1 @($tests.FullName)
.\tools\acceptance\survival-progression\restore-stage.test.ps1
```

These checks do not establish six-resource survival production, a complete Folia plugin combination, graphical resource-pack rendering, or world-unload recovery. Current runtime scope and artifact hashes are recorded in `docs/FOLIA.md` and the linked evidence.

## Physical delivery and sustained consumption

After finishing the six production phases, the `logistics` phase uses the same saved player's existing items and normal `/supply buy`, `sell`, `ship`, `accept` and `deliver` commands. It never grants items, teleports, changes game mode or writes server databases. It reads a consistent schema-v11 SQLite snapshot with Node 22.13+ `node:sqlite` in read-only/query-only mode. The default overworld is required; walking must use existing passages because digging and block placement are disabled during this phase.

Copy `logistics-plan.example.json` outside the tracked source and fill in the actual army UUID, dedicated empty depot/front, configured supply interval, quantities, total purchase prices and freight. The player needs the normal nation-management authority for creating orders, enough treasury funds and all six item types in a saved survival inventory. Create the depot/front and road through normal authority beforehand. The bound army must be at war, in service range and have demand small enough for the available batch. Account for winter/siege demand and any partial consumption while the six deliveries finish; the example counts are not a guarantee that three complete cycles will be possible.

```powershell
.\tools\acceptance\survival-progression\stage-current.ps1 `
  -Stage C:\path\to\isolated-folia-stage `
  -JavaHome C:\path\to\jdk-21 `
  -MineflayerModule C:\path\to\node_modules\mineflayer `
  -PathfinderModule C:\path\to\node_modules\mineflayer-pathfinder `
  -Profile vanilla `
  -ClientScript .\tools\acceptance\survival-progression\six-resources.cjs `
  -ClientArguments @('logistics', 'C:\path\to\logistics-plan.json') `
  -ClientTimeoutMs 2100000
```

The driver requires six uniquely paid purchases and shipments, exact seller/carrier/amount/resource identities, matching stored physical lots, and at least three consecutive complete six-resource consumption intervals. Every consumed unit must equal a reduction in these delivered lots; old cycles, other inventories, unrelated lots or changed interval lengths cannot supply the proof. Matching native outbox rows must be `APPLIED`. The parent runner then requires normal shutdown, closed ports, a survival-mode NBT save with exactly the sold quantities removed and another read-only database verification.

Before any command, a version-2 journal is committed under `folia-proof/survival-logistics-<runId>.json`. A complete sibling file is flushed before replacing the old journal; initial creation cannot overwrite an existing run. Interrupted writes or replacement failures preserve the previous order IDs. Windows does not expose directory fsync through Node, so this protects against process interruptions and write failures without claiming power-loss durability.

Rerun the same command and unchanged plan to resume. The driver compares the original warehouse bindings, saved inventory, player and plugin hashes, finds creations missing their responses using the saved pre-command order IDs, and observes recorded actions instead of sending them again. `BOUGHT`, `IN_TRANSIT`, `SETTLING` and `DELIVERED` progress can continue; the final saved inventory debit is always measured from the original pre-run checkpoint. Missing or ambiguous creation results, changed inputs, unrelated orders, expired shipments and `REVIEW` states stop for inspection. A command that was recorded but never reached the server is deliberately not resent: resolve it using normal authority after checking the journal. Version-1 journals lack these baselines and still require manual inspection. Do not delete a journal to bypass recovery checks, and run only one launcher/client for a given stage.

## Production evidence linkage

The current runner archives the before/after player NBT, records the stable world UID, hashes the complete client action log and records source/JAR hashes. Add an optional `productionEvidence` array to the logistics plan containing the ordered stage-relative `folia-proof/...-result.json` paths for all production attempts, including failed attempts with normal saved checkpoints. All six phases must eventually pass, each NBT hash must continue from the preceding checkpoint, and the final checkpoint must be the original logistics starting inventory. Core/Supply binaries and player/world identities must agree. Historical results lacking the new archives/protocol cannot be automatically promoted into this proof.

The linker counts captured harvesting, natural-container collection and recipe/smelting actions. Recipe outputs require traced inputs; lost or used quantities are conservatively capped by recorded inventory. Initial inventory starts with **zero** acquisition credit. It reports `productionEvidenceLinked`, per-resource `tracedInventory` and `unprovenQuantities`; `survivalProductionVerified` is true only when all delivered counts have sufficient linked acquisition evidence. A complete set of phase passes or a full starting backpack alone cannot set that flag. The retained historical mining inventory may therefore still require independently reviewed source evidence or fresh production. Fixed production targets and automatic replenishment remain separate work.

## Core receipt replay after restart

Build the current disposable `../native-supply` fixture and put `SupplyNativeProof-1.0.0.jar` in the stopped isolated stage before the initial logistics run. Set `"nativeReplay": true` in that run's plan. The vanilla launcher retains this fixture only for a plan requesting replay; it is never a production dependency.

After delivery and sustained consumption, the runner queries/retries every selected physical credit through the public Core API. It requires the matching immutable receipt, unchanged native supply/treasury, rejection of conflicting input and a fresh command correlation ID. A queue acknowledgement alone cannot pass. Normal shutdown then records the original settlements, transfers, consumption and outbox rows.

Run the same launcher and plan with `-ClientArguments @('logistics-replay', 'C:\path\to\logistics-plan.json')` for a second server process. This phase starts no gameplay client and issues no purchase or inventory commands. It requires a different probe boot ID, identical receipts, unchanged original economic/consumption rows, unchanged player balance read independently through Core and unchanged saved player NBT, followed by another normal stop. New consumption intervals may advance; the original rows are compared by identity. Treasury values are checked within each synchronous retry; unrelated ongoing national costs are not attributed to the retry. Only then are `coreReceiptReplayVerified` and `processRestartReplayVerified` true. Remove the isolated fixture after acceptance.

Automated coverage uses real SQLite migrations, fault-injected journal writes and simulated gameplay/Core responses. The updated production linkage and two-process Folia replay workflow still need live execution; automated test success does not certify the full plugin combination.
