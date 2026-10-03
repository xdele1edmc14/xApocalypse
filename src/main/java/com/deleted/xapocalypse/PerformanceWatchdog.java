package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PerformanceWatchdog - Monitors TPS and performs entity culling to prevent lag
 */
public class PerformanceWatchdog {

    private final xApocalypse plugin;
    private BukkitTask watchdogTask;
    
    private long checkIntervalTicks;
    
    // Minor fix: implement spawningPaused so HordeSpawnerTask can query it
    private boolean spawningPaused = false;
    
    // LOD (Level of Detail) system for distance-based AI throttling
    // Bug 7 fix: bounded LinkedHashMap — evicts dead/invalid entries and caps at 1000 to prevent unbounded growth
    private final Map<Zombie, Long> zombieLastAITick = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Zombie, Long> eldest) {
            return size() > 1000 || eldest.getKey().isDead() || !eldest.getKey().isValid();
        }
    };
    private final double lodDistanceThreshold = 32.0; // Blocks
    private final long lodTickInterval = 20L; // Ticks between AI updates for far zombies (1 second)
    private List<Player> lodSnapshotSource = List.of();
    private long lodSnapshotTick = Long.MIN_VALUE;
    private List<PlayerPosition> lodPlayerPositions = List.of();

    public PerformanceWatchdog(xApocalypse plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        checkIntervalTicks = Math.max(1L,
                plugin.getConfig().getLong("performance.check-interval-ticks", 100L));
    }

    public void start() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }
        watchdogTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkPerformance();
            }
        }.runTaskTimer(plugin, 0L, checkIntervalTicks);

        plugin.debugLog("PerformanceWatchdog started (check interval: " + checkIntervalTicks + " ticks)");
    }

    public void stop() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        restoreManagedZombieAI();
        zombieLastAITick.clear();
    }

    public void reload() {
        loadConfig();
        stop();
        start();
    }

    private void checkPerformance() {
        try {
            double currentTPS = getCurrentTPS();
            // Minor fix: honor performance.tps-threshold (previously the config value was ignored and
            // the thresholds were hardcoded 10/15). Pause new spawns below the threshold; resume once
            // TPS recovers a little above it (hysteresis avoids rapid flapping around the threshold).
            double tpsThreshold = plugin.getConfig().getDouble("performance.tps-threshold", 15.0);

            if (currentTPS < tpsThreshold) {
                plugin.debugLog("Low TPS: " + currentTPS + " (< " + tpsThreshold + ") - pausing spawning");
                pauseAllSpawning();
            } else if (currentTPS >= tpsThreshold + 1.5) {
                resumeSpawning();
            }

            // Entity-count culling always runs (it helps TPS recover).
            for (World world : Bukkit.getWorlds()) {
                if (!plugin.isWorldEnabled(world)) continue;

                int zombieCount = countZombiesInWorld(world);
                int maxZombies = Math.max(0,
                        plugin.getConfig().getInt("performance.max-total-zombies", 300));

                if (zombieCount > maxZombies) {
                    plugin.debugLog("Zombie count exceeded limit in " + world.getName() + ": " + zombieCount + " > " + maxZombies);
                    cullZombiesInWorld(world, zombieCount - maxZombies,
                            snapshotPlayerPositions(world.getPlayers()));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("PerformanceWatchdog error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private double getCurrentTPS() {
        try {
            // Use Paper API TPS monitoring
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0) {
                return tps[0]; // 1 minute average
            }
        } catch (Throwable t) {
            // Throwable (not just Exception) so a NoSuchMethodError on very old Spigot builds
            // without getTPS() can't kill the watchdog task.
            plugin.debugLog("Could not get TPS from server API: " + t.getMessage());
        }

        // Fallback if something goes wrong
        return 20.0;
    }

    private int countZombiesInWorld(World world) {
        // world.getEntitiesByClass is already filtered to Zombies
        // .size() is much faster than running a manual 'for' loop
        return world.getEntitiesByClass(Zombie.class).size();
    }

    private void pauseAllSpawning() {
        spawningPaused = true;
        plugin.debugLog("Spawning paused due to critical TPS.");
    }
    
    private void resumeSpawning() {
        if (spawningPaused) {
            spawningPaused = false;
            plugin.debugLog("Spawning resumed — TPS recovered.");
        }
    }

    public boolean isSpawningPaused() {
        return spawningPaused;
    }

    private void cullZombiesInWorld(
            World world, int amount, List<PlayerPosition> playerPositions) {
        List<Zombie> zombies = new ArrayList<>();
        
        for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
            if (entity instanceof Zombie zombie) {
                // Never delete vanilla mobs, NPCs, or Zombie-based MythicMobs.
                if (isManagedZombie(zombie)) zombies.add(zombie);
            }
        }

        if (zombies.isEmpty()) return;

        // Cache distances to avoid recalculating during sorting and culling
        List<ZombieDistance> zombieDistances = new ArrayList<>(zombies.size());
        for (Zombie zombie : zombies) {
            double distance = getDistanceToNearestPlayer(zombie, playerPositions);
            zombieDistances.add(new ZombieDistance(zombie, distance));
        }

        // Sort by distance to nearest player (furthest first)
        zombieDistances.sort((zd1, zd2) -> Double.compare(zd2.distance, zd1.distance));

        int culled = 0;
        for (ZombieDistance zd : zombieDistances) {
            if (culled >= amount) break;
            
            // Don't cull zombies that are very close to players
            if (zd.distance < 20) continue;
            
            zd.zombie.remove();
            culled++;
        }

        if (culled > 0) {
            plugin.debugLog("Culled " + culled + " zombies in " + world.getName() + " due to performance issues");
        }
    }

    private double getDistanceToNearestPlayer(
            Entity entity, List<PlayerPosition> playerPositions) {
        double minDistanceSquared = Double.MAX_VALUE;
        for (PlayerPosition player : playerPositions) {
            double distanceSquared = player.distanceSquared(
                    entity.getX(), entity.getY(), entity.getZ());
            if (distanceSquared < minDistanceSquared) minDistanceSquared = distanceSquared;
        }
        return Math.sqrt(minDistanceSquared);
    }

    public double getCurrentTPSValue() {
        return getCurrentTPS();
    }
    
    /**
     * Applies LOD state and decides whether plugin AI should run during HordeManager's existing
     * zombie pass. Keeping both decisions here eliminates the former second full-world scan.
     */
    public boolean manageZombieAndShouldTick(Zombie zombie, List<Player> worldPlayers, long currentTick) {
        if (zombie.isDead() || !zombie.isValid()) return false;
        if (!isManagedZombie(zombie)) {
            if (zombieLastAITick.remove(zombie) != null && !zombie.hasAI()) zombie.setAI(true);
            return false;
        }
        if (zombie.getPersistentDataContainer().has(
                xApocalypseUtils.ANIMATING_KEY, org.bukkit.persistence.PersistentDataType.BYTE)) return false;

        List<PlayerPosition> playerPositions = lodPlayerPositions(worldPlayers, currentTick);
        if (playerPositions.isEmpty()) {
            if (zombie.hasAI()) zombie.setAI(false);
            zombieLastAITick.put(zombie, currentTick);
            return false;
        }

        double nearestDistanceSquared = Double.MAX_VALUE;
        for (PlayerPosition player : playerPositions) {
            double distanceSquared = player.distanceSquared(
                    zombie.getX(), zombie.getY(), zombie.getZ());
            if (distanceSquared < nearestDistanceSquared) nearestDistanceSquared = distanceSquared;
        }

        if (nearestDistanceSquared > lodDistanceThreshold * lodDistanceThreshold) {
            if (zombie.hasAI()) zombie.setAI(false);
            Long lastTick = zombieLastAITick.get(zombie);
            if (lastTick == null || currentTick - lastTick >= lodTickInterval) {
                zombieLastAITick.put(zombie, currentTick);
                return true;
            }
            return false;
        }

        if (!zombie.hasAI()) zombie.setAI(true);
        zombieLastAITick.remove(zombie);
        return true;
    }

    public void finishAITick() {
        if (zombieLastAITick.size() > 500) {
            zombieLastAITick.entrySet().removeIf(entry -> entry.getKey().isDead() || !entry.getKey().isValid());
        }
        lodSnapshotSource = List.of();
        lodSnapshotTick = Long.MIN_VALUE;
        lodPlayerPositions = List.of();
    }

    private List<PlayerPosition> lodPlayerPositions(
            List<Player> players, long currentTick) {
        if (players == lodSnapshotSource && currentTick == lodSnapshotTick) {
            return lodPlayerPositions;
        }
        lodSnapshotSource = players;
        lodSnapshotTick = currentTick;
        lodPlayerPositions = snapshotPlayerPositions(players);
        return lodPlayerPositions;
    }

    private static List<PlayerPosition> snapshotPlayerPositions(List<Player> players) {
        if (players.isEmpty()) return List.of();
        List<PlayerPosition> snapshot = new ArrayList<>(players.size());
        for (Player player : players) {
            snapshot.add(new PlayerPosition(
                    player.getX(), player.getY(), player.getZ()));
        }
        return snapshot;
    }

    private record PlayerPosition(double x, double y, double z) {
        double distanceSquared(double otherX, double otherY, double otherZ) {
            double dx = x - otherX;
            double dy = y - otherY;
            double dz = z - otherZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private boolean isManagedZombie(Zombie zombie) {
        if (plugin.getUtils() == null || !plugin.getUtils().isCustomZombie(zombie)) return false;
        MythicMobsManager mythic = plugin.getMythicMobsManager();
        return mythic == null || !mythic.isMythicMob(zombie);
    }

    /** Restores AI that this watchdog disabled so reload, shutdown, or plugin removal is reversible. */
    private void restoreManagedZombieAI() {
        if (plugin.getUtils() == null) return;
        for (Zombie zombie : new ArrayList<>(zombieLastAITick.keySet())) {
            if (zombie.isDead() || !zombie.isValid()) continue;
            if (zombie.getPersistentDataContainer().has(
                    xApocalypseUtils.ANIMATING_KEY, org.bukkit.persistence.PersistentDataType.BYTE)) continue;
            if (!zombie.hasAI()) zombie.setAI(true);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                if (!isManagedZombie(zombie) || zombie.isDead() || !zombie.isValid()) continue;
                if (zombie.getPersistentDataContainer().has(
                        xApocalypseUtils.ANIMATING_KEY, org.bukkit.persistence.PersistentDataType.BYTE)) continue;
                if (!zombie.hasAI()) zombie.setAI(true);
            }
        }
    }

    /**
     * Helper class to cache zombie distance calculations
     */
    private static class ZombieDistance {
        final Zombie zombie;
        final double distance;

        ZombieDistance(Zombie zombie, double distance) {
            this.zombie = zombie;
            this.distance = distance;
        }
    }
}
