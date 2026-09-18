package org.craftcore.stellaria.managers;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 複数の機能が同時にボスバー表示を要求しても共存できるマネージャー。
 * アクションバーと違いボスバーは複数枚を同時表示できるため、プレイヤーごとに
 * 「チャンネルID -> 実体のBossBar」を保持し、チャンネルごとに1枚を出し入れする
 * （ActionBarManagerのように1行へ連結する必要はない）。
 */
public class BossBarManager {

    record ChannelEntry(BossBar bar, long expiresAtMillis) {
        boolean isExpired(long now) {
            return expiresAtMillis >= 0 && now >= expiresAtMillis;
        }
    }

    record ExpirySnapshot(List<BossBar> visibleBars, List<BossBar> expiredBars) {
    }

    private final StellariaCore plugin;
    private final Map<UUID, LinkedHashMap<String, ChannelEntry>> channels = new HashMap<>();
    private final Object channelLock = new Object();

    public BossBarManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 無期限で表示し続けるチャンネルを設定/更新する。 */
    public void setChannel(Player player, String channelId, Component title, BossBar.Color color, BossBar.Overlay overlay, float progress) {
        if (!plugin.getConfigManager().getBoolean("boss-bar.enabled", true)) {
            return;
        }
        upsert(player, channelId, title, color, overlay, progress, -1);
    }

    /** durationTicks 後に自動的に消えるチャンネルを設定する（警告・通知などの一時フラッシュ）。 */
    public void flash(Player player, String channelId, Component title, BossBar.Color color, BossBar.Overlay overlay, float progress, long durationTicks) {
        if (!plugin.getConfigManager().getBoolean("boss-bar.enabled", true)) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + (durationTicks * 50L); // 1 tick = 50ms
        upsert(player, channelId, title, color, overlay, progress, expiresAt);
    }

    /** チャンネルを即座に消す。 */
    public void clearChannel(Player player, String channelId) {
        ChannelEntry entry;
        synchronized (channelLock) {
            LinkedHashMap<String, ChannelEntry> playerChannels = channels.get(player.getUniqueId());
            if (playerChannels == null) {
                return;
            }
            entry = playerChannels.remove(channelId);
            if (playerChannels.isEmpty()) {
                channels.remove(player.getUniqueId());
            }
        }
        if (entry != null) {
            player.hideBossBar(entry.bar());
        }
    }

    /** プレイヤー退出時に呼ぶ。表示中のボスバーを全て隠してから情報を破棄する（メモリリーク防止）。 */
    public void removePlayer(UUID uuid) {
        List<ChannelEntry> snapshot;
        synchronized (channelLock) {
            LinkedHashMap<String, ChannelEntry> playerChannels = channels.remove(uuid);
            snapshot = playerChannels == null ? List.of() : List.copyOf(playerChannels.values());
        }
        if (snapshot.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            for (ChannelEntry entry : snapshot) {
                player.hideBossBar(entry.bar());
            }
        }
    }

    /** boss-bar.update-interval-ticks ごとにグローバルリージョンスケジューラから呼ばれる想定。期限切れチャンネルを消すだけ。 */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ExpirySnapshot snapshot;
            synchronized (channelLock) {
                LinkedHashMap<String, ChannelEntry> playerChannels = channels.get(player.getUniqueId());
                if (playerChannels == null || playerChannels.isEmpty()) {
                    continue;
                }
                snapshot = expireAndSnapshot(playerChannels, now);
                if (snapshot.visibleBars().isEmpty()) {
                    channels.remove(player.getUniqueId());
                }
            }
            snapshot.expiredBars().forEach(player::hideBossBar);
        }
    }

    /**
     * Removes expired entries and returns immutable bar snapshots. Callers must hold {@link #channelLock}.
     */
    static ExpirySnapshot expireAndSnapshot(LinkedHashMap<String, ChannelEntry> playerChannels, long now) {
        List<BossBar> expiredBars = new java.util.ArrayList<>();
        playerChannels.entrySet().removeIf(entry -> {
            if (!entry.getValue().isExpired(now)) {
                return false;
            }
            expiredBars.add(entry.getValue().bar());
            return true;
        });
        return new ExpirySnapshot(
                List.copyOf(playerChannels.values().stream().map(ChannelEntry::bar).toList()),
                List.copyOf(expiredBars)
        );
    }

    private void upsert(Player player, String channelId, Component title, BossBar.Color color, BossBar.Overlay overlay, float progress, long expiresAtMillis) {
        float clampedProgress = Math.max(0f, Math.min(1f, progress));
        BossBar newBar = null;
        synchronized (channelLock) {
            LinkedHashMap<String, ChannelEntry> playerChannels = channelsFor(player);
            ChannelEntry existing = playerChannels.get(channelId);
            BossBar bar;
            if (existing != null) {
                bar = existing.bar();
                bar.name(title);
                bar.color(color);
                bar.overlay(overlay);
                bar.progress(clampedProgress);
            } else {
                bar = BossBar.bossBar(title, clampedProgress, color, overlay);
                newBar = bar;
            }
            playerChannels.put(channelId, new ChannelEntry(bar, expiresAtMillis));
        }
        if (newBar != null) {
            player.showBossBar(newBar);
        }
    }

    private LinkedHashMap<String, ChannelEntry> channelsFor(Player player) {
        return channels.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
    }
}
