package com.deleted.xapocalypse;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Owns the entire Blood Moon subsystem: configuration multipliers, the BloodMoonData.yml
 * persistence file, the bossbar, the repeating lifecycle task, and the force/stop commands.
 *
 * Extracted verbatim from the original xApocalypse monolith — every bug-fix (Bug C5/C6,
 * Fix RC1-RC4, Bug M5) is preserved. The MythicMobs integration is resolved lazily through
 * the plugin facade ({@code plugin.getMythicMobsManager()}) so this manager holds no direct
 * reference to MythicMobsManager (keeps the dependency graph acyclic at the type level).
 */
public class BloodMoonManager {

    private final xApocalypse plugin;

    // --- CONFIG VALUES ---
    private boolean bloodMoonEnabled;
    private int bloodMoonInterval;
    private String bloodMoonTitle;
    private int bloodMoonForceDuration; // CRITICAL FIX: force duration config

    // --- MULTIPLIERS ---
    private double bmHealthMult;
    private double bmDamageMult;
    private double bmSpeedMult;
    private double bmHordeMult;

    // --- WARNING CONFIG (bloodmoon.warning.*) ---
    private boolean warningEnabled;
    private int warningDaysBefore;                 // clamped to [1, interval-1]
    private boolean warningTitleEnabled;
    private int warningTitleFadeIn;
    private int warningTitleStay;
    private int warningTitleFadeOut;
    private boolean warningSoundEnabled;
    private Sound warningSound;                    // null if the configured name is invalid
    private float warningSoundVolume;
    private float warningSoundPitch;

    // --- START SOUND CONFIG (bloodmoon.start-sound.*) ---
    private boolean startSoundEnabled;
    private Sound startSound;
    private float startSoundVolume;
    private float startSoundPitch;

    // --- RUNTIME STATE ---
    private boolean forcedBloodMoon = false;
    private long forcedBloodMoonStartTime = -1; // CRITICAL FIX: Track forced blood moon start time
    private long forcedBloodMoonDuration = -1; // CRITICAL FIX: Track actual forced duration
    private BossBar bloodMoonBar;

    // CRITICAL FIX: blood moon persistence fields
    private boolean bloodMoonPersisted = false;
    private long persistedBloodMoonDay = -1;
    private UUID bloodMoonWorldId = null;
    private int missingReferenceWorldChecks = 0;
    private volatile int cachedDaysUntilNextBloodMoon = 0;

    // Pre-blood-moon warning dedupe: the day number the warning last broadcast on.
    // Persisted in BloodMoonData.yml so a same-day restart doesn't re-broadcast.
    private long lastWarnedDay = -1;

    // CRITICAL FIX: Separate blood moon data file
    private File bloodMoonDataFile;
    private FileConfiguration bloodMoonDataConfig;
    private AtomicYamlWriter bloodMoonDataWriter;

    public BloodMoonManager(xApocalypse plugin) {
        this.plugin = plugin;
    }

    // === SETUP / PERSISTENCE ===

    /** Initializes BloodMoonData.yml and loads persisted state. Must NOT touch MessageManager. */
    public void load(File dataFolder) {
        // CRITICAL FIX: Initialize separate blood moon data file
        bloodMoonDataFile = new File(dataFolder, "BloodMoonData.yml");
        if (!bloodMoonDataFile.exists()) {
            try {
                bloodMoonDataFile.createNewFile();
                plugin.getLogger().info("Created new BloodMoonData.yml file");
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create BloodMoonData.yml: " + e.getMessage());
            }
        }
        bloodMoonDataConfig = YamlConfiguration.loadConfiguration(bloodMoonDataFile);
        bloodMoonDataWriter = new AtomicYamlWriter(bloodMoonDataFile.toPath(), exception ->
                plugin.getLogger().severe("Could not save BloodMoonData.yml: " + exception.getMessage()));

        // CRITICAL FIX: Load blood moon persistence data BEFORE config values
        loadBloodMoonData();
    }

    public void loadConfigValues(FileConfiguration cfg) {
        bloodMoonEnabled = cfg.getBoolean("bloodmoon.enabled");
        bloodMoonInterval = Math.max(1, cfg.getInt("bloodmoon.interval-days", 10));
        bloodMoonTitle = cfg.getString("bloodmoon.bossbar-title", "Blood Moon");
        if (bloodMoonTitle == null) bloodMoonTitle = "Blood Moon";
        bloodMoonForceDuration = Math.max(1, cfg.getInt("bloodmoon.force-duration-minutes", 10));
        bmHealthMult = cfg.getDouble("bloodmoon.multipliers.health", 2.0);
        bmDamageMult = cfg.getDouble("bloodmoon.multipliers.damage", 1.5);
        bmSpeedMult = cfg.getDouble("bloodmoon.multipliers.speed", 1.2);
        bmHordeMult = cfg.getDouble("bloodmoon.multipliers.horde-size", 1.5);

        // --- Pre-blood-moon warning settings ---
        warningEnabled = cfg.getBoolean("bloodmoon.warning.enabled", true);
        warningDaysBefore = cfg.getInt("bloodmoon.warning.days-before", 3);
        if (bloodMoonInterval == 1) {
            // There is no distinct "day before" when every day is a Blood Moon day.
            warningDaysBefore = 0;
        } else if (warningDaysBefore < 1) {
            plugin.getLogger().warning("bloodmoon.warning.days-before must be at least 1 (was "
                    + warningDaysBefore + ") - clamping to 1");
            warningDaysBefore = 1;
        }
        if (bloodMoonInterval > 1 && warningDaysBefore >= bloodMoonInterval) {
            plugin.getLogger().warning("bloodmoon.warning.days-before (" + warningDaysBefore
                    + ") must be less than bloodmoon.interval-days (" + bloodMoonInterval
                    + ") - clamping to " + (bloodMoonInterval - 1));
            warningDaysBefore = Math.max(1, bloodMoonInterval - 1);
        }
        warningTitleEnabled = cfg.getBoolean("bloodmoon.warning.title.enabled", true);
        warningTitleFadeIn = cfg.getInt("bloodmoon.warning.title.fade-in-ticks", 10);
        warningTitleStay = cfg.getInt("bloodmoon.warning.title.stay-ticks", 70);
        warningTitleFadeOut = cfg.getInt("bloodmoon.warning.title.fade-out-ticks", 20);
        warningSoundEnabled = cfg.getBoolean("bloodmoon.warning.sound.enabled", true);
        warningSoundVolume = (float) cfg.getDouble("bloodmoon.warning.sound.volume", 1.0);
        warningSoundPitch = (float) cfg.getDouble("bloodmoon.warning.sound.pitch", 0.6);
        String soundName = cfg.getString("bloodmoon.warning.sound.name", "ENTITY_WITHER_AMBIENT");
        warningSound = null;
        if (warningSoundEnabled && soundName != null && !soundName.isEmpty()) {
            try {
                // Accept both "ENTITY_WITHER_AMBIENT" and "entity.wither.ambient" forms.
                warningSound = Sound.valueOf(soundName.toUpperCase().replace('.', '_'));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid bloodmoon.warning.sound.name: '" + soundName
                        + "' - the warning sound will be skipped (chat/title still work)");
            }
        }

        startSoundEnabled = cfg.getBoolean("bloodmoon.start-sound.enabled", true);
        startSoundVolume = Math.max(0.0f,
                (float) cfg.getDouble("bloodmoon.start-sound.volume", 1.0));
        startSoundPitch = Math.max(0.0f,
                (float) cfg.getDouble("bloodmoon.start-sound.pitch", 0.7));
        startSound = startSoundEnabled ? parseSound(cfg.getString(
                "bloodmoon.start-sound.name", "ENTITY_WITHER_SPAWN"),
                "bloodmoon.start-sound.name") : null;

        // CRITICAL FIX: Load blood moon persistence data from separate file (dual-read preserved
        // from the original loadConfigValues, which re-asserted these flags after reloadConfig()).
        bloodMoonPersisted = bloodMoonDataConfig.getBoolean("bloodmoon.persisted", false);
        persistedBloodMoonDay = bloodMoonDataConfig.getLong("bloodmoon.persisted-day", -1);
        forcedBloodMoon = bloodMoonDataConfig.getBoolean("bloodmoon.forced", false);
    }

    // CRITICAL FIX: blood moon data loading method
    private void loadBloodMoonData() {
        if (bloodMoonDataConfig == null) {
            plugin.getLogger().warning("Cannot load blood moon data - bloodMoonDataConfig is null");
            return;
        }

        // Load persisted blood moon state from separate file
        bloodMoonPersisted = bloodMoonDataConfig.getBoolean("bloodmoon.persisted", false);
        persistedBloodMoonDay = bloodMoonDataConfig.getLong("bloodmoon.persisted-day", -1);
        forcedBloodMoon = bloodMoonDataConfig.getBoolean("bloodmoon.forced", false);
        // Bug C6 fix: restore the forced blood moon's original anchor and duration so a restart
        // mid-forced-blood-moon can't reset the timer (which let a forced blood moon be extended
        // indefinitely by restarting before it expired).
        forcedBloodMoonStartTime = bloodMoonDataConfig.getLong("bloodmoon.forced-start-time", -1);
        forcedBloodMoonDuration = bloodMoonDataConfig.getLong("bloodmoon.forced-duration-minutes", -1);
        String worldId = bloodMoonDataConfig.getString("bloodmoon.world-id");
        if (worldId != null) {
            try {
                bloodMoonWorldId = UUID.fromString(worldId);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid bloodmoon.world-id in BloodMoonData.yml; using the first enabled world.");
                bloodMoonWorldId = null;
            }
        }
        // Warning dedupe: restore the last day a pre-blood-moon warning broadcast on so a
        // same-day restart doesn't fire the warning twice.
        lastWarnedDay = bloodMoonDataConfig.getLong("bloodmoon.last-warned-day", -1);

        if (bloodMoonPersisted) {
            plugin.getLogger().info("Loaded persisted blood moon from BloodMoonData.yml - day " + persistedBloodMoonDay);
            plugin.debugLog("Blood moon persistence: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay + ", forced=" + forcedBloodMoon);
        } else {
            plugin.debugLog("No persisted blood moon data found in BloodMoonData.yml");
        }
    }

    // CRITICAL FIX: blood moon data saving method
    public void save() {
        if (bloodMoonDataConfig == null || bloodMoonDataFile == null || bloodMoonDataWriter == null) {
            plugin.getLogger().warning("Cannot save blood moon data - blood moon data files not initialized");
            return;
        }

        try {
            bloodMoonDataConfig.set("bloodmoon.persisted", bloodMoonPersisted);
            bloodMoonDataConfig.set("bloodmoon.persisted-day", persistedBloodMoonDay);
            bloodMoonDataConfig.set("bloodmoon.forced", forcedBloodMoon);
            // Bug C6 fix: persist the forced blood moon anchor + duration alongside the flags.
            bloodMoonDataConfig.set("bloodmoon.forced-start-time", forcedBloodMoonStartTime);
            bloodMoonDataConfig.set("bloodmoon.forced-duration-minutes", forcedBloodMoonDuration);
            bloodMoonDataConfig.set("bloodmoon.world-id",
                    bloodMoonWorldId == null ? null : bloodMoonWorldId.toString());
            bloodMoonDataConfig.set("bloodmoon.last-warned-day", lastWarnedDay);
            bloodMoonDataWriter.write(bloodMoonDataConfig.saveToString());

            plugin.debugLog("Saved blood moon data to BloodMoonData.yml: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay + ", forced=" + forcedBloodMoon);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not serialize BloodMoonData.yml: " + e.getMessage());
        }
    }

    public void close() {
        if (bloodMoonDataWriter != null) bloodMoonDataWriter.close();
    }

    // === STATE QUERIES ===

    /**
     * Returns the single world that owns the server-wide event clock. During an active event the
     * UUID is persisted, so world load order cannot silently transfer ownership to another world.
     */
    public World getReferenceWorld() {
        boolean activeState = bloodMoonPersisted || forcedBloodMoon;
        if (activeState && bloodMoonWorldId != null) {
            World anchored = Bukkit.getWorld(bloodMoonWorldId);
            if (anchored != null && plugin.isWorldEnabled(anchored)) return anchored;
            return null;
        }

        World firstEnabled = Bukkit.getWorlds().stream()
                .filter(plugin::isWorldEnabled)
                .findFirst()
                .orElse(null);
        if (activeState && firstEnabled != null && bloodMoonWorldId == null) {
            // Migration path for BloodMoonData.yml written before the world UUID was persisted.
            bloodMoonWorldId = firstEnabled.getUID();
            save();
        }
        return firstEnabled;
    }

    public boolean isActive(World world) {
        if (!bloodMoonEnabled) return false;
        if (world == null) return false;
        if (!plugin.isWorldEnabled(world)) return false;

        World referenceWorld = getReferenceWorld();
        if (referenceWorld == null) return false;

        long time = referenceWorld.getTime();
        long fullTime = referenceWorld.getFullTime();
        long dayNumber = fullTime / 24000;

        boolean isDayOf = (dayNumber > 0) && (dayNumber % bloodMoonInterval == 0);

        // CRITICAL FIX: Check for persisted blood moon state
        if (bloodMoonPersisted && persistedBloodMoonDay == dayNumber) {
            isDayOf = true;
        }

        if (forcedBloodMoon) {
            isDayOf = true;
        }

        boolean isNight = time >= 13000 && time <= 23000;

        // CRITICAL FIX: For forced blood moon, use actual elapsed time
        if (forcedBloodMoon) {
            if (forcedBloodMoonStartTime == -1) {
                return false;
            }

            long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
            long elapsedTicks = elapsedMs / 50; // Convert milliseconds to ticks (20 ticks = 1000ms)
            long actualDuration = forcedBloodMoonDuration != -1 ? forcedBloodMoonDuration : bloodMoonForceDuration;
            long durationTicks = actualDuration * 60 * 20L;

            // Check if forced blood moon duration has expired
            if (elapsedTicks >= durationTicks) {
                return false;
            }

            return isDayOf; // Still active if duration hasn't expired
        }

        return isDayOf && isNight;
    }

    static long remainingBloodMoonTicks(boolean forced, long durationTicks, long worldTime,
                                        long forcedStartTimeMillis, long currentTimeMillis) {
        if (!forced) {
            return 13_000L + durationTicks - worldTime;
        }

        long elapsedMillis = Math.max(0L, currentTimeMillis - forcedStartTimeMillis);
        return durationTicks - elapsedMillis / 50L;
    }

    public double getHordeMultiplier() {
        return bmHordeMult;
    }

    public double getHealthMult() {
        return bmHealthMult;
    }

    public double getDamageMult() {
        return bmDamageMult;
    }

    public double getSpeedMult() {
        return bmSpeedMult;
    }

    public int getDefaultForceDuration() {
        return bloodMoonForceDuration;
    }

    /** Returns whole in-game days until the next natural Blood Moon; 0 means today/active. */
    public int getDaysUntilNextBloodMoon() {
        if (!bloodMoonEnabled) return 0;
        World world = getReferenceWorld();
        if (world == null) return 0;
        if (isActive(world)) return 0;

        long dayNumber = world.getFullTime() / 24000L;
        long remainder = Math.floorMod(dayNumber, (long) bloodMoonInterval);
        if (dayNumber > 0 && remainder == 0) return 0;
        return (int) (bloodMoonInterval - remainder);
    }

    /** Async-safe snapshot for PlaceholderAPI; refreshed only by the synchronous lifecycle task. */
    public int getCachedDaysUntilNextBloodMoon() {
        return cachedDaysUntilNextBloodMoon;
    }

    public boolean isPersisted() {
        return bloodMoonPersisted;
    }

    public boolean isForced() {
        return forcedBloodMoon;
    }

    /** Used by onEnable to decide whether to resume the MythicMobs blood-moon spawn loop. */
    public boolean wasPersistedOrForcedAtEnable() {
        return bloodMoonPersisted || forcedBloodMoon;
    }

    // === REPEATING TASK ===

    public void startTask() {
        // Bug C5 fix: remove the previous bossbar from all players before replacing the
        // reference. On /xa reload this method runs again; without this, each reload left an
        // orphaned bar stuck in every player's HUD (visible and a memory leak) until restart.
        if (bloodMoonBar != null) {
            bloodMoonBar.removeAll();
        }
        bloodMoonBar = Bukkit.createBossBar("Blood Moon", BarColor.RED, BarStyle.SEGMENTED_10);
        cachedDaysUntilNextBloodMoon = getDaysUntilNextBloodMoon();

        new BukkitRunnable() {
            @Override
            public void run() {
                cachedDaysUntilNextBloodMoon = getDaysUntilNextBloodMoon();
                if (Bukkit.getWorlds().isEmpty()) return;
                if (!bloodMoonEnabled) {
                    if (bloodMoonPersisted || forcedBloodMoon) {
                        bloodMoonPersisted = false;
                        persistedBloodMoonDay = -1;
                        forcedBloodMoon = false;
                        forcedBloodMoonStartTime = -1;
                        forcedBloodMoonDuration = -1;
                        bloodMoonWorldId = null;
                        save();
                        bloodMoonBar.removeAll();
                        signalBloodMoonEnd();
                    }
                    return;
                }

                World mainWorld = getReferenceWorld();
                if (mainWorld == null) {
                    // Give Multiverse-style world managers time to load the persisted anchor. If it
                    // never returns, terminate instead of silently transferring the event clock.
                    if ((bloodMoonPersisted || forcedBloodMoon) && ++missingReferenceWorldChecks >= 60) {
                        bloodMoonPersisted = false;
                        persistedBloodMoonDay = -1;
                        forcedBloodMoon = false;
                        forcedBloodMoonStartTime = -1;
                        forcedBloodMoonDuration = -1;
                        bloodMoonWorldId = null;
                        save();
                        bloodMoonBar.removeAll();
                        signalBloodMoonEnd();
                    }
                    return;
                }
                missingReferenceWorldChecks = 0;

                // Pre-blood-moon countdown warning (dawn broadcast, deduped per day).
                checkWarning(mainWorld);

                // Fix RC4: Detect real-time forced-blood-moon expiry HERE, before
                // calling isBloodMoonActive().  When duration runs out during nighttime,
                // isBloodMoonActive() returns false but the else-branch's daytime guard
                // (time < 13000 || time > 23000) never fires (task keeps time at ~14000).
                // Without this, forcedBloodMoon stays true as a zombie state and
                // onBloodMoonEnd() is never signalled to the MM tick loop.
                if (forcedBloodMoon && forcedBloodMoonStartTime != -1) {
                    long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
                    long actualDur = forcedBloodMoonDuration != -1 ? forcedBloodMoonDuration : bloodMoonForceDuration;
                    if (elapsedMs >= actualDur * 60_000L) {
                        forcedBloodMoon = false;
                        forcedBloodMoonStartTime = -1;
                        forcedBloodMoonDuration = -1;
                        bloodMoonPersisted = false;
                        persistedBloodMoonDay = -1;
                        bloodMoonWorldId = null;
                        save();
                        bloodMoonBar.removeAll();
                        plugin.debugLog("Forced blood moon expired by real-time duration — cleaning up.");
                        signalBloodMoonEnd();
                        return;
                    }
                }

                if (isActive(mainWorld)) {
                    // Bug C1 fix: fire the natural blood-moon "start" exactly once. isActive() returns
                    // true on the FIRST night tick of an interval day — before the old else-branch
                    // start code could ever run — so that code was unreachable and natural blood
                    // moons silently never broadcast, never persisted, and never triggered the
                    // MythicMobs spawn loop. Detect the transition here instead: active, but not yet
                    // persisted and not forced. Once persisted=true this won't fire again, and forced
                    // blood moons already ran onBloodMoonStart() from forceBloodMoon().
                    if (!bloodMoonPersisted && !forcedBloodMoon) {
                        long currentDay = mainWorld.getFullTime() / 24000L;
                        bloodMoonPersisted = true;
                        persistedBloodMoonDay = currentDay;
                        bloodMoonWorldId = mainWorld.getUID();
                        save();

                        MythicMobsManager mm = plugin.getMythicMobsManager();
                        if (mm != null) {
                            mm.onBloodMoonStart();
                        }

                        plugin.getLogger().info("Natural blood moon started on day " + currentDay);
                        plugin.debugLog("Blood moon persistence: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay);

                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (plugin.isWorldEnabled(p.getWorld()) || plugin.isLobbyWorld(p.getWorld())) {
                                p.sendMessage(plugin.getMessages().get("bloodmoon.natural-start"));
                            }
                        }
                        playStartSound();
                    }

                    long time = mainWorld.getTime();

                    if (forcedBloodMoon && (time < 13000 || time > 23000)) {
                        mainWorld.setTime(14000);
                        time = 14000;
                    }

                    // CRITICAL FIX: Use actual command duration, not config default
                    long actualDuration = forcedBloodMoonDuration != -1 ? forcedBloodMoonDuration : bloodMoonForceDuration;
                    long durationTicks = forcedBloodMoon ? actualDuration * 60 * 20L : 10_000L;
                    long remaining = remainingBloodMoonTicks(
                            forcedBloodMoon,
                            durationTicks,
                            time,
                            forcedBloodMoonStartTime,
                            System.currentTimeMillis()
                    );

                    if (remaining > 0) {
                        // CRITICAL FIX: Calculate progress for forced blood moon using real time
                        double progress;
                        if (forcedBloodMoon && forcedBloodMoonStartTime != -1) {
                            long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
                            long elapsedTicks = elapsedMs / 50;
                            progress = 1.0 - ((double) elapsedTicks / durationTicks);
                        } else {
                            progress = (double) remaining / durationTicks;
                        }
                        bloodMoonBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

                        // CRITICAL FIX: Calculate remaining time for forced blood moon using real time
                        long totalSeconds;
                        if (forcedBloodMoon && forcedBloodMoonStartTime != -1) {
                            long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
                            long elapsedSeconds = elapsedMs / 1000;
                            long durationSeconds = actualDuration * 60; // Use actual duration
                            totalSeconds = Math.max(0, durationSeconds - elapsedSeconds);
                        } else {
                            totalSeconds = remaining / 20;
                        }
                        long minutes = totalSeconds / 60;
                        long seconds = totalSeconds % 60;
                        String timeStr = String.format("%02d:%02d", minutes, seconds);

                        bloodMoonBar.setTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', bloodMoonTitle.replace("%time%", timeStr)));
                        // CRITICAL FIX: Proper bossbar lifecycle management
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (plugin.isWorldEnabled(p.getWorld()) || plugin.isLobbyWorld(p.getWorld())) {
                                if (!bloodMoonBar.getPlayers().contains(p)) {
                                    bloodMoonBar.addPlayer(p);
                                }
                            } else {
                                if (bloodMoonBar.getPlayers().contains(p)) {
                                    bloodMoonBar.removePlayer(p);
                                }
                            }
                        }
                    } else {
                        // Blood moon ended (in-game time check).
                        if (!bloodMoonBar.getPlayers().isEmpty()) {
                            bloodMoonBar.removeAll();
                        }
                        // Fix RC2: Gate on (bloodMoonPersisted || forcedBloodMoon).
                        // /xa forcebloodmoon sets forcedBloodMoon=true but NOT bloodMoonPersisted=true,
                        // so the old guard silently skipped onBloodMoonEnd() for every forced blood
                        // moon, permanently orphaning the MM spawn tick loop.
                        if (bloodMoonPersisted || forcedBloodMoon) {
                            bloodMoonPersisted = false;
                            persistedBloodMoonDay = -1;
                            forcedBloodMoon = false;
                            forcedBloodMoonStartTime = -1;
                            forcedBloodMoonDuration = -1;
                            bloodMoonWorldId = null;
                            save();
                            plugin.debugLog("Blood moon ended (in-game time) - persistence reset.");
                            signalBloodMoonEnd();
                        }
                    }
                } else {
                    // CRITICAL FIX: Force bossbar cleanup when blood moon is not active or when it's day
                    if (!bloodMoonBar.getPlayers().isEmpty()) {
                        bloodMoonBar.removeAll();
                        plugin.debugLog("Force cleanup: blood moon not active or it's day, removing bossbar");
                    }

                    // CRITICAL FIX: Reset persistence if it's day time (someone used /time set day)
                    long time = mainWorld.getTime();
                    if (time < 13000 || time > 23000) {
                        if (bloodMoonPersisted || forcedBloodMoon) {
                            bloodMoonPersisted = false;
                            persistedBloodMoonDay = -1;
                            forcedBloodMoon = false;
                            forcedBloodMoonStartTime = -1;
                            forcedBloodMoonDuration = -1;
                            bloodMoonWorldId = null;
                            save();
                            plugin.debugLog("Day time detected - blood moon persistence reset");
                            // Fix RC3: Signal MM manager here too.  Previously onBloodMoonEnd()
                            // was missing from this path, so the spawn tick loop was never told
                            // to stop when a blood moon was wiped by a /time set day command.
                            signalBloodMoonEnd();
                        }
                    }

                    // Bug C1 fix: natural blood-moon START is now handled in the isActive() branch
                    // above. The block that used to live here was unreachable (isActive() returns
                    // true on the first night tick of an interval day, so this else is never entered
                    // during a natural blood moon). Only the day-time persistence cleanup above
                    // remains in this branch.
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // === BOSSBAR LIFECYCLE ===

    // CRITICAL FIX: clean up bossbars for a specific player (called from onPlayerJoin)
    public void cleanupBossbarForPlayer(Player player) {
        // Remove from blood moon bossbar
        if (bloodMoonBar != null && bloodMoonBar.getPlayers().contains(player)) {
            bloodMoonBar.removePlayer(player);
        }
    }

    // CRITICAL FIX: Clean up blood moon bossbar when player quits
    public void onPlayerQuit(Player player) {
        if (bloodMoonBar != null && bloodMoonBar.getPlayers().contains(player)) {
            bloodMoonBar.removePlayer(player);
        }
    }

    public void removeBossBar() {
        if (bloodMoonBar != null) {
            bloodMoonBar.removeAll();
        }
    }

    /**
     * Single end-of-blood-moon hook used by every termination path (real-time forced expiry,
     * in-game-time end, day-time reset, and the /xa stopbloodmoon command). Stops the MythicMobs
     * spawn loop and — unless {@code bloodmoon.despawn-on-end} is disabled — removes the zombies and
     * Mutants the event spawned so they don't linger once it's over.
     */
    private void signalBloodMoonEnd() {
        MythicMobsManager mm = plugin.getMythicMobsManager();
        if (mm != null) {
            mm.onBloodMoonEnd();
        }
        if (plugin.getConfig().getBoolean("bloodmoon.despawn-on-end", true)) {
            if (plugin.getUtils() != null) {
                plugin.getUtils().despawnBloodMoonZombies();
            }
            if (mm != null) {
                mm.despawnActiveMutants();
            }
        }
    }

    // === PRE-BLOOD-MOON WARNINGS ===

    /**
     * Called every second from the lifecycle task. On the dawn (world time < 1000) of each of the
     * last {@code warningDaysBefore} days before a natural blood moon, broadcasts the configurable
     * warning exactly once per day. {@code lastWarnedDay} is the authoritative dedupe (persisted),
     * so lag skipping ticks can't double-fire, and forced blood moons never trigger warnings.
     */
    private void checkWarning(World world) {
        if (!warningEnabled || !bloodMoonEnabled || forcedBloodMoon) return;

        long dayNumber = world.getFullTime() / 24000L;
        if (dayNumber == lastWarnedDay) return;        // already warned today
        if (world.getTime() >= 1000) return;           // only fire at/near dawn

        int daysUntil = daysUntilNaturalBloodMoon(dayNumber);
        if (daysUntil < 1 || daysUntil > warningDaysBefore) return;

        lastWarnedDay = dayNumber;
        save();

        plugin.getLogger().info("Blood moon warning broadcast: " + daysUntil + " day(s) remaining (day " + dayNumber + ")");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.isWorldEnabled(p.getWorld()) || plugin.isLobbyWorld(p.getWorld())) {
                sendWarningToPlayer(p, daysUntil);
            }
        }
    }

    /**
     * Days until the next natural blood moon day (a day D > dayNumber with D % interval == 0).
     * On the blood moon day itself this returns the FULL interval (the next one) — correct: there
     * is no "0 days" warning, the existing natural-start message covers day-of.
     */
    private int daysUntilNaturalBloodMoon(long dayNumber) {
        long rem = dayNumber % bloodMoonInterval;
        return (int) (rem == 0 ? bloodMoonInterval : bloodMoonInterval - rem);
    }

    /** Sends the full warning package (chat lines + optional title + optional sound) to one player. */
    private void sendWarningToPlayer(Player p, int daysUntil) {
        // Chat: exact per-day list, falling back to the generic {0}-placeholder message.
        List<String> lines = plugin.getMessages().getList("bloodmoon.warning.days." + daysUntil);
        if (lines.isEmpty()) {
            lines = plugin.getMessages().getList("bloodmoon.warning.generic", daysUntil);
        }
        for (String line : lines) {
            p.sendMessage(line);
        }

        // Title
        if (warningTitleEnabled) {
            p.showTitle(Title.title(
                    plugin.getMessages().getComponent("bloodmoon.warning.title", daysUntil),
                    plugin.getMessages().getComponent("bloodmoon.warning.subtitle", daysUntil),
                    Title.Times.times(
                            Duration.ofMillis(warningTitleFadeIn * 50L),
                            Duration.ofMillis(warningTitleStay * 50L),
                            Duration.ofMillis(warningTitleFadeOut * 50L))));
        }

        // Sound
        if (warningSoundEnabled && warningSound != null) {
            p.playSound(p.getLocation(), warningSound, warningSoundVolume, warningSoundPitch);
        }
    }

    private Sound parseSound(String soundName, String configPath) {
        if (soundName == null || soundName.isBlank()) return null;
        try {
            // Accept both Bukkit enum names and namespaced-style dotted names.
            return Sound.valueOf(soundName.trim().toUpperCase().replace('.', '_'));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid " + configPath + ": '" + soundName
                    + "' - this sound will be skipped");
            return null;
        }
    }

    private void playStartSound() {
        if (!startSoundEnabled || startSound == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isWorldEnabled(player.getWorld()) || plugin.isLobbyWorld(player.getWorld())) {
                player.playSound(player.getLocation(), startSound, startSoundVolume, startSoundPitch);
            }
        }
    }

    /**
     * Join replay: if today's dawn warning already broadcast (dayNumber == lastWarnedDay), replay
     * it to the joining player ~2s after join (past the join-message spam). Players joining before
     * dawn get nothing here — they'll see the live broadcast instead.
     */
    public void sendWarningOnJoinIfApplicable(Player player) {
        if (!warningEnabled || !bloodMoonEnabled || forcedBloodMoon) return;

        World mainWorld = Bukkit.getWorlds().stream()
                .filter(plugin::isWorldEnabled)
                .findFirst()
                .orElse(null);
        if (mainWorld == null) return;

        long dayNumber = mainWorld.getFullTime() / 24000L;
        if (dayNumber != lastWarnedDay) return;        // no broadcast fired today

        int daysUntil = daysUntilNaturalBloodMoon(dayNumber);
        if (daysUntil < 1 || daysUntil > warningDaysBefore) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()
                    && (plugin.isWorldEnabled(player.getWorld()) || plugin.isLobbyWorld(player.getWorld()))) {
                sendWarningToPlayer(player, daysUntil);
            }
        }, 40L);
    }

    // === COMMANDS ===

    /** /xa forcebloodmoon — the world has already been resolved and the duration validated by the command. */
    public void forceBloodMoon(CommandSender sender, World world, int duration) {
        if (!bloodMoonEnabled) {
            sender.sendMessage("§cBlood Moons are disabled in config.yml.");
            return;
        }
        forcedBloodMoon = true;
        forcedBloodMoonStartTime = System.currentTimeMillis(); // CRITICAL FIX: Track start time
        forcedBloodMoonDuration = duration; // CRITICAL FIX: Store actual duration
        bloodMoonWorldId = world.getUID();

        // CRITICAL FIX: Save forced blood moon state
        save();

        playStartSound();

        // --- MythicMobs: guaranteed Mutant + tick loop ---
        MythicMobsManager mm = plugin.getMythicMobsManager();
        if (mm != null) {
            mm.onBloodMoonStart();
        }

        sender.sendMessage(plugin.getMessages().get("bloodmoon.force-start") + " §7(§e" + duration + " minutes§7)");

        long time = world.getTime();
        if (time < 13000 || time > 23000) {
            world.setTime(13000);
            sender.sendMessage(plugin.getMessages().get("bloodmoon.force-time-set"));
        }

        plugin.getLogger().info("Blood moon force started by " + sender.getName() + " for " + duration + " minutes");
    }

    /** /xa stopbloodmoon */
    public void stopBloodMoon(CommandSender sender) {
        // CRITICAL FIX: Stop blood moon and clean up
        if (bloodMoonPersisted || forcedBloodMoon) {
            World world = getReferenceWorld();
            bloodMoonPersisted = false;
            persistedBloodMoonDay = -1;
            forcedBloodMoon = false;
            forcedBloodMoonStartTime = -1; // CRITICAL FIX: Reset forced start time
            forcedBloodMoonDuration = -1; // CRITICAL FIX: Reset forced duration
            bloodMoonWorldId = null;
            save();

            // --- MythicMobs: stop tick loop + despawn blood-moon entities ---
            signalBloodMoonEnd();

            // CRITICAL FIX: Force bossbar cleanup
            if (bloodMoonBar != null && !bloodMoonBar.getPlayers().isEmpty()) {
                bloodMoonBar.removeAll();
            }

            // CRITICAL FIX: Set time to day to prevent immediate restart
            if (world == null) {
                sender.sendMessage("§cNo enabled world is currently loaded.");
                return;
            }
            world.setTime(1000); // Set to day time
            sender.sendMessage("§7Time set to day to prevent restart.");

            sender.sendMessage("§cBlood moon stopped manually.");
            plugin.getLogger().info("Blood moon stopped by " + sender.getName() + " - time set to day");
            plugin.debugLog("Manual blood moon stop - persistence reset, time set to day.");
        } else {
            sender.sendMessage("§eNo blood moon is currently active.");
        }
    }
}
