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
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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

    private static final int ADMIN_SPAWN_ATTEMPTS_PER_TICK = 10;
    private static final int ADMIN_SPAWN_PROBES_PER_ZOMBIE = 5;
    private static final int ADMIN_SPAWN_MAX_TOTAL_PROBES = 1_500;
    private static final int MUTANT_ATTEMPT_COST = ADMIN_SPAWN_ATTEMPTS_PER_TICK;

    private final xApocalypse plugin;
    private final MessageManager messageManager;
    private final BloodMoonManager bloodMoon;
    private final MythicMobsManager mythicMobsManager;
    private final UndeadSpawner undeadSpawner;
    private final xApocalypseUtils utils;
    private final Deque<AdminSpawnJob> adminSpawnJobs = new ArrayDeque<>();
    private final Set<UUID> playersWithAdminSpawnJob = new HashSet<>();
    private BukkitTask adminSpawnTask;

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

        int maxZombies = Math.max(0,
                plugin.getConfig().getInt("performance.max-total-zombies", 300));
        count = Math.min(count, maxZombies);
        radius = Math.min(radius, 50); // Keep radius reasonable

        // --- MythicMobs: MUTANT type ---
        if (typeArg.equals("MUTANT")) {
            if (mythicMobsManager == null || !mythicMobsManager.isMythicMobsEnabled()) {
                sender.sendMessage("§c[MythicMobs] MythicMobs is not available on this server.");
                return true;
            }
            startBatchedAdminSpawn(player, null, count, radius, true);
            return true;
        }

        int remainingCapacity = Math.max(0,
                maxZombies - player.getWorld().getEntitiesByClass(Zombie.class).size());
        count = Math.min(count, remainingCapacity);

        if (typeArg.equals("HORDE")) {
            startBatchedAdminSpawn(player, null, count, radius, false);
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

        startBatchedAdminSpawn(player, type, count, radius, false);
        return true;
    }

    private void startBatchedAdminSpawn(
            Player player, xApocalypseUtils.ZombieType type, int requested, int radius,
            boolean mutant) {
        if (requested <= 0) {
            player.sendMessage("§eZombie cap reached; nothing was spawned.");
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!playersWithAdminSpawnJob.add(playerId)) {
            player.sendMessage("§eYou already have an active /xa spawn job. Let it finish first.");
            return;
        }

        adminSpawnJobs.addLast(new AdminSpawnJob(player, type, requested, radius, mutant));
        ensureAdminSpawnTask();
        String label = mutant ? "Mutants" : "zombies";
        player.sendMessage("§aQueued " + requested + " " + label
                + " under the shared safe spawn budget.");
    }

    private void ensureAdminSpawnTask() {
        if (adminSpawnTask != null && !adminSpawnTask.isCancelled()) return;
        adminSpawnTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::runAdminSpawnTick, 1L, 1L);
    }

    /**
     * One shared scheduler owns every admin spawn request. Ten ordinary terrain probes total may
     * run in a server tick, regardless of how many admins queue commands. A Mutant placement costs
     * the whole tick budget, so MythicMobs can never receive a multi-boss command burst.
     */
    void runAdminSpawnTick() {
        if (!plugin.isEnabled()) {
            cancelAdminSpawnJobs();
            return;
        }

        int budget = ADMIN_SPAWN_ATTEMPTS_PER_TICK;
        int unaffordableRotations = 0;
        while (budget > 0 && !adminSpawnJobs.isEmpty()) {
            AdminSpawnJob job = adminSpawnJobs.removeFirst();
            Player player = Bukkit.getPlayer(job.playerId);
            if (player == null || !player.isOnline()) {
                playersWithAdminSpawnJob.remove(job.playerId);
                unaffordableRotations = 0;
                continue;
            }

            int cost = job.mutant ? MUTANT_ATTEMPT_COST : 1;
            if (cost > budget) {
                adminSpawnJobs.addLast(job);
                unaffordableRotations++;
                if (unaffordableRotations >= adminSpawnJobs.size()) break;
                continue;
            }

            unaffordableRotations = 0;
            budget -= cost;
            job.runAttempt(player);
            if (job.hasWorkRemaining()) {
                adminSpawnJobs.addLast(job);
            } else {
                playersWithAdminSpawnJob.remove(job.playerId);
                job.sendCompletion(player);
            }
        }

        if (adminSpawnJobs.isEmpty()) stopAdminSpawnTask();
    }

    void cancelAdminSpawnJobs() {
        stopAdminSpawnTask();
        adminSpawnJobs.clear();
        playersWithAdminSpawnJob.clear();
    }

    private void stopAdminSpawnTask() {
        if (adminSpawnTask != null) {
            adminSpawnTask.cancel();
            adminSpawnTask = null;
        }
    }

    private final class AdminSpawnJob {
        private final UUID playerId;
        private final xApocalypseUtils.ZombieType type;
        private final int requested;
        private final int radius;
        private final boolean mutant;
        private int remaining;
        private int probesRemaining;
        private int spawned;

        private AdminSpawnJob(
                Player player, xApocalypseUtils.ZombieType type, int requested, int radius,
                boolean mutant) {
            this.playerId = player.getUniqueId();
            this.type = type;
            this.requested = requested;
            this.remaining = requested;
            this.probesRemaining = (int) Math.min(
                    ADMIN_SPAWN_MAX_TOTAL_PROBES,
                    (long) requested * ADMIN_SPAWN_PROBES_PER_ZOMBIE);
            this.radius = radius;
            this.mutant = mutant;
        }

        private void runAttempt(Player player) {
            if (remaining <= 0 || probesRemaining <= 0) return;
            probesRemaining--;

            if (mutant) {
                MythicMobsManager.CommandSpawnResult result =
                        mythicMobsManager.trySpawnMutantCommand(player, radius);
                if (result == MythicMobsManager.CommandSpawnResult.STOP) {
                    probesRemaining = 0;
                } else if (result == MythicMobsManager.CommandSpawnResult.SPAWNED) {
                    remaining--;
                    spawned++;
                }
                return;
            }

            int maxZombies = Math.max(0,
                    plugin.getConfig().getInt("performance.max-total-zombies", 300));
            int capacity = Math.max(0,
                    maxZombies - player.getWorld().getEntitiesByClass(Zombie.class).size());
            if (capacity <= 0) {
                probesRemaining = 0;
                return;
            }

            Location center = player.getLocation();
            Location candidate = center.clone().add(
                    ThreadLocalRandom.current().nextDouble(-radius, radius), 0,
                    ThreadLocalRandom.current().nextDouble(-radius, radius));
            Location surface = undeadSpawner.getSurfaceSpawnLocation(candidate);
            if (surface == null) return;

            Zombie zombie;
            plugin.setPluginSpawning(true);
            try {
                zombie = (Zombie) player.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
            } finally {
                plugin.setPluginSpawning(false);
            }
            if (zombie == null) return;
            if (type == null) utils.assignZombieType(zombie);
            else utils.applyZombieType(zombie, type);
            remaining--;
            spawned++;
        }

        private boolean hasWorkRemaining() {
            return remaining > 0 && probesRemaining > 0;
        }

        private void sendCompletion(Player player) {
            String label = mutant
                    ? mythicMobsManager.getMobType() + " Mutants"
                    : type == null ? "horde zombies" : type.name() + " zombies";
            player.sendMessage("§aSpawned " + spawned + "/" + requested + " " + label + "!");
        }
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
