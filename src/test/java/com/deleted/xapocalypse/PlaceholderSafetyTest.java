package com.deleted.xapocalypse;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaceholderSafetyTest {

    @Test
    void bloodMoonPlaceholderDoesNotQueryLiveWorldState() {
        xApocalypse plugin = mock(xApocalypse.class);
        BloodMoonManager bloodMoon = mock(BloodMoonManager.class);
        when(plugin.getBloodMoon()).thenReturn(bloodMoon);
        when(bloodMoon.getDaysUntilNextBloodMoon())
                .thenThrow(new AssertionError("Placeholder callback touched live Bukkit world state"));
        when(bloodMoon.getCachedDaysUntilNextBloodMoon()).thenReturn(7);
        xApocalypsePlaceholderExpansion expansion = new xApocalypsePlaceholderExpansion(plugin);

        String result = assertDoesNotThrow(
                () -> expansion.onRequest(null, "bloodmoon_days_left"));

        assertEquals("7", result);
    }

    @Test
    void placeholderVisiblePlayerStateUsesConcurrentCollections() throws Exception {
        xApocalypse plugin = mock(xApocalypse.class);
        ScentManager scent = new ScentManager(plugin);
        ImmunityManager immunity = new ImmunityManager(plugin, mock(MessageManager.class));

        Map<UUID, Double> playerScent = fieldValue(scent, "playerScent");
        Set<UUID> immunePlayers = fieldValue(immunity, "immunePlayers");
        Map<UUID, Long> immunityEndTime = fieldValue(immunity, "immunityEndTime");

        assertInstanceOf(ConcurrentMap.class, playerScent);
        assertTrue(immunePlayers.spliterator().hasCharacteristics(Spliterator.CONCURRENT));
        assertInstanceOf(ConcurrentMap.class, immunityEndTime);
    }

    @Test
    void remainingImmunityDurationReadsPublishedExpiry() throws Exception {
        ImmunityManager immunity = new ImmunityManager(
                mock(xApocalypse.class), mock(MessageManager.class));
        UUID uuid = UUID.randomUUID();
        Set<UUID> immunePlayers = fieldValue(immunity, "immunePlayers");
        Map<UUID, Long> immunityEndTime = fieldValue(immunity, "immunityEndTime");
        immunityEndTime.put(uuid, System.currentTimeMillis() + 60_000L);
        immunePlayers.add(uuid);

        long remaining = immunity.getRemainingSeconds(uuid);

        assertTrue(remaining >= 59L && remaining <= 60L);
    }

    @SuppressWarnings("unchecked")
    private <T> T fieldValue(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }
}
