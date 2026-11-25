package local.test;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.module.army.ArmyService;
import dev.starcore.starcore.module.army.LogisticsSupplyReceipt;
import dev.starcore.starcore.module.army.model.ArmyUnit;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.TreasuryService;
import dev.starsupply.api.SupplyApi;
import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyModels.CampaignStatus;
import dev.starsupply.api.event.SupplyConsumptionEvent;
import dev.starsupply.service.ArmySupplyRules;
import dev.starsupply.service.CampaignRules;
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
    private final Map<UUID, Integer> demandWaits = new ConcurrentHashMap<>();
    private final UUID bootId = UUID.randomUUID();

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
            sender.sendMessage("supplynativeproof prepare <army> <nativeSupply> <baseMorale> | state <army> | demand <army> <correlation> | receipt/retry <request> [correlation] | accounts <nation> <player> <correlation> | watch <army> <seconds>");
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
            case "demand" -> {
                require(a.length == 3, "demand <army> <correlation UUID>");
                UUID armyId = UUID.fromString(a[1]);
                UUID correlation = UUID.fromString(a[2]);
                SupplyApi supply = Objects.requireNonNull(Bukkit.getServicesManager().load(SupplyApi.class));
                var plugin = (JavaPlugin) Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("StarSupply"));
                var config = plugin.getConfig();
                long intervalMillis = (long) config.getDouble("supply-interval-seconds", 300) * 1000;
                long now = System.currentTimeMillis();
                var status = supply.armySupply(armyId).orElse(null);
                if (status == null || status.interval() != Math.floorDiv(now, intervalMillis)) {
                    int attempts = demandWaits.merge(correlation, 1, Integer::sum);
                    require(attempts <= 20, "Current army demand did not become available within 20 observations");
                    Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
                        try { run(a); }
                        catch (Throwable failure) {
                            demandWaits.remove(correlation);
                            emit("failed", Map.of("correlation", correlation, "operation", "demand", "error", failure.toString()));
                            getLogger().log(java.util.logging.Level.SEVERE, "DEMAND_PROOF_FAILED", failure);
                        }
                    }, 20L);
                    return;
                }
                demandWaits.remove(correlation);
                var army = armies.getArmySnapshot(armyId).orElseThrow();
                require(status.nationId().equals(army.nationId()), "Army ownership changed while sampling demand");
                var campaigns = new CampaignRules.Config(config.getDouble("campaign.winter-food-extra", 0.25),
                        config.getDouble("campaign.winter-medical-extra", 0.10),
                        config.getDouble("campaign.siege-maximum-extra", 0.50),
                        (long) config.getDouble("campaign.siege-ramp-seconds", 1800) * 1000,
                        (long) config.getDouble("campaign.siege-continuity-gap-seconds", 60) * 1000);
                // Use the production rules and actual configured maxima. Dropping
                // an efficiency discount must not invalidate the prepared batch.
                int soldiers = Math.max(status.soldiers(), army.soldiers());
                var maximum = new java.util.EnumMap<ResourceType, Integer>(ResourceType.class);
                maximum.putAll(CampaignRules.demand(ArmySupplyRules.demand(soldiers),
                        new CampaignStatus(true, true, now, 0, campaigns.siegeMaximumExtra(), now), campaigns));
                status.demand().forEach((resource, count) -> maximum.merge(resource, count, Math::max));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("protocolVersion", 1); row.put("correlation", correlation);
                row.put("army", armyId); row.put("nation", status.nationId()); row.put("front", status.warehouseId());
                row.put("soldiers", soldiers); row.put("atWar", status.atWar()); row.put("access", status.frontAccess());
                row.put("intervalMillis", intervalMillis); row.put("slot", status.interval()); row.put("capturedAt", now);
                row.put("currentDemand", status.demand()); row.put("maximumDemand", maximum);
                row.put("maxPurchaseItems", (int) config.getDouble("max-purchase-items", 256));
                emit("demand", row);
            }
            case "accounts" -> {
                require(a.length == 4, "accounts <nation> <player> <correlation UUID>");
                UUID nation = UUID.fromString(a[1]);
                UUID player = UUID.fromString(a[2]);
                UUID correlation = UUID.fromString(a[3]);
                TreasuryService treasury = core.service(TreasuryService.class).orElseThrow();
                EconomyService economy = core.service(EconomyService.class).orElseThrow();
                emit("accounts", Map.of("nation", nation, "player", player, "correlation", correlation,
                        "playerBalance", economy.getBalance(player).toPlainString(),
                        "treasuryBalance", treasury.balance(new NationId(nation)).toPlainString()));
            }
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
                require(a.length == 2 || a.length == 3, "receipt/retry <request> [correlation UUID]");
                UUID request = UUID.fromString(a[1]);
                String correlation = a.length == 3 ? UUID.fromString(a[2]).toString() : "legacy";
                LogisticsSupplyReceipt receipt = armies.logisticsSupplyReceipt(request).orElseThrow();
                if (a[0].equals("receipt")) {
                    Map<String, Object> row = new LinkedHashMap<>(receipt(receipt));
                    row.put("correlation", correlation);
                    emit("receipt", row);
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
                        "nativeSupplyAfter", after.supply(), "treasuryBefore", balanceBefore.toPlainString(),
                        "treasuryAfter", balanceAfter.toPlainString(), "conflictRejected", true, "correlation", correlation));
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
        row.put("bootId", bootId);
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
