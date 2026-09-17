package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingReportRegistryTest {
    @Test
    void takeReturnsAndClearsTheReporterPendingState() {
        PendingReportRegistry registry = new PendingReportRegistry();
        UUID reporter = UUID.randomUUID();
        PendingReportRegistry.PendingReport pending =
                new PendingReportRegistry.PendingReport(UUID.randomUUID(), "チート", "world", 1, 64, 2);
        registry.put(reporter, pending);

        assertEquals(pending, registry.take(reporter));
        assertNull(registry.take(reporter));
    }
}
