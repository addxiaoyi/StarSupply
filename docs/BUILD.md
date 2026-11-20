# Build and artifact

The first-stage build targets Java 21, Paper API 1.21.11, and the public StarCore API at commit `f769e62799e769a18fe51305b545869a17f80cd1`. StarCore must first be installed into the same Maven repository. StarSupply references its public services with compile-time types; it does not bundle the Paper API or StarCore classes.

Run the cross-platform Maven build directly with a Java 21 installation, Maven 3.9.6 or newer, and a clean StarCore checkout at that commit:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build.ps1 `
  -StarCoreRoot C:\path\to\starcore `
  -Maven C:\path\to\apache-maven-3.9.11\bin\mvn.cmd `
  -JavaHome C:\path\to\jdk-21
```

The script checks the pinned source revision, builds and installs StarCore, runs the full StarSupply Maven `verify` lifecycle, and prints the resulting JAR SHA-256. It limits the Java heap and compiler processors for smaller hosts. Dependencies resolve from Maven Central and PaperMC's public Maven repository into your standard Maven local repository. Add `-SkipStarCore` only when this precise StarCore version is already installed there.

The current verified workspace artifact is:

| Artifact | SHA-256 |
| --- | --- |
| `target/starsupply-0.1.0.jar`, v8, 2026-11-20 | `939C804E2EEE24ECB8BB546D316493150F1D291F1EEB739BA7051FA03B7BCE78` |

The StarSupply JAR contains `plugin.yml`, `config.yml`, main SQLite schema v1 and migrations v2–v8, Tavern schema v1, and its SQLite JDBC driver; insurance maintains a separate schema v2 ledger with an upgrade from v1. The server supplies StarCore and Paper API. The main lifecycle migration test exercises v1-to-v8. In v6, tariff quotes and tax phases became durable; v7 adds continuous siege observations, and v8 preserves transport failure reasons and event timestamps used for insurance recovery. Insurance v2 records actual activation time so review delays do not consume the departure window.

The current v8 `mvn verify` passed 69 tests with no failures or errors on 2026-11-20 at 12:44:57 (18.94 seconds). These include tariff operator reconciliation, receipt-backed reserve/release and confirmed-unpaid claim recovery, campaign bounds/continuity/fixed consumption intervals, captured or distant frontline suspension and binding replacement, immutable consumption notifications, independent escort bonuses, Tavern permissions/physical observation/expiry, insurance premium/claim ambiguity, activation timing and restart recovery, and wartime export controls against neutral trade partners with internal and importer-only-war exceptions. The full workspace log is `target-expansion-verify.log`.

Paper staging has verified earlier v6 and v8 artifacts; their exact hashes and observed behavior are recorded separately. The artifact above additionally contains frontline control/range, rebinding, consumption notifications, self-escort, insurance timing fixes and configurable wartime export controls, and is undergoing its own final deployment checks. A successful build does not by itself prove those live paths. See [PAPER-ACCEPTANCE.md](PAPER-ACCEPTANCE.md).
