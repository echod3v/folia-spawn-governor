package io.github.jayden0903.spawnbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-server-only companion for the load generator in {@code bench/clients}.
 *
 * <ul>
 *   <li>Places bot players into reproducible layouts ({@code dense}, {@code grid}, {@code chain}, {@code random}).</li>
 *   <li>Writes {@code state.json} every two seconds (bot count and positions) for the orchestration scripts.</li>
 *   <li>Keeps the test world stable: bots cannot die, break, place or pick up anything, and explosions,
 *       fire and mob griefing do not change blocks while the bench is active.</li>
 *   <li>Aborts the run and kicks every bot if a player without the bot name prefix joins, or when the
 *       configured maximum duration expires.</li>
 * </ul>
 *
 * Never install this on a server real players use.
 */
public final class BenchProbe extends JavaPlugin implements Listener {
    private static final java.util.regex.Pattern TRAILING_DIGITS = java.util.regex.Pattern.compile("(\\d+)$");
    private final Map<UUID, Player> bots = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Object>> positions = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();
    private volatile boolean active = true;
    private volatile String layout = "join";
    private String prefix;
    private Path out;
    private long expiresAt;
    private int boundary;
    private World world;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefix = getConfig().getString("bot-name-prefix", "bench");
        out = Path.of(getConfig().getString("output-directory", "bench-output"));
        expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(Math.min(180, getConfig().getInt("max-duration-minutes", 60)));
        boundary = getConfig().getInt("abort-beyond-blocks", 12000);
        world = Bukkit.getWorld(getConfig().getString("world", "world"));
        try {
            Files.createDirectories(out);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create output directory " + out, e);
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getAsyncScheduler().runAtFixedRate(this, t -> {
            dump();
            if (System.currentTimeMillis() > expiresAt) stop("bench duration expired");
        }, 2, 2, TimeUnit.SECONDS);
        getLogger().warning("SpawnBench probe active: test servers only. Bots must be named '" + prefix + "*'.");
    }

    private boolean bot(Player p) { return p.getName().startsWith(prefix); }

    private boolean guarded() { return active && System.currentTimeMillis() < expiresAt; }

    private void count(String key) { counts.computeIfAbsent(key, k -> new LongAdder()).increment(); }

    private void stop(String reason) {
        if (!active) return;
        active = false;
        getLogger().warning("Bench stopped: " + reason);
        for (Player p : bots.values()) p.getScheduler().run(this, t -> p.kick(Component.text("Bench finished")), () -> {});
    }

    private void dump() {
        try {
            var m = new LinkedHashMap<String, Object>();
            m.put("at", System.currentTimeMillis());
            m.put("layout", layout);
            m.put("active", guarded());
            m.put("online", bots.size());
            m.put("players", new ArrayList<>(positions.values()));
            var c = new TreeMap<String, Long>();
            counts.forEach((k, v) -> c.put(k, v.sum()));
            m.put("counts", c);
            String json = new com.google.gson.Gson().toJson(m);
            Path temp = out.resolve("state.tmp");
            Files.writeString(temp, json);
            Files.move(temp, out.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(out.resolve("state.jsonl"), json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            getLogger().warning("state dump failed: " + e);
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!bot(p)) {
            count("nonBotJoinAbort");
            stop("a non-bot player joined");
            return;
        }
        bots.put(p.getUniqueId(), p);
        count("joins");
        p.setGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.getInventory().setItemInMainHand(new ItemStack(Material.WOODEN_SWORD));
        p.getScheduler().runAtFixedRate(this, t -> {
            if (!guarded()) { p.kick(Component.text("Bench finished")); t.cancel(); return; }
            if (p.getTicksLived() % 20 != 0) return;
            if (!p.isDead()) { p.setHealth(20); p.setFoodLevel(20); p.setSaturation(10); }
            positions.put(p.getUniqueId(), Map.of("name", p.getName(), "x", p.getX(), "y", p.getY(), "z", p.getZ(),
                    "world", p.getWorld().getName(), "ping", p.getPing()));
            if (Math.abs(p.getX()) > boundary || Math.abs(p.getZ()) > boundary) { count("boundaryAbort"); stop("bot left the bench area"); }
        }, () -> {}, 1, 1);
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        if (bots.remove(e.getPlayer().getUniqueId()) != null) {
            positions.remove(e.getPlayer().getUniqueId());
            count("quits");
        }
    }

    // --- keep the test world stable while the bench runs ---

    @EventHandler(priority = EventPriority.HIGHEST)
    public void lethal(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && bot(p) && e.getFinalDamage() >= p.getHealth() - 1) { e.setCancelled(true); count("lethalPrevented"); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void breakBlock(BlockBreakEvent e) { if (bot(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void placeBlock(BlockPlaceEvent e) { if (bot(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && bot(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void entityExplode(EntityExplodeEvent e) { if (guarded()) { e.blockList().clear(); e.setYield(0); } }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockExplode(BlockExplodeEvent e) { if (guarded()) { e.blockList().clear(); e.setYield(0); } }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void mobGrief(EntityChangeBlockEvent e) { if (guarded() && !(e.getEntity() instanceof Player)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void burn(BlockBurnEvent e) { if (guarded()) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void ignite(BlockIgniteEvent e) { if (guarded()) e.setCancelled(true); }

    // --- layouts ---

    /** Target column for bot index {@code n} (0-based) in the given layout. */
    static int[] target(String layout, int n, int param) {
        return switch (layout) {
            case "dense" -> new int[] {(n / 2 % 6) * 8, (n / 2 / 6) * 8};                         // pairs, 8 blocks apart
            case "grid" -> new int[] {(n % 10 - 5) * param, (n / 10 - 3) * param};                // 10 columns, default 650
            case "chain" -> new int[] {(n % 10 - 5) * param, (n / 10 - 3) * param};               // same grid, default 400
            case "random" -> {                                                                      // uniform square, default 4500
                var rnd = new Random(n * 7919L + param);
                yield new int[] {rnd.nextInt(2 * param) - param, rnd.nextInt(2 * param) - param};
            }
            default -> throw new IllegalArgumentException("unknown layout " + layout);
        };
    }

    private static int defaultParam(String layout) {
        return switch (layout) { case "grid" -> 650; case "chain" -> 400; case "random" -> 4500; default -> 0; };
    }

    private void place(Player p, String layout, int param, int attempt) {
        if (!guarded() || !p.isOnline()) return;
        if (attempt >= 96) { count("noSafePosition"); return; }
        var digits = TRAILING_DIGITS.matcher(p.getName());
        int n = digits.find() ? Integer.parseInt(digits.group(1)) - 1 : 0; // bots are named <prefix>001, <prefix>002, ...
        int[] base = target(layout, Math.max(0, n), param);
        int step = layout.equals("dense") ? 3 : 8;
        int bx = base[0] + (attempt % 12) * step, bz = base[1] + (attempt / 12) * step;
        world.getChunkAtAsync(bx >> 4, bz >> 4, false).thenAccept(chunk -> {
            if (chunk == null) { count("missingChunk"); return; }
            Bukkit.getRegionScheduler().execute(this, world, bx >> 4, bz >> 4, () -> {
                int y = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                var ground = world.getBlockAt(bx, y, bz);
                String type = ground.getType().name();
                boolean safe = ground.getType().isSolid() && !type.contains("LEAVES")
                        && !Set.of("MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "POWDER_SNOW").contains(type)
                        && world.getBlockAt(bx, y + 1, bz).isPassable() && !world.getBlockAt(bx, y + 1, bz).isLiquid()
                        && world.getBlockAt(bx, y + 2, bz).isPassable();
                if (!safe) { place(p, layout, param, attempt + 1); return; }
                p.getScheduler().run(this, t -> p.teleportAsync(new Location(world, bx + .5, y + 1.0, bz + .5))
                        .thenAccept(ok -> count(ok ? "teleports" : "teleportDenied")), () -> {});
            });
        }).exceptionally(err -> { count("placeErrors"); return null; });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        if (args[0].equals("stop")) { stop("stopped by command"); return true; }
        if (!Set.of("dense", "grid", "chain", "random").contains(args[0])) return false;
        String layoutName = args[0];
        int param = args.length > 1 ? Integer.parseInt(args[1]) : defaultParam(layoutName);
        layout = layoutName + (param > 0 ? ":" + param : "");
        for (Player p : bots.values()) place(p, layoutName, param, 0);
        sender.sendMessage("SpawnBench layout=" + layout + " bots=" + bots.size());
        return true;
    }
}
