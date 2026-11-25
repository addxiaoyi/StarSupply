package local.test;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyApi;
import dev.starsupply.api.SupplyModels.Purchase;
import dev.starsupply.api.event.SupplySettlementEvent;
import dev.starsupply.bukkit.item.InventoryEscrow;
import dev.starsupply.storage.SupplyStore;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Isolated fault fixture, never a production plugin. It prepares real SQL and player receipts,
 * then enters the production callback/guard boundary through reflection to control interruption.
 * It does not replace the Player, scheduler, economy, SQLite, or StarSupply callback registry.
 */
public final class SupplyRetirementProof extends JavaPlugin implements Listener {
    private static final BigDecimal PRICE = new BigDecimal("5.00");
    private JavaPlugin supply;
    private SupplyApi api;
    private SupplyStore store;
    private InventoryEscrow escrow;
    private Method entityMethod, sqlMethod, inventoryTaskMethod, idleMethod;
    private Object callbacks;
    private Set<UUID> busy;
    private ExecutorService background;
    private volatile Attempt current;
    private Location originalLocation;
    private boolean originalGravity, originalInvulnerable, originalAllowFlight, originalFlying;

    private static final class Attempt {
        final String mode;
        final UUID player;
        final UUID transfer = UUID.randomUUID();
        final AtomicInteger executed = new AtomicInteger(), retired = new AtomicInteger();
        final AtomicInteger settlements = new AtomicInteger(), loads = new AtomicInteger();
        final CompletableFuture<Void> callbackFinished = new CompletableFuture<>();
        volatile boolean queued, quit, unloaded, regionAbsent, passed;
        volatile long initialRegionId, reloadedRegionId;
        volatile Location location;
        volatile Purchase purchase;
        volatile InventoryEscrow.Plan plan;
        volatile int initialIron;
        volatile BigDecimal initialBalance;
        Attempt(String mode, UUID player) { this.mode = mode; this.player = player; }
    }

    @Override @SuppressWarnings("unchecked") public void onEnable() {
        try {
            require("127.0.0.1".equals(getServer().getIp()) && !getServer().getOnlineMode(),
                    "requires a loopback offline acceptance server");
            require(Files.isRegularFile(Path.of("folia-proof", "allow-region-retirement-fixture")),
                    "isolated stage marker missing");
            supply = (JavaPlugin) Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("StarSupply"));
            api = (SupplyApi) supply;
            store = (SupplyStore) field("store");
            escrow = (InventoryEscrow) field("inventory");
            busy = (Set<UUID>) field("inventoryBusy");
            callbacks = field("entityCallbacks");
            entityMethod = method("entity", Player.class, Callable.class);
            sqlMethod = method("sql", Callable.class);
            inventoryTaskMethod = method("inventoryTask", Player.class, Supplier.class);
            idleMethod = callbacks.getClass().getDeclaredMethod("isIdle");
            idleMethod.setAccessible(true);
            background = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "retirement-proof-boundary");
                thread.setDaemon(true);
                return thread;
            });
            Bukkit.getPluginManager().registerEvents(this, this);
            Objects.requireNonNull(getCommand("retirementproof")).setExecutor(this);
            emit("enabled", Map.of("server", Bukkit.getVersion(), "fixtureOnly", true));
        } catch (Exception failure) { throw new IllegalStateException("Isolated fixture failed to enable", failure); }
    }

    @Override public void onDisable() { if (background != null) background.shutdownNow(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) return true;
        try {
            require(Bukkit.isOwnedByCurrentRegion(player), "command must own player region");
            require(args.length > 0, "start untouched|receipt / snapshot / finish");
            switch (args[0]) {
                case "start" -> start(player, args[1]);
                case "snapshot" -> snapshot(player);
                case "finish" -> finish(player);
                default -> throw new IllegalArgumentException("unknown fixture action");
            }
        } catch (Throwable failure) { fail(failure); }
        return true;
    }

    private void start(Player player, String mode) {
        require(mode.equals("untouched") || mode.equals("receipt"), "unknown fault mode");
        require(current == null || current.passed, "prior attempt not verified");
        require(!busy.contains(player.getUniqueId()), "join recovery is still busy");
        require(escrow.receipt(player).isEmpty(), "unresolved pre-existing receipt");
        if (originalLocation == null) {
            originalLocation = player.getLocation().clone();
            originalGravity = player.hasGravity();
            originalInvulnerable = player.isInvulnerable();
            originalAllowFlight = player.getAllowFlight();
            originalFlying = player.isFlying();
        }
        Attempt attempt = new Attempt(mode, player.getUniqueId());
        current = attempt;
        // A copied test world and pre-existing distant region; never survival acquisition evidence.
        Location remote = new Location(player.getWorld(), -4120.5, 200, 68.5);
        player.setGravity(false);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.teleportAsync(remote).whenComplete((ok, error) -> {
            if (error != null) { fail(error); return; }
            if (!Boolean.TRUE.equals(ok)) { fail(new IllegalStateException("remote teleport failed")); return; }
            owned(player, () -> prepare(player, attempt));
        });
    }

    private void prepare(Player player, Attempt a) throws Exception {
        a.location = player.getLocation().clone();
        a.initialRegionId = regionId(a.location);
        require(a.initialRegionId >= 0, "player has no owning Folia region");
        a.initialIron = iron(player);
        a.initialBalance = economy().getBalance(a.player);
        a.plan = escrow.plan(player, ResourceType.EQUIPMENT, 1);
        api.createPurchase(player, "alpha-depot", ResourceType.EQUIPMENT, 1, PRICE)
                .thenCompose(purchase -> {
                    a.purchase = purchase;
                    return sql(() -> {
                        List<SupplyStore.Lot> lots = a.plan.lots().stream().map(lot -> new SupplyStore.Lot(
                                lot.itemId(), lot.quality(), lot.qualityRatio(), lot.itemCount(), lot.units(), lot.itemBytes())).toList();
                        store.prepareTransfer(a.transfer, purchase.id(), a.player, a.plan.beforeHash(), a.plan.afterHash(),
                                lots, System.currentTimeMillis());
                        return null;
                    });
                }).whenComplete((ignored, failure) -> {
                    if (failure != null) { fail(failure); return; }
                    owned(player, () -> interrupt(player, a));
                });
    }

    private void interrupt(Player player, Attempt a) throws Exception {
        if (a.mode.equals("receipt")) {
            require(escrow.apply(player, a.transfer, a.plan), "physical debit did not apply");
            require(escrow.verifyReceipt(player, a.transfer), "player receipt not durable");
        }
        emit("prepared", Map.ofEntries(Map.entry("mode", a.mode), Map.entry("transfer", a.transfer),
                Map.entry("purchase", a.purchase.id()), Map.entry("ironBefore", a.initialIron),
                Map.entry("ironNow", iron(player)), Map.entry("receipt", escrow.receipt(player).map(UUID::toString).orElse("")),
                Map.entry("beforeHash", a.plan.beforeHash()), Map.entry("afterHash", a.plan.afterHash()),
                Map.entry("balanceBefore", a.initialBalance), Map.entry("owned", Bukkit.isOwnedByCurrentRegion(player)),
                Map.entry("chunkX", a.location.getBlockX() >> 4), Map.entry("chunkZ", a.location.getBlockZ() >> 4),
                Map.entry("regionId", a.initialRegionId)));

        // An actual delayed Folia task witnesses retirement independently of StarSupply's future.
        require(player.getScheduler().runDelayed(this, task -> fail(new AssertionError("retirement witness ran")),
                () -> {
                    a.retired.incrementAndGet();
                    emit("scheduler-retired", Map.of("mode", a.mode, "player", a.player));
                }, 200L) != null, "player scheduler already retired before test");
        player.kick(Component.text("Isolated retirement boundary: " + a.mode));
    }

    private void queueAtQuit(Player player, Attempt a) throws Exception {
        CountDownLatch enqueued = new CountDownLatch(1);
        background.execute(() -> {
            try {
                CompletionStage<String> result = guarded(player, () -> entity(player, () -> {
                    a.executed.incrementAndGet();
                    // Neither branch may execute after retirement. They are genuine production inventory operations.
                    if (a.mode.equals("untouched")) escrow.apply(player, a.transfer, a.plan);
                    else escrow.clearReceipt(player, a.transfer);
                    return "unexpected callback execution";
                }));
                a.queued = true;
                enqueued.countDown();
                result.whenComplete((value, failure) -> {
                    try {
                        require(failure != null && a.executed.get() == 0, "pending inventory callback ran");
                        Throwable root = cause(failure);
                        boolean retiredStack = Arrays.stream(root.getStackTrace())
                                .anyMatch(frame -> frame.getMethodName().equals("retire"));
                        require(retiredStack, "production future did not finish from a scheduler retirement callback");
                        require(!busy.contains(a.player), "inventory lock leaked after retirement");
                        emit("callback-retired", Map.of("mode", a.mode, "executions", a.executed.get(),
                                "inventoryBusy", busy.contains(a.player), "error", root.toString(),
                                "retirementStack", Arrays.stream(root.getStackTrace()).map(Object::toString).toList()));
                        a.callbackFinished.complete(null);
                    } catch (Throwable invalid) { a.callbackFinished.completeExceptionally(invalid); fail(invalid); }
                });
            } catch (Throwable failure) { enqueued.countDown(); fail(failure); }
        });
        // Test-only bounded barrier: the region cannot reach the next tick until the callback is queued.
        require(enqueued.await(500, TimeUnit.MILLISECONDS) && a.queued, "callback enqueue deadline exceeded");
        require(a.executed.get() == 0 && busy.contains(a.player), "callback was not pending under the inventory lock");
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Attempt a = current;
        if (a == null || !a.player.equals(event.getPlayer().getUniqueId())) return;
        if (a.quit) return;
        try {
            // PlayerList.remove fires quit before retiring the entity scheduler. Its owning tick cannot
            // run the pending task while this listener is active, so this does not race the network close.
            queueAtQuit(event.getPlayer(), a);
        } catch (Throwable failure) { fail(failure); return; }
        a.quit = true;
        emit("quit", Map.of("mode", a.mode, "queued", a.queued));
        // A global observer does not pin or reload the retired player's region.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> background.execute(() -> {
            try {
                a.callbackFinished.get(5, TimeUnit.SECONDS);
                require(a.retired.get() == 1, "missing real Folia retired callback");
                require((boolean) idleMethod.invoke(callbacks), "callback registry did not drain");
                // Submission to an already retired Player must also finish without acquiring a permanent guard.
                try {
                    guarded(event.getPlayer(), () -> entity(event.getPlayer(), () -> {
                        a.executed.incrementAndGet(); return "unexpected retired submission";
                    })).toCompletableFuture().join();
                    throw new AssertionError("retired scheduler accepted callback");
                } catch (CompletionException expected) {
                    require(cause(expected) instanceof IllegalArgumentException, "wrong retired rejection");
                }
                require(!busy.contains(a.player) && a.executed.get() == 0, "retired submission leaked inventory lock");
                require((boolean) idleMethod.invoke(callbacks), "rejected callback remains registered");
                emit("drained", Map.of("mode", a.mode, "inventoryBusy", false, "callbacksIdle", true,
                        "retiredSubmissionRejected", true));
            } catch (Throwable failure) { fail(failure); }
        }), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void unload(ChunkUnloadEvent event) {
        Attempt a = current;
        if (!target(a, event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ()) || !a.quit) return;
        emit("chunk-unload-event", Map.of("mode", a.mode, "x", event.getChunk().getX(), "z", event.getChunk().getZ()));
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            try {
                boolean loaded = a.location.getWorld().isChunkLoaded(a.location.getBlockX() >> 4, a.location.getBlockZ() >> 4);
                require(!loaded, "unload event did not release chunk");
                require(Bukkit.getPlayer(a.player) == null, "player still online after retirement");
                a.unloaded = true;
                emit("unloaded", Map.of("mode", a.mode, "loaded", false, "playerOnline", false));
                AtomicInteger observations = new AtomicInteger();
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, observer -> {
                    try {
                        long id = regionId(a.location);
                        if (id < 0) {
                            a.regionAbsent = true;
                            observer.cancel();
                            emit("region-absent", Map.of("mode", a.mode, "previousId", a.initialRegionId,
                                    "regionPresent", false, "observations", observations.incrementAndGet()));
                        } else if (observations.incrementAndGet() >= 100) {
                            observer.cancel();
                            fail(new IllegalStateException("Folia region still present after chunk unload: " + id));
                        }
                    } catch (Throwable failure) { observer.cancel(); fail(failure); }
                }, 1L, 20L);
            } catch (Throwable failure) { fail(failure); }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void load(ChunkLoadEvent event) {
        Attempt a = current;
        if (target(a, event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ()) && a.unloaded) {
            try {
                a.reloadedRegionId = regionId(a.location);
                require(a.regionAbsent && a.reloadedRegionId >= 0 && a.reloadedRegionId != a.initialRegionId,
                        "reconnect did not recreate an absent Folia region");
                emit("reloaded", Map.of("mode", a.mode, "loads", a.loads.incrementAndGet(),
                        "newChunk", event.isNewChunk(), "regionId", a.reloadedRegionId));
            } catch (Throwable failure) { fail(failure); }
        }
    }

    @EventHandler public void settled(SupplySettlementEvent event) {
        Attempt a = current;
        if (a != null && a.transfer.equals(event.getSettlement().id())) {
            emit("settled", Map.of("mode", a.mode, "id", a.transfer, "count", a.settlements.incrementAndGet(),
                    "outcome", event.getSettlement().outcome(), "amount", event.getSettlement().amount()));
        }
    }

    private void snapshot(Player player) {
        Attempt a = Objects.requireNonNull(current);
        require(a.player.equals(player.getUniqueId()), "wrong acceptance player");
        if (busy.contains(a.player)) { emit("recovering", Map.of("mode", a.mode)); return; }
        int count = iron(player);
        String receipt = escrow.receipt(player).map(UUID::toString).orElse("");
        String hash = escrow.hash(player);
        sql(() -> {
            var purchase = store.purchase(a.purchase.id()).orElseThrow();
            var transfer = store.transfer(a.transfer);
            var settlement = store.settlement(a.transfer);
            String transferState = transfer.map(row -> row.state().name()).orElse("ABSENT");
            String settlementState = settlement.map(row -> row.outcome()).orElse("ABSENT");
            BigDecimal balance = economy().getBalance(a.player);
            boolean untouched = a.mode.equals("untouched");
            boolean pass = a.unloaded && a.regionAbsent && a.loads.get() > 0 && a.retired.get() == 1 && a.executed.get() == 0
                    && receipt.isEmpty() && count == a.initialIron - (untouched ? 0 : 1)
                    && hash.equals(untouched ? a.plan.beforeHash() : a.plan.afterHash())
                    && purchase.state().name().equals(untouched ? "OPEN" : "BOUGHT")
                    && transferState.equals(untouched ? "ABSENT" : "STORED")
                    && settlementState.equals(untouched ? "ABSENT" : "PAID")
                    && a.settlements.get() == (untouched ? 0 : 1)
                    && balance.compareTo(a.initialBalance.add(untouched ? BigDecimal.ZERO : PRICE)) == 0;
            a.passed = pass;
            emit("snapshot", Map.ofEntries(Map.entry("mode", a.mode), Map.entry("pass", pass),
                    Map.entry("purchase", purchase.id()), Map.entry("purchaseState", purchase.state()),
                    Map.entry("transfer", a.transfer), Map.entry("transferState", transferState),
                    Map.entry("settlementState", settlementState), Map.entry("settlementEvents", a.settlements.get()),
                    Map.entry("iron", count), Map.entry("receipt", receipt), Map.entry("fingerprint", hash),
                    Map.entry("balance", balance), Map.entry("chunkUnloaded", a.unloaded),
                    Map.entry("regionAbsent", a.regionAbsent), Map.entry("originalRegionId", a.initialRegionId),
                    Map.entry("reloadedRegionId", a.reloadedRegionId), Map.entry("chunkReloads", a.loads.get()),
                    Map.entry("inventoryBusy", busy.contains(a.player))));
            return null;
        }).exceptionally(failure -> { fail(failure); return null; });
    }

    private void finish(Player player) {
        require(current != null && current.passed, "attempt not verified");
        player.teleportAsync(originalLocation).whenComplete((ok, failure) -> {
            if (failure != null) { fail(failure); return; }
            owned(player, () -> {
                require(Boolean.TRUE.equals(ok), "home teleport failed");
                player.setGravity(originalGravity);
                player.setInvulnerable(originalInvulnerable);
                player.setFlying(originalFlying);
                player.setAllowFlight(originalAllowFlight);
                player.saveData();
                emit("finished", Map.of("player", player.getUniqueId(), "saved", true));
                current = null;
            });
        });
    }

    private boolean target(Attempt a, String world, int x, int z) {
        return a != null && a.location != null && a.location.getWorld().getName().equals(world)
                && a.location.getBlockX() >> 4 == x && a.location.getBlockZ() >> 4 == z;
    }
    /** Read-only internal observation, pinned to the Folia version in the runtime manifest. */
    private long regionId(Location location) throws Exception {
        Object handle = location.getWorld().getClass().getMethod("getHandle").invoke(location.getWorld());
        Object regionizer = handle.getClass().getField("regioniser").get(handle);
        Object region = regionizer.getClass().getMethod("getRegionAtSynchronised", int.class, int.class)
                .invoke(regionizer, location.getBlockX() >> 4, location.getBlockZ() >> 4);
        return region == null ? -1 : region.getClass().getField("id").getLong(region);
    }
    private EconomyService economy() {
        return Objects.requireNonNull(Bukkit.getServicesManager().load(StarCoreApi.class))
                .service(EconomyService.class).orElseThrow();
    }
    private int iron(Player player) {
        return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull)
                .filter(item -> item.getType() == Material.IRON_INGOT).mapToInt(item -> item.getAmount()).sum();
    }
    private Object field(String name) throws Exception {
        Field field = supply.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(supply);
    }
    private Method method(String name, Class<?>... types) throws Exception {
        Method method = supply.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method;
    }
    @SuppressWarnings("unchecked") private <T> CompletionStage<T> invoke(Method method, Object... args) {
        try { return (CompletionStage<T>) method.invoke(supply, args); }
        catch (InvocationTargetException failure) { throw new CompletionException(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new CompletionException(failure); }
    }
    private <T> CompletionStage<T> sql(Callable<T> task) { return invoke(sqlMethod, task); }
    private <T> CompletionStage<T> entity(Player player, Callable<T> task) { return invoke(entityMethod, player, task); }
    private CompletionStage<String> guarded(Player player, Supplier<CompletionStage<String>> task) {
        return invoke(inventoryTaskMethod, player, task);
    }
    private void owned(Player player, CheckedTask action) {
        if (player.getScheduler().run(this, task -> {
            try { action.run(); } catch (Throwable failure) { fail(failure); }
        }, () -> fail(new IllegalStateException("unexpected fixture retirement"))) == null) {
            fail(new IllegalStateException("fixture player already retired"));
        }
    }
    @FunctionalInterface private interface CheckedTask { void run() throws Exception; }
    private void fail(Throwable failure) {
        emit("failed", Map.of("error", cause(failure).toString(), "stack", Arrays.stream(cause(failure).getStackTrace()).map(Object::toString).toList()));
    }
    private static Throwable cause(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof InvocationTargetException) && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private static void require(boolean test, String message) { if (!test) throw new IllegalStateException(message); }
    private synchronized void emit(String type, Map<String, ?> fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type); row.put("at", Instant.now().toString()); row.put("thread", Thread.currentThread().getName());
        row.putAll(fields);
        String line = json(row);
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("evidence.jsonl"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) { throw new IllegalStateException("evidence write failed", failure); }
        getLogger().info("REGION_RETIREMENT " + line);
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> rows = new ArrayList<>(); map.forEach((k, v) -> rows.add(json(k.toString()) + ":" + json(v)));
            return "{" + String.join(",", rows) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> rows = new ArrayList<>(); list.forEach(v -> rows.add(json(v))); return "[" + String.join(",", rows) + "]";
        }
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
