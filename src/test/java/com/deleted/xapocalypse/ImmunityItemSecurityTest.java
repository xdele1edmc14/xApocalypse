package com.deleted.xapocalypse;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImmunityItemSecurityTest {
    private static final String GUTS_NAME = "§2Zombie Guts";

    private MessageManager messages;
    private ImmunityManager immunity;

    @BeforeEach
    void setUp() {
        messages = mock(MessageManager.class);
        when(messages.get("immunity.item-name")).thenReturn(GUTS_NAME);
        immunity = new ImmunityManager(mock(xApocalypse.class), messages);
    }

    @Test
    void renamedRottenFleshCannotForgeZombieGuts() {
        ItemStack renamedFlesh = itemWithMarker(null);
        when(renamedFlesh.getItemMeta().hasDisplayName()).thenReturn(true);
        when(renamedFlesh.getItemMeta().getDisplayName()).thenReturn(GUTS_NAME);

        assertFalse(immunity.isZombieGutsItem(renamedFlesh));
    }

    @Test
    void onlyTheExactPluginMarkerAuthenticatesZombieGuts() {
        assertTrue(immunity.isZombieGutsItem(itemWithMarker((byte) 1)));
        assertFalse(immunity.isZombieGutsItem(itemWithMarker((byte) 0)));
    }

    private ItemStack itemWithMarker(Byte marker) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.ROTTEN_FLESH);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(xApocalypseUtils.ZOMBIE_GUTS_KEY, PersistentDataType.BYTE))
                .thenReturn(marker != null);
        when(data.get(xApocalypseUtils.ZOMBIE_GUTS_KEY, PersistentDataType.BYTE)).thenReturn(marker);
        return item;
    }
}
