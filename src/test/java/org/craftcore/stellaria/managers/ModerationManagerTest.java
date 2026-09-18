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

    @Test
    void formatsPermanentAndTimedBanExpires() {
        assertEquals("永久", ModerationManager.formatBanExpires(null, 1_000L));
        assertEquals("1時間0分", ModerationManager.formatBanExpires(1_000L + 3_600_000L, 1_000L));
        assertEquals("3日0時間", ModerationManager.formatBanExpires(1_000L + 3L * 86_400_000L, 1_000L));
    }
}
