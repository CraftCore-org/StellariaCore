package org.craftcore.stellaria.commands;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockCommandTest {

    @Test
    void acceptsOnlyRequestedContainerMaterials() {
        assertTrue(LockCommand.isActionableTarget(Material.CHEST));
        assertTrue(LockCommand.isActionableTarget(Material.TRAPPED_CHEST));
        assertTrue(LockCommand.isActionableTarget(Material.BARREL));
        assertTrue(LockCommand.isActionableTarget(Material.WHITE_SHULKER_BOX));

        assertFalse(LockCommand.isActionableTarget(Material.HOPPER));
        assertFalse(LockCommand.isActionableTarget(Material.ENDER_CHEST));
        assertFalse(LockCommand.isActionableTarget(Material.STONE));
    }
}
