package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the core horde-spawning mechanics: the per-player horde spawn math (blood-moon and
 * scent multipliers, surface snapping, rising animation), the zombie AI tick loop (gated by
 * the LOD system), and the {@code isPluginSpawning} flag that lets plugin-spawned zombies
 * bypass the CreatureSpawnEvent mob-list gate.
 *
 * Extracted verbatim from the original xApocalypse monolith (Bug M1 single-horde cap,
 * Bug C1/M2/20 plugin-spawning bracketing, Bug 19 animating-tag handling preserved).
 */
public class HordeManager {

    private static final int MAX_TERRAIN_PROBES_PER_CYCLE = 10;

    private final xApocalypse plugin;
    private final xApocalypseUtils utils;
    private final UndeadSpawner undeadSpawner;

    // Bug 4 & 20 fix: flag so onEntitySpawn bypasses the mob-list check for plugin-spawned entities
    private boolean isPluginSpawning = false;
    private int remainingTerrainProbes = MAX_TERRAIN_PROBES_PER_CYCLE;

    public HordeManager(xApocalypse plugin, xApocalypseUtils utils, UndeadSpawner undeadSpawner) {
        this.plugin = plugin;
        this.utils = utils;
        this.undeadSpawner = undeadSpawner;
    }

    /** Called by UndeadSpawner before/after world.spawnEntity so onEntitySpawn skips the mob-list check. */
    public void setPluginSpawning(boolean value) {
        this.isPluginSpawning = value;
    }

    public boolean isPluginSpawning() {
        return isPluginSpawning;
    }

    void beginSpawnCycle() {
        remainingTerrainProbes = MAX_TERRAIN_PROBES_PER_CYCLE;
    }

    // === AI TICK SYSTEM ===

    public void startAITickTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                PerformanceWatchdog watchdog = plugin.getPerformanceWatchdog();
                long currentTick = Bukkit.getServer().getCurrentTick();
                for (World world : Bukkit.getWorlds()) {
                    if (!plugin.isWorldEnabled(world)) continue;

                    List<Player> players = world.getPlayers();
                    for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                        if (watchdog == null
                                || watchdog.manageZombieAndShouldTick(zombie, players, currentTick)) {
                            utils.tickZombieAI(zombie);
                        }
                    }
                }
                if (watchdog != null) watchdog.finishAITick();
            }
        }.runTaskTimer(plugin, 0L, 10L); // Tick every 0.5 seconds
    }

    // === CORE HORDE SPAWNING ===

    public void spawnZombiesNearPlayer(Player player, boolean isDayHordeSpawn) {
        // Guard clause: only spawn for SURVIVAL mode players, prevent Elytra spawns
        if (player.getGameMode() != GameMode.SURVIVAL || player.isGliding() || player.isFlying()) {
            return;
        }

        int baseAmount = Math.max(0, plugin.getConfig().getInt("apocalypse-settings.base-horde-size", 6));
        int variance = Math.max(0, plugin.getConfig().getInt("apocalypse-settings.horde-variance", 4));

        // Day spawns use a separate (smaller) horde size so daytime isn't as brutal as night.
        // isDayHordeSpawn was passed all the way from HordeSpawnerTask but was never consumed — fixed.
        if (isDayHordeSpawn) {
            baseAmount = Math.max(0, plugin.getConfig().getInt("apocalypse-settings.day-horde-size", Math.max(1, baseAmount / 3)));
            variance   = Math.max(0, plugin.getConfig().getInt("apocalypse-settings.day-horde-variance", Math.max(0, variance / 2)));
        }

        World world = player.getWorld();
        int safeVariance = Math.max(0, variance);

        double multiplier = 1.0;

        // Blood Moon multiplier
        if (plugin.isBloodMoonActive(world)) {
            multiplier = plugin.getBloodMoonHordeMultiplier();
        }

        // Scent multiplier
        if (plugin.getConfig().getBoolean("scent-system.enabled", true)) {
            double scent = plugin.getPlayerScent(player.getUniqueId());
            double scentScale = Math.max(0.0001,
                    plugin.getConfig().getDouble("scent-system.scent-scale", 15.0));
            multiplier *= (1.0 + (scent / scentScale));
        }

        // Apply multiplier BEFORE calculating final amount
        int finalHordeSize = (int) ((baseAmount + ThreadLocalRandom.current().nextInt(safeVariance + 1)) * multiplier);

        // Bug M1 fix: cap a single player's horde here, BEFORE the spawn loop. The blood moon ×
        // scent multipliers could push this to the global max-total-zombies (e.g. 300), and that
        // loop runs per eligible player per spawner tick — thousands of getHighestBlockAt() calls
        // that tank TPS independent of actual entity count. The global cap stays as a secondary guard.
        int maxSingleHorde = Math.max(0,
                plugin.getConfig().getInt("apocalypse-settings.max-single-horde-size", 30));
        finalHordeSize = Math.min(finalHordeSize, maxSingleHorde);

        // Cap with max-total-zombies instead of scent-system.spawn-cap
        int spawnCap = Math.max(0, plugin.getConfig().getInt("performance.max-total-zombies", 300));
        int existingZombies = world.getEntitiesByClass(Zombie.class).size();
        int availableSlots = Math.max(0, spawnCap - existingZombies);
        finalHordeSize = Math.min(finalHordeSize, availableSlots);

        boolean risingAnimation = plugin.getConfig().getBoolean(
                "apocalypse-settings.rising-animation", true);
        if (risingAnimation) {
            finalHordeSize = Math.min(finalHordeSize,
                    undeadSpawner.getRemainingAnimationCapacity());
        }
        if (finalHordeSize <= 0 || remainingTerrainProbes <= 0) return;

        plugin.debugLog("Attempting to spawn horde of size: " + finalHordeSize + " near " + player.getName() + " (Multiplier: " + multiplier + ")");

        int spawnRadius = Math.max(1,
                plugin.getConfig().getInt("apocalypse-settings.spawn-radius", 35));

        Location center = player.getLocation();
        int spawned = 0;
        int noSurface = 0;
        int claimed = 0;
        int spawnRejected = 0;
        for (int i = 0; i < finalHordeSize && remainingTerrainProbes > 0; i++) {
            double xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            Location spawnLoc = center.clone().add(xOffset, 0, zOffset);

            Location surface = probeSurface(spawnLoc);

            boolean insideClaim = surface != null && plugin.isInsideClaim(surface);

            // Retry once with a fresh random offset before giving up — reduces wasted
            // attempts near water, ravines, claims, or ocean biomes.
            if ((surface == null || insideClaim) && remainingTerrainProbes > 0) {
                xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
                zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
                spawnLoc = center.clone().add(xOffset, 0, zOffset);
                surface = probeSurface(spawnLoc);
                insideClaim = surface != null && plugin.isInsideClaim(surface);
            }

            if (surface == null) {
                noSurface++;
                continue;
            }
            if (insideClaim) {
                claimed++;
                continue;
            }

            Block surfaceBlock = surface.getBlock().getRelative(BlockFace.DOWN);
            BlockData surfaceData = surfaceBlock.getBlockData();

            if (risingAnimation) {
                long startDelayTicks = i % 5L;
                if (undeadSpawner.trySpawnUndeadRise(surface, surfaceBlock, surfaceData, startDelayTicks) != null) {
                    spawned++;
                } else {
                    spawnRejected++;
                }
            } else {
                Zombie zombie;
                isPluginSpawning = true;
                try {
                    zombie = (Zombie) surface.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
                } finally {
                    isPluginSpawning = false;
                }
                if (zombie != null) {
                    utils.assignZombieType(zombie);
                    spawned++;
                } else {
                    spawnRejected++;
                }
            }
        }

        if (spawned < finalHordeSize) {
            plugin.debugLog("Horde near " + player.getName() + ": " + spawned + "/" + finalHordeSize
                    + " spawned (no surface: " + noSurface + ", claimed: " + claimed
                    + ", spawn rejected: " + spawnRejected + ")");
        }
    }

    private Location probeSurface(Location candidate) {
        if (remainingTerrainProbes <= 0) return null;
        remainingTerrainProbes--;
        return undeadSpawner.getSurfaceSpawnLocation(candidate);
    }
}
