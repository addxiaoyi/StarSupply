package dev.starsupply.api.event;

import dev.starsupply.api.SupplyModels.Operation;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** Fires on the actor's entity scheduler before requesting an order or inventory change. */
public final class SupplyOperationEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Operation operation;
    private final UUID orderId;
    private boolean cancelled;

    public SupplyOperationEvent(Player player, Operation operation, UUID orderId) {
        super(player);
        this.operation = operation;
        this.orderId = orderId;
    }

    public Operation getOperation() { return operation; }
    public UUID getOrderId() { return orderId; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
