package org.craftcore.stellaria.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.List;

/**
 * config.yml の broadcast.auto.* に設定された複数メッセージを、一定間隔で順番に
 * 全プレイヤーへ送信する。各メッセージはプレイヤーごとに PlaceholderManager で解決してから
 * 送信するので、%ping% のような視聴者依存トークンも正しく出る。
 * /stellariareload で {@link #restart()} すると、インデックスをリセットして仕切り直す。
 * UtilsPlugin の AutoBroadcastManager を Folia対応スケジューラに置き換えて移植したもの。
 */
public class AutoBroadcastManager {

    private final StellariaCore plugin;
    private int currentIndex = 0;
    private ScheduledTask task;

    public AutoBroadcastManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 既存タスクがあれば止めてから、config.yml の設定に従って（有効なら）再登録する。 */
    public void start() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }

        if (!plugin.getConfigManager().getBoolean("broadcast.auto.enabled", false)) {
            return;
        }

        List<String> messages = plugin.getConfigManager().getStringList("broadcast.auto.messages");
        if (messages.isEmpty()) {
            plugin.getLogger().warning("broadcast.auto.messages が空のため、定期放送は開始しません。");
            return;
        }

        long intervalTicks = plugin.getConfigManager().getInt("broadcast.auto.interval-minutes", 5) * 60L * 20L;
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> sendNext(), intervalTicks, intervalTicks);
    }

    /** /stellariareload から呼ばれる想定。インデックスを0に戻してから start() する。 */
    public void restart() {
        currentIndex = 0;
        start();
    }

    private void sendNext() {
        List<String> messages = plugin.getConfigManager().getStringList("broadcast.auto.messages");
        if (messages.isEmpty()) {
            return;
        }
        if (currentIndex >= messages.size()) {
            currentIndex = 0;
        }

        String rawMessage = messages.get(currentIndex);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            String resolved = plugin.getPlaceholderManager().resolve(rawMessage, viewer);
            // notifySound=false: 全員分ループして個別送信するので、trueにすると同じ相手に
            // メンション通知音がオンライン人数分連続で鳴ってしまう。
            viewer.sendMessage(plugin.getMentionService().highlightBroadcast(resolved, true, false));
        }
        currentIndex = (currentIndex + 1) % messages.size();
    }
}
