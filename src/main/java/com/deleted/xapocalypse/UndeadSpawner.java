package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class UndeadSpawner {

    private static final int ANIMATION_TICKS = 20;
    private static final double RISE_PER_TICK = 0.06;
    private static final double START_BELOW_SURFACE_Y = 1.2;

    private static final long PER_BLOCK_COOLDOWN_MS = 2000L;

    private static final int MAX_CONCURRENT_ANIMATIONS = 50;
    private static final int VERTICAL_SEARCH_RADIUS = 48;

    private final xApocalypse plugin;
    private final xApocalypseUtils utils;

    private final Map<Long, Long> activeOrRecentByBlockMs = new HashMap<>();
    private final Set<UUID> activeAnimationEntities = new HashSet<>();

    public UndeadSpawner(xApocalypse plugin, xApocalypseUtils utils) {
        this.plugin = plugin;
        this.utils = utils;
    }

    int getRemainingAnimationCapacity() {
        return Math.max(0, MAX_CONCURRENT_ANIMATIONS - activeAnimationEntities.size());
    }

    public Zombie trySpawnUndeadRise(Location surface, Block surfaceBlock, BlockData surfaceData, long startDelayTicks) {
        if (surface == null || surfaceBlock == null || surfaceData == null) return null;

        World world = surface.getWorld();
        if (world == null) return null;

        if (!isValidSurface(surfaceBlock)) return null;
        if (activeAnimationEntities.size() >= MAX_CONCURRENT_ANIMATIONS) return null;

        long blockKey = packBlockKey(world, surfaceBlock);
        long now = System.currentTimeMillis();

        if (activeOrRecentByBlockMs.size() > 2048) {
            long cutoff = now - (PER_BLOCK_COOLDOWN_MS * 2);
            activeOrRecentByBlockMs.entrySet().removeIf(e -> e.getValue() < cutoff);
        }

        Long recent = activeOrRecentByBlockMs.get(blockKey);
        if (recent != null && (now - recent) < PER_BLOCK_COOLDOWN_MS) return null;

        activeOrRecentByBlockMs.put(blockKey, now);

        Location spawnLoc = surface.clone().add(0.0, -START_BELOW_SURFACE_Y, 0.0);
        // Bug 20 fix: set the plugin-spawning flag so onEntitySpawn skips the mob-list check
        // and does not call assignZombieType again (we call it ourselves below).
        Zombie zombie;
        plugin.setPluginSpawning(true);
        try {
            zombie = (Zombie) world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        } finally {
            plugin.setPluginSpawning(false);
        }
        if (zombie == null) {
            activeOrRecentByBlockMs.remove(blockKey);
            return null;
        }

        utils.assignZombieType(zombie);

        activeAnimationEntities.add(zombie.getUniqueId());
        runDigUpAnimation(zombie, surface, surfaceData, surfaceBlock, blockKey, Math.max(0L, startDelayTicks));
        return zombie;
    }

    private void runDigUpAnimation(LivingEntity entity, Location surface, BlockData surfaceBlockData, Block surfaceBlock, long blockKey, long startDelayTicks) {
        if (!(entity instanceof Zombie zombie)) {
            entity.remove();
            activeOrRecentByBlockMs.remove(blockKey);
            return;
        }

        zombie.setAI(false);
        zombie.setGravity(false);
        zombie.setInvulnerable(true);
        // Bug 19 fix: mark as animating so the LOD system's setAI(false) doesn't fight the animation
        zombie.getPersistentDataContainer().set(xApocalypseUtils.ANIMATING_KEY,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);

        Location working = surface.clone().add(0.0, -START_BELOW_SURFACE_Y, 0.0);

        BukkitRunnable timer = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!zombie.isValid() || zombie.isDead()) {
                    activeOrRecentByBlockMs.remove(blockKey);
                    activeAnimationEntities.remove(zombie.getUniqueId());
                    cancel();
                    return;
                }

                if (!isValidSurface(surfaceBlock)) {
                    finalizeZombie(zombie);
                    activeOrRecentByBlockMs.remove(blockKey);
                    activeAnimationEntities.remove(zombie.getUniqueId());
                    cancel();
                    return;
                }

                working.setY(working.getY() + RISE_PER_TICK);
                zombie.teleport(working.clone());

                World w = zombie.getWorld();
                Location particleLoc = zombie.getLocation().clone().add(0, 0.2, 0);
                w.spawnParticle(Particle.BLOCK, particleLoc, 8, 0.25, 0.1, 0.25, 0.02, surfaceBlockData);
                w.playSound(particleLoc, Sound.BLOCK_GRAVEL_BREAK, 0.6f, 0.7f);

                ticks++;
                if (ticks >= ANIMATION_TICKS) {
                    zombie.teleport(surface.clone());
                    finalizeZombie(zombie);
                    activeOrRecentByBlockMs.remove(blockKey);
                    activeAnimationEntities.remove(zombie.getUniqueId());
                    cancel();
                }
            }
        };

        if (startDelayTicks > 0L) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!zombie.isValid() || zombie.isDead()) {
                        activeOrRecentByBlockMs.remove(blockKey);
                        activeAnimationEntities.remove(zombie.getUniqueId());
                        return;
                    }
                    timer.runTaskTimer(plugin, 0L, 1L);
                }
            }.runTaskLater(plugin, startDelayTicks);
        } else {
            timer.runTaskTimer(plugin, 0L, 1L);
        }
    }

    private void finalizeZombie(Zombie zombie) {
        if (!zombie.isValid() || zombie.isDead()) return;
        // Bug 19 fix: remove the animating tag before re-enabling AI so the LOD system takes over normally
        zombie.getPersistentDataContainer().remove(xApocalypseUtils.ANIMATING_KEY);
        zombie.setInvulnerable(false);
        zombie.setGravity(true);
        zombie.setAI(true);
    }

    /**
     * Bug C2 fix: finalize every zombie left mid rise-animation. The animation timers are plugin
     * tasks, so a {@code /xa reload} or server shutdown calls {@code cancelTasks(this)} and kills
     * them WITHOUT running {@link #finalizeZombie}, which strands the zombie permanently
     * invulnerable, AI-off, gravity-off and still tagged {@code ANIMATING_KEY} — and the LOD system
     * then refuses to re-enable its AI, so it survives restarts as a frozen, unkillable mob.
     *
     * Sweeping every world for the animating tag (rather than only the in-memory tracker) also
     * recovers orphans left by a previous crash. Then the concurrency trackers are cleared so a
     * future reload can't leak toward {@code MAX_CONCURRENT_ANIMATIONS} and disable rises entirely.
     * Safe to call repeatedly.
     */
    public void finalizeAllAnimations() {
        for (World world : Bukkit.getWorlds()) {
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                if (zombie.getPersistentDataContainer().has(xApocalypseUtils.ANIMATING_KEY, PersistentDataType.BYTE)) {
                    finalizeZombie(zombie);
                }
            }
        }
        activeAnimationEntities.clear();
        activeOrRecentByBlockMs.clear();
    }

    private boolean isValidSurface(Block surfaceBlock) {
        if (surfaceBlock == null) return false;

        Material surfaceType = surfaceBlock.getType();
        // In the Nether the highest block is normally the bedrock ceiling. Treating bedrock as a
        // valid floor placed hordes on top of the roof instead of in the cavern with the player.
        if (surfaceType == Material.BEDROCK) return false;
        if (!surfaceBlock.isSolid() || surfaceBlock.isLiquid()) return false;
        // Leaves are reported as solid but make unstable canopy spawn points. isOccluding() used
        // to reject them, but it also rejected legitimate TerraformGenerator surfaces such as
        // paths, slabs and stairs, so exclude leaves directly instead.
        if (surfaceType.name().endsWith("_LEAVES")) return false;

        Block above = surfaceBlock.getRelative(BlockFace.UP);
        if (!isSafeHeadroom(above)) return false;

        Block above2 = above.getRelative(BlockFace.UP);
        return isSafeHeadroom(above2);
    }

    private boolean isSafeHeadroom(Block block) {
        return block != null && block.isPassable() && !block.isLiquid();
    }

    Location getSurfaceSpawnLocation(Location target) {
        if (target == null) return null;
        World world = target.getWorld();
        if (world == null) return null;

        int x = target.getBlockX();
        int z = target.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;

        // In normal/custom terrain worlds, only accept the exposed terrain surface. The previous
        // +/- 48 block scan kept descending whenever the top block was unsuitable (water, dense
        // decoration, an overhang, etc.) and could eventually accept a perfectly valid cave floor.
        // Mutants were especially likely to keep that result because their placement code prefers
        // locations outside the player's line of sight. The no-leaves heightmap handles generator
        // terrain and forest floors without allowing the search to escape into underground caves.
        if (world.getEnvironment() != World.Environment.NETHER) {
            int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (surfaceY < world.getMinHeight() || surfaceY >= world.getMaxHeight()) return null;

            Block surface = world.getBlockAt(x, surfaceY, z);
            if (!isValidSurface(surface)) return null;
            return surface.getLocation().add(0.5, 1.0, 0.5);
        }

        // Nether heightmaps point at the bedrock roof, so retain a player-relative cavern scan
        // there. Underground floors are the intended playable terrain in that environment.
        int targetY = target.getBlockY();
        int topY = Math.min(world.getMaxHeight() - 1, targetY + VERTICAL_SEARCH_RADIUS);
        int floorY = Math.max(world.getMinHeight(), targetY - VERTICAL_SEARCH_RADIUS);
        for (int y = topY; y >= floorY; y--) {
            Block candidate = world.getBlockAt(x, y, z);
            if (isValidSurface(candidate)) {
                return candidate.getLocation().add(0.5, 1.0, 0.5);
            }
        }
        return null;
    }

    private long packBlockKey(World world, Block block) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        long key = (((long) x) & 0x3FFFFFFL) << 38;
        key |= (((long) z) & 0x3FFFFFFL) << 12;
        key |= ((long) y) & 0xFFFL;

        return key ^ world.getUID().getLeastSignificantBits();
    }
}
