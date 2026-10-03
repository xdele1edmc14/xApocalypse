package com.deleted.xapocalypse;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HordeManagerTest {

    @Test
    void oneNaturalCycleSharesTenActualTerrainProbesAcrossPlayers() {
        xApocalypse plugin = pluginWithHordeSize(30);
        UndeadSpawner spawner = mock(UndeadSpawner.class);
        Player first = eligiblePlayer("first");
        Player second = eligiblePlayer("second");
        HordeManager manager = new HordeManager(plugin, mock(xApocalypseUtils.class), spawner);

        manager.spawnZombiesNearPlayer(first, false);
        manager.spawnZombiesNearPlayer(second, false);

        verify(spawner, atMost(10)).getSurfaceSpawnLocation(any(Location.class));
    }

    @Test
    void fullRiseAnimationCapacityStopsBeforeAnyTerrainProbe() throws Exception {
        xApocalypse plugin = pluginWithHordeSize(30);
        UndeadSpawner spawner = new UndeadSpawner(plugin, mock(xApocalypseUtils.class));
        Field animations = UndeadSpawner.class.getDeclaredField("activeAnimationEntities");
        animations.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<UUID> active = (HashSet<UUID>) animations.get(spawner);
        for (int i = 0; i < 50; i++) active.add(UUID.randomUUID());

        World world = mock(World.class);
        when(world.getEntitiesByClass(Zombie.class)).thenReturn(List.of());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getName()).thenReturn("world");
        Player player = eligiblePlayer("capacity", world);

        new HordeManager(plugin, mock(xApocalypseUtils.class), spawner)
                .spawnZombiesNearPlayer(player, false);

        verify(world, never()).getHighestBlockYAt(anyInt(), anyInt(), any(org.bukkit.HeightMap.class));
    }

    private static xApocalypse pluginWithHordeSize(int size) {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.equals("apocalypse-settings.base-horde-size")) return size;
            if (path.equals("apocalypse-settings.horde-variance")) return 0;
            if (path.equals("apocalypse-settings.max-single-horde-size")) return size;
            return invocation.getArgument(1);
        });
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (path.equals("scent-system.enabled")) return false;
            if (path.equals("apocalypse-settings.rising-animation")) return true;
            return invocation.getArgument(1);
        });
        return plugin;
    }

    private static Player eligiblePlayer(String name) {
        World world = mock(World.class);
        when(world.getEntitiesByClass(Zombie.class)).thenReturn(List.of());
        when(world.getName()).thenReturn("world");
        return eligiblePlayer(name, world);
    }

    private static Player eligiblePlayer(String name, World world) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(invocation -> new Location(world, 0, 70, 0));
        return player;
    }
}
