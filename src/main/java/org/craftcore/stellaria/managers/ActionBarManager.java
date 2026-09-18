package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 複数の機能が同時にアクションバー表示を要求しても、1行に連結して共存表示するためのマネージャー。
 * プレイヤーごとに「チャンネルID -> 表示内容」を保持し（{@link LinkedHashMap} なので挿入順=表示順、
 * 既存キーの更新は順序を変えない）、{@link #tick()} のたびに期限切れチャンネルを削除してから
 * {@code action-bar.separator} で連結して送信する。
 */
public class ActionBarManager {

    record ChannelEntry(Component content, long expiresAtMillis) {
        boolean isExpired(long now) {
            return expiresAtMillis >= 0 && now >= expiresAtMillis;
        }
    }

    private final StellariaCore plugin;
    private final Map<UUID, LinkedHashMap<String, ChannelEntry>> channels = new HashMap<>();
    private final Object channelLock = new Object();

    public ActionBarManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 無期限で表示し続けるチャンネルを設定/更新する（常設ステータス・TPAカウントダウンなど）。 */
    public void setChannel(Player player, String channelId, Component content) {
        if (!plugin.getConfigManager().getBoolean("action-bar.enabled", true)) {
            return;
        }
        synchronized (channelLock) {
            channelsFor(player).put(channelId, new ChannelEntry(content, -1));
        }
    }

    /** durationTicks 後に自動的に消えるチャンネルを設定する（AFK通知などの一時フラッシュ）。 */
    public void flash(Player player, String channelId, Component content, long durationTicks) {
        if (!plugin.getConfigManager().getBoolean("action-bar.enabled", true)) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + (durationTicks * 50L); // 1 tick = 50ms
        synchronized (channelLock) {
            channelsFor(player).put(channelId, new ChannelEntry(content, expiresAt));
        }
    }

    /**
     * チャンネルを即座に消す（TPAキャンセル時など）。
     * 全チャンネルが無くなった場合は、次のtick()を待たず空のアクションバーを即時送信する
     * — Minecraftのアクションバーは能動的に上書きしない限り一定時間表示され続けるため。
     */
    public void clearChannel(Player player, String channelId) {
        boolean clearActionBar = false;
        synchronized (channelLock) {
            LinkedHashMap<String, ChannelEntry> playerChannels = channels.get(player.getUniqueId());
            if (playerChannels != null && playerChannels.remove(channelId) != null && playerChannels.isEmpty()) {
                channels.remove(player.getUniqueId());
                clearActionBar = true;
            }
        }
        if (clearActionBar) {
            player.sendActionBar(Component.empty());
        }
    }

    /** プレイヤー退出時に呼ぶ。保持しているチャンネル情報を全て破棄する（メモリリーク防止）。 */
    public void removePlayer(UUID uuid) {
        synchronized (channelLock) {
            channels.remove(uuid);
        }
    }

    /** action-bar.update-interval-ticks ごとにグローバルリージョンスケジューラから呼ばれる想定。 */
    public void tick() {
        long now = System.currentTimeMillis();
        String separatorTemplate = plugin.getConfigManager().getString("action-bar.separator", " | ");
        Component separator = ColorUtil.component(separatorTemplate);

        for (Player player : Bukkit.getOnlinePlayers()) {
            List<ChannelEntry> snapshot;
            boolean clearActionBar;
            synchronized (channelLock) {
                LinkedHashMap<String, ChannelEntry> playerChannels = channels.get(player.getUniqueId());
                if (playerChannels == null || playerChannels.isEmpty()) {
                    continue;
                }
                int sizeBeforeExpiry = playerChannels.size();
                snapshot = expireAndSnapshot(playerChannels, now);
                clearActionBar = snapshot.isEmpty() && sizeBeforeExpiry > 0;
                if (snapshot.isEmpty()) {
                    channels.remove(player.getUniqueId());
                }
            }
            if (clearActionBar) {
                player.sendActionBar(Component.empty());
            } else if (!snapshot.isEmpty()) {
                player.sendActionBar(join(snapshot, separator));
            }
        }
    }

    /**
     * Removes expired entries and returns an immutable, insertion-ordered snapshot.
     * Callers must hold {@link #channelLock} while invoking this method.
     */
    static List<ChannelEntry> expireAndSnapshot(LinkedHashMap<String, ChannelEntry> playerChannels, long now) {
        playerChannels.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        return List.copyOf(playerChannels.values());
    }

    private LinkedHashMap<String, ChannelEntry> channelsFor(Player player) {
        return channels.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
    }

    private Component join(Iterable<ChannelEntry> entries, Component separator) {
        Component result = null;
        for (ChannelEntry entry : entries) {
            result = result == null ? entry.content() : result.append(separator).append(entry.content());
        }
        return result != null ? result : Component.empty();
    }
}
