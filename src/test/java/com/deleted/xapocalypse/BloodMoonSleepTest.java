package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BloodMoonSleepTest {

    private static final UUID WORLD_ID = UUID.fromString("12345678-1234-5678-9abc-123456789abc");

    @TempDir
    Path tempDir;

    @Test
    void activeWorldAttemptIsCancelledAndResetsPhantomTimer() {
        ListenerFixture fixture = new ListenerFixture();
        when(fixture.bloodMoon.shouldBlockSleep(fixture.world)).thenReturn(true);
        when(fixture.bloodMoon.shouldResetPhantomTimer()).thenReturn(true);
        when(fixture.messages.get("bloodmoon.sleep-blocked")).thenReturn("blocked");

        fixture.listener.onPlayerBedEnter(fixture.event);

        verify(fixture.event).setCancelled(true);
        verify(fixture.player).sendMessage("blocked");
        verify(fixture.player).setStatistic(Statistic.TIME_SINCE_REST, 0);
    }

    @Test
    void phantomResetCanBeDisabledWithoutAllowingSleep() {
        ListenerFixture fixture = new ListenerFixture();
        when(fixture.bloodMoon.shouldBlockSleep(fixture.world)).thenReturn(true);
        when(fixture.bloodMoon.shouldResetPhantomTimer()).thenReturn(false);
        when(fixture.messages.get("bloodmoon.sleep-blocked")).thenReturn("blocked");

        fixture.listener.onPlayerBedEnter(fixture.event);

        verify(fixture.event).setCancelled(true);
        verify(fixture.player, never()).setStatistic(
                org.mockito.ArgumentMatchers.eq(Statistic.TIME_SINCE_REST), anyInt());
    }

    @Test
    void unrelatedWorldAttemptIsUntouched() {
        ListenerFixture fixture = new ListenerFixture();
        when(fixture.bloodMoon.shouldBlockSleep(fixture.world)).thenReturn(false);

        fixture.listener.onPlayerBedEnter(fixture.event);

        verify(fixture.event, never()).setCancelled(true);
        verify(fixture.player, never()).sendMessage(org.mockito.ArgumentMatchers.anyString());
        verify(fixture.player, never()).setStatistic(
                org.mockito.ArgumentMatchers.eq(Statistic.TIME_SINCE_REST), anyInt());
    }

    @Test
    void managerBlocksOnlyThePersistedBloodMoonWorld() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        BloodMoonAtmosphereManager atmosphere = mock(BloodMoonAtmosphereManager.class);
        World activeWorld = mock(World.class);
        World otherWorld = mock(World.class);
        UUID otherId = UUID.fromString("99999999-8888-7777-6666-555555555555");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BloodMoonSleepTest"));
        when(plugin.isWorldEnabled(activeWorld)).thenReturn(true);
        when(plugin.isWorldEnabled(otherWorld)).thenReturn(true);
        when(activeWorld.getUID()).thenReturn(WORLD_ID);
        when(activeWorld.getTime()).thenReturn(14_000L);
        when(activeWorld.getFullTime()).thenReturn(4L * 24_000L + 14_000L);
        when(otherWorld.getUID()).thenReturn(otherId);

        YamlConfiguration data = new YamlConfiguration();
        data.set("bloodmoon.persisted", true);
        data.set("bloodmoon.persisted-day", 4L);
        data.set("bloodmoon.world-id", WORLD_ID.toString());
        data.save(tempDir.resolve("BloodMoonData.yml").toFile());

        YamlConfiguration config = new YamlConfiguration();
        config.set("bloodmoon.enabled", true);
        config.set("bloodmoon.interval-days", 4);
        config.set("bloodmoon.start-sound.enabled", false);
        config.set("bloodmoon.warning.enabled", false);
        config.set("bloodmoon.warning.sound.enabled", false);
        config.set("bloodmoon.sleep.blocked", true);
        config.set("bloodmoon.sleep.reset-phantom-timer", true);

        BloodMoonManager manager = new BloodMoonManager(plugin, atmosphere);
        manager.load(tempDir.toFile());
        manager.loadConfigValues(config);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(WORLD_ID)).thenReturn(activeWorld);
            assertTrue(manager.shouldBlockSleep(activeWorld));
            assertFalse(manager.shouldBlockSleep(otherWorld));
        }
        assertTrue(manager.shouldResetPhantomTimer());
        manager.close();
    }

    private static final class ListenerFixture {
        private final xApocalypse plugin = mock(xApocalypse.class);
        private final BloodMoonManager bloodMoon = mock(BloodMoonManager.class);
        private final MessageManager messages = mock(MessageManager.class);
        private final World world = mock(World.class);
        private final Player player = mock(Player.class);
        private final PlayerBedEnterEvent event = mock(PlayerBedEnterEvent.class);
        private final xApocalypseListener listener;

        private ListenerFixture() {
            when(plugin.getBloodMoon()).thenReturn(bloodMoon);
            when(plugin.getMessages()).thenReturn(messages);
            when(plugin.getUtils()).thenReturn(mock(xApocalypseUtils.class));
            when(plugin.getImmunity()).thenReturn(mock(ImmunityManager.class));
            when(plugin.getScent()).thenReturn(mock(ScentManager.class));
            when(plugin.getHordeManager()).thenReturn(mock(HordeManager.class));
            when(event.getPlayer()).thenReturn(player);
            when(player.getWorld()).thenReturn(world);
            listener = new xApocalypseListener(plugin);
        }
    }
}
