package edgruberman.bukkit.sleep;

import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import edgruberman.bukkit.messagemanager.MessageLevel;

/**
 * Manages player associations in each world's sleep state.
 */
final class PlayerMonitor extends PlayerListener {
    
    PlayerMonitor(final Plugin plugin) {
        PluginManager pm = plugin.getServer().getPluginManager();
        
        pm.registerEvent(Event.Type.PLAYER_JOIN     , this, Event.Priority.Monitor, plugin);
        pm.registerEvent(Event.Type.PLAYER_TELEPORT , this, Event.Priority.Monitor, plugin);
        pm.registerEvent(Event.Type.PLAYER_BED_ENTER, this, Event.Priority.Monitor, plugin);
        pm.registerEvent(Event.Type.PLAYER_BED_LEAVE, this, Event.Priority.Monitor, plugin);
        pm.registerEvent(Event.Type.PLAYER_QUIT     , this, Event.Priority.Monitor, plugin);
    }
    
    @Override
    public void onPlayerJoin(final PlayerJoinEvent event) {
        // Ignore for untracked world sleep states.
        State state = State.tracked.get(event.getPlayer().getWorld());
        if (state == null) return;
        
        state.worldJoined(event.getPlayer());
    }
    
    @Override
    public void onPlayerTeleport(final PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        
        // Notify tracked sleep states of player moving between them.
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            State from = State.tracked.get(event.getFrom().getWorld());
            if (from != null) from.worldLeft(event.getPlayer());
            
            State to = State.tracked.get(event.getTo().getWorld());
            if (to != null) to.worldJoined(event.getPlayer());
        }
    }
    
    @Override
    public void onPlayerQuit(final PlayerQuitEvent event) {
        // Ignore for untracked world sleep states.
        State state = State.tracked.get(event.getPlayer().getWorld());
        if (state == null) return;
        
        state.worldLeft(event.getPlayer());
    }
    
    @Override
    public void onPlayerBedEnter(final PlayerBedEnterEvent event) {
        if (event.isCancelled()) return;
        
        // Ignore for untracked world sleep states.
        State state = State.tracked.get(event.getPlayer().getWorld());
        if (state == null) return;
        
        Main.messageManager.log(event.getPlayer().getName() + " entered bed in [" + event.getPlayer().getWorld().getName() + "]", MessageLevel.FINE);
        state.bedEntered(event.getPlayer());
    }
    
    @Override
    public void onPlayerBedLeave(final PlayerBedLeaveEvent event) {
        // Ignore for untracked world sleep states.
        State state = State.tracked.get(event.getPlayer().getWorld());
        if (state == null) return;
        
        // Determine if nightmare.
        // A CreatureSpawnEvent occurs for SpawnReason.BED just before a PlayerBedLeaveEvent and before any other processing.
        boolean nightmare = (NightmareTracker.lastBedSpawn != null);
        if (nightmare) {
            Main.messageManager.log(event.getPlayer().getName() + " is having a nightmare in [" + event.getPlayer().getWorld().getName() + "]", MessageLevel.FINE);
            NightmareTracker.lastBedSpawn = null;
        }
        
        Main.messageManager.log(event.getPlayer().getName() + " left bed in [" + event.getPlayer().getWorld().getName() + "]", MessageLevel.FINE);
        state.bedLeft(event.getPlayer(), nightmare);
    }
}