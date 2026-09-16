package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * vanish状態をインメモリで管理する（再ログインで解除、DB永続化しない）。
 * Bukkit標準の Player#hidePlayer/showPlayer で可視性を制御する
 * （タブリスト・エンティティ表示を同時に扱える標準APIなので、PacketEventsは使わない）。
 * stellaria.vanish 権限を持つ他プレイヤーからは常に見える。
 */
public class VanishManager {

    private static final String ACTION_BAR_CHANNEL = "vanish";

    private final StellariaCore plugin;
    private final Set<UUID> vanished = new HashSet<>();

    public VanishManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    /** vanish状態をトグルする。トグル後の状態（true=ON）を返す。 */
    public boolean toggle(Player player) {
        if (vanished.remove(player.getUniqueId())) {
            reveal(player);
            return false;
        }
        vanished.add(player.getUniqueId());
        hide(player);
        return true;
    }

    private void hide(Player vanishedPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(vanishedPlayer) || viewer.hasPermission("stellaria.vanish")) {
                continue;
            }
            viewer.hidePlayer(plugin, vanishedPlayer);
        }
        plugin.getActionBarManager().setChannel(vanishedPlayer, ACTION_BAR_CHANNEL,
                ColorUtil.component(plugin.getConfigManager().getMessage("vanish.action-bar", vanishedPlayer)));
    }

    private void reveal(Player vanishedPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showPlayer(plugin, vanishedPlayer);
        }
        plugin.getActionBarManager().clearChannel(vanishedPlayer, ACTION_BAR_CHANNEL);
    }

    /** 新規参加者に、既にvanish中の（権限を持たない）プレイヤーを非表示にする。PlayerJoinListenerから呼ぶ。 */
    public void syncVisibilityForJoiningPlayer(Player joining) {
        if (joining.hasPermission("stellaria.vanish")) {
            return;
        }
        for (UUID uuid : vanished) {
            Player vanishedPlayer = Bukkit.getPlayer(uuid);
            if (vanishedPlayer != null && !vanishedPlayer.equals(joining)) {
                joining.hidePlayer(plugin, vanishedPlayer);
            }
        }
    }

    /** 退出時のクリーンアップ。PlayerListenerから呼ぶ。 */
    public void removePlayer(UUID uuid) {
        vanished.remove(uuid);
    }
}
