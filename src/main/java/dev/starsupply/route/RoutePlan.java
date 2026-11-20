package dev.starsupply.route;

import java.util.List;

/** Ordered nodes and edges for map rendering and transport checks. */
public record RoutePlan(List<String> nodeIds, List<String> edgeIds, double cost, double risk) {
    public RoutePlan {
        nodeIds = List.copyOf(nodeIds);
        edgeIds = List.copyOf(edgeIds);
        if (nodeIds.size() != edgeIds.size() + 1) {
            throw new IllegalArgumentException("A route has exactly one more node than edge");
        }
        if (!Double.isFinite(cost) || cost < 0 || !Double.isFinite(risk) || risk < 0 || risk > 1) {
            throw new IllegalArgumentException("Invalid route cost or risk");
        }
    }
}
