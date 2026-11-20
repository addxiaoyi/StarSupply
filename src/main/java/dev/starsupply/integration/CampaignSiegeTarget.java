package dev.starsupply.integration;

import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.Warehouse;
import java.util.Objects;
import java.util.UUID;

/** Immutable target copied from a live StarCore siege; it holds no mutable upstream or Bukkit objects. */
record CampaignSiegeTarget(UUID defender, Position wall) {
    CampaignSiegeTarget {
        Objects.requireNonNull(defender);
        Objects.requireNonNull(wall);
    }

    boolean affects(Warehouse front, double radius) {
        return defender.equals(front.nationId()) && wall.within(front.position(), radius);
    }
}
