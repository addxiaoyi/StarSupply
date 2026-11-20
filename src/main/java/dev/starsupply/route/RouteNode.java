package dev.starsupply.route;

import java.util.Objects;
import java.util.UUID;

/** A stationary routing point captured without loading a world or chunk. */
public record RouteNode(String id, UUID nationId, String world, double x, double y, double z) {
    private static final double MAX_COORDINATE = 60_000_000;

    public RouteNode {
        requireIdentifier(id, "node ID");
        Objects.requireNonNull(nationId, "nationId");
        requireIdentifier(world, "world");
        requireCoordinate(x);
        requireCoordinate(y);
        requireCoordinate(z);
    }

    static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1 to 128 characters");
        }
    }

    private static void requireCoordinate(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE) {
            throw new IllegalArgumentException("Coordinate is outside the routing bounds");
        }
    }
}
