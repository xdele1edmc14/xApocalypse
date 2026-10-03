package com.deleted.xapocalypse;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAPIHelper;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class MythicMobsManager {
    private static final int MAX_PLACEMENT_PROBES_PER_SEARCH = 8;
    private static final int CLEANUP_BATCH_SIZE = 10;
    private static final NamespacedKey MYTHIC_ENTITY_KEY =
            new NamespacedKey("xapocalypse", "mythic_entity");
    private static final NamespacedKey BLOOD_MOON_MUTANT_KEY =
            new NamespacedKey("xapocalypse", "blood_moon_mutant");

    private final xApocalypse plugin;
    private final Logger log;
    private BukkitAPIHelper mmAPI;
    private boolean mythicMobsEnabled = false;
    private final Set<UUID> activeMutants = new HashSet<>();
    private BukkitTask spawnTickTask = null;
    private String mobType;
    private int maxGlobalCap;
    private double spawnChance;
    private int spawnRadiusMin;
    private int spawnRadiusMax;
    private int spawnTickInterval;
    private boolean spawnSoundEnabled;
    private Sound spawnSound;
    private float spawnSoundVolume;
    private float spawnSoundPitch;
    private double spawnSoundRadius;
    // Fix RC4: Track consecutive "blood moon inactive" ticks before stopping the loop.
    // A single false return from isBloodMoonActive() can occur during a cross-world
    // teleport, a BetterRTP staging teleport, or a brief state-write race between the
    // blood moon task and the spawn tick task.  Stopping immediately on the first miss
    // is a one-way door — the loop is gone for the rest of the blood moon.
    // Three consecutive misses (~15 s at the default 100-tick interval) is enough to
    // confirm the blood moon is genuinely over without being sensitive to transient blips.
    private int bloodMoonMissCount = 0;

    public MythicMobsManager(xApocalypse plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.init();
    }

    public void init() {
        this.loadConfig();
    }

    public void loadConfig() {
        FileConfiguration cfg = this.plugin.getConfig();
        this.mobType = cfg.getString("mythicmobs.integration.mob-type", "The_Mutant");
        this.maxGlobalCap = Math.max(0, cfg.getInt("mythicmobs.integration.max-global-cap", 15));
        this.spawnChance = Math.max(0.0, Math.min(1.0,
                cfg.getDouble("mythicmobs.integration.spawn-chance", 0.20)));
        this.spawnRadiusMin = Math.max(1, cfg.getInt("mythicmobs.integration.spawn-radius.min", 20));
        this.spawnRadiusMax = Math.max(this.spawnRadiusMin + 1,
                cfg.getInt("mythicmobs.integration.spawn-radius.max", 40));
        this.spawnTickInterval = Math.max(20,
                cfg.getInt("mythicmobs.integration.spawn-tick-interval", 100));
        this.spawnSoundEnabled = cfg.getBoolean("mythicmobs.integration.spawn-sound.enabled", true);
        this.spawnSoundVolume = Math.max(0.0f,
                (float) cfg.getDouble("mythicmobs.integration.spawn-sound.volume", 1.0));
        this.spawnSoundPitch = Math.max(0.0f,
                (float) cfg.getDouble("mythicmobs.integration.spawn-sound.pitch", 0.8));
        this.spawnSoundRadius = Math.max(0.0,
                cfg.getDouble("mythicmobs.integration.spawn-sound.radius", 48.0));
        this.spawnSound = this.spawnSoundEnabled ? parseSound(cfg.getString(
                "mythicmobs.integration.spawn-sound.name", "ENTITY_ENDER_DRAGON_GROWL")) : null;

        hookMythicMobs();
    }

    private void hookMythicMobs() {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            this.log.warning("[MythicMobs] MythicMobs not found — smart spawn disabled.");
            this.mythicMobsEnabled = false;
            this.mmAPI = null;
        } else {
            try {
                this.mmAPI = MythicBukkit.inst().getAPIHelper();
                Optional<MythicMob> mob = MythicBukkit.inst().getMobManager().getMythicMob(this.mobType);
                if (mob.isEmpty()) {
                    this.log.warning("[MythicMobs] Mob type '" + this.mobType + "' not found in MythicMobs.");
                    this.mythicMobsEnabled = false;
                    this.activeMutants.clear();
                } else {
                    this.log.info("[MythicMobs] Hooked in. Mob type '" + this.mobType + "' verified.");
                    this.mythicMobsEnabled = true;
                    this.activeMutants.clear();
                    rebuildTrackedMutants();
                }
            } catch (LinkageError e) {
                disableForLinkageError("hook into the MythicMobs API", e);
            } catch (Exception e) {
                this.log.severe("[MythicMobs] Failed to hook into MythicMobs API: " + e.getMessage());
                this.mythicMobsEnabled = false;
                this.mmAPI = null;
            }
        }
    }

    private void disableForLinkageError(String action, LinkageError error) {
        this.log.severe("[MythicMobs] Could not " + action + " because MythicMobs, its mob pack, "
                + "or the server uses incompatible Minecraft mappings/versions. Install builds that "
                + "match this exact server version and restart the server. Cause: " + error);
        this.mythicMobsEnabled = false;
        this.mmAPI = null;
        this.stopSpawnTickLoop();
    }

    public void onBloodMoonStart() {
        if (!this.mythicMobsEnabled) return;
        this.bloodMoonMissCount = 0; // reset miss counter before starting a new loop

        // Idempotency: onBloodMoonStart can be re-signalled without an intervening end — e.g. an
        // admin /xa forcebloodmoon over an already-active natural blood moon, or the onEnable
        // resume path. Spawn the guaranteed Mutant only on a genuine start (loop not already
        // running), or we'd spawn a duplicate boss + double broadcast and burn a global-cap slot.
        // Using the live loop state as the signal is self-healing — if the miss-counter stopped the
        // loop, a later start correctly counts as fresh.
        boolean alreadyRunning = this.spawnTickTask != null && !this.spawnTickTask.isCancelled();
        if (alreadyRunning) {
            this.log.info("[MythicMobs] Blood Moon re-signalled while spawn loop already active — skipping duplicate guaranteed Mutant.");
        } else {
            this.log.info("[MythicMobs] Blood Moon started — spawning guaranteed Mutant.");
            this.spawnGuaranteedMutant();
        }
        this.startSpawnTickLoop();
    }

    public void onBloodMoonEnd() {
        this.stopSpawnTickLoop();
        this.pruneDeadMutants();
        this.log.info("[MythicMobs] Blood Moon ended. Mutants still alive: " + this.activeMutants.size());
    }

    /** Removes loaded Blood-Moon Mutants and retains unloaded UUIDs for deferred chunk cleanup. */
    public int despawnActiveMutants() {
        Queue<UUID> pending = new ArrayDeque<>(this.activeMutants);
        int queued = pending.size();
        scheduleMutantCleanup(pending, new int[]{0});
        return queued;
    }

    private void scheduleMutantCleanup(Queue<UUID> pending, int[] removed) {
        if (pending.isEmpty() || !plugin.isEnabled()) {
            if (removed[0] > 0) {
                plugin.debugLog("[MythicMobs] Blood moon ended — despawned "
                        + removed[0] + " Mutant(s).");
            }
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) return;
            for (int processed = 0;
                    processed < CLEANUP_BATCH_SIZE && !pending.isEmpty(); processed++) {
                UUID uuid = pending.remove();
                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null) continue;
                if (entity.isDead()) {
                    activeMutants.remove(uuid);
                    continue;
                }
                if (!entity.getPersistentDataContainer().has(
                        BLOOD_MOON_MUTANT_KEY, PersistentDataType.BYTE)) continue;
                entity.remove();
                activeMutants.remove(uuid);
                removed[0]++;
            }
            scheduleMutantCleanup(pending, removed);
        });
    }

    /**
     * Restarts ONLY the periodic spawn tick loop (no guaranteed Mutant) — used after the scheduler's
     * cancelTasks() wipes it, e.g. across a /xa reload during an active blood moon. Without this the
     * periodic Mutant spawns would stay dead until the next blood moon.
     */
    public void resumeSpawnLoop() {
        if (!this.mythicMobsEnabled) return;
        this.bloodMoonMissCount = 0;
        this.startSpawnTickLoop();
    }

    public int spawnMutantCommand(Player player, int count, int radius) {
        if (!this.mythicMobsEnabled) {
            player.sendMessage("§c[MythicMobs] MythicMobs is not enabled on this server.");
            return 0;
        } else {
            this.pruneDeadMutants();
            int spawned = 0;
            int noValidLocation = 0;
            int failedSpawn = 0;

            for(int i = 0; i < count; ++i) {
                if (this.maxGlobalCap > 0 && this.activeMutants.size() >= this.maxGlobalCap) {
                    player.sendMessage("§e[MythicMobs] Global cap reached.");
                    break;
                }

                Location loc = this.findSpawnLocation(player.getLocation(), radius, radius + 2, false);
                if (loc == null) {
                    noValidLocation++;
                    continue;
                }

                Entity entity = this.spawnMythicMob(loc, false);
                if (entity != null) {
                    ++spawned;
                } else {
                    failedSpawn++;
                }
            }

            if (spawned < count) {
                this.plugin.debugLog("[MythicMobs] Mutant command near " + player.getName() + ": "
                        + spawned + "/" + count + " spawned (no location: " + noValidLocation
                        + ", API spawn failed: " + failedSpawn + ").");
            }
            return spawned;
        }
    }

    private void spawnGuaranteedMutant() {
        List<? extends Player> online = Bukkit.getOnlinePlayers().stream()
                .filter((p) -> this.plugin.isWorldEnabled(p.getWorld()) && !this.plugin.isLobbyWorld(p.getWorld()))
                .toList();
        if (!online.isEmpty()) {
            this.pruneDeadMutants();
            if (this.maxGlobalCap <= 0 || this.activeMutants.size() < this.maxGlobalCap) {
                Player target = (Player)online.get(ThreadLocalRandom.current().nextInt(online.size()));
                Location loc = this.findSpawnLocation(target.getLocation(), this.spawnRadiusMin, this.spawnRadiusMax, true);
                if (loc == null) {
                    Location fallback = target.getLocation().add(
                            (double)ThreadLocalRandom.current().nextInt(-5, 5), 0.0,
                            (double)ThreadLocalRandom.current().nextInt(-5, 5));
                    fallback = this.snapToGround(fallback);
                    if (fallback == null || this.plugin.isInsideClaim(fallback)) return;
                    loc = fallback;
                }

                Entity entity = this.spawnMythicMob(loc, true);
                if (entity != null) {
                    this.broadcastBloodMoonSpawn(target);
                }

            }
        }
    }

    private void startSpawnTickLoop() {
        this.stopSpawnTickLoop();
        this.bloodMoonMissCount = 0;
        this.spawnTickTask = (new BukkitRunnable() {
            public void run() {
                World world = MythicMobsManager.this.plugin.getBloodMoon().getReferenceWorld();

                // Delegate the active check entirely to the plugin — it already handles
                // forced blood moon real-time elapsed vs natural night-time checks.
                if (world == null || !MythicMobsManager.this.plugin.isBloodMoonActive(world)) {
                    // Fix RC4: Use a miss counter instead of stopping on the first false return.
                    // Cross-world teleports (Multiverse /mvtp, BetterRTP) can cause a single
                    // tick where isBloodMoonActive() transiently returns false even though the
                    // blood moon is still active.  Stopping immediately makes that a permanent
                    // one-way door.  Require 3 consecutive misses (~15 s) before giving up.
                    if (++MythicMobsManager.this.bloodMoonMissCount >= 3) {
                        MythicMobsManager.this.stopSpawnTickLoop();
                    }
                    return;
                }
                MythicMobsManager.this.bloodMoonMissCount = 0; // clear on a successful check
                MythicMobsManager.this.pruneDeadMutants();
                if (MythicMobsManager.this.activeMutants.size() < MythicMobsManager.this.maxGlobalCap || MythicMobsManager.this.maxGlobalCap <= 0) {
                    List<Player> players = new ArrayList<>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (MythicMobsManager.this.plugin.isWorldEnabled(player.getWorld())
                                && !MythicMobsManager.this.plugin.isLobbyWorld(player.getWorld())) {
                            players.add(player);
                        }
                    }
                    Collections.shuffle(players);
                    for (Player player : players) {
                        if (ThreadLocalRandom.current().nextDouble()
                                >= MythicMobsManager.this.spawnChance) continue;
                        Location loc = MythicMobsManager.this.findSpawnLocation(
                                player.getLocation(), MythicMobsManager.this.spawnRadiusMin,
                                MythicMobsManager.this.spawnRadiusMax, true);
                        if (loc != null) MythicMobsManager.this.spawnMythicMob(loc, true);
                        return;
                    }

                }
            }
        }).runTaskTimer(this.plugin, (long)this.spawnTickInterval, (long)this.spawnTickInterval);
    }

    private void stopSpawnTickLoop() {
        if (this.spawnTickTask != null && !this.spawnTickTask.isCancelled()) {
            this.spawnTickTask.cancel();
            this.spawnTickTask = null;
        }

    }

    private Entity spawnMythicMob(Location loc, boolean bloodMoonSpawn) {
        if (!this.mythicMobsEnabled || this.mmAPI == null) return null;
        if (!isChunkLoaded(loc)) return null;

        if (loc == null) {
            return null;
        } else {
            try {
                Entity entity = this.mmAPI.spawnMythicMob(this.mobType, loc);
                if (entity != null) {
                    entity.getPersistentDataContainer().set(
                            MYTHIC_ENTITY_KEY, PersistentDataType.BYTE, (byte) 1);
                    if (bloodMoonSpawn) {
                        entity.getPersistentDataContainer().set(
                                BLOOD_MOON_MUTANT_KEY, PersistentDataType.BYTE, (byte) 1);
                    }
                    this.activeMutants.add(entity.getUniqueId());
                    this.plugin.debugLog("[MythicMobs] Spawned at " + this.formatLoc(loc));
                    this.playSpawnSound(entity.getLocation());
                }

                return entity;
            } catch (LinkageError e) {
                disableForLinkageError("spawn '" + this.mobType + "'", e);
                return null;
            } catch (Exception e) {
                this.log.warning("[MythicMobs] Failed to spawn " + this.mobType + ": " + e.getMessage());
                return null;
            }
        }
    }

    private Location findSpawnLocation(Location anchor, int minRadius, int maxRadius, boolean avoidClaims) {
        Player nearestPlayer = this.getNearestPlayer(anchor);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for(int attempt = 0; attempt < MAX_PLACEMENT_PROBES_PER_SEARCH; ++attempt) {
            double angle = rng.nextDouble((double)0.0F, (Math.PI * 2D));
            double dist = rng.nextDouble((double)minRadius, (double)maxRadius);
            double x = anchor.getX() + Math.cos(angle) * dist;
            double z = anchor.getZ() + Math.sin(angle) * dist;
            Location candidate = new Location(anchor.getWorld(), x, anchor.getY(), z);
            if (!isChunkLoaded(candidate)) continue;
            candidate = this.snapToGround(candidate);
            boolean blockedByClaim = candidate != null && avoidClaims && this.plugin.isInsideClaim(candidate);
            if (candidate != null && !blockedByClaim
                    && (nearestPlayer == null || !this.hasLineOfSight(nearestPlayer, candidate)
                    || attempt == MAX_PLACEMENT_PROBES_PER_SEARCH - 1)) {
                return candidate;
            }
        }

        return null;
    }

    private Sound parseSound(String soundName) {
        if (soundName == null || soundName.isBlank()) return null;
        try {
            return Sound.valueOf(soundName.trim().toUpperCase().replace('.', '_'));
        } catch (IllegalArgumentException e) {
            this.log.warning("Invalid mythicmobs.integration.spawn-sound.name: '" + soundName
                    + "' - the Mutant spawn sound will be skipped");
            return null;
        }
    }

    private void playSpawnSound(Location location) {
        if (!this.spawnSoundEnabled || this.spawnSound == null || location.getWorld() == null) return;
        double radiusSquared = this.spawnSoundRadius * this.spawnSoundRadius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                player.playSound(player.getLocation(), this.spawnSound,
                        this.spawnSoundVolume, this.spawnSoundPitch);
            }
        }
    }

    private Location snapToGround(Location loc) {
        if (loc == null || this.plugin.getUndeadSpawner() == null) return null;
        if (!isChunkLoaded(loc)) return null;
        return this.plugin.getUndeadSpawner().getSurfaceSpawnLocation(loc);
    }

    private boolean isChunkLoaded(Location location) {
        if (location == null || location.getWorld() == null) return false;
        return location.getWorld().isChunkLoaded(
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private boolean hasLineOfSight(Player player, Location target) {
        if (target == null) {
            return false;
        } else {
            try {
                return player.hasLineOfSight(target);
            } catch (Exception var4) {
                return false;
            }
        }
    }

    private Player getNearestPlayer(Location loc) {
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(loc.getWorld())) {
                double d = p.getLocation().distanceSquared(loc);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = p;
                }
            }
        }

        return nearest;
    }

    // Bug C fix: Bukkit.getEntity() returns null when the entity's chunk is unloaded, not just when dead.
    // Treating null as "dead" caused the cap to be bypassed whenever players ran far enough away.
    // Fix: only remove a UUID when the entity is confirmed to exist AND be dead.
    // If null, the chunk is simply unloaded — keep the UUID in the set.
    private void pruneDeadMutants() {
        rebuildTrackedMutants();
        this.activeMutants.removeIf((uuid) -> {
            Entity entity = Bukkit.getEntity(uuid);
            return entity != null && entity.isDead();
        });
    }

    /** Rebuilds the cap from MythicMobs' own durable active-mob registry. */
    private void rebuildTrackedMutants() {
        if (!this.mythicMobsEnabled) return;
        try {
            Set<UUID> discovered = new HashSet<>();
            for (ActiveMob activeMob : MythicBukkit.inst().getMobManager().getActiveMobs()) {
                if (this.mobType.equals(activeMob.getMobType())) {
                    discovered.add(activeMob.getUniqueId());
                }
            }
            this.activeMutants.retainAll(discovered);
            this.activeMutants.addAll(discovered);
        } catch (Exception e) {
            this.plugin.debugLog("[MythicMobs] Could not rebuild Mutant tracking: " + e.getMessage());
        }
    }

    /** True during MythicMobs' spawn transaction or for an already-registered Mythic entity. */
    public boolean isMythicMobOrSpawning(Entity entity) {
        if (this.mmAPI == null || entity == null) return false;
        try {
            boolean mythic = MythicBukkit.inst().getMobManager().isMythicMobSpawning()
                    || (this.mmAPI != null && this.mmAPI.isMythicMob(entity));
            if (mythic) {
                entity.getPersistentDataContainer().set(
                        MYTHIC_ENTITY_KEY, PersistentDataType.BYTE, (byte) 1);
            }
            return mythic;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isMythicMob(Entity entity) {
        if (entity == null) return false;
        if (entity.getPersistentDataContainer().has(MYTHIC_ENTITY_KEY, PersistentDataType.BYTE)) return true;
        if (this.mmAPI == null) return false;
        try {
            boolean mythic = this.mmAPI.isMythicMob(entity);
            if (mythic) {
                entity.getPersistentDataContainer().set(
                        MYTHIC_ENTITY_KEY, PersistentDataType.BYTE, (byte) 1);
            }
            return mythic;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when an entity is the configured Mutant type, including one restored after restart. */
    public boolean isConfiguredMutant(Entity entity) {
        if (!this.mythicMobsEnabled || entity == null) return false;
        if (this.activeMutants.contains(entity.getUniqueId())) return true;
        try {
            if (this.mmAPI == null || !this.mmAPI.isMythicMob(entity)) return false;
            ActiveMob activeMob = this.mmAPI.getMythicMobInstance(entity);
            if (activeMob != null && this.mobType.equals(activeMob.getMobType())) {
                this.activeMutants.add(entity.getUniqueId());
                return true;
            }
        } catch (Exception ignored) {
            // MythicMobs may already be unregistering the entity during its death event.
        }
        return false;
    }

    /** Removes a deferred Blood-Moon Mutant when its previously-unloaded chunk returns. */
    public int cleanupExpiredBloodMoonMutants(Chunk chunk) {
        if (chunk == null || this.plugin.isBloodMoonActive(chunk.getWorld())) return 0;

        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!entity.getPersistentDataContainer().has(BLOOD_MOON_MUTANT_KEY, PersistentDataType.BYTE)) continue;
            this.activeMutants.remove(entity.getUniqueId());
            entity.remove();
            removed++;
        }
        return removed;
    }

    /**
     * Frees a Mutant's global-cap slot the instant it dies. Called from the death listener for
     * every entity death (a cheap Set.remove for non-mutants). This closes the cap-leak where
     * pruneDeadMutants could never reclaim a UUID whose chunk unloaded before it was seen dead —
     * over several blood moons that filled max-global-cap with ghosts and silently stopped spawns.
     */
    public void notifyEntityDeath(UUID uuid) {
        this.activeMutants.remove(uuid);
    }

    /** True if the given entity is a currently-tracked Mutant. Used by the death listener to grant
     * Mutant rewards — call BEFORE {@link #notifyEntityDeath}, which removes the UUID. */
    public boolean isTrackedMutant(UUID uuid) {
        return this.activeMutants.contains(uuid);
    }

    private void broadcastBloodMoonSpawn(Player nearPlayer) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.isWorldEnabled(p.getWorld()) || this.plugin.isLobbyWorld(p.getWorld())) {
                p.sendMessage("§4§l☠ §cA Mutant has emerged near §f" + nearPlayer.getName() + "§c!");
            }
        }

    }

    private String formatLoc(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    public boolean isMythicMobsEnabled() {
        return this.mythicMobsEnabled;
    }

    public int getActiveMutantCount() {
        this.pruneDeadMutants();
        return this.activeMutants.size();
    }

    public int getMaxGlobalCap() {
        return this.maxGlobalCap;
    }

    public String getMobType() {
        return this.mobType;
    }
}
