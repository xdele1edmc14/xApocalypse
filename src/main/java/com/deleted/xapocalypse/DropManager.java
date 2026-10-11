package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the configurable custom death-reward systems: extra items a slain zombie can drop on top of
 * the vanilla rotten flesh and the Zombie Guts rare drop ({@link ImmunityManager}), console commands
 * run on a kill, and an optional broadcast. MythicMobs exclusively owns boss drops and rewards.
 *
 * Zombie drops/commands read {@code normal} vs {@code bloodmoon} tables from
 * {@code zombie-settings.custom-drops} / {@code zombie-settings.kill-commands}, chosen per-death by
 * {@link xApocalypse#isBloodMoonActive}.
 * Config is parsed once on load/reload (cheap per-death iteration), matching the manager pattern
 * used elsewhere in the plugin.
 */
public class DropManager {

    private static final int MAX_CONFIGURED_DROP_AMOUNT = 64;

    private final xApocalypse plugin;

    private boolean enabled;
    private boolean requirePlayerKill;
    private final List<DropEntry> normalDrops = new ArrayList<>();
    private final List<DropEntry> bloodMoonDrops = new ArrayList<>();

    private boolean commandsEnabled;
    private boolean commandsRequirePlayerKill;
    private final List<CommandEntry> normalCommands = new ArrayList<>();
    private final List<CommandEntry> bloodMoonCommands = new ArrayList<>();

    private boolean zombieBroadcastEnabled;
    private double zombieBroadcastChance;
    private String zombieBroadcastMessage;

    /** One configured drop: an item, an inclusive amount range, and a per-death roll chance (0–1). */
    private record DropEntry(Material material, int min, int max, double chance) {}

    /** One configured kill-command: the raw command string and a per-death roll chance (0–1). */
    private record CommandEntry(String command, double chance) {}

    public DropManager(xApocalypse plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    /** Re-reads the custom-drops config. Called from onEnable and on /xa reload. */
    public void loadConfig() {
        enabled = plugin.getConfig().getBoolean("zombie-settings.custom-drops.enabled", true);
        requirePlayerKill = plugin.getConfig().getBoolean("zombie-settings.custom-drops.require-player-kill", true);

        normalDrops.clear();
        bloodMoonDrops.clear();
        parseTable("zombie-settings.custom-drops.normal", normalDrops);
        parseTable("zombie-settings.custom-drops.bloodmoon", bloodMoonDrops);

        commandsEnabled = plugin.getConfig().getBoolean("zombie-settings.kill-commands.enabled", false);
        commandsRequirePlayerKill = plugin.getConfig().getBoolean("zombie-settings.kill-commands.require-player-kill", true);

        normalCommands.clear();
        bloodMoonCommands.clear();
        parseCommandTable("zombie-settings.kill-commands.normal", normalCommands);
        parseCommandTable("zombie-settings.kill-commands.bloodmoon", bloodMoonCommands);

        zombieBroadcastEnabled = plugin.getConfig().getBoolean("zombie-settings.kill-commands.broadcast.enabled", false);
        zombieBroadcastChance = clampChance(plugin.getConfig().getDouble("zombie-settings.kill-commands.broadcast.chance", 0.0), "zombie-settings.kill-commands.broadcast");
        zombieBroadcastMessage = plugin.getConfig().getString("zombie-settings.kill-commands.broadcast.message", "");

        plugin.debugLog("Custom drops loaded: " + normalDrops.size() + " normal, "
                + bloodMoonDrops.size() + " blood-moon entries (enabled=" + enabled + ").");
        plugin.debugLog("Kill commands loaded: " + normalCommands.size() + " normal, "
                + bloodMoonCommands.size() + " blood-moon entries (enabled=" + commandsEnabled + ").");
    }

    private void parseTable(String path, List<DropEntry> into) {
        List<?> raw = plugin.getConfig().getList(path);
        if (raw == null) return;

        for (Object obj : raw) {
            if (!(obj instanceof java.util.Map<?, ?> map)) continue;

            Object matObj = map.get("material");
            if (matObj == null) continue;
            Material material = Material.matchMaterial(String.valueOf(matObj).trim().toUpperCase());
            if (material == null || material.isAir() || !material.isItem()) {
                plugin.getLogger().warning("[CustomDrops] Skipping invalid material '" + matObj + "' in " + path);
                continue;
            }

            int[] range = parseAmount(map.get("amount"), path, material.getMaxStackSize());
            double chance = parseChance(map.get("chance"));
            if (chance <= 0.0) continue;
            if (chance > 1.0) {
                plugin.getLogger().warning("[CustomDrops] chance " + chance + " in " + path
                        + " is above the documented 0.0-1.0 range; treating as guaranteed (1.0)."
                        + " For a percent, use a fraction (e.g. 50% -> 0.5).");
                chance = 1.0;
            }

            into.add(new DropEntry(material, range[0], range[1], chance));
        }
    }

    private void parseCommandTable(String path, List<CommandEntry> into) {
        List<?> raw = plugin.getConfig().getList(path);
        if (raw == null) return;

        for (Object obj : raw) {
            if (!(obj instanceof java.util.Map<?, ?> map)) continue;

            Object cmdObj = map.get("command");
            if (cmdObj == null) continue;
            String command = String.valueOf(cmdObj).trim();
            if (command.isEmpty()) continue;
            // Tolerate a leading slash; console dispatch expects the command without it.
            if (command.startsWith("/")) command = command.substring(1);

            double chance = parseChance(map.get("chance"));
            if (chance <= 0.0) continue;
            if (chance > 1.0) {
                plugin.getLogger().warning("[KillCommands] chance " + chance + " in " + path
                        + " is above the documented 0.0-1.0 range; treating as guaranteed (1.0)."
                        + " For a percent, use a fraction (e.g. 50% -> 0.5).");
                chance = 1.0;
            }

            into.add(new CommandEntry(command, chance));
        }
    }

    /** Accepts a single int ("3"/3) or a "min-max" range and bounds it to one safe stack. */
    private int[] parseAmount(Object amountObj, String path) {
        return parseAmount(amountObj, path, MAX_CONFIGURED_DROP_AMOUNT);
    }

    private int[] parseAmount(Object amountObj, String path, int materialStackLimit) {
        int min = 1, max = 1;
        if (amountObj != null) {
            String s = String.valueOf(amountObj).trim();
            try {
                if (s.contains("-")) {
                    String[] parts = s.split("-", 2);
                    min = Integer.parseInt(parts[0].trim());
                    max = Integer.parseInt(parts[1].trim());
                } else {
                    min = max = Integer.parseInt(s);
                }
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("[CustomDrops] Invalid amount '" + amountObj + "' in " + path
                        + "; defaulting to 1. Use a number (\"3\") or an inclusive range (\"1-2\").");
                min = max = 1;
            }
        }
        int safeLimit = Math.clamp(materialStackLimit, 1, MAX_CONFIGURED_DROP_AMOUNT);
        min = Math.clamp(min, 1, safeLimit);
        max = Math.clamp(max, min, safeLimit);
        return new int[]{min, max};
    }

    private double parseChance(Object chanceObj) {
        if (chanceObj == null) return 0.0;
        try {
            double chance = Double.parseDouble(String.valueOf(chanceObj).trim());
            return Double.isFinite(chance) ? chance : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Clamps a broadcast chance to 0-1, warning once (per load) if it was out of range. */
    private double clampChance(double chance, String path) {
        if (!Double.isFinite(chance)) return 0.0;
        if (chance > 1.0) {
            plugin.getLogger().warning("[KillCommands] chance " + chance + " in " + path
                    + " is above the documented 0.0-1.0 range; treating as guaranteed (1.0).");
            return 1.0;
        }
        return Math.max(0.0, chance);
    }

    /** Rolls a drop table and appends winners to the death drops. */
    private void rollDrops(List<DropEntry> table, EntityDeathEvent event) {
        if (table.isEmpty()) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (DropEntry entry : table) {
            if (rng.nextDouble() >= entry.chance()) continue;
            int amount = entry.min() == entry.max()
                    ? entry.min()
                    : rng.nextInt(entry.min(), entry.max() + 1);
            event.getDrops().add(new ItemStack(entry.material(), amount));
        }
    }

    /**
     * Rolls a command table and dispatches winners from the console. {@code %player%} is replaced
     * with {@code playerName}; entries referencing {@code %player%} are skipped when it is null.
     */
    private void rollCommands(List<CommandEntry> table, String playerName) {
        if (table.isEmpty()) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (CommandEntry entry : table) {
            if (rng.nextDouble() >= entry.chance()) continue;

            String command = entry.command();
            if (command.contains("%player%")) {
                if (playerName == null) continue;
                command = command.replace("%player%", playerName);
            }

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (Exception e) {
                plugin.getLogger().warning("[KillCommands] Failed to run '" + command + "': " + e.getMessage());
            }
        }
    }

    /**
     * Rolls a configurable broadcast and sends it to the whole server. Skipped when the message uses
     * {@code %player%} but there is no player killer, or the message is blank.
     */
    private void rollBroadcast(boolean enabled, double chance, String message, String playerName) {
        if (!enabled || message == null || message.isEmpty()) return;
        if (message.contains("%player%") && playerName == null) return;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;

        String text = ChatColor.translateAlternateColorCodes('&',
                playerName != null ? message.replace("%player%", playerName) : message);
        Bukkit.broadcastMessage(text);
    }

    /**
     * Rolls the configured custom drops for a slain zombie and appends any winners to the death drops
     * (so they flow through vanilla / other-plugin drop handling). The blood-moon table is used when a
     * blood moon is active in the zombie's world, otherwise the normal table.
     */
    public void applyDrops(EntityDeathEvent event, Zombie zombie) {
        if (!enabled) return;
        if (!plugin.isWorldEnabled(zombie.getWorld()) || plugin.isLobbyWorld(zombie.getWorld())) return;
        if (requirePlayerKill && zombie.getKiller() == null) return;

        boolean bloodMoon = plugin.isBloodMoonActive(zombie.getWorld());
        rollDrops(bloodMoon ? bloodMoonDrops : normalDrops, event);
    }

    /**
     * Rolls the configured kill-commands and optional broadcast for a slain zombie. The blood-moon
     * table is used when a blood moon is active in the zombie's world, otherwise the normal table.
     * {@code %player%} is replaced with the killer's name.
     */
    public void runKillCommands(Zombie zombie) {
        if (!commandsEnabled) return;
        if (!plugin.isWorldEnabled(zombie.getWorld()) || plugin.isLobbyWorld(zombie.getWorld())) return;

        Player killer = zombie.getKiller();
        if (commandsRequirePlayerKill && killer == null) return;

        String playerName = killer != null ? killer.getName() : null;
        boolean bloodMoon = plugin.isBloodMoonActive(zombie.getWorld());
        rollCommands(bloodMoon ? bloodMoonCommands : normalCommands, playerName);
        rollBroadcast(zombieBroadcastEnabled, zombieBroadcastChance, zombieBroadcastMessage, playerName);
    }

}
