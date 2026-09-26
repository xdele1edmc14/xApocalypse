package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single {@link CommandExecutor} for xApocalypse. Everything is now exposed under one root
 * command — {@code /xapocalypse} with the aliases {@code /xa} and {@code /zombie} — and dispatched
 * by sub-command:
 *
 * <pre>
 *   /xa help                                      show the command list
 *   /xa reload                                    reload config + language
 *   /xa item &lt;item&gt; [player] [amount]             give special items
 *   /xa spawn &lt;type|horde&gt; [count] [radius]        spawn zombies
 *   /xa forcebloodmoon [minutes]   (alias fbm)    force a blood moon
 *   /xa stopbloodmoon              (alias sbm)    end a blood moon
 * </pre>
 *
 * All permissions, argument parsing and bug-fixes (Bug M4 surface-snap, gate-bypass, Bug M5
 * enabled-world resolution) are preserved verbatim from the original per-command implementation —
 * only the dispatch layer changed from {@code command.getName()} to {@code args[0]}.
 */
public class xApocalypseCommand implements CommandExecutor {

    private final xApocalypse plugin;
    private final MessageManager messageManager;
    private final BloodMoonManager bloodMoon;
    private final MythicMobsManager mythicMobsManager;
    private final UndeadSpawner undeadSpawner;
    private final xApocalypseUtils utils;

    public xApocalypseCommand(xApocalypse plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessages();
        this.bloodMoon = plugin.getBloodMoon();
        this.mythicMobsManager = plugin.getMythicMobsManager();
        this.undeadSpawner = plugin.getUndeadSpawner();
        this.utils = plugin.getUtils();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Bare /xa (no sub-command) shows the help screen.
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        // Strip the sub-command token so each handler sees the same argument layout it had back
        // when these were separate top-level commands (e.g. "/xa item zombie_guts" -> ["zombie_guts"]).
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "help":
                sendHelp(sender);
                return true;
            case "reload":
                return handleReload(sender);
            case "item":
                return handleItem(sender, subArgs);
            case "spawn":
                return handleSpawn(sender, subArgs);
            case "forcebloodmoon":
            case "fbm":
                return handleForceBloodMoon(sender, subArgs);
            case "stopbloodmoon":
            case "sbm":
                return handleStopBloodMoon(sender);
            default:
                sender.sendMessage(messageManager.getWithPrefix("commands.unknown", sub));
                return true;
        }
    }

    // ==================================================================================
    // SUB-COMMAND HANDLERS
    // ==================================================================================

    /** {@code /xa reload} */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("xapocalypse.admin")) {
            sender.sendMessage(messageManager.get("no-permission"));
            return true;
        }

        try {
            plugin.reloadAll();
            sender.sendMessage(messageManager.getWithPrefix("reload-success"));
        } catch (Exception e) {
            sender.sendMessage(messageManager.getWithPrefix("reload-error", e.getMessage()));
        }
        return true;
    }

    /** {@code /xa item <item> [player] [amount]} */
    private boolean handleItem(CommandSender sender, String[] args) {
        if (!sender.hasPermission("xapocalypse.admin")) {
            sender.sendMessage(messageManager.get("no-permission"));
            return true;
        }

        Player targetPlayer;

        // Run from console an explicit target player is required: /xa item <item> <player>
        if (!(sender instanceof Player)) {
            if (args.length >= 2) {
                targetPlayer = Bukkit.getPlayer(args[1]);
                if (targetPlayer == null) {
                    sender.sendMessage(messageManager.getWithPrefix("player-not-found", args[1]));
                    return true;
                }
            } else {
                sender.sendMessage(messageManager.getWithPrefix("commands.item.usage"));
                return true;
            }
        } else if (args.length >= 2) {
            targetPlayer = Bukkit.getPlayer(args[1]);
            if (targetPlayer == null) {
                sender.sendMessage(messageManager.getWithPrefix("player-not-found", args[1]));
                return true;
            }
        } else {
            targetPlayer = (Player) sender;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("zombie_guts") && plugin.isZombieGutsEnabled()) {
            int amount = parseItemAmount(args);
            ItemStack guts = plugin.getImmunity().createZombieGutsItem(amount);
            if (!targetPlayer.getInventory().addItem(guts).isEmpty()) {
                sender.sendMessage("§c" + targetPlayer.getName() + "'s inventory is full.");
                return true;
            }

            if (sender instanceof Player) {
                targetPlayer.sendMessage(messageManager.getWithPrefix("commands.item.received", messageManager.get("immunity.item-name")));
            } else {
                sender.sendMessage(messageManager.getWithPrefix("commands.item.given", targetPlayer.getName(), messageManager.get("immunity.item-name")));
            }
            return true;
        }
        sender.sendMessage(messageManager.getWithPrefix("commands.item.unknown", args.length > 0 ? args[0] : "none"));
        return true;
    }

    static int parseItemAmount(String[] args) {
        if (args.length < 3) {
            return 1;
        }

        try {
            return Math.clamp(Integer.parseInt(args[2]), 1, 64);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /** {@code /xa forcebloodmoon [minutes]} (alias {@code fbm}) */
    private boolean handleForceBloodMoon(CommandSender sender, String[] args) {
        if (!sender.hasPermission("xapocalypse.admin")) {
            sender.sendMessage(messageManager.get("no-permission"));
            return true;
        }

        World world = bloodMoon.getReferenceWorld();
        if (world == null) {
            sender.sendMessage("§cNo enabled Blood Moon reference world is currently loaded.");
            return true;
        }

        // CRITICAL FIX: Parse duration argument
        int duration = bloodMoon.getDefaultForceDuration(); // Default from config
        if (args.length >= 1) {
            try {
                duration = Integer.parseInt(args[0]);
                if (duration < 1) {
                    sender.sendMessage("§cDuration must be at least 1 minute.");
                    return true;
                }
                if (duration > 120) {
                    sender.sendMessage("§cDuration cannot exceed 120 minutes.");
                    return true;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid duration. Usage: /xa forcebloodmoon [minutes]");
                return true;
            }
        }

        bloodMoon.forceBloodMoon(sender, world, duration);
        return true;
    }

    /** {@code /xa stopbloodmoon} (alias {@code sbm}) */
    private boolean handleStopBloodMoon(CommandSender sender) {
        if (!sender.hasPermission("xapocalypse.admin")) {
            sender.sendMessage(messageManager.get("no-permission"));
            return true;
        }

        bloodMoon.stopBloodMoon(sender);
        return true;
    }

    /** {@code /xa spawn <type|horde> [count] [radius]} */
    private boolean handleSpawn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("xapocalypse.command.spawn")) {
            sender.sendMessage("§cNo permission."); return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("§cUsage: /xa spawn <type|horde> [count] [radius]");
            return true;
        }

        String typeArg = args[0].toUpperCase();
        int count = 1;
        int radius = 5;

        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid count number.");
                return true;
            }
        }

        if (args.length >= 3) {
            try {
                radius = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid radius number.");
                return true;
            }
        }

        if (count < 1) {
            sender.sendMessage("§cCount must be at least 1.");
            return true;
        }
        if (radius < 1) {
            sender.sendMessage("§cRadius must be at least 1 block.");
            return true;
        }

        count = Math.min(count, plugin.getConfig().getInt("performance.max-total-zombies", 300)); // Use config value
        radius = Math.min(radius, 50); // Keep radius reasonable

        // --- MythicMobs: MUTANT type ---
        if (typeArg.equals("MUTANT")) {
            if (mythicMobsManager == null || !mythicMobsManager.isMythicMobsEnabled()) {
                sender.sendMessage("§c[MythicMobs] MythicMobs is not available on this server.");
                return true;
            }
            int spawned = mythicMobsManager.spawnMutantCommand(player, count, radius);
            sender.sendMessage("§aSpawned §c" + spawned + " §a" + mythicMobsManager.getMobType()
                    + "§a! (Active: §c" + mythicMobsManager.getActiveMutantCount()
                    + "§a/§c" + mythicMobsManager.getMaxGlobalCap() + "§a)");
            return true;
        }

        if (typeArg.equals("HORDE")) {
            // Spawn mixed horde
            int hordeSpawned = 0;
            for (int i = 0; i < count; i++) {
                Location spawnLoc = player.getLocation().add(
                        ThreadLocalRandom.current().nextDouble(-radius, radius),
                        0,
                        ThreadLocalRandom.current().nextDouble(-radius, radius)
                );

                // Bug fix: do NOT skip claims here. The natural horde spawner ignores
                // GriefPrevention claims entirely (zombies spawn inside claims normally), so
                // gating the admin /xa spawn command on claims was inconsistent — and at a
                // claimed hub/main-world spawn it skipped every attempt ("horde of 0").
                // Bug M4 fix: snap to a valid surface instead of spawning at the player's raw
                // Y. On non-flat terrain the old code buried/suffocated zombies inside hills or
                // dropped them into the void. Skip the slot if no valid surface is found.
                Location surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);
                if (surface == null) continue;

                // Bug fix: admin /xa spawn must bypass the onEntitySpawn mob-list gate (same
                // rationale as C1). Without the flag, an enabled world running a whitelist
                // (or a blacklist that lists ZOMBIE) silently cancels the spawn — which is why
                // /xa spawn worked in non-enabled worlds (nether/end) but not the main world.
                // Because the gate is bypassed, onEntitySpawn no longer assigns a type, so we
                // assign it here ourselves.
                Zombie zombie;
                plugin.setPluginSpawning(true);
                try {
                    zombie = (Zombie) player.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
                } finally {
                    plugin.setPluginSpawning(false);
                }
                if (zombie != null) {
                    utils.assignZombieType(zombie);
                    hordeSpawned++;
                }
            }
            sender.sendMessage("§aSpawned horde of " + hordeSpawned + " zombies!");
            return true;
        }

        // Specific type
        xApocalypseUtils.ZombieType type;
        try {
            type = xApocalypseUtils.ZombieType.valueOf(typeArg);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid zombie type. Valid types: " + Arrays.toString(xApocalypseUtils.ZombieType.values()));
            return true;
        }

        int spawnedCount = 0;
        for (int i = 0; i < count; i++) {
            Location spawnLoc = player.getLocation().add(
                    ThreadLocalRandom.current().nextDouble(-radius, radius),
                    0,
                    ThreadLocalRandom.current().nextDouble(-radius, radius)
            );

            // Bug fix: snap to a valid surface (parity with the HORDE branch above) so specific-type
            // spawns don't suffocate inside terrain or drop into the void at the player's raw Y.
            Location surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);
            if (surface == null) continue;

            // Bug fix: bypass the onEntitySpawn mob-list gate for admin spawns, then apply the
            // requested type directly.
            Zombie zombie;
            plugin.setPluginSpawning(true);
            try {
                zombie = (Zombie) player.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
            } finally {
                plugin.setPluginSpawning(false);
            }
            if (zombie != null) {
                utils.applyZombieType(zombie, type);
                spawnedCount++;
            }
        }

        sender.sendMessage("§aSpawned " + spawnedCount + " " + type.name() + " zombies!");
        return true;
    }

    // ==================================================================================
    // HELP
    // ==================================================================================

    /** {@code /xa help} — permission-aware: only shows lines the sender can actually run. */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(messageManager.get("commands.help.title"));
        sender.sendMessage(messageManager.get("commands.help.author"));
        sender.sendMessage(messageManager.get("commands.help.help"));

        if (sender.hasPermission("xapocalypse.command.spawn")) {
            sender.sendMessage(messageManager.get("commands.help.spawn"));
        }
        if (sender.hasPermission("xapocalypse.admin")) {
            sender.sendMessage(messageManager.get("commands.help.item"));
            sender.sendMessage(messageManager.get("commands.help.forcebloodmoon"));
            sender.sendMessage(messageManager.get("commands.help.stopbloodmoon"));
            sender.sendMessage(messageManager.get("commands.help.reload"));
        }

        sender.sendMessage(messageManager.get("commands.help.footer"));
    }
}
