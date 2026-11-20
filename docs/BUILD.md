# Build and artifact

The first-stage build targets Java 21, Paper API 1.21.11, and the public StarCore API at commit `f769e62799e769a18fe51305b545869a17f80cd1`. StarCore must first be installed into the same Maven repository. StarSupply references its public services with compile-time types; it does not bundle the Paper API or StarCore classes. The included Apache Maven Wrapper pins Maven 3.9.16 and verifies the distribution SHA-256; its scripts retain their Apache License 2.0 headers. After the StarCore dependency is installed, use `./mvnw verify` on POSIX or `.\mvnw.cmd verify` on Windows. `tools/build.ps1` uses this wrapper by default, so an installed Maven command is optional.

Run the cross-platform Maven build directly with a Java 21 installation, Maven 3.9.6 or newer, and a clean StarCore checkout at that commit:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1 `
  -StarCoreRoot C:\path\to\starcore `
  -Maven C:\path\to\apache-maven-3.9.11\bin\mvn.cmd `
  -JavaHome C:\path\to\jdk-21
```

The script checks the pinned source revision, builds and installs StarCore, runs the full StarSupply Maven `verify` lifecycle, and prints the resulting JAR SHA-256. It limits the Java heap and compiler processors for smaller hosts. Dependencies resolve from Maven Central and PaperMC's public Maven repository into your standard Maven local repository. Add `-SkipStarCore` only when this precise StarCore version is already installed there.

After a successful build and a clean source commit, `powershell -File .\tools\package-review.ps1` packages tracked sources, documentation, the verified JAR and a hash/test manifest into `target/dist/StarSupply-0.1.0-review.zip`. Generated databases, local credentials, test server worlds and ignored tool build output are excluded because the source list comes from Git. StarCore and optional content plugins remain external dependencies; the separate siege patch and its test instructions are included as source.

The current verified workspace artifact is:

| Artifact | SHA-256 |
| --- | --- |
| `target/starsupply-0.1.0.jar`, v8, 2026-11-20 | `D2BFC503F45F110ECD2321E244C0F2211E13D7CBFD72674E44363F2416151C08` |

The StarSupply JAR contains `plugin.yml`, `config.yml`, main SQLite schema v1 and migrations v2–v8, Tavern schema v1, and its SQLite JDBC driver; insurance maintains a separate schema v2 ledger with an upgrade from v1. The server supplies StarCore and Paper API. The main lifecycle migration test exercises v1-to-v8. In v6, tariff quotes and tax phases became durable; v7 adds continuous siege observations, and v8 preserves transport failure reasons and event timestamps used for insurance recovery. Insurance v2 records actual activation time so review delays do not consume the departure window.

The current v8 `mvn verify`, run through this project's Maven Wrapper, passed 69 tests with no failures or errors on 2026-11-20 at 16:56:53 (21.632 seconds). These include tariff operator reconciliation, receipt-backed reserve/release and confirmed-unpaid claim recovery, campaign bounds/continuity/fixed consumption intervals, captured or distant frontline suspension and binding replacement, immutable consumption notifications, independent escort bonuses, Tavern permissions/physical observation/expiry, insurance premium/claim ambiguity, activation timing and restart recovery, and wartime export controls against neutral trade partners with internal and importer-only-war exceptions. The full workspace log is `target-expansion-verify.log`.

The current artifact defers the first campaign capture to the first global scheduled callback, gates army maintenance until a valid snapshot exists, and rejects callbacks from older plugin lifecycles. It uses Paper's `Bukkit.isGlobalTickThread()` guard for campaign sampling on the global scheduler. On the verified Paper host this delegates to the same primary tick-thread check used by the preceding artifact; the API spelling expresses the intended scheduler boundary. It does not establish full Folia support, which still depends on the pinned StarCore and optional plugins.

Paper staging has verified the six-resource loop, market, escort, tariffs, insurance, Tavern, seasonal recovery, frontline controls, real combat and fault recovery on the artifact hashes documented in the acceptance record. The current artifact above additionally passed the public-API first-sample and repeated disable/re-enable regression. Recovery of a held tax settlement across a normal restart was verified on the preceding 870759 artifact; exact evidence belongs to each recorded hash. True siege gameplay was verified with the separately built, tested StarCore compatibility patch; the original pinned StarCore does not include that fix. Exact historical and final hashes are kept distinct in [PAPER-ACCEPTANCE.md](PAPER-ACCEPTANCE.md). Complete Folia-stack compatibility is still unverified and is not claimed.
