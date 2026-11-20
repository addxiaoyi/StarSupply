package dev.starsupply.service;

import static dev.starsupply.api.SupplyModels.*;
import static dev.starsupply.storage.SupplyStore.require;

import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyApi.RouteNetwork;
import dev.starsupply.api.SupplyModels.MarketQuote;
import dev.starsupply.integration.CoreGateway;
import dev.starsupply.integration.CoreGateway.TradePermit;
import dev.starsupply.route.RouteEdge;
import dev.starsupply.route.RouteNode;
import dev.starsupply.route.RoutePlan;
import dev.starsupply.route.RoutePlanner;
import dev.starsupply.storage.SupplyStore;
import dev.starsupply.storage.SupplyStore.Road;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Application rules. All mutation paths are invoked through the serialized background worker. */
public final class Logistics {
    private static final int MAX_ROUTE_RISK_CHUNKS = 4096;
    public record SaleAuthorization(Purchase purchase, UUID exporterNation, UUID agreementId,
                                    long agreementLimit, BigDecimal agreementUnitPrice) {}
    private final SupplyStore store;
    private final CoreGateway core;
    private final RoutePlanner planner = new RoutePlanner();
    private final double radius;
    private final double travelSpeed;
    private final long expiresMillis;
    private final long supplyInterval;
    private final int maxItems;
    private final BigDecimal maxPrice;
    private final Map<ResourceType, BigDecimal> marketBasePrices;
    private final long marketBufferItems;
    private volatile CampaignRules.Config campaignConfig = CampaignRules.Config.DEFAULT;
    private double armyServiceRadius = 256;
    private Consumer<SupplyConsumption> consumptionListener = ignored -> {};
    private Set<ResourceType> wartimeExportControls = Set.of(ResourceType.EQUIPMENT, ResourceType.STRATEGIC);

    public Logistics(SupplyStore store, CoreGateway core, double radius, double travelSpeed, long expiresMillis,
                     long supplyInterval, int maxItems, BigDecimal maxPrice) {
        this(store, core, radius, travelSpeed, expiresMillis, supplyInterval, maxItems, maxPrice,
                Map.of(ResourceType.FOOD, new BigDecimal("2.50"),
                        ResourceType.MEDICAL, new BigDecimal("4.00"),
                        ResourceType.EQUIPMENT, new BigDecimal("5.00"),
                        ResourceType.MATERIALS, new BigDecimal("0.50"),
                        ResourceType.ENGINEERING, new BigDecimal("3.00"),
                        ResourceType.STRATEGIC, new BigDecimal("8.00")), 16);
    }

    public Logistics(SupplyStore store, CoreGateway core, double radius, double travelSpeed, long expiresMillis,
                     long supplyInterval, int maxItems, BigDecimal maxPrice,
                     Map<ResourceType, BigDecimal> marketBasePrices, long marketBufferItems) {
        this.store = store;
        this.core = core;
        this.radius = radius;
        this.travelSpeed = travelSpeed;
        this.expiresMillis = expiresMillis;
        this.supplyInterval = supplyInterval;
        this.maxItems = maxItems;
        this.maxPrice = maxPrice;
        this.marketBasePrices = Map.copyOf(marketBasePrices);
        require(this.marketBasePrices.keySet().containsAll(List.of(ResourceType.values())),
                "区域市场缺少物资基础报价");
        for (BigDecimal price : this.marketBasePrices.values()) {
            require(price != null && price.signum() > 0 && price.scale() <= 2 && price.compareTo(maxPrice) <= 0,
                    "区域市场基础报价无效");
        }
        require(marketBufferItems >= 1 && marketBufferItems <= 1_000_000, "市场缓冲库存须在 1–1000000 之间");
        this.marketBufferItems = marketBufferItems;
    }

    /** Configure bounded campaign pressure before gameplay begins; defaults preserve normal fake gateways. */
    public void configureCampaigns(CampaignRules.Config config) {
        this.campaignConfig = java.util.Objects.requireNonNull(config);
    }

    public void configureFrontlines(double serviceRadius, Consumer<SupplyConsumption> listener) {
        require(Double.isFinite(serviceRadius) && serviceRadius >= 32 && serviceRadius <= 2048,
                "军队前线服务距离须在 32–2048 方块之间");
        armyServiceRadius = serviceRadius;
        consumptionListener = java.util.Objects.requireNonNull(listener);
    }

    /** Physical export controls use StarCore's current combat status; internal supply is unaffected. */
    public void configureWartimeExports(Set<ResourceType> restricted) {
        wartimeExportControls = Set.copyOf(restricted);
    }

    public boolean controlled(Warehouse warehouse) {
        Position position = warehouse.position();
        return core.nationExists(warehouse.nationId()) && core.territoryOwner(position.world(),
                chunk(position.x()), chunk(position.z())).filter(warehouse.nationId()::equals).isPresent();
    }

    public void requireControlled(Warehouse warehouse) {
        require(controlled(warehouse), "仓库所在领土已失守，恢复本国控制后可继续使用");
    }

    public UUID nation(UUID player) {
        return core.nationOf(player).orElseThrow(() -> new IllegalArgumentException("你当前不属于 StarCore 国家"));
    }

    public void manage(UUID actor, UUID nation) {
        require(core.nationExists(nation) && core.canManage(actor, nation), "你没有该国家的军需权限");
    }

    public Warehouse warehouse(String id) throws SQLException {
        requireKey(id);
        return store.warehouse(id).orElseThrow(() -> new IllegalArgumentException("仓库不存在"));
    }

    public Warehouse createWarehouse(UUID actor, String id, Position position, UUID armyId) throws SQLException {
        UUID nation = nation(actor);
        manage(actor, nation);
        require(core.territoryOwner(position.world(), chunk(position.x()), chunk(position.z()))
                .filter(nation::equals).isPresent(), "仓库和前线站必须位于本国已占领领土");
        if (armyId != null) {
            var army = core.army(armyId).orElseThrow(() -> new IllegalArgumentException("StarCore 军队不存在"));
            require(army.nationId().equals(nation), "只能绑定本国军队");
        }
        return store.addWarehouse(new Warehouse(id, nation, armyId == null ? WarehouseKind.DEPOT
                : WarehouseKind.FRONT, position, armyId), armyId != null);
    }

    public Road createRoad(UUID actor, String id, String from, String to, double efficiency, UUID coreLine)
            throws SQLException {
        requireKey(id);
        var origin = warehouse(from);
        var target = warehouse(to);
        manage(actor, origin.nationId());
        requireControlled(origin);
        requireControlled(target);
        require(!from.equals(to) && origin.nationId().equals(target.nationId()), "线路两端必须是不同的本国节点");
        require(Double.isFinite(efficiency) && efficiency >= 0.25 && efficiency <= 4, "道路效率范围为 0.25–4");
        require(origin.position().world().equals(target.position().world()), "第一阶段线路必须位于同一世界");
        require(withinRouteRiskLimit(origin.position(), target.position()),
                "道路经过区块过多，无法安全评估路线风险");
        if (coreLine != null) {
            var line = core.line(coreLine).orElseThrow(() -> new IllegalArgumentException("StarCore 补给线不存在"));
            require(core.canUseLine(origin.nationId(), coreLine), "StarCore 补给线不属于本国参战方");
            var start = new Position(line.world(), line.startX(), line.startY(), line.startZ());
            var end = new Position(line.world(), line.endX(), line.endY(), line.endZ());
            require(origin.position().within(start, radius) && target.position().within(end, radius),
                    "StarCore 补给线方向或端点与仓库不符");
        }
        Road road = new Road(id, origin.nationId(), from, to, efficiency, false, coreLine);
        store.addRoad(road);
        return road;
    }

    public void blockRoad(UUID actor, String id, boolean blocked) throws SQLException {
        var road = store.road(id).orElseThrow(() -> new IllegalArgumentException("道路不存在"));
        manage(actor, road.nationId());
        store.setBlocked(id, blocked);
    }

    public Purchase createPurchase(UUID actor, String depot, ResourceType resource, int items, BigDecimal price,
                                   long now) throws SQLException {
        var warehouse = warehouse(depot);
        manage(actor, warehouse.nationId());
        requireControlled(warehouse);
        require(warehouse.kind() == WarehouseKind.DEPOT, "采购物资须先进入国家仓库");
        require(items >= 1 && items <= maxItems, "采购数量范围为 1–" + maxItems);
        return store.addPurchase(new Purchase(UUID.randomUUID(), warehouse.nationId(), depot, resource,
                items, money(price), PurchaseState.OPEN, null, null, now));
    }

    public MarketQuote marketQuote(String depot, ResourceType resource, long now) throws SQLException {
        var warehouse = warehouse(depot);
        require(warehouse.kind() == WarehouseKind.DEPOT, "区域市场只设在国家采购仓库");
        var signals = store.marketSignals(depot, resource);
        BigDecimal quote = MarketRules.quote(marketBasePrices.get(resource), signals.stockItems(),
                signals.demandItems(), marketBufferItems);
        return new MarketQuote(depot, warehouse.nationId(), resource, signals.stockItems(),
                signals.demandItems(), quote, now);
    }

    public Purchase createMarketPurchase(UUID actor, String depot, ResourceType resource, int items, long now)
            throws SQLException {
        var quote = marketQuote(depot, resource, now);
        BigDecimal total = quote.unitPrice().multiply(BigDecimal.valueOf(items)).setScale(2, RoundingMode.HALF_UP);
        return createPurchase(actor, depot, resource, items, total, now);
    }

    public SaleAuthorization openSale(UUID seller, Position where, UUID id) throws SQLException {
        var order = store.purchase(id).orElseThrow(() -> new IllegalArgumentException("采购单不存在"));
        require(order.state() == PurchaseState.OPEN, "采购单已被预订或关闭");
        require(core.nationExists(order.nationId()), "采购国家已不存在");
        requireControlled(warehouse(order.warehouseId()));
        UUID exporterNation = core.nationOf(seller).orElseThrow(() ->
                new IllegalArgumentException("国家采购只接受所属 StarCore 国家的供货商"));
        UUID agreementId = null;
        long agreementLimit = 0;
        BigDecimal agreementUnitPrice = null;
        if (!exporterNation.equals(order.nationId())) {
            require(!wartimeExportControls.contains(order.resource()) || !core.isAtWar(exporterNation),
                    "出口国处于战争，当前战略物资受战时出口管制");
            require(!core.tradeEmbargoed(exporterNation, order.nationId(), order.resource()),
                    "该战略物资正在受 StarCore 禁运");
            TradePermit permit = core.tradePermit(exporterNation, order.nationId(), order.resource())
                    .orElseThrow(() -> new IllegalArgumentException("没有有效的 StarCore 国家贸易协定"));
            agreementId = permit.agreementId();
            agreementLimit = permit.maximumUnits();
            agreementUnitPrice = permit.unitPrice();
        }
        require(!hostile(seller, order.nationId()), "战争期间不能向敌对国家出售物资");
        require(near(where, warehouse(order.warehouseId())), "请到采购单指定的国家仓库");
        return new SaleAuthorization(order, exporterNation, agreementId, agreementLimit, agreementUnitPrice);
    }

    public long validateSaleUnits(SaleAuthorization authorization, List<SupplyStore.Lot> lots) {
        long units = lots.stream().mapToLong(SupplyStore.Lot::units).sum();
        if (authorization.agreementId() != null) {
            require(units <= authorization.agreementLimit(), "物资数量超过 StarCore 出口协定额度");
            BigDecimal agreedTotal = authorization.agreementUnitPrice().multiply(BigDecimal.valueOf(units))
                    .setScale(2, RoundingMode.HALF_UP);
            require(authorization.purchase().price().compareTo(agreedTotal) == 0,
                    "跨国采购总价须符合 StarCore 协定单价与实际物资单位数");
        }
        return units;
    }

    public Shipment createShipment(UUID actor, UUID purchaseId, String destination, BigDecimal reward,
                                   long now) throws SQLException {
        var purchase = store.purchase(purchaseId).orElseThrow(() -> new IllegalArgumentException("采购单不存在"));
        manage(actor, purchase.nationId());
        var front = warehouse(destination);
        require(front.kind() == WarehouseKind.FRONT && front.nationId().equals(purchase.nationId()),
                "目的地须为本国前线站");
        var plan = route(purchase.nationId(), purchase.warehouseId(), destination)
                .orElseThrow(() -> new IllegalArgumentException("没有可用道路通往前线"));
        travelMillis(plan); // Reject routes that cannot finish within the configured lifetime.
        return store.addShipment(new Shipment(UUID.randomUUID(), purchase.nationId(), purchaseId,
                purchase.warehouseId(), destination, money(reward), TransportState.OPEN, null, 0, 0,
                path(plan), now, plan.risk()));
    }

    public List<UUID> joinEscort(UUID actor, Position where, UUID shipmentId, long now) throws SQLException {
        var shipment = getShipment(shipmentId);
        require(shipment.state() == TransportState.OPEN, "商队已出发，无法再加入护送");
        require(!actor.equals(shipment.carrier()), "承运人无需加入自己的护送队");
        require(core.nationOf(actor).filter(shipment.nationId()::equals).isPresent(), "护送者必须属于发单国家");
        require(!hostile(actor, shipment.nationId()), "当前不能护送该国家的商队");
        require(near(where, warehouse(shipment.origin())), "须到起点仓库加入护送队");
        requireControlled(warehouse(shipment.origin()));
        store.joinEscort(shipmentId, actor, now);
        return store.escorts(shipmentId);
    }

    public Shipment accept(UUID actor, Position where, UUID id, long now) throws SQLException {
        var shipment = getShipment(id);
        require(shipment.state() == TransportState.OPEN, "运输单已经领取或关闭");
        require(core.nationExists(shipment.nationId()) && !hostile(actor, shipment.nationId()),
                "当前不能承运该国家的物资");
        require(near(where, warehouse(shipment.origin())), "须在起点仓库领取运输单");
        var plan = route(shipment.nationId(), shipment.origin(), shipment.target())
                .orElseThrow(() -> new IllegalArgumentException("道路已截断，运输单尚未领取"));
        int escorts = store.escortCount(id, actor);
        require(store.accept(id, actor, now + travelMillis(plan, escorts), now + expiresMillis, path(plan),
                protectedRisk(plan.risk(), escorts)), "订单状态已变化");
        return getShipment(id);
    }

    public String deliver(UUID actor, Position where, UUID id, long now) throws SQLException {
        var shipment = getShipment(id);
        require(actor.equals(shipment.carrier()), "只有承运人可以交付");
        if (shipment.state() == TransportState.DELIVERED || shipment.state() == TransportState.SETTLING
                || shipment.state() == TransportState.REVIEW) {
            return "该单已提交，状态 " + shipment.state() + "，结算编号 " + id;
        }
        require(shipment.state() == TransportState.IN_TRANSIT, "当前运输单不可交付");
        require(now < shipment.expiresAt(), "运输单已超时，物资将退回起点");
        require(!hostile(actor, shipment.nationId()), "当前不能向敌对国家交付");
        require(near(where, warehouse(shipment.target())), "须在目的地前线站交付");
        var plan = route(shipment.nationId(), shipment.origin(), shipment.target())
                .orElseThrow(() -> new IllegalArgumentException("道路已截断，恢复后可再次尝试"));
        String newPath = path(plan);
        int escorts = store.escortCount(id);
        double newRisk = protectedRisk(plan.risk(), escorts);
        if (!newPath.equals(shipment.path()) || Math.abs(newRisk - shipment.routeRisk()) > 0.000001) {
            long ready = Math.max(shipment.readyAt(), now + travelMillis(plan, escorts));
            require(ready < shipment.expiresAt(), "绕行时间超过订单期限，可取消并重新承运");
            require(store.reroute(id, actor, shipment.path(), newPath, ready, newRisk), "订单状态已变化");
            return "运输路线或风险已更新 " + newPath + " / 风险 " + String.format(Locale.ROOT, "%.0f%%", newRisk * 100)
                    + "，请等待 " + seconds(ready - now) + " 秒后交付";
        }
        require(now >= shipment.readyAt(), "还需等待 " + seconds(shipment.readyAt() - now) + " 秒");
        if (store.failRiskyShipment(id, actor, now)) {
            return "商队在敌区遭袭，运输失败；物资已退回起点，本次不结算运费";
        }
        require(store.deliver(id, actor, now), "订单状态已变化");
        return "物资已锁定，正在通过 StarCore 结算运费。编号 " + id;
    }

    public void cancelPurchase(UUID actor, UUID id) throws SQLException {
        var purchase = store.purchase(id).orElseThrow(() -> new IllegalArgumentException("采购单不存在"));
        manage(actor, purchase.nationId());
        require(store.cancelPurchase(id), "只能取消尚无卖家预订的采购单");
    }

    public void cancelShipment(UUID actor, UUID id) throws SQLException {
        var shipment = getShipment(id);
        if (!actor.equals(shipment.carrier())) manage(actor, shipment.nationId());
        require(store.closeShipment(id, TransportState.CANCELLED), "运输已进入结算或已关闭");
    }

    public Shipment getShipment(UUID id) throws SQLException {
        return store.shipment(id).orElseThrow(() -> new IllegalArgumentException("运输单不存在"));
    }

    /** Current physical route risk, including the persisted escort team, for insurance terms. */
    public double transportRisk(UUID shipmentId) throws SQLException {
        Shipment shipment = getShipment(shipmentId);
        return transportRisk(shipmentId, shipment.carrier());
    }

    /** A prospective carrier cannot reduce their own quote by registering as an escort first. */
    public double transportRisk(UUID shipmentId, UUID prospectiveCarrier) throws SQLException {
        Shipment shipment = getShipment(shipmentId);
        RoutePlan plan = route(shipment.nationId(), shipment.origin(), shipment.target())
                .orElseThrow(() -> new IllegalArgumentException("道路已截断，无法取得运输风险报价"));
        return protectedRisk(plan.risk(), store.escortCount(shipmentId, prospectiveCarrier));
    }

    public RouteNetwork graph(UUID nation) throws SQLException {
        List<RouteNode> nodes = new ArrayList<>();
        Map<String, Position> positions = new HashMap<>();
        Set<String> controlledNodes = new HashSet<>();
        for (Warehouse warehouse : store.warehouses(nation)) {
            Position p = warehouse.position();
            nodes.add(new RouteNode(warehouse.id(), nation, p.world(), p.x(), p.y(), p.z()));
            positions.put(warehouse.id(), p);
            if (controlled(warehouse)) controlledNodes.add(warehouse.id());
        }
        Map<ChunkKey, Optional<UUID>> owners = new HashMap<>();
        Map<UUID, Boolean> hostility = new HashMap<>();
        double transportBonus = core.logisticsModifiers(nation).transportSpeed();
        List<RouteEdge> edges = new ArrayList<>();
        for (Road road : store.roads(nation)) {
            Position from = positions.get(road.origin());
            Position to = positions.get(road.target());
            if (from == null || to == null || !from.world().equals(to.world())) continue;
            double distance = Math.max(1.0, Math.hypot(Math.hypot(from.x() - to.x(), from.z() - to.z()), from.y() - to.y()));
            boolean blocked = road.blocked() || !controlledNodes.contains(road.origin())
                    || !controlledNodes.contains(road.target());
            double efficiency = road.efficiency();
            if (road.coreLine() != null) {
                var line = core.line(road.coreLine());
                double factor = line.map(CoreGateway.Line::efficiency).orElse(0.0);
                blocked |= line.isEmpty() || !core.canUseLine(nation, road.coreLine())
                        || line.get().disrupted() || line.get().capacity() <= 0
                        || !Double.isFinite(factor) || factor <= 0.0;
                if (!blocked) {
                    var nativeLine = line.orElseThrow();
                    blocked = !from.within(new Position(nativeLine.world(), nativeLine.startX(), nativeLine.startY(),
                            nativeLine.startZ()), radius) || !to.within(new Position(nativeLine.world(), nativeLine.endX(),
                            nativeLine.endY(), nativeLine.endZ()), radius);
                }
            if (!blocked) efficiency *= Math.clamp(factor, 0.1, 2);
            }
            double risk = 0.0;
            if (!blocked) {
                OptionalDouble scan = routeRisk(nation, from, to, owners, hostility);
                if (scan.isPresent()) risk = scan.getAsDouble();
                else blocked = true; // An incomplete risk scan must never make a route look safer.
            }
            if (!blocked) efficiency *= 1 + transportBonus;
            edges.add(new RouteEdge(road.id(), road.origin(), road.target(), distance,
                    Math.clamp(efficiency, 0.05, 10), blocked, risk));
        }
        return new RouteNetwork(nodes, edges);
    }

    public Optional<RoutePlan> route(UUID nation, String from, String to) throws SQLException {
        requireKey(from);
        requireKey(to);
        var snapshot = graph(nation);
        return planner.plan(snapshot.nodes(), snapshot.edges(), from, to, Set.of());
    }

    private OptionalDouble routeRisk(UUID nation, Position from, Position to,
                                     Map<ChunkKey, Optional<UUID>> owners, Map<UUID, Boolean> hostility) {
        int startX = chunk(from.x());
        int startZ = chunk(from.z());
        int endX = chunk(to.x());
        int endZ = chunk(to.z());
        // A corner crossing can touch one extra chunk. This conservative bound keeps every scan
        // within budget before walking the grid, including paths that run through many corners.
        if (!withinRouteRiskLimit(startX, startZ, endX, endZ)) return OptionalDouble.empty();

        Set<ChunkKey> visited = new HashSet<>();
        int currentX = startX;
        int currentZ = startZ;
        int stepX = direction(to.x() - from.x());
        int stepZ = direction(to.z() - from.z());
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(to.x() - from.x());
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(to.z() - from.z());
        double nextX = nextChunkBoundary(from.x(), currentX, stepX, to.x());
        double nextZ = nextChunkBoundary(from.z(), currentZ, stepZ, to.z());
        addChunk(visited, from.world(), currentX, currentZ);
        while (currentX != endX || currentZ != endZ) {
            if (currentX == endX) {
                currentZ += stepZ;
                nextZ += deltaZ;
            } else if (currentZ == endZ) {
                currentX += stepX;
                nextX += deltaX;
            } else if (sameCrossing(nextX, nextZ)) {
                // Include both side chunks at a corner as a conservative supercover.
                addChunk(visited, from.world(), currentX + stepX, currentZ);
                addChunk(visited, from.world(), currentX, currentZ + stepZ);
                currentX += stepX;
                currentZ += stepZ;
                nextX += deltaX;
                nextZ += deltaZ;
            } else if (nextX < nextZ) {
                currentX += stepX;
                nextX += deltaX;
            } else {
                currentZ += stepZ;
                nextZ += deltaZ;
            }
            addChunk(visited, from.world(), currentX, currentZ);
            if (visited.size() > MAX_ROUTE_RISK_CHUNKS) return OptionalDouble.empty();
        }

        int hostile = 0;
        for (ChunkKey key : visited) {
            UUID owner = owners.computeIfAbsent(key, k -> core.territoryOwner(k.world(), k.x(), k.z())).orElse(null);
            if (owner != null && !owner.equals(nation) && hostility.computeIfAbsent(owner, n -> core.atWar(nation, n))) {
                hostile++;
            }
        }
        return OptionalDouble.of((double) hostile / visited.size());
    }

    private static double nextChunkBoundary(double coordinate, int chunk, int step, double destination) {
        if (step == 0 || chunk(coordinate) == chunk(destination)) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? (chunk + 1) * 16.0 : chunk * 16.0;
        return (boundary - coordinate) / (destination - coordinate);
    }

    private static int direction(double delta) {
        return delta > 0 ? 1 : delta < 0 ? -1 : 0;
    }

    private static boolean withinRouteRiskLimit(Position from, Position to) {
        return withinRouteRiskLimit(chunk(from.x()), chunk(from.z()), chunk(to.x()), chunk(to.z()));
    }

    private static boolean withinRouteRiskLimit(int startX, int startZ, int endX, int endZ) {
        long crossingsX = Math.abs((long) endX - startX);
        long crossingsZ = Math.abs((long) endZ - startZ);
        long maximumChunks = crossingsX + crossingsZ + Math.min(crossingsX, crossingsZ) + 1;
        return maximumChunks <= MAX_ROUTE_RISK_CHUNKS;
    }

    private static boolean sameCrossing(double first, double second) {
        return Double.isFinite(first) && Double.isFinite(second)
                && Math.abs(first - second) <= 1.0e-12;
    }

    private static void addChunk(Set<ChunkKey> visited, String world, int x, int z) {
        visited.add(new ChunkKey(world, x, z));
    }

    public Map<UUID, SupplyStatus> computeArmySupply(long now, boolean consume) throws SQLException {
        Map<UUID, SupplyStatus> statuses = new HashMap<>();
        Map<UUID, Boolean> wartime = new HashMap<>();
        Map<UUID, CoreGateway.LogisticsModifiers> strategic = new HashMap<>();
        long slot = Math.floorDiv(now, supplyInterval);
        boolean prune = false;
        CampaignRules.Config campaignConfig = this.campaignConfig;
        for (Warehouse front : store.fronts()) {
            var army = core.army(front.armyId());
            if (army.isEmpty() || !front.nationId().equals(army.get().nationId())) continue;
            var info = army.get();
            boolean active = info.soldiers() > 0 && wartime.computeIfAbsent(front.nationId(), core::isAtWar);
            FrontAccess access = !controlled(front) ? FrontAccess.CONTROL_LOST
                    : !front.position().world().equals(info.world()) || !front.position().within(
                    new Position(info.world(), info.x(), info.y(), info.z()), armyServiceRadius)
                    ? FrontAccess.ARMY_OUT_OF_RANGE : FrontAccess.CONNECTED;
            double efficiency = strategic.computeIfAbsent(front.nationId(), core::logisticsModifiers)
                    .supplyEfficiency();
            CoreGateway.CampaignConditions conditions = core.campaignConditions(front);
            var siege = store.observeSiege(front, active && conditions.underSiege(), conditions.observedAt(),
                    now, campaignConfig.continuityGapMillis());
            var campaign = CampaignRules.status(conditions, siege.orElse(null), active, now, campaignConfig);
            var demand = CampaignRules.demand(ArmySupplyRules.demand(info.soldiers(), efficiency), campaign,
                    campaignConfig);
            var spent = store.consumption(info.id(), slot);
            if (consume && active && access == FrontAccess.CONNECTED && spent.isEmpty() && !demand.isEmpty()) {
                spent = store.consume(front, slot, demand);
                Map<ResourceType, Integer> actual = new java.util.EnumMap<>(ResourceType.class);
                spent.forEach((resource, consumption) -> actual.put(resource, consumption.units()));
                UUID settlementId = UUID.nameUUIDFromBytes(("starsupply:consumption:" + info.id() + ":" + slot)
                        .getBytes(StandardCharsets.UTF_8));
                consumptionListener.accept(new SupplyConsumption(settlementId, info.id(), front.nationId(), front.id(),
                        slot, demand, actual, now));
            }
            // A season, policy, siege or troop-count change must not rewrite a charged interval's terms.
            if (!spent.isEmpty()) {
                Map<ResourceType, Integer> fixedDemand = new java.util.EnumMap<>(ResourceType.class);
                spent.forEach((resource, consumption) -> fixedDemand.put(resource, consumption.demand()));
                demand = Map.copyOf(fixedDemand);
            }
            var available = store.stock(front.id()).available();
            double coverage = access == FrontAccess.CONNECTED ? ArmySupplyRules.coverage(demand, available, spent) : 0;
            statuses.put(info.id(), new SupplyStatus(info.id(), front.nationId(), front.id(), info.soldiers(),
                    demand, available, coverage, ArmySupplyRules.multiplier(active, coverage,
                    strategic.get(front.nationId()).combatReadiness()), slot, active, campaign, access));
            prune |= consume && active;
        }
        if (prune) store.pruneConsumption(slot - Math.max(2, (7 * 24 * 60 * 60 * 1000L) / supplyInterval));
        return Map.copyOf(statuses);
    }

    private BigDecimal money(BigDecimal price) {
        require(price != null && price.signum() > 0 && price.scale() <= 2 && price.scale() >= 0
                && price.compareTo(maxPrice) <= 0, "金额须大于 0、最多 2 位小数且不超过 " + maxPrice.toPlainString());
        return price.setScale(2, RoundingMode.UNNECESSARY);
    }

    private boolean hostile(UUID player, UUID nation) {
        return core.nationOf(player).filter(other -> !nation.equals(other) && core.atWar(nation, other)).isPresent();
    }

    private boolean near(Position where, Warehouse warehouse) {
        return where.within(warehouse.position(), radius);
    }

    private long travelMillis(RoutePlan plan) { return travelMillis(plan, 0); }

    private long travelMillis(RoutePlan plan, int escorts) {
        double factor = 1 - Math.min(0.30, Math.max(0, escorts) * 0.05);
        long millis = Math.max(1000, (long) Math.ceil(plan.cost() * factor * 1000 / travelSpeed));
        require(millis < expiresMillis, "路线过长，无法在运输期限内完成");
        return millis;
    }

    private static double protectedRisk(double risk, int escorts) {
        double protection = Math.min(0.40, Math.max(0, escorts) * 0.10);
        return Math.clamp(risk * (1 - protection), 0, 1);
    }

    private static String path(RoutePlan plan) {
        return String.join(" → ", plan.edgeIds());
    }

    private static int chunk(double value) { return ((int) Math.floor(value)) >> 4; }
    private static long seconds(long millis) { return (millis + 999) / 1000; }
    private record ChunkKey(String world, int x, int z) {}
}
