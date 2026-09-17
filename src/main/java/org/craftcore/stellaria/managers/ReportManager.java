package org.craftcore.stellaria.managers;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.UUID;

/** 通報の入力待ち状態を保持し、確定した通報を永続化する。 */
public class ReportManager {

    private final PendingReportRegistry pendingReports = new PendingReportRegistry();

    public ReportManager(StellariaCore plugin) {
        // 他のマネージャーと同じ生成契約を維持する。現在はプラグイン参照を保持する必要がない。
    }

    /** カテゴリ選択時点の位置を同期的に記録し、続くチャット入力を待機する。 */
    public void beginReport(Player reporter, UUID targetUuid, String category) {
        Location location = reporter.getLocation();
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        pendingReports.put(reporter.getUniqueId(), new PendingReportRegistry.PendingReport(
            targetUuid, category, world, location.getBlockX(), location.getBlockY(), location.getBlockZ()
        ));
    }

    public PendingReportRegistry.PendingReport takePending(UUID reporterUuid) {
        return pendingReports.take(reporterUuid);
    }

    public void restorePending(UUID reporterUuid, PendingReportRegistry.PendingReport pending) {
        pendingReports.put(reporterUuid, pending);
    }

    public void completeReport(Player reporter, PendingReportRegistry.PendingReport pending, String reason) {
        DatabaseManager.execute(
            "INSERT INTO reports (reporter_uuid, target_uuid, category, reason, world, x, y, z, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            reporter.getUniqueId().toString(), pending.targetUuid().toString(), pending.category(), reason,
            pending.world(), pending.x(), pending.y(), pending.z(), System.currentTimeMillis()
        );
    }

    public void clearPending(UUID reporterUuid) {
        pendingReports.remove(reporterUuid);
    }
}
