package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class ImmunityLifecycleTest {

    private static final NamespacedKey ORIGINAL_HEALTH_KEY =
            new NamespacedKey("xapocalypse", "immunity_original_health");

    @Test
    void joinRestoresHealthFromDurableMarkerWhenDataFileStateIsMissing() {
        xApocalypse plugin = mock(xApocalypse.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ImmunityLifecycleTest"));
        ImmunityManager immunity = new ImmunityManager(plugin, mock(MessageManager.class));

        Player player = mock(Player.class);
        AttributeInstance maxHealth = mock(AttributeInstance.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        AtomicReference<Double> baseValue = new AtomicReference<>(10.0);
        UUID uuid = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(player.getHealth()).thenReturn(5.0);
        when(maxHealth.getBaseValue()).thenAnswer(ignored -> baseValue.get());
        doAnswer(invocation -> {
            baseValue.set(invocation.getArgument(0));
            return null;
        }).when(maxHealth).setBaseValue(org.mockito.ArgumentMatchers.anyDouble());
        when(data.has(ORIGINAL_HEALTH_KEY, PersistentDataType.DOUBLE)).thenReturn(true);
        when(data.get(ORIGINAL_HEALTH_KEY, PersistentDataType.DOUBLE)).thenReturn(20.0);

        immunity.recoverOrphanedHealth(player, maxHealth);

        assertEquals(20.0, baseValue.get());
        verify(data).remove(ORIGINAL_HEALTH_KEY);
    }

    @Test
    void joinLeavesCustomHealthAloneWithoutDurableMarkerOrSavedImmunity() {
        xApocalypse plugin = mock(xApocalypse.class);
        ImmunityManager immunity = new ImmunityManager(plugin, mock(MessageManager.class));
        Player player = mock(Player.class);
        AttributeInstance maxHealth = mock(AttributeInstance.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);

        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(maxHealth.getBaseValue()).thenReturn(10.0);

        immunity.recoverOrphanedHealth(player, maxHealth);

        verify(maxHealth, never()).setBaseValue(org.mockito.ArgumentMatchers.anyDouble());
        verify(data, never()).remove(ORIGINAL_HEALTH_KEY);
    }

    @Test
    void grantingImmunityRecordsOriginalHealthInPlayerData() {
        xApocalypse plugin = mock(xApocalypse.class);
        ImmunityManager immunity = new ImmunityManager(plugin, mock(MessageManager.class));
        Player player = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        AtomicReference<Double> storedHealth = new AtomicReference<>();
        when(player.getPersistentDataContainer()).thenReturn(data);
        doAnswer(invocation -> {
            storedHealth.set(invocation.getArgument(2));
            return null;
        }).when(data).set(ORIGINAL_HEALTH_KEY, PersistentDataType.DOUBLE, 20.0);

        immunity.rememberOriginalHealth(player, 20.0);

        assertEquals(20.0, storedHealth.get());
    }

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
