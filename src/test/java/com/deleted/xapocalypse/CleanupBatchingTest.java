package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CleanupBatchingTest {

    @Test
    void reloadDeduplicatesChunksAndProcessesOnlyEightPerTick() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        xApocalypseUtils utils = mock(xApocalypseUtils.class);
        MythicMobsManager mythic = mock(MythicMobsManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Queue<Runnable> scheduled = new ArrayDeque<>();
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isEnabled()).thenReturn(true);
        when(config.getBoolean("bloodmoon.despawn-on-end", true)).thenReturn(true);
        when(world.getUID()).thenReturn(worldId);

        Chunk[] unique = new Chunk[12];
        for (int i = 0; i < unique.length; i++) {
            Chunk chunk = mock(Chunk.class);
            when(chunk.getWorld()).thenReturn(world);
            when(chunk.getX()).thenReturn(i);
            when(chunk.getZ()).thenReturn(i + 20);
            when(world.isChunkLoaded(i, i + 20)).thenReturn(true);
            when(world.getChunkAt(i, i + 20)).thenReturn(chunk);
            unique[i] = chunk;
        }
        Chunk[] duplicated = new Chunk[24];
        System.arraycopy(unique, 0, duplicated, 0, 12);
        System.arraycopy(unique, 0, duplicated, 12, 12);
        when(world.getLoadedChunks()).thenReturn(duplicated);
        setField(plugin, "utils", utils);
        setField(plugin, "mythicMobsManager", mythic);
        when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return null;
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            Method cleanup = xApocalypse.class.getDeclaredMethod(
                    "cleanupExpiredBloodMoonEntitiesInLoadedChunks");
            cleanup.setAccessible(true);
            cleanup.invoke(plugin);

            assertFalse(scheduled.isEmpty(), "reload cleanup must be deferred into bounded ticks");
            scheduled.remove().run();
            verify(utils, atMost(8)).cleanupExpiredBloodMoonZombies(any(Chunk.class));
            while (!scheduled.isEmpty()) scheduled.remove().run();
        }

        verify(utils, times(12)).cleanupExpiredBloodMoonZombies(any(Chunk.class));
        verify(mythic, times(12)).cleanupExpiredBloodMoonMutants(any(Chunk.class));
    }

    @Test
    void bloodMoonZombieRemovalUsesUuidBatchesOfTen() {
        xApocalypse plugin = configuredPlugin();
        xApocalypseUtils utils = new xApocalypseUtils(plugin);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Queue<Runnable> scheduled = new ArrayDeque<>();
        World world = mock(World.class);
        List<Zombie> zombies = new ArrayList<>();

        for (int i = 0; i < 25; i++) {
            Zombie zombie = mock(Zombie.class);
            PersistentDataContainer data = mock(PersistentDataContainer.class);
            UUID uuid = UUID.randomUUID();
            when(zombie.getUniqueId()).thenReturn(uuid);
            when(zombie.getPersistentDataContainer()).thenReturn(data);
            when(data.has(xApocalypseUtils.BLOOD_MOON_KEY, PersistentDataType.BYTE)).thenReturn(true);
            when(zombie.getLocation()).thenReturn(new Location(world, i, 64, i));
            zombies.add(zombie);
        }
        when(world.getEntitiesByClass(Zombie.class)).thenReturn(zombies);
        when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(1));
            return null;
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            for (Zombie zombie : zombies) {
                bukkit.when(() -> Bukkit.getEntity(zombie.getUniqueId())).thenReturn(zombie);
            }
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            try {
                utils.despawnBloodMoonZombies();
            } catch (LinkageError ignored) {
                // The old synchronous implementation touches Paper's registry-backed Sound
                // constants before scheduling anything. The scheduling assertion below is the
                // behavior under test; a live Paper server supplies those registries.
            }
            assertFalse(scheduled.isEmpty(), "entity removal must be deferred into UUID batches");
            scheduled.remove().run();
            for (Zombie zombie : zombies) verify(zombie, atMost(1)).remove();
            long removedAfterFirstTick = zombies.stream()
                    .filter(zombie -> org.mockito.Mockito.mockingDetails(zombie)
                            .getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("remove")))
                    .count();
            assertTrue(removedAfterFirstTick <= 10);
            while (!scheduled.isEmpty()) scheduled.remove().run();
        }

        for (Zombie zombie : zombies) verify(zombie).remove();
    }

    private static xApocalypse configuredPlugin() {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CleanupBatchingTest"));
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.isWorldEnabled(any(World.class))).thenReturn(true);
        when(config.getStringList(anyString())).thenReturn(List.of());
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        return plugin;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = xApocalypse.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
