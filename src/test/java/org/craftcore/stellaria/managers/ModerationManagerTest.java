package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModerationManagerTest {

    @Test
    void normalizesPermanentAndLegacyNegativeBanExpiryToNull() {
        assertNull(ModerationManager.normalizeBanExpiry(null));
        assertNull(ModerationManager.normalizeBanExpiry(-1L));
        assertNull(ModerationManager.normalizeBanExpiry(-10L));
        assertEquals(1_000L, ModerationManager.normalizeBanExpiry(1_000L));
    }
}
