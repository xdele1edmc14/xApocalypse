package com.deleted.xapocalypse;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceWatchdogTest {

    @Test
    void nonFiniteConfiguredThresholdFallsBackToSafeDefault() throws Exception {
        assertMalformedThresholdFallsBack(Double.NaN);
        assertMalformedThresholdFallsBack(Double.POSITIVE_INFINITY);
    }

    @Test
    void highConfiguredThresholdCanRecoverAtMaximumServerTps() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        when(config.getDouble("performance.tps-threshold", 15.0)).thenReturn(19.5);

        PerformanceWatchdog watchdog = new PerformanceWatchdog(plugin);
        Method checkPerformance = PerformanceWatchdog.class.getDeclaredMethod("checkPerformance");
        checkPerformance.setAccessible(true);

        try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getTPS)
                    .thenReturn(new double[]{18.0}, new double[]{20.0});
            bukkit.when(org.bukkit.Bukkit::getWorlds).thenReturn(List.of());

            checkPerformance.invoke(watchdog);
            assertTrue(watchdog.isSpawningPaused());

            checkPerformance.invoke(watchdog);
            assertFalse(watchdog.isSpawningPaused());
        }
    }

    @Test
    void thresholdAboveMaximumTpsIsClampedAndCanRecover() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        when(config.getDouble("performance.tps-threshold", 15.0)).thenReturn(25.0);

        PerformanceWatchdog watchdog = new PerformanceWatchdog(plugin);
        Method checkPerformance = PerformanceWatchdog.class.getDeclaredMethod("checkPerformance");
        checkPerformance.setAccessible(true);
        try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getTPS)
                    .thenReturn(new double[]{19.0}, new double[]{20.0});
            bukkit.when(org.bukkit.Bukkit::getWorlds).thenReturn(List.of());

            checkPerformance.invoke(watchdog);
            assertFalse(watchdog.isSpawningPaused());

            checkPerformance.invoke(watchdog);
            assertFalse(watchdog.isSpawningPaused());
        }
    }

    @Test
    void vanillaZombieDoesNotCauseManagedZombieCulling() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        xApocalypseUtils utils = mock(xApocalypseUtils.class);
        World world = mock(World.class);
        Zombie vanillaZombie = mock(Zombie.class);
        Zombie managedZombie = mock(Zombie.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getUtils()).thenReturn(utils);
        when(plugin.isWorldEnabled(world)).thenReturn(true);
        when(config.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        when(config.getDouble("performance.tps-threshold", 15.0)).thenReturn(15.0);
        when(config.getInt("performance.max-total-zombies", 300)).thenReturn(1);
        when(world.getEntitiesByClass(Zombie.class))
                .thenReturn(List.of(vanillaZombie, managedZombie));
        when(world.getPlayers()).thenReturn(List.of());
        when(utils.isCustomZombie(managedZombie)).thenReturn(true);
        when(utils.isManagedZombie(managedZombie)).thenReturn(true);

        PerformanceWatchdog watchdog = new PerformanceWatchdog(plugin);
        Method checkPerformance = PerformanceWatchdog.class.getDeclaredMethod("checkPerformance");
        checkPerformance.setAccessible(true);
        try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getTPS).thenReturn(new double[]{20.0});
            bukkit.when(org.bukkit.Bukkit::getWorlds).thenReturn(List.of(world));
            checkPerformance.invoke(watchdog);
        }

        verify(managedZombie, never()).remove();
    }

    @Test
    void lodPassSnapshotsPlayerCoordinatesOnceAndZombieCoordinatesOnce() {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        xApocalypseUtils utils = mock(xApocalypseUtils.class);
        World world = mock(World.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getUtils()).thenReturn(utils);
        when(config.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));

        Player first = playerAt(world, 0, 64, 0);
        Player second = playerAt(world, 100, 64, 100);
        Zombie firstZombie = zombieAt(world, 10, 64, 10);
        Zombie secondZombie = zombieAt(world, 20, 64, 20);
        when(utils.isManagedZombie(firstZombie)).thenReturn(true);
        when(utils.isManagedZombie(secondZombie)).thenReturn(true);
        List<Player> players = List.of(first, second);

        PerformanceWatchdog watchdog = new PerformanceWatchdog(plugin);
        watchdog.manageZombieAndShouldTick(firstZombie, players, 200L);
        watchdog.manageZombieAndShouldTick(secondZombie, players, 200L);

        verify(first, never()).getLocation();
        verify(second, never()).getLocation();
        verify(firstZombie, never()).getLocation();
        verify(secondZombie, never()).getLocation();
    }

    private static Player playerAt(World world, double x, double y, double z) {
        Player player = mock(Player.class);
        when(player.getLocation()).thenAnswer(i -> new Location(world, x, y, z));
        when(player.getX()).thenReturn(x);
        when(player.getY()).thenReturn(y);
        when(player.getZ()).thenReturn(z);
        return player;
    }

    private static void assertMalformedThresholdFallsBack(double threshold) throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong(anyString(), anyLong())).thenAnswer(i -> i.getArgument(1));
        when(config.getDouble("performance.tps-threshold", 15.0)).thenReturn(threshold);

        PerformanceWatchdog watchdog = new PerformanceWatchdog(plugin);
        Method checkPerformance = PerformanceWatchdog.class.getDeclaredMethod("checkPerformance");
        checkPerformance.setAccessible(true);

        try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getTPS)
                    .thenReturn(new double[]{10.0}, new double[]{20.0});
            bukkit.when(org.bukkit.Bukkit::getWorlds).thenReturn(List.of());

            checkPerformance.invoke(watchdog);
            assertTrue(watchdog.isSpawningPaused());

            checkPerformance.invoke(watchdog);
            assertFalse(watchdog.isSpawningPaused());
        }
    }

    private static Zombie zombieAt(World world, double x, double y, double z) {
        Zombie zombie = mock(Zombie.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(zombie.isValid()).thenReturn(true);
        when(zombie.getWorld()).thenReturn(world);
        when(zombie.getLocation()).thenAnswer(i -> new Location(world, x, y, z));
        when(zombie.getX()).thenReturn(x);
        when(zombie.getY()).thenReturn(y);
        when(zombie.getZ()).thenReturn(z);
        when(zombie.getPersistentDataContainer()).thenReturn(data);
        when(zombie.hasAI()).thenReturn(true);
        return zombie;
    }
}
