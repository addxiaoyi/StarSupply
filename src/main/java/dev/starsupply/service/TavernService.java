package dev.starsupply.service;

import static dev.starsupply.storage.SupplyStore.require;

import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.PurchaseState;
import dev.starsupply.api.SupplyModels.TransportState;
import dev.starsupply.api.TavernModels.Board;
import dev.starsupply.api.TavernModels.Hub;
import dev.starsupply.api.TavernModels.ScoutReport;
import dev.starsupply.integration.CoreGateway;
import dev.starsupply.storage.SupplyStore;
import dev.starsupply.storage.TavernStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Permission/position rules; the Bukkit bridge captures a player's current entity-thread position. */
public final class TavernService {
    private final TavernStore store;
    private final SupplyStore supply;
    private final Logistics logistics;
    private final CoreGateway core;
    private volatile Settings settings = new Settings(32, 8, 900_000, false);

    private record Settings(double observationRadius, double hubRadius, long reportTtlMillis,
                            boolean requireVerifiedTavern) {}

    public TavernService(TavernStore store, SupplyStore supply, Logistics logistics, CoreGateway core) {
        this.store = Objects.requireNonNull(store);
        this.supply = Objects.requireNonNull(supply);
        this.logistics = Objects.requireNonNull(logistics);
        this.core = Objects.requireNonNull(core);
    }

    public void configure(double observationRadius, double hubRadius, long reportTtlMillis,
                          boolean requireVerifiedTavern) {
        require(Double.isFinite(observationRadius) && observationRadius >= 1 && observationRadius <= 32,
                "侦察距离须在 1–32 方块之间");
        require(Double.isFinite(hubRadius) && hubRadius >= 1 && hubRadius <= 16,
                "酒馆交互距离须在 1–16 方块之间");
        require(reportTtlMillis >= 60_000 && reportTtlMillis <= 900_000, "情报有效期须在 1–15 分钟之间");
        settings = new Settings(observationRadius, hubRadius, reportTtlMillis, requireVerifiedTavern);
    }

    /** Verified means the bridge authenticated a held Tavern drink; no item is charged or destroyed. */
    public Hub registerHub(UUID actor, Position where, String id, boolean tavernVerified, long now) throws SQLException {
        UUID nation = nation(actor);
        logistics.manage(actor, nation);
        require(core.territoryOwner(where.world(), chunk(where.x()), chunk(where.z())).filter(nation::equals).isPresent(),
                "酒馆集合点必须位于本国已占领领土");
        require(!settings.requireVerifiedTavern() || tavernVerified, "请手持森罗酒馆真实酒品登记集合点");
        return store.addHub(new Hub(id, nation, where, actor, now, tavernVerified));
    }

    public void removeHub(UUID actor, String id) throws SQLException {
        Hub hub = store.hub(id).orElseThrow(() -> new IllegalArgumentException("酒馆集合点不存在"));
        require(nation(actor).equals(hub.nationId()), "只能移除本国的酒馆集合点");
        logistics.manage(actor, hub.nationId());
        require(store.removeHub(id, hub.nationId()), "酒馆集合点状态已变化");
    }

    public List<Hub> hubs(UUID actor) throws SQLException {
        return store.hubs(nation(actor));
    }

    public ScoutReport scoutRoute(UUID actor, Position where, String roadId, long now) throws SQLException {
        UUID nation = nation(actor);
        var road = supply.road(roadId).orElseThrow(() -> new IllegalArgumentException("道路不存在"));
        var origin = supply.warehouse(road.origin()).orElseThrow(() -> new IllegalArgumentException("道路起点不存在"));
        var target = supply.warehouse(road.target()).orElseThrow(() -> new IllegalArgumentException("道路终点不存在"));
        Settings config = settings;
        require(nearSegment(where, origin.position(), target.position(), config.observationRadius()),
                "须在该道路 32 方块内实地侦察");
        var graph = logistics.graph(road.nationId());
        var edge = graph.edges().stream().filter(candidate -> candidate.id().equals(roadId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("道路当前无法生成可靠快照"));
        return store.addReport(new ScoutReport(UUID.randomUUID(), nation, actor, roadId, road.nationId(),
                edge.from(), edge.to(), edge.distance(), edge.roadEfficiency(), edge.blocked(), edge.hostileRisk(),
                where, now, Math.addExact(now, config.reportTtlMillis()), null, 0), now);
    }

    public ScoutReport publishIntelligence(UUID actor, Position where, UUID report, String hubId, long now)
            throws SQLException {
        Hub hub = accessibleHub(actor, where, hubId);
        return store.publish(report, hub.nationId(), hub.id(), now);
    }

    public Board board(UUID actor, Position where, String hubId, long now) throws SQLException {
        Hub hub = accessibleHub(actor, where, hubId);
        return new Board(hub, store.board(hub.id(), hub.nationId(), now),
                supply.nationPurchases(hub.nationId()).stream().filter(order -> order.state() == PurchaseState.OPEN).toList(),
                supply.nationShipments(hub.nationId()).stream().filter(order -> order.state() == TransportState.OPEN).toList(), now);
    }

    private Hub accessibleHub(UUID actor, Position where, String id) throws SQLException {
        UUID nation = nation(actor);
        Hub hub = store.hub(id).orElseThrow(() -> new IllegalArgumentException("酒馆集合点不存在"));
        require(hub.nationId().equals(nation), "只能在本国酒馆查看或发布情报");
        require(where.within(hub.position(), settings.hubRadius()), "请到酒馆集合点附近操作");
        require(core.territoryOwner(hub.position().world(), chunk(hub.position().x()), chunk(hub.position().z()))
                .filter(nation::equals).isPresent(), "酒馆所在领土已失守，恢复控制后才能使用情报板");
        return hub;
    }

    private UUID nation(UUID actor) {
        UUID nation = core.nationOf(Objects.requireNonNull(actor)).orElseThrow(() ->
                new IllegalArgumentException("你当前不属于 StarCore 国家"));
        require(core.nationExists(nation), "国家已经不存在");
        return nation;
    }

    private static int chunk(double value) {
        return (int) Math.floor(value / 16);
    }

    static boolean nearSegment(Position point, Position start, Position end, double radius) {
        if (!point.world().equals(start.world()) || !point.world().equals(end.world())) return false;
        double x = end.x() - start.x();
        double y = end.y() - start.y();
        double z = end.z() - start.z();
        double length = x * x + y * y + z * z;
        double projection = length == 0 ? 0 : Math.clamp(((point.x() - start.x()) * x
                + (point.y() - start.y()) * y + (point.z() - start.z()) * z) / length, 0, 1);
        double dx = point.x() - (start.x() + projection * x);
        double dy = point.y() - (start.y() + projection * y);
        double dz = point.z() - (start.z() + projection * z);
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }
}
