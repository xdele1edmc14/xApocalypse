package com.deleted.xapocalypse;

import org.bukkit.Statistic;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * The single Bukkit {@link Listener} for xApocalypse. Every {@code @EventHandler} that used
 * to live on the monolith now lives here and delegates to the relevant manager. Keeping all
 * handlers in ONE class (rather than scattering them across managers) keeps the dependency
 * graph a star and avoids managers having to implement Listener.
 *
 * Manager references are resolved once in the constructor (all managers exist by the time the
 * listener is registered in onEnable).
 */
public class xApocalypseListener implements Listener {

    private final xApocalypse plugin;
    private final xApocalypseUtils utils;
    private final BloodMoonManager bloodMoon;
    private final ImmunityManager immunity;
    private final ScentManager scent;
    private final HordeManager horde;

    public xApocalypseListener(xApocalypse plugin) {
        this.plugin = plugin;
        this.utils = plugin.getUtils();
        this.bloodMoon = plugin.getBloodMoon();
        this.immunity = plugin.getImmunity();
        this.scent = plugin.getScent();
        this.horde = plugin.getHordeManager();
    }

    // === SCENT EVENTS ===

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        scent.onMove(event.getPlayer());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        UUID deadId = event.getEntity().getUniqueId();
        MythicMobsManager mythicManager = plugin.getMythicMobsManager();
        boolean isMythicEntity = mythicManager.isMythicMob(event.getEntity());
        boolean isMutant = mythicManager.isTrackedMutant(deadId)
                || mythicManager.isConfiguredMutant(event.getEntity());
        // Free a MythicMobs Mutant cap slot the moment any entity dies (cheap no-op for non-mutants).
        // Closes the cap-leak where a killed Mutant whose chunk unloaded before pruning kept its slot
        // forever, eventually filling max-global-cap with ghosts and silently stopping all spawns.
        mythicManager.notifyEntityDeath(deadId);

        if (event.getEntity() instanceof Zombie zombie) {
            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type == xApocalypseUtils.ZombieType.BURSTER) {
                utils.cancelBursterFuse(zombie);
            }

            // Remove effects that should not survive the death tick.
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.SPEED);

            // Reset fire ticks to prevent post-death burning
            zombie.setFireTicks(0);

            // Mutants have their own reward tier. Ordinary rewards only apply where the apocalypse
            // is active; disabled/lobby worlds cannot be used as an economy farming bypass.
            boolean ordinaryRewardEligible = !isMutant && !isMythicEntity
                    && plugin.isWorldEnabled(zombie.getWorld())
                    && !plugin.isLobbyWorld(zombie.getWorld());
            if (ordinaryRewardEligible) {
                immunity.maybeDropZombieGuts(event, zombie);
                plugin.getDropManager().applyDrops(event, zombie);
                plugin.getDropManager().runKillCommands(zombie);
            }
        }

        // Scent is optional; veteran promotion is independent.
        if (plugin.getConfig().getBoolean("scent-system.enabled", true)) {
            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                scent.onKill(killer);
            }
        }

        Entity deadEntity = event.getEntity();

        // Only a player kill can promote the attacking zombie.
        if (deadEntity instanceof Player && deadEntity.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent) {
            Entity damager = damageEvent.getDamager();

            // If a zombie killed this entity, transform it to veteran
            if (damager instanceof Zombie killerZombie) {
                if (plugin.getConfig().getBoolean("zombie-classes.veteran.permanent", true)) {
                    // Defer the rename so the death message keeps the zombie's original class.
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (killerZombie.isValid() && !killerZombie.isDead()) {
                            utils.transformToVeteran(killerZombie);
                            plugin.debugLog("Zombie " + killerZombie.getUniqueId() + " transformed to VETERAN after kill");
                        }
                    }, 1L);
                }
            }
        }
    }

    // === PLAYER LIFECYCLE EVENTS ===

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        if (!bloodMoon.shouldBlockSleep(player.getWorld())) return;

        event.setCancelled(true);
        player.sendMessage(plugin.getMessages().get("bloodmoon.sleep-blocked"));
        if (bloodMoon.shouldResetPhantomTimer()) {
            player.setStatistic(Statistic.TIME_SINCE_REST, 0);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        bloodMoon.cleanupBossbarForPlayer(player);

        // Replay today's pre-blood-moon warning to late joiners (no-op if none fired today)
        bloodMoon.sendWarningOnJoinIfApplicable(player);

        immunity.onPlayerJoin(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        immunity.onPlayerQuit(player);

        bloodMoon.onPlayerQuit(player);

        scent.onPlayerQuit(uuid);
    }

    // === ENTITY EVENTS ===

    @EventHandler
    public void onEntitySpawn(CreatureSpawnEvent event) {
        // The plugin spawner assigns its own type after spawnEntity returns.
        if (horde.isPluginSpawning()) return;

        // MythicMobs fires CreatureSpawnEvent while constructing its base Bukkit entity. Never
        // cancel that spawn or overwrite its configured attributes with an xApocalypse class.
        MythicMobsManager mythic = plugin.getMythicMobsManager();
        if (mythic != null && mythic.isMythicMobOrSpawning(event.getEntity())) return;

        if (!plugin.isWorldEnabled(event.getLocation().getWorld())) return;

        Entity entity = event.getEntity();
        if (isMobListSpawnReason(event.getSpawnReason())) {
            boolean inList = isInMobList(entity.getType(), plugin.getMobList());
            boolean blocked = plugin.isUseMobBlacklist() ? inList : !inList;
            if (blocked) {
                event.setCancelled(true);
                plugin.debugLog("Blocked " + entity.getType().name() + " spawn (reason: "
                        + event.getSpawnReason() + ") by mob "
                        + (plugin.isUseMobBlacklist() ? "blacklist" : "whitelist") + ".");
                return;
            }
        }

        if (entity instanceof Zombie zombie && isMobListSpawnReason(event.getSpawnReason())) {
            if (!plugin.isAllowBabyZombies() && !zombie.isAdult()) { event.setCancelled(true); return; }
            if (!plugin.isAllowZombieVillagers() && zombie.getType() == EntityType.ZOMBIE_VILLAGER) { event.setCancelled(true); return; }

            // Assign zombie type
            utils.assignZombieType(zombie);
        }
    }

    static boolean isMobListSpawnReason(CreatureSpawnEvent.SpawnReason reason) {
        // Custom generators can add their initial entities during chunk population rather than the
        // normal mob tick, which Bukkit reports as CHUNK_GEN. Both are world-generated spawns and
        // should obey the configured blacklist/whitelist; commands, spawners and plugin CUSTOM
        // entities retain their normal behavior.
        return reason == CreatureSpawnEvent.SpawnReason.NATURAL
                || reason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN;
    }

    static boolean isInMobList(EntityType type, List<String> configuredMobs) {
        if (type == null || configuredMobs == null) return false;

        String enumName = type.name();
        String namespacedName = type.getKey().toString();
        for (String configured : configuredMobs) {
            if (configured == null) continue;
            String entry = configured.trim();
            if (entry.equalsIgnoreCase(enumName) || entry.equalsIgnoreCase(namespacedName)) {
                return true;
            }
            // Accept the common "minecraft:zombie" spelling as well as "ZOMBIE", regardless
            // of case, without changing the user's config file on disk.
            int separator = entry.indexOf(':');
            if (separator >= 0 && entry.substring(separator + 1).equalsIgnoreCase(enumName)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onImmunePlayerTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        if (plugin.isZombieGutsEnabled() && immunity.isImmune(player.getUniqueId())) {
            event.setCancelled(true);
            zombie.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.isCancelled()) return;
        if (event.getEntity() instanceof Zombie zombie
                && event.getTarget() instanceof Player player) {
            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type == xApocalypseUtils.ZombieType.BURSTER) {
                utils.handleBursterTarget(zombie, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTemporaryWebBreak(BlockBreakEvent event) {
        if (utils.forgetTemporaryWeb(event.getBlock())) {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTemporaryWebPlace(BlockPlaceEvent event) {
        utils.forgetTemporaryWeb(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;

        DamageCause cause = event.getCause();
        if (cause != DamageCause.FIRE
                && cause != DamageCause.FIRE_TICK
                && cause != DamageCause.LAVA) return;

        xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
        boolean hasFireResistance = zombie.getActivePotionEffects().stream()
                .anyMatch(effect -> effect.getType()
                        == org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);
        if (type == xApocalypseUtils.ZombieType.SCORCHED || hasFireResistance) {
            event.setCancelled(true);
            zombie.setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0.0) return;
        if (event.getDamager() instanceof Zombie zombie && event.getEntity() instanceof Player player) {
            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type == null) return;

            switch (type) {
                case WEBBER -> utils.handleWebberHit(zombie, player);
                case FROST -> utils.handleFrostHit(zombie, player);
                case SCORCHED -> utils.handleScorchedHit(zombie, player);
                case PSYCHOPATH -> utils.handlePsychopathHit(zombie, player);
                default -> {
                    // No special handling for other types
                }
            }
        }
    }

    @EventHandler
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        immunity.handleGutsConsume(event);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Primary Zombie Guts activation — a right-click works regardless of hunger level / game mode.
        immunity.handleGutsInteract(event);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        // Spitter projectiles carry a dedicated byte marker.
        if (!(event.getEntity() instanceof LlamaSpit spit)) return;

        Byte acidTag = spit.getPersistentDataContainer().get(xApocalypseUtils.ACID_SPIT_KEY, PersistentDataType.BYTE);
        if (acidTag != null) {
            if (event.getHitEntity() != null) {
                Entity shooter = spit.getShooter() instanceof Entity entity ? entity : null;
                utils.handleAcidHit(event.getHitEntity(), shooter);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityCombust(EntityCombustEvent event) {
        if (!plugin.isWorldEnabled(event.getEntity().getWorld())) return;
        if (event.getEntity() instanceof Zombie zombie) {
            // Check zombie type and potion effects to decide combustion handling
            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);

            boolean hasFireResistance = zombie.getActivePotionEffects().stream()
                    .anyMatch(pe -> pe.getType() == org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);

            // If the zombie has explicit fire resistance or is a scorched type, cancel combustion
            if (type == xApocalypseUtils.ZombieType.SCORCHED || hasFireResistance) {
                event.setCancelled(true);
                zombie.setFireTicks(0);
                return;
            }

            // Only the base environmental event represents sunlight. Fire Aspect, flaming
            // projectiles, lava and burning blocks use the entity/block subclasses.
            boolean daylightCombustion = !(event instanceof EntityCombustByEntityEvent)
                    && !(event instanceof EntityCombustByBlockEvent);
            long time = zombie.getWorld().getTime();
            boolean isDay = time >= 0 && time < 12300;
            if (daylightCombustion && isDay
                    && utils.isManagedZombie(zombie)
                    && !plugin.getConfig().getBoolean(
                            "zombie-settings.allow-daylight-burning", false)) {
                event.setCancelled(true);
                zombie.setFireTicks(0);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.getConfig().getBoolean("bloodmoon.despawn-on-end", true)) return;

        int zombies = utils.cleanupExpiredBloodMoonZombies(event.getChunk());
        int mutants = plugin.getMythicMobsManager().cleanupExpiredBloodMoonMutants(event.getChunk());
        if (zombies + mutants > 0) {
            plugin.debugLog("Deferred Blood Moon cleanup removed " + zombies + " zombie(s) and "
                    + mutants + " Mutant(s) from chunk " + event.getChunk().getX() + ","
                    + event.getChunk().getZ() + ".");
        }
    }
}
