package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HordeSpawnerTaskTest {

    @Test
    void zeroDaySpawnChanceRejectsTheLowestPossibleRoll() {
        xApocalypse plugin = mock(xApocalypse.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        ThreadLocalRandom random = mock(ThreadLocalRandom.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("apocalypse-settings.day-spawn-chance", 0.0);

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isWorldEnabled(world)).thenReturn(true);
        when(plugin.isLobbyWorld(world)).thenReturn(false);
        when(player.getWorld()).thenReturn(world);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getName()).thenReturn("daytime-player");
        when(world.getTime()).thenReturn(6_000L);
        when(random.nextDouble()).thenReturn(0.0);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<ThreadLocalRandom> randoms = mockStatic(ThreadLocalRandom.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            randoms.when(ThreadLocalRandom::current).thenReturn(random);

            new HordeSpawnerTask(plugin).run();
        }

        verify(random).nextDouble();
        verify(plugin, never()).spawnZombiesNearPlayer(player, true);
    }

    @Test
    void fatalJvmErrorsEscapeTheRepeatingTask() {
        xApocalypse plugin = mock(xApocalypse.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("HordeSpawnerTaskTest"));
        AssertionError fatal = new AssertionError("fatal VM-level failure");
        doThrow(fatal).when(plugin).debugLog(anyString());

        assertThrows(AssertionError.class, () -> new HordeSpawnerTask(plugin).run());
    }
}
