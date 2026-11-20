package dev.starsupply.api.event;

import dev.starsupply.api.SupplyModels.Settlement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Observational notification published off-thread after the local settlement transaction commits. */
public final class SupplySettlementEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Settlement settlement;

    public SupplySettlementEvent(Settlement settlement) {
        super(true);
        this.settlement = settlement;
    }

    public Settlement getSettlement() { return settlement; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
