package edgruberman.bukkit.sleep;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/** raised when a player will stop ignoring sleep */
public class SleepAcknowledge extends PlayerEvent implements Cancellable {

    public SleepAcknowledge(final Player who) {
        super(who);
    }

    // --- cancellable event ----

    private boolean cancelled = false;

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    // ---- event handlers ----

    private static final HandlerList handlers = new HandlerList();

    public static HandlerList getHandlerList() {
        return SleepAcknowledge.handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return SleepAcknowledge.handlers;
    }

}
