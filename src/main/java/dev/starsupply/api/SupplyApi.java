package dev.starsupply.api;

import dev.starsupply.api.SupplyModels.*;
import dev.starsupply.route.RouteEdge;
import dev.starsupply.route.RouteNode;
import dev.starsupply.route.RoutePlan;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Bukkit service registered by StarSupply. Read stages complete off the server tick thread.
 * Call mutations on the actor's entity scheduler. They perform the same authorization
 * and publish the same cancellable operation event as the player commands.
 */
public interface SupplyApi {
    CompletionStage<Optional<Purchase>> purchase(UUID id);
    CompletionStage<Optional<TaxAssessment>> taxAssessment(UUID settlementId);
    CompletionStage<Optional<Shipment>> shipment(UUID id);
    CompletionStage<Optional<Stock>> stock(String warehouseId);
    CompletionStage<Optional<MarketQuote>> marketQuote(String depotId, ResourceType resource);
    Optional<SupplyStatus> armySupply(UUID armyId);
    /** Latest 128 durable physical FOOD obligations; read-only and ordered newest first. */
    default CompletionStage<List<NativeSupplyCredit>> nativeSupplyCredits(UUID armyId) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("Native supply receipts are unavailable"));
    }
    CompletionStage<RouteNetwork> routeNetwork(UUID nationId);
    CompletionStage<Optional<RoutePlan>> route(UUID nationId, String origin, String target);
    CompletionStage<Purchase> createPurchase(Player actor, String depot, ResourceType resource, int count,
                                             BigDecimal totalPrice);
    CompletionStage<Purchase> createMarketPurchase(Player actor, String depot, ResourceType resource, int count);
    /** Rebinds an existing national front to an unbound StarCore army while preserving its stock and routes. */
    default CompletionStage<Warehouse> rebindFront(Player actor, String frontId, UUID armyId) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("Front army rebinding is unavailable"));
    }
    /** Cancelling observation does not cancel an admitted physical inventory transaction. Recover by its receipt. */
    CompletionStage<String> sell(Player actor, UUID purchase);
    CompletionStage<Shipment> createTransport(Player actor, UUID purchase, String front, BigDecimal reward);
    CompletionStage<Shipment> acceptTransport(Player actor, UUID shipment);
    CompletionStage<Integer> joinEscort(Player actor, UUID shipment);
    CompletionStage<String> deliverTransport(Player actor, UUID shipment);
    CompletionStage<Void> cancelOrder(Player actor, UUID order, boolean shipment);
    void mapVanilla(Material material, ResourceType resource, int units);
    void mapCustom(String itemId, ResourceType resource, int units, boolean requireCookeryQuality,
                   double minimumQuality);

    record RouteNetwork(List<RouteNode> nodes, List<RouteEdge> edges) {
        public RouteNetwork {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }
    }
}
