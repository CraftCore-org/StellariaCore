package org.craftcore.stellaria.commands;

import org.bukkit.Material;
import org.craftcore.stellaria.managers.ContainerLock;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void usesASeparateAlreadyLockedMessageForTheOwner() {
        UUID owner = UUID.randomUUID();
        ContainerLock lock = new ContainerLock(UUID.randomUUID(), owner,
                Set.of(new ContainerLock.BlockKey("world", 0, 64, 0)), Set.of());

        assertEquals("lock.already_locked_self", LockCommand.alreadyLockedMessageKey(lock, owner));
        assertEquals("lock.already_locked", LockCommand.alreadyLockedMessageKey(lock, UUID.randomUUID()));
    }
}
