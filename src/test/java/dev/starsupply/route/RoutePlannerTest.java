package dev.starsupply.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutePlannerTest {
    private static final UUID NATION = UUID.fromString("3b0e5e06-03fd-4541-a90e-f9742b07aa12");
    private final RoutePlanner planner = new RoutePlanner();
    private final List<RouteNode> nodes = List.of(node("storage"), node("crossing"), node("front"));

    @Test
    void findsDetourWhenRoadIsCutAndSwitchesBackWhenRestored() {
        RouteEdge first = edge("north", "storage", "crossing", 20, 1, false, 0);
        RouteEdge second = edge("east", "crossing", "front", 25, 1, false, 0);
        RouteEdge cut = edge("bridge", "storage", "front", 15, 1, true, 0);
        RouteEdge repaired = edge("bridge", "storage", "front", 15, 1, false, 0);

        RoutePlan detour = planner.plan(nodes, List.of(first, second, cut), "storage", "front", Set.of())
                .orElseThrow();
        assertEquals(List.of("storage", "crossing", "front"), detour.nodeIds());
        assertEquals(List.of("north", "east"), detour.edgeIds());
        assertEquals(45, detour.cost());

        RoutePlan restored = planner.plan(nodes, List.of(first, second, repaired), "storage", "front", Set.of())
                .orElseThrow();
        assertEquals(List.of("bridge"), restored.edgeIds());
    }

    @Test
    void excludesSelectedRoadsAndUsesRoadEfficiencyAndRisk() {
        RouteEdge shortHostile = edge("bridge", "storage", "front", 100, 1, false, 0.75);
        RouteEdge north = edge("north", "storage", "crossing", 100, 2, false, 0.2);
        RouteEdge east = edge("east", "crossing", "front", 100, 2, false, 0.3);
        List<RouteEdge> roads = List.of(shortHostile, north, east);

        RoutePlan route = planner.plan(nodes, roads, "storage", "front", Set.of()).orElseThrow();
        assertEquals(List.of("north", "east"), route.edgeIds());
        assertEquals(150, route.cost());
        assertEquals(0.44, route.risk(), 0.000001);

        RoutePlan alternative = planner.plan(nodes, roads, "storage", "front", Set.of("north")).orElseThrow();
        assertEquals(List.of("bridge"), alternative.edgeIds());
        assertEquals(250, alternative.cost());
    }

    @Test
    void routesAreDirectedAndMayBeUnreachable() {
        RouteEdge outward = edge("bridge", "storage", "front", 50, 1, false, 0);
        assertTrue(planner.plan(nodes, List.of(outward), "front", "storage", Set.of()).isEmpty());
        assertTrue(planner.plan(nodes, List.of(outward), "storage", "missing", Set.of()).isEmpty());
        assertTrue(planner.plan(nodes, List.of(outward), "storage", "front", Set.of("bridge")).isEmpty());
        assertEquals(new RoutePlan(List.of("storage"), List.of(), 0, 0),
                planner.plan(nodes, List.of(outward), "storage", "storage", Set.of()).orElseThrow());
    }

    @Test
    void rejectsNonFiniteAndInconsistentGraphData() {
        assertThrows(IllegalArgumentException.class, () -> edge("bridge", "storage", "front", Double.NaN, 1, false, 0));
        assertThrows(IllegalArgumentException.class, () -> edge("bridge", "storage", "front", 10, 0, false, 0));
        assertThrows(IllegalArgumentException.class, () -> edge("bridge", "storage", "front", 10, 1, false, 1.1));
        RouteNode end = new RouteNode("end", NATION, "world_the_end", 0, 70, 0);
        RouteEdge invalid = edge("bridge", "storage", "end", 50, 1, false, 0);
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(node("storage"), end), List.of(invalid), "storage", "end", Set.of()));
        assertTrue(planner.plan(List.of(node("storage"), end), List.of(), "storage", "end", Set.of()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(nodes, List.of(invalid), "storage", "front", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(node("storage"), node("storage")), List.of(), "storage", "front", Set.of()));
    }

    @Test
    void planOwnsImmutableCopiesOfTheRoute() {
        List<String> routeNodes = new ArrayList<>(List.of("storage", "front"));
        List<String> routeEdges = new ArrayList<>(List.of("bridge"));
        RoutePlan plan = new RoutePlan(routeNodes, routeEdges, 10, 0);
        routeNodes.clear();
        routeEdges.clear();
        assertEquals(List.of("storage", "front"), plan.nodeIds());
        assertEquals(List.of("bridge"), plan.edgeIds());
        assertThrows(UnsupportedOperationException.class, () -> plan.nodeIds().clear());
    }

    private static RouteNode node(String id) {
        return new RouteNode(id, NATION, "world", 0, 70, 0);
    }

    private static RouteEdge edge(String id, String from, String to, double distance, double efficiency,
                                  boolean blocked, double risk) {
        return new RouteEdge(id, from, to, distance, efficiency, blocked, risk);
    }
}
