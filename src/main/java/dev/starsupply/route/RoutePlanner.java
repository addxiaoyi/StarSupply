package dev.starsupply.route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/** Plans directed routes with no server API or mutable state shared between calls. */
public final class RoutePlanner {
    public static final int MAX_NODES = 10_000;
    public static final int MAX_EDGES = 50_000;

    /**
     * Finds the lowest cost reachable path. Callers should pass a stable graph snapshot and
     * run this calculation off the region or entity thread. An invalid graph is rejected.
     * A missing or disconnected endpoint yields an empty result.
     */
    public Optional<RoutePlan> plan(Collection<RouteNode> nodes, Collection<RouteEdge> edges,
                                    String from, String to, Set<String> excludedEdges) {
        RouteNode.requireIdentifier(from, "origin node ID");
        RouteNode.requireIdentifier(to, "destination node ID");
        if (nodes.size() > MAX_NODES || edges.size() > MAX_EDGES || excludedEdges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("Routing graph is too large");
        }

        Set<String> excluded = Set.copyOf(excludedEdges);
        Map<String, RouteNode> byId = new HashMap<>();
        for (RouteNode node : List.copyOf(nodes)) {
            if (byId.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate road node: " + node.id());
            }
        }

        Map<String, List<RouteEdge>> outgoing = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (RouteEdge edge : List.copyOf(edges)) {
            if (!edgeIds.add(edge.id())) {
                throw new IllegalArgumentException("Duplicate road segment: " + edge.id());
            }
            RouteNode origin = byId.get(edge.from());
            RouteNode destination = byId.get(edge.to());
            if (origin == null || destination == null) {
                throw new IllegalArgumentException("Road references an unknown node: " + edge.id());
            }
            if (!origin.world().equals(destination.world())) {
                throw new IllegalArgumentException("Road crosses worlds: " + edge.id());
            }
            if (!edge.blocked() && !excluded.contains(edge.id())) {
                outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
            }
        }

        if (!byId.containsKey(from) || !byId.containsKey(to)
                || !byId.get(from).world().equals(byId.get(to).world())) {
            return Optional.empty();
        }
        outgoing.values().forEach(segments -> segments.sort(Comparator.comparing(RouteEdge::id)));
        return cheapest(outgoing, from, to);
    }

    private static Optional<RoutePlan> cheapest(Map<String, List<RouteEdge>> outgoing,
                                                String from, String to) {
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingDouble(Visit::cost)
                .thenComparing(Visit::nodeId));
        Map<String, Double> bestCosts = new HashMap<>();
        Map<String, RouteEdge> predecessors = new HashMap<>();
        bestCosts.put(from, 0.0);
        queue.add(new Visit(from, 0));

        while (!queue.isEmpty()) {
            Visit visit = queue.remove();
            if (visit.cost() > bestCosts.get(visit.nodeId())) {
                continue;
            }
            if (visit.nodeId().equals(to)) {
                return Optional.of(reconstruct(predecessors, from, to, visit.cost()));
            }
            for (RouteEdge edge : outgoing.getOrDefault(visit.nodeId(), List.of())) {
                double candidate = visit.cost() + edge.cost();
                if (candidate < bestCosts.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                    bestCosts.put(edge.to(), candidate);
                    predecessors.put(edge.to(), edge);
                    queue.add(new Visit(edge.to(), candidate));
                }
            }
        }
        return Optional.empty();
    }

    private static RoutePlan reconstruct(Map<String, RouteEdge> predecessors,
                                         String from, String to, double cost) {
        List<String> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        double remainingSafety = 1;
        String node = to;
        nodes.add(node);
        while (!node.equals(from)) {
            RouteEdge edge = Objects.requireNonNull(predecessors.get(node));
            edges.add(edge.id());
            remainingSafety *= 1 - edge.hostileRisk();
            node = edge.from();
            nodes.add(node);
        }
        Collections.reverse(nodes);
        Collections.reverse(edges);
        return new RoutePlan(nodes, edges, cost, 1 - remainingSafety);
    }

    private record Visit(String nodeId, double cost) {}
}
