package dev.rtpboost;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * RTPBoost: after a long-distance teleport, drop the player's chunk send
 * distance and raise it back gradually, pausing while the server is busy.
 * This spreads the chunk generation / sending burst (the CPU spike you see
 * after /rtp) over many seconds instead of one big hit.
 */
public final class RTPBoost extends JavaPlugin implements Listener, TabExecutor {

    /** State of one player's ramp-up. */
    private static final class Ramp {
        final int target;      // the player's original send distance
        int current;           // what is applied right now
        int sinceStep;         // ticks since the last step
        int stalled;           // ticks spent waiting for the server to calm down

        Ramp(int target, int current) {
            this.target = target;
            this.current = current;
        }
    }

    private final Map<UUID, Ramp> ramps = new HashMap<>();
    private BukkitTask loop;

    // config values
    private Set<TeleportCause> causes = EnumSet.noneOf(TeleportCause.class);
    private double minDistanceSq;
    private int startDistance;
    private int step;
    private int stepIntervalTicks;
    private int checkIntervalTicks;
    private int maxStallTicks;
    private double maxMspt;
    private double maxCpu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("rtpboost");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        startLoop();
        getLogger().info("RTPBoost enabled.");
    }

    @Override
    public void onDisable() {
        if (loop != null) {
            loop.cancel();
            loop = null;
        }
        // Give everyone their normal view distance back.
        for (Map.Entry<UUID, Ramp> e : ramps.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                p.setSendViewDistance(e.getValue().target);
            }
        }
        ramps.clear();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        causes = EnumSet.noneOf(TeleportCause.class);
        for (String name : c.getStringList("causes")) {
            try {
                causes.add(TeleportCause.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Unknown teleport cause in config: " + name);
            }
        }

        double minDist = Math.max(0, c.getDouble("min-distance", 256));
        minDistanceSq = minDist * minDist;

        startDistance = Math.max(2, c.getInt("ramp.start-view-distance", 3));
        step = Math.max(1, c.getInt("ramp.step", 1));
        stepIntervalTicks = Math.max(1, c.getInt("ramp.step-interval-ticks", 40));
        checkIntervalTicks = Math.max(1, c.getInt("ramp.check-interval-ticks", 10));
        maxStallTicks = Math.max(0, c.getInt("ramp.max-stall-seconds", 30)) * 20;
        maxMspt = c.getDouble("health.max-mspt", 40);
        maxCpu = c.getDouble("health.max-cpu", 0.80);
    }

    private void startLoop() {
        if (loop != null) {
            loop.cancel();
        }
        loop = getServer().getScheduler().runTaskTimer(this, this::tick, checkIntervalTicks, checkIntervalTicks);
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!causes.contains(e.getCause())) {
            return;
        }
        Player p = e.getPlayer();
        if (p.hasPermission("rtpboost.bypass")) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || to.getWorld() == null || from.getWorld() == null) {
            return;
        }
        boolean sameWorld = from.getWorld().equals(to.getWorld());
        if (sameWorld && from.distanceSquared(to) < minDistanceSq) {
            return;
        }
        begin(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ramps.remove(e.getPlayer().getUniqueId());
    }

    private void begin(Player p) {
        Ramp existing = ramps.get(p.getUniqueId());
        // If a ramp is already running, keep its original target.
        int target = existing != null ? existing.target : p.getSendViewDistance();
        int start = Math.min(startDistance, target);
        if (target <= start) {
            return; // nothing to ramp
        }
        ramps.put(p.getUniqueId(), new Ramp(target, start));
        p.setSendViewDistance(start);
    }

    // ------------------------------------------------------------------
    // Ramp loop
    // ------------------------------------------------------------------

    private void tick() {
        if (ramps.isEmpty()) {
            return;
        }
        boolean healthy = isHealthy();

        Iterator<Map.Entry<UUID, Ramp>> it = ramps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Ramp> entry = it.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            Ramp r = entry.getValue();
            r.sinceStep += checkIntervalTicks;
            if (r.sinceStep < stepIntervalTicks) {
                continue;
            }
            if (!healthy) {
                r.stalled += checkIntervalTicks;
                if (r.stalled < maxStallTicks) {
                    continue; // wait for the server to calm down
                }
            }
            r.stalled = 0;
            r.sinceStep = 0;
            r.current = Math.min(r.target, r.current + step);
            p.setSendViewDistance(r.current);
            if (r.current >= r.target) {
                it.remove();
            }
        }
    }

    private boolean isHealthy() {
        return Bukkit.getAverageTickTime() <= maxMspt && cpuLoad() <= maxCpu;
    }

    /** Share (0.0 - 1.0) of total CPU used by this server process. */
    private double cpuLoad() {
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean x) {
                double v = x.getProcessCpuLoad();
                return v < 0 ? 0 : v;
            }
        } catch (Throwable ignored) {
            // not available on this JVM
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Command
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "reload" -> {
                loadSettings();
                startLoop();
                sender.sendMessage("RTPBoost: config reloaded.");
            }
            case "status" -> {
                sender.sendMessage("RTPBoost status:");
                sender.sendMessage(String.format("  MSPT (avg): %.1f / limit %.1f", Bukkit.getAverageTickTime(), maxMspt));
                sender.sendMessage(String.format("  CPU: %.0f%% / limit %.0f%%", cpuLoad() * 100, maxCpu * 100));
                sender.sendMessage("  Healthy: " + isHealthy());
                if (ramps.isEmpty()) {
                    sender.sendMessage("  No players are ramping right now.");
                } else {
                    for (Map.Entry<UUID, Ramp> e : ramps.entrySet()) {
                        Player p = Bukkit.getPlayer(e.getKey());
                        String name = p != null ? p.getName() : e.getKey().toString();
                        sender.sendMessage("  " + name + ": " + e.getValue().current + " -> " + e.getValue().target + " chunks");
                    }
                }
            }
            default -> sender.sendMessage("Usage: /" + label + " <status|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("status", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
