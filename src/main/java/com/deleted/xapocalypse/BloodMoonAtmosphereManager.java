package com.deleted.xapocalypse;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

final class BloodMoonAtmosphereManager {

    private static final String STATE_PATH = "bloodmoon.atmosphere";
    private static final double RARE_SCARE_CHANCE = 0.01;
    private static final String RARE_SCARE_SOUND = "minecraft:entity.creeper.primed";

    private final xApocalypse plugin;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final Function<String, String> soundResolver;
    private final Runnable persistState;

    private boolean enabled;
    private boolean weatherEnabled;
    private boolean forceStorm;
    private boolean forceThunder;
    private boolean restorePreviousWeather;
    private boolean soundsEnabled;
    private long minimumDelayMillis;
    private long maximumDelayMillis;
    private float soundVolume;
    private float soundPitch;
    private List<String> resolvedSoundKeys = List.of();

    private WeatherSnapshot snapshot;
    private World activeWorld;
    private long nextSoundAtMillis = Long.MAX_VALUE;
    private boolean rareScareUsed;

    BloodMoonAtmosphereManager(xApocalypse plugin, Runnable persistState) {
        this(plugin, System::currentTimeMillis, ThreadLocalRandom.current(),
                BloodMoonAtmosphereManager::resolveSoundKey, persistState);
    }

    BloodMoonAtmosphereManager(xApocalypse plugin,
                               LongSupplier clock,
                               RandomGenerator random,
                               Function<String, String> soundResolver,
                               Runnable persistState) {
        this.plugin = plugin;
        this.clock = clock;
        this.random = random;
        this.soundResolver = soundResolver;
        this.persistState = persistState;
    }

    void loadConfig(FileConfiguration config) {
        enabled = config.getBoolean("bloodmoon.atmosphere.enabled", true);
        weatherEnabled = config.getBoolean("bloodmoon.atmosphere.weather.enabled", true);
        forceStorm = config.getBoolean("bloodmoon.atmosphere.weather.storm", true);
        forceThunder = config.getBoolean("bloodmoon.atmosphere.weather.thunder", true);
        restorePreviousWeather = config.getBoolean(
                "bloodmoon.atmosphere.weather.restore-previous-weather", true);

        soundsEnabled = config.getBoolean("bloodmoon.atmosphere.sounds.enabled", true);
        long minimumSeconds = Math.max(1L,
                config.getLong("bloodmoon.atmosphere.sounds.min-interval-seconds", 60L));
        long maximumSeconds = Math.max(minimumSeconds,
                config.getLong("bloodmoon.atmosphere.sounds.max-interval-seconds", 120L));
        minimumDelayMillis = minimumSeconds * 1_000L;
        maximumDelayMillis = maximumSeconds * 1_000L;
        soundVolume = Math.max(0.0f,
                (float) config.getDouble("bloodmoon.atmosphere.sounds.volume", 0.8));
        soundPitch = clamp((float) config.getDouble(
                "bloodmoon.atmosphere.sounds.pitch", 0.7), 0.5f, 2.0f);

        List<String> validSounds = new ArrayList<>();
        for (String configuredName : config.getStringList("bloodmoon.atmosphere.sounds.names")) {
            String resolved = configuredName == null ? null : soundResolver.apply(configuredName);
            if (resolved == null) {
                plugin.getLogger().warning("Invalid Blood Moon atmosphere sound: '"
                        + configuredName + "' - skipping it.");
            } else {
                validSounds.add(resolved);
            }
        }
        resolvedSoundKeys = Collections.unmodifiableList(validSounds);
    }

    void loadState(FileConfiguration data) {
        String worldId = data.getString(STATE_PATH + ".snapshot.world-id");
        snapshot = null;
        if (worldId != null) {
            try {
                snapshot = new WeatherSnapshot(
                        UUID.fromString(worldId),
                        data.getBoolean(STATE_PATH + ".snapshot.storm"),
                        data.getBoolean(STATE_PATH + ".snapshot.thunder"),
                        data.getInt(STATE_PATH + ".snapshot.weather-duration"),
                        data.getInt(STATE_PATH + ".snapshot.thunder-duration"));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid Blood Moon atmosphere world UUID; ignoring saved weather.");
            }
        }
        rareScareUsed = data.getBoolean(STATE_PATH + ".rare-scare-used", false);
    }

    void writeState(FileConfiguration data) {
        if (snapshot == null) {
            data.set(STATE_PATH + ".snapshot", null);
        } else {
            data.set(STATE_PATH + ".snapshot.world-id", snapshot.worldId().toString());
            data.set(STATE_PATH + ".snapshot.storm", snapshot.storm());
            data.set(STATE_PATH + ".snapshot.thunder", snapshot.thunder());
            data.set(STATE_PATH + ".snapshot.weather-duration", snapshot.weatherDuration());
            data.set(STATE_PATH + ".snapshot.thunder-duration", snapshot.thunderDuration());
        }
        data.set(STATE_PATH + ".rare-scare-used", rareScareUsed);
    }

    void start(World world) {
        if (world == null || snapshot != null && snapshot.worldId().equals(world.getUID())) {
            return;
        }

        snapshot = new WeatherSnapshot(
                world.getUID(),
                world.hasStorm(),
                world.isThundering(),
                world.getWeatherDuration(),
                world.getThunderDuration());
        activeWorld = world;
        rareScareUsed = false;
        applyWeather();
        scheduleNextSound();
        persistState.run();
    }

    void resume(World world) {
        if (world == null) return;

        activeWorld = world;
        if (snapshot == null || !snapshot.worldId().equals(world.getUID())) {
            snapshot = new WeatherSnapshot(
                    world.getUID(),
                    world.hasStorm(),
                    world.isThundering(),
                    world.getWeatherDuration(),
                    world.getThunderDuration());
            persistState.run();
        }
        applyWeather();
        scheduleNextSound();
    }

    void heartbeat(World world) {
        if (world == null || snapshot == null || !snapshot.worldId().equals(world.getUID())) return;
        activeWorld = world;
        applyWeather();
        playDueSound();
    }

    void end() {
        restoreWeather();
        snapshot = null;
        activeWorld = null;
        nextSoundAtMillis = Long.MAX_VALUE;
        rareScareUsed = false;
        persistState.run();
    }

    void suspend() {
        restoreWeather();
        activeWorld = null;
        nextSoundAtMillis = Long.MAX_VALUE;
    }

    private void applyWeather() {
        if (!enabled || !weatherEnabled || activeWorld == null) return;

        if (forceStorm && !activeWorld.hasStorm()) {
            activeWorld.setStorm(true);
        }
        if (forceThunder && !activeWorld.isThundering()) {
            activeWorld.setThundering(true);
        }
    }

    private void restoreWeather() {
        if (!restorePreviousWeather || snapshot == null || activeWorld == null
                || !snapshot.worldId().equals(activeWorld.getUID())) {
            return;
        }

        activeWorld.setStorm(snapshot.storm());
        activeWorld.setThundering(snapshot.thunder());
        activeWorld.setWeatherDuration(snapshot.weatherDuration());
        activeWorld.setThunderDuration(snapshot.thunderDuration());
    }

    private void scheduleNextSound() {
        if (!enabled || !soundsEnabled || resolvedSoundKeys.isEmpty()) {
            nextSoundAtMillis = Long.MAX_VALUE;
            return;
        }

        long delay = minimumDelayMillis == maximumDelayMillis
                ? minimumDelayMillis
                : random.nextLong(minimumDelayMillis, maximumDelayMillis + 1L);
        nextSoundAtMillis = clock.getAsLong() + delay;
    }

    private void playDueSound() {
        if (!enabled || !soundsEnabled || resolvedSoundKeys.isEmpty()
                || clock.getAsLong() < nextSoundAtMillis) {
            return;
        }

        List<Player> players = activeWorld.getPlayers();
        String soundKey = resolvedSoundKeys.get(random.nextInt(resolvedSoundKeys.size()));
        for (Player player : players) {
            player.playSound(player.getLocation(), soundKey, SoundCategory.AMBIENT,
                    soundVolume, soundPitch);
        }

        if (!rareScareUsed && !players.isEmpty() && random.nextDouble() < RARE_SCARE_CHANCE) {
            playRareScare(players.get(random.nextInt(players.size())));
            rareScareUsed = true;
            persistState.run();
        }

        scheduleNextSound();
    }

    private void playRareScare(Player player) {
        Location location = player.getLocation().clone();
        Vector behind = location.getDirection().setY(0.0);
        if (behind.lengthSquared() < 0.0001) {
            behind.setZ(-1.0);
        } else {
            behind.normalize().multiply(-3.0);
        }
        location.add(behind);
        player.playSound(location, RARE_SCARE_SOUND, SoundCategory.HOSTILE, 0.8f, 1.0f);
    }

    private static String resolveSoundKey(String configuredName) {
        try {
            String enumName = configuredName.trim().toUpperCase(Locale.ROOT).replace('.', '_');
            return Sound.valueOf(enumName).getKey().toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    long minimumDelayMillis() {
        return minimumDelayMillis;
    }

    long maximumDelayMillis() {
        return maximumDelayMillis;
    }

    List<String> resolvedSoundKeys() {
        return resolvedSoundKeys;
    }

    long nextSoundAtMillis() {
        return nextSoundAtMillis;
    }

    boolean rareScareUsed() {
        return rareScareUsed;
    }

    boolean hasSnapshotFor(UUID worldId) {
        return snapshot != null && snapshot.worldId().equals(worldId);
    }

    private record WeatherSnapshot(UUID worldId,
                                   boolean storm,
                                   boolean thunder,
                                   int weatherDuration,
                                   int thunderDuration) {
    }
}
