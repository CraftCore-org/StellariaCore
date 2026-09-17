package org.craftcore.stellaria.managers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PendingReportRegistry {
    private final Map<UUID, PendingReport> pending = new ConcurrentHashMap<>();

    public record PendingReport(UUID targetUuid, String category, String world, int x, int y, int z) { }

    public void put(UUID reporterUuid, PendingReport report) {
        pending.put(reporterUuid, report);
    }

    public PendingReport take(UUID reporterUuid) {
        return pending.remove(reporterUuid);
    }

    public void remove(UUID reporterUuid) {
        pending.remove(reporterUuid);
    }
}
