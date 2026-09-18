package org.craftcore.stellaria.managers;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.HashMap;

public class PendingReportRegistry {
    private final Map<UUID, PendingReport> pending = new HashMap<>();
    private final Map<UUID, UUID> activeTokens = new HashMap<>();

    public record PendingReport(UUID targetUuid, String category, String world, int x, int y, int z, UUID sessionToken) {
        public PendingReport(UUID targetUuid, String category, String world, int x, int y, int z) {
            this(targetUuid, category, world, x, y, z, UUID.randomUUID());
        }
    }

    public synchronized void put(UUID reporterUuid, PendingReport report) {
        Objects.requireNonNull(reporterUuid, "reporterUuid");
        Objects.requireNonNull(report, "report");
        pending.put(reporterUuid, report);
        activeTokens.put(reporterUuid, report.sessionToken());
    }

    public synchronized PendingReport take(UUID reporterUuid) {
        return pending.remove(reporterUuid);
    }

    /** 同じ報告セッションがまだ有効で、別のpending reportが始まっていない時だけ復元する。 */
    public synchronized boolean restoreIfCurrent(UUID reporterUuid, PendingReport report) {
        if (report == null || !report.sessionToken().equals(activeTokens.get(reporterUuid)) || pending.containsKey(reporterUuid)) {
            return false;
        }
        pending.put(reporterUuid, report);
        return true;
    }

    /** 同じ報告セッションがまだ有効な時だけ、pendingとsession tokenを無効化する。 */
    public synchronized boolean removeIfCurrent(UUID reporterUuid, PendingReport report) {
        if (report == null || !report.sessionToken().equals(activeTokens.get(reporterUuid))) {
            return false;
        }
        pending.remove(reporterUuid);
        activeTokens.remove(reporterUuid);
        return true;
    }

    public synchronized void remove(UUID reporterUuid) {
        pending.remove(reporterUuid);
        activeTokens.remove(reporterUuid);
    }
}
