package io.github.jayden0903.spawngovernor;

import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Boss;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Squid;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Overload-only natural spawn governor for Folia.
 *
 * <p>While a region's 5-second average tick time is at or below the target, nothing is changed. Above it,
 * whole natural-spawn ticks are skipped for that region only by cancelling
 * {@link PlayerNaturallySpawnCreaturesEvent} for every player of the region in that tick; Paper then drops
 * those chunks from the spawn pass, so the spawn attempts themselves are saved. Existing mobs keep vanilla
 * behaviour and despawn normally, so density falls just far enough to hold the target.</p>
 *
 * <p>Last resort, only while spawning is already fully cut and the region is still above
 * {@code despawn-assist-above-mspt}: mobs that vanilla itself may despawn are despawned sooner.</p>
 */
public final class SpawnGovernorPlugin extends JavaPlugin implements Listener {
    /** All fields are confined to the owning region thread, except the volatile timestamp read by housekeeping. */
    private static final class RegionState {
        final SpawnController controller;
        long decidedTick = Long.MIN_VALUE, firstTick = Long.MIN_VALUE, sweptTick = Long.MIN_VALUE;
        boolean allowThisTick = true;
        int players;
        long despawned;
        String sample = "";
        final List<Player> sampled = new ArrayList<>();
        final ArrayDeque<Player> sweepQueue = new ArrayDeque<>();
        List<Location> sweepAnchors = List.of();
        volatile long touchedNanos = System.nanoTime();

        RegionState(SpawnController.Settings settings) { controller = new SpawnController(settings); }
    }

    private RegionClock clock;
    private final Map<Object, RegionState> regions = new ConcurrentHashMap<>();
    /** Last region state per player, so a merged, split or new region inherits the throttle of its players. */
    private final Map<UUID, RegionState> lastByPlayer = new ConcurrentHashMap<>();
    private final LongAdder allowedPlayerTicks = new LongAdder(), skippedPlayerTicks = new LongAdder(), assistedDespawns = new LongAdder();

    private volatile boolean enabled, assist;
    private volatile double assistAbove, assistChance;
    private volatile SpawnController.Settings settings = SpawnController.Settings.defaults();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        try {
            clock = new RegionClock();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia region scheduler not found: this plugin requires Folia", e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> housekeeping(), 30, 30, TimeUnit.SECONDS);
        getLogger().info(String.format(Locale.ROOT, "Spawn governor %s: target %.1fms (hold band %.1fms, 5s region average), despawn assist %s above %.1fms",
                enabled ? "enabled" : "disabled", settings.targetMspt(), settings.holdBandMspt(), assist ? "on" : "off", assistAbove));
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        enabled = c.getBoolean("enabled", true);
        double target = clamp(c.getDouble("target-mspt", 42.0), 20.0, 49.0);
        settings = new SpawnController.Settings(
                target,
                clamp(c.getDouble("hold-band-mspt", 4.0), 1.0, target - 1.0),
                clamp(c.getDouble("minimum-spawn-fraction", 0.0), 0.0, 1.0),
                clamp(c.getDouble("max-decrease-per-second", 0.25), 0.05, 0.95),
                clamp(c.getDouble("recover-step-per-second", 0.02), 0.005, 1.0));
        assist = c.getBoolean("despawn-assist", true);
        assistAbove = clamp(c.getDouble("despawn-assist-above-mspt", 46.0), target, 54.0);
        assistChance = clamp(c.getDouble("despawn-assist-chance-per-second", 0.2), 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalSpawnTick(PlayerNaturallySpawnCreaturesEvent event) {
        if (!enabled) return;
        Object region = clock.currentRegion();
        if (region == null) return; // never disturb vanilla on an unexpected scheduler state
        long tick = clock.tick(region);
        RegionState state = regions.computeIfAbsent(region, ignored -> new RegionState(settings));
        if (state.firstTick == Long.MIN_VALUE) state.firstTick = tick;

        Player player = event.getPlayer();
        RegionState previous = lastByPlayer.put(player.getUniqueId(), state);
        if (previous != null && previous != state && tick - state.firstTick < 20) {
            state.controller.inheritFrom(previous.controller); // only during the new region's first second
        }

        if (state.decidedTick != tick) decide(state, region, tick);

        if (tick % 20 == 0) {
            state.players++;
            state.sampled.add(player);
            if (state.sample.isEmpty()) state.sample = player.getName() + "@" + player.getLocation().getBlockX() + "," + player.getLocation().getBlockZ();
        } else if (!state.sweepQueue.isEmpty() && state.sweptTick != tick) {
            state.sweptTick = tick;
            sweep(state, 3); // at most three players' surroundings per region tick
        }

        if (state.allowThisTick) {
            allowedPlayerTicks.increment();
        } else {
            event.setCancelled(true);
            skippedPlayerTicks.increment();
        }
    }

    private void decide(RegionState state, Object region, long tick) {
        state.decidedTick = tick;
        state.touchedNanos = System.nanoTime();
        if (tick % 20 == 0) {
            var controller = state.controller;
            state.sweepQueue.clear();
            if (assist && controller.atFloor() && controller.lastMspt() > assistAbove && !state.sampled.isEmpty()) {
                state.sweepQueue.addAll(state.sampled);
                var anchors = new ArrayList<Location>(state.sampled.size());
                for (Player p : state.sampled) if (p.isValid()) anchors.add(p.getLocation());
                state.sweepAnchors = anchors;
            }
            state.sampled.clear();
            state.players = 0;
            state.sample = "";
            controller.sample(clock.averageMspt(region));
        }
        state.allowThisTick = state.controller.allowSpawnTick();
    }

    /**
     * Last-resort acceleration of the vanilla random despawn. Only mobs vanilla itself may despawn
     * ({@code removeWhenFarAway}, beyond the 32-block soft range from every player, no name tag, leash or
     * vehicle) are removed, without drops, exactly like a vanilla despawn.
     */
    private void sweep(RegionState state, int budget) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        var random = ThreadLocalRandom.current();
        while (budget-- > 0 && !state.sweepQueue.isEmpty()) {
            Player player = state.sweepQueue.poll();
            if (player == null || !player.isValid() || !Bukkit.isOwnedByCurrentRegion(player)) continue;
            for (Entity entity : player.getNearbyEntities(128, 128, 128)) {
                if (!seen.add(entity) || !despawnable(entity) || !Bukkit.isOwnedByCurrentRegion(entity)) continue;
                if (nearestSquared(entity.getLocation(), state.sweepAnchors) <= 32 * 32) continue;
                if (random.nextDouble() < assistChance) {
                    entity.remove();
                    state.despawned++;
                    assistedDespawns.increment();
                }
            }
        }
    }

    static boolean despawnable(Entity entity) {
        if (!(entity instanceof Mob mob) || !mob.getRemoveWhenFarAway()) return false;
        if (mob.customName() != null || mob.isLeashed() || mob.isInsideVehicle() || !mob.getPassengers().isEmpty()) return false;
        if (mob instanceof Boss || mob instanceof Shulker || mob instanceof Warden || mob instanceof ElderGuardian) return false;
        if (mob instanceof Raider raider && (raider.getRaid() != null || raider.isPatrolLeader())) return false;
        return mob instanceof Enemy || mob instanceof Bat || mob instanceof Fish || mob instanceof Squid;
    }

    private static double nearestSquared(Location at, List<Location> anchors) {
        double best = Double.MAX_VALUE;
        for (Location p : anchors) if (p.getWorld() == at.getWorld()) best = Math.min(best, p.distanceSquared(at));
        return best;
    }

    private void housekeeping() {
        long now = System.nanoTime();
        long ttl = TimeUnit.SECONDS.toNanos(60);
        regions.values().removeIf(s -> now - s.touchedNanos > ttl);
        lastByPlayer.values().removeIf(s -> now - s.touchedNanos > ttl);
        StringBuilder governed = new StringBuilder();
        for (RegionState s : regions.values()) {
            if (s.controller.governed()) governed.append(describe(s));
        }
        if (!governed.isEmpty()) getLogger().info("Overloaded regions, natural spawning reduced:" + governed);
    }

    private static String describe(RegionState s) {
        return String.format(Locale.ROOT, " [%s players=%d mspt5s=%.1f spawn=%.0f%% assistedDespawns=%d]",
                s.sample, s.players, s.controller.lastMspt(), s.controller.fraction() * 100, s.despawned);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            regions.clear(); // new controllers pick up the new settings
            lastByPlayer.clear();
            sender.sendMessage("SpawnGovernor reloaded: enabled=" + enabled + " target=" + settings.targetMspt() + "ms");
            return true;
        }
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT,
                "SpawnGovernor enabled=%s regions=%d allowedPlayerTicks=%d skippedPlayerTicks=%d assistedDespawns=%d",
                enabled, regions.size(), allowedPlayerTicks.sum(), skippedPlayerTicks.sum(), assistedDespawns.sum()));
        for (RegionState s : regions.values()) out.append('\n').append(' ').append(describe(s));
        sender.sendMessage(out.toString());
        return true;
    }
}
