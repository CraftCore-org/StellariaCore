package org.craftcore.stellaria.managers;

import net.dv8tion.jda.api.EmbedBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.UUID;

/** 通報の入力待ち状態を保持し、確定した通報を永続化する。 */
public class ReportManager {

    private final StellariaCore plugin;
    private final PendingReportRegistry pendingReports = new PendingReportRegistry();

    public ReportManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** カテゴリ選択時点の位置を同期的に記録し、続くチャット入力を待機する。 */
    public void beginReport(Player reporter, UUID targetUuid, String category) {
        Location location = reporter.getLocation();
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        pendingReports.put(reporter.getUniqueId(), new PendingReportRegistry.PendingReport(
            targetUuid, category, world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), UUID.randomUUID()
        ));
    }

    public PendingReportRegistry.PendingReport takePending(UUID reporterUuid) {
        return pendingReports.take(reporterUuid);
    }

    public boolean restorePendingIfCurrent(UUID reporterUuid, PendingReportRegistry.PendingReport pending) {
        return pendingReports.restoreIfCurrent(reporterUuid, pending);
    }

    public boolean claimCompletion(
            UUID reporterUuid,
            PendingReportRegistry.PendingReport pending
    ) {
        return pendingReports.removeIfCurrent(reporterUuid, pending);
    }

    /** 完了した報告と同じsession tokenが有効な時だけ無効化し、新しい報告を消さない。 */
    public void completePending(UUID reporterUuid, PendingReportRegistry.PendingReport pending) {
        pendingReports.removeIfCurrent(reporterUuid, pending);
    }

    public void completeReportAsync(
            UUID reporterUuid,
            String reporterName,
            PendingReportRegistry.PendingReport pending,
            String targetName,
            String reason,
            java.util.function.Consumer<Boolean> callback
    ) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            int changed = DatabaseManager.execute(
                    "INSERT INTO reports "
                            + "(reporter_uuid, target_uuid, category, reason, world, x, y, z, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    reporterUuid.toString(),
                    pending.targetUuid().toString(),
                    pending.category(),
                    reason,
                    pending.world(),
                    pending.x(),
                    pending.y(),
                    pending.z(),
                    System.currentTimeMillis()
            );

            if (changed <= 0) {
                callback.accept(false);
                return;
            }

            plugin.getDiscordBotManager().sendReportLog(
                    new EmbedBuilder()
                            .setTitle("REPORT")
                            .addField("報告者", reporterName, true)
                            .addField("対象", targetName, true)
                            .addField("カテゴリ", pending.category(), true)
                            .addField("理由", reason, false)
                            .addField(
                                    "場所",
                                    pending.world()
                                            + " ("
                                            + pending.x()
                                            + ", "
                                            + pending.y()
                                            + ", "
                                            + pending.z()
                                            + ")",
                                    false
                            )
            );

            callback.accept(true);
        });
    }

    public void clearPending(UUID reporterUuid) {
        pendingReports.remove(reporterUuid);
    }
}
