package local.test;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.module.army.ArmyService;
import dev.starcore.starcore.module.army.LogisticsSupplyReceipt;
import dev.starcore.starcore.module.army.model.ArmyUnit;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.TreasuryService;
import dev.starsupply.api.SupplyApi;
import dev.starsupply.api.event.SupplyConsumptionEvent;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable acceptance fixture. No SQL writes, private-field access or production installation. */
public final class SupplyNativeProof extends JavaPlugin implements Listener {
    private final Map<UUID, Integer> events = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        Objects.requireNonNull(getCommand("supplynativeproof")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        emit("enabled", Map.of("isolatedFixture", true, "server", Bukkit.getVersion()));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("Isolated acceptance: console/RCON only");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("supplynativeproof prepare <army> <nativeSupply> <baseMorale> | state <army> | receipt <request> | retry <request> | watch <army> <seconds>");
            return true;
        }
        String[] captured = args.clone();
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            try {
                require(Bukkit.isGlobalTickThread(), "Global authority context required");
                run(captured);
            } catch (Throwable failure) {
                emit("failed", Map.of("operation", String.join(" ", captured), "error", failure.toString()));
                getLogger().log(java.util.logging.Level.SEVERE, "NATIVE_SUPPLY_PROOF_FAILED", failure);
            }
        });
        sender.sendMessage("NATIVE_SUPPLY_PROOF_QUEUED " + args[0]);
        return true;
    }

    private void run(String[] a) {
        StarCoreApi core = Objects.requireNonNull(Bukkit.getServicesManager().load(StarCoreApi.class));
        ArmyService armies = core.service(ArmyService.class).orElseThrow();
        switch (a[0]) {
            case "prepare" -> {
                require(a.length == 4, "prepare <army> <nativeSupply> <baseMorale>");
                UUID id = UUID.fromString(a[1]);
                int supply = Integer.parseInt(a[2]);
                double morale = Double.parseDouble(a[3]);
                require(supply >= 0 && supply <= 100 && Double.isFinite(morale)
                        && morale >= 10 && morale <= 100, "Preparation bounds");
                ArmyUnit before = armies.getArmySnapshot(id).orElseThrow();
                ArmyUnit prepared = before.copy();
                prepared.consumeSupply(100);
                prepared.resupply(supply);
                prepared.changeMorale(morale - prepared.morale());
                armies.restoreArmyStates(List.of(prepared));
                ArmyUnit after = armies.getArmySnapshot(id).orElseThrow();
                require(after.supply() == supply && Math.abs(after.morale() - morale) < 1e-7,
                        "Prepared snapshot not durably restored");
                emit("prepared", Map.of("before", unit(before), "after", unit(after),
                        "publicDetachedSnapshotRestored", true, "testPreparationNotGameplaySupply", true));
            }
            case "state" -> {
                require(a.length == 2, "state <army>");
                state(armies, UUID.fromString(a[1]), "manual");
            }
            case "watch" -> {
                require(a.length == 3, "watch <army> <seconds>");
                UUID id = UUID.fromString(a[1]);
                int seconds = Integer.parseInt(a[2]);
                require(seconds >= 1 && seconds <= 600, "watch seconds 1..600");
                AtomicInteger remaining = new AtomicInteger(seconds);
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                    try { state(armies, id, "watch"); }
                    catch (RuntimeException failure) {
                        emit("watch-failed", Map.of("army", id, "error", failure.toString()));
                        task.cancel();
                    }
                    if (remaining.decrementAndGet() <= 0) task.cancel();
                }, 1L, 20L);
                emit("watch-started", Map.of("army", id, "seconds", seconds));
            }
            case "receipt", "retry" -> {
                require(a.length == 2, "receipt/retry <request>");
                UUID request = UUID.fromString(a[1]);
                LogisticsSupplyReceipt receipt = armies.logisticsSupplyReceipt(request).orElseThrow();
                if (a[0].equals("receipt")) {
                    emit("receipt", receipt(receipt));
                    return;
                }
                TreasuryService treasury = core.service(TreasuryService.class).orElseThrow();
                NationId nation = new NationId(receipt.nationId());
                var balanceBefore = treasury.balance(nation);
                ArmyUnit before = armies.getArmySnapshot(receipt.armyId()).orElseThrow();
                LogisticsSupplyReceipt retried = armies.applyLogisticsSupply(request, receipt.armyId(),
                        receipt.nationId(), receipt.requestedPoints());
                ArmyUnit after = armies.getArmySnapshot(receipt.armyId()).orElseThrow();
                var balanceAfter = treasury.balance(nation);
                require(receipt.equals(retried), "Retry changed immutable receipt");
                require(before.supply() == after.supply(), "Retry credited native supply twice");
                require(balanceBefore.compareTo(balanceAfter) == 0, "Logistics retry changed treasury");
                boolean conflictRejected = false;
                try {
                    armies.applyLogisticsSupply(request, receipt.armyId(), receipt.nationId(),
                            receipt.requestedPoints() == 100 ? 99 : receipt.requestedPoints() + 1);
                } catch (IllegalArgumentException expected) { conflictRejected = true; }
                require(conflictRejected, "Request ID accepted different supply amount");
                require(armies.getArmySnapshot(receipt.armyId()).orElseThrow().supply() == after.supply(),
                        "Conflicting replay changed native supply");
                emit("retry-passed", Map.of("receipt", receipt(receipt), "nativeSupplyBefore", before.supply(),
                        "nativeSupplyAfter", after.supply(), "treasuryBefore", balanceBefore,
                        "treasuryAfter", balanceAfter, "conflictRejected", true));
            }
            default -> throw new IllegalArgumentException("Unknown acceptance operation");
        }
    }

    private void state(ArmyService armies, UUID id, String label) {
        ArmyUnit army = armies.getArmySnapshot(id).orElseThrow();
        SupplyApi supply = Objects.requireNonNull(Bukkit.getServicesManager().load(SupplyApi.class));
        Map<String, Object> row = new LinkedHashMap<>(unit(army));
        row.put("label", label);
        row.put("effectiveMorale", armies.effectiveMorale(id));
        row.put("starSupply", supply.armySupply(id).map(Object::toString).orElse("unbound-or-not-ready"));
        emit("state", row);
    }

    @EventHandler public void consumed(SupplyConsumptionEvent event) {
        var c = event.getConsumption();
        emit("consumed", Map.of("request", c.settlementId(), "army", c.armyId(), "nation", c.nationId(),
                "interval", c.interval(), "consumed", c.consumed(), "demand", c.demand(),
                "count", events.merge(c.settlementId(), 1, Integer::sum)));
    }

    private static Map<String, Object> unit(ArmyUnit army) {
        return Map.of("army", army.id(), "nation", army.nationId(), "nativeSupply", army.supply(),
                "baseMorale", army.morale(), "soldiers", army.soldiers(), "state", army.state());
    }

    private static Map<String, Object> receipt(LogisticsSupplyReceipt r) {
        return Map.of("request", r.requestId(), "army", r.armyId(), "nation", r.nationId(),
                "requestedPoints", r.requestedPoints(), "appliedPoints", r.appliedPoints());
    }

    private synchronized void emit(String type, Map<String, ?> values) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("at", Instant.now());
        row.put("thread", Thread.currentThread().getName()); row.put("global", Bukkit.isGlobalTickThread());
        row.putAll(values);
        String json = json(row);
        try {
            Files.createDirectories(getDataFolder().toPath());
            try (FileChannel out = FileChannel.open(getDataFolder().toPath().resolve("evidence.jsonl"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer data = StandardCharsets.UTF_8.encode(json + "\n");
                while (data.hasRemaining()) out.write(data);
                out.force(true);
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot persist acceptance evidence", failure);
        }
        getLogger().info("NATIVE_SUPPLY_PROOF " + json);
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .map(e -> json(String.valueOf(e.getKey())) + ":" + json(e.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> values) {
            List<String> entries = new java.util.ArrayList<>();
            for (Object entry : values) entries.add(json(entry));
            return String.join(",", entries).transform(s -> "[" + s + "]");
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) switch (c) {
            case '\\' -> out.append("\\\\");
            case '"' -> out.append("\\\"");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> { if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
        }
        return out.append('"').toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
