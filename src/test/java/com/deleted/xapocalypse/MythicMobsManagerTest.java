package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import io.lumine.mythic.bukkit.BukkitAPIHelper;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MythicMobsManagerTest {

    @Test
    void spawnIntervalIsClampedToAtLeastOneSecond() throws Exception {
        ManagerFixture fixture = fixture(1);

        Field field = MythicMobsManager.class.getDeclaredField("spawnTickInterval");
        field.setAccessible(true);
        assertEquals(20, field.getInt(fixture.manager));
    }

    @Test
    void placementRejectsUnloadedCandidatesBeforeSurfaceProbe() throws Exception {
        ManagerFixture fixture = fixture(100);
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);

        invokeFind(fixture.manager, new Location(world, 0, 70, 0));

        verify(fixture.spawner, never()).getSurfaceSpawnLocation(any(Location.class));
    }

    @Test
    void onePlacementSearchUsesAtMostEightTerrainProbes() throws Exception {
        ManagerFixture fixture = fixture(100);
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(fixture.spawner.getSurfaceSpawnLocation(any(Location.class))).thenReturn(null);

        invokeFind(fixture.manager, new Location(world, 0, 70, 0));

        verify(fixture.spawner, atMost(8)).getSurfaceSpawnLocation(any(Location.class));
    }

    @Test
    void validatedPlacementIsNotSurfaceProbedAgainDuringSpawn() throws Exception {
        ManagerFixture fixture = fixture(100);
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        BukkitAPIHelper api = mock(BukkitAPIHelper.class);
        setField(fixture.manager, "mythicMobsEnabled", true);
        setField(fixture.manager, "mmAPI", api);
        Method method = MythicMobsManager.class.getDeclaredMethod(
                "spawnMythicMob", Location.class, boolean.class);
        method.setAccessible(true);

        method.invoke(fixture.manager, new Location(world, 10.5, 65, 10.5), true);

        verify(fixture.spawner, never()).getSurfaceSpawnLocation(any(Location.class));
    }

    @Test
    void finalAllowedPlacementProbeCanUseVisibleTerrainFallback() throws Exception {
        ManagerFixture fixture = fixture(100);
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 70, 0));
        when(player.hasLineOfSight(any(Location.class))).thenReturn(true);
        when(fixture.spawner.getSurfaceSpawnLocation(any(Location.class)))
                .thenAnswer(i -> i.getArgument(0));

        Location result = invokeFind(
                fixture.manager, new Location(world, 0, 70, 0), List.of(player));

        assertNotNull(result);
    }

    private static Location invokeFind(
            MythicMobsManager manager, Location anchor, List<Player> players) throws Exception {
        Method method = MythicMobsManager.class.getDeclaredMethod(
                "findSpawnLocation", Location.class, int.class, int.class, boolean.class);
        method.setAccessible(true);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(players);
            return (Location) method.invoke(manager, anchor, 20, 40, false);
        }
    }

    private static void invokeFind(MythicMobsManager manager, Location anchor) throws Exception {
        invokeFind(manager, anchor, List.of());
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static ManagerFixture fixture(int interval) {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        PluginManager pluginManager = mock(PluginManager.class);
        UndeadSpawner spawner = mock(UndeadSpawner.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MythicMobsManagerTest"));
        when(plugin.getUndeadSpawner()).thenReturn(spawner);
        when(config.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(i ->
                i.getArgument(0, String.class).equals("mythicmobs.integration.spawn-tick-interval")
                        ? interval : i.getArgument(1));
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(i ->
                i.getArgument(0, String.class).equals("mythicmobs.integration.spawn-sound.enabled")
                        ? false : i.getArgument(1));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            MythicMobsManager manager = new MythicMobsManager(plugin);
            return new ManagerFixture(manager, spawner);
        }
    }

    private record ManagerFixture(MythicMobsManager manager, UndeadSpawner spawner) {
    }
}
