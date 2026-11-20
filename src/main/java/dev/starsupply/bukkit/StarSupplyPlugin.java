package dev.starsupply.bukkit;

import static dev.starsupply.api.SupplyModels.*;
import static dev.starsupply.storage.SupplyStore.require;

import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyApi;
import dev.starsupply.api.SupplyModels;
import dev.starsupply.api.InsuranceApi;
import dev.starsupply.api.TavernApi;
import dev.starsupply.api.TavernModels;
import dev.starsupply.api.event.SupplyOperationEvent;
import dev.starsupply.api.event.SupplySettlementEvent;
import dev.starsupply.api.event.SupplyConsumptionEvent;
import dev.starsupply.bukkit.item.InventoryEscrow;
import dev.starsupply.bukkit.item.InventoryEscrow.EscrowLot;
import dev.starsupply.bukkit.item.ResourceItemRegistry;
import dev.starsupply.bukkit.ui.SupplyUi;
import dev.starsupply.bukkit.ui.SupplyUi.Entry;
import dev.starsupply.bukkit.ui.SupplyUi.Section;
import dev.starsupply.integration.CoreGateway.PaymentResult;
import dev.starsupply.integration.StarCoreGateway;
import dev.starsupply.integration.TavernBridge;
import dev.starsupply.route.RoutePlan;
import dev.starsupply.service.Logistics;
import dev.starsupply.service.CampaignRules;
import dev.starsupply.service.CaravanInsurance;
import dev.starsupply.service.TavernService;
import dev.starsupply.storage.SupplyStore;
import dev.starsupply.storage.InsuranceStore;
import dev.starsupply.storage.TavernStore;
import dev.starsupply.storage.SupplyStore.Lot;
import dev.starsupply.storage.SupplyStore.Transfer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point. Entity operations never run on the SQL worker. */
public final class StarSupplyPlugin extends JavaPlugin implements Listener, SupplyUi.Actions, SupplyApi, TavernApi, InsuranceApi {
    private final Set<UUID> inventoryBusy = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean maintenanceQueued = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean campaignReady = new AtomicBoolean();
    private final AtomicLong lifecycle = new AtomicLong();
    private volatile Map<UUID, SupplyStatus> armyStatus = Map.of();
    private volatile long armyStatusAt;
    private volatile List<Warehouse> campaignFronts = List.of();
    private ThreadPoolExecutor worker;
    private ScheduledTask periodic;
    private ScheduledTask campaignSampling;
    private SupplyStore store;
    private StarCoreGateway core;
    private Logistics logistics;
    private ResourceItemRegistry items;
    private InventoryEscrow inventory;
    private TavernStore tavernStore;
    private TavernService taverns;
    private TavernBridge tavernBridge;
    private InsuranceStore insuranceStore;
    private CaravanInsurance insurance;
    private SupplyUi ui;
    private Runnable unregisterCombat;

    @Override
    public void onEnable() {
        try {
            require(worker == null || worker.isTerminated(), "上一轮数据库操作尚未停止，请等待后重新启用插件");
            long generation = lifecycle.incrementAndGet();
            campaignReady.set(false);
            saveDefaultConfig();
            Files.createDirectories(getDataFolder().toPath());
            String cap = getConfig().getString("max-order-payment", "100000.00");
            BigDecimal maxPayment = new BigDecimal(cap);
            require(maxPayment.signum() > 0 && maxPayment.scale() <= 2
                    && maxPayment.compareTo(new BigDecimal("100000000.00")) <= 0, "max-order-payment 无效");
            Map<ResourceType, BigDecimal> marketBasePrices = marketBasePrices();
            core = new StarCoreGateway(
                    logisticsBonuses("policy-logistics-bonuses", Map.of("war_economy", 0.10), 0.5),
                    logisticsBonuses("policy-combat-bonuses", Map.of("military_drill", 0.08,
                            "war_economy", 0.05), 0.10),
                    logisticsBonuses("technology-logistics-bonuses", Map.of("railways", 0.50), 1.5));
            store = new SupplyStore(getDataFolder().toPath().resolve("supply.db"));
            logistics = new Logistics(store, core,
                    number("warehouse-interaction-radius", 8, 1, 32),
                    number("transport-blocks-per-second", 10, 0.25, 1000),
                    (long) number("transport-timeout-seconds", 3600, 10, 86_400) * 1000,
                    (long) number("supply-interval-seconds", 300, 5, 86_400) * 1000,
                    (int) number("max-purchase-items", 256, 1, 1024), maxPayment, marketBasePrices,
                    (long) number("market-buffer-items", 16, 1, 1_000_000));
            logistics.configureCampaigns(new CampaignRules.Config(
                    number("campaign.winter-food-extra", 0.25, 0, 0.50),
                    number("campaign.winter-medical-extra", 0.10, 0, 0.50),
                    number("campaign.siege-maximum-extra", 0.50, 0, 0.50),
                    (long) number("campaign.siege-ramp-seconds", 1800, 60, 86_400) * 1000,
                    (long) number("campaign.siege-continuity-gap-seconds", 60, 15, 300) * 1000));
            List<String> exportControls = getConfig().getStringList("wartime-export-controls");
            if (!getConfig().contains("wartime-export-controls")) exportControls = List.of("EQUIPMENT", "STRATEGIC");
            logistics.configureWartimeExports(exportControls.stream()
                    .map(value -> ResourceType.valueOf(value.toUpperCase(Locale.ROOT)))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            core.configureCampaigns(number("campaign.siege-radius", 128, 16, 512));
            logistics.configureFrontlines(number("army-frontline-radius", 256, 32, 2048), consumption -> {
                try {
                    getServer().getPluginManager().callEvent(new SupplyConsumptionEvent(consumption));
                } catch (RuntimeException failure) {
                    getLogger().log(Level.WARNING, "补给已消费，事件通知失败 " + consumption.settlementId(), failure);
                }
            });
            campaignFronts = store.fronts();
            tavernStore = new TavernStore(getDataFolder().toPath().resolve("tavern.db"));
            taverns = new TavernService(tavernStore, store, logistics, core);
            taverns.configure(number("tavern.observation-radius", 32, 1, 32),
                    number("tavern.interaction-radius", 8, 1, 16),
                    (long) number("tavern.report-ttl-seconds", 900, 60, 900) * 1000,
                    getConfig().getBoolean("tavern.require-authentic-beverage", false));
            tavernBridge = TavernBridge.bind(this);
            insuranceStore = new InsuranceStore(getDataFolder().toPath().resolve("insurance.db"));
            insurance = new CaravanInsurance(insuranceStore, core.insuranceEconomy(getLogger()),
                    BigDecimal.valueOf(number("insurance.loading-rate", 0.10, 0, 0.50)),
                    new BigDecimal(getConfig().getString("insurance.minimum-premium", "1.00")));
            items = new ResourceItemRegistry(this);
            items.load(getConfig());
            inventory = new InventoryEscrow(this, items);
            worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), runnable -> {
                Thread thread = new Thread(runnable, "StarSupply-SQL");
                thread.setDaemon(true);
                return thread;
            }) {
                @Override
                protected void terminated() {
                    // Close after every already-accepted SQL operation, without racing the connection.
                    closeStore();
                    super.terminated();
                }
            };
            unregisterCombat = core.registerArmyCombatModifier(id -> {
                SupplyStatus status = armyStatus.get(id);
                return status == null || now() - armyStatusAt > 15_000 ? 1.0 : status.combatMultiplier();
            });
            ui = new SupplyUi(this, this);
            var command = Objects.requireNonNull(getCommand("supply"));
            command.setExecutor(ui);
            command.setTabCompleter(ui);
            getServer().getPluginManager().registerEvents(ui, this);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getServicesManager().register(SupplyApi.class, this, this, ServicePriority.Normal);
            getServer().getServicesManager().register(TavernApi.class, this, this, ServicePriority.Normal);
            getServer().getServicesManager().register(InsuranceApi.class, this, this, ServicePriority.Normal);
            accepting.set(true);
            StarCoreGateway campaignSource = core;
            campaignSampling = getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                if (!accepting.get() || lifecycle.get() != generation) return;
                try {
                    campaignSource.captureCampaigns(campaignFronts, now());
                    if (accepting.get() && lifecycle.get() == generation) campaignReady.set(true);
                } catch (RuntimeException failure) {
                    getLogger().log(Level.WARNING, "StarCore 季节/围城采样失败，过期后恢复正常消耗", failure);
                }
            }, 1, 100);
            periodic = getServer().getAsyncScheduler().runAtFixedRate(this, tick -> maintenance(),
                    1, 1, TimeUnit.SECONDS);
            for (Player player : Bukkit.getOnlinePlayers()) recoverOnJoin(player);
            getLogger().info("StarSupply 已启用，数据库 supply.db");
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "无法启用 StarSupply，请检查配置及 StarCore 版本", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        accepting.set(false);
        lifecycle.incrementAndGet();
        campaignReady.set(false);
        if (periodic != null) periodic.cancel();
        if (campaignSampling != null) campaignSampling.cancel();
        getServer().getServicesManager().unregisterAll(this);
        if (unregisterCombat != null) unregisterCombat.run();
        armyStatus = Map.of();
        campaignFronts = List.of();
        if (worker != null) {
            // Drain accepted transactions; terminated() closes SQLite exactly once.
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("StarSupply 数据库仍在完成已接收的操作，将在后台线程停止后关闭");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } else {
            closeStore();
        }
    }

    private void closeStore() {
        for (AutoCloseable database : new AutoCloseable[]{insuranceStore, tavernStore, store}) {
            if (database == null) continue;
            try {
                database.close();
            } catch (Exception failure) {
                getLogger().log(Level.WARNING, "无法关闭 StarSupply 数据库", failure);
            }
        }
    }

    @EventHandler
    public void joined(PlayerJoinEvent event) {
        recoverOnJoin(event.getPlayer());
    }

    private void recoverOnJoin(Player player) {
        entity(player, () -> {
            inventoryTask(player, () -> recoverPending(player)).whenComplete((message, failure) -> {
                if (message != null && failure == null) tell(player, message);
                if (failure != null) problem(player, failure);
            });
            return null;
        });
    }

    private void maintenance() {
        if (!accepting.get() || !campaignReady.get() || !maintenanceQueued.compareAndSet(false, true)) return;
        sql(() -> {
            long time = now();
            store.expire(time);
            for (Settlement settlement : store.pendingSettlements(time)) settle(settlement);
            insurance.resume(time);
            for (InsuranceApi.Policy policy : insuranceStore.active()) syncInsurance(policy.shipmentId(), time);
            refreshArmies(true);
            return null;
        }).whenComplete((ignored, failure) -> {
            maintenanceQueued.set(false);
            if (failure != null && accepting.get()) {
                getLogger().log(Level.WARNING, "StarSupply 后台轮询失败", cause(failure));
            }
        });
    }

    private void settle(Settlement item) throws Exception {
        PaymentResult result;
        try {
            Optional<SupplyModels.TaxAssessment> tax = store.taxAssessment(item.id());
            result = tax.isPresent()
                    ? core.pay(item.id(), item.nationId(), item.playerId(), item.amount(), tax.get(),
                    this::fundTariff)
                    : core.pay(item.id(), item.nationId(), item.playerId(), item.amount());
        } catch (RuntimeException ex) {
            // The gateway uses durable per-leg records. Retry through that same authority key.
            store.retryLater(item.id(), now() + 10_000);
            getLogger().log(Level.WARNING, "结算将在原编号下重试 " + item.id(), ex);
            return;
        }
        if (result == PaymentResult.RETRY) {
            store.retryLater(item.id(), now() + 5_000);
            return;
        }
        store.finishSettlement(item.id(), result.name(), now(), false).ifPresent(this::publish);
    }

    private boolean fundTariff(SupplyModels.TaxAssessment assessment) {
        try {
            Optional<SupplyStore.TaxState> current = store.taxState(assessment.settlementId());
            if (current.filter(state -> state == SupplyStore.TaxState.FUNDED).isPresent()) return true;
            if (current.filter(state -> state == SupplyStore.TaxState.REVIEW).isPresent()) return false;
            if (current.filter(state -> state == SupplyStore.TaxState.FUNDING).isPresent()) {
                store.finishTaxFunding(assessment.settlementId(), false, now());
                return false;
            }
            if (!store.beginTaxFunding(assessment.settlementId(), now())) return false;
            boolean funded;
            try {
                funded = core.fundTariff(assessment);
            } catch (RuntimeException uncertain) {
                store.finishTaxFunding(assessment.settlementId(), false, now());
                getLogger().log(Level.SEVERE, "StarCore tariff outcome needs reconciliation "
                        + assessment.settlementId(), uncertain);
                return false;
            }
            store.finishTaxFunding(assessment.settlementId(), funded, now());
            return funded;
        } catch (Exception uncertain) {
            getLogger().log(Level.SEVERE, "StarCore tariff outcome needs reconciliation "
                    + assessment.settlementId(), uncertain);
            return false;
        }
    }

    private void refreshArmies(boolean consume) throws Exception {
        long time = now();
        campaignFronts = store.fronts();
        if (!campaignReady.get()) return;
        armyStatus = logistics.computeArmySupply(time, consume);
        armyStatusAt = time;
    }

    private void publish(Settlement settlement) {
        if (settlement.outcome().equals("REVIEW")) {
            getLogger().warning("结算需要核对 " + settlement.id() + "，订单 " + settlement.orderId());
        } else {
            getLogger().info("结算 " + settlement.id() + " " + settlement.kind() + " " + settlement.outcome());
        }
        try {
            getServer().getPluginManager().callEvent(new SupplySettlementEvent(settlement));
        } catch (RuntimeException failure) {
            getLogger().log(Level.WARNING, "结算已保存，通知发送失败 " + settlement.id(), failure);
        }
    }

    @Override
    public void dispatch(Player player, String command, String[] args) {
        try {
            require(player.hasPermission("starsupply.use"), "没有使用星际后勤的权限");
            var actor = player.getUniqueId();
            CompletionStage<String> action = switch (command) {
                case "depot", "front" -> {
                    permission(player, "starsupply.manage");
                    before(player, Operation.WAREHOUSE_CREATE, null);
                    Position where = position(player);
                    yield sql(() -> {
                        UUID army = command.equals("front") ? resolveOwnArmy(actor, args[1]) : null;
                        Warehouse warehouse = logistics.createWarehouse(actor, args[0], where, army);
                        refreshArmies(false);
                        return "已登记 " + (army == null ? "仓库 " : "前线站 ") + warehouse.id();
                    });
                }
                case "route" -> {
                    permission(player, "starsupply.manage");
                    before(player, Operation.ROUTE_UPDATE, null);
                    double efficiency = Double.parseDouble(args[3]);
                    UUID line = args.length == 5 ? id(args[4]) : null;
                    yield sql(() -> "已登记道路 " + logistics.createRoad(actor, args[0], args[1], args[2],
                            efficiency, line).id());
                }
                case "block" -> {
                    permission(player, "starsupply.manage");
                    before(player, Operation.ROUTE_UPDATE, null);
                    require(List.of("true", "false").contains(args[1]), "封锁状态须为 true 或 false");
                    yield sql(() -> {
                        logistics.blockRoad(actor, args[0], Boolean.parseBoolean(args[1]));
                        return "已更新道路 " + args[0];
                    });
                }
                case "buy" -> {
                    permission(player, "starsupply.manage");
                    ResourceType resource = ResourceType.valueOf(args[1].toUpperCase(Locale.ROOT));
                    int itemCount = Integer.parseInt(args[2]);
                    if (args.length == 3 || args[3].equalsIgnoreCase("market")) {
                        yield createMarketPurchase(player, args[0], resource, itemCount)
                                .thenApply(order -> "采购单 " + order.id() + " / 市场总价 " + order.price());
                    }
                    yield createPurchase(player, args[0], resource, itemCount, parseMoney(args[3]))
                            .thenApply(order -> "采购单 " + order.id());
                }
                case "market" -> marketQuote(args[0], ResourceType.valueOf(args[1].toUpperCase(Locale.ROOT)))
                        .thenApply(quote -> quote.map(value -> value.warehouseId() + " / "
                                + value.resource().label() + " / 每件 " + value.unitPrice().toPlainString()
                                + " / 库存 " + value.stockItems() + " / 待购 " + value.demandItems())
                                .orElse("仓库不存在"));
                case "sell" -> sell(player, id(args[0]));
                case "ship" -> {
                    permission(player, "starsupply.manage");
                    yield createTransport(player, id(args[0]), args[1], parseMoney(args[2]))
                            .thenApply(shipment -> "运输单 " + shipment.id());
                }
                case "accept" -> acceptTransport(player, id(args[0])).thenApply(shipment ->
                        "已领取 " + shipment.id() + "。预计 " + Math.max(1, (shipment.readyAt() - now() + 999) / 1000)
                                + " 秒后到站可交付，线路 " + shipment.path() + " / 风险 "
                                + String.format(Locale.ROOT, "%.0f%%", shipment.routeRisk() * 100));
                case "escort" -> joinEscort(player, id(args[0])).thenApply(count -> "已加入商队护送队，当前护送者 "
                        + count + " / 4；路线风险降低，运输更快，遭袭失败时货物退回且不结算运费");
                case "deliver" -> deliverTransport(player, id(args[0]));
                case "cancel" -> {
                    UUID order = id(args[1]);
                    require(List.of("purchase", "shipment").contains(args[0]), "订单类型须为 purchase 或 shipment");
                    yield cancelOrder(player, order, args[0].equals("shipment"))
                            .thenApply(ignored -> "已取消 " + order);
                }
                case "recover" -> {
                    UUID transfer = args.length == 0 ? null : id(args[0]);
                    before(player, Operation.RETURN, transfer);
                    yield inventoryTask(player, () -> restoreOrRecover(player, transfer));
                }
                case "status" -> sql(() -> statusLine(actor, args.length == 0 ? null : args[0]));
                case "tavern" -> registerHub(player, args[0]).thenApply(hub -> "已登记酒馆集合点 " + hub.id()
                        + (hub.tavernVerified() ? " / 森罗酒品已验证" : ""));
                case "untavern" -> removeHub(player, args[0]).thenApply(ignored -> "已移除酒馆集合点 " + args[0]);
                case "scout" -> scoutRoute(player, args[0]).thenApply(report -> "侦察报告 " + report.id()
                        + " / " + (report.blocked() ? "线路截断" : "线路开放") + "；请到酒馆用 /supply intel "
                        + report.id() + " <集合点ID> 交换情报");
                case "intel" -> publishIntelligence(player, id(args[0]), args[1])
                        .thenApply(report -> "情报已发布至 " + report.publishedHub() + "，不会延长原有效期");
                case "board" -> tavernBoard(player, args[0]).thenCompose(board -> entity(player, () -> {
                    ui.present(player, Section.TAVERNS, boardEntries(board));
                    return null;
                }));
                case "insurance" -> insurance(id(args[0])).thenCompose(policy -> policy.isPresent()
                        ? CompletableFuture.completedFuture(insuranceText(policy.get()))
                        : insuranceQuote(player, id(args[0])).thenApply(quote -> "承运收入险 / 预付保费 " + quote.premium()
                        + " / 遭袭赔额 " + quote.claimAmount() + " / 风险上限 "
                        + String.format(Locale.ROOT, "%.0f%%", quote.maximumRisk() * 100)
                        + "；在起点执行 /supply insure " + quote.shipmentId() + " 投保；生效后退保不退保费"));
                case "insure" -> insure(player, id(args[0])).thenApply(StarSupplyPlugin::insuranceText);
                case "uninsure" -> {
                    UUID shipment = id(args[0]);
                    before(player, Operation.INSURANCE_CANCEL, shipment);
                    boolean managementPermission = player.hasPermission("starsupply.manage");
                    yield sql(() -> {
                        Shipment current = logistics.getShipment(shipment);
                        CaravanInsurance.requireCancellableCargo(current);
                        boolean manager = managementPermission && core.canManage(actor, current.nationId());
                        return insuranceText(insurance.cancel(current, actor, manager, now()));
                    });
                }
                case "reconcile" -> {
                    permission(player, "starsupply.admin");
                    UUID settlement = id(args[0]);
                    before(player, Operation.RECONCILE, settlement);
                    require(List.of("paid", "unpaid").contains(args[1]), "核对结果须为 paid 或 unpaid");
                    yield sql(() -> reconcile(actor, settlement, args[1].equals("paid")));
                }
                case "reconcile-tax" -> {
                    permission(player, "starsupply.admin");
                    UUID settlement = id(args[0]);
                    before(player, Operation.RECONCILE, settlement);
                    require(List.of("funded", "unfunded").contains(args[1]),
                            "关税核对结果须为 funded 或 unfunded");
                    yield sql(() -> reconcileTax(actor, settlement, args[1].equals("funded")));
                }
                case "reconcile-insurance" -> {
                    permission(player, "starsupply.admin");
                    UUID policy = id(args[0]);
                    before(player, Operation.RECONCILE, policy);
                    InsuranceApi.MoneyLeg leg = InsuranceApi.MoneyLeg.valueOf(args[1].toUpperCase(Locale.ROOT));
                    require(List.of("applied", "untouched").contains(args[2]), "核对结果须为 applied 或 untouched");
                    yield sql(() -> {
                        InsuranceApi.Policy result = insurance.reconcile(policy, leg, args[2].equals("applied"), now());
                        getLogger().warning("保险人工核对 " + policy + " / " + leg + " / " + args[2] + " / 管理员 " + actor);
                        return insuranceText(result);
                    });
                }
                case "reconcile-inventory" -> {
                    permission(player, "starsupply.admin");
                    UUID receipt = id(args[0]);
                    before(player, Operation.RECONCILE, receipt);
                    require(List.of("applied", "untouched").contains(args[1]), "核对结果须为 applied 或 untouched");
                    yield sql(() -> {
                        Transfer transfer = store.receiptTransfer(receipt).orElseThrow(() ->
                                new IllegalArgumentException("库存回执不存在"));
                        require(!inventoryBusy.contains(transfer.player()), "玩家库存操作仍在进行");
                        store.reconcileInventory(receipt, args[1].equals("applied"), now());
                        getLogger().warning("库存人工核对 " + receipt + " " + args[1] + "，管理员 " + actor);
                        return "库存核对已记录，请玩家重进或使用 /supply recover";
                    });
                }
                default -> throw new IllegalArgumentException("未知后勤命令");
            };
            action.whenComplete((result, failure) -> {
                if (failure != null) problem(player, failure);
                else if (result != null) tell(player, result);
            });
        } catch (RuntimeException failure) {
            problem(player, failure);
        }
    }

    @Override
    public CompletionStage<Purchase> createPurchase(Player actor, String depot, ResourceType resource,
                                                    int count, BigDecimal price) {
        permission(actor, "starsupply.manage");
        before(actor, Operation.PURCHASE_CREATE, null);
        UUID actorId = actor.getUniqueId();
        return sql(() -> logistics.createPurchase(actorId, depot, resource, count, price, now()));
    }

    @Override
    public CompletionStage<Purchase> createMarketPurchase(Player actor, String depot, ResourceType resource,
                                                          int count) {
        permission(actor, "starsupply.manage");
        before(actor, Operation.PURCHASE_CREATE, null);
        UUID actorId = actor.getUniqueId();
        return sql(() -> logistics.createMarketPurchase(actorId, depot, resource, count, now()));
    }

    @Override
    public CompletionStage<Optional<MarketQuote>> marketQuote(String depot, ResourceType resource) {
        return sql(() -> {
            try {
                return Optional.of(logistics.marketQuote(depot, resource, now()));
            } catch (IllegalArgumentException absentDepot) {
                return Optional.empty();
            }
        });
    }

    @Override
    public CompletionStage<Shipment> createTransport(Player actor, UUID purchase, String front, BigDecimal reward) {
        permission(actor, "starsupply.manage");
        before(actor, Operation.TRANSPORT_CREATE, purchase);
        UUID actorId = actor.getUniqueId();
        return sql(() -> logistics.createShipment(actorId, purchase, front, reward, now()));
    }

    @Override
    public CompletionStage<Shipment> acceptTransport(Player actor, UUID shipment) {
        before(actor, Operation.ACCEPT, shipment);
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        return sql(() -> {
            if (insurance.policy(shipment).isPresent()) {
                insurance.expireUnstarted(logistics.getShipment(shipment), now(), 300_000);
            }
            insurance.requireCarrier(shipment, actorId);
            if (insurance.policy(shipment).filter(policy -> !policy.terminal()).isPresent()) {
                insurance.requireRisk(shipment, logistics.transportRisk(shipment, actorId));
            }
            return logistics.accept(actorId, where, shipment, now());
        });
    }

    @Override
    public CompletionStage<Integer> joinEscort(Player actor, UUID shipment) {
        before(actor, Operation.ESCORT, shipment);
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        return sql(() -> logistics.joinEscort(actorId, where, shipment, now()).size());
    }

    @Override
    public CompletionStage<String> deliverTransport(Player actor, UUID shipment) {
        before(actor, Operation.DELIVER, shipment);
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        return sql(() -> {
            insurance.requireCarrier(shipment, actorId);
            Shipment current = logistics.getShipment(shipment);
            if (current.state() == TransportState.IN_TRANSIT
                    && insurance.policy(shipment).filter(policy -> !policy.terminal()).isPresent()) {
                insurance.requireRisk(shipment, logistics.transportRisk(shipment));
            }
            String result = logistics.deliver(actorId, where, shipment, now());
            syncInsurance(shipment, now());
            return result;
        });
    }

    @Override
    public CompletionStage<Void> cancelOrder(Player actor, UUID order, boolean shipment) {
        before(actor, Operation.CANCEL, order);
        UUID actorId = actor.getUniqueId();
        boolean managementPermission = actor.hasPermission("starsupply.manage");
        return sql(() -> {
            if (shipment) {
                require(managementPermission || actorId.equals(logistics.getShipment(order).carrier()),
                        "没有取消其他玩家运输单的权限");
                Shipment current = logistics.getShipment(order);
                CaravanInsurance.requireCancellableCargo(current);
                boolean manager = managementPermission && core.canManage(actorId, current.nationId());
                if (!actorId.equals(current.carrier())) require(manager, "只有本国军需官可以取消此运输");
                Optional<InsuranceApi.Policy> policy = insurance.policy(order);
                if (policy.isPresent() && !policy.get().terminal()) {
                    require(!policy.get().blocksTransport(), "保险待结算或待核验，不能取消运输");
                    InsuranceApi.Policy closed = insurance.cancel(current, actorId, manager, now());
                    require(closed.terminal(), "保险准备金正在释放，请稍后重试取消运输");
                }
                logistics.cancelShipment(actorId, order);
                syncInsurance(order, now());
            } else {
                require(managementPermission, "没有取消采购单的权限");
                logistics.cancelPurchase(actorId, order);
            }
            return null;
        });
    }

    @Override public CompletionStage<InsuranceApi.Quote> insuranceQuote(UUID shipment) {
        return sql(() -> insurance.quote(logistics.getShipment(shipment), logistics.transportRisk(shipment), now()));
    }

    public CompletionStage<InsuranceApi.Quote> insuranceQuote(Player actor, UUID shipment) {
        require(Bukkit.isOwnedByCurrentRegion(actor), "请在玩家的实体调度线程调用");
        permission(actor, "starsupply.use");
        UUID actorId = actor.getUniqueId();
        return sql(() -> insurance.quote(logistics.getShipment(shipment), logistics.transportRisk(shipment, actorId), now()));
    }

    @Override public CompletionStage<InsuranceApi.Policy> insure(Player actor, UUID shipment) {
        before(actor, Operation.INSURE, shipment);
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        double interactionRadius = number("warehouse-interaction-radius", 8, 1, 32);
        return sql(() -> {
            Shipment current = logistics.getShipment(shipment);
            require(current.state() == TransportState.OPEN, "只能在领取运输之前投保");
            require(core.nationExists(current.nationId()) && core.nationOf(actorId)
                    .filter(current.nationId()::equals).isPresent(), "国家承保的收入险仅供本国承运者投保");
            Warehouse origin = store.warehouse(current.origin()).orElseThrow();
            logistics.requireControlled(origin);
            require(where.within(origin.position(), interactionRadius),
                    "请到起点仓库投保");
            return insurance.buy(current, actorId, store.hasFailedPurchase(current.purchaseId()),
                    logistics.transportRisk(shipment, actorId), now());
        });
    }

    @Override public CompletionStage<Optional<InsuranceApi.Policy>> insurance(UUID shipment) {
        return sql(() -> insurance.policy(shipment));
    }

    private void syncInsurance(UUID shipment, long time) throws Exception {
        Optional<InsuranceApi.Policy> policy = insurance.policy(shipment);
        if (policy.isEmpty() || policy.get().terminal() || policy.get().state() != InsuranceApi.State.ACTIVE) return;
        Shipment current = logistics.getShipment(shipment);
        if (current.state() == TransportState.OPEN) {
            insurance.expireUnstarted(current, time, 300_000);
            return;
        }
        if (current.state() != TransportState.FAILED && current.state() != TransportState.DELIVERED
                && current.state() != TransportState.CANCELLED) return;
        var failure = store.shipmentFailure(shipment);
        boolean risk = failure.filter(value -> value.reason().equals("RISK")).isPresent();
        long outcomeAt = failure.map(SupplyStore.ShipmentFailure::occurredAt).orElse(time);
        insurance.complete(current, risk, outcomeAt, time);
    }

    @Override public CompletionStage<TavernModels.Hub> registerHub(Player actor, String hub) {
        permission(actor, "starsupply.manage");
        before(actor, Operation.TAVERN_CREATE, null);
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        boolean verified = tavernBridge != null && tavernBridge.authenticBeverage(
                actor.getInventory().getItemInMainHand(), (int) number("tavern.minimum-brew-level", 1, 0, 6));
        return sql(() -> taverns.registerHub(actorId, where, hub, verified, now()));
    }

    @Override public CompletionStage<Void> removeHub(Player actor, String hub) {
        permission(actor, "starsupply.manage");
        before(actor, Operation.TAVERN_REMOVE, null);
        UUID actorId = actor.getUniqueId();
        return sql(() -> { taverns.removeHub(actorId, hub); return null; });
    }

    @Override public CompletionStage<TavernModels.ScoutReport> scoutRoute(Player actor, String road) {
        before(actor, Operation.SCOUT, null);
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        return sql(() -> taverns.scoutRoute(actorId, where, road, now()));
    }

    @Override public CompletionStage<TavernModels.ScoutReport> publishIntelligence(Player actor, UUID report, String hub) {
        before(actor, Operation.INTELLIGENCE_PUBLISH, report);
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        return sql(() -> taverns.publishIntelligence(actorId, where, report, hub, now()));
    }

    @Override public CompletionStage<TavernModels.Board> tavernBoard(Player actor, String hub) {
        require(Bukkit.isOwnedByCurrentRegion(actor), "请在玩家的实体调度线程调用");
        permission(actor, "starsupply.use");
        UUID actorId = actor.getUniqueId();
        Position where = position(actor);
        return sql(() -> taverns.board(actorId, where, hub, now()));
    }

    @Override public CompletionStage<List<TavernModels.Hub>> tavernHubs(Player actor) {
        permission(actor, "starsupply.use");
        require(Bukkit.isOwnedByCurrentRegion(actor), "请在玩家的实体调度线程调用");
        UUID actorId = actor.getUniqueId();
        return sql(() -> taverns.hubs(actorId));
    }

    private static String insuranceText(InsuranceApi.Policy policy) {
        return "保单 " + policy.id() + " / " + policy.state() + (policy.review() ? " / 待人工核验" : "")
                + " / 保费 " + policy.premium() + " / 赔额 " + policy.claimAmount()
                + " / 经济阶段 " + policy.money();
    }

    private List<Entry> boardEntries(TavernModels.Board board) {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(Material.CAMPFIRE, "商人集结 · " + board.hub().id(),
                List.of("本国物资合同与已发布道路情报", "情报为观察时快照，超过时限自动失效")));
        for (TavernModels.ScoutReport report : board.reports()) {
            entries.add(new Entry(Material.MAP, "侦察 · " + report.roadId(), List.of(
                    report.origin() + " → " + report.target(), report.blocked() ? "观察时：截断" : "观察时：开放",
                    String.format(Locale.ROOT, "效率 %.2f / 敌区 %.0f%%", report.roadEfficiency(), report.hostileRisk() * 100),
                    "观察于 " + java.time.Instant.ofEpochMilli(report.observedAt()),
                    "有效至 " + java.time.Instant.ofEpochMilli(report.expiresAt()))));
        }
        for (Purchase purchase : board.purchases()) {
            entries.add(new Entry(Material.WRITABLE_BOOK, "采购 · " + purchase.resource().label(), List.of(
                    purchase.id().toString(), "仓库 " + purchase.warehouseId(),
                    "数量 " + purchase.itemCount() + " / 总价 " + purchase.price(), "到指定仓库提交真实物资")));
        }
        for (Shipment shipment : board.shipments()) {
            entries.add(new Entry(Material.COMPASS, "招募承运与护送", List.of(shipment.id().toString(),
                    shipment.origin() + " → " + shipment.target(), "运费 " + shipment.reward(), "到起点领取运输或加入护送")));
        }
        return List.copyOf(entries);
    }

    @Override
    public CompletionStage<String> sell(Player player, UUID purchaseId) {
        before(player, Operation.SELL, purchaseId);
        UUID seller = player.getUniqueId();
        Position where = position(player);
        return inventoryTask(player, () -> recoverPending(player).thenCompose(result ->
                sql(() -> logistics.openSale(seller, where, purchaseId))).thenCompose(authorization ->
                entity(player, () -> {
                    require(position(player).within(where, 2), "请在出库操作完成前留在仓库");
                    require(inventory.receipt(player).isEmpty(), "有尚未清理的库存回执");
                    return inventory.plan(player, authorization.purchase().resource(),
                            authorization.purchase().itemCount());
                }).thenApply(plan -> new AuthorizedPlan(authorization, plan))).thenCompose(authorizedPlan -> {
                    var authorization = authorizedPlan.authorization();
                    var plan = authorizedPlan.plan();
                    UUID transfer = UUID.randomUUID();
                    List<Lot> lots = plan.lots().stream().map(lot -> new Lot(lot.itemId(), lot.quality(),
                            lot.qualityRatio(), lot.itemCount(), lot.units(), lot.itemBytes())).toList();
                    long tradeUnits = logistics.validateSaleUnits(authorization, lots);
                    long recordedTradeUnits = authorization.agreementId() == null ? 0 : tradeUnits;
                    return sql(() -> {
                        var current = logistics.openSale(seller, where, purchaseId);
                        require(Objects.equals(current.agreementId(), authorization.agreementId()),
                                "StarCore 贸易协定在出库期间发生变化，请重试");
                        require(current.agreementLimit() == authorization.agreementLimit()
                                        && Objects.equals(current.exporterNation(), authorization.exporterNation())
                                        && (current.agreementUnitPrice() == null
                                        ? authorization.agreementUnitPrice() == null
                                        : current.agreementUnitPrice().compareTo(authorization.agreementUnitPrice()) == 0),
                                "StarCore 贸易协定条款在出库期间发生变化，请重试");
                        SupplyModels.TaxAssessment tax = authorization.agreementId() == null ? null
                                : core.quoteTariff(transfer, authorization.purchase().nationId(), seller,
                                authorization.purchase().price());
                        if (tax != null && tax.taxAmount().signum() == 0) tax = null;
                        store.prepareTransfer(transfer, purchaseId, seller, authorization.exporterNation(),
                                authorization.agreementId(), authorization.agreementLimit(), recordedTradeUnits,
                                tax,
                                plan.beforeHash(), plan.afterHash(), lots, now());
                        return transfer;
                    }).thenCompose(receipt -> entity(player, () -> {
                        if (!player.hasPermission("starsupply.use") || !position(player).within(where, 2)) return false;
                        return inventory.apply(player, receipt, plan);
                    })).thenCompose(applied -> sql(() -> {
                        if (applied) store.confirmTaken(transfer, now());
                        else store.abandonTransfer(transfer);
                        return applied;
                    })).thenCompose(applied -> {
                        if (!applied) return CompletableFuture.failedFuture(
                                new IllegalArgumentException("背包或位置已变化，库存未扣除"));
                        return clear(player, transfer, "军需物资已托管，正在通过 StarCore 付款。编号 " + transfer);
                    });
                }));
    }

    private CompletionStage<String> restoreOrRecover(Player player, UUID requested) {
        UUID actor = player.getUniqueId();
        return recoverPending(player).thenCompose(recovered -> {
            if (recovered != null && requested == null) return CompletableFuture.completedFuture(recovered);
            return sql(() -> {
                List<Transfer> eligible = store.recoverable(actor);
                if (requested != null) {
                    eligible = eligible.stream().filter(item -> item.id().equals(requested)).toList();
                    require(!eligible.isEmpty(), "找不到这笔可恢复库存");
                }
                if (eligible.isEmpty()) return null;
                if (eligible.size() > 1 && requested == null) {
                    throw new IllegalArgumentException("请指定 /supply recover <编号>。待恢复 "
                            + eligible.stream().map(item -> item.id().toString()).reduce((a, b) -> a + " / " + b)
                            .orElseThrow());
                }
                Transfer record = eligible.getFirst();
                require(record.state() == TransferState.REJECTED, "库存或款项仍待管理员核对 " + record.id());
                List<EscrowLot> lots = store.lots(record.id()).stream().map(lot -> new EscrowLot(lot.itemId(),
                        lot.quality(), lot.qualityRatio(), lot.count(), lot.units(), lot.payload())).toList();
                return new Recovery(record, lots);
            }).thenCompose(recovery -> {
                if (recovery == null) return CompletableFuture.completedFuture("当前没有待退还物资");
                UUID transfer = recovery.transfer().id();
                UUID receipt = UUID.randomUUID();
                return entity(player, () -> {
                    require(inventory.receipt(player).isEmpty(), "有尚未清理的库存回执");
                    return inventory.planRestore(player, recovery.transfer().resource(), recovery.lots());
                }).thenCompose(plan -> sql(() -> {
                    store.beginReturn(transfer, receipt, plan.beforeHash(), plan.afterHash());
                    return plan;
                })).thenCompose(plan -> entity(player, () -> {
                    if (!player.hasPermission("starsupply.use")) return false;
                    return inventory.apply(player, receipt, plan);
                })).thenCompose(applied -> sql(() -> {
                    store.finishReturn(transfer, receipt, applied);
                    return applied;
                })).thenCompose(applied -> {
                    if (!applied) return CompletableFuture.failedFuture(new IllegalArgumentException("背包已变化，请重试"));
                    return clear(player, receipt, "实物已退回背包");
                });
            });
        });
    }

    private CompletionStage<String> recoverPending(Player player) {
        return entity(player, () -> new InventoryObservation(player.getUniqueId(), inventory.receipt(player).orElse(null),
                inventory.hash(player)))
                .thenCompose(observation -> sql(() -> {
                    if (observation.receipt() != null) {
                        Transfer transfer = store.receiptTransfer(observation.receipt()).orElseThrow(() ->
                                new IllegalArgumentException("发现外部或未知的玩家库存回执 " + observation.receipt()));
                        require(transfer.player().equals(observation.player()), "库存回执的玩家不匹配");
                        return transfer;
                    }
                    var pending = store.pendingInventory(observation.player());
                    if (pending.isPresent()) {
                        Transfer item = pending.get();
                        // The complete storage-slot fingerprint is persisted before SQL prepares a debit.
                        // When it still matches, the player save cannot contain the planned change, so
                        // a crash before the apply step is safely abortable/retryable.
                        if (item.beforeHash().equals(observation.inventoryHash())) {
                            if (item.state() == TransferState.PREPARED) {
                                store.abandonTransfer(item.id());
                                return null;
                            }
                            if (item.state() == TransferState.RETURNING) {
                                store.finishReturn(item.id(), item.returnId(), false);
                                return null;
                            }
                        }
                        store.inventoryReview(item.id(), "missing durable player receipt after interruption");
                        throw new IllegalArgumentException("库存操作需要管理员核对 "
                                + (item.returnId() == null ? item.id() : item.returnId()));
                    }
                    return null;
                }).thenCompose(transfer -> {
                    if (transfer == null) return CompletableFuture.completedFuture(null);
                    UUID receipt = observation.receipt();
                    boolean taking = transfer.state() == TransferState.PREPARED && receipt.equals(transfer.id());
                    boolean returning = transfer.state() == TransferState.RETURNING && receipt.equals(transfer.returnId());
                    if (taking || returning) {
                        return entity(player, () -> {
                            if (!inventory.verifyReceipt(player, receipt)) inventory.persistReceipt(player, receipt);
                            return true;
                        }).thenCompose(verified -> sql(() -> {
                            if (taking) store.confirmTaken(transfer.id(), now());
                            else store.finishReturn(transfer.id(), receipt, true);
                            return null;
                        })).thenCompose(ignored -> clear(player, receipt, "已恢复库存操作 " + receipt));
                    }
                    require(transfer.state() != TransferState.REVIEW || transfer.note().equals("payment"),
                            "库存操作需要管理员核对 " + receipt);
                    require(transfer.state() != TransferState.PREPARED && transfer.state() != TransferState.RETURNING,
                            "库存回执与交易不一致 " + receipt);
                    return clear(player, receipt, "已清理完成的库存回执 " + receipt);
                }));
    }

    private CompletionStage<String> clear(Player player, UUID receipt, String message) {
        return entity(player, () -> {
            inventory.clearReceipt(player, receipt);
            return message;
        });
    }

    private CompletionStage<String> inventoryTask(Player player, Supplier<CompletionStage<String>> start) {
        UUID id = player.getUniqueId();
        if (!inventoryBusy.add(id)) return CompletableFuture.failedFuture(
                new IllegalArgumentException("背包操作正在处理，请稍后重试"));
        try {
            return start.get().whenComplete((message, failure) -> inventoryBusy.remove(id));
        } catch (RuntimeException failure) {
            inventoryBusy.remove(id);
            throw failure;
        }
    }

    private String reconcile(UUID actor, UUID id, boolean paid) throws Exception {
        Settlement item = store.settlement(id).orElseThrow(() -> new IllegalArgumentException("找不到结算编号"));
        require(item.outcome().equals("REVIEW"), "结算不在人工核对状态");
        boolean tariff = store.taxAssessment(id).isPresent();
        PaymentResult outcome = core.reconcilePayment(id, paid);
        if (outcome == PaymentResult.PAID || outcome == PaymentResult.REJECTED) {
            store.finishSettlement(id, outcome.name(), now(), true).ifPresent(this::publish);
            getLogger().warning("付款人工核对 " + id + " " + outcome + "，管理员 " + actor);
            return "人工核对已结算 " + outcome;
        }
        if (tariff && paid && outcome == PaymentResult.REVIEW) {
            return "卖家入账已确认；请核对 StarCore 关税，再执行 /supply reconcile-tax " + id + " <funded|unfunded>";
        }
        return "StarCore 尚未确认结果，保持待核对状态 " + id;
    }

    private String reconcileTax(UUID actor, UUID id, boolean alreadyFunded) throws Exception {
        Settlement item = store.settlement(id).orElseThrow(() -> new IllegalArgumentException("找不到结算编号"));
        require(item.outcome().equals("REVIEW"), "交易结算不在人工核对状态");
        SupplyModels.TaxAssessment tax = store.taxAssessment(id)
                .orElseThrow(() -> new IllegalArgumentException("该交易没有关税快照"));
        Optional<SupplyStore.TaxState> taxState = store.taxState(id);
        require(taxState.filter(state -> state == SupplyStore.TaxState.QUOTED
                        || state == SupplyStore.TaxState.FUNDING || state == SupplyStore.TaxState.REVIEW
                        || state == SupplyStore.TaxState.FUNDED).isPresent(),
                "关税不在人工核对状态");
        boolean confirmFunded = alreadyFunded || taxState.filter(state -> state == SupplyStore.TaxState.FUNDED).isPresent();
        if (!confirmFunded) {
            store.reconcileTaxFunding(id, alreadyFunded, now());
        }
        PaymentResult outcome = core.reconcileTariff(id, tax, confirmFunded, this::fundTariff);
        if (outcome == PaymentResult.PAID) {
            if (confirmFunded) store.reconcileTaxFunding(id, true, now());
            store.finishSettlement(id, "PAID", now(), true).ifPresent(this::publish);
            getLogger().warning("关税人工核对成功 " + id + "，管理员 " + actor
                    + (alreadyFunded ? "（已确认入账）" : "（确认未入账后已补缴）"));
            return "关税核对完成，采购已结算";
        }
        return "关税或卖家款仍待核对 " + id;
    }

    @Override
    public CompletionStage<List<Entry>> entries(Player player, Section section) {
        UUID actor = player.getUniqueId();
        return sql(() -> {
            if (section == Section.ORDERS) return orderEntries(actor);
            UUID nation = logistics.nation(actor);
            return switch (section) {
                case WAREHOUSES -> warehouseEntries(nation);
                case ARMY -> armyEntries(nation);
                case ROUTES -> logistics.graph(nation).edges().stream().map(edge -> new Entry(Material.MAP, edge.id(),
                        List.of(edge.from() + " → " + edge.to(), edge.blocked() ? "已截断" : "开放",
                                String.format(Locale.ROOT, "%.0f 格 / 效率 %.2f / 敌区 %.0f%%",
                                        edge.distance(), edge.roadEfficiency(), edge.hostileRisk() * 100)))).toList();
                case TAVERNS -> taverns.hubs(actor).stream().map(hub -> new Entry(Material.CAMPFIRE,
                        hub.id(), List.of(hub.position().world() + " " + (int) hub.position().x() + " "
                        + (int) hub.position().y() + " " + (int) hub.position().z(),
                        hub.tavernVerified() ? "森罗酒品验证的集合点" : "本国商人集合点", "到附近点击查看情报板"),
                        "board", List.of(hub.id()))).toList();
                default -> List.of();
            };
        });
    }

    private List<Entry> warehouseEntries(UUID nation) throws Exception {
        List<Entry> entries = new ArrayList<>();
        for (Warehouse warehouse : store.warehouses(nation)) {
            Stock stock = store.stock(warehouse.id());
            Position where = warehouse.position();
            List<String> lore = new ArrayList<>();
            lore.add(logistics.controlled(warehouse) ? "本国控制" : "领土已失守，库存保留、操作暂停");
            lore.add(String.format(Locale.ROOT, "%s %.0f %.0f %.0f", where.world(), where.x(), where.y(), where.z()));
            for (ResourceType type : ResourceType.values()) {
                lore.add(type.label() + " " + stock.available().getOrDefault(type, 0L)
                        + "（运输锁定 " + stock.reserved().getOrDefault(type, 0L) + "）");
                if (warehouse.kind() == WarehouseKind.DEPOT) {
                    var quote = logistics.marketQuote(warehouse.id(), type, now());
                    lore.add("市场参考价/件 " + quote.unitPrice().toPlainString());
                }
            }
            entries.add(new Entry(warehouse.kind() == WarehouseKind.FRONT ? Material.BARREL : Material.CHEST,
                    warehouse.id(), lore));
        }
        return List.copyOf(entries);
    }

    private List<Entry> orderEntries(UUID actor) throws Exception {
        Optional<UUID> membership = core.nationOf(actor);
        List<Entry> entries = new ArrayList<>();
        // Personal and national history remain available even after the order leaves the public board.
        Map<UUID, Purchase> purchases = new java.util.LinkedHashMap<>();
        for (Purchase order : store.playerPurchases(actor)) purchases.put(order.id(), order);
        if (membership.isPresent()) {
            for (Purchase order : store.nationPurchases(membership.get())) purchases.putIfAbsent(order.id(), order);
        }
        for (Purchase order : store.purchases()) purchases.putIfAbsent(order.id(), order);
        for (Purchase order : purchases.values()) {
            if (order.state() == PurchaseState.OPEN && membership.filter(other -> !other.equals(order.nationId())
                    && core.atWar(order.nationId(), other)).isPresent()) continue;
            boolean open = order.state() == PurchaseState.OPEN;
            List<String> lore = new ArrayList<>(List.of(order.id().toString(), "仓库 " + order.warehouseId(),
                    "数量 " + order.itemCount() + "，金额 " + order.price().toPlainString(),
                    open ? "点击后在指定仓库出售" : order.state().name()));
            if (order.settlementId() != null) {
                lore.add("结算 " + order.settlementId());
                Optional<SupplyModels.TaxAssessment> assessment = store.taxAssessment(order.settlementId());
                if (assessment.isPresent()) {
                    SupplyModels.TaxAssessment tax = assessment.get();
                    String taxState = store.taxState(order.settlementId()).map(Enum::name).orElse("未知");
                    lore.add("StarCore 进口关税 " + tax.taxAmount().toPlainString() + " / 卖家净额 "
                            + tax.netAmount().toPlainString() + " / 状态 " + taxState);
                }
            }
            entries.add(new Entry(Material.WRITABLE_BOOK, "采购 · " + order.resource().label(), lore,
                    open ? "sell" : null, open ? List.of(order.id().toString()) : List.of()));
        }
        Map<UUID, Shipment> shipments = new java.util.LinkedHashMap<>();
        for (Shipment shipment : store.shipments(actor)) shipments.put(shipment.id(), shipment);
        if (membership.isPresent()) {
            for (Shipment shipment : store.nationShipments(membership.get())) shipments.putIfAbsent(shipment.id(), shipment);
        }
        for (Shipment shipment : shipments.values()) {
            if (shipment.state() == TransportState.OPEN && membership.filter(other -> !other.equals(shipment.nationId())
                    && core.atWar(shipment.nationId(), other)).isPresent()) continue;
            String action = shipment.state() == TransportState.OPEN ? "accept"
                    : shipment.state() == TransportState.IN_TRANSIT && actor.equals(shipment.carrier()) ? "deliver" : null;
            List<String> lore = new ArrayList<>(List.of(shipment.id().toString(),
                    shipment.origin() + " → " + shipment.target(), shipment.state() + " / 运费 " + shipment.reward(),
                    "线路 " + shipment.path(), "风险 " + String.format(Locale.ROOT, "%.0f%%", shipment.routeRisk() * 100),
                    "护送者 " + store.escortCount(shipment.id()) + " / 4"));
            Optional<InsuranceApi.Policy> insured = insurance.policy(shipment.id());
            insured.ifPresent(policy -> {
                lore.add("收入险 " + policy.state() + (policy.review() ? " / 待人工核验" : ""));
                lore.add("保单 " + policy.id() + " / 保费 " + policy.premium() + " / 赔额 " + policy.claimAmount());
            });
            if (shipment.readyAt() > now() && shipment.state() == TransportState.IN_TRANSIT) {
                lore.add("预计剩余 " + (shipment.readyAt() - now() + 999) / 1000 + " 秒");
            }
            entries.add(new Entry(Material.COMPASS, "运输 · " + shipment.target(), lore, action,
                    action == null ? List.of() : List.of(shipment.id().toString())));
            if (shipment.state() == TransportState.OPEN && insured.isEmpty() && shipment.routeRisk() > 0) {
                entries.add(new Entry(Material.GOLD_NUGGET, "承运收入险报价", List.of(shipment.id().toString(),
                        "查看保费、赔额和风险上限", "生效后退保不退保费；只赔遭袭失去的运费"),
                        "insurance", List.of(shipment.id().toString())));
            }
            if (shipment.state() == TransportState.OPEN && membership.filter(shipment.nationId()::equals).isPresent()) {
                entries.add(new Entry(Material.SHIELD, "护送 · " + shipment.target(),
                        List.of(shipment.id().toString(), "在起点仓库加入护送", "最多 4 人；降低风险并缩短运输时间"),
                        "escort", List.of(shipment.id().toString())));
            }
        }
        return List.copyOf(entries);
    }

    private List<Entry> armyEntries(UUID nation) {
        List<Entry> entries = new ArrayList<>();
        for (var army : core.armies(nation)) {
            SupplyStatus status = armyStatus.get(army.id());
            List<String> lore = new ArrayList<>();
            lore.add(army.id().toString());
            lore.add("兵力 " + army.soldiers());
            if (status == null) lore.add("未绑定前线站");
            else {
                lore.add(statusText(status));
                for (ResourceType type : ResourceType.values()) {
                    lore.add(type.label() + " 库存 " + status.available().getOrDefault(type, 0L)
                            + " / 每周期需求 " + status.demand().getOrDefault(type, 0));
                }
            }
            entries.add(new Entry(Material.SHIELD, "军队 · " + army.id().toString().substring(0, 8), lore));
        }
        return List.copyOf(entries);
    }

    private String statusLine(UUID actor, String input) {
        if (input != null) {
            UUID army = resolveOwnArmy(actor, input);
            return armySupply(army).map(StarSupplyPlugin::statusText).orElse("该军队尚未绑定前线站");
        }
        UUID nation = logistics.nation(actor);
        List<String> status = new ArrayList<>();
        for (var army : core.armies(nation)) {
            String label = army.id().toString();
            status.add(label + " " + armySupply(army.id()).map(StarSupplyPlugin::statusText).orElse("未绑定"));
        }
        return status.isEmpty() ? "本国暂无军队" : String.join("\n", status);
    }

    private static String statusText(SupplyStatus status) {
        var campaign = status.campaign();
        String conditions = (campaign.winter() ? " / 冬季" : "")
                + (campaign.underSiege() ? String.format(Locale.ROOT, " / 围城 %d秒，额外需求 %.0f%%",
                campaign.siegeSeconds(), campaign.siegeExtraRatio() * 100) : "");
        conditions += switch (status.frontAccess()) {
            case CONNECTED -> "";
            case CONTROL_LOST -> " / 前线领土失守，补给暂停";
            case ARMY_OUT_OF_RANGE -> " / 军队超出前线服务距离，补给暂停";
        };
        return String.format(Locale.ROOT, "%s / %s / 补给 %.0f%% / 作战 ×%.3f%s", status.warehouseId(),
                status.atWar() ? "战时" : "和平", status.coverage() * 100, status.combatMultiplier(), conditions);
    }

    private UUID resolveOwnArmy(UUID actor, String input) {
        UUID nation = logistics.nation(actor);
        String prefix = input.toLowerCase(Locale.ROOT);
        require(prefix.matches("[a-f0-9-]{8,36}"), "军队编号须为完整 UUID 或至少 8 位前缀");
        var matches = core.armies(nation).stream().map(army -> army.id())
                .filter(id -> id.toString().startsWith(prefix)).limit(2).toList();
        require(matches.size() == 1, "军队编号不存在或前缀不唯一");
        return matches.getFirst();
    }

    @Override public CompletionStage<Optional<Purchase>> purchase(UUID id) { return sql(() -> store.purchase(id)); }
    @Override public CompletionStage<Optional<SupplyModels.TaxAssessment>> taxAssessment(UUID id) {
        return sql(() -> store.taxAssessment(id));
    }
    @Override public CompletionStage<Optional<Shipment>> shipment(UUID id) { return sql(() -> store.shipment(id)); }
    @Override public CompletionStage<Optional<Stock>> stock(String warehouse) {
        return sql(() -> store.warehouse(warehouse).isPresent() ? Optional.of(store.stock(warehouse)) : Optional.empty());
    }
    @Override public Optional<SupplyStatus> armySupply(UUID army) { return Optional.ofNullable(armyStatus.get(army)); }
    @Override public CompletionStage<RouteNetwork> routeNetwork(UUID nation) { return sql(() -> logistics.graph(nation)); }
    @Override public CompletionStage<Optional<RoutePlan>> route(UUID nation, String origin, String target) {
        return sql(() -> logistics.route(nation, origin, target));
    }
    @Override public void mapVanilla(Material material, ResourceType resource, int units) {
        items.registerVanilla(material, resource, units);
    }
    @Override public void mapCustom(String item, ResourceType resource, int units, boolean cookery, double quality) {
        items.registerCustom(item, resource, units, cookery, quality);
    }

    private void before(Player player, Operation operation, UUID order) {
        require(Bukkit.isOwnedByCurrentRegion(player), "请在玩家的实体调度线程调用");
        permission(player, "starsupply.use");
        SupplyOperationEvent event = new SupplyOperationEvent(player, operation, order);
        getServer().getPluginManager().callEvent(event);
        require(!event.isCancelled(), "操作已取消");
    }

    private static void permission(Player player, String permission) {
        require(player.hasPermission(permission), "缺少权限 " + permission);
    }

    private <T> CompletableFuture<T> sql(Callable<T> task) {
        var future = new CompletableFuture<T>();
        if (!accepting.get() || worker == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("StarSupply 当前不可用"));
        }
        try {
            worker.execute(() -> {
                try { future.complete(task.call()); }
                catch (Exception | LinkageError failure) { future.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException full) {
            future.completeExceptionally(new IllegalArgumentException("后勤服务繁忙，请稍后重试"));
        }
        return future;
    }

    private <T> CompletableFuture<T> entity(Player player, Callable<T> task) {
        var future = new CompletableFuture<T>();
        if (!accepting.get()) return CompletableFuture.failedFuture(new IllegalStateException("StarSupply 当前不可用"));
        Runnable run = () -> {
            try {
                require(player.isOnline(), "玩家已断线，库存操作将在重连时检查");
                future.complete(task.call());
            } catch (Exception | LinkageError failure) {
                future.completeExceptionally(failure);
            }
        };
        try {
            if (Bukkit.isOwnedByCurrentRegion(player)) run.run();
            else {
                Runnable retired = () -> future.completeExceptionally(
                        new IllegalArgumentException("玩家已断线，库存操作将在重连时检查"));
                if (player.getScheduler().run(this, taskHandle -> run.run(), retired) == null) retired.run();
            }
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    private void tell(Player player, String message) {
        entity(player, () -> { player.sendMessage("§6[StarSupply] §r" + message); return null; });
    }

    private void problem(Player player, Throwable failure) {
        Throwable root = cause(failure);
        if (root instanceof IllegalArgumentException) tell(player, root.getMessage());
        else {
            getLogger().log(Level.WARNING, "StarSupply 操作失败", root);
            tell(player, "后勤操作暂未完成，重试或使用 /supply recover 检查库存操作");
        }
    }

    private static Throwable cause(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private static Position position(Player player) {
        Location location = player.getLocation();
        return new Position(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    private static UUID id(String text) {
        if (text == null || !text.matches("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("请输入完整的订单或结算 UUID");
        }
        return UUID.fromString(text);
    }

    private static BigDecimal parseMoney(String text) {
        require(text.matches("[0-9]{1,9}(\\.[0-9]{1,2})?"), "请输入正数金额，最多 2 位小数");
        return new BigDecimal(text);
    }

    private double number(String key, double fallback, double minimum, double maximum) {
        double value = getConfig().getDouble(key, fallback);
        require(Double.isFinite(value) && value >= minimum && value <= maximum, "配置 " + key + " 超出范围");
        return value;
    }

    private Map<ResourceType, BigDecimal> marketBasePrices() {
        org.bukkit.configuration.ConfigurationSection section = getConfig()
                .getConfigurationSection("market-base-prices");
        if (section == null) section = getConfig().createSection("market-base-prices");
        Map<ResourceType, BigDecimal> prices = new java.util.EnumMap<>(ResourceType.class);
        for (ResourceType resource : ResourceType.values()) {
            String raw = section.getString(resource.name());
            if (raw == null) {
                raw = switch (resource) {
                    case FOOD -> "2.50";
                    case MEDICAL -> "4.00";
                    case EQUIPMENT -> "5.00";
                    case MATERIALS -> "0.50";
                    case ENGINEERING -> "3.00";
                    case STRATEGIC -> "8.00";
                };
                section.set(resource.name(), raw);
            }
            BigDecimal price;
            try {
                price = new BigDecimal(raw);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("无效市场基础报价 " + resource.name());
            }
            require(price.signum() > 0 && price.scale() <= 2 && price.compareTo(new BigDecimal("100000.00")) <= 0,
                    "市场基础报价须大于 0、最多两位小数且不超过 100000");
            prices.put(resource, price);
        }
        if (getConfig().get("market-buffer-items") == null) {
            getConfig().set("market-buffer-items", 16);
        }
        saveConfig();
        return Map.copyOf(prices);
    }

    private Map<String, Double> logisticsBonuses(String sectionName, Map<String, Double> defaults, double maximum) {
        org.bukkit.configuration.ConfigurationSection section = getConfig().getConfigurationSection(sectionName);
        if (section == null) section = getConfig().createSection(sectionName);
        Set<String> configuredKeys = section.getKeys(false);
        for (var entry : defaults.entrySet()) {
            if (!configuredKeys.contains(entry.getKey())) section.set(entry.getKey(), entry.getValue());
        }
        Map<String, Double> bonuses = new java.util.TreeMap<>();
        for (String key : section.getKeys(false)) {
            Object raw = section.get(key);
            require(key.matches("[a-z0-9][a-z0-9_-]{0,63}") && raw instanceof Number,
                    "配置 " + sectionName + " 须使用战略键和数值倍率");
            double value = ((Number) raw).doubleValue();
            require(Double.isFinite(value) && value >= 0 && value <= maximum,
                    "配置 " + sectionName + "." + key + " 超出范围");
            bonuses.put(key, value);
        }
        saveConfig();
        return Map.copyOf(bonuses);
    }

    private static long now() { return System.currentTimeMillis(); }

    private record InventoryObservation(UUID player, UUID receipt, String inventoryHash) {}
    private record Recovery(Transfer transfer, List<EscrowLot> lots) {}
    private record AuthorizedPlan(Logistics.SaleAuthorization authorization, InventoryEscrow.Plan plan) {}
}
