package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerLockManagerTest {

    @Test
    void doubleChestKeysResolveToTheSameCachedLock() {
        ContainerLockManager manager = new ContainerLockManager();
        ContainerLock.BlockKey left = key(0);
        ContainerLock.BlockKey right = key(1);
        ContainerLock lock = lock(left, right);

        manager.registerLoadedLock(lock);

        assertSame(manager.findCached(left).orElseThrow(), manager.findCached(right).orElseThrow());
    }

    @Test
    void detachingOneDoubleChestHalfKeepsTheRemainingHalfLocked() {
        ContainerLockManager manager = new ContainerLockManager();
        ContainerLock.BlockKey left = key(0);
        ContainerLock.BlockKey right = key(1);
        manager.registerLoadedLock(lock(left, right));

        assertTrue(manager.detachFromCache(left));
        assertTrue(manager.findCached(left).isEmpty());
        assertTrue(manager.findCached(right).isPresent());
    }

    @Test
    void detachingTheLastBlockRemovesTheLockFromBothIndexes() {
        ContainerLockManager manager = new ContainerLockManager();
        ContainerLock.BlockKey only = key(0);
        ContainerLock lock = lock(only);
        manager.registerLoadedLock(lock);

        assertTrue(manager.detachFromCache(only));
        assertTrue(manager.findCached(only).isEmpty());
        assertTrue(manager.findCached(lock.lockId()).isEmpty());
    }

    @Test
    void bypassIsDisabledByDefaultAndTogglesPerPlayer() {
        ContainerLockManager manager = new ContainerLockManager();
        UUID admin = UUID.randomUUID();

        assertTrue(!manager.hasBypassEnabled(admin));
        assertTrue(manager.toggleBypass(admin));
        assertTrue(manager.hasBypassEnabled(admin));
        assertTrue(!manager.toggleBypass(admin));
        assertTrue(!manager.hasBypassEnabled(admin));
    }

    private static ContainerLock lock(ContainerLock.BlockKey... blocks) {
        return new ContainerLock(UUID.randomUUID(), UUID.randomUUID(), Set.of(blocks), Set.of());
    }

    private static ContainerLock.BlockKey key(int x) {
        return new ContainerLock.BlockKey("world", x, 64, 0);
    }
}
