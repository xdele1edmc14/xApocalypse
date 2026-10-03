package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the entire Zombie-Guts Immunity subsystem: the immunity state maps, the data.yml
 * persistence file, the bossbar + expiry-check tasks, granting immunity on guts consumption,
 * and restoring player state on join/quit.
 *
 * Extracted verbatim from the original xApocalypse monolith. All BUGFIX behaviour is
 * preserved — most importantly that all immunity timing uses System.currentTimeMillis()
 * (the real-world clock) rather than world.getFullTime(), which is warped by the blood-moon
 * system's world.setTime() calls.
 */
public class ImmunityManager {

    private final xApocalypse plugin;
    private final MessageManager messageManager;

    // --- PERSISTENCE (data.yml is owned entirely by the immunity subsystem) ---
    private File dataFile;
    private FileConfiguration dataConfig;
    private AtomicYamlWriter dataWriter;

    // --- IMMUNITY TRACKING ---
    // Bug fix: this was a List<UUID>, which permitted DUPLICATE entries. loadImmunityData()
    // (on enable/reload) and onPlayerJoin() both add the UUID with no guard, while onPlayerQuit()
    // intentionally leaves the entry to persist immunity — so a restart or reconnect during an
    // active immunity listed the player twice ([uuid, uuid]). cleanUpPlayerState()'s
    // List.remove() then stripped only ONE copy, leaving the player permanently flagged immune in
    // memory (zombies ignored them; re-consuming Zombie Guts said "already immune") even though
    // data.yml was correctly cleared. A Set cannot hold duplicates, so every expiry path now fully
    // clears membership. This set is concurrent because PlaceholderAPI may read it asynchronously.
    private final Set<UUID> immunePlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BossBar> immunityBossBars = new HashMap<>();
    private final Map<UUID, Long> immunityEndTime = new ConcurrentHashMap<>();
    private final Map<UUID, Double> originalHealth = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledTasks = new HashMap<>();
    private final long IMMUNITY_DURATION_TICKS = 10 * 60 * 20L;
    // BUGFIX: immunity timing now runs off the real-world clock instead of world.getFullTime(),
    // because world.setTime() calls elsewhere (blood moon start/stop/correction) warp full-time
    // by up to +-24000 ticks instantly, which was corrupting every active immunity countdown.
    private final long IMMUNITY_DURATION_MILLIS = IMMUNITY_DURATION_TICKS * 50L;

    public ImmunityManager(xApocalypse plugin, MessageManager messageManager) {
        this.plugin = plugin;
        this.messageManager = messageManager;
    }

    // === SETUP / PERSISTENCE ===

    /** Initializes data.yml and loads persisted immunity state. */
    public void load() {
        // --- Data Persistence Setup ---
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        dataWriter = new AtomicYamlWriter(dataFile.toPath(), exception ->
                plugin.getLogger().severe("Could not save data.yml: " + exception.getMessage()));

        loadImmunityData();
    }

    private void loadImmunityData() {
        // Fixed: Null check to prevent startup crash
        if (!dataConfig.isConfigurationSection("player-immunity")) {
            plugin.debugLog("No immunity data found in data.yml");
            return;
        }

        // BUGFIX: use the real-world clock (System.currentTimeMillis()) instead of
        // world.getFullTime() — full-time gets warped by world.setTime() calls in the
        // blood moon system, which was the root cause of immunity timers freezing or
        // jumping to nonsense values. We store an ABSOLUTE end timestamp so downtime
        // between saves/restarts is accounted for automatically (just like a real clock).
        long now = System.currentTimeMillis();

        for (String key : dataConfig.getConfigurationSection("player-immunity").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long endTimeMillis = dataConfig.getLong("player-immunity." + key + ".endTimeMillis");
                double originalHealthVal = dataConfig.getDouble("player-immunity." + key + ".originalHealth");

                if (originalHealthVal <= 0.0) continue;

                // Always remember their original max health so onPlayerJoin can restore
                // it even if immunity already expired while the server was offline.
                originalHealth.put(uuid, originalHealthVal);
                immunityEndTime.put(uuid, endTimeMillis);

                long remainingMillis = endTimeMillis - now;
                if (remainingMillis > IMMUNITY_DURATION_MILLIS) {
                    remainingMillis = IMMUNITY_DURATION_MILLIS;
                }

                if (remainingMillis > 0) {
                    long newEndTime = now + remainingMillis;
                    immunityEndTime.put(uuid, newEndTime);
                    immunePlayers.add(uuid);
                    plugin.debugLog("Loaded active immunity for " + key);

                    // BUGFIX: previously this never recreated the bossbar or scheduled a
                    // removal task, so a player already online when the plugin (re)enabled
                    // (e.g. /plugman reload) had no bossbar and depended entirely on the
                    // periodic check task to ever clear their immunity.
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                        bar.addPlayer(online);
                        immunityBossBars.put(uuid, bar);
                        scheduleImmunityRemoval(online, remainingMillis / 50L);
                    }
                }

            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in data.yml: " + key);
            }
        }
    }

    public void save() {
        // CRITICAL FIX: Add null safety checks
        if (dataConfig == null || dataFile == null || dataWriter == null) {
            plugin.getLogger().warning("Cannot save immunity data - data files not initialized");
            return;
        }

        dataConfig.set("player-immunity", null);

        for (UUID uuid : new ArrayList<>(originalHealth.keySet())) {
            String path = "player-immunity." + uuid.toString();
            double storedHealth = originalHealth.get(uuid);
            Long endTime = immunityEndTime.get(uuid);

            // BUGFIX: only persist players who are actually still immune. Storing an
            // absolute epoch-millis timestamp here (instead of a remaining-tick count
            // derived from world.getFullTime()) means this value survives server
            // downtime and blood-moon time skips correctly.
            if (storedHealth > 0.0 && endTime != null) {
                dataConfig.set(path + ".endTimeMillis", endTime);
                dataConfig.set(path + ".originalHealth", storedHealth);
            }
        }
        try {
            dataWriter.write(dataConfig.saveToString());
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Could not serialize data.yml: " + exception.getMessage());
        }
    }

    // === REPEATING TASKS ===

    // CRITICAL FIX: immunity check task
    public void startCheckTask() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (immunePlayers.isEmpty()) return;

                // BUGFIX: was Bukkit.getWorlds().get(0).getFullTime() — world full-time gets
                // warped by world.setTime() calls (blood moon start/stop/correction), which
                // froze or scrambled this check entirely. System time is unaffected by that.
                long now = System.currentTimeMillis();
                boolean retiredOfflineState = false;

                for (UUID uuid : new ArrayList<>(immunePlayers)) {
                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) {
                        immunePlayers.remove(uuid);
                        continue;
                    }

                    // Check if immunity has expired
                    if (now >= endTime) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null || !player.isOnline()) {
                            immunePlayers.remove(uuid);
                            BukkitTask task = scheduledTasks.remove(uuid);
                            if (task != null) task.cancel();
                            BossBar bar = immunityBossBars.remove(uuid);
                            if (bar != null) bar.removeAll();
                            retiredOfflineState = true;
                            continue;
                        }
                        plugin.debugLog("Immunity expired for player " + player.getName() + ", retargeting zombies");

                        // BUGFIX: this branch used to call cleanUpPlayerState() directly,
                        // which wipes originalHealth WITHOUT ever restoring the player's
                        // max-health attribute. Any immunity that expired through this path
                        // (e.g. one restored from data.yml after a restart, which never gets
                        // a bossbar) left the player permanently stuck at reduced health.
                        // expireImmunity() does the restore first, then cleans up.
                        expireImmunity(player, "immunity.expired");

                        // Force nearby zombies to target the player
                        retargetZombiesNearPlayer(player);
                    }
                }
                if (retiredOfflineState) save();
            }
        }.runTaskTimer(plugin, 20L, 20L); // Check every second
    }

    public void startBossBarTask() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (immunityBossBars.isEmpty()) return;

                // BUGFIX: real-world clock instead of world.getFullTime() (see startCheckTask).
                long now = System.currentTimeMillis();

                for (UUID uuid : new ArrayList<>(immunityBossBars.keySet())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) { continue; }
                    if (player.getWorld() == null) continue;

                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) continue;

                    long remainingMillis = endTime - now;

                    if (remainingMillis <= 0) {
                        // Immunity expired - clean up immediately (restores health internally)
                        expireImmunity(player, "immunity.expired");
                        continue;
                    }

                    double progress = (double) remainingMillis / IMMUNITY_DURATION_MILLIS;
                    immunityBossBars.get(uuid).setProgress(Math.max(0.0, Math.min(1.0, progress)));

                    long remainingSeconds = remainingMillis / 1000;
                    long minutes = remainingSeconds / 60;
                    long seconds = remainingSeconds % 60;
                    String timeString = String.format("%02d:%02d", minutes, seconds);

                    immunityBossBars.get(uuid).setTitle(messageManager.get("immunity.bossbar", timeString));
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    // CRITICAL FIX: method to retarget zombies near player
    private void retargetZombiesNearPlayer(Player player) {
        Location loc = player.getLocation();
        double radius = 50.0; // Check within 50 blocks

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity instanceof Zombie zombie) {
                // Only retarget zombies that don't already have a target
                if (zombie.getTarget() == null) {
                    zombie.setTarget(player);
                    plugin.debugLog("Retargeted zombie to player " + player.getName());
                }
            }
        }
    }

    /**
     * BUGFIX: single source of truth for ending a player's Zombie Guts immunity.
     * Previously there were three separate copies of this logic (here, the boss bar
     * task, and the scheduled removal task) and one of them — the check task above —
     * never restored the player's max health, which is why hearts sometimes never
     * changed back after immunity wore off. Restoring health here, in one place,
     * means every expiry path behaves identically.
     */
    private void expireImmunity(Player player, String expiredMessageKey) {
        UUID uuid = player.getUniqueId();

        // Bug M6 fix: the bossbar task (every 5 ticks) and the check task (every 20 ticks) can
        // both hit the expiry edge on the same tick. Bail if this player is no longer immune so
        // the "expired" message and health restore only happen once.
        if (!immunePlayers.contains(uuid)) return;

        if (originalHealth.containsKey(uuid) && player.getAttribute(AttributeResolver.MAX_HEALTH) != null) {
            double originalMaxHealth = originalHealth.get(uuid);
            player.getAttribute(AttributeResolver.MAX_HEALTH).setBaseValue(originalMaxHealth);
            player.setHealth(Math.min(player.getHealth(), originalMaxHealth));
            player.sendMessage(messageManager.get("immunity.health-restored"));
        }

        cleanUpPlayerState(player);
        player.sendMessage(messageManager.get(expiredMessageKey));

        save();
    }

    private void scheduleImmunityRemoval(Player player, long durationTicks) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && immunePlayers.contains(uuid)) {
                expireImmunity(player, "immunity.expired");
            }
            scheduledTasks.remove(uuid);
        }, Math.max(0, durationTicks));
        scheduledTasks.put(uuid, task);
    }

    private void cleanUpPlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        immunePlayers.remove(uuid);
        immunityEndTime.remove(uuid);
        originalHealth.remove(uuid);

        BukkitTask task = scheduledTasks.remove(uuid);
        if (task != null) task.cancel();

        BossBar bar = immunityBossBars.remove(uuid);
        if (bar != null) bar.removeAll();
    }

    // === EVENT HOOKS (called by xApocalypseListener) ===

    public boolean isImmune(UUID uuid) {
        return immunePlayers.contains(uuid);
    }

    /** Remaining Zombie Guts duration in whole seconds, rounded up while the effect is active. */
    public long getRemainingSeconds(UUID uuid) {
        if (!immunePlayers.contains(uuid)) return 0L;
        Long endTime = immunityEndTime.get(uuid);
        if (endTime == null) return 0L;
        long remainingMillis = endTime - System.currentTimeMillis();
        return remainingMillis <= 0L ? 0L : (remainingMillis + 999L) / 1000L;
    }

    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();

        if (originalHealth.containsKey(uuid) && player.getAttribute(AttributeResolver.MAX_HEALTH) != null) {
            // BUGFIX: was player.getWorld().getFullTime() — corrupted by world.setTime()
            // calls in the blood moon system. Use the real-world clock instead.
            long remainingMillis = 0;
            if (immunityEndTime.containsKey(uuid)) {
                remainingMillis = immunityEndTime.get(uuid) - System.currentTimeMillis();
            }

            if (remainingMillis > 0) {
                player.getAttribute(AttributeResolver.MAX_HEALTH).setBaseValue(10.0);
                player.setHealth(Math.min(player.getHealth(), 10.0));
                immunePlayers.add(uuid);

                BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                bar.addPlayer(player);
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, remainingMillis / 50L);
            } else {
                double storedOriginalHealth = originalHealth.get(uuid);
                player.getAttribute(AttributeResolver.MAX_HEALTH).setBaseValue(storedOriginalHealth);
                player.setHealth(Math.min(player.getHealth(), storedOriginalHealth));
                cleanUpPlayerState(player);
                save();
            }
        }
    }

    /** Immune-player cleanup on quit. The listener invokes {@link #save()} afterwards (parity with original ordering). */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();

        if (immunePlayers.contains(uuid)) {
            BukkitTask task = scheduledTasks.remove(uuid);
            if (task != null) task.cancel();

            BossBar bar = immunityBossBars.remove(uuid);
            if (bar != null) bar.removeAll();
        }
    }

    // ==================================================================================
    // ZOMBIE GUTS — item factory, identity, activation, and rare drop
    // ==================================================================================

    /**
     * Single source of truth for the Zombie Guts ItemStack. Used by the /xa item command and the
     * rare death-drop, and recognised back by {@link #isZombieGutsItem}. The item carries a PDC
     * marker key so identity survives display-name / MiniMessage changes.
     */
    public ItemStack createZombieGutsItem(int amount) {
        ItemStack guts = new ItemStack(Material.ROTTEN_FLESH, Math.max(1, amount));
        ItemMeta meta = guts.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(messageManager.get("immunity.item-name"));
            meta.setLore(messageManager.getList("immunity.item-lore"));
            meta.getPersistentDataContainer().set(xApocalypseUtils.ZOMBIE_GUTS_KEY, PersistentDataType.BYTE, (byte) 1);
            guts.setItemMeta(meta);
        }
        return guts;
    }

    /** True if the stack is a Zombie Guts item — PDC marker first, legacy display-name match as a fallback. */
    public boolean isZombieGutsItem(ItemStack item) {
        if (item == null || item.getType() != Material.ROTTEN_FLESH) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (meta.getPersistentDataContainer().has(xApocalypseUtils.ZOMBIE_GUTS_KEY, PersistentDataType.BYTE)) return true;
        return meta.hasDisplayName() && meta.getDisplayName().equals(messageManager.get("immunity.item-name"));
    }

    /**
     * Primary activation path: a right-click. BUGFIX — the old code only granted immunity from
     * {@link PlayerItemConsumeEvent}, which never fires when the hunger bar is full or in Creative
     * mode (Zombie Guts is rotten flesh — normal food), so eating it often did nothing at all and
     * hearts never dropped to 5. Driving activation off a right-click makes it independent of
     * hunger level and game mode.
     */
    public void handleGutsInteract(PlayerInteractEvent event) {
        if (!plugin.isZombieGutsEnabled()) return;

        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // Don't hijack right-clicks on usable blocks (chests, doors, buttons, crafting tables, ...).
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable()) return;

        // event.getItem() is the item in the hand that fired THIS event, so the main-hand and
        // off-hand passes are naturally distinguished — only the hand actually holding guts proceeds.
        if (!isZombieGutsItem(event.getItem())) return;

        // Bukkit fires one interaction per hand. If both hands hold Guts, prefer the main hand and
        // silently suppress the off-hand pass so it cannot emit a second "already immune" message.
        if (hand == EquipmentSlot.OFF_HAND
                && isZombieGutsItem(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true); // suppress the vanilla eat / any use-on-block
        grantGutsImmunity(event.getPlayer(), hand);
    }

    /**
     * Fallback path for players who still actually eat the item in Survival (hunger not full).
     * Cancels the consume so vanilla nutrition isn't applied, then routes to the same grant logic.
     */
    public void handleGutsConsume(PlayerItemConsumeEvent event) {
        if (!plugin.isZombieGutsEnabled()) return;
        if (!isZombieGutsItem(event.getItem())) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        EquipmentSlot hand = isZombieGutsItem(player.getInventory().getItemInMainHand())
                ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        grantGutsImmunity(player, hand);
    }

    /**
     * Grants Zombie Guts immunity: drops max health to 5 hearts, starts the 10-minute timer +
     * bossbar, persists state, clears current zombie targets, and consumes one item from {@code hand}.
     * Shared by both the right-click and the consume path.
     */
    private void grantGutsImmunity(Player player, EquipmentSlot hand) {
        UUID uuid = player.getUniqueId();

        if (immunePlayers.contains(uuid)) {
            player.sendMessage(messageManager.get("immunity.already-immune"));
            return;
        }

        // BUGFIX: this null check used to run AFTER originalHealth/immunePlayers were already
        // mutated, so a null world would leave the player permanently flagged immune with no
        // bossbar and no removal task.
        if (player.getWorld() == null) return;

        double maxHealth = 10.0;
        if (player.getAttribute(AttributeResolver.MAX_HEALTH) != null) {
            originalHealth.put(uuid, player.getAttribute(AttributeResolver.MAX_HEALTH).getBaseValue());

            player.getAttribute(AttributeResolver.MAX_HEALTH).setBaseValue(maxHealth);
            player.setHealth(Math.min(player.getHealth(), maxHealth));
        }

        // BUGFIX: was player.getWorld().getFullTime() + IMMUNITY_DURATION_TICKS — world full-time
        // is warped by world.setTime() calls in the blood moon system (forced/natural start, stop,
        // and the night-time correction), which is what made this timer freeze or jump.
        long endTime = System.currentTimeMillis() + IMMUNITY_DURATION_MILLIS;
        immunityEndTime.put(uuid, endTime);
        immunePlayers.add(uuid);

        BossBar bar = Bukkit.createBossBar(
                messageManager.get("immunity.bossbar", "10:00"),
                BarColor.GREEN,
                BarStyle.SOLID
        );
        bar.addPlayer(player);
        immunityBossBars.put(uuid, bar);

        scheduleImmunityRemoval(player, IMMUNITY_DURATION_TICKS);

        // BUGFIX: persist immediately — a crash or /stop before the player quit used to lose the grant.
        save();

        player.sendMessage(messageManager.get("immunity.consumed"));

        // Clear all zombies currently targeting this player
        for (Entity entity : player.getWorld().getEntitiesByClass(Zombie.class)) {
            if (entity instanceof Zombie zombie) {
                if (zombie.getTarget() != null && zombie.getTarget().equals(player)) {
                    zombie.setTarget(null);
                }
            }
        }

        consumeOne(player, hand);
    }

    /** Removes one Zombie Guts from the given hand (live inventory stack), clearing the slot at 0. */
    private void consumeOne(Player player, EquipmentSlot hand) {
        ItemStack inHand = (hand == EquipmentSlot.OFF_HAND)
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == Material.AIR) return;

        if (inHand.getAmount() > 1) {
            inHand.setAmount(inHand.getAmount() - 1);
        } else if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    /**
     * Rolls the configurable rare chance for a slain zombie to drop Zombie Guts, adding it to the
     * death drops so it flows through vanilla / other-plugin drop handling. Config is read live so
     * /xa reload takes effect. Called from the death listener BEFORE the scent-system early-return,
     * so it works even when the scent system is disabled. Defaults match the shipped config.yml.
     */
    public void maybeDropZombieGuts(EntityDeathEvent event, Zombie zombie) {
        if (!plugin.isZombieGutsEnabled()) return;
        if (!plugin.isWorldEnabled(zombie.getWorld()) || plugin.isLobbyWorld(zombie.getWorld())) return;
        if (!plugin.getConfig().getBoolean("zombie-settings.zombie-guts.drop.enabled", true)) return;
        if (plugin.getConfig().getBoolean("zombie-settings.zombie-guts.drop.require-player-kill", true)
                && zombie.getKiller() == null) return;

        double chance = plugin.getConfig().getDouble("zombie-settings.zombie-guts.drop.chance", 0.02);
        if (chance <= 0.0) return;

        if (ThreadLocalRandom.current().nextDouble() < chance) {
            event.getDrops().add(createZombieGutsItem(1));
        }
    }

    // === SHUTDOWN ===

    public void removeAllBossBars() {
        for (BossBar bar : immunityBossBars.values()) {
            bar.removeAll();
        }
    }

    public void close() {
        if (dataWriter != null) dataWriter.close();
    }
}
