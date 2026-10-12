package com.deleted.xapocalypse;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BloodMoonAtmosphereManagerTest {

    private static final UUID WORLD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private xApocalypse plugin;
    private World world;
    private RandomGenerator random;
    private MutableClock clock;
    private AtomicInteger saves;
    private BloodMoonAtmosphereManager manager;

    private AtomicBoolean storm;
    private AtomicBoolean thunder;
    private AtomicInteger weatherDuration;
    private AtomicInteger thunderDuration;

    @BeforeEach
    void setUp() {
        plugin = mock(xApocalypse.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BloodMoonAtmosphereManagerTest"));

        storm = new AtomicBoolean(false);
        thunder = new AtomicBoolean(false);
        weatherDuration = new AtomicInteger(2400);
        thunderDuration = new AtomicInteger(1200);

        world = mock(World.class);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.hasStorm()).thenAnswer(invocation -> storm.get());
        when(world.isThundering()).thenAnswer(invocation -> thunder.get());
        when(world.getWeatherDuration()).thenAnswer(invocation -> weatherDuration.get());
        when(world.getThunderDuration()).thenAnswer(invocation -> thunderDuration.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            storm.set(invocation.getArgument(0));
            return null;
        }).when(world).setStorm(org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.doAnswer(invocation -> {
            thunder.set(invocation.getArgument(0));
            return null;
        }).when(world).setThundering(org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.doAnswer(invocation -> {
            weatherDuration.set(invocation.getArgument(0));
            return null;
        }).when(world).setWeatherDuration(org.mockito.ArgumentMatchers.anyInt());
        org.mockito.Mockito.doAnswer(invocation -> {
            thunderDuration.set(invocation.getArgument(0));
            return null;
        }).when(world).setThunderDuration(org.mockito.ArgumentMatchers.anyInt());

        random = mock(RandomGenerator.class);
        when(random.nextLong(anyLong(), anyLong())).thenReturn(60_000L);
        clock = new MutableClock();
        saves = new AtomicInteger();

        Function<String, String> soundResolver = name -> switch (name) {
            case "AMBIENT_CAVE" -> "minecraft:ambient.cave";
            case "ENTITY_WITHER_AMBIENT" -> "minecraft:entity.wither.ambient";
            default -> null;
        };
        manager = new BloodMoonAtmosphereManager(
                plugin, clock, random, soundResolver, saves::incrementAndGet);
    }

    @Test
    void startCapturesWeatherOnceAndEndRestoresIt() {
        manager.loadConfig(config(true, true, true));

        manager.start(world);
        manager.start(world);

        assertEquals(true, storm.get());
        assertEquals(true, thunder.get());
        verify(world).setStorm(true);
        verify(world).setThundering(true);

        manager.end();

        assertEquals(false, storm.get());
        assertEquals(false, thunder.get());
        assertEquals(2400, weatherDuration.get());
        assertEquals(1200, thunderDuration.get());
        assertEquals(2, saves.get());
    }

    @Test
    void heartbeatOnlyReassertsWeatherThatChanged() {
        manager.loadConfig(config(true, true, true));
        manager.start(world);
        org.mockito.Mockito.clearInvocations(world);

        manager.heartbeat(world);

        verify(world, never()).setStorm(org.mockito.ArgumentMatchers.anyBoolean());
        verify(world, never()).setThundering(org.mockito.ArgumentMatchers.anyBoolean());

        storm.set(false);
        manager.heartbeat(world);

        verify(world).setStorm(true);
        verify(world, never()).setThundering(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void invalidIntervalsClampAndInvalidSoundsAreSkipped() {
        YamlConfiguration config = config(true, true, true);
        config.set("bloodmoon.atmosphere.sounds.min-interval-seconds", 0);
        config.set("bloodmoon.atmosphere.sounds.max-interval-seconds", -20);
        config.set("bloodmoon.atmosphere.sounds.names", List.of("BAD", "AMBIENT_CAVE"));

        manager.loadConfig(config);

        assertEquals(1_000L, manager.minimumDelayMillis());
        assertEquals(1_000L, manager.maximumDelayMillis());
        assertEquals(List.of("minecraft:ambient.cave"), manager.resolvedSoundKeys());
    }

    @Test
    void dueCuePlaysOnceForEveryPlayerInTheActiveWorld() {
        Player first = mock(Player.class);
        Player second = mock(Player.class);
        when(first.getLocation()).thenReturn(new Location(world, 0.0, 64.0, 0.0));
        when(second.getLocation()).thenReturn(new Location(world, 10.0, 64.0, 10.0));
        when(world.getPlayers()).thenReturn(List.of(first, second));
        when(random.nextDouble()).thenReturn(0.5);
        manager.loadConfig(config(true, true, true));
        manager.start(world);

        clock.set(60_000L);
        manager.heartbeat(world);

        verify(first).playSound(any(Location.class), eq("minecraft:ambient.cave"),
                eq(SoundCategory.AMBIENT), eq(0.8f), eq(0.7f));
        verify(second).playSound(any(Location.class), eq("minecraft:ambient.cave"),
                eq(SoundCategory.AMBIENT), eq(0.8f), eq(0.7f));
        assertEquals(120_000L, manager.nextSoundAtMillis());
    }

    @Test
    void rareScareTargetsOnePlayerAtMostOncePerEvent() {
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 0.0, 64.0, 0.0, 0.0f, 0.0f));
        when(world.getPlayers()).thenReturn(List.of(player));
        when(random.nextDouble()).thenReturn(0.009);
        manager.loadConfig(config(true, true, true));
        manager.start(world);

        clock.set(60_000L);
        manager.heartbeat(world);
        clock.set(120_000L);
        manager.heartbeat(world);

        verify(player, org.mockito.Mockito.times(1)).playSound(
                any(Location.class), eq("minecraft:entity.creeper.primed"),
                eq(SoundCategory.HOSTILE), anyFloat(), anyFloat());
        assertTrue(manager.rareScareUsed());
    }

    @Test
    void scareDoesNotTriggerAtTheOnePercentBoundaryOrWithoutPlayers() {
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 0.0, 64.0, 0.0));
        when(world.getPlayers()).thenReturn(List.of(player), List.of());
        when(random.nextDouble()).thenReturn(0.01, 0.0);
        manager.loadConfig(config(true, true, true));
        manager.start(world);

        clock.set(60_000L);
        manager.heartbeat(world);
        clock.set(120_000L);
        manager.heartbeat(world);

        verify(player, never()).playSound(any(Location.class),
                eq("minecraft:entity.creeper.primed"), eq(SoundCategory.HOSTILE),
                anyFloat(), anyFloat());
        assertFalse(manager.rareScareUsed());
    }

    @Test
    void savedScareStateAndWeatherSnapshotSurviveReload() {
        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(new Location(world, 0.0, 64.0, 0.0));
        when(world.getPlayers()).thenReturn(List.of(player));
        when(random.nextDouble()).thenReturn(0.0);
        manager.loadConfig(config(true, true, true));
        manager.start(world);
        clock.set(60_000L);
        manager.heartbeat(world);
        YamlConfiguration data = new YamlConfiguration();
        manager.writeState(data);

        BloodMoonAtmosphereManager reloaded = new BloodMoonAtmosphereManager(
                plugin, clock, random, name -> "minecraft:ambient.cave", () -> { });
        reloaded.loadState(data);

        assertTrue(reloaded.hasSnapshotFor(WORLD_ID));
        assertTrue(reloaded.rareScareUsed());
    }

    @Test
    void disablingWeatherOnReloadRestoresWithoutDiscardingTheSnapshot() {
        manager.loadConfig(config(true, true, true));
        manager.start(world);
        assertTrue(storm.get());
        assertTrue(thunder.get());

        YamlConfiguration disabled = config(true, true, true);
        disabled.set("bloodmoon.atmosphere.weather.enabled", false);
        manager.loadConfig(disabled);

        assertFalse(storm.get());
        assertFalse(thunder.get());
        assertTrue(manager.hasSnapshotFor(WORLD_ID));

        manager.loadConfig(config(true, true, true));
        manager.resume(world);

        assertTrue(storm.get());
        assertTrue(thunder.get());
    }

    private static YamlConfiguration config(boolean atmosphere, boolean storm, boolean thunder) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("bloodmoon.atmosphere.enabled", atmosphere);
        config.set("bloodmoon.atmosphere.weather.enabled", true);
        config.set("bloodmoon.atmosphere.weather.storm", storm);
        config.set("bloodmoon.atmosphere.weather.thunder", thunder);
        config.set("bloodmoon.atmosphere.weather.restore-previous-weather", true);
        config.set("bloodmoon.atmosphere.sounds.enabled", true);
        config.set("bloodmoon.atmosphere.sounds.min-interval-seconds", 60);
        config.set("bloodmoon.atmosphere.sounds.max-interval-seconds", 120);
        config.set("bloodmoon.atmosphere.sounds.volume", 0.8);
        config.set("bloodmoon.atmosphere.sounds.pitch", 0.7);
        config.set("bloodmoon.atmosphere.sounds.names", List.of("AMBIENT_CAVE"));
        return config;
    }

    private static final class MutableClock implements java.util.function.LongSupplier {
        private long value;

        void set(long value) {
            this.value = value;
        }

        @Override
        public long getAsLong() {
            return value;
        }
    }
}
