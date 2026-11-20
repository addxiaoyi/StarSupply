package dev.starsupply.route;

/** One directed road segment, with distance in blocks and a normalized hostile risk score. */
public record RouteEdge(String id, String from, String to, double distance, double roadEfficiency,
                        boolean blocked, double hostileRisk) {
    public RouteEdge {
        RouteNode.requireIdentifier(id, "edge ID");
        RouteNode.requireIdentifier(from, "origin node ID");
        RouteNode.requireIdentifier(to, "destination node ID");
        if (from.equals(to)) {
            throw new IllegalArgumentException("A road must connect two different nodes");
        }
        if (!Double.isFinite(distance) || distance < 0.000001 || distance > 210_000_000) {
            throw new IllegalArgumentException("Road distance must be between 0.000001 and 210000000 blocks");
        }
        if (!Double.isFinite(roadEfficiency) || roadEfficiency < 0.05 || roadEfficiency > 10) {
            throw new IllegalArgumentException("Road efficiency must be between 0.05 and 10");
        }
        if (!Double.isFinite(hostileRisk) || hostileRisk < 0 || hostileRisk > 1) {
            throw new IllegalArgumentException("Hostile risk must be between 0 and 1");
        }
    }

    double cost() {
        return distance / roadEfficiency * (1 + 2 * hostileRisk);
    }
}
