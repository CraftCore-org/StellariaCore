package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActiveBanRegistryTest {
    @Test
    void returnsPermanentBanWithoutExpiry() {
        ActiveBanRegistry registry = new ActiveBanRegistry();
        UUID target = UUID.randomUUID();
        registry.put(new ActiveBanRegistry.BanEntry(1, target, null, "reason", 100L, null));

        assertNotNull(registry.getActive(target, 200L));
    }

    @Test
    void removesExpiredBanFromCacheButReturnsItForHistoryPersistence() {
        ActiveBanRegistry registry = new ActiveBanRegistry();
        UUID target = UUID.randomUUID();
        registry.put(new ActiveBanRegistry.BanEntry(1, target, null, "reason", 100L, 150L));

        assertNull(registry.getActive(target, 150L));
        assertNull(registry.getActive(target, 200L));
    }
}
