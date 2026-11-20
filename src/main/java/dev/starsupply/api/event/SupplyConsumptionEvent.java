package dev.starsupply.api.event;

import dev.starsupply.api.SupplyModels.SupplyConsumption;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Async notification after real physical supply is consumed and its unique cycle receipt commits. */
public final class SupplyConsumptionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SupplyConsumption consumption;

    public SupplyConsumptionEvent(SupplyConsumption consumption) {
        super(true);
        this.consumption = Objects.requireNonNull(consumption);
    }

    public SupplyConsumption getConsumption() { return consumption; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
