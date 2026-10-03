package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class HordeSpawnerTask extends BukkitRunnable {

    private final xApocalypse plugin;

    public HordeSpawnerTask(xApocalypse plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            plugin.debugLog("TASK: Running scheduled spawner check.");

            // Minor fix: exit early if TPS is critically low (PerformanceWatchdog sets this flag)
            if (plugin.getPerformanceWatchdog() != null && plugin.getPerformanceWatchdog().isSpawningPaused()) {
                plugin.debugLog("TASK: Spawning paused due to low TPS, skipping.");
                return;
            }

            // Bug 12 fix: use the first world that is actually enabled by the plugin,
            // not Bukkit.getWorlds().get(0) which may be an untracked world (e.g. nether/end).
            World targetWorld = Bukkit.getWorlds().stream()
                    .filter(plugin::isWorldEnabled)
                    .findFirst()
                    .orElse(null);

            if (targetWorld == null) {
                plugin.debugLog("TASK: No enabled worlds loaded, skipping spawn attempt.");
                return;
            }

            plugin.beginHordeSpawnCycle();
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            Collections.shuffle(players);
            for (Player player : players) {
                // Diagnostic: log the live world the plugin actually sees for this player and the
                // reason it is (or isn't) eligible. Decisive for the "/rtp lands me in the lobby
                // world, not the main world" question — the spawner always reads player.getWorld()
                // fresh, so whatever name prints here is genuinely where the player is right now.
                World pw = player.getWorld();
                if (!plugin.isWorldEnabled(pw)) {
                    plugin.debugLog("Spawn skip: " + player.getName() + " is in world '" + pw.getName()
                            + "' which is NOT in enabled-worlds.");
                    continue;
                }
                // Lobby world: bossbar and events still work, but no zombie spawning
                if (plugin.isLobbyWorld(pw)) {
                    plugin.debugLog("Spawn skip: " + player.getName() + " is in world '" + pw.getName()
                            + "' which is a lobby-world (spawning suppressed there).");
                    continue;
                }
                // Minor fix: use enum constants instead of string comparison
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                    plugin.debugLog("Spawn skip: " + player.getName() + " is in gamemode " + player.getGameMode() + ".");
                    continue;
                }

                // Day/night eligibility belongs to the player's actual world. Using the first
                // enabled world's clock suppressed night hordes in secondary worlds and could
                // spawn full night hordes around players standing in daylight.
                long playerWorldTime = pw.getTime();
                boolean isDayHordeSpawn = playerWorldTime >= 0 && playerWorldTime < 13000;
                if (isDayHordeSpawn) {
                    double daySpawnChance = Math.max(0.0, Math.min(1.0,
                            plugin.getConfig().getDouble(
                                    "apocalypse-settings.day-spawn-chance", 0.0)));
                    if (ThreadLocalRandom.current().nextDouble() >= daySpawnChance) continue;
                }
                plugin.debugLog("Spawn eligible: " + player.getName() + " in world '" + pw.getName() + "' — spawning horde.");
                plugin.spawnZombiesNearPlayer(player, isDayHordeSpawn);
            }

        } catch (Exception t) {
            plugin.getLogger().severe("FATAL TASK ERROR: The repeating spawn task crashed! Check stack trace below.");
            t.printStackTrace();
        }
    }
}
