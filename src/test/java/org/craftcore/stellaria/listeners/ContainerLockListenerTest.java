package org.craftcore.stellaria.listeners;

import org.bukkit.Material;
import org.craftcore.stellaria.managers.ContainerLock;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerLockListenerTest {

    @Test
    void selectsProtectedMessageForTheAffectedContainerType() {
        assertEquals("lock.protected_chest", ContainerLockListener.protectedMessageKey(Material.CHEST));
        assertEquals("lock.protected_chest", ContainerLockListener.protectedMessageKey(Material.TRAPPED_CHEST));
        assertEquals("lock.protected_barrel", ContainerLockListener.protectedMessageKey(Material.BARREL));
        assertEquals("lock.protected_shulker_box", ContainerLockListener.protectedMessageKey(Material.WHITE_SHULKER_BOX));
    }

    @Test
    void permitsOnlyTheOwnerOrBypassingAdminToDestroyALockedContainer() {
        UUID owner = UUID.randomUUID();
        ContainerLock lock = new ContainerLock(UUID.randomUUID(), owner,
                Set.of(new ContainerLock.BlockKey("world", 0, 64, 0)), Set.of(UUID.randomUUID()));

        assertTrue(ContainerLockListener.canDestroy(lock, owner, false));
        assertFalse(ContainerLockListener.canDestroy(lock, UUID.randomUUID(), false));
        assertTrue(ContainerLockListener.canDestroy(lock, UUID.randomUUID(), true));
    }

    @Test
    void confirmsPlacementOnlyWhenTheExpectedContainerStillExists() {
        assertTrue(ContainerLockListener.isPlacementConfirmed(Material.CHEST));
        assertTrue(ContainerLockListener.isPlacementConfirmed(Material.WHITE_SHULKER_BOX));
        assertFalse(ContainerLockListener.isPlacementConfirmed(Material.AIR));
        assertFalse(ContainerLockListener.isPlacementConfirmed(Material.STONE));
    }
}
