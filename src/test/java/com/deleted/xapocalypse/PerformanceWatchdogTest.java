package com.deleted.xapocalypse;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceWatchdogTest {

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
        when(utils.isCustomZombie(firstZombie)).thenReturn(true);
        when(utils.isCustomZombie(secondZombie)).thenReturn(true);
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
