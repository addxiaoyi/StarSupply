# Isolated acceptance fault bootstrap

This helper belongs only to this disposable Paper staging directory. It is not part of StarSupply's production source or distributable. It depends only on StarCore, loads before StarSupply, and registers a highest-priority `StarCoreApi` view that forwards all public services except wrappers around `TaxationService`, `TreasuryService`, and `NationSpendingService`.

It does not write the StarCore or StarSupply database, reflect into fields, replace the internal economy implementation, or fabricate successful receipts. All real financial operations still call the original public authority. The one-shot failure simulates the response being lost before or after that call. The wrapper affects the API handle captured by StarSupply at startup, not the treasury instance already used inside StarCore's `NationSpendingService`.

After building StarSupply, run `powershell -ExecutionPolicy Bypass -File .\build.ps1 -StarCoreJar C:\path\to\starcore-1.0.0.jar -JavaHome C:\path\to\jdk-21` here. Use `-MavenRepo` for a nondefault Maven cache. The build creates `build/StarSupplyFaultBootstrap.jar` and never installs it. Copy it only into a stopped, isolated test server before a planned restart. Confirm the log shows `StarSupplyFaultBootstrap` before `StarSupply`. Remove the helper after acceptance, restart, and verify `supplyfault` is no longer available.

Commands work from console and RCON, including Bukkit's distinct remote console sender. Player senders are always rejected.

```text
supplyfault status
supplyfault tax-before [importerNationUUID] [taxAmount]
supplyfault tax-after [importerNationUUID] [taxAmount]
supplyfault premium-before <underwriterNationUUID> <premiumAmount>
supplyfault premium-after <underwriterNationUUID> <premiumAmount>
supplyfault spend-retry <nationUUID> <amount>
supplyfault balances <nationUUID> <playerUUID>
supplyfault receipt <transactionId>
supplyfault off
```

Always supply exact nation and amount filters for financial tests. Premium and spending fault arming requires both filters. Amounts must be positive exact currency amounts with at most two decimal places. Only a matching call consumes the armed fault; `status` displays the remaining arm, and `off` clears it. Restart clears all arms. Do not arm while any other nation financial test is running.

The helper persists append-only UTF-8 JSON lines at `plugins/StarSupplyFaultBootstrap/fault-evidence.jsonl`. Every line is flushed with `FileChannel.force(true)`. It records the one-shot mode, the actual public method, stable tax settlement UUID when available, original delegate return, and treasury balances immediately around the call. Before mode records `delegate_called=false`. After mode calls the original service and records its actual result before throwing. `spend-retry` is different: after verifying the live balance covers the exact request, it returns one synthetic `INSUFFICIENT_FUNDS` result without calling or changing the treasury. The subsequent unarmed retry delegates to the original service. Any real delegate exception is recorded separately and propagated; never infer successful application from that case. The helper does not contain passwords or print authentication data.

## Treasury Retry

1. Start with a fresh open purchase and seller inventory in an isolated server. Read the nation UUID and exact purchase amount from independent server state.
2. Arm `spend-retry <nation> <amount>` for that exact pair, then submit the physical sale once. The first StarCore spending call must be logged with `delegate_called=false`, `INSUFFICIENT_FUNDS`, and a public balance that covers the request. StarSupply should keep the same settlement pending without storing the cargo or changing either balance.
3. Allow the next maintenance retry to call the real StarCore service. Verify the same settlement UUID is used, the real receipt is recorded once, the seller is credited once, the treasury is debited once, and the purchase and item transfer become `BOUGHT` and `STORED`.
4. Stop normally and inspect the independent fault evidence, public balances, StarCore spending receipt, player inventory save, and read-only StarSupply snapshot. Restore the isolated test snapshot after recording the result.

## Tax recovery

1. Set up a real cross-border purchase with a valid public StarCore agreement and nonzero importer TARIFF. Use the existing acceptance probe to read the original public treasury, seller balance, and tax history before the call.
2. Arm `tax-after <importer> <tax>` and sell the real goods. StarSupply should freeze the order/real cargo in `REVIEW`; the seller net amount is credited once. The independent helper evidence must show original `fundTax` returned the expected tax and the original public tax history must contain that same result.
3. Only after checking that independent evidence, execute `/supply reconcile-tax <settlementUUID> funded` as the authorized admin. Confirm purchase `BOUGHT`, ordinary payment `PAID`, tax `FUNDED`, and cargo stored once. Repeating the command must not fund tax or credit the seller again.
4. Repeat using a fresh purchase and `tax-before <importer> <tax>`. Confirm evidence says the original service was never called, no new public tax history exists, and seller net amount was credited once. Then use `/supply reconcile-tax <settlementUUID> unfunded`; verify the original same quote is funded exactly once before cargo releases.
5. For restart recovery, stop and restart while the order is held, keeping both independent logs and original state snapshots. Do not rely on StarCore tax history after restart because this pinned version's history is not durable. The held local tax phase and stable UUID must survive without another seller credit.

## Insurance premium-credit recovery

This wrapper injects only the treasury premium-credit leg. It does not inject player premium withdrawal/claim calls, because StarSupply deliberately requires the final `InternalEconomyService` implementation. Those boundaries remain covered by adapter/unit tests unless a separate approved fixture is added.

1. Use a fresh purchased cargo batch with a genuinely risky route and a national carrier at the origin. Read `/supply insurance <shipment>` to get the exact premium and verify available player/national funds.
2. Arm `premium-after <nation> <premium>` before `/supply insure`. The original national reserve and player premium withdrawal should apply once, the original treasury premium credit should return true once, and the injected lost return should leave the policy in review. Transport accept/cancel/deliver must remain blocked.
3. Confirm independent evidence shows `tryDeposit=true` and the expected balance change. Execute `/supply reconcile-insurance <policyUUID> premium_credit applied`. Confirm the policy becomes `ACTIVE`, `activatedAt` is the actual activation boundary, and no player debit or treasury credit repeats.
4. A second fresh cargo policy with `premium-before` should freeze at the same stage with `delegate_called=false`. After verifying absence of application, execute `/supply reconcile-insurance <policyUUID> premium_credit untouched`. This should refund the proven player premium debit once using its stable refund leg and release the original reserve via StarCore's receipt. The policy closes; cargo is unchanged.
5. In both modes, repeat the reconciliation and restart. Check fixed money IDs and no duplicate payment/refund, and inspect both SQL snapshots read-only. Keep these test effects separate from ordinary freight/claim settlement.

Faults are observations of a controlled call boundary. They do not authorize an admin to mark ambiguous real-world transactions as applied without evidence.

## Cross-border Trade Rejections

`trade-rejections` builds a Paper-only probe that creates, checks, cancels, and lifts StarCore trade agreements and embargoes through public services. Its runner starts the isolated Paper 1.21.11 stage, uses the existing SupplyBuyer and SupplyCarrier profiles, verifies physical inventory and public balances, stops the server normally, and restores the saved plugin and player state. The fixture creates temporary Core trade state, so use it only on the disposable loopback stage with the backup manifest configured in `run.cjs`.

Build the fixture using the stage's compatible StarCore JAR and Java 21.

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\acceptance\trade-rejections\build.ps1 `
  -StarCoreJar C:\path\to\isolated\plugins\StarCore-1.0.0.jar `
  -JavaHome C:\path\to\jdk-21
```

Run the workflow against the stopped, isolated stage.

```powershell
node .\tools\acceptance\trade-rejections\run.cjs `
  C:\path\to\isolated-paper-stage `
  C:\path\to\jdk-21\bin\java.exe `
  'C:\Program Files\nodejs\node.exe' `
  C:\path\to\python.exe
```

The run record covers one accepted trade under a one-unit quota, followed by quota-exhausted, expired, cancelled, mispriced, and embargoed rejections. It grants one test bread per attempted sale and restores Buyer and Carrier player files. It does not restore world region files. The 2026-11-23 result is in `docs/verification/paper-v11-cross-border-trade-rejections-20261123.json`.

## Folia entity retirement and region recreation

[Region retirement acceptance](region-retirement/README.md) provides a reproducible fixture for pending inventory callbacks, actual Folia scheduler retirement, guard cleanup, distant chunk unload and removal of its region, and reconnect into a newly created region. It checks both untouched PREPARED transfers and durable player receipts using production storage and recovery code. The fixture creates a separate copied stage and records every test JAR hash; its reflection and bounded synchronization are confined to the test plugin. It is not a survival acquisition test.
