package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AFK（離席）状態の管理。手動トグル（/afk）とタイムアウト自動判定の両方から
 * {@link #setAfk} を呼ぶ。状態はTPAリクエストと同様インメモリのみ（永続化なし）。
 * UtilsPlugin の AFKManager を StellariaCore の流儀（Folia対応スケジューラ・
 * ConfigManager経由のメッセージ）に合わせて移植したもの。
 */
public class AfkManager {

    private final StellariaCore plugin;
    private final Set<UUID> afkPlayers = new HashSet<>();
    private final Map<UUID, Long> lastActivityMillis = new HashMap<>();

    public AfkManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public boolean isAfk(UUID uuid) {
        return afkPlayers.contains(uuid);
    }

    /** 移動・インタラクト等の操作を検知した時に呼ぶ。AFK中だったら自動的に復帰させる。 */
    public void updateActivity(Player player) {
        lastActivityMillis.put(player.getUniqueId(), System.currentTimeMillis());
        if (afkPlayers.contains(player.getUniqueId())) {
            setAfk(player, false);
        }
    }

    /** /afk コマンドやタイムアウト検知から呼ぶ。状態が実際に変わった時だけ通知を出す。 */
    public void setAfk(Player player, boolean afk) {
        UUID uuid = player.getUniqueId();
        if (afk == afkPlayers.contains(uuid)) {
            return;
        }
        if (afk) {
            afkPlayers.add(uuid);
        } else {
            afkPlayers.remove(uuid);
        }
        lastActivityMillis.put(uuid, System.currentTimeMillis());
        broadcastStateChange(player, afk);
    }

    public void removePlayer(UUID uuid) {
        afkPlayers.remove(uuid);
        lastActivityMillis.remove(uuid);
    }

    /** afk.enabled が true の間、10秒毎にグローバルリージョンスケジューラから呼ばれる想定。 */
    public void tick() {
        long timeoutMillis = plugin.getConfigManager().getInt("afk.timeout-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Long lastActivity = lastActivityMillis.get(uuid);
            if (lastActivity == null) {
                lastActivityMillis.put(uuid, now);
                continue;
            }
            if (!afkPlayers.contains(uuid) && now - lastActivity > timeoutMillis) {
                setAfk(player, true);
            }
        }
    }

    private void broadcastStateChange(Player player, boolean afk) {
        String path = afk ? "afk.became" : "afk.returned";
        String message = plugin.getConfigManager().getMessage(path, player);
        Bukkit.broadcast(ColorUtil.component(message));

        String actionBarPath = afk ? "afk.became_actionbar" : "afk.returned_actionbar";
        String actionBarMessage = plugin.getConfigManager().getMessage(actionBarPath, player);
        long durationTicks = plugin.getConfigManager().getInt("afk.actionbar-flash-seconds", 3) * 20L;
        plugin.getActionBarManager().flash(player, "afk_flash", ColorUtil.component(actionBarMessage), durationTicks);
    }
}
