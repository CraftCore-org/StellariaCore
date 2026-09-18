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
        PendingReportRegistry.PendingReport pending = plugin.getReportManager().takePending(reporter.getUniqueId());
        if (pending == null) {
            return;
        }

        event.setCancelled(true);
        String reason = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (reason.isEmpty()) {
            reporter.getScheduler().run(plugin, task -> {
                if (!reporter.isOnline()) {
                    return;
                }
                if (plugin.getReportManager().restorePendingIfCurrent(reporter.getUniqueId(), pending)) {
                    reporter.sendMessage(plugin.getConfigManager().getMessage("report.detail_required", reporter));
                }
            }, null);
            return;
        }

        plugin.getReportManager().completeReport(reporter, pending, reason);
        plugin.getReportManager().completePending(reporter.getUniqueId(), pending);
        reporter.getScheduler().run(plugin, task -> reporter.sendMessage(
                plugin.getConfigManager().getMessage("report.submitted", reporter)), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getReportManager().clearPending(event.getPlayer().getUniqueId());
    }
}
