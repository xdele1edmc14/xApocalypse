package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the Scent subsystem: per-player scent accumulation, the decay + continuous-sprint
 * tasks, and scent accrual from sprinting, jumping and kills. Scent raises the size of the
 * hordes spawned near a player (consumed by {@link HordeManager#spawnZombiesNearPlayer}).
 *
 * Extracted verbatim from the original xApocalypse monolith (Bug C4 jump-edge detection
 * preserved).
 */
public class ScentManager {

    private final xApocalypse plugin;

    private final Map<UUID, Double> playerScent = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastJumpTime = new HashMap<>();
    // Bug C4 fix: previous-tick ground state so onMove can detect the on-ground -> airborne jump edge
    private final Map<UUID, Boolean> playerWasOnGround = new HashMap<>();
    private BukkitTask scentDecayTask;
    private BukkitTask scentSprintTask;

    // Minor fix: cache hot-path config so onMove (fires on EVERY PlayerMoveEvent, including
    // look-only moves) and addScent don't re-read config each call. Refreshed in startTasks()
    // on enable and on every /xa reload.
    private boolean scentEnabled = true;
    private double maxScent = 100.0;

    public ScentManager(xApocalypse plugin) {
        this.plugin = plugin;
    }

    public void startTasks() {
        if (scentDecayTask != null && !scentDecayTask.isCancelled()) scentDecayTask.cancel();
        if (scentSprintTask != null && !scentSprintTask.isCancelled()) scentSprintTask.cancel();
        scentDecayTask = null;
        scentSprintTask = null;

        scentEnabled = plugin.getConfig().getBoolean("scent-system.enabled", true);
        maxScent = Math.max(0.0, plugin.getConfig().getDouble("scent-system.max-scent", 100.0));
        playerScent.replaceAll((uuid, scent) -> Math.max(0.0, Math.min(scent, maxScent)));
        playerScent.entrySet().removeIf(entry -> entry.getValue() <= 0.0);

        if (!scentEnabled) return;

        int intervalSeconds = Math.max(1,
                plugin.getConfig().getInt("scent-system.decay-interval-seconds", 5));
        double decayAmount = Math.max(0.0,
                plugin.getConfig().getDouble("scent-system.decay-amount", 1.0));

        scentDecayTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : new ArrayList<>(playerScent.keySet())) {
                    double current = playerScent.get(uuid);
                    double newScent = Math.max(0.0, current - decayAmount);
                    if (newScent <= 0.0) {
                        playerScent.remove(uuid);
                    } else {
                        playerScent.put(uuid, newScent);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, intervalSeconds * 20L);

        // Continuous sprint scent: fires every 20 ticks (1 second) using the player's live state.
        double sprintAdd = Math.max(0.0,
                plugin.getConfig().getDouble("scent-system.sprint-add", 2.0));
        scentSprintTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isScentWorld(p)) continue;
                    if (p.isSprinting()) {
                        addScent(p.getUniqueId(), sprintAdd);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public double getScent(UUID uuid) {
        return playerScent.getOrDefault(uuid, 0.0);
    }

    public void addScent(UUID uuid, double amount) {
        if (!scentEnabled || amount <= 0.0 || maxScent <= 0.0) return;
        double current = playerScent.getOrDefault(uuid, 0.0);
        double newScent = Math.min(current + amount, maxScent);
        playerScent.put(uuid, newScent);
        plugin.debugLog("Player " + uuid + " scent increased by " + amount + " (now: " + newScent + " / " + maxScent + ")");
    }

    // === EVENT HOOKS (called by xApocalypseListener) ===

    public void onMove(Player player) {
        UUID uuid = player.getUniqueId();
        if (!scentEnabled || !isScentWorld(player)) {
            playerWasOnGround.remove(uuid);
            return;
        }

        // Jump detection: a jump is the transition from on-ground (last tick) to airborne
        // (this tick) with upward velocity. Bug C4 fix: the old guard `if (!player.isOnGround())
        // return;` fired for the entire airborne portion of every jump, so the velocity check
        // below was never reached and jumps produced zero scent. Track the previous-tick ground
        // state to detect the take-off edge instead.
        boolean wasOnGround = playerWasOnGround.getOrDefault(uuid, true);
        boolean nowOnGround = player.isOnGround();
        playerWasOnGround.put(uuid, nowOnGround);

        if (!wasOnGround || nowOnGround) return;

        long currentTime = System.currentTimeMillis();
        Long lastJump = lastJumpTime.get(uuid);
        if (lastJump != null && (currentTime - lastJump) < 500) return;

        double velocityY = player.getVelocity().getY();
        if (velocityY > 0.3) {
            double jumpAdd = Math.max(0.0,
                    plugin.getConfig().getDouble("scent-system.jump-add", 0.5));
            addScent(uuid, jumpAdd);
            lastJumpTime.put(uuid, currentTime);
            plugin.debugLog("Player " + player.getName() + " jumped, added " + jumpAdd + " scent");
        }
    }

    public void onKill(Player killer) {
        if (!scentEnabled || !isScentWorld(killer)) return;
        double killAdd = Math.max(0.0,
                plugin.getConfig().getDouble("scent-system.kill-add", 1.0));
        addScent(killer.getUniqueId(), killAdd);
    }

    public void onPlayerQuit(UUID uuid) {
        playerScent.remove(uuid);
        lastJumpTime.remove(uuid);
        playerWasOnGround.remove(uuid);
    }

    private boolean isScentWorld(Player player) {
        return plugin.isWorldEnabled(player.getWorld()) || plugin.isLobbyWorld(player.getWorld());
    }
}
