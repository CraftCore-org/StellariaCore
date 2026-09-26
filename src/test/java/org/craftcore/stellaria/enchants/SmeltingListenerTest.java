package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmeltingListenerTest {

    @Test
    void rawOresAndAncientDebrisAreSmeltTargets() {
        assertTrue(SmeltingListener.isSmeltTarget(Material.RAW_IRON));
        assertTrue(SmeltingListener.isSmeltTarget(Material.RAW_GOLD));
        assertTrue(SmeltingListener.isSmeltTarget(Material.RAW_COPPER));
        assertTrue(SmeltingListener.isSmeltTarget(Material.ANCIENT_DEBRIS));
        assertTrue(SmeltingListener.isSmeltTarget(Material.IRON_ORE));
    }

    @Test
    void nonOreDropsAreNotSmeltTargets() {
        assertFalse(SmeltingListener.isSmeltTarget(Material.COBBLESTONE));
        assertFalse(SmeltingListener.isSmeltTarget(Material.COBBLED_DEEPSLATE));
        assertFalse(SmeltingListener.isSmeltTarget(Material.SAND));
        assertFalse(SmeltingListener.isSmeltTarget(Material.DIAMOND));
    }
}
