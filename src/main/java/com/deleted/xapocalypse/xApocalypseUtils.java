package com.deleted.xapocalypse;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class xApocalypseUtils {

    private static final int BLOOD_MOON_CLEANUP_BATCH_SIZE = 10;

    private final xApocalypse plugin;

    public static final NamespacedKey ZOMBIE_TYPE_KEY = new NamespacedKey("xapocalypse", "zombie_type");
    public static final NamespacedKey LAST_HEAL_KEY = new NamespacedKey("xapocalypse", "last_heal");
    public static final NamespacedKey LAST_BREAK_KEY = new NamespacedKey("xapocalypse", "last_break");
    public static final NamespacedKey LAST_SPIT_KEY = new NamespacedKey("xapocalypse", "last_spit");
    public static final NamespacedKey LAST_RAGE_KEY = new NamespacedKey("xapocalypse", "last_rage");
    public static final NamespacedKey LAST_WEB_KEY = new NamespacedKey("xapocalypse", "last_web");
    public static final NamespacedKey BURSTER_PRIMED_KEY = new NamespacedKey("xapocalypse", "burster_primed");
    // Bug 2 fix: dedicated PDC key for acid spit projectiles (ZOMBIE_TYPE_KEY belongs to zombie entities, not projectiles)
    public static final NamespacedKey ACID_SPIT_KEY = new NamespacedKey("xapocalypse", "acid_spit");
    // Bug 19 fix: marks a zombie as mid-rise-animation so the LOD system won't call setAI(false) on it
    public static final NamespacedKey ANIMATING_KEY = new NamespacedKey("xapocalypse", "animating");
    // Marker placed on the Zombie Guts ItemStack so it is identified by PDC rather than display name.
    public static final NamespacedKey ZOMBIE_GUTS_KEY = new NamespacedKey("xapocalypse", "zombie_guts_item");
    // Marks a zombie that was spawned/typed during an active blood moon, so it can be despawned
    // when the blood moon ends (see despawnBloodMoonZombies). Zombies present before the event are untagged.
    public static final NamespacedKey BLOOD_MOON_KEY = new NamespacedKey("xapocalypse", "blood_moon");

    public enum ZombieType {
        SWARMER, MINER, NURSE, PSYCHOPATH, SCORCHED, TANK, RUNNER, SPITTER, VETERAN, WEBBER, BURSTER, FROST, NORMAL;
    }

    private final Map<ZombieType, Double> spawnWeights = new HashMap<>();
    private double totalWeight = 0.0;

    private final Map<UUID, BukkitRunnable> activeBursterFuses = new HashMap<>();
    private final Map<Block, BlockData> temporaryWebBlocks = new HashMap<>();

    // Frequently used class behavior settings, refreshed on enable and /xa reload.
    private long nurseIntervalMs;
    private double nurseRadius;
    private double nurseHealAmount;
    private int nurseMaxTargets;
    private long minerBreakDelayMs;
    private Set<Material> minerBreakables = EnumSet.noneOf(Material.class);
    private boolean minerDropItems;
    private double minerTowerMinHeight;
    private double minerTowerMaxHeight;
    private double minerTowerHorizontalRangeSquared;
    private long spitterCooldownMs;
    private int spitterPoisonDurationTicks;
    private int spitterPoisonAmplifier;
    private double spitterImpactDamage;
    private double spitterMinRange;
    private double spitterMaxRange;
    private double spitterProjectileSpeed;
    private double spitterRetreatRange;
    private double spitterRetreatSpeed;
    private long psychopathRageCooldownMs;
    private int psychopathBleedDurationTicks;
    private boolean veteranPermanent;
    private int webberWebCount;
    private long webberCleanupDelayTicks;
    private double bursterRadiusSquared;
    private int bursterFuseTicks;
    private float bursterPower;
    private boolean bursterBreakBlocks;
    private int frostDurationTicks;
    private int frostAmplifier;
    private int scorchedFireTicks;

    public xApocalypseUtils(xApocalypse plugin) {
        this.plugin = plugin;
        loadWeights();
        loadBehaviorConfig();
    }

    private void loadWeights() {
        spawnWeights.clear();
        totalWeight = 0.0;
        for (ZombieType type : ZombieType.values()) {
            // VETERAN is not spawned randomly (it is transformed from kills, never rolled)
            if (type == ZombieType.VETERAN || type == ZombieType.NORMAL) continue;
            // Bug M4 fix: honor per-class enabled flags so disabling a class removes it from the
            // random spawn pool (previously these flags were never read).
            if (!isClassEnabled(type)) continue;
            double weight = Math.max(0.0,
                    plugin.getConfig().getDouble("zombie-classes.weights." + type.name(), 0.1));
            if (weight == 0.0) continue;
            spawnWeights.put(type, weight);
            totalWeight += weight;
        }
    }

    /** True unless the class carries an explicit {@code zombie-classes.<class>.enabled: false} flag. */
    private boolean isClassEnabled(ZombieType type) {
        String key = switch (type) {
            case MINER -> "zombie-classes.miner.enabled";
            case NURSE -> "zombie-classes.nurse.enabled";
            case SPITTER -> "zombie-classes.spitter.enabled";
            case SCORCHED -> "zombie-classes.scorched.enabled";
            default -> null;
        };
        return key == null || plugin.getConfig().getBoolean(key, true);
    }

    public void reloadWeights() {
        loadWeights();
        loadBehaviorConfig();
    }

    private void loadBehaviorConfig() {
        nurseIntervalMs = Math.max(0L,
                plugin.getConfig().getLong("zombie-classes.nurse.interval-seconds", 3L)) * 1000L;
        nurseRadius = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.nurse.heal-radius", 5.0));
        nurseHealAmount = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.nurse.heal-amount-hp", 3.0));
        nurseMaxTargets = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.nurse.max-targets-per-tick", 5));

        minerBreakDelayMs = Math.max(0L,
                plugin.getConfig().getLong("zombie-classes.miner.break-delay-ticks", 30L)) * 50L;
        EnumSet<Material> parsedBreakables = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("zombie-classes.miner.breakables")) {
            Material material = Material.matchMaterial(name);
            if (material != null) parsedBreakables.add(material);
        }
        minerBreakables = parsedBreakables;
        minerDropItems = plugin.getConfig().getBoolean("zombie-classes.miner.drop-items", true);
        minerTowerMinHeight = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.miner.tower-min-height", 1.5));
        minerTowerMaxHeight = Math.max(minerTowerMinHeight,
                plugin.getConfig().getDouble("zombie-classes.miner.tower-max-height", 4.0));
        double minerTowerHorizontalRange = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.miner.tower-horizontal-range", 3.0));
        minerTowerHorizontalRangeSquared = minerTowerHorizontalRange * minerTowerHorizontalRange;

        spitterCooldownMs = Math.max(0L,
                plugin.getConfig().getLong("zombie-classes.spitter.projectile-cooldown-seconds", 4L)) * 1000L;
        spitterPoisonDurationTicks = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.spitter.poison-duration-seconds", 6)) * 20;
        spitterPoisonAmplifier = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.spitter.poison-level", 2) - 1);
        spitterImpactDamage = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.spitter.impact-damage", 2.0));
        spitterMinRange = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.spitter.min-range", 1.5));
        spitterMaxRange = Math.max(spitterMinRange,
                plugin.getConfig().getDouble("zombie-classes.spitter.max-range", 18.0));
        spitterProjectileSpeed = Math.max(0.1,
                plugin.getConfig().getDouble("zombie-classes.spitter.projectile-speed", 1.4));
        spitterRetreatRange = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.spitter.retreat-range", 4.0));
        spitterRetreatSpeed = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.spitter.retreat-speed", 0.22));

        psychopathRageCooldownMs = Math.max(0L,
                plugin.getConfig().getLong("zombie-classes.psychopath.rage-cooldown-seconds", 25L)) * 1000L;
        psychopathBleedDurationTicks = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.psychopath.bleed-duration-seconds", 3)) * 20;
        veteranPermanent = plugin.getConfig().getBoolean("zombie-classes.veteran.permanent", true);

        webberWebCount = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.webber.web_count", 3));
        if (plugin.getConfig().contains("zombie-classes.webber.cleanup_delay_seconds")) {
            webberCleanupDelayTicks = Math.max(1L,
                    plugin.getConfig().getLong("zombie-classes.webber.cleanup_delay_seconds", 5L)) * 20L;
        } else {
            webberCleanupDelayTicks = Math.max(1L,
                    plugin.getConfig().getLong("zombie-classes.webber.cleanup_delay", 100L));
        }

        double bursterRadius = Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.burster.radius", 3.0));
        bursterRadiusSquared = bursterRadius * bursterRadius;
        bursterFuseTicks = Math.max(1,
                plugin.getConfig().getInt("zombie-classes.burster.fuse_ticks", 30));
        bursterPower = (float) Math.max(0.0,
                plugin.getConfig().getDouble("zombie-classes.burster.power", 3.0));
        bursterBreakBlocks = plugin.getConfig().getBoolean("zombie-classes.burster.break_blocks", true);

        frostDurationTicks = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.frost.duration_ticks", 100));
        frostAmplifier = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.frost.slowness_level", 2) - 1);
        scorchedFireTicks = Math.max(0,
                plugin.getConfig().getInt("zombie-classes.scorched.fire-duration-seconds", 4)) * 20;
    }

    public void assignZombieType(Zombie zombie) {
        if (!plugin.getConfig().getBoolean("zombie-classes.enabled", true)) return;
        applyZombieType(zombie, getRandomZombieType());
    }

    private ZombieType getRandomZombieType() {
        // Bug 14 fix: guard against zero/empty weights
        if (totalWeight <= 0 || spawnWeights.isEmpty()) return ZombieType.NORMAL;
        double random = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (Map.Entry<ZombieType, Double> entry : spawnWeights.entrySet()) {
            cumulative += entry.getValue();
            // Bug 14 fix: strict < instead of <= eliminates last-entry boundary bias
            if (random < cumulative) return entry.getKey();
        }
        // Floating-point edge case fallback — return an available entry rather than a hardcoded type.
        return spawnWeights.entrySet().iterator().next().getKey();
    }

    public void applyZombieType(Zombie zombie, ZombieType type) {
        zombie.getPersistentDataContainer().set(ZOMBIE_TYPE_KEY, PersistentDataType.STRING, type.name());
        // Tag zombies typed during an active blood moon so they can be cleaned up when it ends.
        // Guard on type != VETERAN: veteran promotion (transformToVeteran) re-runs this method on an
        // already-living zombie. Without the guard, a zombie that existed BEFORE the blood moon and
        // promotes to Veteran mid-event would be freshly tagged and then wrongly despawned at event
        // end — violating the "leave pre-existing zombies alone" contract. The tag is only stamped
        // at initial typing (every spawn path passes a non-VETERAN type first).
        if (type != ZombieType.VETERAN && plugin.isBloodMoonActive(zombie.getWorld())) {
            zombie.getPersistentDataContainer().set(BLOOD_MOON_KEY, PersistentDataType.BYTE, (byte) 1);
        }
        applyZombieHead(zombie, type);
        applyZombieStats(zombie, type);
    }

    private void applyZombieHead(Zombie zombie, ZombieType type) {
        // Minor fix: honor visuals.use-nametags (previously ignored — zombies always got a nametag).
        if (!plugin.getConfig().getBoolean("visuals.use-nametags", true)) {
            zombie.setCustomName(null);
            zombie.setCustomNameVisible(false);
            return;
        }
        zombie.setCustomName(getZombieDisplayName(type));
        zombie.setCustomNameVisible(plugin.getConfig().getBoolean("visuals.nametag-always-visible", true));
    }

    private String getZombieDisplayName(ZombieType type) {
        return switch (type) {
            case NORMAL -> "§8Zombie";
            case SWARMER -> "§7⚔ Swarmer";
            case MINER -> "§6⛏ Miner";
            case NURSE -> "§d❤ Nurse";
            case RUNNER -> "§b⚡ Runner";
            case SPITTER -> "§a☠ Spitter";
            case PSYCHOPATH -> "§c⚔ Psychopath";
            case SCORCHED -> "§4🔥 Scorched";
            case TANK -> "§8⛨ Tank";
            case VETERAN -> "§e★ Veteran";
            case WEBBER -> "§8🕸 Webber";
            case BURSTER -> "§c💣 Burster";
            case FROST -> "§b❄ Frost";
            default -> "§7Zombie";
        };
    }

    private void applyZombieStats(Zombie zombie, ZombieType type) {
        double baseHealth = plugin.getConfig().getDouble("zombie-settings.health", 25.0);
        double baseDamage = plugin.getConfig().getDouble("zombie-settings.damage", 6.0);
        double baseSpeed = plugin.getConfig().getDouble("zombie-settings.speed", 0.32);

        boolean isBloodMoon = plugin.isBloodMoonActive(zombie.getWorld());
        if (isBloodMoon) {
            baseHealth *= plugin.getBloodMoon().getHealthMult();
            baseDamage *= plugin.getBloodMoon().getDamageMult();
            baseSpeed *= plugin.getBloodMoon().getSpeedMult();
        }

        switch (type) {
            case NORMAL -> {
                // Basic vanilla zombie - no special effects
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                
                // CRITICAL FIX: Add permanent fire resistance to all custom zombie types
                // NORMAL zombies don't get fire immunity (they can burn normally)
            }
            case SWARMER -> {
                // Basic zombie - standard stats
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                
                // CRITICAL FIX: Add permanent fire resistance to all custom zombie types
                zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
            }
            case RUNNER -> {
                // Fast but fragile
                double healthMult = plugin.getConfig().getDouble("zombie-classes.runner.health-multiplier", 0.75);
                double runnerSpeed = plugin.getConfig().getDouble("zombie-classes.runner.speed", 0.38);
                if (isBloodMoon) {
                    runnerSpeed *= plugin.getBloodMoon().getSpeedMult();
                }
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth * healthMult);
                zombie.setHealth(baseHealth * healthMult);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage * 0.9);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, runnerSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case TANK -> {
                // High HP, armored, knockback resistant
                double tankHealth = plugin.getConfig().getDouble("zombie-classes.tank.health", 50.0);
                double knockbackResist = plugin.getConfig().getDouble("zombie-classes.tank.knockback-resistance", 0.6);
                if (isBloodMoon) {
                    tankHealth *= plugin.getBloodMoon().getHealthMult();
                }
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, tankHealth);
                zombie.setHealth(tankHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage * 1.2);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed * 0.85);
                setZombieStat(zombie, AttributeResolver.KNOCKBACK_RESISTANCE, knockbackResist);
                zombie.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                applyFireResistance(zombie); // Bug 15 fix
            }
            case MINER -> {
                // Standard stats, focused on block breaking
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case NURSE -> {
                // Support zombie - lower damage, standard HP
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage * 0.7);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case SPITTER -> {
                // Ranged attacker - lower HP, standard speed
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth * 0.85);
                zombie.setHealth(baseHealth * 0.85);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage * 0.8);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case SCORCHED -> {
                // Fire zombie - standard stats with fire aura
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                
                // CRITICAL FIX: Proper fire immunity for scorched zombies
                zombie.setFireTicks(Integer.MAX_VALUE); // Immune to fire
                // CRITICAL FIX: Add fire resistance potion effect to prevent self-damage
                zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
            }
            case PSYCHOPATH -> {
                // Berserker - higher damage plus a small passive speed bonus
                double attackBonus = plugin.getConfig().getDouble("zombie-classes.psychopath.attack-bonus", 2.0);
                // Bug M1 fix: honor zombie-classes.psychopath.speed-bonus (was never applied).
                double speedBonus = plugin.getConfig().getDouble("zombie-classes.psychopath.speed-bonus", 0.08);
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage + attackBonus);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed + speedBonus);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case VETERAN -> {
                // Elite zombie - transformed from kills
                double attackBonus = plugin.getConfig().getDouble("zombie-classes.veteran.attack-bonus", 4.0);
                double healthAdd = plugin.getConfig().getDouble("zombie-classes.veteran.add-health", 0.0);
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth + healthAdd);
                zombie.setHealth(baseHealth + healthAdd);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage + attackBonus);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed * 1.1);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case WEBBER -> {
                // Webber - places cobwebs on hit, holds string in off-hand
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                zombie.getEquipment().setItemInOffHand(new ItemStack(Material.STRING));
                applyFireResistance(zombie); // Bug 15 fix
            }
            case BURSTER -> {
                // Burster - explodes when close, lower HP
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth * 0.8);
                zombie.setHealth(baseHealth * 0.8);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage * 0.5);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed * 0.6);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case FROST -> {
                // Frost - slows targets on hit, wears aqua chestplate
                setZombieStat(zombie, AttributeResolver.MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, AttributeResolver.ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, AttributeResolver.MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
                
                // CRITICAL FIX: Only frost zombies get blue leather chestplate
                ItemStack chestplate = new ItemStack(Material.LEATHER_CHESTPLATE);
                if (chestplate.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta meta) {
                    meta.setColor(org.bukkit.Color.AQUA);
                    chestplate.setItemMeta(meta);
                }
                zombie.getEquipment().setChestplate(chestplate);
            }
        }
    }

    private void setZombieStat(Zombie zombie, Attribute attribute, double value) {
        var instance = zombie.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    // Bug 15 fix: centralised helper so every non-NORMAL type gets fire resistance
    // (onEntityCombust cancels sunlight burning only when this effect is present)
    private void applyFireResistance(Zombie zombie) {
        zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                Integer.MAX_VALUE, 0, false, false));
    }

    public ZombieType getZombieType(Zombie zombie) {
        String s = zombie.getPersistentDataContainer().get(ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return ZombieType.valueOf(s);
        } catch (IllegalArgumentException ignored) {
            zombie.getPersistentDataContainer().remove(ZOMBIE_TYPE_KEY);
            return null;
        }
    }

    /** True only for zombies whose class/stats are owned by xApocalypse. */
    public boolean isCustomZombie(Zombie zombie) {
        return zombie != null
                && zombie.getPersistentDataContainer().has(ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
    }

    public void tickZombieAI(Zombie zombie) {
        ZombieType type = getZombieType(zombie);
        if (type == null) return;
        
        // Clean switch expression for AI behaviors (Strategy Pattern-like approach)
        switch (type) {
            case NURSE -> tickNurseAI(zombie);
            case MINER -> tickMinerAI(zombie);
            case SPITTER -> tickSpitterAI(zombie);
            case SCORCHED -> tickScorchedAI(zombie);
            case PSYCHOPATH -> tickPsychopathAI(zombie);
            case BURSTER -> tickBursterAI(zombie);
            // SWARMER, RUNNER, TANK, VETERAN have no special AI behaviors
            default -> { /* No special AI for this type */ }
        }
    }

    private void tickNurseAI(Zombie nurse) {
        long now = System.currentTimeMillis();
        // Bug M1 fix: honor the configured heal interval/radius/amount/target-cap (these keys were
        // previously ignored — the values below were all hardcoded).
        Long lastHeal = nurse.getPersistentDataContainer().get(LAST_HEAL_KEY, PersistentDataType.LONG);
        if (lastHeal != null && (now - lastHeal) < nurseIntervalMs) return;
        nurse.getPersistentDataContainer().set(LAST_HEAL_KEY, PersistentDataType.LONG, now);

        boolean healed = false;
        int healedCount = 0;
        for (Entity e : nurse.getNearbyEntities(nurseRadius, nurseRadius, nurseRadius)) {
            if (healedCount >= nurseMaxTargets) break;
            if (e instanceof Zombie z) {
                if (!z.isValid() || z.isDead()) continue;
                double health = z.getHealth();
                if (health <= 0.0) continue;
                var maxHealth = z.getAttribute(AttributeResolver.MAX_HEALTH);
                if (maxHealth == null || health >= maxHealth.getValue()) continue;
                z.setHealth(Math.min(health + nurseHealAmount, maxHealth.getValue()));
                z.getWorld().spawnParticle(Particle.HEART, z.getLocation().add(0, 1.5, 0), 5, 0.2, 0.2, 0.2, 0.1);
                healed = true;
                healedCount++;
            }
        }
        if (healed) {
            nurse.getWorld().playSound(nurse.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
        }
    }

    private void tickMinerAI(Zombie miner) {
        LivingEntity target = miner.getTarget();
        if (target == null || !miner.getWorld().equals(target.getWorld())) return;

        long now = System.currentTimeMillis();
        Long lastBreak = miner.getPersistentDataContainer().get(LAST_BREAK_KEY, PersistentDataType.LONG);
        if (lastBreak != null && (now - lastBreak) < minerBreakDelayMs) return;

        for (Block candidate : getMinerBreakCandidates(miner, target)) {
            if (tryBreak(miner, candidate)) {
                miner.getPersistentDataContainer().set(LAST_BREAK_KEY, PersistentDataType.LONG, now);
                return;
            }
        }
    }

    /**
     * Selects actual grid-adjacent obstructions instead of adding a normalized vector to the
     * entity's fractional position. The latter could round back into the miner's own block.
     */
    private List<Block> getMinerBreakCandidates(Zombie miner, LivingEntity target) {
        Location minerLocation = miner.getLocation();
        Location targetLocation = target.getLocation();
        LinkedHashSet<Block> candidates = new LinkedHashSet<>();

        double dx = targetLocation.getX() - minerLocation.getX();
        double dz = targetLocation.getZ() - minerLocation.getZ();
        double horizontalDistanceSquared = dx * dx + dz * dz;
        double towerHeight = targetLocation.getY() - minerLocation.getY();
        if (towerHeight >= minerTowerMinHeight && towerHeight <= minerTowerMaxHeight
                && horizontalDistanceSquared <= minerTowerHorizontalRangeSquared) {
            candidates.add(targetLocation.clone().subtract(0, 1, 0).getBlock());
        }

        int stepX = Integer.compare(targetLocation.getBlockX(), minerLocation.getBlockX());
        int stepZ = Integer.compare(targetLocation.getBlockZ(), minerLocation.getBlockZ());
        List<int[]> offsets = new ArrayList<>(3);
        if (stepX != 0 && stepZ != 0) offsets.add(new int[]{stepX, stepZ});
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (stepX != 0) offsets.add(new int[]{stepX, 0});
            if (stepZ != 0) offsets.add(new int[]{0, stepZ});
        } else {
            if (stepZ != 0) offsets.add(new int[]{0, stepZ});
            if (stepX != 0) offsets.add(new int[]{stepX, 0});
        }

        int feetY = minerLocation.getBlockY();
        for (int[] offset : offsets) {
            int x = minerLocation.getBlockX() + offset[0];
            int z = minerLocation.getBlockZ() + offset[1];
            candidates.add(miner.getWorld().getBlockAt(x, feetY, z));
            candidates.add(miner.getWorld().getBlockAt(x, feetY + 1, z));
        }
        return new ArrayList<>(candidates);
    }

    private boolean tryBreak(Zombie miner, Block b) {
        if (b.getType() == Material.AIR || b.getType() == Material.BEDROCK) return false;
        if (!minerBreakables.contains(b.getType())) return false;
        if (isInsideClaim(b.getLocation())) return false;

        BlockData brokenData = b.getBlockData();
        BlockData air = plugin.getServer().createBlockData(Material.AIR);
        EntityChangeBlockEvent changeEvent = new EntityChangeBlockEvent(miner, b, air);
        plugin.getServer().getPluginManager().callEvent(changeEvent);
        if (changeEvent.isCancelled()) return false;
        if (minerDropItems) {
            b.breakNaturally();
        } else {
            b.setType(Material.AIR);
        }
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 10, brokenData);
        return true;
    }

    private void tickSpitterAI(Zombie spitter) {
        LivingEntity target = spitter.getTarget();
        if (target == null || !spitter.getWorld().equals(target.getWorld())) return;

        double dist = spitter.getLocation().distance(target.getLocation());
        if (dist < spitterRetreatRange) {
            Vector away = spitter.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
            if (away.lengthSquared() > 0.0001) {
                Vector currentVelocity = spitter.getVelocity();
                double verticalVelocity = currentVelocity == null ? 0.0 : currentVelocity.getY();
                spitter.setVelocity(away.normalize().multiply(spitterRetreatSpeed).setY(verticalVelocity));
            }
        }
        if (dist > spitterMaxRange || dist < spitterMinRange || !spitter.hasLineOfSight(target)) return;

        long now = System.currentTimeMillis();
        Long lastSpit = spitter.getPersistentDataContainer().get(LAST_SPIT_KEY, PersistentDataType.LONG);
        if (lastSpit != null && (now - lastSpit) < spitterCooldownMs) return;

        Vector aim = target.getEyeLocation().toVector().subtract(spitter.getEyeLocation().toVector());
        if (aim.lengthSquared() < 0.0001) return;
        Vector velocity = aim.normalize().multiply(spitterProjectileSpeed);
        LlamaSpit spit = spitter.launchProjectile(LlamaSpit.class, velocity);
        spit.setShooter(spitter);
        spit.getPersistentDataContainer().set(ACID_SPIT_KEY, PersistentDataType.BYTE, (byte) 1);
        spitter.getPersistentDataContainer().set(LAST_SPIT_KEY, PersistentDataType.LONG, now);
        spitter.getWorld().playSound(spitter.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.0f, 0.8f);
    }

    private void tickScorchedAI(Zombie scorched) {
        if (ThreadLocalRandom.current().nextDouble() < 0.1) {
            scorched.getWorld().spawnParticle(Particle.FLAME, scorched.getLocation().add(0, 1, 0), 3, 0.2, 0.5, 0.2, 0.02);
        }
        for (Entity e : scorched.getNearbyEntities(2, 2, 2)) {
            if (e instanceof Player p && p.getGameMode() == GameMode.SURVIVAL) {
                // Cosmetic flame particles instead of actual fire
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.5, 0), 8, 0.3, 0.6, 0.3, 0.05);
                p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 0.5, 0), 4, 0.2, 0.4, 0.2, 0.02);
            }
        }
    }

    private void tickPsychopathAI(Zombie psycho) {
        var maxHealth = psycho.getAttribute(AttributeResolver.MAX_HEALTH);
        if (maxHealth != null && psycho.getHealth() < maxHealth.getValue() * 0.5) {
            long now = System.currentTimeMillis();
            Long lastRage = psycho.getPersistentDataContainer().get(LAST_RAGE_KEY, PersistentDataType.LONG);
            // Bug M1 fix: honor zombie-classes.psychopath.rage-cooldown-seconds (was hardcoded 10 s).
            if (lastRage == null || (now - lastRage) > psychopathRageCooldownMs) {
                psycho.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 1));
                psycho.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1));
                psycho.getWorld().playSound(psycho.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 1.5f);
                psycho.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, psycho.getLocation().add(0, 2, 0), 5);
                psycho.getPersistentDataContainer().set(LAST_RAGE_KEY, PersistentDataType.LONG, now);
            }
        }
    }

    private void tickBursterAI(Zombie burster) {
        if (burster.getTarget() instanceof Player player) {
            handleBursterTarget(burster, player);
        }
    }

    public void transformToVeteran(Zombie zombie) {
        // Single source of truth for the toggle: the same key the listener gates on
        // (zombie-classes.veteran.permanent). Previously this re-checked a separate "persist" key,
        // so disabling one but not the other produced confusing half-on behavior.
        if (!veteranPermanent) return;
        applyZombieType(zombie, ZombieType.VETERAN);
    }

    /**
     * Removes every blood-moon-tagged zombie (see {@link #BLOOD_MOON_KEY}) across all enabled worlds
     * with a small smoke puff + sound — called when a blood moon ends so the horde it summoned does
     * not linger. Zombies that existed before the blood moon are left untouched. Returns the count
     * removed. The entities are removed outright (no death drops), avoiding an end-of-event loot dump.
     */
    public int despawnBloodMoonZombies() {
        Queue<UUID> pending = new ArrayDeque<>();
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            if (!plugin.isWorldEnabled(world)) continue;
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                if (!zombie.getPersistentDataContainer().has(BLOOD_MOON_KEY, PersistentDataType.BYTE)) continue;
                pending.add(zombie.getUniqueId());
            }
        }
        int queued = pending.size();
        scheduleBloodMoonZombieCleanup(pending, new int[]{0});
        return queued;
    }

    private void scheduleBloodMoonZombieCleanup(Queue<UUID> pending, int[] removed) {
        if (pending.isEmpty() || !plugin.isEnabled()) {
            if (removed[0] > 0) {
                plugin.debugLog("Blood moon ended — despawned " + removed[0]
                        + " blood-moon zombie(s).");
            }
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) return;
            for (int processed = 0;
                    processed < BLOOD_MOON_CLEANUP_BATCH_SIZE && !pending.isEmpty();
                    processed++) {
                Entity entity = Bukkit.getEntity(pending.remove());
                if (!(entity instanceof Zombie zombie)) continue;
                if (!zombie.getPersistentDataContainer().has(
                        BLOOD_MOON_KEY, PersistentDataType.BYTE)) continue;
                cancelBursterFuse(zombie);
                Location location = zombie.getLocation();
                World world = zombie.getWorld();
                zombie.remove();
                removed[0]++;
                try {
                    world.spawnParticle(Particle.SMOKE, location.clone().add(0, 1, 0),
                            12, 0.25, 0.4, 0.25, 0.02);
                    world.playSound(location, Sound.ENTITY_ZOMBIE_DEATH, 0.6f, 0.7f);
                } catch (LinkageError | RuntimeException ignored) {
                    // Visual feedback is optional; cleanup must continue if a registry is unavailable.
                }
            }
            scheduleBloodMoonZombieCleanup(pending, removed);
        });
    }

    /**
     * Completes deferred Blood Moon cleanup when a previously-unloaded chunk returns. Bukkit's
     * world entity collections only contain loaded chunks, so the normal end-of-event sweep cannot
     * see every tagged zombie. The persistent tag is the durable cleanup record.
     */
    public int cleanupExpiredBloodMoonZombies(Chunk chunk) {
        if (chunk == null || plugin.isBloodMoonActive(chunk.getWorld())) return 0;

        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Zombie zombie)) continue;
            if (!zombie.getPersistentDataContainer().has(BLOOD_MOON_KEY, PersistentDataType.BYTE)) continue;
            cancelBursterFuse(zombie);
            zombie.remove();
            removed++;
        }
        return removed;
    }

    public boolean isInsideClaim(Location loc) {
        return plugin.isInsideClaim(loc);
    }

    public void handleAcidHit(Entity e) {
        handleAcidHit(e, null);
    }

    public void handleAcidHit(Entity e, Entity source) {
        if (!(e instanceof LivingEntity l)) return;
        if (spitterImpactDamage > 0) {
            if (source != null) l.damage(spitterImpactDamage, source);
            else l.damage(spitterImpactDamage);
        }
        if (spitterPoisonDurationTicks > 0) {
            l.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                    spitterPoisonDurationTicks, spitterPoisonAmplifier));
        }
        l.getWorld().spawnParticle(Particle.ITEM_SLIME, l.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);
        l.getWorld().playSound(l.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1.0f, 1.0f);
    }

    // === WEBBER EVENT HANDLERS ===
    
    public void handleWebberHit(Zombie webber, Player victim) {
        long now = System.currentTimeMillis();
        Long lastWeb = webber.getPersistentDataContainer().get(LAST_WEB_KEY, PersistentDataType.LONG);
        
        // Cooldown: once every 7 seconds per zombie
        if (lastWeb != null && (now - lastWeb) < 7000) return;

         Block baseBlock = victim.getLocation().getBlock();
         Set<Block> placed = new HashSet<>();
         int maxAttempts = Math.max(1, webberWebCount) * 10;
         int attempts = 0;

         while (placed.size() < webberWebCount && attempts < maxAttempts) {
             int dx = ThreadLocalRandom.current().nextInt(-1, 2);
             int dz = ThreadLocalRandom.current().nextInt(-1, 2);
             Block block = baseBlock.getRelative(dx, 0, dz);
             if (block.getType() == Material.AIR && !plugin.isInsideClaim(block.getLocation())) {
                 temporaryWebBlocks.put(block, block.getBlockData().clone());
                 block.setType(Material.COBWEB, false);
                 placed.add(block);
             }
             attempts++;
         }

         List<Block> webBlocks = new ArrayList<>(placed);
         Bukkit.getScheduler().runTaskLater(plugin, () -> {
             for (Block block : webBlocks) {
                 restoreTemporaryWeb(block);
             }
         }, webberCleanupDelayTicks);

        webber.getPersistentDataContainer().set(LAST_WEB_KEY, PersistentDataType.LONG, now);
    }

    // === BURSTER EVENT HANDLERS ===
    
    public void handleBursterTarget(Zombie burster, Player target) {
        if (!burster.getWorld().equals(target.getWorld())) return;
        if (burster.getLocation().distanceSquared(target.getLocation()) > bursterRadiusSquared) return;

        UUID id = burster.getUniqueId();
        if (activeBursterFuses.containsKey(id)) return;

        // CRITICAL FIX: Add burster fuse to tracking map
        BukkitRunnable fuse = new BukkitRunnable() {
            private int ticks = 0;
            
            @Override
            public void run() {
                if (burster.isDead() || !burster.isValid()) {
                    this.cancel();
                    activeBursterFuses.remove(id);
                    return;
                }
                
                ticks++;
                
                if (ticks >= bursterFuseTicks) {
                    // Explode
                    Location loc = burster.getLocation();
                    burster.remove(); // Remove entity before explosion
                    loc.getWorld().createExplosion(loc, bursterPower, false, bursterBreakBlocks);
                    this.cancel();
                    activeBursterFuses.remove(id);
                } else {
                    // Visual effects
                    burster.setGlowing(true);
                    if (ticks % 5 == 0) {
                        burster.setGlowing(false);
                    }
                }
            }
        };
        
        activeBursterFuses.put(id, fuse);
        fuse.runTaskTimer(plugin, 0, 1);
    }
    
    // CRITICAL FIX: Add method to cancel burster fuse
    public void cancelBursterFuse(Zombie burster) {
        UUID id = burster.getUniqueId();
        BukkitRunnable fuse = activeBursterFuses.remove(id);
        if (fuse != null) {
            fuse.cancel();
        }
        if (burster.isValid() && !burster.isDead()) {
            burster.setGlowing(false);
        }
        burster.getPersistentDataContainer().remove(BURSTER_PRIMED_KEY);
    }

    public void forgetTemporaryWeb(Block block) {
        temporaryWebBlocks.remove(block);
    }

    private void restoreTemporaryWeb(Block block) {
        BlockData original = temporaryWebBlocks.remove(block);
        if (original != null && block.getType() == Material.COBWEB) {
            block.setBlockData(original, false);
        }
    }

    public void cleanupTransientEffects() {
        for (BukkitRunnable fuse : new ArrayList<>(activeBursterFuses.values())) {
            fuse.cancel();
        }
        activeBursterFuses.clear();

        for (World world : Bukkit.getWorlds()) {
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                String type = zombie.getPersistentDataContainer().get(
                        ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
                if (ZombieType.BURSTER.name().equals(type)
                        || zombie.getPersistentDataContainer().has(BURSTER_PRIMED_KEY, PersistentDataType.BYTE)
                        || zombie.getPersistentDataContainer().has(BURSTER_PRIMED_KEY, PersistentDataType.LONG)) {
                    zombie.setGlowing(false);
                    zombie.getPersistentDataContainer().remove(BURSTER_PRIMED_KEY);
                }
            }
        }

        for (Block block : new ArrayList<>(temporaryWebBlocks.keySet())) {
            restoreTemporaryWeb(block);
        }
    }

    // === FROST EVENT HANDLERS ===

    public void handleFrostHit(Zombie frost, Player victim) {
        if (frostDurationTicks > 0) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    frostDurationTicks, frostAmplifier));
        }
    }

    // === SCORCHED EVENT HANDLERS ===

    /**
     * Bug M2 fix: a Scorched zombie sets its melee victim on fire for the configured duration.
     * Previously the class only emitted cosmetic flame particles and had no on-hit effect at all,
     * so zombie-classes.scorched.fire-duration-seconds did nothing.
     */
    public void handleScorchedHit(Zombie scorched, Player victim) {
        if (scorchedFireTicks <= 0) return;
        victim.setFireTicks(Math.max(victim.getFireTicks(), scorchedFireTicks));
    }

    // === PSYCHOPATH EVENT HANDLERS ===

    /**
     * Bug M1 fix: a Psychopath's melee applies a short "bleed" (Wither) for the configured duration.
     * Previously zombie-classes.psychopath.bleed-duration-seconds was never read.
     */
    public void handlePsychopathHit(Zombie psycho, Player victim) {
        if (psychopathBleedDurationTicks <= 0) return;
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                psychopathBleedDurationTicks, 0));
    }
}
