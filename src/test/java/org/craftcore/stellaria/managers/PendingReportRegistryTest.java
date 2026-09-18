package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void restoresBlankInputForTheSameActiveReportSession() {
        PendingReportRegistry registry = new PendingReportRegistry();
        UUID reporter = UUID.randomUUID();
        PendingReportRegistry.PendingReport pending = pending("チート");
        registry.put(reporter, pending);

        PendingReportRegistry.PendingReport taken = registry.take(reporter);

        assertTrue(registry.restoreIfCurrent(reporter, taken));
        assertEquals(pending, registry.take(reporter));
    }

    @Test
    void doesNotRestoreAfterQuitInvalidatesTheReportSession() {
        PendingReportRegistry registry = new PendingReportRegistry();
        UUID reporter = UUID.randomUUID();
        registry.put(reporter, pending("チート"));

        PendingReportRegistry.PendingReport taken = registry.take(reporter);
        registry.remove(reporter);

        assertFalse(registry.restoreIfCurrent(reporter, taken));
        assertNull(registry.take(reporter));
    }

    @Test
    void doesNotOverwriteANewReportWhenRestoringAnOlderSession() {
        PendingReportRegistry registry = new PendingReportRegistry();
        UUID reporter = UUID.randomUUID();
        PendingReportRegistry.PendingReport original = pending("チート");
        PendingReportRegistry.PendingReport replacement = pending("荒らし");
        registry.put(reporter, original);

        PendingReportRegistry.PendingReport taken = registry.take(reporter);
        registry.put(reporter, replacement);

        assertFalse(registry.restoreIfCurrent(reporter, taken));
        assertEquals(replacement, registry.take(reporter));
    }

    private static PendingReportRegistry.PendingReport pending(String category) {
        return new PendingReportRegistry.PendingReport(UUID.randomUUID(), category, "world", 1, 64, 2);
    }
}
