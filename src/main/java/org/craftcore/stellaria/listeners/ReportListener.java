package org.craftcore.stellaria.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.PendingReportRegistry;

/** 報告詳細のチャット入力を通常の公開チャットより先に消費する。 */
public final class ReportListener implements Listener {

    private final StellariaCore plugin;

    public ReportListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player reporter = event.getPlayer();
        PendingReportRegistry.PendingReport pending =
                plugin.getReportManager().takePending(reporter.getUniqueId());

        if (pending == null) {
            return;
        }

        event.setCancelled(true);

        String reason = PlainTextComponentSerializer.plainText()
                .serialize(event.message())
                .trim();

        // 空入力なら、同じ報告セッションがまだ有効な場合だけ復元する
        if (reason.isEmpty()) {
            reporter.getScheduler().run(plugin, task -> {
                if (!reporter.isOnline()) {
                    return;
                }

                if (plugin.getReportManager()
                        .restorePendingIfCurrent(reporter.getUniqueId(), pending)) {
                    reporter.sendMessage(
                            plugin.getConfigManager()
                                    .getMessage("report.detail_required", reporter)
                    );
                }
            }, null);

            return;
        }

        /*
         * DBへ保存する前に、今処理しているpendingが
         * 「現在の報告セッション」かを確認して無効化する。
         *
         * 新しい/reportが既に開始されていた場合、
         * 古いpendingのsession tokenは一致しないためfalseになり、
         * 古い報告は保存されない。
         */
        if (!plugin.getReportManager()
                .claimCompletion(reporter.getUniqueId(), pending)) {
            return;
        }

        plugin.getReportManager().completeReport(reporter, pending, reason);

        reporter.getScheduler().run(plugin, task -> {
            if (!reporter.isOnline()) {
                return;
            }

            reporter.sendMessage(
                    plugin.getConfigManager()
                            .getMessage("report.submitted", reporter)
            );
        }, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getReportManager()
                .clearPending(event.getPlayer().getUniqueId());
    }
}
