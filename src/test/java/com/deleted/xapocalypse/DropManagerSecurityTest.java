package com.deleted.xapocalypse;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DropManagerSecurityTest {

    @Test
    void extremeConfiguredAmountIsBoundedBeforeRewardRoll() throws Exception {
        assertArrayEquals(new int[]{1, 64}, parseAmount("1-2147483647"));
    }

    @Test
    void nonFiniteChanceCannotTurnIntoGuaranteedReward() throws Exception {
        assertEquals(0.0, clampChance(Double.NaN));
        assertEquals(0.0, parseChance("NaN"));
        assertEquals(0.0, parseChance("Infinity"));
        assertEquals(0.0, parseChance("-Infinity"));
    }

    @Test
    void nonItemMaterialIsRejectedBeforeDeathRewardRoll() {
        Material nonItem = mock(Material.class);
        when(nonItem.isAir()).thenReturn(false);
        when(nonItem.isItem()).thenReturn(false);

        assertTrue(loadDropEntries("WATER", "1", nonItem).isEmpty());
    }

    @Test
    void configuredAmountCannotExceedMaterialsStackLimit() throws Exception {
        Material lowStackItem = mock(Material.class);
        when(lowStackItem.isAir()).thenReturn(false);
        when(lowStackItem.isItem()).thenReturn(true);
        when(lowStackItem.getMaxStackSize()).thenReturn(16);

        List<?> entries = loadDropEntries("ENDER_PEARL", "64", lowStackItem);

        assertEquals(1, entries.size());
        assertEquals(16, dropEntryAmount(entries.getFirst(), "max"));
    }

    private List<?> loadDropEntries(String materialName, String amount, Material material) {
        xApocalypse plugin = mock(xApocalypse.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DropManagerSecurityTest"));
        when(config.getBoolean("zombie-settings.custom-drops.enabled", true)).thenReturn(true);
        when(config.getBoolean("zombie-settings.custom-drops.require-player-kill", true)).thenReturn(false);
        doReturn(List.of(Map.of(
                "material", materialName,
                "amount", amount,
                "chance", 1.0))).when(config).getList("zombie-settings.custom-drops.normal");

        try (MockedStatic<Material> materials = mockStatic(Material.class)) {
            materials.when(() -> Material.matchMaterial(materialName)).thenReturn(material);
            DropManager manager = new DropManager(plugin);
            Field field = DropManager.class.getDeclaredField("normalDrops");
            field.setAccessible(true);
            return List.copyOf((List<?>) field.get(manager));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private int dropEntryAmount(Object entry, String accessor) throws Exception {
        Method method = entry.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (int) method.invoke(entry);
    }

    private int[] parseAmount(Object amount) throws Exception {
        DropManager manager = mock(DropManager.class, CALLS_REAL_METHODS);
        Method method = DropManager.class.getDeclaredMethod("parseAmount", Object.class, String.class);
        method.setAccessible(true);
        return (int[]) method.invoke(manager, amount, "test.drop-table");
    }

    private double parseChance(Object chance) throws Exception {
        DropManager manager = mock(DropManager.class, CALLS_REAL_METHODS);
        Method method = DropManager.class.getDeclaredMethod("parseChance", Object.class);
        method.setAccessible(true);
        return (double) method.invoke(manager, chance);
    }

    private double clampChance(double chance) throws Exception {
        DropManager manager = mock(DropManager.class, CALLS_REAL_METHODS);
        Method method = DropManager.class.getDeclaredMethod("clampChance", double.class, String.class);
        method.setAccessible(true);
        return (double) method.invoke(manager, chance, "test.broadcast");
    }
}
