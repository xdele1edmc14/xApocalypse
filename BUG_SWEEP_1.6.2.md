# xApocalypse 1.6.2 exhaustive bug sweep

Scope: all 5,796 lines in `src/main/java`, all three resources, all four test classes, every event listener, scheduled task, persistence path, command path, optional hook, and cross-manager caller. The review assumes 200 concurrent players. There is no database code in this version.

Findings: **13 major**, **15 moderate**, **11 minor**, and **4 extra/optional** (**43 total**).

Validation evidence:

- Plain `mvn clean test` compiles production and test code, then reports 12 Mockito initialization errors on Java 25 because Byte Buddy is not configured as a Java agent.
- `mvn test -DargLine=-javaagent:/home/xdele1ed/.m2/repository/net/bytebuddy/byte-buddy-agent/1.17.7/byte-buddy-agent-1.17.7.jar` passes all 16 tests.
- The existing tests do not cover Blood Moon lifecycle, persistence, reloads, MythicMobs, immunity, Nurse healing, horde scheduling, performance caps, drops, or entity death/removal.
- No production source was changed during this audit. Pre-existing `.gitignore` and `LICENSE` edits were preserved.

## Execution trace inventory

All Bukkit-facing code is currently scheduled synchronously. No database driver, query, connection, statement, transaction, or database configuration exists in this source tree.

| Entry point | Cross-subsystem path |
|---|---|
| `PlayerMoveEvent` | Listener -> `ScentManager.onMove` -> jump/scent maps |
| `EntityDeathEvent` | Listener -> Mythic identity/cap cleanup -> Burster cleanup -> immunity/custom drops/commands -> scent -> delayed Veteran promotion |
| `PlayerJoinEvent` / `PlayerQuitEvent` | Listener -> Blood Moon bars/warnings -> immunity restore/task/bar state -> scent cleanup -> persistence |
| `CreatureSpawnEvent` | Listener -> plugin-spawn bypass -> Mythic identity -> mob-list policy -> class/PDC/attributes |
| `EntityTargetLivingEntityEvent` | Listener -> immunity cancellation -> Burster fuse |
| `EntityDamageEvent` / `EntityDamageByEntityEvent` / `EntityCombustEvent` | Listener -> class identity -> fire policy or class-specific secondary effects |
| `PlayerInteractEvent` / `PlayerItemConsumeEvent` | Listener -> Zombie Guts identity -> immunity state/health/bar/task/persistence -> inventory consumption |
| `ProjectileHitEvent` | Listener -> acid PDC -> Spitter damage/poison/effects |
| `BlockBreakEvent` / `BlockPlaceEvent` | Listener -> temporary Webber block tracking |
| `ChunkLoadEvent` | Listener -> deferred tagged zombie/Mutant cleanup |
| Horde scheduler | `HordeSpawnerTask` -> TPS gate -> player/world filters -> `HordeManager` -> terrain/claim checks -> `UndeadSpawner`/class assignment |
| AI scheduler, every 10 ticks | `HordeManager` -> `PerformanceWatchdog` LOD -> `xApocalypseUtils.tickZombieAI` -> Nurse/Spitter/Miner/Burster/class logic |
| Blood Moon scheduler, every 20 ticks | warnings/state transitions/time/bar/persistence -> Mythic start/end -> tagged-entity cleanup |
| Immunity schedulers | one-second expiry scan + five-tick boss-bar update + per-player delayed expiry |
| Scent schedulers | configured decay loop + one-second sprint accrual |
| Performance scheduler | configured TPS sampling -> spawn pause/resume -> cap count/cull |
| Mythic scheduler | Blood Moon state -> player chance rolls -> placement/claims/visibility -> Mythic spawn/cap |
| Rise-animation tasks | delayed start -> per-tick teleport/particles/sound -> AI/gravity/invulnerability restoration |
| Webber/Burster/Warning/Veteran delayed tasks | block restoration, per-tick fuse/explosion, delayed join warning, and one-tick Veteran promotion |

## Major bugs

- **[🔴 MAJOR] Async PlaceholderAPI access to Bukkit state and unsafe collections / `xApocalypsePlaceholderExpansion.java:39-46`**
- **The Flaw:** PlaceholderAPI executes an expansion on the requesting plugin's thread. Async scoreboard and tab-list plugins can therefore call `onRequest` off the server thread. `bloodmoon_days_left` reads Bukkit worlds and world time, while scent and immunity read ordinary `HashMap` and `LinkedHashSet` instances concurrently with main-thread writes. This is an unsafe asynchronous Bukkit call plus a Java collection data race.
- **The Fix:** Cache the Blood Moon value on the main thread and use concurrent maps for values read by placeholders. Replace the expansion with the full class below.

```java
public final class xApocalypsePlaceholderExpansion extends PlaceholderExpansion {
    private final xApocalypse plugin;

    public xApocalypsePlaceholderExpansion(xApocalypse plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "xapocalypse";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        UUID uuid = player == null ? null : player.getUniqueId();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "bloodmoon_days_left" -> formatBloodMoonDays(
                    plugin.getBloodMoon().getCachedDaysUntilNextBloodMoon());
            case "zombie_guts_duration" -> uuid == null ? "0" : Long.toString(
                    plugin.getImmunity().getRemainingSeconds(uuid));
            case "current_scent" -> uuid == null ? "0" : formatScent(
                    plugin.getPlayerScent(uuid));
            default -> null;
        };
    }

    private String formatBloodMoonDays(int days) {
        return days == 0 ? "Tonight" : Integer.toString(days);
    }

    private String formatScent(double scent) {
        if (!Double.isFinite(scent) || scent <= 0.0) return "0";
        return Long.toString(Math.round(scent));
    }
}
```

In `ScentManager` and `ImmunityManager`, replace the placeholder-visible collections with:

```java
private final ConcurrentMap<UUID, Double> playerScent = new ConcurrentHashMap<>();

private final Set<UUID> immunePlayers = ConcurrentHashMap.newKeySet();
private final ConcurrentMap<UUID, Long> immunityEndTime = new ConcurrentHashMap<>();
```

In `BloodMoonManager`, add `private volatile int cachedDaysUntilNextBloodMoon;`, refresh it from the existing synchronous one-second task, and expose a getter that returns only the cached primitive.

- **[🔴 MAJOR] Blocking YAML persistence on gameplay and lifecycle ticks / `ImmunityManager.java:148-171, 296-299, 367-368, 511-512`; `BloodMoonManager.java:210-231`**
- **The Flaw:** `YamlConfiguration.save(File)` performs blocking filesystem I/O on the server thread. Immunity grants, expiries, joins, disconnects, Blood Moon warnings, state transitions, and commands can all write synchronously. A mass disconnect or simultaneous immunity expiry can perform many complete file rewrites in one tick. Direct writes can also truncate the only state file if the process stops mid-write.
- **The Fix:** Serialize an immutable YAML payload on the main thread, queue writes through one ordered executor, and atomically replace the target. Use the following complete writer class for both managers.

```java
final class AtomicYamlWriter implements AutoCloseable {
    private final Logger logger;
    private final ExecutorService executor;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    AtomicYamlWriter(Logger logger, String threadName) {
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name(threadName).factory());
    }

    synchronized void submit(Path target, String payload) {
        tail = tail.handle((ignored, failure) -> {
            if (failure != null) logger.log(Level.SEVERE, "Previous YAML save failed", failure);
            return null;
        }).thenRunAsync(() -> writeAtomically(target, payload), executor);
    }

    private void writeAtomically(Path target, String payload) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, payload, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    @Override
    public synchronized void close() {
        executor.shutdown();
        try {
            tail.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Could not flush YAML persistence", exception);
        } finally {
            executor.shutdownNow();
        }
    }
}
```

Each manager's `save()` must update its in-memory `YamlConfiguration`, call `saveToString()`, then submit that string. `onDisable()` must submit the final snapshot and close both writers. Remove the redundant `immunity.save()` from `PlayerQuitEvent`; quitting already removes transient tasks and boss bars without changing durable immunity data.

- **[🔴 MAJOR] Natural horde spawn storm and wasted surface work / `HordeManager.java:77-188`; `UndeadSpawner.java:53-54`**
- **The Flaw:** The animation capacity check occurs inside `trySpawnUndeadRise`, after surface heightmap queries, claim checks, and block-data reads. Once 50 rise animations are active, later players still perform every placement probe while spawning nothing. With 200 eligible players and a 30-zombie horde cap this can produce roughly 11,640 wasted synchronous probes in one scheduler tick. With animation disabled, the same path may create hundreds of entities in one tick.
- **The Fix:** Apply one global ten-attempt budget before any terrain work, bound the request by available animation slots, and randomize player order in `HordeSpawnerTask` so the same players do not monopolize the budget. Add the fields/helper to `HordeManager`, add the capacity getter to `UndeadSpawner`, and replace the complete horde method below.

```java
// HordeManager fields and helper
private static final int MAX_HORDE_SPAWN_ATTEMPTS_PER_TICK = 10;
private long spawnBudgetTick = Long.MIN_VALUE;
private int spawnBudgetRemaining = MAX_HORDE_SPAWN_ATTEMPTS_PER_TICK;

private int claimSpawnBudget(int requested) {
    long currentTick = Bukkit.getServer().getCurrentTick();
    if (currentTick != spawnBudgetTick) {
        spawnBudgetTick = currentTick;
        spawnBudgetRemaining = MAX_HORDE_SPAWN_ATTEMPTS_PER_TICK;
    }

    int accepted = Math.min(Math.max(0, requested), spawnBudgetRemaining);
    spawnBudgetRemaining -= accepted;
    return accepted;
}
```

```java
// UndeadSpawner
public int getAvailableAnimationSlots() {
    return Math.max(
            0,
            MAX_CONCURRENT_ANIMATIONS - activeAnimationEntities.size());
}
```

```java
public void spawnZombiesNearPlayer(Player player, boolean isDayHordeSpawn) {
    if (player.getGameMode() != GameMode.SURVIVAL
            || player.isGliding()
            || player.isFlying()) {
        return;
    }

    int baseAmount = Math.max(0, plugin.getConfig().getInt(
            "apocalypse-settings.base-horde-size", 6));
    int variance = Math.max(0, plugin.getConfig().getInt(
            "apocalypse-settings.horde-variance", 4));
    if (isDayHordeSpawn) {
        baseAmount = Math.max(0, plugin.getConfig().getInt(
                "apocalypse-settings.day-horde-size",
                Math.max(1, baseAmount / 3)));
        variance = Math.max(0, plugin.getConfig().getInt(
                "apocalypse-settings.day-horde-variance",
                Math.max(0, variance / 2)));
    }

    World world = player.getWorld();
    double multiplier = 1.0;
    if (plugin.isBloodMoonActive(world)) {
        double configured = plugin.getBloodMoonHordeMultiplier();
        multiplier = Double.isFinite(configured) ? Math.max(0.0, configured) : 1.0;
    }

    if (plugin.getConfig().getBoolean("scent-system.enabled", true)) {
        double scent = plugin.getPlayerScent(player.getUniqueId());
        if (!Double.isFinite(scent) || scent < 0.0) scent = 0.0;
        double scentScale = plugin.getConfig().getDouble(
                "scent-system.scent-scale", 15.0);
        if (!Double.isFinite(scentScale) || scentScale <= 0.0) scentScale = 15.0;
        multiplier *= 1.0 + scent / scentScale;
    }

    int rolled = baseAmount + ThreadLocalRandom.current().nextInt(variance + 1);
    double scaled = rolled * multiplier;
    int wanted = scaled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    wanted = Math.min(wanted, Math.max(0, plugin.getConfig().getInt(
            "apocalypse-settings.max-single-horde-size", 30)));

    PerformanceWatchdog watchdog = plugin.getPerformanceWatchdog();
    int existing = watchdog == null
            ? world.getEntitiesByClass(Zombie.class).size()
            : watchdog.countManagedZombiesInWorld(world);
    int cap = Math.max(0, plugin.getConfig().getInt(
            "performance.max-total-zombies", 300));
    wanted = Math.min(wanted, Math.max(0, cap - existing));

    boolean rising = plugin.getConfig().getBoolean(
            "apocalypse-settings.rising-animation", true);
    if (rising) {
        wanted = Math.min(wanted, undeadSpawner.getAvailableAnimationSlots());
    }
    int attempts = claimSpawnBudget(wanted);
    if (attempts <= 0) return;

    int radius = Math.max(1, plugin.getConfig().getInt(
            "apocalypse-settings.spawn-radius", 35));
    Location center = player.getLocation();
    int spawned = 0;

    for (int i = 0; i < attempts; i++) {
        if (rising && undeadSpawner.getAvailableAnimationSlots() <= 0) break;

        Location surface = null;
        for (int placementAttempt = 0; placementAttempt < 2; placementAttempt++) {
            Location candidate = center.clone().add(
                    ThreadLocalRandom.current().nextDouble(-radius, radius),
                    0.0,
                    ThreadLocalRandom.current().nextDouble(-radius, radius));
            Location found = undeadSpawner.getSurfaceSpawnLocation(candidate);
            if (found != null && !plugin.isInsideClaim(found)) {
                surface = found;
                break;
            }
        }
        if (surface == null) continue;

        if (rising) {
            Block surfaceBlock = surface.getBlock().getRelative(BlockFace.DOWN);
            BlockData surfaceData = surfaceBlock.getBlockData();
            if (undeadSpawner.trySpawnUndeadRise(
                    surface,
                    surfaceBlock,
                    surfaceData,
                    i % 5L) != null) {
                spawned++;
            }
        } else {
            Zombie zombie;
            isPluginSpawning = true;
            try {
                zombie = (Zombie) world.spawnEntity(surface, EntityType.ZOMBIE);
            } finally {
                isPluginSpawning = false;
            }
            utils.assignZombieType(zombie);
            spawned++;
        }
    }

    plugin.debugLog("Spawned " + spawned + "/" + attempts
            + " budgeted zombies near " + player.getName() + ".");
}
```

In `HordeSpawnerTask.run()`, copy the eligible players to an `ArrayList`, call `Collections.shuffle(players)`, and iterate that randomized list.

- **[🔴 MAJOR] Ordinary zombie placement can synchronously load or generate chunks / `UndeadSpawner.java:220-255`**
- **The Flaw:** Both natural hordes and `/xa spawn` call `getHighestBlockYAt` or `getBlockAt` without first proving that the random candidate chunk is already loaded. Candidates near a player's view-distance edge can therefore synchronously load or generate terrain on the main thread. A large horde or command burst multiplies that cost.
- **The Fix:** Reject unloaded chunks before every heightmap or block lookup. Replace the complete method with:

```java
Location getSurfaceSpawnLocation(Location target) {
    if (target == null) return null;
    World world = target.getWorld();
    if (world == null) return null;

    int x = target.getBlockX();
    int z = target.getBlockZ();
    if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;

    if (world.getEnvironment() != World.Environment.NETHER) {
        int surfaceY = world.getHighestBlockYAt(
                x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (surfaceY < world.getMinHeight()
                || surfaceY >= world.getMaxHeight()) {
            return null;
        }

        Block surface = world.getBlockAt(x, surfaceY, z);
        if (!isValidSurface(surface)) return null;
        return surface.getLocation().add(0.5, 1.0, 0.5);
    }

    int targetY = target.getBlockY();
    int topY = Math.min(
            world.getMaxHeight() - 1,
            targetY + VERTICAL_SEARCH_RADIUS);
    int floorY = Math.max(
            world.getMinHeight(),
            targetY - VERTICAL_SEARCH_RADIUS);

    for (int y = topY; y >= floorY; y--) {
        Block candidate = world.getBlockAt(x, y, z);
        if (isValidSurface(candidate)) {
            return candidate.getLocation().add(0.5, 1.0, 0.5);
        }
    }
    return null;
}
```

- **[🔴 MAJOR] Repeating horde task swallows fatal JVM errors / `HordeSpawnerTask.java:20-83`**
- **The Flaw:** The outer `catch (Throwable)` intercepts `OutOfMemoryError`, `StackOverflowError`, `ThreadDeath`, and linkage failures along with recoverable exceptions. Continuing the repeating task after a JVM-level failure can compound heap corruption or memory pressure and prevent the server's normal fatal-error handling from taking control.
- **The Fix:** Catch recoverable `Exception` only and allow JVM `Error` subclasses to propagate. Replace the complete task method with:

```java
@Override
public void run() {
    try {
        plugin.debugLog("TASK: Running scheduled spawner check.");

        PerformanceWatchdog watchdog = plugin.getPerformanceWatchdog();
        if (watchdog != null && watchdog.isSpawningPaused()) {
            plugin.debugLog("TASK: Spawning paused due to low TPS, skipping.");
            return;
        }

        World targetWorld = Bukkit.getWorlds().stream()
                .filter(plugin::isWorldEnabled)
                .findFirst()
                .orElse(null);
        if (targetWorld == null) {
            plugin.debugLog("TASK: No enabled worlds loaded, skipping spawn attempt.");
            return;
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Collections.shuffle(players);
        for (Player player : players) {
            World playerWorld = player.getWorld();
            if (!plugin.isWorldEnabled(playerWorld)
                    || plugin.isLobbyWorld(playerWorld)) {
                continue;
            }
            if (player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            long time = playerWorld.getTime();
            boolean dayHorde = time >= 0L && time < 13000L;
            if (dayHorde) {
                double configuredChance = plugin.getConfig().getDouble(
                        "apocalypse-settings.day-spawn-chance", 0.0);
                double chance = Double.isFinite(configuredChance)
                        ? Math.clamp(configuredChance, 0.0, 1.0)
                        : 0.0;
                if (ThreadLocalRandom.current().nextDouble() >= chance) continue;
            }

            plugin.spawnZombiesNearPlayer(player, dayHorde);
        }
    } catch (Exception exception) {
        plugin.getLogger().log(
                java.util.logging.Level.SEVERE,
                "The repeating horde task failed this cycle.",
                exception);
    }
}
```

- **[🔴 MAJOR] O(zombies × players) LOD allocation storm / `HordeManager.java:53-72`; `PerformanceWatchdog.java:210-243`**
- **The Flaw:** Every 10 ticks, every managed zombie calls `getLocation()` for itself and every player while searching for the nearest player. At 300 zombies and 200 players this is 60,000 comparisons and about 120,000 `Location` allocations per pass, twice each second, on the server thread.
- **The Fix:** Snapshot player coordinates once per world and zombie coordinates once per zombie. Replace both methods with:

```java
public void startAITickTask() {
    new BukkitRunnable() {
        @Override
        public void run() {
            PerformanceWatchdog watchdog = plugin.getPerformanceWatchdog();
            long currentTick = Bukkit.getServer().getCurrentTick();
            for (World world : Bukkit.getWorlds()) {
                if (!plugin.isWorldEnabled(world)) continue;

                List<Location> playerLocations = world.getPlayers().stream()
                        .map(Player::getLocation)
                        .toList();
                for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                    if (watchdog == null
                            || watchdog.manageZombieAndShouldTick(
                                    zombie, playerLocations, currentTick)) {
                        utils.tickZombieAI(zombie);
                    }
                }
            }
            if (watchdog != null) watchdog.finishAITick();
        }
    }.runTaskTimer(plugin, 0L, 10L);
}
```

```java
public boolean manageZombieAndShouldTick(
        Zombie zombie, List<Location> playerLocations, long currentTick) {
    if (zombie.isDead() || !zombie.isValid()) return false;
    if (!isManagedZombie(zombie)) {
        if (zombieLastAITick.remove(zombie) != null && !zombie.hasAI()) zombie.setAI(true);
        return false;
    }
    if (zombie.getPersistentDataContainer().has(
            xApocalypseUtils.ANIMATING_KEY, PersistentDataType.BYTE)) return false;

    if (playerLocations.isEmpty()) {
        if (zombie.hasAI()) zombie.setAI(false);
        zombieLastAITick.put(zombie, currentTick);
        return false;
    }

    Location location = zombie.getLocation();
    double zx = location.getX();
    double zy = location.getY();
    double zz = location.getZ();
    double nearestSquared = Double.MAX_VALUE;
    for (Location player : playerLocations) {
        double dx = zx - player.getX();
        double dy = zy - player.getY();
        double dz = zz - player.getZ();
        nearestSquared = Math.min(nearestSquared, dx * dx + dy * dy + dz * dz);
    }

    if (nearestSquared > lodDistanceThreshold * lodDistanceThreshold) {
        if (zombie.hasAI()) zombie.setAI(false);
        Long lastTick = zombieLastAITick.get(zombie);
        if (lastTick == null || currentTick - lastTick >= lodTickInterval) {
            zombieLastAITick.put(zombie, currentTick);
            return true;
        }
        return false;
    }

    if (!zombie.hasAI()) zombie.setAI(true);
    zombieLastAITick.remove(zombie);
    return true;
}
```

- **[🔴 MAJOR] Unbounded synchronous `/xa spawn` burst and cap bypass / `xApocalypseCommand.java:217-360`**
- **The Flaw:** The command can perform 300 heightmap queries, entity spawns, event dispatches, metadata writes, attribute mutations, and equipment operations in one tick. It clamps the requested count to the configured maximum without subtracting living zombies, so repeated commands exceed the cap indefinitely.
- **The Fix:** Calculate remaining capacity and schedule at most 10 creations per tick.

```java
private static final int COMMAND_SPAWNS_PER_TICK = 10;
private long commandBudgetTick = Long.MIN_VALUE;
private int commandBudgetRemaining = COMMAND_SPAWNS_PER_TICK;

private int claimCommandSpawnBudget(int requested) {
    long tick = Bukkit.getServer().getCurrentTick();
    if (tick != commandBudgetTick) {
        commandBudgetTick = tick;
        commandBudgetRemaining = COMMAND_SPAWNS_PER_TICK;
    }
    int granted = Math.min(Math.max(0, requested), commandBudgetRemaining);
    commandBudgetRemaining -= granted;
    return granted;
}

private int availableZombieSlots(World world) {
    int cap = Math.max(0, plugin.getConfig().getInt(
            "performance.max-total-zombies", 300));
    int existing = plugin.getPerformanceWatchdog()
            .countManagedZombiesInWorld(world);
    return Math.max(0, cap - existing);
}

private void scheduleZombieSpawns(
        Player player,
        int requested,
        int radius,
        xApocalypseUtils.ZombieType forcedType) {
    World world = player.getWorld();
    int accepted = Math.min(requested, availableZombieSlots(world));
    if (accepted <= 0) {
        player.sendMessage("§eThe zombie cap has already been reached.");
        return;
    }

    new BukkitRunnable() {
        private int attemptsRemaining = accepted;
        private int spawned;

        @Override
        public void run() {
            if (!player.isOnline() || availableZombieSlots(world) <= 0) {
                finish();
                return;
            }
            int batch = claimCommandSpawnBudget(attemptsRemaining);
            if (batch <= 0) return;
            for (int i = 0; i < batch; i++) {
                attemptsRemaining--;
                Location center = player.getLocation();
                Location candidate = center.clone().add(
                        ThreadLocalRandom.current().nextDouble(-radius, radius),
                        0.0,
                        ThreadLocalRandom.current().nextDouble(-radius, radius));
                Location surface = undeadSpawner.getSurfaceSpawnLocation(candidate);
                if (surface == null || availableZombieSlots(world) <= 0) continue;

                Zombie zombie;
                plugin.setPluginSpawning(true);
                try {
                    zombie = (Zombie) world.spawnEntity(surface, EntityType.ZOMBIE);
                } finally {
                    plugin.setPluginSpawning(false);
                }
                if (forcedType == null) utils.assignZombieType(zombie);
                else utils.applyZombieType(zombie, forcedType);
                spawned++;
            }
            if (attemptsRemaining <= 0) finish();
        }

        private void finish() {
            cancel();
            if (player.isOnline()) {
                String type = forcedType == null ? "mixed" : forcedType.name();
                player.sendMessage("§aSpawned " + spawned + " " + type + " zombies.");
            }
        }
    }.runTaskTimer(plugin, 0L, 1L);
}
```

- **[🔴 MAJOR] Mutant placement can synchronously load or generate chunks / `MythicMobsManager.java:343-359`**
- **The Flaw:** Each spawn roll tries up to 15 positions and reaches `getHighestBlockYAt` without checking whether the candidate chunk is loaded. Paper can synchronously load or generate a missing chunk on the server thread.
- **The Fix:** Add a chunk guard before every heightmap, block, claim, line-of-sight, and spawn operation.

```java
private boolean isChunkLoaded(Location location) {
    if (location == null || location.getWorld() == null) return false;
    return location.getWorld().isChunkLoaded(
            location.getBlockX() >> 4,
            location.getBlockZ() >> 4);
}

private Location findSpawnLocation(
        Location anchor, int minRadius, int maxRadius, boolean avoidClaims) {
    if (anchor == null || anchor.getWorld() == null) return null;
    World world = anchor.getWorld();
    int safeMin = Math.max(1, minRadius);
    int safeMax = Math.max(safeMin + 1, maxRadius);
    Player nearestPlayer = getNearestPlayer(anchor);
    ThreadLocalRandom rng = ThreadLocalRandom.current();

    for (int attempt = 0; attempt < 15; attempt++) {
        double angle = rng.nextDouble(0.0, Math.PI * 2.0);
        double distance = rng.nextDouble(safeMin, safeMax);
        Location unsnapped = new Location(
                world,
                anchor.getX() + Math.cos(angle) * distance,
                anchor.getY(),
                anchor.getZ() + Math.sin(angle) * distance);
        if (!isChunkLoaded(unsnapped)) continue;

        Location candidate = snapToGround(unsnapped);
        if (candidate == null) continue;
        if (avoidClaims && plugin.isInsideClaim(candidate)) continue;
        if (nearestPlayer == null
                || !hasLineOfSight(nearestPlayer, candidate)
                || attempt >= 10) return candidate;
    }
    return null;
}

private Entity spawnMythicMob(Location location, boolean bloodMoonSpawn) {
    if (!mythicMobsEnabled || mmAPI == null || !isChunkLoaded(location)) {
        return null;
    }

    Location grounded = snapToGround(location);
    if (grounded == null || !isChunkLoaded(grounded)) return null;

    try {
        Entity entity = mmAPI.spawnMythicMob(mobType, grounded);
        if (entity == null) return null;

        entity.getPersistentDataContainer().set(
                MYTHIC_ENTITY_KEY, PersistentDataType.BYTE, (byte) 1);
        if (bloodMoonSpawn) {
            entity.getPersistentDataContainer().set(
                    BLOOD_MOON_MUTANT_KEY,
                    PersistentDataType.BYTE,
                    (byte) 1);
        }

        activeMutants.add(entity.getUniqueId());
        plugin.debugLog("[MythicMobs] Spawned at " + formatLoc(grounded));
        playSpawnSound(entity.getLocation());
        return entity;
    } catch (LinkageError error) {
        disableForLinkageError("spawn '" + mobType + "'", error);
        return null;
    } catch (Exception exception) {
        log.warning("[MythicMobs] Failed to spawn " + mobType + ": " + exception);
        return null;
    }
}
```

- **[🔴 MAJOR] Mutant spawn burst is unbounded per cycle / `MythicMobsManager.java:259-300`**
- **The Flaw:** Every eligible player rolls in one tick and every success immediately performs placement and MythicMobs creation. With 200 players at the default 5 percent chance, one cycle averages ten expensive attempts. With cap zero, chance one, and interval one, it can request 200 complex entities every tick.
- **The Fix:** Clamp the interval to at least 20 ticks, shuffle successful candidates, allow at most eight placement searches, and spawn at most one Mutant per cycle.

```java
private static final int MAX_PLACEMENT_ATTEMPTS_PER_CYCLE = 8;
private static final int MAX_SPAWNS_PER_CYCLE = 1;

private void startSpawnTickLoop() {
    stopSpawnTickLoop();
    spawnTickTask = new BukkitRunnable() {
        @Override
        public void run() {
            World world = plugin.getBloodMoon().getReferenceWorld();
            if (world == null || !plugin.isBloodMoonActive(world)) return;

            pruneDeadMutants();
            if (maxGlobalCap > 0 && activeMutants.size() >= maxGlobalCap) return;

            List<Player> candidates = new ArrayList<>();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!plugin.isWorldEnabled(player.getWorld())
                        || plugin.isLobbyWorld(player.getWorld())) continue;
                if (random.nextDouble() < spawnChance) candidates.add(player);
            }
            Collections.shuffle(candidates);

            int attempts = 0;
            int spawned = 0;
            for (Player player : candidates) {
                if (attempts >= MAX_PLACEMENT_ATTEMPTS_PER_CYCLE
                        || spawned >= MAX_SPAWNS_PER_CYCLE) break;
                if (maxGlobalCap > 0 && activeMutants.size() >= maxGlobalCap) break;
                attempts++;
                Location location = findSpawnLocation(
                        player.getLocation(), spawnRadiusMin, spawnRadiusMax, true);
                if (location != null && spawnMythicMob(location, true) != null) spawned++;
            }
        }
    }.runTaskTimer(plugin, Math.max(20, spawnTickInterval),
            Math.max(20, spawnTickInterval));
}
```

- **[🔴 MAJOR] Miner block damage bypasses standard protection events / `xApocalypseUtils.java:547-560`**
- **The Flaw:** `breakNaturally()` and `setType(AIR)` are invoked directly. They do not fire `EntityChangeBlockEvent`, so WorldGuard-style or custom protection plugins cannot veto Miner griefing. The optional direct GriefPrevention lookup protects only one plugin's claims.
- **The Fix:** Fire the standard cancellable event before changing the block.

```java
private boolean tryBreak(Zombie miner, Block block) {
    if (block.getType() == Material.AIR || block.getType() == Material.BEDROCK) return false;
    if (!minerBreakables.contains(block.getType())) return false;
    if (isInsideClaim(block.getLocation())) return false;

    BlockData brokenData = block.getBlockData();
    BlockData air = Material.AIR.createBlockData();
    EntityChangeBlockEvent changeEvent = new EntityChangeBlockEvent(miner, block, air);
    Bukkit.getPluginManager().callEvent(changeEvent);
    if (changeEvent.isCancelled()) return false;

    if (minerDropItems) block.breakNaturally();
    else block.setBlockData(air, false);

    block.getWorld().playSound(
            block.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
    block.getWorld().spawnParticle(
            Particle.BLOCK,
            block.getLocation().add(0.5, 0.5, 0.5),
            10,
            brokenData);
    return true;
}
```

- **[🔴 MAJOR] `/xa reload` scans every loaded chunk twice in one tick / `xApocalypse.java:267-277`**
- **The Flaw:** Reload walks all loaded chunks and asks both cleanup systems to enumerate entities immediately. With 200 geographically separated players, this can touch thousands of chunks inside the command tick.
- **The Fix:** Snapshot chunk coordinates and process a bounded batch each tick.

```java
private record LoadedChunkReference(UUID worldId, int x, int z) {}

private void cleanupExpiredBloodMoonEntitiesInLoadedChunks() {
    if (!getConfig().getBoolean("bloodmoon.despawn-on-end", true)) return;
    World reference = bloodMoon.getReferenceWorld();
    if (reference != null && isBloodMoonActive(reference)) return;

    ArrayDeque<LoadedChunkReference> pending = new ArrayDeque<>();
    for (World world : Bukkit.getWorlds()) {
        for (Chunk chunk : world.getLoadedChunks()) {
            pending.addLast(new LoadedChunkReference(
                    world.getUID(), chunk.getX(), chunk.getZ()));
        }
    }

    new BukkitRunnable() {
        private static final int CHUNKS_PER_TICK = 64;

        @Override
        public void run() {
            for (int processed = 0;
                    processed < CHUNKS_PER_TICK && !pending.isEmpty();
                    processed++) {
                LoadedChunkReference reference = pending.removeFirst();
                World world = Bukkit.getWorld(reference.worldId());
                if (world == null
                        || !world.isChunkLoaded(reference.x(), reference.z())) continue;
                Chunk chunk = world.getChunkAt(reference.x(), reference.z());
                utils.cleanupExpiredBloodMoonZombies(chunk);
                mythicMobsManager.cleanupExpiredBloodMoonMutants(chunk);
            }
            if (pending.isEmpty()) cancel();
        }
    }.runTaskTimer(this, 1L, 1L);
}
```

- **[🔴 MAJOR] Blood Moon shutdown removes and broadcasts every tagged entity in one tick / `BloodMoonManager.java:599-611`; `xApocalypseUtils.java:641-659`; `MythicMobsManager.java:498-510`**
- **The Flaw:** Event termination scans multiple worlds, spawns particles, plays sounds, removes every tagged zombie, and removes every tracked Mutant synchronously. Several worlds at a 300-zombie cap can generate hundreds of entity removals and packet broadcasts in one tick.
- **The Fix:** Collect UUIDs and remove a fixed batch each tick.

```java
public int despawnBloodMoonZombies() {
    ArrayDeque<UUID> pending = new ArrayDeque<>();
    for (World world : Bukkit.getWorlds()) {
        if (!plugin.isWorldEnabled(world)) continue;
        for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
            if (zombie.getPersistentDataContainer().has(
                    BLOOD_MOON_KEY, PersistentDataType.BYTE)) {
                pending.addLast(zombie.getUniqueId());
            }
        }
    }

    int scheduled = pending.size();
    if (scheduled == 0) return 0;
    new BukkitRunnable() {
        private static final int REMOVALS_PER_TICK = 25;

        @Override
        public void run() {
            int removed = 0;
            while (removed < REMOVALS_PER_TICK && !pending.isEmpty()) {
                Entity entity = Bukkit.getEntity(pending.removeFirst());
                if (!(entity instanceof Zombie zombie)
                        || zombie.isDead()
                        || !zombie.getPersistentDataContainer().has(
                                BLOOD_MOON_KEY, PersistentDataType.BYTE)) continue;
                cancelBursterFuse(zombie);
                World world = zombie.getWorld();
                Location effect = zombie.getLocation().clone().add(0.0, 1.0, 0.0);
                world.spawnParticle(Particle.SMOKE, effect,
                        12, 0.25, 0.4, 0.25, 0.02);
                world.playSound(zombie.getLocation(),
                        Sound.ENTITY_ZOMBIE_DEATH, 0.6f, 0.7f);
                zombie.remove();
                removed++;
            }
            if (pending.isEmpty()) cancel();
        }
    }.runTaskTimer(plugin, 0L, 1L);
    return scheduled;
}
```

Replace the complete Mutant method with the matching PDC-authoritative batch:

```java
public int despawnActiveMutants() {
    ArrayDeque<UUID> pending = new ArrayDeque<>();
    for (World world : Bukkit.getWorlds()) {
        for (Entity entity : world.getEntities()) {
            if (entity.getPersistentDataContainer().has(
                    BLOOD_MOON_MUTANT_KEY, PersistentDataType.BYTE)) {
                pending.addLast(entity.getUniqueId());
                activeMutants.remove(entity.getUniqueId());
            }
        }
    }

    int scheduled = pending.size();
    if (scheduled == 0) return 0;

    new BukkitRunnable() {
        private static final int REMOVALS_PER_TICK = 25;

        @Override
        public void run() {
            int removed = 0;
            while (removed < REMOVALS_PER_TICK && !pending.isEmpty()) {
                Entity entity = Bukkit.getEntity(pending.removeFirst());
                if (entity == null || entity.isDead()) continue;
                if (!entity.getPersistentDataContainer().has(
                        BLOOD_MOON_MUTANT_KEY,
                        PersistentDataType.BYTE)) {
                    continue;
                }
                entity.remove();
                removed++;
            }
            if (pending.isEmpty()) cancel();
        }
    }.runTaskTimer(plugin, 0L, 1L);

    return scheduled;
}
```

- **[🔴 MAJOR] Expired offline immunity entries grow forever and are scanned every second / `ImmunityManager.java:176-212`**
- **The Flaw:** `startCheckTask` skips offline players before checking expiry. Their UUIDs remain in `immunePlayers`, `immunityEndTime`, and `originalHealth` indefinitely, and the task scans the growing set once per second. A long-running public server accumulates every player who consumed Guts and logged out before expiry. A related persistence path can discard the saved original max health on a later shutdown, leaving that player at five hearts permanently.
- **The Fix:** Retire expired offline UUIDs from the active set while retaining the original health and absolute end time until the player rejoins. Always load the end time, including expired records.

```java
private void loadImmunityData() {
    if (!dataConfig.isConfigurationSection("player-immunity")) return;
    long now = System.currentTimeMillis();
    ConfigurationSection section =
            dataConfig.getConfigurationSection("player-immunity");
    if (section == null) return;

    for (String key : section.getKeys(false)) {
        try {
            UUID uuid = UUID.fromString(key);
            long endTime = dataConfig.getLong(
                    "player-immunity." + key + ".endTimeMillis");
            double storedHealth = dataConfig.getDouble(
                    "player-immunity." + key + ".originalHealth");
            if (storedHealth <= 0.0 || endTime <= 0L) continue;

            originalHealth.put(uuid, storedHealth);
            immunityEndTime.put(uuid, endTime);
            if (endTime > now) immunePlayers.add(uuid);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid UUID in data.yml: " + key);
        }
    }
}
```

```java
public void startCheckTask() {
    new BukkitRunnable() {
        @Override
        public void run() {
            if (immunePlayers.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (UUID uuid : new ArrayList<>(immunePlayers)) {
                Long endTime = immunityEndTime.get(uuid);
                if (endTime == null || now < endTime) continue;

                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    expireImmunity(player, "immunity.expired");
                    retargetZombiesNearPlayer(player);
                } else {
                    immunePlayers.remove(uuid);
                    BukkitTask task = scheduledTasks.remove(uuid);
                    if (task != null) task.cancel();
                    BossBar bar = immunityBossBars.remove(uuid);
                    if (bar != null) bar.removeAll();
                    // originalHealth and immunityEndTime remain until join restores health.
                }
            }
        }
    }.runTaskTimer(plugin, 20L, 20L);
}
```
## Moderate bugs

- **[🟡 MODERATE] Nurse Can Resurrect a Zombie After Its Death Sequence Starts / `xApocalypseUtils.java:465-488`**
- **The Flaw:** `tickNurseAI` accepts every nearby `Zombie` whose numeric health is below its maximum. A killed zombie remains in the world's entity index during Minecraft's roughly 20-tick death animation. During that window its health is `0`, its model is red and tilted, and its internal death timer has already started. The Nurse writes positive health back with `setHealth` without resetting that internal death state. The result matches the reported bug: the zombie remains indefinitely in its death pose, no longer completes removal, and cannot be killed normally. The same method stores `LAST_HEAL_KEY` only after a successful heal, so a Nurse surrounded by healthy zombies runs a fresh nearby-entity query every 10 ticks instead of respecting the configured three-second interval.
- **The Fix:** Replace the complete method with the following. It rejects invalid, dead, and zero-health targets before reading attributes or writing health. The configured interval becomes a scan cooldown, which also prevents healthy groups from repeatedly querying the entity index.

```java
private void tickNurseAI(Zombie nurse) {
    if (!nurse.isValid() || nurse.isDead()) return;

    long now = System.currentTimeMillis();
    Long lastHeal = nurse.getPersistentDataContainer().get(
            LAST_HEAL_KEY, PersistentDataType.LONG);
    if (lastHeal != null && (now - lastHeal) < nurseIntervalMs) return;

    // Rate-limit the scan itself. Otherwise a Nurse with nothing to heal performs
    // this nearby-entity query on every custom-AI pass.
    nurse.getPersistentDataContainer().set(
            LAST_HEAL_KEY, PersistentDataType.LONG, now);

    if (nurseMaxTargets <= 0 || nurseHealAmount <= 0.0 || nurseRadius <= 0.0) {
        return;
    }

    int healedCount = 0;
    double radiusSquared = nurseRadius * nurseRadius;
    Location nurseLocation = nurse.getLocation();

    for (Entity entity : nurse.getNearbyEntities(
            nurseRadius, nurseRadius, nurseRadius)) {
        if (healedCount >= nurseMaxTargets) break;
        if (!(entity instanceof Zombie target)) continue;
        if (!target.isValid() || target.isDead()) continue;

        double currentHealth = target.getHealth();
        if (currentHealth <= 0.0) continue;
        if (nurseLocation.distanceSquared(target.getLocation()) > radiusSquared) continue;

        var maxHealth = target.getAttribute(AttributeResolver.MAX_HEALTH);
        if (maxHealth == null || currentHealth >= maxHealth.getValue()) continue;

        target.setHealth(Math.min(
                currentHealth + nurseHealAmount, maxHealth.getValue()));
        target.getWorld().spawnParticle(
                Particle.HEART,
                target.getLocation().add(0, 1.5, 0),
                5, 0.2, 0.2, 0.2, 0.1);
        healedCount++;
    }

    if (healedCount > 0) {
        nurse.getWorld().playSound(
                nurse.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
    }
}
```

- **[🟡 MODERATE] Zombie Cap Counts Every Zombie but Culls Only xApocalypse Zombies / `HordeManager.java:122-126`, `PerformanceWatchdog.java:94-105,130-134,152-160`, `xApocalypseUtils.java:201-233,442-446`**
- **The Flaw:** Horde admission and watchdog over-cap math use every Bukkit `Zombie` in the world, including vanilla mobs, NPC bases, and third-party mobs. Culling then filters that population through `isManagedZombie` and removes only xApocalypse zombies. A vanilla zombie farm can therefore stop all apocalypse hordes and make the watchdog delete every xApocalypse zombie while the measured count remains above the limit. Using `ZOMBIE_TYPE_KEY` alone as ownership is also insufficient because `zombie-classes.enabled: false` causes `assignZombieType` to return without applying that key. The cap needs a durable ownership marker independent of the optional class system.
- **The Fix:** Add a dedicated marker, stamp it on every xApocalypse-managed zombie, retain the type key as a legacy fallback, and use the same predicate for horde admission, watchdog counting, and watchdog culling. The following are complete corrected methods and the required key declaration.

```java
// Add beside the other xApocalypseUtils keys.
public static final NamespacedKey MANAGED_ZOMBIE_KEY =
        new NamespacedKey("xapocalypse", "managed_zombie");

public void assignZombieType(Zombie zombie) {
    zombie.getPersistentDataContainer().set(
            MANAGED_ZOMBIE_KEY, PersistentDataType.BYTE, (byte) 1);
    if (!plugin.getConfig().getBoolean("zombie-classes.enabled", true)) return;
    applyZombieType(zombie, getRandomZombieType());
}

public void applyZombieType(Zombie zombie, ZombieType type) {
    zombie.getPersistentDataContainer().set(
            MANAGED_ZOMBIE_KEY, PersistentDataType.BYTE, (byte) 1);
    zombie.getPersistentDataContainer().set(
            ZOMBIE_TYPE_KEY, PersistentDataType.STRING, type.name());

    // Tag zombies typed during an active blood moon so they can be cleaned up when it ends.
    // Veteran promotion reuses this method for an existing zombie, so it must not create a new
    // blood-moon ownership record for a pre-existing zombie.
    if (type != ZombieType.VETERAN && plugin.isBloodMoonActive(zombie.getWorld())) {
        zombie.getPersistentDataContainer().set(
                BLOOD_MOON_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    applyZombieHead(zombie, type);
    applyZombieStats(zombie, type);
}

public boolean isManagedZombie(Zombie zombie) {
    if (zombie == null) return false;
    return zombie.getPersistentDataContainer().has(
                    MANAGED_ZOMBIE_KEY, PersistentDataType.BYTE)
            // Legacy fallback for zombies saved by releases before MANAGED_ZOMBIE_KEY.
            || zombie.getPersistentDataContainer().has(
                    ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
}
```

Replace the complete `PerformanceWatchdog.isManagedZombie` and `countZombiesInWorld` methods with:

```java
private boolean isManagedZombie(Zombie zombie) {
    if (plugin.getUtils() == null || !plugin.getUtils().isManagedZombie(zombie)) {
        return false;
    }
    MythicMobsManager mythic = plugin.getMythicMobsManager();
    return mythic == null || !mythic.isMythicMob(zombie);
}

public int countManagedZombiesInWorld(World world) {
    int count = 0;
    for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
        if (isManagedZombie(zombie)) count++;
    }
    return count;
}
```

The complete corrected `HordeManager.spawnZombiesNearPlayer` method is supplied in the Major finding "Natural horde spawn storm and wasted surface work." That consolidated method uses this same ownership predicate for admission while also preserving the required global ten-spawns-per-tick budget; do not restore the unbudgeted loop when applying this cap correction.

Keep the existing key for compatibility but correct its documented scope:

```yaml
performance:
  # Maximum xApocalypse-managed zombies per enabled world.
  # Vanilla mobs, NPC bases, and third-party mobs are not counted or culled.
  max-total-zombies: 300
  tps-threshold: 15.0
  check-interval-ticks: 100
```

- **[🟡 MODERATE] TPS Threshold Can Make Horde Spawning Permanently Pause / `PerformanceWatchdog.java:79-110`**
- **The Flaw:** Spawning pauses below `performance.tps-threshold` and resumes only at `threshold + 1.5`. The configuration is not validated. A threshold above `18.5` therefore requires a TPS value above Paper's maximum `20.0`; after one dip, `spawningPaused` can never return to `false`. Non-finite YAML numeric values also make both comparisons false and silently disable watchdog transitions.
- **The Fix:** Replace the complete method below. It rejects non-finite values and clamps the pause threshold to `18.5`, guaranteeing a reachable resume point no higher than `20.0`. This version also uses the corrected managed-zombie count from the preceding finding.

```java
private void checkPerformance() {
    try {
        double currentTPS = getCurrentTPS();
        double configuredThreshold = plugin.getConfig().getDouble(
                "performance.tps-threshold", 15.0);
        if (!Double.isFinite(configuredThreshold)) {
            configuredThreshold = 15.0;
        }

        double tpsThreshold = Math.max(0.0,
                Math.min(18.5, configuredThreshold));
        double resumeThreshold = tpsThreshold + 1.5;

        if (currentTPS < tpsThreshold) {
            plugin.debugLog("Low TPS: " + currentTPS + " (< "
                    + tpsThreshold + ") - pausing spawning");
            pauseAllSpawning();
        } else if (currentTPS >= resumeThreshold) {
            resumeSpawning();
        }

        for (World world : Bukkit.getWorlds()) {
            if (!plugin.isWorldEnabled(world)) continue;

            int zombieCount = countManagedZombiesInWorld(world);
            int maxZombies = Math.max(0,
                    plugin.getConfig().getInt(
                            "performance.max-total-zombies", 300));
            if (zombieCount > maxZombies) {
                plugin.debugLog("Managed zombie count exceeded limit in "
                        + world.getName() + ": " + zombieCount
                        + " > " + maxZombies);
                cullZombiesInWorld(world, zombieCount - maxZombies);
            }
        }
    } catch (Exception exception) {
        plugin.getLogger().log(
                java.util.logging.Level.WARNING,
                "PerformanceWatchdog check failed", exception);
    }
}
```

- **[🟡 MODERATE] Custom Zombies Ignore All Fire and Lava Damage / `xApocalypseListener.java:258-275`**
- **The Flaw:** `onEntityDamage` cancels `FIRE`, `FIRE_TICK`, and `LAVA` for every typed zombie except `NORMAL`. This is independent of `zombie-settings.allow-daylight-burning`, so Runner, Nurse, Tank, Webber, and every other custom class can burn visually while taking no damage and can stand in lava indefinitely. The per-damage debug log also runs inside a hot event path when debug mode is enabled.
- **The Fix:** Replace the complete method with the following. Only Scorched zombies and zombies that explicitly have Fire Resistance remain fire immune.

```java
@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
public void onEntityDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Zombie zombie)) return;

    DamageCause cause = event.getCause();
    if (cause != DamageCause.FIRE
            && cause != DamageCause.FIRE_TICK
            && cause != DamageCause.LAVA) {
        return;
    }

    xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
    boolean explicitlyFireImmune = type == xApocalypseUtils.ZombieType.SCORCHED
            || zombie.hasPotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);

    if (explicitlyFireImmune) {
        event.setCancelled(true);
        zombie.setFireTicks(0);
    }
}
```

- **[🟡 MODERATE] Daylight Option Also Cancels Fire Weapons and Burning Blocks / `xApocalypseListener.java:324-347`**
- **The Flaw:** `EntityCombustEvent` is the parent of `EntityCombustByEntityEvent` and `EntityCombustByBlockEvent`. The current daytime branch treats every combustion event as sunlight. When daylight burning is disabled, Fire Aspect, flaming projectiles, lava, and burning blocks also fail to ignite zombies during the day. The current `time > 0` test additionally misses the first tick of day.
- **The Fix:** Replace the complete method with the following. The daylight rule applies only to the base environmental combustion event, while entity and block causes retain normal behavior.

```java
@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
public void onEntityCombust(EntityCombustEvent event) {
    if (!(event.getEntity() instanceof Zombie zombie)) return;
    if (!plugin.isWorldEnabled(zombie.getWorld())) return;

    xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
    boolean explicitlyFireImmune = type == xApocalypseUtils.ZombieType.SCORCHED
            || zombie.hasPotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);

    if (explicitlyFireImmune) {
        event.setCancelled(true);
        zombie.setFireTicks(0);
        return;
    }

    boolean daylightCombustion = !(event instanceof EntityCombustByEntityEvent)
            && !(event instanceof EntityCombustByBlockEvent);
    long time = zombie.getWorld().getTime();
    boolean isDay = time >= 0L && time < 12300L;

    if (daylightCombustion
            && isDay
            && !plugin.getConfig().getBoolean(
                    "zombie-settings.allow-daylight-burning", true)) {
        event.setCancelled(true);
    }
}
```

- **[🟡 MODERATE] Bursters Prime From Target Events Other Plugins Cancel / `xApocalypseListener.java:229-245`**
- **The Flaw:** The current default-priority handler performs immunity cancellation and Burster fuse activation together. A protection or AI plugin can cancel the target event at `HIGH` or `HIGHEST` after xApocalypse has already started the fuse. The Burster can then explode near a player it was never allowed to target. Merely adding `ignoreCancelled = true` to the current `NORMAL` handler does not solve later cancellation.
- **The Fix:** Remove the current `onEntityTarget` method and replace it with these two complete handlers. Immunity makes its decision at `HIGHEST`; Burster activation observes the final uncancelled result at `MONITOR`.

```java
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
public void onBursterTargetConfirmed(EntityTargetLivingEntityEvent event) {
    if (!(event.getEntity() instanceof Zombie zombie)) return;
    if (!(event.getTarget() instanceof Player player)) return;

    if (utils.getZombieType(zombie) == xApocalypseUtils.ZombieType.BURSTER) {
        utils.handleBursterTarget(zombie, player);
    }
}
```

- **[🟡 MODERATE] Special Melee Effects Trigger Through Shields and Zero-Damage Hits / `xApocalypseListener.java:277-294`**
- **The Flaw:** Webber webs, Frost slowness, Scorched fire, and Psychopath Wither are applied without testing the resolved damage. A shield block or another modifier that reduces final damage to zero still applies the complete secondary effect. Running at `HIGHEST` also means xApocalypse may act before another high-priority protection handler makes its final decision.
- **The Fix:** Replace the complete method with the following final-outcome handler. It ignores cancelled and zero-damage attacks.

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
    if (event.getFinalDamage() <= 0.0) return;
    if (!(event.getDamager() instanceof Zombie zombie)) return;
    if (!(event.getEntity() instanceof Player player)) return;

    xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
    if (type == null) return;

    switch (type) {
        case WEBBER -> utils.handleWebberHit(zombie, player);
        case FROST -> utils.handleFrostHit(zombie, player);
        case SCORCHED -> utils.handleScorchedHit(zombie, player);
        case PSYCHOPATH -> utils.handlePsychopathHit(zombie, player);
        default -> {
            // This zombie class has no melee secondary effect.
        }
    }
}
```

- **[🟡 MODERATE] Invalid Custom-Drop Values Can Break Every Eligible Zombie Death / `DropManager.java:83-180`**
- **The Flaw:** The parser accepts Bukkit materials that are blocks or fluids but cannot exist as an `ItemStack`, such as `WATER`. Paper throws when the death handler later constructs that stack. Amounts are not bounded; `entry.max() + 1` overflows at `Integer.MAX_VALUE` and makes `ThreadLocalRandom.nextInt` throw. Java accepts `NaN` as a double, and all comparisons with NaN are false, so a NaN drop, command, or broadcast chance becomes guaranteed. These failures occur in `EntityDeathEvent`, causing repeated stack traces and preventing the remaining reward logic from completing.
- **The Fix:** Replace the complete `parseTable`, `parseAmount`, `parseChance`, and `clampChance` methods below. This preserves their current signatures, rejects non-items, bounds every range to the material's legal stack size, and treats non-finite chances as disabled.

```java
private void parseTable(String path, List<DropEntry> into) {
    List<?> raw = plugin.getConfig().getList(path);
    if (raw == null) return;

    for (Object obj : raw) {
        if (!(obj instanceof java.util.Map<?, ?> map)) continue;

        Object matObj = map.get("material");
        if (matObj == null) continue;

        String materialName = String.valueOf(matObj).trim().toUpperCase(java.util.Locale.ROOT);
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir() || !material.isItem()) {
            plugin.getLogger().warning(
                    "[CustomDrops] Skipping invalid item material '" + matObj + "' in " + path);
            continue;
        }

        int[] range = parseAmount(map.get("amount"), path);
        int stackLimit = Math.max(1, material.getMaxStackSize());
        int min = Math.clamp(range[0], 1, stackLimit);
        int max = Math.clamp(range[1], min, stackLimit);

        double chance = parseChance(map.get("chance"));
        if (chance <= 0.0) continue;
        if (chance > 1.0) {
            plugin.getLogger().warning("[CustomDrops] chance " + chance + " in " + path
                    + " is above the documented 0.0-1.0 range; treating as guaranteed (1.0)."
                    + " For a percent, use a fraction (e.g. 50% -> 0.5).");
            chance = 1.0;
        }

        into.add(new DropEntry(material, min, max, chance));
    }
}

private int[] parseAmount(Object amountObj, String path) {
    long parsedMin = 1L;
    long parsedMax = 1L;

    if (amountObj != null) {
        String value = String.valueOf(amountObj).trim();
        try {
            if (value.contains("-")) {
                String[] parts = value.split("-", 2);
                parsedMin = Long.parseLong(parts[0].trim());
                parsedMax = Long.parseLong(parts[1].trim());
            } else {
                parsedMin = parsedMax = Long.parseLong(value);
            }
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("[CustomDrops] Invalid amount '" + amountObj + "' in " + path
                    + "; defaulting to 1. Use a number (\"3\") or an inclusive range (\"1-2\").");
            parsedMin = parsedMax = 1L;
        }
    }

    int min = (int) Math.clamp(parsedMin, 1L, 64L);
    int max = (int) Math.clamp(parsedMax, (long) min, 64L);
    return new int[]{min, max};
}

private double parseChance(Object chanceObj) {
    if (chanceObj == null) return 0.0;

    try {
        double chance = Double.parseDouble(String.valueOf(chanceObj).trim());
        return Double.isFinite(chance) ? chance : 0.0;
    } catch (NumberFormatException exception) {
        return 0.0;
    }
}

private double clampChance(double chance, String path) {
    if (!Double.isFinite(chance)) {
        plugin.getLogger().warning("[KillCommands] chance in " + path
                + " is not finite; disabling this roll.");
        return 0.0;
    }
    if (chance > 1.0) {
        plugin.getLogger().warning("[KillCommands] chance " + chance + " in " + path
                + " is above the documented 0.0-1.0 range; treating as guaranteed (1.0).");
        return 1.0;
    }
    return Math.max(0.0, chance);
}
```

- **[🟡 MODERATE] `/xa item` Partially Inserts Items Before Reporting Failure / `xApocalypseCommand.java:108-156`**
- **The Flaw:** `PlayerInventory.addItem` first fills partial stacks and empty slots, then returns only the remainder. The current code checks that remainder after mutation. A request for 64 Zombie Guts can therefore insert part of the stack and then tell the sender that the inventory is full. Repeating the command produces confusing partial grants.
- **The Fix:** Replace the complete `handleItem` method and add the complete helper below. Capacity is checked before the inventory is mutated, so the command is atomic.

```java
private boolean handleItem(CommandSender sender, String[] args) {
    if (!sender.hasPermission("xapocalypse.admin")) {
        sender.sendMessage(messageManager.get("no-permission"));
        return true;
    }

    if (args.length < 1) {
        sender.sendMessage(messageManager.getWithPrefix("commands.item.usage"));
        return true;
    }

    Player targetPlayer;
    if (!(sender instanceof Player)) {
        if (args.length < 2) {
            sender.sendMessage(messageManager.getWithPrefix("commands.item.usage"));
            return true;
        }
        targetPlayer = Bukkit.getPlayer(args[1]);
        if (targetPlayer == null) {
            sender.sendMessage(messageManager.getWithPrefix("player-not-found", args[1]));
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

    if (!args[0].equalsIgnoreCase("zombie_guts") || !plugin.isZombieGutsEnabled()) {
        sender.sendMessage(messageManager.getWithPrefix("commands.item.unknown", args[0]));
        return true;
    }

    int amount = parseItemAmount(args);
    ItemStack guts = plugin.getImmunity().createZombieGutsItem(amount);
    if (!canFitInStorage(targetPlayer, guts)) {
        sender.sendMessage("§c" + targetPlayer.getName()
                + "'s inventory does not have enough space.");
        return true;
    }

    targetPlayer.getInventory().addItem(guts);

    if (sender instanceof Player) {
        targetPlayer.sendMessage(messageManager.getWithPrefix(
                "commands.item.received", messageManager.get("immunity.item-name")));
    } else {
        sender.sendMessage(messageManager.getWithPrefix(
                "commands.item.given",
                targetPlayer.getName(),
                messageManager.get("immunity.item-name")));
    }
    return true;
}

private boolean canFitInStorage(Player player, ItemStack item) {
    int remaining = item.getAmount();
    int stackLimit = item.getMaxStackSize();

    for (ItemStack stored : player.getInventory().getStorageContents()) {
        if (stored == null || stored.getType().isAir()) {
            remaining -= stackLimit;
        } else if (stored.isSimilar(item)) {
            remaining -= Math.max(0, stackLimit - stored.getAmount());
        }

        if (remaining <= 0) return true;
    }
    return false;
}
```

- **[🟡 MODERATE] Mutant loop permanently stops before the Blood Moon world recovery window expires / `MythicMobsManager.java:259-300`, `BloodMoonManager.java:383-400`**
- **The Flaw:** `BloodMoonManager` allows a persisted reference world to be unavailable for 60 one-second checks, but `MythicMobsManager` cancels its periodic loop after three misses, which is about 15 seconds at the default interval. If the world returns between 15 and 60 seconds, the Blood Moon continues but the Mutant loop is already gone. Because the event is still marked persisted or forced, no new start transition calls `onBloodMoonStart()`, so periodic Mutants remain disabled until the next Blood Moon.
- **The Fix:** Make `BloodMoonManager` the sole owner of the start and end transition. A missing or temporarily inactive world pauses a Mythic cycle; it does not cancel the task. Delete the `bloodMoonMissCount` field and replace the complete method with the bounded version below, using the two cycle-budget constants defined in the Major Mutant spawn-burst finding:

```java
private void startSpawnTickLoop() {
    stopSpawnTickLoop();
    spawnTickTask = new BukkitRunnable() {
        @Override
        public void run() {
            World world = plugin.getBloodMoon().getReferenceWorld();
            if (world == null || !plugin.isBloodMoonActive(world)) return;

            pruneDeadMutants();
            if (maxGlobalCap > 0 && activeMutants.size() >= maxGlobalCap) return;

            List<Player> candidates = new ArrayList<>();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!plugin.isWorldEnabled(player.getWorld())
                        || plugin.isLobbyWorld(player.getWorld())) continue;
                if (random.nextDouble() < spawnChance) candidates.add(player);
            }
            Collections.shuffle(candidates);

            int attempts = 0;
            int spawned = 0;
            for (Player player : candidates) {
                if (attempts >= MAX_PLACEMENT_ATTEMPTS_PER_CYCLE
                        || spawned >= MAX_SPAWNS_PER_CYCLE) break;
                if (maxGlobalCap > 0 && activeMutants.size() >= maxGlobalCap) break;
                attempts++;
                Location location = findSpawnLocation(
                        player.getLocation(), spawnRadiusMin, spawnRadiusMax, true);
                if (location != null && spawnMythicMob(location, true) != null) spawned++;
            }
        }
    }.runTaskTimer(plugin, Math.max(20, spawnTickInterval),
            Math.max(20, spawnTickInterval));
}
```

- **[🟡 MODERATE] Reloading `mob-type` strands Blood Moon Mutants spawned under the old type / `MythicMobsManager.java:94-112`, `MythicMobsManager.java:424-446`, `MythicMobsManager.java:499-510`**
- **The Flaw:** `hookMythicMobs()` clears `activeMutants`, and `rebuildTrackedMutants()` repopulates it only with the newly configured `mobType`. A Blood Moon Mutant spawned under the previous type still has the durable `blood_moon_mutant` PDC tag, but its UUID is absent from the set used by `despawnActiveMutants()`. If its chunk stays loaded, the entity survives the end of the event indefinitely because the deferred chunk-load cleanup never fires.
- **The Fix:** Preserve the existing UUID set across a config reload, add current-type registry entries without discarding old ones, and treat the PDC tag as authoritative for end-of-event cleanup. This avoids adding another synchronous full-world scan to `/xa reload`. Replace the complete hook, rebuild, and chunk-cleanup methods below; use the PDC-based batched `despawnActiveMutants()` from the Major Blood Moon shutdown finding.

```java
private void hookMythicMobs() {
    org.bukkit.plugin.Plugin dependency =
            Bukkit.getPluginManager().getPlugin("MythicMobs");
    if (dependency == null || !dependency.isEnabled()) {
        log.warning("[MythicMobs] MythicMobs not available; smart spawn disabled.");
        mythicMobsEnabled = false;
        mmAPI = null;
        return;
    }

    try {
        mmAPI = MythicBukkit.inst().getAPIHelper();
        Optional<MythicMob> configured = MythicBukkit.inst()
                .getMobManager()
                .getMythicMob(mobType);
        if (configured.isEmpty()) {
            log.warning("[MythicMobs] Mob type '" + mobType + "' not found.");
            mythicMobsEnabled = false;
            return;
        }

        mythicMobsEnabled = true;
        rebuildTrackedMutants();
        log.info("[MythicMobs] Hooked in. Mob type '" + mobType + "' verified.");
    } catch (LinkageError error) {
        disableForLinkageError("hook into the MythicMobs API", error);
    } catch (Exception exception) {
        log.log(java.util.logging.Level.SEVERE,
                "[MythicMobs] Failed to hook into MythicMobs API", exception);
        mythicMobsEnabled = false;
        mmAPI = null;
    }
}

private void rebuildTrackedMutants() {
    if (!mythicMobsEnabled) return;

    try {
        for (ActiveMob activeMob :
                MythicBukkit.inst().getMobManager().getActiveMobs()) {
            if (mobType.equals(activeMob.getMobType())) {
                activeMutants.add(activeMob.getUniqueId());
            }
        }
    } catch (LinkageError error) {
        disableForLinkageError("rebuild Mutant tracking", error);
    } catch (Exception exception) {
        logMythicApiFailure("rebuild Mutant tracking", exception);
    }
}

public int cleanupExpiredBloodMoonMutants(Chunk chunk) {
    if (chunk == null) return 0;

    boolean bloodMoonActive = plugin.isBloodMoonActive(chunk.getWorld());
    int removed = 0;

    for (Entity entity : chunk.getEntities()) {
        if (!entity.getPersistentDataContainer().has(
                BLOOD_MOON_MUTANT_KEY, PersistentDataType.BYTE)) {
            continue;
        }

        if (bloodMoonActive) {
            activeMutants.add(entity.getUniqueId());
            continue;
        }

        activeMutants.remove(entity.getUniqueId());
        entity.remove();
        removed++;
    }

    return removed;
}
```

- **[🟡 MODERATE] Guaranteed Mutant placement ignores the configured minimum radius / `MythicMobsManager.java:232-257`**
- **The Flaw:** When the configured search fails, the fallback chooses offsets from `-5` through `4`, allowing the boss to appear on top of the target or only a few blocks away. It bypasses `spawn-radius.min`, `spawn-radius.max`, and the line-of-sight preference that the configuration promises. The claim check alone does not make this fallback valid.
- **The Fix:** Defer the guaranteed spawn when no safe location exists inside the configured radius. The periodic loop can retry naturally, and the plugin avoids creating an unfair point-blank boss. Replace the complete method with:

```java
private void spawnGuaranteedMutant() {
    List<? extends Player> online = Bukkit.getOnlinePlayers().stream()
            .filter(player -> plugin.isWorldEnabled(player.getWorld()))
            .filter(player -> !plugin.isLobbyWorld(player.getWorld()))
            .toList();

    if (online.isEmpty()) return;

    pruneDeadMutants();
    if (maxGlobalCap > 0 && activeMutants.size() >= maxGlobalCap) {
        return;
    }

    Player target = online.get(
            ThreadLocalRandom.current().nextInt(online.size()));
    Location location = findSpawnLocation(
            target.getLocation(),
            spawnRadiusMin,
            spawnRadiusMax,
            true);

    if (location == null) {
        plugin.debugLog(
                "[MythicMobs] Guaranteed Mutant deferred: no safe location "
                        + "within the configured radius of " + target.getName() + ".");
        return;
    }

    if (spawnMythicMob(location, true) != null) {
        broadcastBloodMoonSpawn(target);
    }
}
```

- **[🟡 MODERATE] Mythic API linkage failures escape event and scheduler paths while ordinary failures are hidden / `MythicMobsManager.java:391-400`, `MythicMobsManager.java:424-496`**
- **The Flaw:** Hooking and spawning catch `LinkageError`, but registry and identity calls catch only `Exception`. `NoSuchMethodError`, `NoClassDefFoundError`, or a mapping mismatch can therefore escape `CreatureSpawnEvent`, `EntityDeathEvent`, or the periodic task. That can terminate the scheduler and repeatedly break event dispatch. The existing `Exception` catches also return false without recording the failure, making incorrect classification and visible spawns impossible to diagnose. These are two manifestations of the same incomplete API failure boundary.
- **The Fix:** Add one rate-limited diagnostic boundary for ordinary API failures and handle `LinkageError` consistently by disabling the integration. Add the field and helper, keep the corrected `rebuildTrackedMutants()` above, and replace the complete identity and line-of-sight methods with:

```java
private long nextMythicApiWarningAtMillis = 0L;

private void logMythicApiFailure(String action, Exception exception) {
    long now = System.currentTimeMillis();
    if (now < nextMythicApiWarningAtMillis) return;

    nextMythicApiWarningAtMillis = now + 30_000L;
    log.warning("[MythicMobs] Could not " + action + ": " + exception);
}

private boolean hasLineOfSight(Player player, Location target) {
    if (player == null || target == null) return false;

    try {
        return player.hasLineOfSight(target);
    } catch (Exception exception) {
        logMythicApiFailure("perform a line-of-sight check", exception);
        return false;
    }
}

public boolean isMythicMobOrSpawning(Entity entity) {
    if (!mythicMobsEnabled || mmAPI == null || entity == null) return false;

    try {
        boolean mythic = MythicBukkit.inst()
                .getMobManager()
                .isMythicMobSpawning()
                || mmAPI.isMythicMob(entity);

        if (mythic) {
            entity.getPersistentDataContainer().set(
                    MYTHIC_ENTITY_KEY,
                    PersistentDataType.BYTE,
                    (byte) 1);
        }
        return mythic;
    } catch (LinkageError error) {
        disableForLinkageError("inspect a spawning entity", error);
        return false;
    } catch (Exception exception) {
        logMythicApiFailure("inspect a spawning entity", exception);
        return false;
    }
}

public boolean isMythicMob(Entity entity) {
    if (entity == null) return false;
    if (entity.getPersistentDataContainer().has(
            MYTHIC_ENTITY_KEY, PersistentDataType.BYTE)) {
        return true;
    }
    if (!mythicMobsEnabled || mmAPI == null) return false;

    try {
        boolean mythic = mmAPI.isMythicMob(entity);
        if (mythic) {
            entity.getPersistentDataContainer().set(
                    MYTHIC_ENTITY_KEY,
                    PersistentDataType.BYTE,
                    (byte) 1);
        }
        return mythic;
    } catch (LinkageError error) {
        disableForLinkageError("inspect a Mythic entity", error);
        return false;
    } catch (Exception exception) {
        logMythicApiFailure("inspect a Mythic entity", exception);
        return false;
    }
}

public boolean isConfiguredMutant(Entity entity) {
    if (!mythicMobsEnabled || entity == null) return false;
    if (activeMutants.contains(entity.getUniqueId())) return true;

    try {
        if (mmAPI == null || !mmAPI.isMythicMob(entity)) return false;

        ActiveMob activeMob = mmAPI.getMythicMobInstance(entity);
        if (activeMob == null || !mobType.equals(activeMob.getMobType())) {
            return false;
        }

        activeMutants.add(entity.getUniqueId());
        return true;
    } catch (LinkageError error) {
        disableForLinkageError("resolve a configured Mutant", error);
        return false;
    } catch (Exception exception) {
        logMythicApiFailure("resolve a configured Mutant", exception);
        return false;
    }
}
```

- **[🟡 MODERATE] NaN and infinite configuration values bypass numeric clamps / `BloodMoonManager.java:109-170`, `MythicMobsManager.java:70-92`**
- **The Flaw:** Java returns `NaN` from `Math.min` and `Math.max` when either operand is `NaN`. YAML values such as `.NaN` therefore survive the current clamps. Blood Moon multipliers can reach Bukkit attributes and horde arithmetic as nonfinite numbers, while a NaN Mythic spawn chance silently makes every chance comparison false. Infinite volume, pitch, and radius values are also retained.
- **The Fix:** Validate finiteness before applying bounds. Add the following helper to each manager and replace the complete configuration methods with these versions.

`BloodMoonManager`:

```java
private double finiteConfigDouble(
        FileConfiguration config,
        String path,
        double defaultValue,
        double minimum,
        double maximum) {

    double value = config.getDouble(path, defaultValue);
    if (!Double.isFinite(value)) {
        plugin.getLogger().warning(
                path + " must be finite; using " + defaultValue + ".");
        return defaultValue;
    }
    return Math.max(minimum, Math.min(maximum, value));
}

public void loadConfigValues(FileConfiguration cfg) {
    bloodMoonEnabled = cfg.getBoolean("bloodmoon.enabled");
    bloodMoonInterval = Math.max(1, cfg.getInt("bloodmoon.interval-days", 10));
    bloodMoonTitle = cfg.getString("bloodmoon.bossbar-title", "Blood Moon");
    if (bloodMoonTitle == null) bloodMoonTitle = "Blood Moon";
    bloodMoonForceDuration = Math.clamp(
            cfg.getInt("bloodmoon.force-duration-minutes", 10), 1, 120);

    bmHealthMult = finiteConfigDouble(
            cfg, "bloodmoon.multipliers.health", 2.0, 0.01, 100.0);
    bmDamageMult = finiteConfigDouble(
            cfg, "bloodmoon.multipliers.damage", 1.5, 0.0, 100.0);
    bmSpeedMult = finiteConfigDouble(
            cfg, "bloodmoon.multipliers.speed", 1.2, 0.0, 10.0);
    bmHordeMult = finiteConfigDouble(
            cfg, "bloodmoon.multipliers.horde-size", 1.5, 0.0, 100.0);

    warningEnabled = cfg.getBoolean("bloodmoon.warning.enabled", true);
    warningDaysBefore = cfg.getInt("bloodmoon.warning.days-before", 3);
    if (bloodMoonInterval == 1) {
        warningDaysBefore = 0;
    } else if (warningDaysBefore < 1) {
        plugin.getLogger().warning(
                "bloodmoon.warning.days-before must be at least 1 (was "
                        + warningDaysBefore + ") - clamping to 1");
        warningDaysBefore = 1;
    }
    if (bloodMoonInterval > 1 && warningDaysBefore >= bloodMoonInterval) {
        plugin.getLogger().warning(
                "bloodmoon.warning.days-before (" + warningDaysBefore
                        + ") must be less than bloodmoon.interval-days ("
                        + bloodMoonInterval + ") - clamping to "
                        + (bloodMoonInterval - 1));
        warningDaysBefore = Math.max(1, bloodMoonInterval - 1);
    }

    warningTitleEnabled = cfg.getBoolean(
            "bloodmoon.warning.title.enabled", true);
    warningTitleFadeIn = Math.max(0, cfg.getInt(
            "bloodmoon.warning.title.fade-in-ticks", 10));
    warningTitleStay = Math.max(0, cfg.getInt(
            "bloodmoon.warning.title.stay-ticks", 70));
    warningTitleFadeOut = Math.max(0, cfg.getInt(
            "bloodmoon.warning.title.fade-out-ticks", 20));
    warningSoundEnabled = cfg.getBoolean(
            "bloodmoon.warning.sound.enabled", true);
    warningSoundVolume = (float) finiteConfigDouble(
            cfg, "bloodmoon.warning.sound.volume", 1.0, 0.0, 16.0);
    warningSoundPitch = (float) finiteConfigDouble(
            cfg, "bloodmoon.warning.sound.pitch", 0.6, 0.0, 2.0);

    String warningSoundName = cfg.getString(
            "bloodmoon.warning.sound.name", "ENTITY_WITHER_AMBIENT");
    warningSound = warningSoundEnabled
            ? parseSound(warningSoundName, "bloodmoon.warning.sound.name")
            : null;

    startSoundEnabled = cfg.getBoolean(
            "bloodmoon.start-sound.enabled", true);
    startSoundVolume = (float) finiteConfigDouble(
            cfg, "bloodmoon.start-sound.volume", 1.0, 0.0, 16.0);
    startSoundPitch = (float) finiteConfigDouble(
            cfg, "bloodmoon.start-sound.pitch", 0.7, 0.0, 2.0);
    startSound = startSoundEnabled
            ? parseSound(
                    cfg.getString(
                            "bloodmoon.start-sound.name",
                            "ENTITY_WITHER_SPAWN"),
                    "bloodmoon.start-sound.name")
            : null;

    bloodMoonPersisted = bloodMoonDataConfig.getBoolean(
            "bloodmoon.persisted", false);
    persistedBloodMoonDay = bloodMoonDataConfig.getLong(
            "bloodmoon.persisted-day", -1);
    forcedBloodMoon = bloodMoonDataConfig.getBoolean(
            "bloodmoon.forced", false);
}
```

`MythicMobsManager`:

```java
private double finiteConfigDouble(
        FileConfiguration config,
        String path,
        double defaultValue,
        double minimum,
        double maximum) {

    double value = config.getDouble(path, defaultValue);
    if (!Double.isFinite(value)) {
        log.warning(path + " must be finite; using " + defaultValue + ".");
        return defaultValue;
    }
    return Math.max(minimum, Math.min(maximum, value));
}

public void loadConfig() {
    FileConfiguration cfg = plugin.getConfig();

    mobType = cfg.getString(
            "mythicmobs.integration.mob-type", "The_Mutant");
    if (mobType == null || mobType.isBlank()) {
        mobType = "The_Mutant";
        log.warning(
                "mythicmobs.integration.mob-type was blank; using The_Mutant.");
    }

    maxGlobalCap = Math.max(
            0,
            cfg.getInt("mythicmobs.integration.max-global-cap", 15));
    spawnChance = finiteConfigDouble(
            cfg,
            "mythicmobs.integration.spawn-chance",
            0.20,
            0.0,
            1.0);
    spawnRadiusMin = Math.clamp(
            cfg.getInt("mythicmobs.integration.spawn-radius.min", 20),
            1,
            511);
    spawnRadiusMax = Math.clamp(
            cfg.getInt("mythicmobs.integration.spawn-radius.max", 40),
            spawnRadiusMin + 1,
            512);
    spawnTickInterval = Math.max(
            20,
            cfg.getInt(
                    "mythicmobs.integration.spawn-tick-interval", 100));

    spawnSoundEnabled = cfg.getBoolean(
            "mythicmobs.integration.spawn-sound.enabled", true);
    spawnSoundVolume = (float) finiteConfigDouble(
            cfg,
            "mythicmobs.integration.spawn-sound.volume",
            1.0,
            0.0,
            16.0);
    spawnSoundPitch = (float) finiteConfigDouble(
            cfg,
            "mythicmobs.integration.spawn-sound.pitch",
            0.8,
            0.0,
            2.0);
    spawnSoundRadius = finiteConfigDouble(
            cfg,
            "mythicmobs.integration.spawn-sound.radius",
            48.0,
            0.0,
            1024.0);
    spawnSound = spawnSoundEnabled
            ? parseSound(cfg.getString(
                    "mythicmobs.integration.spawn-sound.name",
                    "ENTITY_ENDER_DRAGON_GROWL"))
            : null;

    hookMythicMobs();
}
```

- **[🟡 MODERATE] Disabled GriefPrevention can remain marked usable and terminate spawn work / `xApocalypse.java:395-405`, `xApocalypse.java:431-437`**
- **The Flaw:** `setupHooks()` accepts a `GriefPrevention` instance without verifying that the plugin is enabled. The cached `griefPreventionEnabled` flag also remains true if GriefPrevention is disabled later. A subsequent claim check can access an unavailable or cleared `dataStore`. That exception occurs inside placement code and can terminate the scheduled spawn task.
- **The Fix:** Verify enabled state during setup and again at the point of use. Replace both complete methods with:

```java
private void setupHooks() {
    griefPreventionEnabled = false;
    griefPrevention = null;

    if (!getConfig().getBoolean("hooks.griefprevention.enabled")) {
        return;
    }

    Plugin candidate = getServer()
            .getPluginManager()
            .getPlugin("GriefPrevention");
    if (candidate instanceof GriefPrevention gp && candidate.isEnabled()) {
        griefPrevention = gp;
        griefPreventionEnabled = true;
        getLogger().info("Hooked into GriefPrevention successfully.");
    } else if (candidate != null) {
        getLogger().warning(
                "GriefPrevention is installed but disabled; claim checks are disabled.");
    }
}

public boolean isInsideClaim(Location location) {
    if (location == null) return false;
    if (!getConfig().getBoolean("hooks.griefprevention.enabled")) return false;
    if (!getConfig().getBoolean(
            "hooks.griefprevention.prevent-spawning-in-claims")) {
        return false;
    }

    GriefPrevention gp = griefPrevention;
    if (!griefPreventionEnabled || gp == null || !gp.isEnabled()) {
        griefPreventionEnabled = false;
        griefPrevention = null;
        return false;
    }

    return gp.dataStore.getClaimAt(location, false, null) != null;
}
```

## Minor bugs

- **[🟢 MINOR] LOD Map Retains Dead Entity Wrappers Indefinitely Below 501 Entries / `PerformanceWatchdog.java:246-250`**
- **The Flaw:** `zombieLastAITick` holds strong references to Bukkit `Zombie` wrappers. `finishAITick` removes invalid entries only when the map size is greater than `500`. If a server accumulates 500 or fewer tracked far zombies and they later die or unload, those wrappers remain reachable for the rest of the plugin's lifetime unless the map happens to grow past the threshold. The map is capped, so this is bounded retention rather than an unbounded server killer.
- **The Fix:** Replace the complete method. The AI pass already runs every 10 ticks, and the configured entity cap keeps this map small; pruning it directly is cheap and deterministic.

```java
public void finishAITick() {
    zombieLastAITick.entrySet().removeIf(entry -> {
        Zombie zombie = entry.getKey();
        return zombie == null || zombie.isDead() || !zombie.isValid();
    });
}
```

- **[🟢 MINOR] Java 25 Test Suite Fails Unless Mockito's Byte Buddy Agent Is Added Manually / `pom.xml:15-20,89-101,104-118`**
- **The Flaw:** Mockito 5.23 attempts to attach its Byte Buddy instrumentation agent dynamically. Java 25 on the current host does not provide a working self-attach path, so plain `mvn test` discovers 16 tests but 12 error before their assertions run. The failure is reproducible. Supplying Mockito as a premain Java agent makes all 16 tests pass. A default test command that fails masks real regressions and prevents dependable local or CI verification.
- **The Fix:** Use one Mockito version property for the dependency and Surefire agent path, and add the complete Surefire plugin block below. Mockito 5.23's JAR declares `Premain-Class: org.mockito.internal.PremainAttach`, which initializes Byte Buddy without runtime self-attachment.

```xml
<properties>
    <java.version>25</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
    <mockito.version>5.23.0</mockito.version>
</properties>

<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>${mockito.version}</version>
    <scope>test</scope>
</dependency>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.4</version>
    <configuration>
        <argLine>-javaagent:${settings.localRepository}/org/mockito/mockito-core/${mockito.version}/mockito-core-${mockito.version}.jar</argLine>
    </configuration>
</plugin>
```

- **[🟢 MINOR] Look-Only Movement Packets Enter Scent Bookkeeping / `xApocalypseListener.java:47-50`**
- **The Flaw:** `PlayerMoveEvent` fires for yaw and pitch changes as well as movement. At high player counts, looking around causes UUID lookups and writes to `playerWasOnGround` even though the player did not change position and cannot have started a jump.
- **The Fix:** Replace the complete method with the following Paper API position guard.

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onPlayerMove(PlayerMoveEvent event) {
    if (!event.hasChangedPosition()) return;
    scent.onMove(event.getPlayer());
}
```

- **[🟢 MINOR] Cancelled Creature Spawns Still Receive Type and Attribute Work / `xApocalypseListener.java:161-197`**
- **The Flaw:** The handler does not ignore an event already cancelled by another plugin. xApocalypse can still roll a class, write persistent data, set attributes, generate equipment, and query Blood Moon state for an entity that Bukkit will discard at the end of the event.
- **The Fix:** Replace the complete method with the following. The body is otherwise unchanged.

```java
@EventHandler(ignoreCancelled = true)
public void onEntitySpawn(CreatureSpawnEvent event) {
    if (horde.isPluginSpawning()) return;

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

    if (entity instanceof Zombie zombie) {
        if (!plugin.isAllowBabyZombies() && !zombie.isAdult()) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.isAllowZombieVillagers()
                && zombie.getType() == EntityType.ZOMBIE_VILLAGER) {
            event.setCancelled(true);
            return;
        }
        utils.assignZombieType(zombie);
    }
}
```

- **[🟢 MINOR] Root Command Uses the JVM Default Locale / `xApocalypseCommand.java:53-86`**
- **The Flaw:** `String.toLowerCase()` uses the host's default locale. On Turkish-like locales, uppercase `I` lowercases to a dotless character, so otherwise valid input such as `/xa ITEM` fails dispatch.
- **The Fix:** Add `import java.util.Locale;` and replace the complete `onCommand` method with the following.

```java
@Override
public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
        sendHelp(sender);
        return true;
    }

    String sub = args[0].toLowerCase(Locale.ROOT);
    String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

    return switch (sub) {
        case "help" -> {
            sendHelp(sender);
            yield true;
        }
        case "reload" -> handleReload(sender);
        case "item" -> handleItem(sender, subArgs);
        case "spawn" -> handleSpawn(sender, subArgs);
        case "forcebloodmoon", "fbm" -> handleForceBloodMoon(sender, subArgs);
        case "stopbloodmoon", "sbm" -> handleStopBloodMoon(sender);
        default -> {
            sender.sendMessage(messageManager.getWithPrefix("commands.unknown", sub));
            yield true;
        }
    };
}
```

- **[🟢 MINOR] Tab Completion Uses the Default Locale, Advertises Unavailable Mutants, and Omits Amounts / `xApocalypseTabCompleter.java:1-82`**
- **The Flaw:** The completer repeats the locale-sensitive parsing bug, always advertises `MUTANT` even when MythicMobs integration is unavailable, omits the supported `fbm` and `sbm` aliases, and does not complete the documented fourth item-amount argument.
- **The Fix:** Replace the complete class with the following.

```java
package com.deleted.xapocalypse;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class xApocalypseTabCompleter implements TabCompleter {

    private final xApocalypse plugin;

    public xApocalypseTabCompleter(xApocalypse plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("help");
            if (sender.hasPermission("xapocalypse.command.spawn")) {
                subs.add("spawn");
            }
            if (sender.hasPermission("xapocalypse.admin")) {
                subs.add("reload");
                subs.add("item");
                subs.add("forcebloodmoon");
                subs.add("fbm");
                subs.add("stopbloodmoon");
                subs.add("sbm");
            }
            return filter(subs, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("spawn") && sender.hasPermission("xapocalypse.command.spawn")) {
            if (args.length == 2) {
                List<String> types = new ArrayList<>();
                types.add("horde");

                MythicMobsManager mythic = plugin.getMythicMobsManager();
                if (mythic != null && mythic.isMythicMobsEnabled()) {
                    types.add("MUTANT");
                }

                for (xApocalypseUtils.ZombieType type : xApocalypseUtils.ZombieType.values()) {
                    types.add(type.name());
                }
                return filter(types, args[1]);
            }
            if (args.length == 3) {
                return filter(Arrays.asList("1", "5", "10", "20", "50"), args[2]);
            }
            if (args.length == 4) {
                return filter(Arrays.asList("5", "10", "15", "20", "30"), args[3]);
            }
        }

        if (sub.equals("item") && sender.hasPermission("xapocalypse.admin")) {
            if (args.length == 2) {
                return filter(Collections.singletonList("zombie_guts"), args[1]);
            }
            if (args.length == 3) {
                return null;
            }
            if (args.length == 4) {
                return filter(Arrays.asList("1", "8", "16", "32", "64"), args[3]);
            }
        }

        if ((sub.equals("forcebloodmoon") || sub.equals("fbm"))
                && sender.hasPermission("xapocalypse.admin")
                && args.length == 2) {
            return filter(Arrays.asList("5", "10", "15", "30", "60"), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String input) {
        String low = input.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(low))
                .collect(Collectors.toList());
    }
}
```

The construction in `xApocalypse.onEnable()` must use the matching constructor:

```java
xApocalypseTabCompleter tabCompleter = new xApocalypseTabCompleter(this);
```

- **[🟢 MINOR] Plugin and Help Versions Are Hardcoded to 1.6.1 / `plugin.yml:3`, `messages.yml:1,18`**
- **The Flaw:** Maven resource filtering is already enabled, but both runtime metadata and the user-facing help title hardcode `1.6.1`. Building 1.6.2 or 1.7.0 without manually editing every copy produces a JAR whose displayed version disagrees with `pom.xml`.
- **The Fix:** Replace `plugin.yml` with this complete corrected configuration and use the corrected complete `commands.help` block in `messages.yml`.

```yaml
name: xApocalypse
main: com.deleted.xapocalypse.xApocalypse
version: "${project.version}"
api-version: "26.2"
authors: [xDele1ed]
description: A hardcore zombie horde plugin with advanced AI, Blood Moons, and GriefPrevention support.

softdepend: [GriefPrevention, MythicMobs, PlaceholderAPI]

# Everything lives under one root command. /xa, /xapocalypse and /zombie are interchangeable.
#   /xa help                                     show the command list
#   /xa reload                                   reload config + language
#   /xa item <item> [player]                     give a special item
#   /xa spawn <type|horde> [count] [radius]      spawn zombies
#   /xa forcebloodmoon [minutes]   (alias fbm)   force a blood moon
#   /xa stopbloodmoon              (alias sbm)   end a blood moon
commands:
  xapocalypse:
    description: Root command for xApocalypse — zombies, blood moons, items and config.
    usage: /xa help
    aliases: [xa, zombie]

permissions:
  xapocalypse.admin:
    description: Access to all admin sub-commands (reload, item, forcebloodmoon, stopbloodmoon)
    default: op
  xapocalypse.command.spawn:
    description: Ability to spawn custom zombies via /xa spawn
    default: op
```

```yaml
# xApocalypse v${project.version} - Localization File
commands:
  help:
    title: "&8&m       &r &c&lxApocalypse &r&7v${project.version} &8&m       "
    author: "&7Created by &fxDele1ed"
    help: "&c/xa help &8» &7Show this command list"
    spawn: "&c/xa spawn <type|horde> [count] [radius] &8» &7Spawn zombies"
    item: "&c/xa item <item> [player] [amount] &8» &7Get special items"
    forcebloodmoon: "&c/xa forcebloodmoon [minutes] &8(&7fbm&8) &8» &7Force a Blood Moon"
    stopbloodmoon: "&c/xa stopbloodmoon &8(&7sbm&8) &8» &7End the Blood Moon"
    reload: "&c/xa reload &8» &7Reload config and language files"
    footer: "&8Roots: &7/xa &8• &7/xapocalypse &8• &7/zombie"
```

- **[🟢 MINOR] Boss-bar membership synchronization performs quadratic list searches every second / `BloodMoonManager.java:502-512`**
- **The Flaw:** For every online player, the task calls `bloodMoonBar.getPlayers().contains(player)`. With 200 players this can perform roughly 40,000 equality checks each second and may repeatedly create list views depending on the server implementation.
- **The Fix:** Capture the current membership once and synchronize with sets. Add this complete helper method and replace lines 502-512 with `synchronizeBossBarPlayers();`:

```java
private void synchronizeBossBarPlayers() {
    java.util.Set<UUID> current = bloodMoonBar.getPlayers().stream()
            .map(Player::getUniqueId)
            .collect(java.util.stream.Collectors.toSet());
    java.util.Set<UUID> eligible = new java.util.HashSet<>();

    for (Player player : Bukkit.getOnlinePlayers()) {
        UUID uuid = player.getUniqueId();
        if (plugin.isWorldEnabled(player.getWorld())
                || plugin.isLobbyWorld(player.getWorld())) {
            eligible.add(uuid);
            if (!current.contains(uuid)) {
                bloodMoonBar.addPlayer(player);
            }
        }
    }

    for (Player player : List.copyOf(bloodMoonBar.getPlayers())) {
        if (!eligible.contains(player.getUniqueId())) {
            bloodMoonBar.removePlayer(player);
        }
    }
}
```

- **[🟢 MINOR] Every entity death performs Mythic API identity lookups before relevance is known / `xApocalypseListener.java:52-126`**
- **The Flaw:** The listener calls `isMythicMob` and `isConfiguredMutant` for animals, players, armor stands, and every other living entity even though those booleans are only used inside the Zombie reward branch. Busy farms therefore cross the optional Mythic API boundary several times per irrelevant death. UUID cap removal is the only operation that must remain type-agnostic.
- **The Fix:** Keep the cheap UUID removal at the top, but defer Mythic identity checks until the dead entity is a Zombie. Replace the complete method with:

```java
@EventHandler
public void onEntityDeath(EntityDeathEvent event) {
    Entity dead = event.getEntity();
    UUID deadId = dead.getUniqueId();
    MythicMobsManager mythic = plugin.getMythicMobsManager();

    // Any Mythic type can occupy the configured cap.
    mythic.notifyEntityDeath(deadId);

    if (dead instanceof Zombie zombie) {
        boolean mythicEntity = mythic.isMythicMob(zombie);
        boolean mutant = mythic.isTrackedMutant(deadId)
                || mythic.isConfiguredMutant(zombie);

        xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
        if (type == xApocalypseUtils.ZombieType.BURSTER) {
            utils.cancelBursterFuse(zombie);
        }

        zombie.removePotionEffect(
                org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);
        zombie.removePotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS);
        zombie.removePotionEffect(
                org.bukkit.potion.PotionEffectType.SPEED);
        zombie.setFireTicks(0);

        boolean rewardEligible = !mutant
                && !mythicEntity
                && plugin.isWorldEnabled(zombie.getWorld())
                && !plugin.isLobbyWorld(zombie.getWorld());
        if (rewardEligible) {
            immunity.maybeDropZombieGuts(event, zombie);
            plugin.getDropManager().applyDrops(event, zombie);
            plugin.getDropManager().runKillCommands(zombie);
        }
    }

    if (plugin.getConfig().getBoolean("scent-system.enabled", true)) {
        Player killer = dead.getKiller();
        if (killer != null) scent.onKill(killer);
    }

    if (!(dead instanceof Player)) return;
    if (!(dead.getLastDamageCause()
            instanceof EntityDamageByEntityEvent damageEvent)) return;
    if (!(damageEvent.getDamager() instanceof Zombie killerZombie)) return;
    if (!plugin.getConfig().getBoolean(
            "zombie-classes.veteran.permanent", true)) return;

    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
        if (killerZombie.isValid() && !killerZombie.isDead()) {
            utils.transformToVeteran(killerZombie);
            plugin.debugLog("Zombie " + killerZombie.getUniqueId()
                    + " transformed to VETERAN after kill");
        }
    }, 1L);
}
```

- **[🟢 MINOR] Root command registration assumes `plugin.yml` is perfect / `xApocalypse.java:125-129`**
- **The Flaw:** `getCommand("xapocalypse")` is dereferenced twice without checking for `null`. A packaging or metadata regression turns that into an opaque `NullPointerException` during enable rather than a clear plugin-disable reason.
- **The Fix:** Resolve the command once and fail with a precise error. Add this complete helper and call it from `onEnable()` after manager construction:

```java
private void registerRootCommand() {
    org.bukkit.command.PluginCommand command = getCommand("xapocalypse");
    if (command == null) {
        throw new IllegalStateException(
                "Missing commands.xapocalypse in the packaged plugin.yml");
    }

    command.setExecutor(new xApocalypseCommand(this));
    command.setTabCompleter(new xApocalypseTabCompleter(this));
}
```

- **[🟢 MINOR] Immunity persistence failures are silently discarded / `ImmunityManager.java:296-299,367-368`**
- **The Flaw:** Two expiry/restoration branches catch `IOException` with an empty body. A failed deletion is therefore invisible, and stale immunity can be reloaded later with no diagnostic explaining why. This does not directly interrupt gameplay, but it makes persistence failures difficult to detect and repair.
- **The Fix:** After applying the asynchronous `save()` from the Major persistence finding, route every record deletion through one complete helper and never perform a direct silent file write:

```java
private void removePersistedImmunity(UUID uuid) {
    if (uuid == null || dataConfig == null) return;
    dataConfig.set("player-immunity." + uuid, null);
    save();
}
```

Both `expireImmunity` and the expired branch of `onPlayerJoin` must call `removePersistedImmunity(uuid)` after restoring health and cleaning transient state.

## Extra / optional

- **[🔵 EXTRA / OPTIONAL] No Regression Coverage for Dead-Zombie Healing or Managed-Cap Ownership / `src/test/java/com/deleted/xapocalypse`**
- **The Flaw:** The current 16 tests cover mob-list matching, command amount parsing, surface selection, Miner geometry, Spitter launch, and direct acid-handler invocation. None drives Nurse healing, entity death/removal, ownership markers, cap counting, watchdog state transitions, or scheduler saturation. The half-dead bug could therefore exist for months without any failing build. The cap-domain mismatch is similarly invisible because no test mixes managed and unmanaged zombies.
- **The Fix:** Add the two complete regression test classes below. The Nurse test fails against the current resurrection path because the dead target has zero health and a valid maximum-health attribute; the corrected method exits before `setHealth`. The cap test asserts that unmanaged and Mythic zombies do not consume or get removed from the xApocalypse ownership budget.

```java
package com.deleted.xapocalypse;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NurseDeathRegressionTest {

    private xApocalypseUtils utils;

    @BeforeEach
    void setUp() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getStringList(anyString())).thenReturn(List.of());
        when(config.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getDouble(anyString(), anyDouble()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getLong(anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        utils = new xApocalypseUtils(plugin);
        setField("nurseIntervalMs", 0L);
        setField("nurseRadius", 5.0);
        setField("nurseHealAmount", 3.0);
        setField("nurseMaxTargets", 5);
    }

    @Test
    void nurseNeverHealsZombieWhoseDeathSequenceHasStarted() throws Exception {
        World world = mock(World.class);
        Zombie nurse = mock(Zombie.class);
        Zombie dying = mock(Zombie.class);
        AttributeInstance maxHealth = mock(AttributeInstance.class);
        PersistentDataContainer nurseData = mock(PersistentDataContainer.class);

        when(nurse.isValid()).thenReturn(true);
        when(nurse.isDead()).thenReturn(false);
        when(nurse.getWorld()).thenReturn(world);
        when(nurse.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(nurse.getPersistentDataContainer()).thenReturn(nurseData);
        when(nurse.getNearbyEntities(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.<Entity>of(dying));

        when(dying.isValid()).thenReturn(true);
        when(dying.isDead()).thenReturn(true);
        when(dying.getHealth()).thenReturn(0.0);
        when(dying.getAttribute(any())).thenReturn(maxHealth);
        when(maxHealth.getValue()).thenReturn(25.0);

        Method tickNurse = xApocalypseUtils.class.getDeclaredMethod(
                "tickNurseAI", Zombie.class);
        tickNurse.setAccessible(true);
        tickNurse.invoke(utils, nurse);

        verify(dying, never()).setHealth(anyDouble());
    }

    private void setField(String name, Object value) throws Exception {
        Field field = xApocalypseUtils.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(utils, value);
    }
}
```

```java
package com.deleted.xapocalypse;

import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceWatchdogCapTest {

    @Test
    void capCountsOnlyManagedNonMythicZombies() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        xApocalypseUtils utils = mock(xApocalypseUtils.class);
        MythicMobsManager mythic = mock(MythicMobsManager.class);
        World world = mock(World.class);
        Zombie managed = mock(Zombie.class);
        Zombie unmanaged = mock(Zombie.class);
        Zombie mythicZombie = mock(Zombie.class);

        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong(anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(plugin.getUtils()).thenReturn(utils);
        when(plugin.getMythicMobsManager()).thenReturn(mythic);
        when(world.getEntitiesByClass(Zombie.class))
                .thenReturn(List.of(managed, unmanaged, mythicZombie));

        when(utils.isManagedZombie(managed)).thenReturn(true);
        when(utils.isManagedZombie(unmanaged)).thenReturn(false);
        when(utils.isManagedZombie(mythicZombie)).thenReturn(true);
        when(mythic.isMythicMob(managed)).thenReturn(false);
        when(mythic.isMythicMob(mythicZombie)).thenReturn(true);

        PerformanceWatchdog watchdog = new PerformanceWatchdog(plugin);
        assertEquals(1, watchdog.countManagedZombiesInWorld(world));
    }
}
```

The next optional integration layer should exercise 200 simulated players, more than 50 simultaneous rise requests, plugin reload during an active rise, and a real Paper death animation. Those cases need a running Paper test fixture because Mockito cannot model NMS death timers, chunk loading, scheduler ordering, or packet visibility accurately.

- **[🔵 EXTRA / OPTIONAL] MiniMessage Components Are Immediately Flattened Back to Legacy Text / `MessageManager.java:92-142,241-265`**
- **The Flaw:** `get()` parses MiniMessage into a `Component`, serializes it back to legacy section-code text, and callers then use `sendMessage(String)`. This down-samples RGB gradients and discards hover, click, insertion, and other Adventure events. `getComponent` exists, but nearly all command messages bypass it. In addition, `getComponent` currently inserts arguments before MiniMessage parsing without escaping tags, which would allow a future player-controlled argument to inject MiniMessage markup.
- **The Fix:** Keep legacy `get()` for APIs that still require strings, but route player, command-sender, title, and bossbar output through these complete component methods. Arguments are escaped only for the MiniMessage path.

```java
public Component getComponent(String path, Object... args) {
    String rawMessage = messages.getString(path);
    if (rawMessage == null) {
        rawMessage = "&cMissing message: " + path;
    }

    boolean mini = usesMiniMessage(rawMessage);
    for (int i = 0; i < args.length; i++) {
        String replacement = String.valueOf(args[i]);
        if (mini) {
            replacement = miniMessage.escapeTags(replacement);
        }
        rawMessage = rawMessage.replace("{" + i + "}", replacement);
    }

    if (mini) {
        try {
            return miniMessage.deserialize(legacyToMiniMessage(rawMessage));
        } catch (Exception exception) {
            plugin.getLogger().warning(
                    "Failed to parse MiniMessage component at '" + path + "': "
                            + exception.getMessage());
        }
    }

    String legacy = ChatColor.translateAlternateColorCodes('&', rawMessage);
    return legacySerializer.deserialize(legacy);
}

public Component getComponentWithPrefix(String path, Object... args) {
    return Component.text()
            .append(getComponent("prefix"))
            .append(Component.space())
            .append(getComponent(path, args))
            .build();
}
```

Paper's `CommandSender` is an Adventure audience, so migrated call sites can preserve the component directly:

```java
sender.sendMessage(messageManager.getComponentWithPrefix("commands.unknown", sub));
```

- **[🔵 EXTRA / OPTIONAL] Replace repeated full Mythic active-registry reconciliation with Mythic lifecycle events / `MythicMobsManager.java:424-446`**
- **The Flaw:** `pruneDeadMutants()` calls `getActiveMobs()` every periodic cycle and from commands and lifecycle transitions. That makes xApocalypse repeatedly scan every Mythic mob on the server. MythicMobs already exposes spawn, death, and despawn events that can keep the cap set exact as entities change.
- **The Fix:** Keep one startup/reload reconciliation, then maintain the set through a dedicated optional listener. Add the complete manager methods and listener class below. Register the listener only when MythicMobs is installed and enabled.

```java
public void notifyMythicMobSpawn(ActiveMob activeMob, Entity entity) {
    if (!mythicMobsEnabled || activeMob == null || entity == null) return;

    entity.getPersistentDataContainer().set(
            MYTHIC_ENTITY_KEY,
            PersistentDataType.BYTE,
            (byte) 1);

    if (mobType.equals(activeMob.getMobType())) {
        activeMutants.add(activeMob.getUniqueId());
    }
}

public void notifyMythicMobRemoved(UUID uuid) {
    if (uuid != null) {
        activeMutants.remove(uuid);
    }
}

private void pruneDeadMutants() {
    activeMutants.removeIf(uuid -> {
        Entity entity = Bukkit.getEntity(uuid);
        return entity != null && entity.isDead();
    });
}
```

```java
package com.deleted.xapocalypse;

import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.bukkit.events.MythicMobDespawnEvent;
import io.lumine.mythic.bukkit.events.MythicMobSpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class MythicMobTrackingListener implements Listener {
    private final MythicMobsManager manager;

    public MythicMobTrackingListener(MythicMobsManager manager) {
        this.manager = manager;
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onMythicMobSpawn(MythicMobSpawnEvent event) {
        manager.notifyMythicMobSpawn(event.getMob(), event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMythicMobDeath(MythicMobDeathEvent event) {
        manager.notifyMythicMobRemoved(event.getMob().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMythicMobDespawn(MythicMobDespawnEvent event) {
        manager.notifyMythicMobRemoved(event.getMob().getUniqueId());
    }
}
```

- **[🔵 EXTRA / OPTIONAL] Give each subsystem ownership of its scheduled tasks and optional listeners / `xApocalypse.java:212-268`, `BloodMoonManager.java:355-568`**
- **The Flaw:** Reload currently relies on `Bukkit.getScheduler().cancelTasks(this)`, which cancels every task owned by the plugin. Managers then have to be restarted in a fragile order, and a new task added to one subsystem can silently be killed by another subsystem's restart. `BloodMoonManager` also does not retain its lifecycle task, so it cannot independently guarantee that only one copy exists.
- **The Fix:** Give each manager one lifecycle object and close only that manager's resources during reload or shutdown. The following complete utility class owns repeating/delayed tasks and listeners without touching unrelated plugin work:

```java
package com.deleted.xapocalypse;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SubsystemLifecycle implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Set<BukkitTask> tasks = new HashSet<>();
    private final Set<Listener> listeners = new HashSet<>();
    private boolean closed;

    public SubsystemLifecycle(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public BukkitTask runTaskTimer(
            Runnable runnable,
            long delayTicks,
            long periodTicks) {

        ensureOpen();
        BukkitTask task = plugin.getServer()
                .getScheduler()
                .runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        tasks.add(task);
        return task;
    }

    public BukkitTask runTaskLater(Runnable runnable, long delayTicks) {
        ensureOpen();
        BukkitTask task = plugin.getServer()
                .getScheduler()
                .runTaskLater(plugin, runnable, delayTicks);
        tasks.add(task);
        return task;
    }

    public void registerListener(Listener listener) {
        ensureOpen();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(listener, plugin);
        listeners.add(listener);
    }

    public void cancelTasks() {
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    public void unregisterListeners() {
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cancelTasks();
        unregisterListeners();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Subsystem lifecycle is closed");
        }
    }
}
```

Each manager should create its own `SubsystemLifecycle`, use it instead of anonymous untracked scheduler calls, and close/recreate that lifecycle in its explicit `reload()` method. `xApocalypse.reloadAll()` can then call manager reload methods without globally cancelling unrelated plugin tasks.
