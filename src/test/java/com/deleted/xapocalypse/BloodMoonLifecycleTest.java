package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BloodMoonLifecycleTest {

    private static final UUID WORLD_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER_WORLD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path tempDir;

    private xApocalypse plugin;
    private BloodMoonAtmosphereManager atmosphere;
    private World world;
    private YamlConfiguration config;
    private MessageManager messages;

    @BeforeEach
    void setUp() {
        plugin = mock(xApocalypse.class);
        atmosphere = mock(BloodMoonAtmosphereManager.class);
        world = mock(World.class);
        messages = mock(MessageManager.class);

        when(plugin.getLogger()).thenReturn(Logger.getLogger("BloodMoonLifecycleTest"));
        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.isWorldEnabled(world)).thenReturn(true);
        when(world.getUID()).thenReturn(WORLD_ID);
        when(world.getTime()).thenReturn(14_000L);
        when(world.getFullTime()).thenReturn(4L * 24_000L + 14_000L);

        config = new YamlConfiguration();
        config.set("bloodmoon.enabled", true);
        config.set("bloodmoon.interval-days", 4);
        config.set("bloodmoon.start-sound.enabled", false);
        config.set("bloodmoon.warning.enabled", false);
        config.set("bloodmoon.warning.sound.enabled", false);
        config.set("bloodmoon.despawn-on-end", false);
        when(plugin.getConfig()).thenReturn(config);
    }

    @Test
    void loadAndSaveDelegateAtmosphereState() {
        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);

        manager.load(tempDir.toFile());
        manager.loadConfigValues(config);
        manager.save();
        manager.close();

        verify(atmosphere).loadState(any(YamlConfiguration.class));
        verify(atmosphere).loadConfig(config);
        verify(atmosphere).writeState(any(YamlConfiguration.class));
    }

    @Test
    void forcedStartStartsAtmosphereInTheCommandWorld() {
        BloodMoonManager manager = loadedManager();
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("tester");
        when(messages.get("bloodmoon.force-start")).thenReturn("started");

        manager.forceBloodMoon(sender, world, 2);
        manager.close();

        verify(atmosphere).start(world);
        assertTrue(manager.isForced());
    }

    @Test
    void naturalStartStartsAtmosphereInTheReferenceWorld() {
        BloodMoonManager manager = loadedManager();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            manager.beginNaturalBloodMoon(world, 4L);
        }
        manager.close();

        verify(atmosphere).start(world);
        assertTrue(manager.isPersisted());
    }

    @Test
    void persistedEventResumesWithoutStartingAgain() throws Exception {
        YamlConfiguration data = new YamlConfiguration();
        data.set("bloodmoon.persisted", true);
        data.set("bloodmoon.persisted-day", 4L);
        data.set("bloodmoon.world-id", WORLD_ID.toString());
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());
        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        manager.loadConfigValues(config);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(world);
            manager.resumeAtmosphereIfActive();
        }
        manager.close();

        verify(atmosphere).resume(world);
        verify(atmosphere, never()).start(any());
    }

    @Test
    void centralizedEndAndShutdownReachAtmosphereCleanup() {
        BloodMoonManager manager = loadedManager();

        manager.signalBloodMoonEnd();
        manager.suspendAtmosphere();
        manager.close();

        verify(atmosphere).end();
        verify(atmosphere).suspend();
    }

    @Test
    void invalidActiveWorldIdClearsThePersistedEvent() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.set("bloodmoon.world-id", "not-a-uuid");
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());
        when(atmosphere.isCurrentStateFormat()).thenReturn(true);

        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        manager.close();

        assertFalse(manager.isPersisted());
        assertFalse(manager.isForced());
    }

    @Test
    void missingActiveWorldIdInCurrentStateClearsThePersistedEvent() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.set("bloodmoon.world-id", null);
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());
        when(atmosphere.isCurrentStateFormat()).thenReturn(true);

        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        manager.close();

        assertFalse(manager.isPersisted());
    }

    @Test
    void mismatchedAtmosphereSnapshotClearsThePersistedEvent() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());
        when(atmosphere.isCurrentStateFormat()).thenReturn(true);
        when(atmosphere.snapshotWorldId()).thenReturn(OTHER_WORLD_ID);

        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        manager.close();

        assertFalse(manager.isPersisted());
    }

    @Test
    void currentActiveStateWithoutAWeatherSnapshotIsCleared() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());
        when(atmosphere.isCurrentStateFormat()).thenReturn(true);
        when(atmosphere.snapshotWorldId()).thenReturn(null);

        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        manager.close();

        assertFalse(manager.isPersisted());
    }

    @Test
    void currentActiveStateWithMalformedSnapshotUuidIsCleared() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.set("bloodmoon.atmosphere.state-version", 2);
        data.set("bloodmoon.atmosphere.snapshot.world-id", "not-a-uuid");
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());

        BloodMoonManager manager = new BloodMoonManager(plugin);
        manager.load(tempDir.toFile());
        manager.close();

        assertFalse(manager.isPersisted());
    }

    @Test
    void versionOneActiveStateWithoutClearWeatherTimerIsCleared() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.set("bloodmoon.atmosphere.state-version", 1);
        data.set("bloodmoon.atmosphere.snapshot.world-id", WORLD_ID.toString());
        data.set("bloodmoon.atmosphere.snapshot.storm", false);
        data.set("bloodmoon.atmosphere.snapshot.thunder", false);
        data.set("bloodmoon.atmosphere.snapshot.weather-duration", 1);
        data.set("bloodmoon.atmosphere.snapshot.thunder-duration", 1);
        data.set("bloodmoon.atmosphere.snapshot.storm-applied", true);
        data.set("bloodmoon.atmosphere.snapshot.thunder-applied", true);
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());

        BloodMoonManager manager = new BloodMoonManager(plugin);
        manager.load(tempDir.toFile());
        manager.close();

        assertFalse(manager.isPersisted());
    }

    @Test
    void legacyActiveStateWithoutAWorldIdStillMigratesOnce() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.set("bloodmoon.world-id", null);
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());
        when(atmosphere.isCurrentStateFormat()).thenReturn(false);

        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            assertSame(world, manager.getReferenceWorld());
        }
        manager.close();

        assertTrue(manager.isPersisted());
    }

    @Test
    void inactiveCorruptEventRestoresPendingWeatherWhenItsWorldLoads() throws Exception {
        YamlConfiguration data = persistedEventData();
        data.set("bloodmoon.world-id", "not-a-uuid");
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());
        when(atmosphere.isCurrentStateFormat()).thenReturn(true);
        when(atmosphere.snapshotWorldId()).thenReturn(OTHER_WORLD_ID);
        when(atmosphere.requiresWeatherRestore()).thenReturn(true);

        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        World snapshotWorld = mock(World.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(OTHER_WORLD_ID)).thenReturn(snapshotWorld);
            manager.recoverPendingAtmosphere();
        }
        manager.close();

        verify(atmosphere).recoverPendingWeather(snapshotWorld);
    }

    private static YamlConfiguration persistedEventData() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("bloodmoon.persisted", true);
        data.set("bloodmoon.persisted-day", 4L);
        data.set("bloodmoon.world-id", WORLD_ID.toString());
        return data;
    }

    private BloodMoonManager loadedManager() {
        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        manager.loadConfigValues(config);
        return manager;
    }
}
