package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldResetManagerTest {

    @Test
    void resetCannotCompleteWhenContainerLockCleanupFails() {
        assertFalse(WorldResetManager.shouldCompleteReset(false));
        assertTrue(WorldResetManager.shouldCompleteReset(true));
    }

    @Test
    void pendingLockCleanupIsRetriedEvenWhenWorldResetIsDisabled() {
        assertTrue(WorldResetManager.shouldRetryPendingLockCleanup(false, true));
        assertFalse(WorldResetManager.shouldRetryPendingLockCleanup(false, false));
    }
}
