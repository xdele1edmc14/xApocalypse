package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XApocalypseCommandTest {

    @Test
    void itemAmountUsesTheOptionalThirdArgument() {
        assertEquals(2, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "2"}));
    }

    @Test
    void itemAmountDefaultsToOneAndNeverExceedsAStack() {
        assertEquals(1, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player"}));
        assertEquals(1, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "nope"}));
        assertEquals(1, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "0"}));
        assertEquals(64, xApocalypseCommand.parseItemAmount(new String[]{"zombie_guts", "Player", "99"}));
    }

    @Test
    void adminSpawnNeverExceedsRemainingWorldCapacity() {
        CommandFixture fixture = commandFixture(30, 28);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(fixture.playerId)).thenReturn(fixture.player);
            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "horde", "25", "5"});
            fixture.command.runAdminSpawnTick();
        }

        verify(fixture.world, atMost(2)).spawnEntity(any(Location.class), eq(EntityType.ZOMBIE));
    }

    @Test
    void adminSpawnPerformsAtMostTenAttemptsInTheCommandTick() {
        CommandFixture fixture = commandFixture(100, 0);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(fixture.playerId)).thenReturn(fixture.player);
            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "horde", "25", "5"});
            fixture.command.runAdminSpawnTick();
        }

        verify(fixture.spawner, atMost(10)).getSurfaceSpawnLocation(any(Location.class));
    }

    @Test
    void concurrentAdminSpawnJobsShareOneGlobalTenProbeBudget() {
        CommandFixture fixture = commandFixture(100, 0);
        Player secondPlayer = mock(Player.class);
        UUID secondId = UUID.randomUUID();
        when(secondPlayer.getUniqueId()).thenReturn(secondId);
        when(secondPlayer.hasPermission(anyString())).thenReturn(true);
        when(secondPlayer.getWorld()).thenReturn(fixture.world);
        when(secondPlayer.getLocation()).thenAnswer(invocation -> new Location(fixture.world, 5, 70, 5));
        when(secondPlayer.isOnline()).thenReturn(true);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(fixture.playerId)).thenReturn(fixture.player);
            bukkit.when(() -> Bukkit.getPlayer(secondId)).thenReturn(secondPlayer);

            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "horde", "25", "5"});
            fixture.command.onCommand(secondPlayer, mock(Command.class), "xa",
                    new String[]{"spawn", "horde", "25", "5"});
            fixture.command.runAdminSpawnTick();
        }

        verify(fixture.spawner, atMost(10)).getSurfaceSpawnLocation(any(Location.class));
    }

    @Test
    void onePlayerCannotStackDuplicateAdminSpawnJobs() {
        CommandFixture fixture = commandFixture(100, 0);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "horde", "25", "5"});
            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "horde", "25", "5"});
        }

        verify(scheduler, times(1)).runTaskTimer(
                eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L));
        verify(fixture.player).sendMessage(argThat((String message) -> message.contains("already")));
    }

    @Test
    void mutantAdminSpawnRunsAtMostOneExpensiveAttemptPerTick() {
        CommandFixture fixture = commandFixture(300, 0);
        when(fixture.mythic.isMythicMobsEnabled()).thenReturn(true);
        when(fixture.mythic.trySpawnMutantCommand(fixture.player, 5))
                .thenReturn(MythicMobsManager.CommandSpawnResult.SPAWNED);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(fixture.playerId)).thenReturn(fixture.player);
            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "mutant", "25", "5"});

            verify(fixture.mythic, never()).trySpawnMutantCommand(fixture.player, 5);
            fixture.command.runAdminSpawnTick();
            verify(fixture.mythic, times(1)).trySpawnMutantCommand(fixture.player, 5);
        }
    }

    @Test
    void adminSpawnRetriesInvalidTerrainWithoutConsumingRequestedCount() {
        CommandFixture fixture = commandFixture(100, 0);
        Location safe = new Location(fixture.world, 4, 70, 4);
        when(fixture.spawner.getSurfaceSpawnLocation(any(Location.class)))
                .thenReturn(null, null, safe, safe);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(fixture.playerId)).thenReturn(fixture.player);
            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "miner", "2", "5"});
            fixture.command.runAdminSpawnTick();
        }

        verify(fixture.world, times(2)).spawnEntity(any(Location.class), eq(EntityType.ZOMBIE));
    }

    @Test
    void adminSpawnReportsSpawnedAndRequestedCountsWhenTerrainRetriesRunOut() {
        CommandFixture fixture = commandFixture(100, 0);
        when(fixture.spawner.getSurfaceSpawnLocation(any(Location.class))).thenReturn(null);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(fixture.plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(fixture.playerId)).thenReturn(fixture.player);
            fixture.command.onCommand(fixture.player, mock(Command.class), "xa",
                    new String[]{"spawn", "miner", "2", "5"});
            fixture.command.runAdminSpawnTick();
        }

        verify(fixture.player).sendMessage(argThat((String message) -> message.contains("0/2")));
    }

    private static CommandFixture commandFixture(int cap, int existing) {
        xApocalypse plugin = mock(xApocalypse.class);
        MessageManager messages = mock(MessageManager.class);
        BloodMoonManager bloodMoon = mock(BloodMoonManager.class);
        MythicMobsManager mythic = mock(MythicMobsManager.class);
        UndeadSpawner spawner = mock(UndeadSpawner.class);
        xApocalypseUtils utils = mock(xApocalypseUtils.class);
        FileConfiguration config = mock(FileConfiguration.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        Zombie zombie = mock(Zombie.class);
        UUID playerId = UUID.randomUUID();

        when(plugin.getMessages()).thenReturn(messages);
        when(plugin.getBloodMoon()).thenReturn(bloodMoon);
        when(plugin.getMythicMobsManager()).thenReturn(mythic);
        when(plugin.getUndeadSpawner()).thenReturn(spawner);
        when(plugin.getUtils()).thenReturn(utils);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isEnabled()).thenReturn(true);
        when(config.getInt("performance.max-total-zombies", 300)).thenReturn(cap);
        List<Zombie> existingZombies = new ArrayList<>();
        for (int i = 0; i < existing; i++) existingZombies.add(mock(Zombie.class));
        when(world.getEntitiesByClass(Zombie.class)).thenReturn(existingZombies);
        when(world.spawnEntity(any(Location.class), eq(EntityType.ZOMBIE))).thenReturn(zombie);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(invocation -> new Location(world, 0, 70, 0));
        when(spawner.getSurfaceSpawnLocation(any(Location.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        return new CommandFixture(new xApocalypseCommand(plugin), plugin, player, playerId,
                world, spawner, mythic);
    }

    private record CommandFixture(
            xApocalypseCommand command, xApocalypse plugin, Player player, UUID playerId,
            World world, UndeadSpawner spawner, MythicMobsManager mythic) {
    }
}
