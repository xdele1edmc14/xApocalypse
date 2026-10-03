package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImmunityLifecycleTest {

    @Test
    void expiredOfflinePlayerLeavesActiveScanButKeepsHealthRestorationState() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ImmunityLifecycleTest"));
        ImmunityManager immunity = new ImmunityManager(plugin, mock(MessageManager.class));
        UUID uuid = UUID.randomUUID();
        fieldValue(immunity, "immunePlayers", Set.class).add(uuid);
        fieldValue(immunity, "immunityEndTime", Map.class)
                .put(uuid, System.currentTimeMillis() - 1_000L);
        fieldValue(immunity, "originalHealth", Map.class).put(uuid, 20.0);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(20L), eq(20L)))
                .thenReturn(task);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
            immunity.startCheckTask();
            verify(scheduler).runTaskTimer(eq(plugin), runnable.capture(), eq(20L), eq(20L));
            runnable.getValue().run();
        }

        assertFalse(immunity.isImmune(uuid));
        assertTrue(fieldValue(immunity, "immunityEndTime", Map.class).containsKey(uuid));
        assertTrue(fieldValue(immunity, "originalHealth", Map.class).containsKey(uuid));
    }

    @SuppressWarnings("unchecked")
    private static <T> T fieldValue(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }
}
