package dev.starsupply.api;

import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.Purchase;
import dev.starsupply.api.SupplyModels.Shipment;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Detached, immutable snapshots suitable for background work and GUI/map rendering. */
public final class TavernModels {
    private TavernModels() {}

    public record Hub(String id, UUID nationId, Position position, UUID registeredBy, long createdAt,
                      boolean tavernVerified) {
        public Hub {
            SupplyModels.requireKey(id);
            Objects.requireNonNull(nationId);
            Objects.requireNonNull(position);
            Objects.requireNonNull(registeredBy);
            if (createdAt <= 0) throw new IllegalArgumentException("Invalid tavern registration time");
        }
    }

    public record ScoutReport(UUID id, UUID nationId, UUID scoutId, String roadId, UUID roadNationId,
                              String origin, String target, double distance, double roadEfficiency,
                              boolean blocked, double hostileRisk, Position observedFrom, long observedAt,
                              long expiresAt, String publishedHub, long publishedAt) {
        public ScoutReport {
            Objects.requireNonNull(id);
            Objects.requireNonNull(nationId);
            Objects.requireNonNull(scoutId);
            SupplyModels.requireKey(roadId);
            Objects.requireNonNull(roadNationId);
            SupplyModels.requireKey(origin);
            SupplyModels.requireKey(target);
            Objects.requireNonNull(observedFrom);
            if (!Double.isFinite(distance) || distance <= 0 || distance > 210_000_000
                    || !Double.isFinite(roadEfficiency) || roadEfficiency < 0.05 || roadEfficiency > 10
                    || !Double.isFinite(hostileRisk) || hostileRisk < 0 || hostileRisk > 1
                    || observedAt <= 0 || expiresAt <= observedAt || expiresAt - observedAt > 900_000
                    || (publishedHub == null && publishedAt != 0)
                    || (publishedHub != null && (publishedAt < observedAt || publishedAt >= expiresAt))) {
                throw new IllegalArgumentException("Invalid physical route intelligence snapshot");
            }
            if (publishedHub != null) SupplyModels.requireKey(publishedHub);
        }

        public boolean expired(long now) {
            return now < observedAt || now >= expiresAt;
        }
    }

    /** Only the viewer's nation and physically accessible hub contribute board content. */
    public record Board(Hub hub, List<ScoutReport> reports, List<Purchase> purchases, List<Shipment> shipments,
                        long viewedAt) {
        public Board {
            Objects.requireNonNull(hub);
            reports = List.copyOf(reports);
            purchases = List.copyOf(purchases);
            shipments = List.copyOf(shipments);
        }
    }
}
