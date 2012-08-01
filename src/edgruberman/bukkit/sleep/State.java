package edgruberman.bukkit.sleep;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import edgruberman.bukkit.playeractivity.EventTracker;
import edgruberman.bukkit.playeractivity.PlayerActivity;
import edgruberman.bukkit.playeractivity.PlayerIdle;
import edgruberman.bukkit.playeractivity.consumers.AwayBack;

/**
 * Sleep state for a specific world
 */
public final class State implements Observer, Listener {

    /**
     * First world relative time (hours * 1000) associated with ability to
     * enter bed (Derived empirically)
     */
    private static final long TIME_NIGHT_START = 12540;

    /**
     * First world relative time (hours * 1000) associated with inability to
     * enter bed (Derived empirically)
     */
    private static final long TIME_NIGHT_END = 23455;

    /**
     * Number of ticks to wait before deep sleep will engage
     * Minecraft considers deep sleep to be 100 ticks in bed
     */
    private static final long TICKS_BEFORE_DEEP_SLEEP = 90;

    private static final long TICKS_PER_SECOND = 20;

    public final JavaPlugin plugin;
    public final World world;
    public final boolean isSleepEnabled;
    public final int forceCount;
    public final int forcePercent;
    public final int bedNoticeLimit;
    public final Collection<PotionEffect> rewardEffects = new HashSet<PotionEffect>();
    public final TemporaryBed temporaryBed;
    public Float rewardAddSaturation;
    public Float rewardSetExhaustion;

    public final EventTracker tracker;
    public AwayBack awayBack;

    public final Set<Player> players = new HashSet<Player>();
    public final Set<Player> playersInBed = new HashSet<Player>();
    public final Set<Player> playersIdle = new HashSet<Player>();
    public final Set<Player> playersIgnored = new HashSet<Player>();
    public final Set<Player> playersAway = new HashSet<Player>();

    private boolean hasGeneratedEnterBed = false;
    private boolean isForcingSleep = false;
    private CommandSender sleepForcer = null;

    private final Map<Player, Long> lastBedEnterMessage = new HashMap<Player, Long>();
    private final Map<Player, Long> lastBedLeaveMessage = new HashMap<Player, Long>();

    State(final JavaPlugin plugin, final World world, final Configuration config) {
        this.plugin = plugin;
        this.world = world;

        this.isSleepEnabled = config.getBoolean("sleep");
        this.bedNoticeLimit = config.getInt("bedNoticeLimit");
        this.loadReward(config.getConfigurationSection("reward"));

        if (config.getBoolean("force.enabled")) {
            this.forceCount = config.getInt("force.count");
            this.forcePercent = config.getInt("force.percent");
        } else {
            this.forceCount = -1;
            this.forcePercent = -1;
        }

        if (config.getBoolean("temporaryBed.enabled")) {
            this.temporaryBed = new TemporaryBed(this, config.getLong("temporaryBed.duration") * State.TICKS_PER_SECOND);
        } else {
            this.temporaryBed = null;
        }

        if (config.getBoolean("idle.enabled")) {
            this.tracker = new EventTracker(plugin);
            this.tracker.setDefaultPriority(EventPriority.HIGHEST); // One below Somnologist's MONITOR to ensure activity/idle status are updated before any processing in this State
            for (final String className : config.getStringList("idle.activity"))
                try {
                    this.tracker.addInterpreter(EventTracker.newInterpreter(className));
                } catch (final Exception e) {
                    plugin.getLogger().warning("Unsupported activity for " + world.getName() + ": " + className + "; " + e.getClass().getName() + "; " + e.getMessage());
                }

            this.tracker.activityPublisher.addObserver(this);
            this.tracker.idlePublisher.setThreshold(config.getLong("idle.duration") * 1000);
            this.tracker.idlePublisher.addObserver(this);
            this.tracker.idlePublisher.reset(this.world.getPlayers());
        } else {
            this.tracker = null;
        }

        if (config.getBoolean("idle.awayIdle") && config.getBoolean("idle.enabled")) {
            final Plugin paPlugin = Bukkit.getPluginManager().getPlugin("PlayerActivity");
            if (paPlugin != null) {
                this.plugin.getLogger().config("Using PlayerActivity v" + paPlugin.getDescription().getVersion() + " awayBack for awayIdle");
                final edgruberman.bukkit.playeractivity.Main playerActivity = (edgruberman.bukkit.playeractivity.Main) paPlugin;
                this.awayBack = playerActivity.awayBack;
                if (this.awayBack == null) plugin.getLogger().warning("Unable to activate awayIdle feature for [" + world.getName() + "]: PlayerActivity plugin's awayBack feature is not enabled");
                Bukkit.getPluginManager().registerEvents(this, plugin);
            } else {
                this.awayBack = ((Main) this.plugin).awayBack;
                if (this.awayBack == null) plugin.getLogger().warning("Unable to activate awayIdle feature for [" + world.getName() + "]: Sleep plugin's awayBack feature is not enabled");
            }
        } else {
            this.awayBack = null;
        }

        for (final Player player : world.getPlayers()) {
            this.players.add(player);
            this.lastBedEnterMessage.put(player, 0L);
            this.lastBedLeaveMessage.put(player, 0L);
            if (player.isSleeping()) this.playersInBed.add(player);
            if (this.tracker != null && this.tracker.idlePublisher.getIdle().contains(player)) this.playersIdle.add(player);
            if (this.isAwayIdle(player)) this.playersAway.add(player);
            if (player.hasPermission("sleep.ignore")) this.playersIgnored.add(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(final PluginDisableEvent disabled) {
        if (!disabled.getPlugin().getName().equals("PlayerActivity")) return;

        this.awayBack = null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(final PluginEnableEvent disabled) {
        if (!disabled.getPlugin().getName().equals("PlayerActivity")) return;

        final edgruberman.bukkit.playeractivity.Main playerActivity = (edgruberman.bukkit.playeractivity.Main) disabled.getPlugin();
        this.awayBack = playerActivity.awayBack;
    }

    private void loadReward(final ConfigurationSection reward) {
        if (reward == null || !reward.getBoolean("enabled")) return;

        final ConfigurationSection effects = reward.getConfigurationSection("effects");
        if (effects != null) {
            final Set<PotionEffect> potionEffects = new HashSet<PotionEffect>();
            for (final String type : effects.getKeys(false)) {
                final PotionEffectType effect = PotionEffectType.getByName(type);
                if (effect == null) {
                    this.plugin.getLogger().warning("Unrecognized reward PotionEffectType: " + type);
                    continue;
                }

                final ConfigurationSection entry = effects.getConfigurationSection(type);
                final int duration = entry.getInt("duration", 4);
                final int amplifier = entry.getInt("amplifier", 1);

                potionEffects.add(new PotionEffect(effect, duration, amplifier));
            }
            this.rewardEffects.addAll(potionEffects);
        }

        final ConfigurationSection food = reward.getConfigurationSection("food");
        if (food != null) {
            if (food.isDouble("addSaturation")) this.rewardAddSaturation = (float) food.getDouble("addSaturation");
            if (food.isDouble("setExhaustion")) this.rewardSetExhaustion = (float) food.getDouble("setExhaustion");
        }
    }

    private boolean isAwayIdle(final Player player) {
        return this.awayBack != null && this.awayBack.isAway(player);
    }

    /**
     * Ensure all object references have been released.
     */
    void clear() {
        HandlerList.unregisterAll(this);
        if (this.tracker != null) this.tracker.clear();
        if (this.temporaryBed != null) this.temporaryBed.clear();
        this.players.clear();
        this.playersInBed.clear();
        this.playersIdle.clear();
        this.playersIgnored.clear();
        this.playersAway.clear();
        this.lastBedEnterMessage.clear();
        this.lastBedLeaveMessage.clear();
    }

    // ---- Player Status Management ------------------------------------------

    /**
     * Factor in a player for sleep.
     *
     * @param joiner who joined this world
     */
    void add(final Player joiner) {
        this.players.add(joiner);
        this.lastBedEnterMessage.put(joiner, 0L);
        this.lastBedLeaveMessage.put(joiner, 0L);
        if (this.tracker != null && this.tracker.idlePublisher.getIdle().contains(joiner)) this.playersIdle.add(joiner);
        if (this.isAwayIdle(joiner)) this.playersAway.add(joiner);
        if (joiner.hasPermission("sleep.ignore")) this.playersIgnored.add(joiner);

        if (this.playersInBed.size() == 0) return;

        this.plugin.getLogger().log(Level.FINEST, "[" + this.world.getName() + "] Add: " + joiner.getName());

        if (this.playersIdle.contains(joiner) || this.playersAway.contains(joiner) || this.playersIgnored.contains(joiner)) {
            this.lull();
            return;
        }

        // Prevent interruption of forced sleep in progress
        if (this.isForcingSleep) {
            this.setSleepingIgnored(joiner, true, "Forcing Sleep: World Join");
            return;
        }

        // Notify of interruption when a natural sleep is in progress
        if (this.sleepersNeeded() == 1) {
            this.plugin.getLogger().fine("[" + this.world.getName() + "] Interruption: " + joiner.getName());
            Main.messenger.broadcast("interrupt", joiner, joiner.getDisplayName(), this.sleepersNeeded(), this.playersInBed.size(), this.sleepersPossible());
            return;
        }

        // Private notification of missed enter bed notification(s)
        if (this.hasGeneratedEnterBed)
            Main.messenger.tell(joiner, "status", this.plugin.getName(), this.sleepersNeeded(), this.playersInBed.size(), this.sleepersPossible());
    }

    /**
     * Process a player entering a bed.
     *
     * @param enterer who entered bed
     */
    void bedEntered(final Player enterer) {
        this.playersInBed.add(enterer);
        this.plugin.getLogger().log(Level.FINEST, "[" + this.world.getName() + "] Bed Entered: " + enterer.getName());

        this.setSleepingIgnored(enterer, false, "Entered Bed");

        if (enterer.hasPermission("sleep.autoforce")) {
            this.forceSleep(enterer);
            return;
        }

        if (System.currentTimeMillis() > (this.lastBedEnterMessage.get(enterer) + (this.bedNoticeLimit * 1000))) {
            this.lastBedEnterMessage.put(enterer, System.currentTimeMillis());
            Main.messenger.broadcast("bedEnter", enterer.getDisplayName(), this.sleepersNeeded(), this.playersInBed.size(), this.sleepersPossible());
            this.hasGeneratedEnterBed = true;
        }

        if (!this.isSleepEnabled) {
            this.plugin.getServer().getScheduler().scheduleSyncDelayedTask(this.plugin, new Insomnia(enterer, this.plugin), State.TICKS_BEFORE_DEEP_SLEEP);
            return;
        }

        this.lull();
    }

    /**
     * Process a player leaving a bed.
     *
     * @param leaver who left bed
     */
    void bedLeft(final Player leaver) {
        if (!this.playersInBed.remove(leaver)) return;

        this.plugin.getLogger().log(Level.FINEST, "[" + this.world.getName() + "] Bed Left: " + leaver.getName());

        if (this.isNight()) {
            if (this.playersIdle.contains(leaver) || this.playersAway.contains(leaver) || this.playersIgnored.contains(leaver)) this.lull();

            // Night time bed leaves only occur because of a manual action
            if (System.currentTimeMillis() > (this.lastBedLeaveMessage.get(leaver) + (this.bedNoticeLimit * 1000))) {
                this.lastBedLeaveMessage.put(leaver, System.currentTimeMillis());
                Main.messenger.broadcast("bedLeave", leaver.getDisplayName(), this.sleepersNeeded(), this.playersInBed.size(), this.sleepersPossible());
            }
            return;
        }

        // Morning
        leaver.addPotionEffects(this.rewardEffects);
        if (this.rewardAddSaturation != null) leaver.setSaturation(leaver.getSaturation() + this.rewardAddSaturation);
        if (this.rewardSetExhaustion != null) leaver.setExhaustion(this.rewardSetExhaustion);

        if (this.playersInBed.size() == 0) {
            // Last player to leave bed during a morning awakening
            this.hasGeneratedEnterBed = false;
            for (final Player player : this.world.getPlayers())
                this.setSleepingIgnored(player, false, "Awakening World");

            if (!this.isForcingSleep) return;

            // Generate forced sleep notification
            String type = "forceConfig";
            String name = this.plugin.getName();
            if (this.sleepForcer != null) {
                type = "forceCommand";
                name = this.sleepForcer.getName();
                if (this.sleepForcer instanceof Player) name = ((Player) this.sleepForcer).getDisplayName();
            }
            Main.messenger.broadcast(type, name, this.sleepersNeeded(), this.playersInBed.size(), this.sleepersPossible());

            // Allow activity to again cancel idle status in order to remove ignored sleep
            this.sleepForcer = null;
            this.isForcingSleep = false;
        }

    }

    /**
     * Remove a player from consideration for sleep.
     *
     * @param leaver who left world
     */
    void remove(final Player leaver) {
        this.players.remove(leaver);
        this.playersIdle.remove(leaver);
        this.playersAway.remove(leaver);
        this.playersIgnored.remove(leaver);
        this.bedLeft(leaver);

        this.lastBedEnterMessage.remove(leaver);
        this.lastBedLeaveMessage.remove(leaver);

        if (this.playersInBed.size() == 0) return;

        this.plugin.getLogger().log(Level.FINEST, "[" + this.world.getName() + "] Remove: " + leaver.getName());
        this.lull();
    }

    /**
     * Receives notification of player activity and idle from the EventTracker.
     * (This could be called on high frequency events such as PlayerMoveEvent.)
     */
    @Override
    public void update(final Observable o, final Object arg) {
        // Player Idle
        if (arg instanceof PlayerIdle) {
            final PlayerIdle idle = (PlayerIdle) arg;
            if (!idle.player.getWorld().equals(this.world)) return;

            this.playersIdle.add(idle.player);
            this.lull();
            return;
        }

        // Player Activity
        final PlayerActivity activity = (PlayerActivity) arg;
        if (!activity.player.getWorld().equals(this.world)) return;

        this.playersIdle.remove(activity.player);

        // Activity should not remove ignore status if not currently ignoring
        if (!activity.player.isSleepingIgnored()) return;

        // Activity should not remove ignore status when forcing sleep, or when force sleep would occur after not being idle
        if (this.isForcingSleep) return;

        // Activity should not remove ignore status for always ignored players
        if (this.playersIgnored.contains(activity.player)) return;

        // Activity should not remove ignore status for away players
        if (this.playersAway.contains(activity.player)) return;

        this.setSleepingIgnored(activity.player, false, "Activity: " + activity.event.getEventName());

        this.lull(); // Necessary in case player is idle before a natural sleep that would have caused a force
    }

    public void setAway(final Player player) {
        if (this.awayBack == null) return;

        this.playersAway.add(player);
        this.lull();
    }

    public void setBack(final Player player) {
        if (this.awayBack == null) return;

        this.playersAway.remove(player);
        this.setSleepingIgnored(player, false, "Back");

        this.add(player);
    }

    // ---- Sleep Management --------------------------------------------------

    /**
     * Configure idle players, and always ignored players to ignore sleep.
     * If all other players in the world are then either in bed or ignoring
     * sleep a natural sleep cycle should automatically commence. (If forced
     * sleep is defined and requirements are met, sleep will be forced.)
     */
    public void lull() {
        if (!this.isNight() || this.playersInBed.size() == 0) return;

        // Configure always ignored players to ignore sleep
        for (final Player player : this.playersIgnored)
            this.setSleepingIgnored(player, true, "Always Ignored");

        // Configure idle players to ignore sleep
        for (final Player player : this.playersIdle)
            this.setSleepingIgnored(player, true, "Idle");

        // Configure away players to ignore sleep
        for (final Player player : this.playersAway)
            this.setSleepingIgnored(player, true, "Away");

        if (this.plugin.getLogger().isLoggable(Level.FINER))
            this.plugin.getLogger().finer("[" + this.world.getName() + "] " + this.description());

        if (this.forceCount <= -1 && this.forcePercent <= -1) return;

        // Let natural sleep happen if everyone is in bed
        if (this.playersInBed.size() == this.sleepersPossible().size()) return;

        // Force sleep if no more are needed
        if (this.sleepersNeeded() == 0) this.forceSleep(null);
    }

    /**
     * Manually force sleep for all players.
     *
     * @param sender source that is manually forcing sleep; null for config
     */
    public void forceSleep(final CommandSender sender) {
        // Indicate forced sleep for this world to ensure activity does not negate ignore status
        this.isForcingSleep = true;
        this.sleepForcer = sender;

        // Set sleeping ignored for players not already in bed, or entering bed, or ignoring sleep to allow Minecraft to manage sleep normally
        for (final Player player : this.world.getPlayers())
            this.setSleepingIgnored(player, true, "Forcing Sleep");
    }

    /**
     * Set whether or not a player ignores sleep status checks.
     *
     * @param player player to set sleeping ignored status on
     * @param ignore true to set player to ignore sleeping; false otherwise
     * @param reason brief description for logging/troubleshooting purposes
     */
    public void setSleepingIgnored(final Player player, final boolean ignore, final String reason) {
        // Don't modify players already set as expected
        if (player.isSleepingIgnored() == ignore) return;

        // Don't ignore players in bed
        if (ignore && this.playersInBed.contains(player)) return;

        this.plugin.getLogger().log(Level.FINE, "[" + this.world.getName() + "] Setting " + player.getName() + " to" + (ignore ? "" : " not") + " ignore sleep (" + reason + ")");
        player.setSleepingIgnored(ignore);
    }

    // ---- Current Status Summarizing ----------------------------------------

    /**
     * Number of players still needed to enter bed for sleep to occur.
     *
     * @return number of players still needed; 0 if no more are needed
     */
    public int sleepersNeeded() {
        final int possible = this.sleepersPossible().size();
        final int inBed = this.playersInBed.size();

        // Need 100% of possible if percent not specified
        final double forcePercent = (((this.forcePercent > 0) && (this.forcePercent < 100)) ? this.forcePercent : 100);
        final int needPercent = (int) Math.ceil(forcePercent / 100 * possible);

        // Use all possible if count not specified
        final int needCount = (this.forceCount > 0 ? this.forceCount : possible);

        // Need lowest count to satisfy either count or percent
        int need = Math.min(needCount, needPercent) - inBed;

        // Can't need less than no one
        if (need < 0) need = 0;

        // Can't need more than who is possible
        if (need > possible) need = possible;

        // Always need at least 1 person actually in bed
        if (inBed == 0 && need == 0) need = 1;

        return need;
    }

    /**
     * Total number of players considered for sleep.
     *
     * @return number of active, not always ignored, and not already in bed
     */
    public Set<Player> sleepersPossible() {
        final Set<Player> possible = new HashSet<Player>(this.players);
        possible.removeAll(this.playersIdle);
        possible.removeAll(this.playersAway);
        possible.removeAll(this.playersIgnored);
        possible.addAll(this.playersInBed); // Add back in any players idle and/or ignored that are also in bed

        return possible;
    }

    /**
     * Description of status of sleep cycle.
     *
     * @return text description of status
     */
    private String description() {
        // Example output:
        // "Sleep needs +4; 3 in bed out of 7 possible = 42%";
        // "Sleep needs +2; 3 in bed (forced when 5) out of 7 possible = 42% (forced when 50%)";
        // "Sleep needs +2; 3 in bed (forced when 5) out of 7 possible = 42%";
        // "Sleep needs +1; 3 in bed out of 7 possible = 42% (forced when 50%)";
        final int need = this.sleepersNeeded();
        final int count = this.playersInBed.size();
        final int possible = this.sleepersPossible().size();
        final int requiredPercent = (this.forcePercent <= 100 ? this.forcePercent : 100);
        final int currentPercent = (int) Math.floor((double) count / (possible > 0 ? possible : 1) * 100);

        return "Sleep needs " + (need > 0 ? "+" + need : "no more") + ";"
            + " " + count + " in bed" + (this.forceCount > 0 ? " (forced when " + this.forceCount + ")" : "")
            + " out of " + possible + " possible"
            + " = " + currentPercent + "%" + (requiredPercent > 0 ? " (forced when " + requiredPercent + "%)" : "")
        ;
    }

    /**
     * Determine if world time will let a player get in to bed.
     *
     * @return true if time allows bed usage; otherwise false
     */
    public boolean isNight() {
        final long now = this.world.getTime();

        if ((State.TIME_NIGHT_START <= now) && (now < State.TIME_NIGHT_END)) return true;

        return false;
    }

}
