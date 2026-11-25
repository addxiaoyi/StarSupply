package dev.starsupply.api;

import dev.starsupply.route.RoutePlan;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable views. UUIDs are StarCore identifiers wherever they refer to nations or armies. */
public final class SupplyModels {
    private SupplyModels() {}

    public enum WarehouseKind { DEPOT, FRONT }
    public enum PurchaseState { OPEN, RESERVED, SETTLING, BOUGHT, CANCELLED, REVIEW }
    public enum TransportState { OPEN, IN_TRANSIT, SETTLING, DELIVERED, CANCELLED, FAILED, REVIEW }
    public enum TransferState { PREPARED, TAKEN, REJECTED, RETURNING, RETURNED, STORED, REVIEW }
    public enum FrontAccess { CONNECTED, CONTROL_LOST, ARMY_OUT_OF_RANGE }
    public enum Operation {
        WAREHOUSE_CREATE, FRONT_REBIND, ROUTE_UPDATE, PURCHASE_CREATE, SELL, TRANSPORT_CREATE, ACCEPT, ESCORT, DELIVER, CANCEL, RETURN,
        RECONCILE, TAVERN_CREATE, TAVERN_REMOVE, SCOUT, INTELLIGENCE_PUBLISH, INSURE, INSURANCE_CANCEL
    }

    public record Position(String world, double x, double y, double z) {
        public Position {
            if (world == null || world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z) || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                    || Math.abs(y) > 4096) {
                throw new IllegalArgumentException("无效的世界坐标");
            }
        }

        public boolean within(Position target, double radius) {
            if (!world.equals(target.world)) return false;
            double dx = x - target.x;
            double dy = y - target.y;
            double dz = z - target.z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    public record Warehouse(String id, UUID nationId, WarehouseKind kind, Position position,
                            UUID armyId) {
        public Warehouse {
            requireKey(id);
            Objects.requireNonNull(nationId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(position);
            if (kind == WarehouseKind.DEPOT && armyId != null) {
                throw new IllegalArgumentException("军队只能绑定到前线站");
            }
        }
    }

    public record Purchase(UUID id, UUID nationId, String warehouseId, ResourceType resource,
                           int itemCount, BigDecimal price, PurchaseState state, UUID seller,
                           UUID settlementId, long createdAt) {}

    public record Shipment(UUID id, UUID nationId, UUID purchaseId, String origin, String target,
                           BigDecimal reward, TransportState state, UUID carrier,
                           long readyAt, long expiresAt, String path, long createdAt, double routeRisk) {
        public Shipment {
            if (!Double.isFinite(routeRisk) || routeRisk < 0 || routeRisk > 1) {
                throw new IllegalArgumentException("运输风险须在 0–1 之间");
            }
        }
    }

    public record SupplyStatus(UUID armyId, UUID nationId, String warehouseId, int soldiers,
                               Map<ResourceType, Integer> demand, Map<ResourceType, Long> available,
                               double coverage, double combatMultiplier, long interval, boolean atWar,
                               CampaignStatus campaign, FrontAccess frontAccess, double moraleDelta) {
        public SupplyStatus {
            demand = Map.copyOf(demand);
            available = Map.copyOf(available);
            campaign = Objects.requireNonNull(campaign);
            frontAccess = Objects.requireNonNull(frontAccess);
            if (!Double.isFinite(moraleDelta) || moraleDelta < -10 || moraleDelta > 0 || (!atWar && moraleDelta != 0)) {
                throw new IllegalArgumentException("Invalid temporary logistics morale contribution");
            }
        }

        /** Compatibility constructor for consumers that do not provide a morale contribution. */
        public SupplyStatus(UUID armyId, UUID nationId, String warehouseId, int soldiers,
                            Map<ResourceType, Integer> demand, Map<ResourceType, Long> available,
                            double coverage, double combatMultiplier, long interval, boolean atWar,
                            CampaignStatus campaign, FrontAccess frontAccess) {
            this(armyId, nationId, warehouseId, soldiers, demand, available, coverage, combatMultiplier,
                    interval, atWar, campaign, frontAccess, 0);
        }

        public SupplyStatus(UUID armyId, UUID nationId, String warehouseId, int soldiers,
                            Map<ResourceType, Integer> demand, Map<ResourceType, Long> available,
                            double coverage, double combatMultiplier, long interval, boolean atWar,
                            CampaignStatus campaign) {
            this(armyId, nationId, warehouseId, soldiers, demand, available, coverage, combatMultiplier,
                    interval, atWar, campaign, FrontAccess.CONNECTED);
        }

        public SupplyStatus(UUID armyId, UUID nationId, String warehouseId, int soldiers,
                            Map<ResourceType, Integer> demand, Map<ResourceType, Long> available,
                            double coverage, double combatMultiplier, long interval, boolean atWar) {
            this(armyId, nationId, warehouseId, soldiers, demand, available, coverage, combatMultiplier,
                    interval, atWar, CampaignStatus.NORMAL);
        }
    }

    /** Durable FOOD-to-native-supply obligation. APPLIED means StarCore confirmed its own receipt. */
    public record NativeSupplyCredit(UUID id, UUID armyId, UUID nationId, long interval, long intervalMillis, int points,
                                     String state, int attempts, String lastError) {
        public NativeSupplyCredit {
            Objects.requireNonNull(id);
            Objects.requireNonNull(armyId);
            Objects.requireNonNull(nationId);
            Objects.requireNonNull(lastError);
            if (intervalMillis < 0 || intervalMillis > 86_400_000L || points < 1 || points > 10 || attempts < 0
                    || !java.util.Set.of("PENDING", "APPLIED", "REVIEW").contains(state)) {
                throw new IllegalArgumentException("Invalid native supply obligation");
            }
        }
    }

    /** Receipt for one army/resource cycle; the ID is stable across process restarts. */
    public record SupplyConsumption(UUID settlementId, UUID armyId, UUID nationId, String warehouseId,
                                    long interval, Map<ResourceType, Integer> demand,
                                    Map<ResourceType, Integer> consumed, long committedAt) {
        public SupplyConsumption {
            Objects.requireNonNull(settlementId);
            Objects.requireNonNull(armyId);
            Objects.requireNonNull(nationId);
            requireKey(warehouseId);
            demand = Map.copyOf(demand);
            consumed = Map.copyOf(consumed);
        }
    }

    /** Current temporary conditions; an already-consumed interval keeps its persisted demand snapshot. */
    public record CampaignStatus(boolean winter, boolean underSiege, long siegeSince, long siegeSeconds,
                                 double siegeExtraRatio, long observedAt) {
        public static final CampaignStatus NORMAL = new CampaignStatus(false, false, 0, 0, 0, 0);

        public CampaignStatus {
            if (siegeSince < 0 || siegeSeconds < 0 || observedAt < 0 || !Double.isFinite(siegeExtraRatio)
                    || siegeExtraRatio < 0 || siegeExtraRatio > 0.50
                    || (!underSiege && (siegeSince != 0 || siegeSeconds != 0 || siegeExtraRatio != 0))) {
                throw new IllegalArgumentException("Invalid campaign supply status");
            }
        }
    }

    public record Stock(String warehouseId, Map<ResourceType, Long> available, Map<ResourceType, Long> reserved) {
        public Stock {
            available = Map.copyOf(available);
            reserved = Map.copyOf(reserved);
        }
    }

    /** Price snapshot derived from one depot's physical item count and open purchase demand. */
    public record MarketQuote(String warehouseId, UUID nationId, ResourceType resource, long stockItems,
                              long demandItems, BigDecimal unitPrice, long quotedAt) {
        public MarketQuote {
            requireKey(warehouseId);
            Objects.requireNonNull(nationId);
            Objects.requireNonNull(resource);
            Objects.requireNonNull(unitPrice);
            if (stockItems < 0 || demandItems < 0 || unitPrice.signum() <= 0) {
                throw new IllegalArgumentException("Invalid regional market quote");
            }
        }
    }

    public record Settlement(UUID id, UUID orderId, UUID nationId, UUID playerId, String kind,
                             String outcome, BigDecimal amount, long timestamp) {}

    /** Immutable tax terms captured from StarCore before the seller's inventory is changed. */
    public record TaxAssessment(UUID settlementId, String type, UUID nationId, UUID payerId,
                                BigDecimal grossAmount, BigDecimal taxAmount) {
        public TaxAssessment {
            Objects.requireNonNull(settlementId, "settlementId");
            if (!"TARIFF".equals(type)) throw new IllegalArgumentException("Unsupported physical trade tax type");
            Objects.requireNonNull(nationId, "nationId");
            Objects.requireNonNull(payerId, "payerId");
            Objects.requireNonNull(grossAmount, "grossAmount");
            Objects.requireNonNull(taxAmount, "taxAmount");
            if (grossAmount.scale() > 2 || taxAmount.scale() > 2 || grossAmount.signum() <= 0
                    || taxAmount.signum() < 0 || taxAmount.compareTo(grossAmount) > 0) {
                throw new IllegalArgumentException("Invalid StarCore trade tax assessment");
            }
        }

        public BigDecimal netAmount() { return grossAmount.subtract(taxAmount); }
    }

    public record RouteView(String from, String to, RoutePlan plan) {}

    public static void requireKey(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("编号须为 1–32 位小写字母、数字、- 或 _");
        }
    }
}
